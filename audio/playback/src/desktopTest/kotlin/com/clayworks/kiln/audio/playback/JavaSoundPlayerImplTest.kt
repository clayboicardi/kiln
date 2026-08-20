// Smoke test for JavaSoundPlayerImpl. Verifies construction, state-flow defaults,
// loadQueue start-index mapping, and the non-playback control ops. Does NOT exercise
// the SourceDataLine output path (that needs the fake-line harness in
// JavaSoundPlayerActorTest). #28: the player is now a command/actor, so control ops are
// async (trySend); tests construct the impl directly (to call the internal awaitDrained
// test seam) and await the actor before asserting post-mutation state.

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import com.clayworks.kiln.audio.dsp.AudioFrame
import com.clayworks.kiln.audio.dsp.AudioProcessor
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.replaygain.ReplayGainProcessor
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.settings.SettingsRepository
import com.clayworks.kiln.library.settings.ThemeMode
import com.clayworks.kiln.library.source.AudioCodec
import com.clayworks.kiln.library.source.BrowseScope
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.MusicSource
import com.clayworks.kiln.library.source.Playable
import com.clayworks.kiln.library.source.SearchResult
import com.clayworks.kiln.library.source.LocalSourceCapabilities
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

class JavaSoundPlayerImplTest {

    // Stub MusicSource — returns Left for any getPlayable call.
    private class AlwaysFailingSource(override val id: SourceId = SourceId("test")) : MusicSource {
        override val displayName: String = "test"
        override val capabilities: SourceCapabilities = LocalSourceCapabilities
        override suspend fun search(query: String, limit: Int): Flow<SearchResult> = emptyFlow()
        override suspend fun browse(scope: BrowseScope): Flow<MediaItem> = emptyFlow()
        override suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable> =
            Either.Left(SourceError.ItemNotFound(itemId))
    }

    // Stub MusicSource that resolves only specific ItemId values, used to
    // exercise loadQueue's startIndex mapping when some items fail to resolve.
    private class SelectivelyResolvingSource(
        private val resolvableIds: Set<String>,
        override val id: SourceId = SourceId("test"),
    ) : MusicSource {
        override val displayName: String = "test"
        override val capabilities: SourceCapabilities = LocalSourceCapabilities
        override suspend fun search(query: String, limit: Int): Flow<SearchResult> = emptyFlow()
        override suspend fun browse(scope: BrowseScope): Flow<MediaItem> = emptyFlow()
        override suspend fun getPlayable(itemId: ItemId): Either<SourceError, Playable> =
            if (itemId.value in resolvableIds) {
                Either.Right(
                    Playable(
                        itemId = itemId,
                        sourceId = id,
                        uri = "file:///tmp/${itemId.value}.flac",  // unused by the test
                        codec = AudioCodec.FLAC,
                        sampleRateHz = 44_100,
                        bitDepth = 16,
                        channels = 2,
                        bitrateKbps = null,
                        durationMs = 0L,
                        replayGain = null,
                    ),
                )
            } else {
                Either.Left(SourceError.ItemNotFound(itemId))
            }
    }

    // Stub Decoder — open always fails (Left), so no SourceDataLine is opened by these tests.
    private class StubDecoder : Decoder {
        override fun supports(codec: AudioCodec): Boolean = false
        override suspend fun open(playable: Playable): Either<DecoderError, DecodedStream> =
            Either.Left(DecoderError.UnsupportedCodec(playable.codec))
    }

    // Minimal SettingsRepository stub: returns Off / 0.0 for RG settings; defaults for the rest.
    private class StubSettingsRepository : SettingsRepository {
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

    // Construct the impl directly (internal, same module) so tests can call awaitDrained().
    // Both dispatchers are Unconfined so command processing runs inline on the sending thread.
    private fun newPlayer(source: MusicSource = AlwaysFailingSource(), decoder: Decoder = StubDecoder()): JavaSoundPlayerImpl =
        JavaSoundPlayerImpl(
            audioDispatcher = Dispatchers.Unconfined,
            decodeDispatcher = Dispatchers.Unconfined,
            decoder = decoder,
            source = source,
            settings = StubSettingsRepository(),
            rgProcessor = ReplayGainProcessor(),
        )

