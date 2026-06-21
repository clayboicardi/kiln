package com.clayworks.kiln.library.scan

import arrow.core.Either
import com.clayworks.kiln.library.source.TestDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackAnalysisRunnerTest {

    private val testDb = TestDb()

    @AfterTest fun tearDown() = testDb.close()

    @Test
    fun `empty library returns zero counts`() = runBlocking {
        val analyzer = FakeTrackAnalyzer(emptyMap())
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined, LibraryWriteLock())
        val result = runner.runOnce()
        assertEquals(0, result.tracksAnalyzed)
        assertEquals(0, result.tracksSkipped)
        assertEquals(0, result.albumsAggregated)
        assertTrue(result.durationMs >= 0L)
    }

    @Test
    fun `three tracks one album persists per-track and per-album values`() = runBlocking {
        val artistId = testDb.insertArtist("Test Artist")
        val albumId = testDb.insertAlbum(artistId, "Test Album")
        val t1 = testDb.insertTrack(artistId, albumId, "T1", filePath = "/test/t1.flac")
        val t2 = testDb.insertTrack(artistId, albumId, "T2", filePath = "/test/t2.flac")
        val t3 = testDb.insertTrack(artistId, albumId, "T3", filePath = "/test/t3.flac")

        val analyzer = FakeTrackAnalyzer(mapOf(
            "/test/t1.flac" to Either.Right(TrackLoudness(integratedLufs = -23.0, truePeakDbtp = -1.0)),
            "/test/t2.flac" to Either.Right(TrackLoudness(integratedLufs = -18.0, truePeakDbtp =  0.0)),
            "/test/t3.flac" to Either.Right(TrackLoudness(integratedLufs = -28.0, truePeakDbtp = -3.0)),
        ))
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined, LibraryWriteLock())

        val result = runner.runOnce()
        assertEquals(3, result.tracksAnalyzed)
        assertEquals(0, result.tracksSkipped)
        assertEquals(1, result.albumsAggregated)

        // Per-track: replay_gain_track_db = -18.0 - integratedLufs.
        val row1 = testDb.db.trackQueries.selectById(t1).executeAsOne()
        assertNotNull(row1.replay_gain_track_db)
        assertEquals(5.0, row1.replay_gain_track_db!!, 1e-9)        // -18 - (-23) = +5
        // Peak linear: 10^(-1/20) ≈ 0.8913
        assertNotNull(row1.replay_gain_track_peak)
        assertTrue(abs(row1.replay_gain_track_peak!! - 0.8913) < 1e-3)

        // Per-album: album LUFS for (-23,-18,-28) ≈ -21.26 → album_db = -18 - (-21.26) = +3.26.
        val row2 = testDb.db.trackQueries.selectById(t2).executeAsOne()
        assertNotNull(row2.replay_gain_album_db)
        assertTrue(
            abs(row2.replay_gain_album_db!! - 3.26) < 0.05,
            "expected ~3.26, got ${row2.replay_gain_album_db}",
        )
        // Album peak linear = max of track peak linears.
        // t2 had dBTP = 0 → linear = 1.0; biggest of the three. Album peak = 1.0.
        assertNotNull(row2.replay_gain_album_peak)
        assertTrue(abs(row2.replay_gain_album_peak!! - 1.0) < 1e-6)
    }

    @Test
    fun `two albums each get an independent rollup`() = runBlocking {
        val artistId = testDb.insertArtist("Test Artist")
        val albumA = testDb.insertAlbum(artistId, "Album A")
        val albumB = testDb.insertAlbum(artistId, "Album B")
        val ta = testDb.insertTrack(artistId, albumA, "TA", filePath = "/test/a.flac")
        val tb = testDb.insertTrack(artistId, albumB, "TB", filePath = "/test/b.flac")

        val analyzer = FakeTrackAnalyzer(mapOf(
            "/test/a.flac" to Either.Right(TrackLoudness(-23.0, -2.0)),
            "/test/b.flac" to Either.Right(TrackLoudness(-15.0, +1.0)),
        ))
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined, LibraryWriteLock())
        val result = runner.runOnce()

        assertEquals(2, result.tracksAnalyzed)
        assertEquals(2, result.albumsAggregated)

        // Single-track albums: album_db ≈ track_db (within floating-point rounding;
        // albumIntegratedLufs does an energy-domain roundtrip that introduces ~1e-14 drift).
        val rowA = testDb.db.trackQueries.selectById(ta).executeAsOne()
        assertNotNull(rowA.replay_gain_track_db)
        assertNotNull(rowA.replay_gain_album_db)
        assertTrue(
            abs(rowA.replay_gain_track_db!! - rowA.replay_gain_album_db!!) < 1e-9,
            "album A: track_db=${rowA.replay_gain_track_db}, album_db=${rowA.replay_gain_album_db}",
        )
        val rowB = testDb.db.trackQueries.selectById(tb).executeAsOne()
        assertNotNull(rowB.replay_gain_track_db)
        assertNotNull(rowB.replay_gain_album_db)
        assertTrue(
            abs(rowB.replay_gain_track_db!! - rowB.replay_gain_album_db!!) < 1e-9,
            "album B: track_db=${rowB.replay_gain_track_db}, album_db=${rowB.replay_gain_album_db}",
        )

        // Different albums got different aggregates (sanity).
        assertTrue(rowA.replay_gain_album_db!! != rowB.replay_gain_album_db!!)
    }

    @Test
    fun `analyzer Left for one track skips that row but persists others`() = runBlocking {
        val artistId = testDb.insertArtist("Test Artist")
        val albumId = testDb.insertAlbum(artistId, "Test Album")
        val good = testDb.insertTrack(artistId, albumId, "Good", filePath = "/test/good.flac")
        val bad  = testDb.insertTrack(artistId, albumId, "Bad",  filePath = "/test/bad.mp3")

        val analyzer = FakeTrackAnalyzer(mapOf(
            "/test/good.flac" to Either.Right(TrackLoudness(-18.0, -0.5)),
            "/test/bad.mp3"   to Either.Left(TrackAnalysisError.CodecUnsupported("MP3")),
        ))
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined, LibraryWriteLock())
        val result = runner.runOnce()

        assertEquals(1, result.tracksAnalyzed)
        assertEquals(1, result.tracksSkipped)
        assertEquals(1, result.albumsAggregated)  // good's album still aggregates (one track is enough)

        // Good was persisted.
        val rowGood = testDb.db.trackQueries.selectById(good).executeAsOne()
        assertNotNull(rowGood.replay_gain_track_db)
        // Bad was not.
        val rowBad = testDb.db.trackQueries.selectById(bad).executeAsOne()
        assertNull(rowBad.replay_gain_track_db)
    }

    @Test
    fun `tracks without an album_id are analyzed but skip rollup`() = runBlocking {
        val artistId = testDb.insertArtist("Test Artist")
        val orphan = testDb.insertTrack(artistId, albumId = null, title = "Orphan", filePath = "/test/orph.flac")

        val analyzer = FakeTrackAnalyzer(mapOf(
            "/test/orph.flac" to Either.Right(TrackLoudness(-20.0, -1.5)),
        ))
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined, LibraryWriteLock())
        val result = runner.runOnce()

        assertEquals(1, result.tracksAnalyzed)
        assertEquals(0, result.albumsAggregated)

        val row = testDb.db.trackQueries.selectById(orphan).executeAsOne()
        assertNotNull(row.replay_gain_track_db)
        assertNull(row.replay_gain_album_db)  // no album → no rollup
    }

    @Test
    fun `already-populated tracks are not re-analyzed`() = runBlocking {
        val artistId = testDb.insertArtist("Test Artist")
        val albumId = testDb.insertAlbum(artistId, "Test Album")
        val preExisting = testDb.insertTrack(
            artistId, albumId, "Pre",
            filePath = "/test/pre.flac",
            replayGainTrackDb = 4.2,
            replayGainTrackPeak = 0.95,
        )
        val fresh = testDb.insertTrack(artistId, albumId, "Fresh", filePath = "/test/fresh.flac")

        val analyzer = FakeTrackAnalyzer(mapOf(
            "/test/fresh.flac" to Either.Right(TrackLoudness(-22.0, -1.0)),
        ))
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined, LibraryWriteLock())
        val result = runner.runOnce()

        assertEquals(1, result.tracksAnalyzed)
        assertEquals(listOf("/test/fresh.flac"), analyzer.analyzed)

        // Pre-existing track's stored values are untouched.
        val rowPre = testDb.db.trackQueries.selectById(preExisting).executeAsOne()
        assertEquals(4.2, rowPre.replay_gain_track_db!!, 1e-9)
        assertEquals(0.95, rowPre.replay_gain_track_peak!!, 1e-9)
    }

    @Test
    fun `large library with all-skipping analyzer terminates without infinite loop`() = runBlocking {
        // I1 fix verification: 105 tracks (above PAGE_SIZE=100), all returning
        // Left, must terminate. Pre-fix, the runner would loop forever because
        // skipped rows stay in the worklist and naive offset-advance wouldn't
        // scroll past them across pages.
        val artistId = testDb.insertArtist("Test Artist")
        val albumId = testDb.insertAlbum(artistId, "Test Album")
        val filePaths = (1..105).map { "/test/track-$it.mp3" }
        filePaths.forEach { path ->
            testDb.insertTrack(artistId, albumId, title = path.substringAfterLast('/'), filePath = path)
        }

        val analyzer = FakeTrackAnalyzer(
            results = filePaths.associateWith {
                Either.Left(TrackAnalysisError.CodecUnsupported("MP3"))
            },
        )
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined, LibraryWriteLock())

        val result = runner.runOnce()
        assertEquals(0, result.tracksAnalyzed)
        assertEquals(105, result.tracksSkipped)
        assertEquals(0, result.albumsAggregated)
    }

    @Test
    fun `partial-pass run 1 then run 2 finalizes album_db with all tracks`() = runBlocking {
        // I2 fix verification: an album where run 1 analyzed 2 of 3 tracks leaves
        // album_db set to a partial aggregate. Run 2 (when the 3rd track gets
        // analyzed) must re-aggregate the album with all 3 tracks, not skip it
        // because album_db is non-null.
        val artistId = testDb.insertArtist("Test Artist")
        val albumId = testDb.insertAlbum(artistId, "Test Album")
        val t1 = testDb.insertTrack(artistId, albumId, "T1", filePath = "/test/t1.flac")
        val t2 = testDb.insertTrack(artistId, albumId, "T2", filePath = "/test/t2.flac")
        val t3 = testDb.insertTrack(artistId, albumId, "T3", filePath = "/test/t3.flac")

        val analyzer1 = FakeTrackAnalyzer(mapOf(
            "/test/t1.flac" to Either.Right(TrackLoudness(-23.0, -1.0)),
            "/test/t2.flac" to Either.Right(TrackLoudness(-18.0,  0.0)),
        ))
        val runner1 = TrackAnalysisRunner(testDb.db, analyzer1, Dispatchers.Unconfined, LibraryWriteLock())
        val r1 = runner1.runOnce()
        assertEquals(2, r1.tracksAnalyzed)
        assertEquals(1, r1.tracksSkipped)
        assertEquals(1, r1.albumsAggregated)

        val albumDbAfterRun1 = testDb.db.trackQueries.selectById(t1).executeAsOne()
            .replay_gain_album_db
        assertNotNull(albumDbAfterRun1)

        val analyzer2 = FakeTrackAnalyzer(mapOf(
            "/test/t3.flac" to Either.Right(TrackLoudness(-28.0, -3.0)),
        ))
        val runner2 = TrackAnalysisRunner(testDb.db, analyzer2, Dispatchers.Unconfined, LibraryWriteLock())
        val r2 = runner2.runOnce()
        assertEquals(1, r2.tracksAnalyzed)
        assertEquals(0, r2.tracksSkipped)
        assertEquals(1, r2.albumsAggregated)

        val albumDbAfterRun2 = testDb.db.trackQueries.selectById(t1).executeAsOne()
            .replay_gain_album_db
        assertNotNull(albumDbAfterRun2)
        assertTrue(
            kotlin.math.abs(albumDbAfterRun1!! - albumDbAfterRun2!!) > 0.1,
            "album_db should differ between 2-track ($albumDbAfterRun1) and 3-track " +
                "($albumDbAfterRun2) aggregates by at least 0.1 dB",
        )
    }

    @Test
    fun `runOnceWithProgress emits Started then Progress then Complete`() = runBlocking {
        val artistId = testDb.insertArtist("Test Artist")
        val albumId = testDb.insertAlbum(artistId, "Test Album")
        val t1 = testDb.insertTrack(artistId, albumId, "T1", filePath = "/test/t1.flac")
        val t2 = testDb.insertTrack(artistId, albumId, "T2", filePath = "/test/t2.flac")

        val analyzer = FakeTrackAnalyzer(mapOf(
            "/test/t1.flac" to Either.Right(TrackLoudness(-20.0, -1.0)),
            "/test/t2.flac" to Either.Right(TrackLoudness(-18.0, -0.5)),
        ))
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined, LibraryWriteLock())

        val emissions = runner.runOnceWithProgress().toList()
        assertTrue(emissions.size >= 2, "expected >= 2 emissions, got ${emissions.size}")
        assertTrue(emissions.first() is AnalysisProgress.Started, "first emission must be Started, got ${emissions.first()}")
        assertEquals(2, (emissions.first() as AnalysisProgress.Started).total)
        assertTrue(emissions.last() is AnalysisProgress.Complete, "last emission must be Complete, got ${emissions.last()}")
        val finalResult = (emissions.last() as AnalysisProgress.Complete).result
        assertEquals(2, finalResult.tracksAnalyzed)
        assertEquals(0, finalResult.tracksSkipped)
        assertEquals(1, finalResult.albumsAggregated)
    }

    @Test
    fun `runOnceWithProgress on empty library emits Started 0 then Complete 0`() = runBlocking {
        val analyzer = FakeTrackAnalyzer(emptyMap())
        val runner = TrackAnalysisRunner(testDb.db, analyzer, Dispatchers.Unconfined, LibraryWriteLock())
        val emissions = runner.runOnceWithProgress().toList()
        assertTrue(emissions.first() is AnalysisProgress.Started)
        assertEquals(0, (emissions.first() as AnalysisProgress.Started).total)
        assertTrue(emissions.last() is AnalysisProgress.Complete)
        assertEquals(0, (emissions.last() as AnalysisProgress.Complete).result.tracksAnalyzed)
    }

    @Test
    fun `runOnceWithProgress runs analyzer on ioDispatcher not collector dispatcher`() = runBlocking {
        // Regression test for the Android-ANR bug: without flowOn(ioDispatcher),
        // the analyzer's analyze() method would run on the collector's
        // dispatcher (Dispatchers.Main in production), blocking the UI.
        val artistId = testDb.insertArtist("Test Artist")
        val albumId = testDb.insertAlbum(artistId, "Test Album")
        testDb.insertTrack(artistId, albumId, "T1", filePath = "/test/t1.flac")

        // Custom dispatcher that records the thread name at the analyzer call.
        val ioDispatcher = kotlinx.coroutines.Dispatchers.IO
        val analyzerThreadNames = mutableListOf<String>()
        val analyzer = object : TrackAnalyzer {
            override suspend fun analyze(filePath: String, codec: String): arrow.core.Either<TrackAnalysisError, TrackLoudness> {
                analyzerThreadNames += Thread.currentThread().name
                return arrow.core.Either.Right(TrackLoudness(-20.0, -1.0))
            }
        }
        val runner = TrackAnalysisRunner(testDb.db, analyzer, ioDispatcher, LibraryWriteLock())

        // Collect on the test's runBlocking thread (= main-thread in production analog).
        val mainThreadName = Thread.currentThread().name
        runner.runOnceWithProgress().toList()

        assertTrue(analyzerThreadNames.isNotEmpty(), "analyzer should have been called")
        val analyzerThread = analyzerThreadNames.first()
        assertTrue(
            analyzerThread != mainThreadName,
            "analyzer ran on $analyzerThread which matches collector thread $mainThreadName — flowOn(ioDispatcher) is missing",
        )
        // Bonus: confirm it's a Dispatchers.IO thread (name pattern "DefaultDispatcher-worker-N" or similar)
        assertTrue(
            analyzerThread.contains("worker") || analyzerThread.contains("DefaultDispatcher"),
            "expected an IO worker thread, got '$analyzerThread'",
        )
    }
}
