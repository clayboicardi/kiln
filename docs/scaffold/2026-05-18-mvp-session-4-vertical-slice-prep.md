# MVP Session 4-7 Vertical-Slice Prep — Interface Sketches

**Date:** 2026-05-18 (Post-Pre-MVP-Research; pre-MVP-Session-4 synthesis)
**Author:** Claude Opus 4.7 (1M context) for Clay Haworth
**Status:** Sketches — actual `.kt` files land at MVP Session 4-7. Bounded the same way as the schema sketch: define the shape; let the implementing session refine.
**Authoritative sources:**
- Locked spec §3.3 (Source Protocol), §3.4 (Concentric Modules): [`docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md`](../superpowers/specs/2026-05-18-kiln-rebuild-design.md)
- Locked plan §3.2 Sessions 4-7: [`docs/superpowers/plans/2026-05-18-kiln-execution-plan.md`](../superpowers/plans/2026-05-18-kiln-execution-plan.md)
- Vetting log Items 9, 13 + addendum: [`docs/decisions/2026-05-18-library-vetting.md`](../decisions/2026-05-18-library-vetting.md)
- Schema sketch: [`docs/decisions/2026-05-18-sqldelight-schema-sketch.md`](../decisions/2026-05-18-sqldelight-schema-sketch.md)
- Session 1-3 prep: [`./2026-05-18-mvp-session-1-prep.md`](./2026-05-18-mvp-session-1-prep.md)

This document sketches the load-bearing interfaces and data classes for MVP Session 4-7 (Library + playback vertical slice). The goal is to capture the **shape** of the contracts that embody spec §3.3 (Source Protocol) and §3.4 (Concentric Modules) invariants while context from Pre-MVP Research is fresh. The implementing session refines the bytes; this document keeps the architecture coherent.

---

## 1. What MVP Session 4-7 produces

Per plan §3.2 (revised 2026-05-18 to include FLAC decoder work):

- `MusicSource` interface in `:data:library/commonMain` — first instantiation of spec §3.3 Source Protocol
- `LocalLibrarySource` implementation (Android + Desktop adapters)
- Library scan workflow (MediaStore on Android; `Files.walk` + jaudiotagger on Desktop)
- SQLDelight `.sq` files per [schema sketch](../decisions/2026-05-18-sqldelight-schema-sketch.md)
- `PlatformPlayer` interface in `:audio:playback/commonMain` — first instantiation of spec §13 / vetting Item 13 engine-swap-shaped boundary
- `Media3ExoPlayerImpl` (Android adapter)
- `JavaSoundPlayerImpl` (Desktop adapter)
- `Decoder` + `DecodedStream` interfaces
- `Media3DecoderImpl` (Android — delegates to Media3 decoder library)
- `JvmFlacDecoderImpl` (Desktop — JNA bridge to vendored Xiph libFLAC 1.5.0 BSD-3) per vetting Item 9 addendum
- Minimal wire-up: `kotlin-inject` graph wires the source + player + decoder; a single button somewhere plays a track end-to-end
- **Vertical-slice milestone:** play a FLAC from Clay's library on both platforms

Effort: ~30-45 hrs (revised from 20-30 hrs after Item 9 addendum added the JNA-libFLAC bridge work).

---

## 2. Source Protocol — `MusicSource` interface

Spec §3.3 gives the skeleton. This section expands it with full supporting types. **The implementing session may refine names and minor shape; the invariant is "no source-specific branching anywhere in the codebase."**

### 2.1 Core interface

```kotlin
// :data:library/src/commonMain/kotlin/com/clayworks/kiln/library/source/MusicSource.kt
package com.clayworks.kiln.library.source

import arrow.core.Either
import kotlinx.coroutines.flow.Flow

interface MusicSource {
    val id: SourceId
    val displayName: String
    val capabilities: SourceCapabilities

    suspend fun search(query: String, limit: Int = 50): Flow<SearchResult>
    suspend fun browse(scope: BrowseScope): Flow<MediaItem>
    suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable>

    /**
     * Hint to the source that we want the most up-to-date metadata.
     * Local sources may trigger a rescan; network sources may bypass cache.
     * Returns when the refresh completes (or is denied).
     */
    suspend fun refresh(): Either<SourceError, Unit> = Either.Right(Unit)
}
```

**Invariants enforced by this shape:**

- `id` lets us reference the source persistently (`track.source = "local"` in the SQLDelight `source` column per schema)
- `capabilities` replaces the spec's `supportsDownload` / `supportsStreaming` booleans with a richer structure (see §2.5)
- `Either<SourceError, X>` for fallible operations is the **Arrow showcase entry point** outside `:audio:dsp` — search + browse return Flows that can emit errors as values; `getPlayable` returns `Either` because it's a single-point-failure operation
- `refresh()` has a default impl so non-local sources don't have to think about it

### 2.2 Value classes for IDs

```kotlin
// :data:library/src/commonMain/kotlin/com/clayworks/kiln/library/source/Ids.kt
package com.clayworks.kiln.library.source

@JvmInline
value class SourceId(val value: String)  // 'local', 'subsonic-1', 'navidrome-foo', ...

@JvmInline
value class ItemId(val value: String)    // opaque to consumers; source decodes internally

@JvmInline
value class AlbumId(val value: Long)     // SQLDelight rowid for local; opaque string for network sources

@JvmInline
value class ArtistId(val value: Long)

@JvmInline
value class PlaylistId(val value: Long)

@JvmInline
value class TrackId(val value: Long)
```

`@JvmInline value class` gives compile-time type safety without runtime cost — the underlying String / Long is the actual representation. Bus-Factor-of-One: passing the wrong ID type to the wrong function is a compile error.

### 2.3 BrowseScope — the browse query language

```kotlin
// :data:library/src/commonMain/kotlin/com/clayworks/kiln/library/source/BrowseScope.kt
package com.clayworks.kiln.library.source

sealed interface BrowseScope {
    /** All tracks. Use sortOrder + paging. */
    data class AllTracks(
        val sortOrder: TrackSort = TrackSort.TitleAsc,
        val pageSize: Int = 100,
        val pageOffset: Int = 0,
    ) : BrowseScope

    /** All albums. */
    data class AllAlbums(
        val sortOrder: AlbumSort = AlbumSort.ArtistThenAlbum,
        val pageSize: Int = 100,
        val pageOffset: Int = 0,
    ) : BrowseScope

    /** All artists. */
    data class AllArtists(
        val pageSize: Int = 100,
        val pageOffset: Int = 0,
    ) : BrowseScope

    /** All playlists. */
    data object AllPlaylists : BrowseScope

    /** Tracks in a specific album, ordered by disc + track number. */
    data class TracksOfAlbum(val albumId: AlbumId) : BrowseScope

    /** Tracks by a specific artist (per-track artist, not album-artist). */
    data class TracksOfArtist(val artistId: ArtistId) : BrowseScope

    /** Albums by a specific artist (album-artist). */
    data class AlbumsOfArtist(val artistId: ArtistId) : BrowseScope

    /** Tracks in a playlist, ordered by playlist position. */
    data class TracksOfPlaylist(val playlistId: PlaylistId) : BrowseScope

    /** "Recently added" view from the listening_history side. */
    data class RecentlyAdded(val pageSize: Int = 50) : BrowseScope

    /** "Recently played" via track.last_played_ms DESC. */
    data class RecentlyPlayed(val pageSize: Int = 50) : BrowseScope

    /** "Most played" via track.play_count DESC. */
    data class MostPlayed(val pageSize: Int = 50) : BrowseScope
}

enum class TrackSort { TitleAsc, TitleDesc, AddedDesc, PlayCountDesc, LastPlayedDesc, AlbumArtistTrack }
enum class AlbumSort { ArtistThenAlbum, AlbumName, YearDesc, RecentlyAdded }
```

