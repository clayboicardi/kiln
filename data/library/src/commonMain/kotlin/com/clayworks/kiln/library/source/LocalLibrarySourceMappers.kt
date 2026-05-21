// Row → Source-Protocol-type extension functions. Keeping mapping isolated
// preserves the Source Protocol's independence from the DB schema (even
// though LocalLibrarySource happens to be DB-backed). Per scaffold prep §3.1.

package com.clayworks.kiln.library.source

import com.clayworks.kiln.data.library.db.Album
import com.clayworks.kiln.data.library.db.Artist
import com.clayworks.kiln.data.library.db.Playlist
import com.clayworks.kiln.data.library.db.SearchTracks
import com.clayworks.kiln.data.library.db.SelectRecentlyPlayed
import com.clayworks.kiln.data.library.db.Track

// ItemId namespacing contract (consumed by getPlayable + UI list rendering):
//   - Track:    bare numeric, e.g., "42"          (back-compat with Session 6 mapper)
//   - Album:    "album:<id>"
//   - Artist:   "artist:<id>"
//   - Playlist: "playlist:<id>"
// Albums/artists/playlists are *containers*, not playables — getPlayable returns
// ItemNotFound for any non-numeric ItemId. UI should browse(TracksOfAlbum/...) to
// resolve playable Track items first.

internal fun Track.toMediaItem(): MediaItem = MediaItem(
    itemId = ItemId(id.toString()),
    sourceId = SourceId("local"),
    kind = MediaItem.Kind.Track,
    title = title,
    subtitle = null,  // Artist/album JOIN deferred — list UIs can compose if needed
    durationMs = duration_ms,
    artUri = art_path,
    metadata = emptyMap(),
)

internal fun Album.toMediaItem(): MediaItem = MediaItem(
    itemId = ItemId("album:$id"),
    sourceId = SourceId("local"),
    kind = MediaItem.Kind.Album,
    title = name,
    subtitle = year?.toString(),
    durationMs = null,
    artUri = art_path,
    metadata = emptyMap(),
)

internal fun Artist.toMediaItem(): MediaItem = MediaItem(
    itemId = ItemId("artist:$id"),
    sourceId = SourceId("local"),
    kind = MediaItem.Kind.Artist,
    title = name,
    subtitle = null,
    durationMs = null,
    artUri = null,  // Artist art deferred — derive from first album in UI if desired
    metadata = emptyMap(),
)

internal fun Playlist.toMediaItem(): MediaItem = MediaItem(
    itemId = ItemId("playlist:$id"),
    sourceId = SourceId("local"),
    kind = MediaItem.Kind.Playlist,
    title = name,
    subtitle = description,
    durationMs = null,
    artUri = null,
    metadata = emptyMap(),
)

internal fun Track.toPlayable(sourceId: SourceId): Playable = Playable(
    itemId = ItemId(id.toString()),
    sourceId = sourceId,
    uri = fileSystemPathToFileUri(file_path),
    codec = codec.toAudioCodec(),
    sampleRateHz = sample_rate_hz.toInt(),
    bitDepth = bit_depth?.toInt(),
    channels = channels.toInt(),
    bitrateKbps = bitrate_kbps?.toInt(),
    durationMs = duration_ms,
    replayGain = if (replay_gain_track_db != null ||
        replay_gain_album_db != null ||
        replay_gain_track_peak != null ||
        replay_gain_album_peak != null
    ) {
        ReplayGain(
            trackDb = replay_gain_track_db,
            trackPeak = replay_gain_track_peak,
            albumDb = replay_gain_album_db,
            albumPeak = replay_gain_album_peak,
        )
    } else {
        null
    },
    cachedLocally = true,
)

// SQLDelight type-narrows selectRecentlyPlayed because of `last_played_ms IS
// NOT NULL` in the WHERE clause (changes nullability of the column). It thus
// generates a custom `SelectRecentlyPlayed` row class instead of using `Track`.
// Same field shape; separate mapper for type safety.
internal fun SelectRecentlyPlayed.toMediaItem(): MediaItem = MediaItem(
    itemId = ItemId(id.toString()),
    sourceId = SourceId("local"),
    kind = MediaItem.Kind.Track,
    title = title,
    subtitle = null,
    durationMs = duration_ms,
    artUri = art_path,
    metadata = emptyMap(),
)

