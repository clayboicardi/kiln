// Source Protocol data types: lightweight reference (MediaItem), resolved
// resource (Playable), search result wrapper (SearchResult).

package com.clayworks.kiln.library.source

/**
 * Lightweight reference for browse + queue display. Does NOT carry a resolved
 * playable resource — that's [Playable], acquired via [MusicSource.getPlayable].
 *
 * MediaItem is what populates list UIs, queue entries, mini-player surfaces.
 * Hundreds-of-thousands may exist in memory; keep small.
 */
data class MediaItem(
    val itemId: ItemId,
    val sourceId: SourceId,
    val kind: Kind,
    val title: String,
    val subtitle: String? = null,
    val durationMs: Long? = null,
    val artUri: String? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    enum class Kind { Track, Album, Artist, Playlist }
}

/**
 * Resolved playable resource. Acquired via [MusicSource.getPlayable].
 * Local: file path. Future network sources: stream URL.
 */
data class Playable(
    val itemId: ItemId,
    val sourceId: SourceId,
    val uri: String,
    val codec: AudioCodec,
    val sampleRateHz: Int,
    val bitDepth: Int?,
    val channels: Int,
    val bitrateKbps: Int?,
    val durationMs: Long,
    val replayGain: ReplayGain?,
    val cachedLocally: Boolean = true,
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
    val matchScore: Double = 1.0,
    val matchedField: MatchedField? = null,
) {
    enum class MatchedField { Title, AlbumName, ArtistName, AlbumArtistName, PlaylistName }
}