Each `BrowseScope` maps to a SQLDelight query per the schema sketch §3-§4. Adding new browse modes is a matter of adding a `data class` variant + a query — the Source Protocol stays stable.

### 2.4 MediaItem, Playable, SearchResult

```kotlin
// :data:library/src/commonMain/kotlin/com/clayworks/kiln/library/source/MediaItem.kt
package com.clayworks.kiln.library.source

/**
 * Lightweight reference for browse + queue display. Does NOT carry a resolved playable
 * resource — that's [Playable], acquired via [MusicSource.getPlayable].
 *
 * MediaItem is what populates list UIs, queue entries, mini-player surfaces.
 * Hundreds-of-thousands of these may exist in memory; keep small.
 */
data class MediaItem(
    val itemId: ItemId,
    val sourceId: SourceId,
    val kind: Kind,                         // Track, Album, Artist, Playlist
    val title: String,
    val subtitle: String? = null,           // e.g., "Artist • Album" for tracks
    val durationMs: Long? = null,           // null for non-track kinds
    val artUri: String? = null,             // resolved by Coil
    val metadata: Map<String, String> = emptyMap(),  // codec, year, etc. — for display
) {
    enum class Kind { Track, Album, Artist, Playlist }
}

/**
 * Resolved playable resource. Acquired via [MusicSource.getPlayable].
 * For local sources, this is a file path. For future network sources, a stream URL.
 */
data class Playable(
    val itemId: ItemId,
    val sourceId: SourceId,
    val uri: String,                        // file:// for local, https:// for network
    val codec: AudioCodec,
    val sampleRateHz: Int,
    val bitDepth: Int?,                     // null for lossy
    val channels: Int,
    val bitrateKbps: Int?,                  // null for lossless variable
    val durationMs: Long,
    val replayGain: ReplayGain?,            // per spec §6.1 JAMZ-parity requirement
    val cachedLocally: Boolean = true,      // future-proofing for streaming sources
)

data class ReplayGain(
    val trackDb: Double? = null,
    val trackPeak: Double? = null,
    val albumDb: Double? = null,
    val albumPeak: Double? = null,
)

enum class AudioCodec { FLAC, MP3, ALAC, OGG_VORBIS, OGG_OPUS, WAV, AAC, UNKNOWN }

data class SearchResult(
    val item: MediaItem,
    val matchScore: Double = 1.0,           // for FTS5 bm25 ranking; 1.0 = perfect
    val matchedField: MatchedField? = null, // for UI highlight: title, artist, album, etc.
) {
    enum class MatchedField { Title, AlbumName, ArtistName, AlbumArtistName, PlaylistName }
}
```

### 2.5 Capability flags — the polymorphism alternative to source-type discrimination

Per spec §3.3: "No source-specific branching anywhere in the codebase — if `if (source is LocalLibrarySource)` appears, the interface is wrong."

The way to write Kiln features that need to vary by source without violating this is **capability flags + polymorphic dispatch**.

```kotlin
// :data:library/src/commonMain/kotlin/com/clayworks/kiln/library/source/SourceCapabilities.kt
package com.clayworks.kiln.library.source

data class SourceCapabilities(
    // Discovery
    val canSearch: Boolean = true,
    val canBrowseByAlbum: Boolean = true,
    val canBrowseByArtist: Boolean = true,
    val canBrowseByPlaylist: Boolean = true,
    val canBrowseByGenre: Boolean = false,
    val canBrowseRecentlyAdded: Boolean = true,
    val canBrowseRecentlyPlayed: Boolean = true,
    val canBrowseMostPlayed: Boolean = true,

    // Acquisition
    val sourceType: SourceType,
    val supportsOfflineCache: Boolean = false,  // streaming sources only
    val supportsDownload: Boolean = false,       // network sources only

    // User-data
    val supportsLikedTracks: Boolean = false,    // local: future feature; network: subsonic-style
    val supportsListeningHistory: Boolean = true,
    val supportsUserPlaylists: Boolean = true,
    val supportsScrobbleExport: Boolean = false, // Last.fm — out of scope per anti-roadmap §11

    // Playback hints
    val supportsScrubbing: Boolean = true,       // can seek mid-track
    val supportsGaplessPlayback: Boolean = true,
)

enum class SourceType {
    LOCAL,              // file-backed library on the device
    NETWORK_LOSSLESS,   // Subsonic/Navidrome with FLAC fetch
    NETWORK_LOSSY,      // Spotify/Apple Music (not on Kiln roadmap; SourceType present for completeness)
}

/** Canonical LocalLibrarySource capability set. */
val LocalSourceCapabilities = SourceCapabilities(
    sourceType = SourceType.LOCAL,
    canBrowseByGenre = false,           // MVP: denormalized; Phase 2a if a "browse by genre" view ships
    supportsLikedTracks = false,        // MVP: future feature; flip when implemented
    supportsListeningHistory = true,
    supportsUserPlaylists = true,
    supportsScrubbing = true,
    supportsGaplessPlayback = true,
)
```

**How features should consume capabilities (no `is` checks):**

```kotlin
// Bad — Source Protocol violation:
if (source is LocalLibrarySource) { /* show genre browser */ }

// Good — capability check:
if (source.capabilities.canBrowseByGenre) { /* show genre browser */ }
```

A reviewer's "if I see `is XxxSource` in this PR, what's the right correction?" answer: usually add a capability flag.

### 2.6 SourceError — sealed Arrow Either return type

```kotlin
// :data:library/src/commonMain/kotlin/com/clayworks/kiln/library/source/SourceError.kt
package com.clayworks.kiln.library.source

sealed interface SourceError {
    /** The item ID is unknown to this source. */
    data class ItemNotFound(val itemId: ItemId) : SourceError

    /** The underlying file/URL is missing or unreachable. */
    data class ResourceUnavailable(val reason: String) : SourceError

    /** The source is temporarily offline (network-only). */
    data class SourceUnavailable(val reason: String) : SourceError

    /** Underlying I/O or parsing failure. */
    data class IoError(val cause: Throwable) : SourceError

    /** Catch-all for source-internal errors. */
    data class Internal(val message: String, val cause: Throwable? = null) : SourceError
}
```

Consumers handle errors via Arrow's `Either.fold` or pattern-match on the sealed interface:

```kotlin
when (val result = source.getPlayable(item.itemId)) {
    is Either.Left -> when (val err = result.value) {
        is SourceError.ItemNotFound -> showSnack("Track not in library anymore")
        is SourceError.ResourceUnavailable -> showSnack("File missing: ${err.reason}")
        // ...
    }
    is Either.Right -> player.loadQueue(listOf(result.value))
}
```

---

## 3. LocalLibrarySource — the only MVP implementation

