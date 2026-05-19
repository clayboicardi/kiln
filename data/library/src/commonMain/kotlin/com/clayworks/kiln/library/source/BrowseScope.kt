// BrowseScope sealed-interface "query language" for MusicSource.browse().
// Each variant maps to a SQLDelight query per the schema sketch.

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
    data class TracksOfArtist(
        val artistId: ArtistId,
        val pageSize: Int = 100,
        val pageOffset: Int = 0,
    ) : BrowseScope

    /** Albums by a specific artist (album-artist). */
    data class AlbumsOfArtist(val artistId: ArtistId) : BrowseScope

    /** Tracks in a playlist, ordered by playlist position. */
    data class TracksOfPlaylist(val playlistId: PlaylistId) : BrowseScope

    /** "Recently added" via track.added_at_ms DESC. */
    data class RecentlyAdded(val pageSize: Int = 50) : BrowseScope

    /** "Recently played" via track.last_played_ms DESC. */
    data class RecentlyPlayed(val pageSize: Int = 50) : BrowseScope

    /** "Most played" via track.play_count DESC. */
    data class MostPlayed(val pageSize: Int = 50) : BrowseScope
}

enum class TrackSort {
    TitleAsc,
    TitleDesc,
    AddedDesc,
    PlayCountDesc,
    LastPlayedDesc,
    AlbumArtistTrack,
}

enum class AlbumSort {
    ArtistThenAlbum,
    AlbumName,
    YearDesc,
    RecentlyAdded,
}
