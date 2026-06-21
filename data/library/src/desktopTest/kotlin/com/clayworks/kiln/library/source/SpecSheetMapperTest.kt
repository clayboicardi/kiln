// Spec-sheet read-model coverage (Task A1). Exercises LibraryStatsSource —
// the NEW narrow interface co-implemented by LocalLibrarySource — against a
// fresh in-memory KilnDatabase via TestDb.
//
// Location note: brief §1 sketches this under commonTest/, but TestDb +
// JdbcSqliteDriver are JVM-only (see TestDb.kt header + build.gradle.kts:
// sqldelight.sqlite.driver lands in desktopMain/desktopTest). Hosting in
// commonTest would fail to resolve the import — this file joins the existing
// LocalLibrarySourceTest in desktopTest for the same reason.
//
// Hot-flow collection idiom mirrors LocalLibrarySourceTest: first() on the
// SQLDelight-backed flow yields the current DB snapshot, then completes for
// the buffered collector. We use .first() directly here since each assertion
// reads a single emission.

package com.clayworks.kiln.library.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class SpecSheetMapperTest {
    private val testDb = TestDb()
    private val source = LocalLibrarySource(testDb.db, Dispatchers.Unconfined)

    @AfterTest fun tearDown() = testDb.close()

    @Test
    fun specSheetEntry_maps_format_facts() = runTest {
        val artistId = testDb.insertArtist("Pink Floyd", "pink floyd")
        testDb.insertTrack(
            artistId,
            title = "Comfortably Numb",
            codec = "FLAC",
            sampleRateHz = 96_000L,
            bitDepth = 24L,
            channels = 2L,
            filePath = "/wall/01.flac",
        )

        val entry = source.specSheetEntry("1").first()

        assertNotNull(entry)
        assertEquals("1", entry.trackId)
        assertEquals("Comfortably Numb", entry.title)
        assertEquals("FLAC", entry.codec)
        assertEquals(96_000, entry.sampleRateHz)
        assertEquals(24, entry.bitDepth)
        assertEquals(2, entry.channels)
    }

    @Test
    fun aggregateStats_replayGain_coverage() = runTest {
        val artistId = testDb.insertArtist("Pink Floyd", "pink floyd")
        // One track WITH replay_gain_track_db, one WITHOUT.
        testDb.insertTrack(
            artistId,
            title = "Has RG",
            filePath = "/rg/has.flac",
            replayGainTrackDb = -7.5,
        )
        testDb.insertTrack(
            artistId,
            title = "No RG",
            filePath = "/rg/none.flac",
            replayGainTrackDb = null,
        )

        val aggregate = source.aggregateStats().first()

        assertEquals(2L, aggregate.totalTracks)
        assertEquals(0.5, aggregate.replayGainCoverage)
    }
}