```kotlin
// :data:library/src/commonMain/kotlin/com/clayworks/kiln/library/source/LocalLibrarySource.kt
package com.clayworks.kiln.library.source

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import arrow.core.Either
import com.clayworks.kiln.library.db.KilnDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow

/**
 * The local FLAC-library [MusicSource]. Backed by SQLDelight per the schema sketch.
 *
 * Scanner population is the [LibraryScanner] responsibility (see §6); this class
 * only reads from the indexed database.
 */
class LocalLibrarySource(
    private val db: KilnDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val scanner: LibraryScanner,
) : MusicSource {
    override val id = SourceId("local")
    override val displayName = "Local Library"
    override val capabilities = LocalSourceCapabilities

    override suspend fun search(query: String, limit: Int): Flow<SearchResult> {
        val ftsQuery = sanitizeFtsQuery(query)
        return db.trackSearchQueries
            .searchTracks(ftsQuery, limit.toLong())
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.map(::toSearchResult) }
    }

    override suspend fun browse(scope: BrowseScope): Flow<MediaItem> = when (scope) {
        is BrowseScope.AllTracks -> db.trackQueries
            .allTracks(scope.sortOrder.toSql(), scope.pageSize.toLong(), scope.pageOffset.toLong())
            .asFlow().mapToList(ioDispatcher).map { it.map(::toMediaItem) }
        is BrowseScope.AllAlbums -> /* ... */
        // ... (one branch per scope variant; all return Flow<MediaItem>)
    }

    override suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable> =
        Either.catch {
            val track = db.trackQueries.byItemId(itemId.value).executeAsOneOrNull()
                ?: return Either.Left(SourceError.ItemNotFound(itemId))
            track.toPlayable(sourceId = id)
        }.mapLeft { SourceError.IoError(it) }

    override suspend fun refresh(): Either<SourceError, Unit> =
        scanner.scanIncremental().mapLeft { SourceError.Internal(it.message ?: "Scan failed") }
}
```

**Note on the `is` check at the bottom of `browse`:** this is **branching on the parameter type, not the source type.** That's allowed and necessary — `BrowseScope` is a sealed interface designed for exhaustive matching. The Source Protocol rule is about branching on `MusicSource`'s concrete class, not about all `is` checks in the codebase.

### 3.1 SQLDelight query mapping helper

`toMediaItem`, `toSearchResult`, `toPlayable` are extension functions in `LocalLibrarySourceMappers.kt` that convert SQLDelight generated row classes to the source-protocol types. Keeping mapping isolated means the Source Protocol types stay independent of the DB schema even though the local source happens to be DB-backed.

### 3.2 FTS query sanitization

```kotlin
// :data:library/src/commonMain/kotlin/com/clayworks/kiln/library/source/internal/FtsSanitize.kt
package com.clayworks.kiln.library.source.internal

private val FTS5_OPERATORS = setOf('"', '(', ')', '*', ':', '-', '+', '^', '~')

/**
 * Sanitize a user-typed query for SQLite FTS5 MATCH.
 *
 * - Strip operator chars that would change FTS5 semantics
 * - Wrap whitespace-split tokens in quotes
 * - Append `*` to the last token for type-ahead prefix matching
 */
internal fun sanitizeFtsQuery(raw: String): String {
    val cleaned = raw.filter { it !in FTS5_OPERATORS }
    val tokens = cleaned.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return "\"\""
    val quoted = tokens.dropLast(1).map { "\"$it\"" }
    val last = "\"${tokens.last()}\"*"
    return (quoted + last).joinToString(" ")
}
```

This is the same `sanitizeFTS` pattern from the engram example in the system memory — type-ahead matching requires the trailing `*`. Stripping FTS5 operators prevents user input like `"foo AND bar"` from crashing SQLite.

---

## 4. PlatformPlayer — the engine-swap-shaped boundary

Per spec §13 / vetting Item 13 decision: MVP commits to a `PlatformPlayer` abstraction that hides Media3 (Android) and Java Sound (Desktop). Phase 2b Flights H+I (AAudio MMAP, WASAPI) may swap implementations behind this interface without touching consumers.

### 4.1 Core interface

```kotlin
// :audio:playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/PlatformPlayer.kt
package com.clayworks.kiln.audio.playback

import com.clayworks.kiln.library.source.MediaItem
import kotlinx.coroutines.flow.StateFlow

interface PlatformPlayer {
    val state: StateFlow<PlayerState>
    val positionMs: StateFlow<Long>
    val queue: StateFlow<QueueState>
    val volume: StateFlow<VolumeState>

    suspend fun loadQueue(items: List<MediaItem>, startIndex: Int = 0, autoPlay: Boolean = true)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seekTo(positionMs: Long)
    suspend fun skipToNext()
    suspend fun skipToPrevious()
    suspend fun skipTo(queueIndex: Int)
    suspend fun setRepeatMode(mode: RepeatMode)
    suspend fun setShuffleMode(enabled: Boolean)
    suspend fun setVolume(linear: Float)         // 0.0f..1.0f
    suspend fun setMuted(muted: Boolean)

    /** Insert a processor into the audio pipeline (visualizer slot, EQ slot, ReplayGain, etc.). */
    fun addAudioProcessor(processor: AudioProcessor)
    fun removeAudioProcessor(processor: AudioProcessor)
    val processors: StateFlow<List<AudioProcessor>>

    /** Capture mode for Phase 3 room correction. Stubbed at MVP. */
    suspend fun enterMeasurementMode(): MeasurementSession

    /** Release platform resources. */
    suspend fun release()
}
```

### 4.2 PlayerState — sealed interface

```kotlin
// :audio:playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/PlayerState.kt
package com.clayworks.kiln.audio.playback

sealed interface PlayerState {
    data object Idle : PlayerState
    data object Loading : PlayerState
    data class Ready(val isPlaying: Boolean) : PlayerState
    data object Buffering : PlayerState
    data class Error(val cause: PlayerError) : PlayerState

    /** Currently in mic-capture measurement mode (Phase 3 stubbed at MVP). */
    data object Measuring : PlayerState
}

sealed interface PlayerError {
    data class DeviceUnavailable(val reason: String) : PlayerError    // USB DAC unplugged
    data class FormatUnsupported(val codec: String) : PlayerError
    data class DecodeFailed(val cause: Throwable) : PlayerError       // libFLAC error, etc.
    data class IoError(val cause: Throwable) : PlayerError
    data class Internal(val message: String) : PlayerError
}
```

### 4.3 QueueState, VolumeState, RepeatMode

```kotlin
// :audio:playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/Queue.kt
package com.clayworks.kiln.audio.playback

import com.clayworks.kiln.library.source.MediaItem

data class QueueState(
    val items: List<MediaItem>,
    val currentIndex: Int,
    val repeatMode: RepeatMode,
    val shuffleEnabled: Boolean,
) {
    val currentItem: MediaItem? get() = items.getOrNull(currentIndex)
    val hasNext: Boolean get() = when (repeatMode) {
        RepeatMode.All -> items.isNotEmpty()
        RepeatMode.One -> items.isNotEmpty()
        RepeatMode.Off -> currentIndex < items.lastIndex
    }
    val hasPrevious: Boolean get() = currentIndex > 0 || repeatMode == RepeatMode.All
}

enum class RepeatMode { Off, One, All }

data class VolumeState(
    val linear: Float,       // 0.0..1.0
    val muted: Boolean,
)
```

