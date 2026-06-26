// Query-level regression for the #31 item-3 backfill TOCTOU guard.
//
// AndroidFormatFactBackfill reads the worklist page + native format facts OFF
// the writer thread, so a concurrent rescan can change a row between the read
// and the (queued) write. The guarded writes — updateTrackFormatFactsIfUnchanged
// / markBackfilledNoMetadataIfUnchanged — must therefore match 0 rows when any
// of id / file_path / file_mtime_ms / file_size_bytes no longer matches (same
// TOCTOU class as item-2's updateTrackReplayGainIfUnchanged), so the stale facts
// are dropped and the row re-backfills next pass.
//
// These exercise the generated queries directly (no private-method seam on the
// androidMain-only backfill, which can't run on this desktop JVM) — the queries
// live in commonMain (track.sq) so they're reachable from desktopTest via TestDb.

package com.clayworks.kiln.library.scan

import com.clayworks.kiln.library.source.TestDb
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackfillGuardQueryTest {

    private val testDb = TestDb()

    @AfterTest fun tearDown() = testDb.close()

    private fun seedTrack(): Long {
        val artistId = testDb.insertArtist("A")
        // TestDb defaults: sample_rate_hz=44100, bit_depth=16, channels=2,
        // bitrate_kbps=null, has_embedded_art=0, metadata_backfilled_at_ms=NULL.
        return testDb.insertTrack(
            artistId, title = "T", filePath = "/x.flac", fileMtimeMs = 111L, fileSizeBytes = 4096L,
        )
    }

    // ---------- updateTrackFormatFactsIfUnchanged ----------

    @Test
    fun `format facts persist when id, file_path, mtime and size all match`() {
        val id = seedTrack()
        testDb.db.trackQueries.updateTrackFormatFactsIfUnchanged(
            sampleRateHz = 96_000L, bitDepth = 24L, channels = 1L, bitrateKbps = 1_411L,
            hasEmbeddedArt = 1L, backfilledAtMs = 555L,
            id = id, filePath = "/x.flac", fileMtimeMs = 111L, fileSizeBytes = 4096L,
        )
        assertEquals(1L, testDb.db.trackQueries.selectChanges().executeAsOne())
        val row = testDb.db.trackQueries.selectById(id).executeAsOne()
        assertEquals(96_000L, row.sample_rate_hz)
        assertEquals(24L, row.bit_depth)
        assertEquals(1L, row.channels)
        assertEquals(1_411L, row.bitrate_kbps)
        assertEquals(1L, row.has_embedded_art)
        assertEquals(555L, row.metadata_backfilled_at_ms)
    }

    @Test
    fun `format facts are a no-op when file_path changed`() {
        val id = seedTrack()
        testDb.db.trackQueries.updateTrackFormatFactsIfUnchanged(
            sampleRateHz = 96_000L, bitDepth = 24L, channels = 1L, bitrateKbps = 1_411L,
            hasEmbeddedArt = 1L, backfilledAtMs = 555L,
            id = id, filePath = "/STALE.flac", fileMtimeMs = 111L, fileSizeBytes = 4096L,
        )
        assertEquals(0L, testDb.db.trackQueries.selectChanges().executeAsOne())
        val row = testDb.db.trackQueries.selectById(id).executeAsOne()
        assertNull(row.metadata_backfilled_at_ms, "stale facts must NOT stamp the changed row")
        assertEquals(44_100L, row.sample_rate_hz, "placeholder sample rate must be untouched")
        assertEquals(2L, row.channels)
    }

    @Test
    fun `format facts are a no-op when mtime changed`() {
        val id = seedTrack()
        testDb.db.trackQueries.updateTrackFormatFactsIfUnchanged(
            sampleRateHz = 96_000L, bitDepth = 24L, channels = 1L, bitrateKbps = 1_411L,
            hasEmbeddedArt = 1L, backfilledAtMs = 555L,
            id = id, filePath = "/x.flac", fileMtimeMs = 999L, fileSizeBytes = 4096L,
        )
        assertEquals(0L, testDb.db.trackQueries.selectChanges().executeAsOne())
        val row = testDb.db.trackQueries.selectById(id).executeAsOne()
        assertNull(row.metadata_backfilled_at_ms)
        assertEquals(44_100L, row.sample_rate_hz)
    }

    @Test
    fun `format facts are a no-op when file_size changed`() {
        val id = seedTrack()
        testDb.db.trackQueries.updateTrackFormatFactsIfUnchanged(
            sampleRateHz = 96_000L, bitDepth = 24L, channels = 1L, bitrateKbps = 1_411L,
            hasEmbeddedArt = 1L, backfilledAtMs = 555L,
            id = id, filePath = "/x.flac", fileMtimeMs = 111L, fileSizeBytes = 9_999L,
        )
        assertEquals(0L, testDb.db.trackQueries.selectChanges().executeAsOne())
        assertNull(testDb.db.trackQueries.selectById(id).executeAsOne().metadata_backfilled_at_ms)
    }

    // ---------- markBackfilledNoMetadataIfUnchanged ----------

    @Test
    fun `no-metadata stamp persists when keys match`() {
        val id = seedTrack()
        testDb.db.trackQueries.markBackfilledNoMetadataIfUnchanged(
            backfilledAtMs = 777L, id = id, filePath = "/x.flac", fileMtimeMs = 111L, fileSizeBytes = 4096L,
        )
        assertEquals(1L, testDb.db.trackQueries.selectChanges().executeAsOne())
        assertEquals(777L, testDb.db.trackQueries.selectById(id).executeAsOne().metadata_backfilled_at_ms)
    }

    @Test
    fun `no-metadata stamp is a no-op when mtime changed`() {
        val id = seedTrack()
        testDb.db.trackQueries.markBackfilledNoMetadataIfUnchanged(
            backfilledAtMs = 777L, id = id, filePath = "/x.flac", fileMtimeMs = 999L, fileSizeBytes = 4096L,
        )
        assertEquals(0L, testDb.db.trackQueries.selectChanges().executeAsOne())
        assertNull(
            testDb.db.trackQueries.selectById(id).executeAsOne().metadata_backfilled_at_ms,
            "a row a concurrent scan reset must NOT be drained from the worklist",
        )
    }
}
