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
}