### 4.4 Android implementation sketch (`Media3ExoPlayerImpl`)

```kotlin
// :audio:playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/Media3ExoPlayerImpl.kt
package com.clayworks.kiln.audio.playback

import android.content.Context
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import kotlinx.coroutines.flow.MutableStateFlow

internal class Media3ExoPlayerImpl(
    private val context: Context,
    private val decoderFactory: Media3DecoderFactory,
) : PlatformPlayer {

    private val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(/* music-content attributes */, /* handleAudioFocus = */ true)
        .setHandleAudioBecomingNoisy(true)   // BLE disconnect pause per vetting Item 11
        .setRenderersFactory(KilnRenderersFactory(decoderFactory))  // injects processor chain
        .build()

    private val session: MediaSession = MediaSession.Builder(context, exo).build()

    override val state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    override val positionMs = MutableStateFlow(0L)
    override val queue = MutableStateFlow(QueueState(emptyList(), -1, RepeatMode.Off, false))
    override val volume = MutableStateFlow(VolumeState(1.0f, false))
    override val processors = MutableStateFlow<List<AudioProcessor>>(emptyList())

    init {
        exo.addListener(/* Player.Listener that updates state/positionMs/queue StateFlows */)
        startPositionTicker()  // 250ms tick to update positionMs
    }

    override suspend fun loadQueue(items: List<MediaItem>, startIndex: Int, autoPlay: Boolean) {
        // Convert MediaItems to Media3 MediaItems, setMediaItems(...), prepare, play if autoPlay
    }

    // ... rest of the interface
}
```

### 4.5 Desktop implementation sketch (`JavaSoundPlayerImpl`)

```kotlin
// :audio:playback/src/jvmMain/kotlin/com/clayworks/kiln/audio/playback/JavaSoundPlayerImpl.kt
package com.clayworks.kiln.audio.playback

import com.clayworks.kiln.audio.playback.native.JvmDecoderResolver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

internal class JavaSoundPlayerImpl(
    private val decoderResolver: JvmDecoderResolver,
    private val audioDispatcher: CoroutineDispatcher,
) : PlatformPlayer {

    override val state = MutableStateFlow<PlayerState>(PlayerState.Idle)
    override val positionMs = MutableStateFlow(0L)
    override val queue = MutableStateFlow(QueueState(emptyList(), -1, RepeatMode.Off, false))
    override val volume = MutableStateFlow(VolumeState(1.0f, false))
    override val processors = MutableStateFlow<List<AudioProcessor>>(emptyList())

    private val scope = CoroutineScope(audioDispatcher + SupervisorJob())
    private var line: SourceDataLine? = null
    private var playbackJob: Job? = null

    override suspend fun loadQueue(items: List<MediaItem>, startIndex: Int, autoPlay: Boolean) {
        // 1. Resolve Playable for items[startIndex] via injected MusicSource
        // 2. Resolve decoder via decoderResolver.forCodec(playable.codec) — libFLAC for FLAC, etc.
        // 3. Open AudioFormat from playable's codec + sampleRateHz + bitDepth + channels
        // 4. AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, format)) — may throw LineUnavailableException
        // 5. line.open(format, bufferSizeBytes(50ms))
        // 6. Start playback job (see §4.6)
        // 7. queue.value = QueueState(items, startIndex, ...)
        // 8. state.value = PlayerState.Ready(autoPlay)
    }

    // ... rest of interface
}
```

### 4.6 Desktop playback loop (the heart of `JavaSoundPlayerImpl`)

```kotlin
private fun startPlaybackLoop(decoder: Decoder, playable: Playable) {
    playbackJob = scope.launch {
        decoder.open(playable).use { stream ->
            line!!.start()
            stream.frames.collect { frame ->
                // Run processors in order — they may mutate frame in-place
                processors.value.forEach { it.process(frame) }
                line!!.write(frame.bytes, 0, frame.byteCount)
                positionMs.value = stream.positionMs
            }
        }
        // EOF: advance queue
        skipToNext()
    }
}
```

Key constraints:
- Playback runs on `audioDispatcher` (a dedicated single-thread dispatcher backed by a real-time-priority thread; see §7)
- Buffer underrun mitigation: decoder produces frames 100-200ms ahead of `line.write`
- `line.write` blocks until the audio mixer accepts the data — that's the source of the latency budget

---

## 5. Decoder + DecodedStream — the FLAC + WAV + MP3 abstraction

### 5.1 Interfaces

```kotlin
// :audio:playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/Decoder.kt
package com.clayworks.kiln.audio.playback

import arrow.core.Either
import com.clayworks.kiln.library.source.Playable
import kotlinx.coroutines.flow.Flow

interface Decoder {
    /**
     * Open a decoder for the given playable. The returned [DecodedStream] is a
     * [Closeable]-style resource — caller must invoke [DecodedStream.close] (use .use{}).
     */
    suspend fun open(playable: Playable): Either<DecoderError, DecodedStream>

    /** Return true if this decoder can handle the given codec on the current platform. */
    fun supports(codec: AudioCodec): Boolean
}

interface DecodedStream : AutoCloseable {
    val format: DecodedAudioFormat
    val frames: Flow<AudioFrame>
    val positionMs: Long
    val durationMs: Long

    suspend fun seekTo(positionMs: Long)
    override fun close()
}

data class DecodedAudioFormat(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channels: Int,
    val sampleFormat: SampleFormat,   // PCM_S16LE, PCM_S24LE, PCM_F32LE, ...
)

enum class SampleFormat { PCM_S16_LE, PCM_S24_LE, PCM_S32_LE, PCM_F32_LE }

/**
 * One chunk of decoded audio. `bytes` is interleaved PCM; `byteCount` is the
 * valid byte count (≤ bytes.size — reusing a buffer pool is common).
 */
data class AudioFrame(
    val bytes: ByteArray,
    val byteCount: Int,
    val sampleCount: Int,
    val timestampMs: Long,
)

sealed interface DecoderError {
    data class UnsupportedCodec(val codec: AudioCodec) : DecoderError
    data class CorruptStream(val message: String) : DecoderError
    data class IoError(val cause: Throwable) : DecoderError
    data class NativeBindingFailed(val message: String) : DecoderError  // libFLAC load/init
    data class Internal(val message: String) : DecoderError
}
```

### 5.2 Android implementation — `Media3DecoderImpl`

```kotlin
// :audio:playback/src/androidMain/kotlin/com/clayworks/kiln/audio/playback/Media3DecoderImpl.kt
package com.clayworks.kiln.audio.playback

/**
 * Adapter around Media3's MediaExtractor + MediaCodec pipeline.
 *
 * For the MVP Media3ExoPlayerImpl, ExoPlayer owns its own decoder chain internally
 * and we DON'T use Media3DecoderImpl in that path — we use a custom RenderersFactory
 * that injects the processor chain. Media3DecoderImpl exists for the Phase 2b
 * AAudioMmapPlayerImpl path where we need raw PCM out of Android's decoders to feed
 * the AAudio ring buffer ourselves.
 */
internal class Media3DecoderImpl(/* ... */) : Decoder {
    override fun supports(codec: AudioCodec): Boolean = when (codec) {
        AudioCodec.FLAC, AudioCodec.WAV, AudioCodec.MP3, AudioCodec.AAC, AudioCodec.OGG_VORBIS,
        AudioCodec.OGG_OPUS, AudioCodec.ALAC -> true
        AudioCodec.UNKNOWN -> false
    }
    // ...
}
```

