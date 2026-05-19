// Capability flags for the Source Protocol. Per spec §3.3, features that need
// to vary by source MUST consult these flags instead of doing `if (source is X)`.

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
    val supportsOfflineCache: Boolean = false,
    val supportsDownload: Boolean = false,

    // User-data
    val supportsLikedTracks: Boolean = false,
    val supportsListeningHistory: Boolean = true,
    val supportsUserPlaylists: Boolean = true,
    val supportsScrobbleExport: Boolean = false,  // out of scope per anti-roadmap §11

    // Playback hints
    val supportsScrubbing: Boolean = true,
    val supportsGaplessPlayback: Boolean = true,
)

enum class SourceType {
    LOCAL,
    NETWORK_LOSSLESS,
    NETWORK_LOSSY,
}

/** Canonical LocalLibrarySource capability set. */
val LocalSourceCapabilities = SourceCapabilities(
    sourceType = SourceType.LOCAL,
    canBrowseByGenre = false,
    supportsLikedTracks = false,
    supportsListeningHistory = true,
    supportsUserPlaylists = true,
    supportsScrubbing = true,
    supportsGaplessPlayback = true,
)