internal fun SearchTracks.toSearchResult(): SearchResult = SearchResult(
    item = MediaItem(
        itemId = ItemId(id.toString()),
        sourceId = SourceId("local"),
        kind = MediaItem.Kind.Track,
        title = title,
        subtitle = null,
        durationMs = duration_ms,
        artUri = art_path,
        metadata = emptyMap(),
    ),
    matchScore = match_score,  // bm25 returns non-null Double for matched rows
    matchedField = SearchResult.MatchedField.Title,  // bm25 doesn't tell us which field; default to Title
)

/**
 * Convert an absolute filesystem path to a properly-formed `file://` URI per RFC 8089.
 * Handles Windows drive-letter paths (`D:\foo bar\baz.flac` → `file:///D:/foo%20bar/baz.flac`)
 * and Unix-style paths (`/foo bar/baz.flac` → `file:///foo%20bar/baz.flac`). Percent-encodes
 * non-unreserved characters so spaces, parentheses, etc. round-trip cleanly through
 * `java.net.URI` on the consumer side (see JvmFlacDecoderImpl, Media3 fromUri).
 *
 * Pass-through if the input already looks like a URI (contains `"://"`) so future remote
 * sources (http:// content://) bypass the file-path normalization.
 *
 * Internal visibility for direct unit-test access from commonTest.
 */
internal fun fileSystemPathToFileUri(absolutePath: String): String {
    if (absolutePath.contains("://")) return absolutePath

    val withForwardSlashes = absolutePath.replace('\\', '/')
    val isWindowsDrive = withForwardSlashes.length >= 2 &&
        (withForwardSlashes[0] in 'A'..'Z' || withForwardSlashes[0] in 'a'..'z') &&
        withForwardSlashes[1] == ':'
    val withRoot = if (isWindowsDrive) "/$withForwardSlashes" else withForwardSlashes

    return buildString {
        append("file://")
        for (c in withRoot) {
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' -> append(c)
                c == '-' || c == '.' || c == '_' || c == '~' -> append(c)
                c == '/' || c == ':' -> append(c)
                else -> appendUtf8PercentEncoded(c)
            }
        }
    }
}

private fun StringBuilder.appendUtf8PercentEncoded(c: Char) {
    val code = c.code
    when {
        code < 0x80 -> appendPercentByte(code)
        code < 0x800 -> {
            appendPercentByte(0xC0 or (code shr 6))
            appendPercentByte(0x80 or (code and 0x3F))
        }
        code in 0xD800..0xDFFF -> {
            // Lone surrogate — fall back to Unicode replacement character (U+FFFD encoded as
            // EF BF BD). File paths rarely carry split surrogate pairs; this is a defensive
            // best-effort that preserves round-trip-able output.
            appendPercentByte(0xEF)
            appendPercentByte(0xBF)
            appendPercentByte(0xBD)
        }
        else -> {
            appendPercentByte(0xE0 or (code shr 12))
            appendPercentByte(0x80 or ((code shr 6) and 0x3F))
            appendPercentByte(0x80 or (code and 0x3F))
        }
    }
}

private fun StringBuilder.appendPercentByte(b: Int) {
    val v = b and 0xFF
    append('%')
    append(HEX_CHARS[(v shr 4) and 0x0F])
    append(HEX_CHARS[v and 0x0F])
}

private const val HEX_CHARS = "0123456789ABCDEF"

private fun String.toAudioCodec(): AudioCodec = when (uppercase()) {
    "FLAC" -> AudioCodec.FLAC
    "MP3" -> AudioCodec.MP3
    "ALAC" -> AudioCodec.ALAC
    "OGG", "OGG_VORBIS", "VORBIS" -> AudioCodec.OGG_VORBIS
    "OPUS", "OGG_OPUS" -> AudioCodec.OGG_OPUS
    "WAV", "PCM" -> AudioCodec.WAV
    "AAC", "M4A" -> AudioCodec.AAC
    else -> AudioCodec.UNKNOWN
}