### 5.3 Desktop implementation — `JvmFlacDecoderImpl` (the big one)

```kotlin
// :audio:playback/src/jvmMain/kotlin/com/clayworks/kiln/audio/playback/JvmFlacDecoderImpl.kt
package com.clayworks.kiln.audio.playback

import com.clayworks.kiln.audio.playback.native.LibFlacBinding
import com.clayworks.kiln.audio.playback.native.NativeLibraryLoader
import com.clayworks.kiln.library.source.AudioCodec
import com.clayworks.kiln.library.source.Playable
import kotlinx.coroutines.flow.flow

/**
 * Desktop FLAC decoder via JNA bridge to vendored Xiph libFLAC 1.5.0 BSD-3.
 * Vetting log Item 9 addendum decision.
 */
internal class JvmFlacDecoderImpl(
    private val libFlac: LibFlacBinding,
) : Decoder {
    override fun supports(codec: AudioCodec) = codec == AudioCodec.FLAC

    override suspend fun open(playable: Playable): Either<DecoderError, DecodedStream> =
        Either.catch {
            require(playable.codec == AudioCodec.FLAC) { "Use a different decoder for ${playable.codec}" }
            val handle = libFlac.streamDecoderNew()
                ?: return Either.Left(DecoderError.NativeBindingFailed("libFLAC stream_decoder_new returned null"))
            val initResult = libFlac.streamDecoderInitFile(handle, playable.uri.removePrefix("file://"))
            if (initResult != 0) {
                libFlac.streamDecoderDelete(handle)
                return Either.Left(DecoderError.CorruptStream("init failed: $initResult"))
            }
            // First metadata block read populates format details:
            libFlac.streamDecoderProcessUntilEndOfMetadata(handle)
            val streamInfo = libFlac.getStreamInfo(handle)
            JvmFlacDecodedStream(libFlac, handle, streamInfo, playable.durationMs)
        }.mapLeft { DecoderError.IoError(it) }
}
```

```kotlin
// :audio:playback/src/jvmMain/kotlin/com/clayworks/kiln/audio/playback/JvmFlacDecodedStream.kt
package com.clayworks.kiln.audio.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class JvmFlacDecodedStream(
    private val libFlac: LibFlacBinding,
    private val handle: Long,
    streamInfo: LibFlacBinding.StreamInfo,
    override val durationMs: Long,
) : DecodedStream {

    override val format = DecodedAudioFormat(
        sampleRateHz = streamInfo.sampleRateHz,
        bitDepth = streamInfo.bitDepth,
        channels = streamInfo.channels,
        sampleFormat = when (streamInfo.bitDepth) {
            16 -> SampleFormat.PCM_S16_LE
            24 -> SampleFormat.PCM_S24_LE
            32 -> SampleFormat.PCM_S32_LE
            else -> error("Unexpected FLAC bit depth: ${streamInfo.bitDepth}")
        },
    )

    @Volatile override var positionMs: Long = 0L
        private set

    override val frames: Flow<AudioFrame> = flow {
        val bufferPool = AudioFrameBufferPool(format, frameSizeSamples = 4096)
        while (libFlac.streamDecoderGetState(handle) == LibFlacBinding.STATE_OK) {
            val frame = bufferPool.acquire()
            val samplesDecoded = libFlac.streamDecoderProcessSingle(handle, frame)
            if (samplesDecoded == 0) break    // EOF
            positionMs += (samplesDecoded * 1000L) / format.sampleRateHz
            emit(frame)
        }
    }

    override suspend fun seekTo(positionMs: Long) {
        val sample = (positionMs * format.sampleRateHz) / 1000L
        libFlac.streamDecoderSeekAbsolute(handle, sample)
        this.positionMs = positionMs
    }

    override fun close() {
        libFlac.streamDecoderFinish(handle)
        libFlac.streamDecoderDelete(handle)
    }
}
```

### 5.4 LibFlacBinding — the JNA interface

```kotlin
// :audio:playback/src/jvmMain/kotlin/com/clayworks/kiln/audio/playback/native/LibFlacBinding.kt
package com.clayworks.kiln.audio.playback.native

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

internal interface LibFlacBinding : Library {
    // Stream decoder
    fun FLAC__stream_decoder_new(): Pointer?
    fun FLAC__stream_decoder_init_file(handle: Pointer, filePath: String, /* callbacks */): Int
    fun FLAC__stream_decoder_process_until_end_of_metadata(handle: Pointer): Boolean
    fun FLAC__stream_decoder_process_single(handle: Pointer): Boolean
    fun FLAC__stream_decoder_seek_absolute(handle: Pointer, sample: Long): Boolean
    fun FLAC__stream_decoder_finish(handle: Pointer): Boolean
    fun FLAC__stream_decoder_delete(handle: Pointer)
    fun FLAC__stream_decoder_get_state(handle: Pointer): Int

    // Internal helpers (in Kotlin layer, not native):
    fun streamDecoderNew(): Long? = FLAC__stream_decoder_new()?.let { Pointer.nativeValue(it) }
    fun streamDecoderInitFile(handle: Long, path: String): Int = TODO("wire callbacks")
    // ...

    companion object {
        const val STATE_OK = 0  // FLAC__STREAM_DECODER_OK constant from FLAC/stream_decoder.h
        // ... other state constants
    }

    data class StreamInfo(
        val sampleRateHz: Int,
        val bitDepth: Int,
        val channels: Int,
        val totalSamples: Long,
    )
}

/**
 * Loads the native libFLAC.dll from the JAR's vendored resources and
 * binds it via JNA. Called once at app init from the kotlin-inject graph.
 */
internal object LibFlacLoader {
    fun load(): LibFlacBinding {
        NativeLibraryLoader.loadLibFlac()  // extracts DLL from JAR to temp, System.load()s it
        return Native.load("FLAC", LibFlacBinding::class.java)
    }
}
```

**Note on the JNA write-up here:** the actual binding will be more involved because libFLAC's stream-decoder model is callback-based (you register `write_callback`, `metadata_callback`, `error_callback`) and JNA callbacks have specific marshalling rules. The sketch shows the shape; the implementing session writes the full callback dance. A reference implementation is the C example at `xiph/flac/examples/c/decode/file/main.c`.

### 5.5 DecoderResolver — pick the right decoder for a Playable

```kotlin
// :audio:playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/DecoderResolver.kt
package com.clayworks.kiln.audio.playback

interface DecoderResolver {
    fun forCodec(codec: AudioCodec): Decoder?
}

/** Common-code resolver — implementations register decoders for their platform. */
class DefaultDecoderResolver(private val decoders: List<Decoder>) : DecoderResolver {
    override fun forCodec(codec: AudioCodec): Decoder? =
        decoders.firstOrNull { it.supports(codec) }
}
```

In `:app-android` the DI graph provides `Media3DecoderImpl`; in `:app-desktop` it provides `JvmFlacDecoderImpl` + `JvmWavDecoderImpl` (and eventually MP3 / OGG when MVP scope extends).

---

