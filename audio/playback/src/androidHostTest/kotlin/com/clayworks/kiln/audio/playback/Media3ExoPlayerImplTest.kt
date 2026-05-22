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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class Media3ExoPlayerImplTest {

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
        return Media3ExoPlayerImpl(context, source)
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
