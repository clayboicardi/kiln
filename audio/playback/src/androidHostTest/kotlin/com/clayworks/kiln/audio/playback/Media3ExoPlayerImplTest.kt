// Media3ExoPlayerImpl coverage — mirrors :audio:playback's
// JavaSoundPlayerImplTest one-for-one against the Android adapter. Robolectric
// gives a real android.content.Context + main-looper environment; ExoPlayer
// constructs cleanly on the test's main thread (Robolectric's default).
//
// The constructor differs from JavaSoundPlayer's: Media3 takes only
// (context, source) — there is no decoder parameter, because ExoPlayer
// brings its own decoder chain. Otherwise the surface (StateFlows + loadQueue
// + transport + volume + processors + release) is identical, so the test
// bodies port verbatim.

package com.clayworks.kiln.audio.playback

import androidx.test.core.app.ApplicationProvider
import arrow.core.Either
import com.clayworks.kiln.audio.dsp.AudioFrame
import com.clayworks.kiln.audio.dsp.AudioProcessor
import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class Media3ExoPlayerImplTest {

    // Track every player constructed by newPlayer() so @After can release()
    // them. Media3's MediaSession registry forbids duplicate session IDs
    // within a process — without explicit release between tests, the second
    // @Test's MediaSession.Builder.build() throws
    // "IllegalStateException: Session ID must be unique. ID=".
    // JavaSoundPlayerImplTest has no equivalent need because JavaSound has
    // no process-singleton registry.
    private val players: MutableList<Media3ExoPlayerImpl> = mutableListOf()

    @After
    fun tearDown() = runBlocking {
        players.forEach { it.release() }
        players.clear()
    }

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

    private fun newPlayer(source: MusicSource = AlwaysFailingSource()): Media3ExoPlayerImpl {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return Media3ExoPlayerImpl(context, source).also { players.add(it) }
    }

    @Test
    fun `initial state — Idle, position 0, empty queue, volume 1, no processors`() {
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
        assertEquals(0, player.processors.value.size)
    }

    @Test
    fun `loadQueue with empty list — stays Idle, queue is empty`() = runBlocking {
        val player = newPlayer()
        player.loadQueue(items = emptyList(), startIndex = 0, autoPlay = true)
        assertEquals(PlayerState.Idle, player.state.value)
        assertEquals(0, player.queue.value.items.size)
        assertEquals(-1, player.queue.value.currentIndex)
    }

    @Test
    fun `loadQueue with items but source always fails — stays Idle, queue is empty`() = runBlocking {
        val player = newPlayer(source = AlwaysFailingSource())
        val items = listOf(makeMediaItem("a"), makeMediaItem("b"))
        player.loadQueue(items, startIndex = 0, autoPlay = true)
        // All items skipped by the source → queue ends up empty → state Idle.
        assertEquals(PlayerState.Idle, player.state.value)
        assertEquals(0, player.queue.value.items.size)
    }

    // ---------- startIndex mismapping when items fail to resolve (U1) ----------

    @Test
    fun `loadQueue startIndex maps to correct surviving item when earlier items fail to resolve`() = runBlocking {
        // items = [a, b, c, d, e] (5 items in user-facing list); B and D fail.
        // Resolved list becomes [a, c, e] (3 items). User clicks "play C" →
        // startIndex = 2 in the original-list. Post-fix, this maps to resolved
        // index 1 (where C lives). Pre-fix, coercedStart.coerceIn(0,2) gave 2 → E.
        val player = newPlayer(source = SelectivelyResolvingSource(resolvableIds = setOf("a", "c", "e")))
        val items = listOf("a", "b", "c", "d", "e").map { makeMediaItem(it) }
        player.loadQueue(items, startIndex = 2, autoPlay = false)

        val q = player.queue.value
        assertEquals(3, q.items.size, "only a, c, e should have survived")
        assertEquals(1, q.currentIndex, "C is at resolved-list index 1, not 2")
        assertEquals("c", q.items[q.currentIndex].itemId.value, "currentItem must be C")
    }

    @Test
    fun `loadQueue falls forward when the requested item itself fails to resolve`() = runBlocking {
        // items = [a, b, c, d, e]; only a and e resolve. User clicks "play C"
        // (startIndex=2), but C failed. Should fall forward to E (the first
        // surviving item at or after original index 2).
        val player = newPlayer(source = SelectivelyResolvingSource(resolvableIds = setOf("a", "e")))
        val items = listOf("a", "b", "c", "d", "e").map { makeMediaItem(it) }
        player.loadQueue(items, startIndex = 2, autoPlay = false)

        val q = player.queue.value
        assertEquals(2, q.items.size, "only a and e survived")
        assertEquals(1, q.currentIndex, "fell forward from missing-C to E (resolved index 1)")
        assertEquals("e", q.items[q.currentIndex].itemId.value, "currentItem must be E")
    }

    @Test
    fun `loadQueue falls back to last when no resolved item exists at or after startIndex`() = runBlocking {
        // items = [a, b, c, d, e]; only a and b resolve. User clicks "play D"
        // (startIndex=3), but C, D, E all failed. Fall back to last surviving (B).
        val player = newPlayer(source = SelectivelyResolvingSource(resolvableIds = setOf("a", "b")))
        val items = listOf("a", "b", "c", "d", "e").map { makeMediaItem(it) }
        player.loadQueue(items, startIndex = 3, autoPlay = false)

        val q = player.queue.value
        assertEquals(2, q.items.size, "only a and b survived")
        assertEquals(1, q.currentIndex, "fell back to B (last surviving = resolved index 1)")
        assertEquals("b", q.items[q.currentIndex].itemId.value, "currentItem must be B")
    }

    @Test
    fun `loadQueue startIndex zero with all items resolving picks index zero`() = runBlocking {
        // Sanity check that the new mapping logic doesn't regress the happy path.
        val player = newPlayer(source = SelectivelyResolvingSource(resolvableIds = setOf("a", "b", "c")))
        val items = listOf("a", "b", "c").map { makeMediaItem(it) }
        player.loadQueue(items, startIndex = 0, autoPlay = false)

        val q = player.queue.value
        assertEquals(3, q.items.size)
        assertEquals(0, q.currentIndex, "startIndex=0 must always map to resolved index 0")
        assertEquals("a", q.items[q.currentIndex].itemId.value)
    }

    @Test
    fun `setRepeatMode + setShuffleMode update queue flow without playback`() = runBlocking {
        val player = newPlayer()
        player.setRepeatMode(RepeatMode.All)
        assertEquals(RepeatMode.All, player.queue.value.repeatMode)
        player.setShuffleMode(true)
        assertEquals(true, player.queue.value.shuffleEnabled)
        player.setRepeatMode(RepeatMode.Off)
        player.setShuffleMode(false)
        assertEquals(RepeatMode.Off, player.queue.value.repeatMode)
        assertEquals(false, player.queue.value.shuffleEnabled)
    }

    @Test
    fun `setVolume + setMuted update volume flow without playback`() = runBlocking {
        val player = newPlayer()
        player.setVolume(0.5f)
        assertEquals(0.5f, player.volume.value.linear)
        player.setMuted(true)
        assertEquals(true, player.volume.value.muted)
        player.setVolume(1.0f)
        player.setMuted(false)
        assertEquals(1.0f, player.volume.value.linear)
        assertEquals(false, player.volume.value.muted)
    }

    @Test
    fun `addAudioProcessor + removeAudioProcessor mutate processors flow`() {
        // NOTE: Media3ExoPlayerImpl.addAudioProcessor stores into the
        // _processors MutableStateFlow but does NOT yet inject the processor
        // into the audio pipeline — the chain hot-apply is deferred to MVP
        // Sessions 16-22 (custom RenderersFactory wrapping AudioSink). The
        // flow surface IS the testable contract today: Compose surfaces can
        // observe the list of registered processors even though the actual
        // audio path doesn't run them yet.
        val player = newPlayer()
        val processor = object : AudioProcessor {
            override val id = "test"
            override fun onFormatChange(format: DecodedAudioFormat) = Unit
            override fun process(frame: AudioFrame): AudioFrame = frame
        }
        player.addAudioProcessor(processor)
        assertEquals(1, player.processors.value.size)
        player.removeAudioProcessor(processor)
        assertEquals(0, player.processors.value.size)
    }

    @Test
    fun `play pause stop are safe to invoke before loadQueue`() = runBlocking {
        val player = newPlayer()
        // None of these should crash. ExoPlayer accepts play()/pause()/stop()
        // in any state — they're no-ops when no media is loaded, mirroring
        // the JavaSoundPlayer line-null no-op contract.
        player.play()
        player.pause()
        player.stop()
        // State stays Idle.
        assertEquals(PlayerState.Idle, player.state.value)
    }

    @Test
    fun `release marks the player unusable — subsequent ops are no-ops`() = runBlocking {
        val player = newPlayer()
        val stateBeforeRelease = player.state.value
        player.release()
        // Further suspend calls should be safe no-ops (no crash, no state
        // mutation). The `released` volatile flag short-circuits each entry
        // point at Media3ExoPlayerImpl.kt:188/246/251/etc.
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