## 6. Library scan workflow

The scanner reads from the filesystem (Desktop) or MediaStore (Android) and upserts the SQLDelight tables per the schema sketch. **Application-managed FTS5 population** is the chosen pattern (rejecting SQL triggers per schema-sketch §4.2).

### 6.1 LibraryScanner interface

```kotlin
// :data:library/src/commonMain/kotlin/com/clayworks/kiln/library/scan/LibraryScanner.kt
package com.clayworks.kiln.library.scan

import arrow.core.Either
import kotlinx.coroutines.flow.Flow

interface LibraryScanner {
    /** Scan only files changed since the last full scan. Default user-initiated path. */
    suspend fun scanIncremental(): Either<ScanError, ScanResult>

    /** Full rescan — clears all tracks (soft-delete via `deleted_at_ms`), repopulates. */
    suspend fun scanFull(): Either<ScanError, ScanResult>

    /** Real-time progress for UI; emits during a scan. */
    val progress: Flow<ScanProgress>
}

data class ScanResult(
    val tracksAdded: Int,
    val tracksUpdated: Int,
    val tracksDeleted: Int,        // soft-deleted via deleted_at_ms
    val durationMs: Long,
)

data class ScanProgress(
    val state: ScanState,
    val filesScanned: Int,
    val totalFilesEstimated: Int,
)

sealed interface ScanState {
    data object Idle : ScanState
    data object Discovering : ScanState
    data class Indexing(val currentFile: String) : ScanState
    data object Finalizing : ScanState
}

sealed interface ScanError {
    data class PermissionDenied(val message: String) : ScanError
    data class IoError(val cause: Throwable) : ScanError
    data class Internal(val message: String) : ScanError
}
```

### 6.2 Android impl — MediaStore

```kotlin
// :data:library/src/androidMain/kotlin/com/clayworks/kiln/library/scan/AndroidMediaStoreScanner.kt
internal class AndroidMediaStoreScanner(
    private val context: Context,
    private val db: KilnDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) : LibraryScanner {
    // Query MediaStore.Audio.Media with projection on FLAC + WAV + MP3 paths;
    // for each file, read tags via MediaStore columns (or fall back to MediaMetadataRetriever);
    // upsert into SQLDelight; refresh FTS via track_search.replace.
}
```

### 6.3 Desktop impl — filesystem walker + jaudiotagger

```kotlin
// :data:library/src/jvmMain/kotlin/com/clayworks/kiln/library/scan/JvmFilesystemScanner.kt
internal class JvmFilesystemScanner(
    private val scanFolders: List<Path>,
    private val db: KilnDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) : LibraryScanner {
    override suspend fun scanIncremental(): Either<ScanError, ScanResult> = withContext(ioDispatcher) {
        Either.catch {
            val now = System.currentTimeMillis()
            val candidates = scanFolders.asSequence()
                .flatMap { Files.walk(it).asSequence() }
                .filter { isAudioFile(it) }
                .filter { needsRescan(it) }   // mtime > track.file_mtime_ms OR not yet indexed
                .toList()

            val results = candidates.map { path ->
                upsertTrackFromFile(path, now)
            }
            markMissingFilesAsDeleted(now)
            ScanResult(/* counts from results */)
        }.mapLeft { ScanError.IoError(it) }
    }

    private fun upsertTrackFromFile(path: Path, scanStartedMs: Long): UpsertOutcome {
        val audioFile = AudioFileIO.read(path.toFile())   // jaudiotagger
        val tag = audioFile.tag
        val header = audioFile.audioHeader

        db.transaction {
            val artistId = db.artistQueries.upsert(/* tag.artist */)
            val albumArtistId = db.artistQueries.upsert(/* tag.albumArtist or tag.artist */)
            val albumId = db.albumQueries.upsert(/* albumArtistId, tag.album */)
            val trackId = db.trackQueries.upsert(
                albumId = albumId,
                artistId = artistId,
                title = tag.getFirst(FieldKey.TITLE),
                titleSort = computeSortName(tag.getFirst(FieldKey.TITLE)),
                durationMs = header.trackLength * 1000L,
                trackNumber = tag.getFirst(FieldKey.TRACK).toIntOrNull(),
                discNumber = tag.getFirst(FieldKey.DISC_NO).toIntOrNull(),
                year = tag.getFirst(FieldKey.YEAR).toIntOrNull(),
                codec = AudioCodec.FLAC.name,   // jaudiotagger's audioHeader.format -> codec
                sampleRateHz = header.sampleRateAsNumber,
                bitDepth = header.bitsPerSample,
                channels = if (header.channels == "Stereo") 2 else if (header.channels == "Mono") 1 else 2,
                bitrateKbps = header.bitRateAsNumber.toInt(),
                filePath = path.toString(),
                fileSizeBytes = Files.size(path),
                fileMtimeMs = Files.getLastModifiedTime(path).toMillis(),
                replayGainTrackDb = tag.getFirst(FieldKey.RATING).toDoubleOrNull(),  // ReplayGain via custom Vorbis comments
                /* ... */
                dateAddedMs = scanStartedMs,
                dateModifiedMs = scanStartedMs,
                lastScannedMs = scanStartedMs,
            )

            // FTS5 replacement
            db.trackSearchQueries.replace(
                rowid = trackId,
                title = tag.getFirst(FieldKey.TITLE) ?: "",
                albumName = tag.getFirst(FieldKey.ALBUM) ?: "",
                artistName = tag.getFirst(FieldKey.ARTIST) ?: "",
                albumArtistName = tag.getFirst(FieldKey.ALBUM_ARTIST) ?: "",
            )
        }
        return UpsertOutcome.UPSERTED
    }
}

private fun isAudioFile(path: Path): Boolean = path.toString().substringAfterLast('.', "")
    .lowercase() in setOf("flac", "wav", "mp3", "alac", "ogg", "opus", "m4a")
```

**ReplayGain parsing:** FLAC stores ReplayGain in Vorbis Comments (`REPLAYGAIN_TRACK_GAIN`, `REPLAYGAIN_TRACK_PEAK`, etc.). jaudiotagger doesn't have first-class support for these tags via `FieldKey`; access via `tag.getFirst("REPLAYGAIN_TRACK_GAIN")` (string-based field access). Parse the dB value (e.g., `"-6.42 dB"`) by stripping units.

---

## 7. Threading + lifecycle model

| Concern | Dispatcher | Notes |
|---|---|---|
| Library scan + tag-read | `ioDispatcher` (`Dispatchers.IO`) | Filesystem walk + jaudiotagger reads are blocking; bound to IO pool |
| SQLDelight queries (Flow-based) | `ioDispatcher` for `.mapToList(ioDispatcher)` | SQLite I/O blocks; isolate to IO |
| Android playback (ExoPlayer) | Media3 owns its threads; we observe via `Player.Listener` on Main | App-level state updates marshal to Main via `withContext(Dispatchers.Main.immediate)` |
| Desktop playback (Java Sound) | Dedicated single-thread `audioDispatcher` | Backed by `Executors.newSingleThreadExecutor(Thread { it.priority = Thread.MAX_PRIORITY; it.isDaemon = true; it.name = "kiln-audio-out" })`. Real-time-priority emulation on Java; not as tight as a true RT thread but adequate for music playback |
| UI state observation | `Dispatchers.Main.immediate` | Compose collects StateFlows on the immediate Main dispatcher |
| Coil image decode | Coil's internal dispatcher (configurable) | Default fine; bumping to Default may help on desktop |
| kmpalette extraction | `Dispatchers.Default` (CPU-bound) | Compose `LaunchedEffect` calls — keep off Main |

