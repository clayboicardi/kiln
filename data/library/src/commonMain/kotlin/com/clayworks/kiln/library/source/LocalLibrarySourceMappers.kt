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
    uri = if (file_path.startsWith("/") || file_path.matches(Regex("^[A-Za-z]:.*"))) {
        "file://$file_path"
    } else {
        file_path
    },
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
