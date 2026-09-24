// Behavioural tests for the JavaSoundPlayerImpl command/actor model (#28). Each proves a
// defect fix or a falsify safeguard, using the FakeDecodedStream/FakeDecoder/FakeOutputLine doubles.
// Both dispatchers are Unconfined so command + frame processing runs inline when driven from the
// test thread; awaitDrained() barriers the actor before each assertion. Stream events (EOF / decode
// error) are NOT commands, so those tests wait on the observable outcome via awaitStreamEvent first.

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import com.clayworks.kiln.audio.dsp.AudioFrame
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
import com.clayworks.kiln.audio.playback.output.OutputLineException
import com.clayworks.kiln.audio.playback.output.PcmOutputLineFactory
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.settings.SettingsRepository
import com.clayworks.kiln.library.settings.ThemeMode
import com.clayworks.kiln.library.source.AudioCodec
import com.clayworks.kiln.library.source.BrowseScope
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.LocalSourceCapabilities
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.MusicSource
import com.clayworks.kiln.library.source.Playable
import com.clayworks.kiln.library.source.SearchResult
import com.clayworks.kiln.library.source.SourceCapabilities
import com.clayworks.kiln.library.source.SourceError
import com.clayworks.kiln.library.source.SourceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaSoundPlayerActorTest {

    private val fmt = DecodedAudioFormat(44_100, 16, 2, SampleFormat.PCM_S16_LE)

    private class StubSettings : SettingsRepository {
        override val themeMode: Flow<ThemeMode> = flowOf(ThemeMode.System)
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override val scanOnLaunch: Flow<Boolean> = flowOf(false)
        override suspend fun setScanOnLaunch(enabled: Boolean) = Unit
        override val autoScanOnFolderAdd: Flow<Boolean> = flowOf(true)
        override suspend fun setAutoScanOnFolderAdd(enabled: Boolean) = Unit
        override val scanFolders: Flow<List<String>> = flowOf(emptyList())
        override suspend fun setScanFolders(folders: List<String>) = Unit
        override val replayGainMode: Flow<ReplayGainMode> = flowOf(ReplayGainMode.Off)
        override suspend fun setReplayGainMode(mode: ReplayGainMode) = Unit
        override val replayGainPreAmpDb: Flow<Double> = flowOf(0.0)
        override suspend fun setReplayGainPreAmpDb(db: Double) = Unit
    }

    private class ResolvingSource(private val ids: Set<String>) : MusicSource {
        override val id = SourceId("test")
        override val displayName = "test"
        override val capabilities: SourceCapabilities = LocalSourceCapabilities
        override suspend fun search(query: String, limit: Int): Flow<SearchResult> = emptyFlow()
        override suspend fun browse(scope: BrowseScope): Flow<MediaItem> = emptyFlow()
        override suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable> =
            if (itemId.value in ids) {
                Either.Right(
                    Playable(
                        itemId = itemId, sourceId = id, uri = "file:///${itemId.value}.flac",
                        codec = AudioCodec.FLAC, sampleRateHz = 44_100, bitDepth = 16, channels = 2,
                        bitrateKbps = null, durationMs = 0L, replayGain = null,
                    ),
                )
            } else {
                Either.Left(SourceError.ItemNotFound(itemId))
            }
    }

    private fun item(id: String) = MediaItem(
        itemId = ItemId(id), sourceId = SourceId("test"), kind = MediaItem.Kind.Track,
        title = "Track $id", subtitle = null, durationMs = null, artUri = null, metadata = emptyMap(),
    )

    private fun newPlayer(
        ids: List<String>,
        streams: Map<String, FakeDecodedStream>,
        line: FakeOutputLine = FakeOutputLine(),
    ): JavaSoundPlayerImpl = JavaSoundPlayerImpl(
        audioDispatcher = Dispatchers.Unconfined,
        decodeDispatcher = Dispatchers.Unconfined,
        decoder = FakeDecoder(streams),
        source = ResolvingSource(ids.toSet()),
        settings = StubSettings(),
        rgProcessor = ReplayGainProcessor(),
        lineFactory = { line },
    )

    @Test
    fun `loadQueue while playing switches tracks (defect 1)`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val sB = FakeDecodedStream(fmt)
        val player = newPlayer(listOf("a", "b", "c"), mapOf("a" to sA, "b" to sB))
        player.loadQueue(listOf(item("a"), item("b"), item("c")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        assertEquals("a", player.queue.value.currentItem?.itemId?.value, "A should be current after initial load")

        // "Click track B while A is playing" — must switch (the old design did nothing until EOF).
        player.loadQueue(listOf(item("a"), item("b"), item("c")), startIndex = 1, autoPlay = true)
        player.awaitDrained()
        assertEquals(1, player.queue.value.currentIndex)
        assertEquals("b", player.queue.value.currentItem?.itemId?.value, "must switch to B mid-playback")
        assertTrue(sA.closed, "stream A must be torn down on switch")
    }

    @Test
    fun `skipToNext advances on a multi-item queue (defect 2b)`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val sB = FakeDecodedStream(fmt)
        val player = newPlayer(listOf("a", "b", "c"), mapOf("a" to sA, "b" to sB))
        player.loadQueue(listOf(item("a"), item("b"), item("c")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        player.skipToNext()
        player.awaitDrained()
        assertEquals(1, player.queue.value.currentIndex)
        assertEquals("b", player.queue.value.currentItem?.itemId?.value)
        assertTrue(sA.closed, "stream A torn down on skip")
    }

    @Test
    fun `skip works while paused (safeguard 3 — paused still selects commands)`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val sB = FakeDecodedStream(fmt)
        val player = newPlayer(listOf("a", "b"), mapOf("a" to sA, "b" to sB))
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        assertEquals(PlayerState.Ready(isPlaying = true), player.state.value)
        player.pause()
        player.awaitDrained()
        assertEquals(PlayerState.Ready(isPlaying = false), player.state.value, "should be paused")
        // Skip while paused — the actor must still service the command (no pause-gate deadlock).
        player.skipToNext()
        player.awaitDrained()
        assertEquals(1, player.queue.value.currentIndex)
        assertEquals("b", player.queue.value.currentItem?.itemId?.value)
    }

    @Test
    fun `rapid skip ends at the correct track (skip-spam correctness)`() = runBlocking {
        val ids = listOf("a", "b", "c", "d", "e")
        val streams = ids.associateWith { FakeDecodedStream(fmt) }
        val player = newPlayer(ids, streams)
        player.loadQueue(ids.map { item(it) }, startIndex = 0, autoPlay = true)
        player.awaitDrained()
        repeat(4) { player.skipToNext() }
        player.awaitDrained()
        assertEquals(4, player.queue.value.currentIndex)
        assertEquals("e", player.queue.value.currentItem?.itemId?.value)
    }

    @Test
    fun `EOF auto-advances to the next track`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val sB = FakeDecodedStream(fmt)
        val player = newPlayer(listOf("a", "b"), mapOf("a" to sA, "b" to sB))
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        sA.signalEof()  // stream A ends normally
        awaitStreamEvent { player.queue.first { it.currentIndex == 1 } }
        player.awaitDrained()
        assertEquals(1, player.queue.value.currentIndex, "EOF should advance to B")
        assertEquals("b", player.queue.value.currentItem?.itemId?.value)
        assertTrue(sA.closed)
    }

    @Test
    fun `decode error flips to Error and does NOT advance`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val sB = FakeDecodedStream(fmt)
        val player = newPlayer(listOf("a", "b"), mapOf("a" to sA, "b" to sB))
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        sA.signalError(RuntimeException("decode boom"))  // stream A fails
        awaitStreamEvent { player.state.first { it is PlayerState.Error } }
        player.awaitDrained()
        assertTrue(player.state.value is PlayerState.Error, "decode error must surface as Error, not EOF")
        assertEquals(0, player.queue.value.currentIndex, "must NOT advance to B on a decode error")
    }

    @Test
    fun `release mid-playback tears down without crashing`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val player = newPlayer(listOf("a", "b"), mapOf("a" to sA, "b" to FakeDecodedStream(fmt)))
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        player.release()  // no awaitDrained — the actor exits on Release
        assertTrue(sA.closed, "release must tear down the active stream")
    }

    @Test
    fun `EOF tail waits for the line's queued audio before advancing`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val line = FakeOutputLine(bufferNanos = 100_000_000L, queued = 80_000_000L) // 80 ms queued
        val player = newPlayer(listOf("a", "b"), mapOf("a" to sA, "b" to FakeDecodedStream(fmt)), line)
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        val t0 = System.nanoTime()
        sA.signalEof()
        awaitStreamEvent { player.queue.first { it.currentIndex == 1 } }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(elapsedMs >= 70, "EOF must play out the ~80 ms still queued; advanced after $elapsedMs ms")
    }

    @Test
    fun `EOF tail is capped at twice the line buffer`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val line = FakeOutputLine(bufferNanos = 50_000_000L, queued = 5_000_000_000L) // claims 5 s queued
        val player = newPlayer(listOf("a", "b"), mapOf("a" to sA, "b" to FakeDecodedStream(fmt)), line)
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        val t0 = System.nanoTime()
        sA.signalEof()
        awaitStreamEvent { player.queue.first { it.currentIndex == 1 } }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue(elapsedMs >= 90, "tail must still play out the 2 × 50 ms cap; advanced after $elapsedMs ms")
        assertTrue(elapsedMs < 1_000, "tail must cap at 2 × 50 ms, not wait 5 s; took $elapsedMs ms")
    }

    private val fmt24 = DecodedAudioFormat(44_100, 24, 2, SampleFormat.PCM_S24_LE)

    private fun frameAt(ms: Long) = AudioFrame(bytes = ByteArray(4), byteCount = 4, sampleCount = 1, timestampMs = ms)

    private fun playerWith(
        ids: List<String>,
        streams: Map<String, FakeDecodedStream>,
        factory: PcmOutputLineFactory,
    ) = JavaSoundPlayerImpl(
        audioDispatcher = Dispatchers.Unconfined,
        decodeDispatcher = Dispatchers.Unconfined,
        decoder = FakeDecoder(streams),
        source = ResolvingSource(ids.toSet()),
        settings = StubSettings(),
        rgProcessor = ReplayGainProcessor(),
        lineFactory = factory,
    )

    /** Accepts 16-bit (returns [line]); rejects 24-bit the way WASAPI/Java Sound do. */
    private fun rejecting24(line: FakeOutputLine) = PcmOutputLineFactory { f ->
        if (f.bitDepth == 24) throw OutputLineException.FormatRejected(f, "24-bit rejected") else line
    }

    @Test
    fun `failed decoder open resets the position to zero`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val player = newPlayer(listOf("a", "b"), mapOf("a" to sA)) // no stream for b → decoder fails
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        sA.emit(frameAt(5_000))
        awaitStreamEvent { player.positionMs.first { it == 5_000L } }
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 1, autoPlay = true)
        player.awaitDrained()
        assertTrue(player.state.value is PlayerState.Error)
        assertEquals(0L, player.positionMs.value, "a failed open must not keep the previous track's position")
    }

    @Test
    fun `failed line open resets the position and reports OutputFormatUnsupported`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val player = playerWith(
            listOf("a", "b"),
            mapOf("a" to sA, "b" to FakeDecodedStream(fmt24)),
            rejecting24(FakeOutputLine()),
        )
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        sA.emit(frameAt(5_000))
        awaitStreamEvent { player.positionMs.first { it == 5_000L } }
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 1, autoPlay = true)
        player.awaitDrained()
        val error = (player.state.value as PlayerState.Error).cause
        assertEquals(PlayerError.OutputFormatUnsupported(44_100, 24, 2, "24-bit rejected"), error)
        assertEquals(0L, player.positionMs.value)
    }

    @Test
    fun `device problem at open reports DeviceUnavailable`() = runBlocking {
        val player = playerWith(
            listOf("a"),
            mapOf("a" to FakeDecodedStream(fmt)),
            PcmOutputLineFactory { throw OutputLineException.DeviceProblem("no audio output device") },
        )
        player.loadQueue(listOf(item("a")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        assertEquals(
            PlayerError.DeviceUnavailable("no audio output device"),
            (player.state.value as PlayerState.Error).cause,
        )
    }

    @Test
    fun `device problem mid-stream reports DeviceUnavailable and does not advance`() = runBlocking {
        val sA = FakeDecodedStream(fmt)
        val line = FakeOutputLine()
        val player = newPlayer(listOf("a", "b"), mapOf("a" to sA, "b" to FakeDecodedStream(fmt)), line)
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        line.writeFailure = OutputLineException.DeviceProblem("device invalidated")
        sA.emit(frameAt(1_000))
        awaitStreamEvent { player.state.first { it is PlayerState.Error } }
        player.awaitDrained()
        assertEquals(PlayerError.DeviceUnavailable("device invalidated"), (player.state.value as PlayerState.Error).cause)
        assertEquals(0, player.queue.value.currentIndex, "a dead device must not trigger any advance")
    }

    // ----- controller ruling R9: line calls outside write() that throw must not kill the actor -----
    // (WASAPI's GetCurrentPadding / SetMasterVolume can throw DeviceProblem. runActor only has
    // try/finally, so an escape there would silently drop every later command.)

    @Test
    fun `a queuedNanos failure at EOF skips the tail and still advances`() = runBlocking {
        val ids = listOf("a", "b", "c")
        val streams = ids.associateWith { FakeDecodedStream(fmt) }
        val line = FakeOutputLine()
        val player = newPlayer(ids, streams, line)
        player.loadQueue(ids.map { item(it) }, startIndex = 0, autoPlay = true)
        player.awaitDrained()
        line.queuedFailure = OutputLineException.DeviceProblem("device invalidated")
        streams.getValue("a").signalEof()
        awaitStreamEvent { player.queue.first { it.currentIndex == 1 } }
        withTimeout(5_000) { player.awaitDrained() }
        assertEquals(PlayerState.Ready(isPlaying = true), player.state.value)
        // The actor is still alive: a later command is serviced.
        player.skipToNext()
        withTimeout(5_000) { player.awaitDrained() }
        assertEquals(2, player.queue.value.currentIndex)
    }

    @Test
    fun `a setVolume failure neither blocks playback nor wedges the actor`() = runBlocking {
        val line = FakeOutputLine()
        line.volumeFailure = OutputLineException.DeviceProblem("device invalidated")
        val player = newPlayer(listOf("a"), mapOf("a" to FakeDecodedStream(fmt)), line)
        player.loadQueue(listOf(item("a")), startIndex = 0, autoPlay = true)
        withTimeout(5_000) { player.awaitDrained() }
        assertEquals(PlayerState.Ready(isPlaying = true), player.state.value)
        player.setVolume(0.5f)
        withTimeout(5_000) { player.awaitDrained() }
        assertEquals(0.5f, player.volume.value.linear)
    }

    // ----- controller ruling R19: a failed seek-time flush surfaces instead of being swallowed -----
    // (On WASAPI a failed flush can leave the line stopped with a full buffer, so the next write
    // would block until an interrupt and playback would silently halt.)

    @Test
    fun `a flush failure on seek surfaces as DeviceUnavailable without skipping`() = runBlocking {
        val line = FakeOutputLine()
        val player = newPlayer(listOf("a", "b"), mapOf("a" to FakeDecodedStream(fmt), "b" to FakeDecodedStream(fmt)), line)
        player.loadQueue(listOf(item("a"), item("b")), startIndex = 0, autoPlay = true)
        withTimeout(5_000) { player.awaitDrained() }
        assertEquals(PlayerState.Ready(isPlaying = true), player.state.value)
        line.flushFailure = OutputLineException.DeviceProblem("device invalidated")
        player.seekTo(10_000)
        withTimeout(5_000) { player.awaitDrained() }
        assertEquals(PlayerState.Error(PlayerError.DeviceUnavailable("device invalidated")), player.state.value)
        assertEquals(0, player.queue.value.currentIndex, "a seek-time device failure must not skip")
        assertEquals(1, line.closedCount, "the dead line is torn down")
        // The actor is still alive: a later command is serviced.
        line.flushFailure = null
        player.skipToNext()
        withTimeout(5_000) { player.awaitDrained() }
        assertEquals(1, player.queue.value.currentIndex)
        assertEquals(PlayerState.Ready(isPlaying = true), player.state.value)
    }

    /**
     * Wait for the observable outcome of a STREAM event (EOF / decode error). awaitDrained() is a
     * command Barrier and only orders against other commands. The actor is command-biased, so a
     * Barrier queued after signalEof() can be acked before the actor handles the closed frame
     * channel. That happens whenever the actor isn't running inline on the test thread: the init
     * settings collector posts ReapplyGain from Dispatchers.Default, which resumes the Unconfined
     * actor on a Default worker (CI flake on PR #36, reproduced 7/300 locally).
     */
    private suspend fun awaitStreamEvent(condition: suspend () -> Unit) {
        withTimeout(5_000) { condition() }
    }
}
