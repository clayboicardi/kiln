// LocalLibrarySource browse-branch coverage. Each @Test exercises one of the
// 11 BrowseScope variants on a fresh in-memory KilnDatabase via TestDb.
// Closes review finding P1-6 (LocalLibrarySource had no tests).
//
// Each @Test gets a fresh LocalLibrarySourceTest instance (per the kotlin.test
// contract), so the `testDb` field initializer runs per-test; @AfterTest
// closes the underlying driver. Unconfined dispatcher avoids real
// threading — fine for hermetic in-memory queries.
//
// Collection idiom: `browse()` returns a hot flow backed by SQLDelight's
// asFlow() (reactive to DB changes — never completes naturally). We collect
// the first DB-query snapshot into a buffer on `backgroundScope`, then
// `runCurrent()` to drain the test scheduler. backgroundScope is auto-cancelled
// at test end, which avoids the UncompletedCoroutinesError that a plain
// `.toList()` would produce. Works for both N rows AND zero rows.

package com.clayworks.kiln.library.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocalLibrarySourceTest {
    private val testDb = TestDb()
    private val source = LocalLibrarySource(testDb.db, Dispatchers.Unconfined)

    @AfterTest fun tearDown() = testDb.close()

    /**
     * Collect the first DB-query snapshot from a hot SQLDelight-backed flow.
     * Uses backgroundScope so the never-completing collector is auto-cancelled
     * at test end; runCurrent() drains the test scheduler so the snapshot is
     * populated before assertions. Works for empty results too.
     */
    private fun <T> TestScope.snapshot(flow: Flow<T>): List<T> {
        val buf = mutableListOf<T>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.toList(buf)
        }
        runCurrent()
        return buf.toList()
    }

    @Test
    fun browse_AllTracks_returnsInsertedTracks() = runTest {
        val artistId = testDb.insertArtist("Pink Floyd", "pink floyd")
        val albumId = testDb.insertAlbum(artistId, "The Wall", year = 1979)
        testDb.insertTrack(artistId, albumId, "Comfortably Numb", filePath = "/wall/01.flac")
        testDb.insertTrack(artistId, albumId, "Another Brick in the Wall", filePath = "/wall/02.flac")

        val items = snapshot(source.browse(BrowseScope.AllTracks()))

        assertEquals(2, items.size)
        assertTrue(items.all { it.kind == MediaItem.Kind.Track })
    }

    @Test
    fun browse_AllAlbums_returnsInsertedAlbums() = runTest {
        // selectAllOrderedByArtistThenAlbum JOINs album with artist, so the
        // artist row must exist for the album to surface.
        val artistId = testDb.insertArtist("Pink Floyd", "pink floyd")
        testDb.insertAlbum(artistId, "The Wall", year = 1979)

        val items = snapshot(source.browse(BrowseScope.AllAlbums()))

        assertEquals(1, items.size)
        assertTrue(items.all { it.kind == MediaItem.Kind.Album })
    }

    @Test
    fun browse_AllArtists_returnsInsertedArtists() = runTest {
        testDb.insertArtist("Pink Floyd", "pink floyd")

        val items = snapshot(source.browse(BrowseScope.AllArtists()))

        assertEquals(1, items.size)
        assertTrue(items.all { it.kind == MediaItem.Kind.Artist })
    }

    @Test
    fun browse_AllPlaylists_returnsInsertedPlaylists() = runTest {
        // Empty case: snapshot of zero rows must collect cleanly (validates
        // the snapshot helper handles the empty-result code path).
        val empty = snapshot(source.browse(BrowseScope.AllPlaylists))
        assertEquals(0, empty.size)

        testDb.insertPlaylist("Favorites")

        val items = snapshot(source.browse(BrowseScope.AllPlaylists))
        assertEquals(1, items.size)
        assertTrue(items.all { it.kind == MediaItem.Kind.Playlist })
    }

    @Test
    fun browse_TracksOfAlbum_filtersByAlbumId() = runTest {
        val artistId = testDb.insertArtist("Pink Floyd", "pink floyd")
        val albumA = testDb.insertAlbum(artistId, "The Wall", year = 1979)
        val albumB = testDb.insertAlbum(artistId, "Animals", year = 1977)
        testDb.insertTrack(artistId, albumA, "Comfortably Numb", filePath = "/wall/01.flac")
        testDb.insertTrack(artistId, albumA, "Another Brick in the Wall", filePath = "/wall/02.flac")
        testDb.insertTrack(artistId, albumB, "Pigs", filePath = "/animals/01.flac")

        val items = snapshot(source.browse(BrowseScope.TracksOfAlbum(AlbumId(albumA))))

        assertEquals(2, items.size)
        assertTrue(items.all { it.kind == MediaItem.Kind.Track })
        val titles = items.map { it.title }.toSet()
        assertEquals(setOf("Comfortably Numb", "Another Brick in the Wall"), titles)
    }

    @Test
    fun browse_TracksOfArtist_filtersByArtistId() = runTest {
        // track.artist_id is the *per-track* artist (not album-artist). Tracks
        // filed under artist1 should surface; tracks under artist2 should not.
        val artist1 = testDb.insertArtist("Pink Floyd", "pink floyd")
        val artist2 = testDb.insertArtist("Led Zeppelin", "led zeppelin")
        testDb.insertTrack(artist1, albumId = null, title = "Comfortably Numb", filePath = "/pf/01.flac")
        testDb.insertTrack(artist1, albumId = null, title = "Wish You Were Here", filePath = "/pf/02.flac")
        testDb.insertTrack(artist2, albumId = null, title = "Stairway to Heaven", filePath = "/lz/01.flac")
        testDb.insertTrack(artist2, albumId = null, title = "Black Dog", filePath = "/lz/02.flac")

        val items = snapshot(source.browse(BrowseScope.TracksOfArtist(ArtistId(artist1))))

        assertEquals(2, items.size)
        assertTrue(items.all { it.kind == MediaItem.Kind.Track })
        val titles = items.map { it.title }.toSet()
        assertEquals(setOf("Comfortably Numb", "Wish You Were Here"), titles)
    }

    @Test
    fun browse_AlbumsOfArtist_filtersByArtistId() = runTest {
        // album.artist_id is the album-artist (per album.sq:43 selectByArtist).
        // Albums whose artist_id matches the queried artist surface; others do not.
        val artist1 = testDb.insertArtist("Pink Floyd", "pink floyd")
        val artist2 = testDb.insertArtist("Led Zeppelin", "led zeppelin")
        testDb.insertAlbum(artist1, "The Wall", year = 1979)
        testDb.insertAlbum(artist1, "Animals", year = 1977)
        testDb.insertAlbum(artist2, "IV", year = 1971)

        val items = snapshot(source.browse(BrowseScope.AlbumsOfArtist(ArtistId(artist1))))

        assertEquals(2, items.size)
        assertTrue(items.all { it.kind == MediaItem.Kind.Album })
        val titles = items.map { it.title }.toSet()
        assertEquals(setOf("The Wall", "Animals"), titles)
    }

    @Test
    fun browse_TracksOfPlaylist_returnsInPlaylistOrder() = runTest {
        // selectTracksOfPlaylist orders by playlist_track.position (NOT track
        // title-alphabetical). Insert tracks A/B/C; add to playlist in order
        // [C, A, B] so positions are C=0, A=1, B=2 (maxPositionInPlaylist
        // returns -1 on empty, then +1 → 0; subsequent appends → 1, 2).
        // Expected browse order: C, A, B (playlist position, not alphabetical).
        val artistId = testDb.insertArtist("Test Artist", "test artist")
        val trackA = testDb.insertTrack(artistId, title = "A", filePath = "/p/a.flac")
        val trackB = testDb.insertTrack(artistId, title = "B", filePath = "/p/b.flac")
        val trackC = testDb.insertTrack(artistId, title = "C", filePath = "/p/c.flac")
        val playlistId = testDb.insertPlaylist("Custom Order")
        testDb.insertPlaylistTrack(playlistId, trackC)  // position = 0
        testDb.insertPlaylistTrack(playlistId, trackA)  // position = 1
        testDb.insertPlaylistTrack(playlistId, trackB)  // position = 2

        val items = snapshot(source.browse(BrowseScope.TracksOfPlaylist(PlaylistId(playlistId))))

        assertEquals(3, items.size)
        assertTrue(items.all { it.kind == MediaItem.Kind.Track })
        assertEquals(listOf("C", "A", "B"), items.map { it.title })
    }

    @Test
    fun browse_RecentlyAdded_orderedByDateAddedDesc() = runTest {
        // selectRecentlyAdded orders by date_added_ms DESC. Insert three
        // tracks with explicit dateAddedMs (newest -> oldest is C, B, A);
        // expect that exact order out.
        val artistId = testDb.insertArtist("Test Artist", "test artist")
        testDb.insertTrack(
            artistId, title = "Older", filePath = "/r/older.flac",
            dateAddedMs = TestDb.NOW_MS - 2_000L,
        )
        testDb.insertTrack(
            artistId, title = "Newer", filePath = "/r/newer.flac",
            dateAddedMs = TestDb.NOW_MS,
        )
        testDb.insertTrack(
            artistId, title = "Middle", filePath = "/r/middle.flac",
            dateAddedMs = TestDb.NOW_MS - 1_000L,
        )

        val items = snapshot(source.browse(BrowseScope.RecentlyAdded()))

        assertEquals(3, items.size)
        assertTrue(items.all { it.kind == MediaItem.Kind.Track })
        assertEquals(listOf("Newer", "Middle", "Older"), items.map { it.title })
    }

    @Test
    fun browse_RecentlyPlayed_filtersAndOrdersByLastPlayed() = runTest {
        // selectRecentlyPlayed filters WHERE last_played_ms IS NOT NULL and
        // orders by last_played_ms DESC. Tracks with NULL last_played_ms
        // (the table default) MUST be excluded.
        //
        // TestDb.insertTrack: setting playCount alone no longer auto-populates
        // last_played_ms; must pass BOTH playCount AND lastPlayedMs to make a
        // row eligible for RecentlyPlayed (mirrors markPlayed semantics).
        val artistId = testDb.insertArtist("Test Artist", "test artist")
        testDb.insertTrack(
            artistId, title = "Recent", filePath = "/p/recent.flac",
            playCount = 1L, lastPlayedMs = TestDb.NOW_MS,
        )
        testDb.insertTrack(
            artistId, title = "Older", filePath = "/p/older.flac",
            playCount = 1L, lastPlayedMs = TestDb.NOW_MS - 1_000L,
        )
        testDb.insertTrack(artistId, title = "NeverPlayed1", filePath = "/p/np1.flac")
        testDb.insertTrack(artistId, title = "NeverPlayed2", filePath = "/p/np2.flac")

        val items = snapshot(source.browse(BrowseScope.RecentlyPlayed()))

        assertEquals(2, items.size)
        assertTrue(items.all { it.kind == MediaItem.Kind.Track })
        assertEquals(listOf("Recent", "Older"), items.map { it.title })
    }

    @Test
    fun search_findsTrackByTitle() = runTest {
        // FTS5 happy path. The track_search virtual table is contentless and
        // application-managed (see track_search.sq §4.2) — production code
        // populates it via the scanner's end-of-scan FTS5 rebuild. In tests
        // we populate it directly via insertSearchIndex. The rowid MUST
        // equal track.id (the INNER JOIN in searchTracks: rowid = track.id);
        // since this is a fresh in-memory DB and the track is the first
        // inserted, track.id = 1L (AUTOINCREMENT starts at 1; inserts into
        // artist/album do not bump track.id's sequence).
        val artistId = testDb.insertArtist("Foo")
        val albumId = testDb.insertAlbum(artistId, "Bar")
        testDb.insertTrack(artistId, albumId, "Specific Title Here")
        testDb.db.track_searchQueries.insertSearchIndex(
            rowid = 1L,
            title = "Specific Title Here",
            album_name = "Bar",
            artist_name = "Foo",
            album_artist_name = "Foo",
        )

        val results = snapshot(source.search("Specific Title"))

        assertEquals(1, results.size)
        assertEquals("Specific Title Here", results.first().item.title)
    }

    @Test
    fun search_sanitizesFts5SpecialCharacters() = runTest {
        // FTS5 MATCH parses double-quotes, parens, *, :, -, +, ^, ~ as
        // operators. sanitizeFtsQuery (FtsSanitize.kt) strips those
        // operator chars and wraps the remaining tokens in double quotes
        // (apostrophes survive the strip and become phrase-literals inside
        // the quoted token). Without sanitization, a user typing
        // `let's` could surface as a syntax error from FTS5 — or worse, a
        // future query like `foo*` (user thinks "wildcard") would mis-parse.
        // This test verifies search() does NOT throw on apostrophe input.
        val artistId = testDb.insertArtist("Foo")
        testDb.insertTrack(artistId, null, "Let's Go Crazy")
        testDb.db.track_searchQueries.insertSearchIndex(
            rowid = 1L,
            title = "Let's Go Crazy",
            album_name = "",
            artist_name = "Foo",
            album_artist_name = "Foo",
        )

        // Must not throw — primary assertion is the absence of an FTS5
        // syntax error. We also assert the row matches, to ensure the
        // sanitized form isn't producing a no-match query (which would
        // silently "pass" without actually exercising the sanitizer).
        val results = snapshot(source.search("let's"))
        assertEquals(1, results.size)
    }

    @Test
    fun browse_MostPlayed_filtersAndOrdersByPlayCount() = runTest {
        // selectMostPlayed filters WHERE play_count > 0 and orders by
        // play_count DESC. Tracks with play_count = 0 (the table default)
        // MUST be excluded.
        //
        // Per TestDb L143-145 — production model is "if it's been played,
        // last_played_ms is set". Pass both playCount AND lastPlayedMs so
        // fixtures mirror reality (and to keep the setPlayStats path
        // self-consistent: playCount=0 with non-null lastPlayedMs would be
        // an impossible state for production code).
        val artistId = testDb.insertArtist("Test Artist", "test artist")
        testDb.insertTrack(
            artistId, title = "TopPlayed", filePath = "/m/top.flac",
            playCount = 5L, lastPlayedMs = TestDb.NOW_MS,
        )
        testDb.insertTrack(
            artistId, title = "MidPlayed", filePath = "/m/mid.flac",
            playCount = 3L, lastPlayedMs = TestDb.NOW_MS - 1_000L,
        )
        testDb.insertTrack(artistId, title = "Unplayed1", filePath = "/m/u1.flac")
        testDb.insertTrack(artistId, title = "Unplayed2", filePath = "/m/u2.flac")

        val items = snapshot(source.browse(BrowseScope.MostPlayed()))

        assertEquals(2, items.size)
        assertTrue(items.all { it.kind == MediaItem.Kind.Track })
        assertEquals(listOf("TopPlayed", "MidPlayed"), items.map { it.title })
    }
}