Lifecycle:
- `PlatformPlayer.release()` MUST be called on app shutdown — releases the `SourceDataLine` / `ExoPlayer.release()`
- `DecodedStream.close()` MUST be called when the stream finishes (use `.use { ... }` pattern)
- libFLAC's stream-decoder handle MUST be `streamDecoderDelete`'d — leaking it leaks native memory
- `LibraryScanner.progress` Flow MUST be canceled when the consuming UI surface is destroyed

---

## 8. Wiring with kotlin-inject

Conceptual graph (actual `.kt` files at MVP Session 4-7):

```kotlin
// :app-desktop/src/jvmMain/kotlin/com/clayworks/kiln/desktop/di/AppGraph.kt
@Component
abstract class DesktopAppGraph(
    @get:Provides val userDataDir: UserDataDir,
) {
    // Database
    abstract val database: KilnDatabase
    @Provides
    fun sqliteDriver(): SqlDriver = JdbcSqliteDriver(
        url = "jdbc:sqlite:${userDataDir.value.resolve("kiln.db")}",
        properties = Properties().apply { put("foreign_keys", "true") },
    ).also { KilnDatabase.Schema.create(it) }

    // Library source
    @Provides
    fun musicSource(db: KilnDatabase, scanner: LibraryScanner): MusicSource =
        LocalLibrarySource(db, Dispatchers.IO, scanner)
    abstract val musicSource: MusicSource

    // Scanner
    @Provides
    fun scanner(db: KilnDatabase, scanFolders: ScanFolders): LibraryScanner =
        JvmFilesystemScanner(scanFolders.paths, db, Dispatchers.IO)

    // Player
    @Provides
    fun platformPlayer(decoderResolver: DecoderResolver): PlatformPlayer =
        JavaSoundPlayerImpl(decoderResolver, kilnAudioDispatcher())
    abstract val player: PlatformPlayer

    // Decoders
    @Provides
    fun libFlac(): LibFlacBinding = LibFlacLoader.load()
    @Provides
    fun decoderResolver(libFlac: LibFlacBinding): DecoderResolver = DefaultDecoderResolver(
        listOf(JvmFlacDecoderImpl(libFlac), JvmWavDecoderImpl()),
    )
}
```

Android side is parallel with `AndroidSqliteDriver`, `AndroidMediaStoreScanner`, `Media3ExoPlayerImpl`, `Media3DecoderImpl`. The interfaces flowing between modules are identical; only the `androidMain` / `jvmMain` adapter implementations differ.

---

## 9. AudioProcessor — the DSP slot

The audio pipeline accepts processor plugins per spec §6.1 / plan §3.2 Sessions 16-22 (EQ port) and Phase 2a Flight E (FFT visualizer).

### 9.1 Interface

```kotlin
// :audio:playback/src/commonMain/kotlin/com/clayworks/kiln/audio/playback/AudioProcessor.kt
package com.clayworks.kiln.audio.playback

interface AudioProcessor {
    val id: String                                  // e.g., "kiln.eq.parametric"
    val isEnabled: Boolean
    val supportedFormats: Set<SampleFormat>

    /** Process a frame in-place. Mutate `frame.bytes[0..frame.byteCount)`. */
    fun process(frame: AudioFrame)

    /** Notify processor of format change (track transition). May allocate buffers. */
    fun onFormatChange(format: DecodedAudioFormat)

    /** Release resources (called on `PlatformPlayer.release()`). */
    fun release()
}
```

### 9.2 MVP wiring

At MVP Session 4-7 the processor chain is empty — the player passes frames through unchanged. The interface exists so:
- MVP Session 16-22 EQ port: implements `ParametricEqProcessor` in `:audio:dsp/commonMain`. Both Android (via Media3 `BaseAudioProcessor` adapter) and Desktop (direct `AudioProcessor`) wrap it
- Phase 2a Flight E FFT visualizer: implements `FftVisualizerProcessor` in `:audio:visualizer/commonMain`. Same adapter pattern
- Phase 3 room correction: another processor in the chain

The chain is ordered: `processors.value.forEach { it.process(frame) }`. EQ comes first, visualizer last (visualizer reads but doesn't alter — though that's a convention, not enforced).

---

## 10. Empirical FLAC smoke test design (vetting Item 9 addendum gate)

Per vetting Item 9 addendum + plan §3.2 Session 4-7 revised effort: the JNA-libFLAC path must be empirically verified against Clay's actual library before declaring `:audio:playback` complete.

### 10.1 Test design