    @Test
    fun `initial state — Idle, position 0, empty queue, volume 1, rgProcessor in chain`() {
        val player = newPlayer()
        assertEquals(PlayerState.Idle, player.state.value)
        assertEquals(0L, player.positionMs.value)
        val q = player.queue.value
        assertEquals(0, q.items.size)
        assertEquals(-1, q.currentIndex)
        assertEquals(RepeatMode.Off, q.repeatMode)
        assertEquals(false, q.shuffleEnabled)
        assertEquals(1.0f, player.volume.value.linear)
        assertEquals(false, player.volume.value.muted)
        // ReplayGainProcessor is added to the chain in the init block.
        assertEquals(1, player.processors.value.size)
    }

    @Test
    fun `loadQueue with empty list — stays Idle, queue is empty`() = runBlocking {
        val player = newPlayer()
        player.loadQueue(items = emptyList(), startIndex = 0, autoPlay = true)
        player.awaitDrained()
        assertEquals(PlayerState.Idle, player.state.value)
        assertEquals(0, player.queue.value.items.size)
        assertEquals(-1, player.queue.value.currentIndex)
    }

    @Test
    fun `loadQueue with items but source always fails — stays Idle, queue is empty`() = runBlocking {
        val player = newPlayer(source = AlwaysFailingSource())
        val items = listOf(makeMediaItem("a"), makeMediaItem("b"))
        player.loadQueue(items, startIndex = 0, autoPlay = true)
        player.awaitDrained()
        // All items skipped by the source → queue ends up empty → state Idle.
        assertEquals(PlayerState.Idle, player.state.value)
        assertEquals(0, player.queue.value.items.size)
    }

    // ---------- startIndex mismapping when items fail to resolve (U1) ----------

    @Test
    fun `loadQueue startIndex maps to correct surviving item when earlier items fail to resolve`() = runBlocking {
        // items = [a, b, c, d, e]; B and D fail. Resolved = [a, c, e]. User clicks "play C"
        // (startIndex = 2) → maps to resolved index 1 (where C lives).
        val player = newPlayer(source = SelectivelyResolvingSource(resolvableIds = setOf("a", "c", "e")))
        val items = listOf("a", "b", "c", "d", "e").map { makeMediaItem(it) }
        player.loadQueue(items, startIndex = 2, autoPlay = false)
        player.awaitDrained()

        val q = player.queue.value
        assertEquals(3, q.items.size, "only a, c, e should have survived")
        assertEquals(1, q.currentIndex, "C is at resolved-list index 1, not 2")
        assertEquals("c", q.items[q.currentIndex].itemId.value, "currentItem must be C")
    }

    @Test
    fun `loadQueue falls forward when the requested item itself fails to resolve`() = runBlocking {
        // items = [a, b, c, d, e]; only a and e resolve. Click "play C" (startIndex=2), C failed
        // → fall forward to E (first surviving at or after original index 2).
        val player = newPlayer(source = SelectivelyResolvingSource(resolvableIds = setOf("a", "e")))
        val items = listOf("a", "b", "c", "d", "e").map { makeMediaItem(it) }
        player.loadQueue(items, startIndex = 2, autoPlay = false)
        player.awaitDrained()

        val q = player.queue.value
        assertEquals(2, q.items.size, "only a and e survived")
        assertEquals(1, q.currentIndex, "fell forward from missing-C to E (resolved index 1)")
        assertEquals("e", q.items[q.currentIndex].itemId.value, "currentItem must be E")
    }

    @Test
    fun `loadQueue falls back to last when no resolved item exists at or after startIndex`() = runBlocking {
        // items = [a, b, c, d, e]; only a and b resolve. Click "play D" (startIndex=3), C/D/E all
        // failed → fall back to last surviving (B).
        val player = newPlayer(source = SelectivelyResolvingSource(resolvableIds = setOf("a", "b")))
        val items = listOf("a", "b", "c", "d", "e").map { makeMediaItem(it) }
        player.loadQueue(items, startIndex = 3, autoPlay = false)
        player.awaitDrained()

        val q = player.queue.value
        assertEquals(2, q.items.size, "only a and b survived")
        assertEquals(1, q.currentIndex, "fell back to B (last surviving = resolved index 1)")
        assertEquals("b", q.items[q.currentIndex].itemId.value, "currentItem must be B")
    }

