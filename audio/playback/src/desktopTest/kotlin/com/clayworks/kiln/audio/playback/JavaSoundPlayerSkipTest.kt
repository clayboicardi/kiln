// Capped skip-ahead (#37). Auto-advance and Next/Previous step past tracks that fail for a
// per-track reason, up to MAX_CONSECUTIVE_FAILURES opens. Device failures and explicit picks
// (LoadQueue / SkipTo) never skip. Every skip emits a PlaybackNotice.

package com.clayworks.kiln.audio.playback

import arrow.core.Either
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JavaSoundPlayerSkipTest {

    private val f16 = DecodedAudioFormat(44_100, 16, 2, SampleFormat.PCM_S16_LE)
    private val f24 = DecodedAudioFormat(44_100, 24, 2, SampleFormat.PCM_S24_LE)

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

    /** 16-bit opens on [line]; 24-bit is rejected as a per-track format failure (or as a device failure). */
    private fun factory(line: FakeOutputLine, deviceFailureOn24: Boolean = false) = PcmOutputLineFactory { f ->
        when {
            f.bitDepth != 24 -> line
            deviceFailureOn24 -> throw OutputLineException.DeviceProblem("device in use")
            else -> throw OutputLineException.FormatRejected(f, "24-bit rejected")
        }
    }

    /** ids ending in "24" get a 24-bit stream; everything else is 16-bit. */
    private fun setup(ids: List<String>, deviceFailureOn24: Boolean = false): Pair<JavaSoundPlayerImpl, Map<String, FakeDecodedStream>> {
        val streams = ids.associateWith { FakeDecodedStream(if (it.endsWith("24")) f24 else f16) }
        val player = JavaSoundPlayerImpl(
            audioDispatcher = Dispatchers.Unconfined,
            decodeDispatcher = Dispatchers.Unconfined,
            decoder = FakeDecoder(streams),
            source = ResolvingSource(ids.toSet()),
            settings = StubSettings(),
            rgProcessor = ReplayGainProcessor(),
            lineFactory = factory(FakeOutputLine(), deviceFailureOn24),
        )
        return player to streams
    }

    private fun CoroutineScope.collectNotices(player: JavaSoundPlayerImpl): MutableList<PlaybackNotice> {
        val out = CopyOnWriteArrayList<PlaybackNotice>()
        launch(Dispatchers.Unconfined) { player.notices.collect { out += it } }
        return out
    }

    private suspend fun <T> within(block: suspend () -> T): T = withTimeout(5_000) { block() }

    /**
     * Wait until [count] notices have reached the collector. Delivery is asynchronous when the
     * actor runs off the test thread: the init settings collector posts ReapplyGain from
     * Dispatchers.Default, which can resume the Unconfined actor on a Default worker. The
     * Unconfined collector is then resumed only after the actor suspends, which can be after
     * awaitDrained() has returned. (Without this wait, about 1 in 10 runs saw [] or a missing GaveUp.)
     */
    private suspend fun List<PlaybackNotice>.awaitCount(count: Int) = within { while (size < count) delay(1) }

    @Test
    fun `EOF skips two unplayable tracks and plays the next good one`() = runBlocking {
        val ids = listOf("a", "b24", "c24", "d")
        val (player, streams) = setup(ids)
        val notices = collectNotices(player)
        player.loadQueue(ids.map(::item), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        streams.getValue("a").signalEof()
        within { player.queue.first { it.currentIndex == 3 } }
        player.awaitDrained()
        assertEquals(PlayerState.Ready(isPlaying = true), player.state.value)
        notices.awaitCount(2)
        assertEquals(listOf("Track b24", "Track c24"), notices.map { (it as PlaybackNotice.Skipped).title })
        assertIs<PlayerError.OutputFormatUnsupported>((notices[0] as PlaybackNotice.Skipped).cause)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `three consecutive failures stop on the error and report GaveUp`() = runBlocking {
        val ids = listOf("a", "b24", "c24", "d24", "e")
        val (player, streams) = setup(ids)
        val notices = collectNotices(player)
        player.loadQueue(ids.map(::item), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        streams.getValue("a").signalEof()
        within { player.state.first { it is PlayerState.Error } }
        player.awaitDrained()
        assertEquals(3, player.queue.value.currentIndex, "stops ON the third failure (d24), never reaching e")
        notices.awaitCount(3)
        assertEquals(PlaybackNotice.GaveUp(3), notices.last())
        assertEquals(2, notices.count { it is PlaybackNotice.Skipped })
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a device failure never skips`() = runBlocking {
        val ids = listOf("a", "b24", "c")
        val (player, streams) = setup(ids, deviceFailureOn24 = true)
        val notices = collectNotices(player)
        player.loadQueue(ids.map(::item), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        streams.getValue("a").signalEof()
        within { player.state.first { it is PlayerState.Error } }
        player.awaitDrained()
        assertIs<PlayerError.DeviceUnavailable>((player.state.value as PlayerState.Error).cause)
        assertEquals(1, player.queue.value.currentIndex)
        assertTrue(notices.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `an explicit pick of an unplayable track shows its error without skipping`() = runBlocking {
        val ids = listOf("a24", "b")
        val (player, _) = setup(ids)
        val notices = collectNotices(player)
        player.loadQueue(ids.map(::item), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        assertIs<PlayerError.OutputFormatUnsupported>((player.state.value as PlayerState.Error).cause)
        assertEquals(0, player.queue.value.currentIndex)
        assertTrue(notices.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `skipTo an unplayable track shows its error without skipping`() = runBlocking {
        val ids = listOf("a", "b", "c24", "d")
        val (player, _) = setup(ids)
        player.loadQueue(ids.map(::item), startIndex = 1, autoPlay = true)
        player.awaitDrained()
        player.skipTo(2)
        player.awaitDrained()
        assertTrue(player.state.value is PlayerState.Error)
        assertEquals(2, player.queue.value.currentIndex)
    }

    @Test
    fun `previous skips backward past an unplayable track`() = runBlocking {
        val ids = listOf("a", "b24", "c")
        val (player, _) = setup(ids)
        val notices = collectNotices(player)
        player.loadQueue(ids.map(::item), startIndex = 2, autoPlay = true)
        player.awaitDrained()
        player.skipToPrevious()
        player.awaitDrained()
        assertEquals(0, player.queue.value.currentIndex)
        assertEquals(PlayerState.Ready(isPlaying = true), player.state.value)
        notices.awaitCount(1)
        assertEquals(listOf("Track b24"), notices.map { (it as PlaybackNotice.Skipped).title })
        coroutineContext.cancelChildren()
    }

    @Test
    fun `RepeatMode One does not retry a track that became unplayable`() = runBlocking {
        // a plays, ends, and (RepeatOne) is reopened. Its stream is consumed, so it now fails.
        // The skip must move FORWARD (b24 fails too, then c plays), not retry a until the cap.
        val ids = listOf("a", "b24", "c")
        val (player, streams) = setup(ids)
        player.loadQueue(ids.map(::item), startIndex = 0, autoPlay = true)
        player.setRepeatMode(RepeatMode.One)
        player.awaitDrained()
        streams.getValue("a").signalEof()
        within { player.queue.first { it.currentIndex == 2 } }
        player.awaitDrained()
        assertEquals(PlayerState.Ready(isPlaying = true), player.state.value)
    }

    @Test
    fun `a single-track RepeatAll queue that fails does not loop`() = runBlocking {
        val ids = listOf("a")
        val (player, streams) = setup(ids)
        val notices = collectNotices(player)
        player.loadQueue(ids.map(::item), startIndex = 0, autoPlay = true)
        player.setRepeatMode(RepeatMode.All)
        player.awaitDrained()
        streams.getValue("a").signalEof() // replay of a fails (consumed); stepping wraps back to a itself
        within { player.state.first { it is PlayerState.Error } }
        player.awaitDrained()
        assertTrue(notices.isEmpty(), "no skip when stepping lands on the same track")
        coroutineContext.cancelChildren()
    }

    @Test
    fun `the output route is published on open and cleared on stop`() = runBlocking {
        val (player, _) = setup(listOf("a"))
        player.loadQueue(listOf(item("a")), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        assertEquals(OutputEngine.WasapiShared, player.outputRoute.value?.engine)
        assertEquals(44_100, player.outputRoute.value?.sourceRateHz)
        player.stop()
        player.awaitDrained()
        assertEquals(null, player.outputRoute.value)
    }

    // ----- controller ruling R8: start() failures map through outputError and never skip -----

    @Test
    fun `a start failure on auto-advance reports the device detail and does not skip`() = runBlocking {
        val ids = listOf("a", "b", "c")
        val line = FakeOutputLine()
        val streams = ids.associateWith { FakeDecodedStream(f16) }
        val player = JavaSoundPlayerImpl(
            audioDispatcher = Dispatchers.Unconfined,
            decodeDispatcher = Dispatchers.Unconfined,
            decoder = FakeDecoder(streams),
            source = ResolvingSource(ids.toSet()),
            settings = StubSettings(),
            rgProcessor = ReplayGainProcessor(),
            lineFactory = factory(line),
        )
        val notices = collectNotices(player)
        player.loadQueue(ids.map(::item), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        // The device dies between tracks: the next line opens but won't start.
        line.startFailure = OutputLineException.DeviceProblem("device invalidated")
        streams.getValue("a").signalEof()
        within { player.state.first { it is PlayerState.Error } }
        player.awaitDrained()
        assertEquals(PlayerError.DeviceUnavailable("device invalidated"), (player.state.value as PlayerState.Error).cause)
        assertEquals(1, player.queue.value.currentIndex, "a failed start() is a device failure, never a skip")
        assertTrue(notices.isEmpty())
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a start failure on resume reports the device detail`() = runBlocking {
        val line = FakeOutputLine()
        val player = JavaSoundPlayerImpl(
            audioDispatcher = Dispatchers.Unconfined,
            decodeDispatcher = Dispatchers.Unconfined,
            decoder = FakeDecoder(mapOf("a" to FakeDecodedStream(f16))),
            source = ResolvingSource(setOf("a")),
            settings = StubSettings(),
            rgProcessor = ReplayGainProcessor(),
            lineFactory = factory(line),
        )
        player.loadQueue(listOf(item("a")), startIndex = 0, autoPlay = false)
        player.awaitDrained()
        assertEquals(PlayerState.Ready(isPlaying = false), player.state.value)
        line.startFailure = OutputLineException.DeviceProblem("device invalidated")
        player.play()
        player.awaitDrained()
        assertEquals(PlayerError.DeviceUnavailable("device invalidated"), (player.state.value as PlayerState.Error).cause)
    }
}