Pick 10 representative FLAC files from Clay's 39,500-track library spanning:
- 1 × 16-bit / 44.1 kHz / stereo (standard CD-quality)
- 1 × 16-bit / 48 kHz / stereo
- 2 × 24-bit / 96 kHz / stereo (hi-res common case)
- 1 × 24-bit / 88.2 kHz / stereo
- 1 × 24-bit / 192 kHz / stereo (high-res edge case; check USB DAC compatibility too)
- 1 × 24-bit / 96 kHz / 5.1 channels (multichannel — uncommon but possible in Clay's library)
- 1 × FLAC with full ReplayGain track + album tags
- 1 × FLAC with embedded album art (Vorbis comment METADATA_BLOCK_PICTURE)
- 1 × FLAC with ID3v2 tags (uncommon; some rippers add these)

### 10.2 Reference comparison

For each test file:
```bash
# Decode via ffmpeg as ground truth
ffmpeg -i test.flac -f s32le -acodec pcm_s32le reference.pcm

# Decode via JvmFlacDecoderImpl + JNA libFLAC
./gradlew :audio:playback:jvmTest --tests "FlacDecodeSmokeTest.decodes_test_flac_correctly"
# Test outputs decoded PCM to a known path

# Compare byte-by-byte
cmp -l reference.pcm kiln-output.pcm | wc -l   # zero mismatches = success
```

Note bit-depth: `JvmFlacDecodedStream` returns `SampleFormat.PCM_S{16,24,32}_LE` per source bit depth. The ffmpeg reference must use the same target format. For 24-bit input, ffmpeg `-f s24le -acodec pcm_s24le` matches `SampleFormat.PCM_S24_LE`.

### 10.3 Success gate

- 10/10 files decode byte-identical to ffmpeg reference: **PASS** — JNA path is firm
- 1-2 files fail: investigate. May be a JNA marshalling bug (24-bit PCM packing is fiddly: 3 bytes per sample, little-endian, sign-extended into a 32-bit int)
- 3+ files fail: surface to Clay; reconsider JNA path. Fallback options per vetting Item 9 addendum

### 10.4 Smoke test location

`:audio:playback/src/jvmTest/kotlin/com/clayworks/kiln/audio/playback/FlacDecodeSmokeTest.kt`. Excluded from CI matrix (requires Clay's library on disk); runs locally via `./gradlew :audio:playback:jvmTest`.

---

## 11. Module-by-module file inventory for MVP Session 4-7

### 11.1 `:data:library`

```
data/library/src/
  commonMain/kotlin/com/clayworks/kiln/library/
    source/
      MusicSource.kt           interface + supporting types
      Ids.kt                   value classes
      BrowseScope.kt           sealed interface + sort enums
      MediaItem.kt             data class
      Playable.kt              data class + ReplayGain + AudioCodec
      SearchResult.kt          data class
      SourceCapabilities.kt    data class + SourceType
      SourceError.kt           sealed interface
      LocalLibrarySource.kt    the only impl
      LocalLibrarySourceMappers.kt   SQLDelight row → source-protocol type
      internal/
        FtsSanitize.kt         sanitizeFtsQuery()
    scan/
      LibraryScanner.kt        interface
      ScanResult.kt, ScanProgress.kt, ScanError.kt
    di/
      LibraryGraph.kt          kotlin-inject component contributions
  commonMain/sqldelight/com/clayworks/kiln/library/db/
    artist.sq, album.sq, track.sq, playlist.sq, playlist_track.sq,
    listening_history.sq, track_search.sq, pragmas.sq      # per schema sketch §3
  androidMain/kotlin/com/clayworks/kiln/library/
    scan/AndroidMediaStoreScanner.kt
    db/AndroidDriverFactory.kt
  jvmMain/kotlin/com/clayworks/kiln/library/
    scan/JvmFilesystemScanner.kt
    db/JvmDriverFactory.kt
```

### 11.2 `:audio:playback`

```
audio/playback/src/
  commonMain/kotlin/com/clayworks/kiln/audio/playback/
    PlatformPlayer.kt          interface
    PlayerState.kt             sealed interface + PlayerError
    Queue.kt                   QueueState, RepeatMode, VolumeState
    AudioProcessor.kt          DSP slot interface
    Decoder.kt                 + DecodedStream + DecodedAudioFormat + AudioFrame
    DecoderResolver.kt         + DefaultDecoderResolver
    DecoderError.kt
    MeasurementSession.kt      stubbed for Phase 3
    di/PlaybackGraph.kt        kotlin-inject contributions
  androidMain/kotlin/com/clayworks/kiln/audio/playback/
    Media3ExoPlayerImpl.kt
    Media3DecoderImpl.kt
    KilnRenderersFactory.kt    custom ExoPlayer factory injecting processor chain
    media3session/
      KilnMediaSessionService.kt   per vetting Item 11
  jvmMain/kotlin/com/clayworks/kiln/audio/playback/
    JavaSoundPlayerImpl.kt
    JvmFlacDecoderImpl.kt      + JvmFlacDecodedStream.kt
    JvmWavDecoderImpl.kt       (built-in Java Sound; lightweight)
    native/
      LibFlacBinding.kt        JNA interface
      LibFlacLoader.kt         loads + binds
      NativeLibraryLoader.kt   extracts DLL from JAR to temp
    resources/native/win-x64/
      libFLAC.dll              ← vendored 1.5.0 BSD-3
      LICENSE-libflac.txt      ← Xiph BSD-3 attribution
  jvmTest/kotlin/com/clayworks/kiln/audio/playback/
    FlacDecodeSmokeTest.kt     per §10
```

### 11.3 `:audio:dsp`, `:audio:visualizer`

Empty at MVP Session 4-7. Populated at MVP Session 16-22 (EQ port) and Phase 2a Flight E (FFT). `commonMain` only — strict Concentric Modules invariant per spec §3.4.

### 11.4 `:ui:components`, `:ui:theme`

Touch only as needed for the play-a-track end-to-end vertical slice. A throwaway "play this track" button is fine; the proper library UI lands at MVP Session 8-11 per plan §3.2.

---

## 12. Open design questions to resolve at MVP Session 4-7

| # | Question | When |
|---|---|---|
| 1 | Should `MusicSource.search` accept a paging cursor (continuation token) or just `limit + offset`? | Session 4 — pick `limit/offset` for simplicity unless a use case forces cursor semantics |
| 2 | Should `Decoder.open` be `Either<DecoderError, DecodedStream>` (current sketch) or throw? | Session 4 — Arrow `Either` shown is consistent with `MusicSource.getPlayable`; keep the pattern |
| 3 | Should `AudioFrame.bytes` be `ByteArray` or `ByteBuffer`? | Session 4 — `ByteArray` for KMP friendliness; `ByteBuffer` if profiling shows the conversion overhead matters |
| 4 | How do we surface "track removed during playback" (file deleted from disk)? | Session 5-6 — `PlayerState.Error(PlayerError.IoError(...))` + transition queue past it |
| 5 | Cover art lookup ordering: embedded → `folder.jpg` → null? | Session 4 — match what JAMZ/Gramophone does; document |
| 6 | Should the JVM `audioDispatcher` use `Dispatchers.IO` or a dedicated single-thread executor? | Session 4-5 — start with single-thread for predictable scheduling; benchmark vs IO at Session 16-22 (DSP perf smoke test) |
| 7 | How does the scanner handle file moves (same content, different path)? | Session 5-6 — content hash fallback if file_path lookup fails; otherwise treat as delete+add |
| 8 | What's the kotlin-inject scope for `PlatformPlayer`? Singleton across the app? | Session 4 — yes, singleton — the player owns native resources (`SourceDataLine` / `ExoPlayer`) |
| 9 | When does the scanner run? On app start? Manual button? Background service? | Session 5-6 — incremental on app start; manual button surface in Settings; no background service at MVP |

---

## 13. What this prep doc does NOT do

- Does NOT define the final `.kt` files — sketches only; types/names may refine at MVP Session 4-7
- Does NOT pin which JNA version to use (5.14.0 sketched; verify at scaffold time per Session 1-3 prep doc)
- Does NOT prescribe a specific Coroutines structured-concurrency strategy beyond "playback runs in a single-thread audio dispatcher" — exact `CoroutineScope` topology is implementer's call
- Does NOT replace the schema sketch — this doc treats the schema as given and adds the Source Protocol layer ON TOP
- Does NOT preempt the Item 12 LazyColumn 40k spike — paged loading default is in §3 BrowseScope.AllTracks; spike confirms whether unpaged is also viable
- Does NOT specify ProGuard/R8 rules for the Android app — Sessions 26-28 polish per plan §3.2
- Does NOT include UI code — that's MVP Session 8-11+ work
- Does NOT design the audio pipeline error-recovery state machine in detail (network reconnection, decoder retry policies) — MVP Session 4-7 implements basic error states; Phase 2a refines

---

## 14. Effort projection

Per plan §3.2 (revised 2026-05-18):

| Session group | Effort | Scope per this doc |
|---|---|---|
| MVP Sessions 4-5 | ~10-15 hrs | `MusicSource` + `LocalLibrarySource` + `LibraryScanner` Android+Desktop adapters; SQLDelight `.sq` files; minimal browse query |
| MVP Sessions 6-7 | ~20-30 hrs | `PlatformPlayer` interface + Media3ExoPlayerImpl + JavaSoundPlayerImpl + JNA libFLAC bridge + smoke tests + vertical-slice "play a track" wire-up |
| **Total Sessions 4-7** | **30-45 hrs** | per revised plan |

The JNA-libFLAC bridge work is the long pole. Allocate ~10-15 hrs of the Session 6-7 budget specifically to it.

---

End of MVP Session 4-7 Vertical-Slice Prep.