    @Test
    fun `loadQueue startIndex zero with all items resolving picks index zero`() = runBlocking {
        val player = newPlayer(source = SelectivelyResolvingSource(resolvableIds = setOf("a", "b", "c")))
        val items = listOf("a", "b", "c").map { makeMediaItem(it) }
        player.loadQueue(items, startIndex = 0, autoPlay = false)
        player.awaitDrained()

        val q = player.queue.value
        assertEquals(3, q.items.size)
        assertEquals(0, q.currentIndex, "startIndex=0 must always map to resolved index 0")
        assertEquals("a", q.items[q.currentIndex].itemId.value)
    }

    @Test
    fun `setRepeatMode + setShuffleMode update queue flow without playback`() = runBlocking {
        val player = newPlayer()
        player.setRepeatMode(RepeatMode.All); player.awaitDrained()
        assertEquals(RepeatMode.All, player.queue.value.repeatMode)
        player.setShuffleMode(true); player.awaitDrained()
        assertEquals(true, player.queue.value.shuffleEnabled)
        player.setRepeatMode(RepeatMode.Off); player.setShuffleMode(false); player.awaitDrained()
        assertEquals(RepeatMode.Off, player.queue.value.repeatMode)
        assertEquals(false, player.queue.value.shuffleEnabled)
    }

    @Test
    fun `setVolume + setMuted update volume flow without playback`() = runBlocking {
        val player = newPlayer()
        player.setVolume(0.5f); player.awaitDrained()
        assertEquals(0.5f, player.volume.value.linear)
        player.setMuted(true); player.awaitDrained()
        assertEquals(true, player.volume.value.muted)
        player.setVolume(1.0f); player.setMuted(false); player.awaitDrained()
        assertEquals(1.0f, player.volume.value.linear)
        assertEquals(false, player.volume.value.muted)
    }

    @Test
    fun `addAudioProcessor + removeAudioProcessor mutate processors flow`() = runBlocking {
        val player = newPlayer()
        // The init block already adds rgProcessor — size starts at 1.
        assertEquals(1, player.processors.value.size)
        val processor = object : AudioProcessor {
            override val id = "test"
            override fun onFormatChange(format: DecodedAudioFormat) = Unit
            override fun process(frame: AudioFrame): AudioFrame = frame
        }
        player.addAudioProcessor(processor); player.awaitDrained()
        assertEquals(2, player.processors.value.size)
        player.removeAudioProcessor(processor); player.awaitDrained()
        assertEquals(1, player.processors.value.size)
    }

    @Test
    fun `play pause stop are safe to invoke before loadQueue`() = runBlocking {
        val player = newPlayer()
        // None of these should crash; the line is null so all are no-ops.
        player.play()
        player.pause()
        player.stop()
        player.awaitDrained()
        // State stays Idle.
        assertEquals(PlayerState.Idle, player.state.value)
    }

    @Test
    fun `release marks the player unusable — subsequent ops are no-ops`() = runBlocking {
        val player = newPlayer()
        val stateBeforeRelease = player.state.value
        player.release()
        // No awaitDrained here: the actor exits on Release, so a trailing Barrier would never be
        // processed. release() doesn't mutate _state, so the assertion below holds regardless.
        // Further suspend calls should be safe no-ops (no crash, no state mutation).
        player.play()
        player.pause()
        player.loadQueue(listOf(makeMediaItem("z")), 0, true)
        assertEquals(
            stateBeforeRelease,
            player.state.value,
            "post-release state should remain whatever it was at release time",
        )
    }

    private fun makeMediaItem(id: String): MediaItem = MediaItem(
        itemId = ItemId(id),
        sourceId = SourceId("test"),
        kind = MediaItem.Kind.Track,
        title = "Track $id",
        subtitle = null,
        durationMs = null,
        artUri = null,
        metadata = emptyMap(),
    )
}
