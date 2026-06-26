// Behavioural tests for the JavaSoundPlayerImpl command/actor model (#28). Each proves a
// defect fix or a falsify safeguard, using the FakeDecodedStream/FakeDecoder/FakeLine doubles.
// Both dispatchers are Unconfined so command + frame processing runs inline (deterministic);
// awaitDrained() barriers the actor before each assertion.

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
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
        line: FakeLine = FakeLine(),
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
}
