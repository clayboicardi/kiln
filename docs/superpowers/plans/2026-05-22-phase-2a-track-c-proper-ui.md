# Phase 2a Track C (Scoped) — Proper UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace H7's dev-affordance PlayFirstTrackScreen (Scan / Play First Track buttons) with the actual Kiln app shell: Voyager TabNavigator hosting three tabs (Library / Now Playing / Search) on both Android and Desktop. Settings (gear icon) remains a separate route from Track A.

**Architecture:**
- **`:ui:components` exposes 3 stateless content composables + 3 Voyager `Tab` wrappers + a `KilnHomeScreen` Scaffold-with-TabNavigator.** Content composables take state + callbacks; Tab wrappers own state collection (via `collectAsState` of repository / player flows) and bridge MusicSource / PlatformPlayer to the content composables.
- **No Circuit presenter wiring yet** — Voyager screens are stateful Compose composables for this MVP. Circuit landing is Track C3 / Phase 2a Flight.
- **No FFT Fluid Canvas visualizer yet** — Now Playing shows a minimal player surface (title / artist / album / position slider / transport buttons). Visualizer is Track C2.
- **No Coil album art rendering yet** — list rows are text-only (title, subtitle). Coil-backed art is Track C2.
- **`:app-android` and `:app-desktop` wire `KilnHomeScreen(graph, onOpenSettings = { showSettings = true })`** in place of the current PlayFirstTrackScreen. The `if (showSettings) AndroidSettingsRoute / DesktopSettingsRoute else KilnHomeScreen(...)` toggle from Track A is preserved.

**Tech Stack:** Voyager 1.1.0-beta03 (`voyager-tab-navigator` + `voyager-navigator`), Compose Multiplatform 1.11.0 Material 3, kotlinx-coroutines flows, existing MusicSource + PlatformPlayer contracts.

**Scope discipline (explicit OUT OF SCOPE):**
- FFT Fluid Canvas visualizer → Track C2
- Coil album art rendering on rows → Track C2
- Circuit presenter / Molecule wiring → Track C3
- Mini-player overlay shared across tabs → Track C3
- Queue management UI (reorder, swipe-to-remove) → Track C3
- Search debounce sophistication (live results refresh on text change with proper debounce) → simple 300ms via LaunchedEffect for Track C
- Sectioned search → Track C3
- Library sort/filter UI → Track C2
- Tab persistence across app restarts → not load-bearing for Track C

---

## Pre-flight (must hold true at start)

- Track A merged or branched-from cleanly (PR #4)
- Track B merged or branched-from cleanly (PR #5)
- Local branch: `phase-2a-track-c-proper-ui` based on Track B's branch tip (`4fa636b`)
- `kiln-verify-build` PASS (104+1 tests)
- Git tree clean

## End-state (must hold true at completion)

- `:app-android:assembleDebug` + `:app-desktop:assemble` PASS
- `:ui:components:desktopTest` adds ≥3 Compose UI tests for the new content composables
- `:data:library:desktopTest` unchanged (71 tests)
- `:data:library:testAndroidHostTest` unchanged (50 tests)
- Canonical verify-build PASS (104+1 + 3 = ~107+1)
- Desktop manual smoke: launch app → see 3-tab shell with Library tab default → switch tabs works → Library shows track list (text-only) → tap a track → playback starts → Now Playing tab shows current track + transport works → Search tab text input → enter "the" or any common word → results appear
- Android Pixel 7 manual smoke (delegated to Clay): same flow via tap interactions on the device
- CLAUDE.md gains 1-2 Voyager gotcha entries IF discovered (otherwise no change)

---

## File Structure

**New files (in `:ui:components`):**

| Path | Responsibility |
|------|---------------|
| `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/home/KilnHomeScreen.kt` | Top-level Scaffold + TabNavigator + bottom NavigationBar. Takes `graph`-shaped dependencies as constructor params via factories. |
| `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/library/LibraryTab.kt` | Voyager Tab object + content composable. Collects `MusicSource.browse(AllTracks)` into a state list. |
| `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/library/LibraryContent.kt` | Stateless: `(tracks, onTrackClick) -> LazyColumn`. |
| `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingTab.kt` | Voyager Tab object + content composable. Collects PlatformPlayer state/queue/position. |
| `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingContent.kt` | Stateless: `(state, onPlayPause, onSeek, onSkipNext, onSkipPrev) -> Column`. |
| `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/search/SearchTab.kt` | Voyager Tab object + content composable. Debounced query → MusicSource.search → results. |
| `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/search/SearchContent.kt` | Stateless: `(query, onQueryChange, results, onResultClick) -> Column`. |
| `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/library/LibraryContentTest.kt` | Compose UI test for text-only list rendering. |
| `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingContentTest.kt` | Compose UI test for transport callbacks. |
| `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/search/SearchContentTest.kt` | Compose UI test for query input + results rendering. |

**Modified files:**

| Path | Change |
|------|--------|
| `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt` | Replace `PlayFirstTrackScreen(graph, onOpenSettings)` with `KilnHomeScreen(graph, onOpenSettings)`. The `if (showSettings) AndroidSettingsRoute(...) else ...` toggle remains. The H7 dev surface is fully gone. |
| `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt` | Same swap on Desktop side. |
| `CLAUDE.md` | Only if new Voyager gotchas surface during impl. |

---

## Tasks (one subagent dispatch per task)

### Task 1: `LibraryContent` + `LibraryTab` — text-only track list

**Why first:** Library is the most-used tab. Establishing its content composable + Tab wrapper first lets Tasks 2/3 mirror the pattern. The LazyColumn shape is the simplest of the three.

**Files:**
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/library/LibraryContent.kt`
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/library/LibraryTab.kt`
- Create: `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/library/LibraryContentTest.kt`

**Steps:**

- [ ] **Step 1: Write the failing test FIRST (TDD)**

Path: `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/library/LibraryContentTest.kt`

```kotlin
package com.clayworks.kiln.ui.components.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.SourceId
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shows_empty_state_when_no_tracks() {
        composeRule.setContent {
            LibraryContent(
                tracks = emptyList(),
                onTrackClick = {},
            )
        }
        composeRule.onNodeWithText("No tracks. Run a Library scan from Settings.")
            .assertIsDisplayed()
    }

    @Test
    fun renders_track_titles_and_subtitles() {
        composeRule.setContent {
            LibraryContent(
                tracks = listOf(
                    MediaItem(
                        itemId = ItemId("1"),
                        sourceId = SourceId("local"),
                        kind = MediaItem.Kind.Track,
                        title = "Smells Like Teen Spirit",
                        subtitle = "Nirvana — Nevermind",
                    ),
                    MediaItem(
                        itemId = ItemId("2"),
                        sourceId = SourceId("local"),
                        kind = MediaItem.Kind.Track,
                        title = "Come As You Are",
                        subtitle = "Nirvana — Nevermind",
                    ),
                ),
                onTrackClick = {},
            )
        }
        composeRule.onNodeWithText("Smells Like Teen Spirit").assertIsDisplayed()
        composeRule.onNodeWithText("Come As You Are").assertIsDisplayed()
    }

    @Test
    fun track_click_invokes_callback() {
        var clicked: MediaItem? = null
        val sample = MediaItem(
            itemId = ItemId("42"),
            sourceId = SourceId("local"),
            kind = MediaItem.Kind.Track,
            title = "Clicked Track",
            subtitle = "Artist",
        )
        composeRule.setContent {
            LibraryContent(
                tracks = listOf(sample),
                onTrackClick = { clicked = it },
            )
        }
        composeRule.onNodeWithText("Clicked Track").performClick()
        assertEquals(sample, clicked)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
pwsh -c "./gradlew :ui:components:desktopTest"
```
Expected: COMPILATION ERROR — `Unresolved reference: LibraryContent`. Failing-test gate.

- [ ] **Step 3: Implement LibraryContent**

Path: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/library/LibraryContent.kt`

```kotlin
// LibraryContent — stateless list of tracks. Text-only rows (no album art);
// Coil-backed art landing in Track C2. Empty-state surface routes the user
// toward Settings → Scan Library when nothing is in the index yet.

package com.clayworks.kiln.ui.components.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clayworks.kiln.library.source.MediaItem

@Composable
fun LibraryContent(
    tracks: List<MediaItem>,
    onTrackClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No tracks. Run a Library scan from Settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(items = tracks, key = { it.itemId.value }) { track ->
            Surface(
                onClick = { onTrackClick(track) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    track.subtitle?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
```

Note: `ItemId` is a value class wrapping a String — `it.itemId.value` accesses the wrapped value. If the actual implementation uses a different field name, adjust during empirical compile (the LSP tool can help: `LSP documentSymbol` on `data/library/src/commonMain/kotlin/com/clayworks/kiln/library/source/Ids.kt`).

- [ ] **Step 4: Run tests, expect 3/3 pass**

```powershell
pwsh -c "./gradlew :ui:components:desktopTest"
```
Expected: BUILD SUCCESSFUL with 8 tests passing (5 existing SettingsScreenTest + 3 new LibraryContentTest).

- [ ] **Step 5: Implement LibraryTab (Voyager Tab wrapper)**

Path: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/library/LibraryTab.kt`

```kotlin
// LibraryTab — Voyager Tab wrapping LibraryContent. Owns state collection
// from MusicSource.browse(AllTracks). For Track C MVP, fetches the first 500
// tracks via .take(500).toList() inside a LaunchedEffect; real pagination
// lands at Track C2 along with sort/filter UI.

package com.clayworks.kiln.ui.components.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.library.source.BrowseScope
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.MusicSource
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

class LibraryTab(
    private val musicSource: MusicSource,
    private val player: PlatformPlayer,
) : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 0u,
            title = "Library",
            icon = rememberVectorPainter(Icons.Filled.LibraryMusic),
        )

    @Composable
    override fun Content() {
        var tracks by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            tracks = musicSource.browse(BrowseScope.AllTracks(pageSize = 500, pageOffset = 0))
                .take(500)
                .toList()
        }

        LibraryContent(
            tracks = tracks,
            onTrackClick = { item ->
                coroutineScope.launch {
                    player.loadQueue(items = listOf(item), startIndex = 0, autoPlay = true)
                }
            },
        )
    }
}
```

Note: `Icons.Filled.LibraryMusic` is in `material-icons-core`. The bundled `compose-material-icons-core` includes it. If not, fall back to `Icons.Filled.List` or similar (verify empirically).

- [ ] **Step 6: Run full :ui:components:desktopTest + canonical verify**

```powershell
pwsh -c "./gradlew :ui:components:desktopTest"
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```
Expected: PASS, 5/5 targets, 107+1 tests (was 104+1; +3 LibraryContentTest cases).

- [ ] **Step 7: Commit**

```bash
git add ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/library/ ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/library/
git commit -m "$(cat <<'EOF'
feat(ui:components): LibraryContent + LibraryTab — Phase 2a Track C tab 1 of 3

LibraryContent is a stateless LazyColumn of MediaItem with text-only rows
(title + subtitle, no album art). Empty-state routes the user toward
Settings → Scan Library. Track C2 lands Coil-backed art + sort/filter.

LibraryTab wraps LibraryContent as a Voyager Tab. Collects MusicSource.browse
(AllTracks, pageSize=500) into local state via a LaunchedEffect — bounded
read so the existing Flow<MediaItem>.transform-per-item shape doesn't
overwhelm composition. Real pagination + reactive refresh lands at Track C2.

3 Compose UI tests: empty state, two-track rendering, click callback.
:ui:components:desktopTest count: 5 → 8.

Phase 2a Track C Task 1 of 6.
EOF
)"
```

---

### Task 2: `NowPlayingContent` + `NowPlayingTab` — minimal player surface

**Why second:** the NowPlaying surface is the most state-dense (PlayerState + QueueState + positionMs); building it now exercises the StateFlow-collection patterns that Search will mirror in a lighter form.

**Files:**
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingContent.kt`
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingTab.kt`
- Create: `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingContentTest.kt`

**Steps:**

- [ ] **Step 1: Write the failing test FIRST (TDD)**

Path: `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingContentTest.kt`

```kotlin
package com.clayworks.kiln.ui.components.nowplaying

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.clayworks.kiln.audio.playback.PlayerState
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.SourceId
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertTrue

class NowPlayingContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shows_idle_state_when_nothing_playing() {
        composeRule.setContent {
            NowPlayingContent(
                state = NowPlayingState(
                    playerState = PlayerState.Idle,
                    currentItem = null,
                    positionMs = 0L,
                    durationMs = 0L,
                ),
                onPlayPause = {},
                onSeek = {},
                onSkipNext = {},
                onSkipPrevious = {},
            )
        }
        composeRule.onNodeWithText("Nothing playing").assertIsDisplayed()
    }

    @Test
    fun shows_current_track_when_ready() {
        val sample = MediaItem(
            itemId = ItemId("1"),
            sourceId = SourceId("local"),
            kind = MediaItem.Kind.Track,
            title = "In Bloom",
            subtitle = "Nirvana",
        )
        composeRule.setContent {
            NowPlayingContent(
                state = NowPlayingState(
                    playerState = PlayerState.Ready(isPlaying = true),
                    currentItem = sample,
                    positionMs = 30_000L,
                    durationMs = 240_000L,
                ),
                onPlayPause = {},
                onSeek = {},
                onSkipNext = {},
                onSkipPrevious = {},
            )
        }
        composeRule.onNodeWithText("In Bloom").assertIsDisplayed()
        composeRule.onNodeWithText("Nirvana").assertIsDisplayed()
    }

    @Test
    fun play_pause_button_invokes_callback() {
        var toggled = false
        composeRule.setContent {
            NowPlayingContent(
                state = NowPlayingState(
                    playerState = PlayerState.Ready(isPlaying = false),
                    currentItem = MediaItem(
                        itemId = ItemId("1"),
                        sourceId = SourceId("local"),
                        kind = MediaItem.Kind.Track,
                        title = "Foo",
                        subtitle = null,
                    ),
                    positionMs = 0L,
                    durationMs = 100_000L,
                ),
                onPlayPause = { toggled = true },
                onSeek = {},
                onSkipNext = {},
                onSkipPrevious = {},
            )
        }
        composeRule.onNodeWithContentDescription("Play").performClick()
        assertTrue(toggled)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```powershell
pwsh -c "./gradlew :ui:components:desktopTest"
```
Expected: COMPILATION ERROR — `Unresolved reference: NowPlayingContent / NowPlayingState`. Gate passed.

- [ ] **Step 3: Implement NowPlayingContent**

Path: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingContent.kt`

```kotlin
// NowPlayingContent — minimal player surface (no FFT visualizer). Track C2
// adds the Fluid Canvas viz; Track C3 adds queue/reorder/mini-player overlay.
//
// State hoisted via NowPlayingState — Tab wrapper collects PlatformPlayer
// flows into this shape and passes in. Transport callbacks fire through
// rememberCoroutineScope().launch in the Tab.

package com.clayworks.kiln.ui.components.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clayworks.kiln.audio.playback.PlayerState
import com.clayworks.kiln.library.source.MediaItem

data class NowPlayingState(
    val playerState: PlayerState,
    val currentItem: MediaItem?,
    val positionMs: Long,
    val durationMs: Long,
)

@Composable
fun NowPlayingContent(
    state: NowPlayingState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.currentItem
    if (item == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Nothing playing",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val isPlaying = (state.playerState as? PlayerState.Ready)?.isPlaying == true

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Title + subtitle (artist - album when subtitle is "artist — album")
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            item.subtitle?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Position slider + time labels
        Column(modifier = Modifier.fillMaxWidth()) {
            val duration = state.durationMs.coerceAtLeast(1L).toFloat()
            val position = state.positionMs.coerceIn(0L, state.durationMs).toFloat()
            Slider(
                value = position,
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..duration,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = formatMs(state.positionMs), style = MaterialTheme.typography.labelSmall)
                Text(text = formatMs(state.durationMs), style = MaterialTheme.typography.labelSmall)
            }
        }

        // Transport
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onSkipPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Skip previous")
            }
            IconButton(onClick = onPlayPause) {
                val (icon, label) = if (isPlaying) {
                    Icons.Filled.Pause to "Pause"
                } else {
                    Icons.Filled.PlayArrow to "Play"
                }
                Icon(icon, contentDescription = label)
            }
            IconButton(onClick = onSkipNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Skip next")
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
```

- [ ] **Step 4: Run tests, expect 3/3 pass**

```powershell
pwsh -c "./gradlew :ui:components:desktopTest"
```
Expected: BUILD SUCCESSFUL with 11 tests passing (5 SettingsScreenTest + 3 LibraryContentTest + 3 NowPlayingContentTest).

- [ ] **Step 5: Implement NowPlayingTab**

Path: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/nowplaying/NowPlayingTab.kt`

```kotlin
// NowPlayingTab — Voyager Tab wrapping NowPlayingContent. Collects
// PlatformPlayer's state / queue / positionMs flows.

package com.clayworks.kiln.ui.components.nowplaying

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import com.clayworks.kiln.audio.playback.PlatformPlayer
import kotlinx.coroutines.launch

class NowPlayingTab(
    private val player: PlatformPlayer,
) : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 1u,
            title = "Now Playing",
            icon = rememberVectorPainter(Icons.Filled.PlayCircle),
        )

    @Composable
    override fun Content() {
        val playerState by player.state.collectAsState()
        val queue by player.queue.collectAsState()
        val positionMs by player.positionMs.collectAsState()
        val coroutineScope = rememberCoroutineScope()

        val state = NowPlayingState(
            playerState = playerState,
            currentItem = queue.currentItem,
            positionMs = positionMs,
            durationMs = queue.currentItem?.durationMs ?: 0L,
        )

        NowPlayingContent(
            state = state,
            onPlayPause = {
                coroutineScope.launch {
                    val isPlaying = (playerState as? com.clayworks.kiln.audio.playback.PlayerState.Ready)?.isPlaying == true
                    if (isPlaying) player.pause() else player.play()
                }
            },
            onSeek = { positionMs ->
                coroutineScope.launch { player.seekTo(positionMs) }
            },
            onSkipNext = {
                coroutineScope.launch { player.skipToNext() }
            },
            onSkipPrevious = {
                coroutineScope.launch { player.skipToPrevious() }
            },
        )
    }
}
```

- [ ] **Step 6: Run full :ui:components:desktopTest**

```powershell
pwsh -c "./gradlew :ui:components:desktopTest"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/nowplaying/ ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/nowplaying/
git commit -m "$(cat <<'EOF'
feat(ui:components): NowPlayingContent + NowPlayingTab — Phase 2a Track C tab 2 of 3

NowPlayingContent is the minimal player surface: title + subtitle + position
slider + Skip Prev / Play-Pause / Skip Next transport. NOT shipping:
FFT Fluid Canvas visualizer (Track C2), queue list (Track C3), shuffle/repeat
toggles (Track C3), volume slider (Track C3).

NowPlayingTab collects PlatformPlayer's state / queue / positionMs flows
into a hoisted NowPlayingState data class; transport callbacks fire through
rememberCoroutineScope().launch.

3 Compose UI tests: idle "Nothing playing" empty state, ready state with
title + artist, Play button callback. :ui:components:desktopTest 8 → 11.

Phase 2a Track C Task 2 of 6.
EOF
)"
```

---

### Task 3: `SearchContent` + `SearchTab` — FTS5-backed text search

**Why third:** mirrors Library's flow-collection pattern + adds reactive query debouncing. Closes the tab set.

**Files:**
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/search/SearchContent.kt`
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/search/SearchTab.kt`
- Create: `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/search/SearchContentTest.kt`

**Steps:**

- [ ] **Step 1: Write the failing test FIRST (TDD)**

Path: `ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/search/SearchContentTest.kt`

```kotlin
package com.clayworks.kiln.ui.components.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.clayworks.kiln.library.source.ItemId
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.SearchResult
import com.clayworks.kiln.library.source.SourceId
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun shows_prompt_when_query_empty() {
        composeRule.setContent {
            SearchContent(
                query = "",
                onQueryChange = {},
                results = emptyList(),
                onResultClick = {},
            )
        }
        composeRule.onNodeWithText("Search your library").assertIsDisplayed()
    }

    @Test
    fun typing_invokes_onQueryChange() {
        var captured = ""
        composeRule.setContent {
            SearchContent(
                query = captured,
                onQueryChange = { captured = it },
                results = emptyList(),
                onResultClick = {},
            )
        }
        composeRule.onNodeWithText("Search your library").performClick()
        composeRule.onNodeWithText("Search your library").performTextInput("nirvana")
        assertEquals("nirvana", captured)
    }

    @Test
    fun renders_search_results() {
        composeRule.setContent {
            SearchContent(
                query = "nirvana",
                onQueryChange = {},
                results = listOf(
                    SearchResult(
                        item = MediaItem(
                            itemId = ItemId("1"),
                            sourceId = SourceId("local"),
                            kind = MediaItem.Kind.Track,
                            title = "Smells Like Teen Spirit",
                            subtitle = "Nirvana",
                        ),
                    ),
                    SearchResult(
                        item = MediaItem(
                            itemId = ItemId("2"),
                            sourceId = SourceId("local"),
                            kind = MediaItem.Kind.Track,
                            title = "Come As You Are",
                            subtitle = "Nirvana",
                        ),
                    ),
                ),
                onResultClick = {},
            )
        }
        composeRule.onNodeWithText("Smells Like Teen Spirit").assertIsDisplayed()
        composeRule.onNodeWithText("Come As You Are").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```powershell
pwsh -c "./gradlew :ui:components:desktopTest"
```
Expected: COMPILATION ERROR. Gate passed.

- [ ] **Step 3: Implement SearchContent**

Path: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/search/SearchContent.kt`

```kotlin
// SearchContent — stateless query input + results list. Sectioned search
// (Phase 2a Flight D) and advanced filters land at Track C3 / later session.

package com.clayworks.kiln.ui.components.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clayworks.kiln.library.source.SearchResult

@Composable
fun SearchContent(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<SearchResult>,
    onResultClick: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search your library") },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true,
        )

        if (query.isBlank()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Type to search.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }

        if (results.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No results.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = results, key = { it.item.itemId.value }) { result ->
                Surface(
                    onClick = { onResultClick(result) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = result.item.title,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        result.item.subtitle?.let { subtitle ->
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
```

- [ ] **Step 4: Run tests, expect 3/3 pass**

```powershell
pwsh -c "./gradlew :ui:components:desktopTest"
```
Expected: BUILD SUCCESSFUL with 14 tests passing.

- [ ] **Step 5: Implement SearchTab**

Path: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/search/SearchTab.kt`

```kotlin
// SearchTab — Voyager Tab wrapping SearchContent. Debounces query input
// (300ms) before calling MusicSource.search; results bounded to 50.

package com.clayworks.kiln.ui.components.search

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.library.source.MediaItem
import com.clayworks.kiln.library.source.MusicSource
import com.clayworks.kiln.library.source.SearchResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

class SearchTab(
    private val musicSource: MusicSource,
    private val player: PlatformPlayer,
) : Tab {

    override val options: TabOptions
        @Composable
        get() = TabOptions(
            index = 2u,
            title = "Search",
            icon = rememberVectorPainter(Icons.Filled.Search),
        )

    @Composable
    override fun Content() {
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(query) {
            if (query.isBlank()) {
                results = emptyList()
                return@LaunchedEffect
            }
            delay(300) // debounce
            results = musicSource.search(query, limit = 50).take(50).toList()
        }

        SearchContent(
            query = query,
            onQueryChange = { query = it },
            results = results,
            onResultClick = { result ->
                coroutineScope.launch {
                    player.loadQueue(items = listOf(result.item), startIndex = 0, autoPlay = true)
                }
            },
        )
    }
}
```

- [ ] **Step 6: Run full :ui:components:desktopTest + canonical verify**

```powershell
pwsh -c "./gradlew :ui:components:desktopTest"
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```
Expected: PASS, 5/5 targets, ≥110+1 tests (was 104+1; +9 from Tasks 1+2+3 = +3 each).

- [ ] **Step 7: Commit**

```bash
git add ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/search/ ui/components/src/desktopTest/kotlin/com/clayworks/kiln/ui/components/search/
git commit -m "$(cat <<'EOF'
feat(ui:components): SearchContent + SearchTab — Phase 2a Track C tab 3 of 3

SearchContent: OutlinedTextField over MusicSource.search results.
Three states: blank ("Type to search"), no-results ("No results"),
results list. NOT shipping: sectioned search (Track C3), filters,
recent-searches memory.

SearchTab: 300ms debounce via LaunchedEffect(query) + delay, then
MusicSource.search(query, limit=50).take(50).toList(). On result-click,
loadQueue with the single track + autoPlay.

3 Compose UI tests: blank prompt, typing invokes onQueryChange,
results render. :ui:components:desktopTest 11 → 14.

Phase 2a Track C Task 3 of 6.
EOF
)"
```

---

### Task 4: `KilnHomeScreen` — TabNavigator scaffold

**Why fourth:** with all three tabs implemented, the scaffold + bottom NavigationBar wiring is straightforward. This is the composable both apps will import.

**Files:**
- Create: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/home/KilnHomeScreen.kt`

**Steps:**

- [ ] **Step 1: Create KilnHomeScreen**

Path: `ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/home/KilnHomeScreen.kt`

```kotlin
// KilnHomeScreen — top-level Compose composable that hosts the 3-tab
// Voyager TabNavigator. Takes MusicSource + PlatformPlayer + onOpenSettings
// callback; constructs the three Tab instances internally.
//
// Track A's gear-icon → SettingsScreen flow is preserved at the app-module
// layer (MainActivity / Main.kt route `showSettings` boolean toggles
// between KilnHomeScreen and SettingsRoute).

package com.clayworks.kiln.ui.components.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.tab.CurrentTab
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import com.clayworks.kiln.audio.playback.PlatformPlayer
import com.clayworks.kiln.library.source.MusicSource
import com.clayworks.kiln.ui.components.library.LibraryTab
import com.clayworks.kiln.ui.components.nowplaying.NowPlayingTab
import com.clayworks.kiln.ui.components.search.SearchTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KilnHomeScreen(
    musicSource: MusicSource,
    player: PlatformPlayer,
    onOpenSettings: () -> Unit,
) {
    val libraryTab = remember(musicSource, player) { LibraryTab(musicSource, player) }
    val nowPlayingTab = remember(player) { NowPlayingTab(player) }
    val searchTab = remember(musicSource, player) { SearchTab(musicSource, player) }

    TabNavigator(libraryTab) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Kiln by Clayworks", style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    TabNavigationItem(libraryTab)
                    TabNavigationItem(nowPlayingTab)
                    TabNavigationItem(searchTab)
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { padding ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                CurrentTab()
            }
        }
    }
}

@Composable
private fun TabNavigationItem(tab: Tab) {
    val tabNavigator = LocalTabNavigator.current
    NavigationBarItem(
        selected = tabNavigator.current == tab,
        onClick = { tabNavigator.current = tab },
        icon = {
            tab.options.icon?.let { painter ->
                Icon(painter = painter, contentDescription = tab.options.title)
            }
        },
        label = { Text(tab.options.title) },
    )
}
```

- [ ] **Step 2: Build :ui:components:build**

```powershell
pwsh -c "./gradlew :ui:components:build"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Canonical verify**

```powershell
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```
Expected: PASS, 5/5 targets, ≥110+1 tests.

- [ ] **Step 4: Commit**

```bash
git add ui/components/src/commonMain/kotlin/com/clayworks/kiln/ui/components/home/
git commit -m "$(cat <<'EOF'
feat(ui:components): KilnHomeScreen — TabNavigator scaffold for Phase 2a Track C

Top-level Scaffold with TopAppBar (title + gear icon → onOpenSettings)
and a 3-tab Voyager TabNavigator below (Library / Now Playing / Search).
Library tab is the default landing tab. Each tab's instances are
remembered keyed on (musicSource, player) so DI graph rebuilds don't
churn unrelated tabs.

App modules (MainActivity / Main.kt) wire this in Task 5, replacing the
H7 PlayFirstTrackScreen dev surface. Track A's gear-icon → SettingsRoute
toggle is preserved at the app-module layer.

NOT shipping: tab persistence across restarts (Track C3), Voyager
transitions/animations (Track C2 polish).

Phase 2a Track C Task 4 of 6.
EOF
)"
```

---

### Task 5: Wire `KilnHomeScreen` into both apps — replace `PlayFirstTrackScreen`

**Why:** end-to-end activation. After this task, the H7 dev surface is gone and the proper Kiln app shell is live on both platforms.

**Files:**
- Modify: `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt`
- Modify: `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt`

**Steps:**

- [ ] **Step 1: Modify MainActivity.kt — swap PlayFirstTrackScreen → KilnHomeScreen**

Edit `app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt`. In the `setContent { KilnTheme { Surface { if (showSettings) ... else ... } } }` block, replace:

```kotlin
PlayFirstTrackScreen(graph = graph, onOpenSettings = { showSettings = true })
```

with:

```kotlin
KilnHomeScreen(
    musicSource = graph.musicSource,
    player = graph.player,
    onOpenSettings = { showSettings = true },
)
```

Add import: `import com.clayworks.kiln.ui.components.home.KilnHomeScreen`.

**Delete the entire `PlayFirstTrackScreen` composable + the `playFirstTrackFromBrowse()` extension + the `scanLibrary()` helper.** These were the H7 dev surface and are now obsolete. Also delete the now-unused imports they referenced (BrowseScope, scan helpers, etc.) — let the compiler tell you which.

Keep:
- The permission-flow Composable logic — but it now needs to wrap KilnHomeScreen too (since READ_MEDIA_AUDIO is still required for the library scan). Restructure: `if (!permissionGranted) GrantPermissionScreen(onGrantClick) else KilnHomeScreen(...)`.

Concrete proposed shape:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as KilnApplication).graph
        setContent {
            val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.System)
            KilnTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) {
                        AndroidSettingsRoute(graph = graph, onClose = { showSettings = false })
                    } else {
                        PermissionGate {
                            KilnHomeScreen(
                                musicSource = graph.musicSource,
                                player = graph.player,
                                onOpenSettings = { showSettings = true },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredPermission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> permissionGranted = granted }

    if (!permissionGranted) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Text(
                text = "Kiln by Clayworks",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text("Audio library access required.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { permissionLauncher.launch(requiredPermission) }) {
                Text("Grant Permission")
            }
        }
    } else {
        content()
    }
}
```

The `AndroidSettingsRoute` from Track A + B is preserved verbatim.

- [ ] **Step 2: Modify Main.kt (Desktop) — same swap**

Edit `app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt`. Replace the `PlayFirstTrackScreen(graph, onOpenSettings = ...)` call with:

```kotlin
KilnHomeScreen(
    musicSource = graph.musicSource,
    player = graph.player,
    onOpenSettings = { showSettings = true },
)
```

Add import: `import com.clayworks.kiln.ui.components.home.KilnHomeScreen`.

**Delete the entire `PlayFirstTrackScreen` composable + `playFirstTrackFromBrowse()` + `scanLibrary()` helpers from Main.kt** — H7 dev surface gone.

The `DesktopSettingsRoute` from Track A + B is preserved verbatim.

- [ ] **Step 3: Build both apps**

```powershell
pwsh -c "./gradlew :app-android:assembleDebug :app-desktop:assemble"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Canonical verify-build**

```powershell
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```
Expected: PASS, 5/5 targets, ≥110+1 tests.

- [ ] **Step 5: Smoke-launch desktop**

```powershell
pwsh -c "./gradlew :app-desktop:run"
```
Manually verify (~30 sec):
1. Window opens; title bar shows "Kiln by Clayworks"; gear icon top-right
2. Bottom bar shows 3 NavigationBar items: Library / Now Playing / Search
3. Library tab is default; shows track list (if DB has tracks from prior scans) or "No tracks. Run a Library scan from Settings."
4. Switching tabs works (tap each one)
5. Tap a track in Library → playback starts (or fails silently if no playback wiring; that's expected since the existing DesktopAppGraph chain still works)
6. Now Playing tab shows the current track + transport buttons
7. Search tab text input works; typing a word produces results (after 300ms debounce)
8. Gear icon → SettingsScreen flow still works (Track A)
9. Close app

- [ ] **Step 6: Commit**

```bash
git add app-android/src/main/kotlin/com/clayworks/kiln/MainActivity.kt app-desktop/src/main/kotlin/com/clayworks/kiln/desktop/Main.kt
git commit -m "$(cat <<'EOF'
feat(apps): wire KilnHomeScreen — H7 dev surface retired — Phase 2a Track C

MainActivity (Android) + Main.kt (Desktop) replace PlayFirstTrackScreen
with KilnHomeScreen. The H7 Scan / Play First Track buttons are GONE;
the user now interacts with the proper 3-tab Kiln shell (Library /
Now Playing / Search) hosted by Voyager's TabNavigator. Settings (gear
icon) and Track A's AndroidSettingsRoute / DesktopSettingsRoute are
preserved.

Android: PermissionGate composable now wraps KilnHomeScreen — the
READ_MEDIA_AUDIO grant is still required before the library is
accessible; deny state shows the same "Audio library access required"
prompt as H7.

Cleanup: deleted PlayFirstTrackScreen, playFirstTrackFromBrowse,
scanLibrary helper from both files. The corresponding imports (BrowseScope,
take/toList, ScanError, etc.) were removed.

Phase 2a Track C Task 5 of 6.
EOF
)"
```

---

### Task 6: Final verification + Pixel 7 install + handoff appendix

**Why:** Track C wraps. Confirm the integrated state + capture any Voyager gotchas + drop a Pixel 7 manual smoke checklist for Clay.

**Files:**
- Modify (only if Voyager gotchas surfaced): `CLAUDE.md`
- Modify: `docs/superpowers/plans/2026-05-22-phase-2a-track-c-proper-ui.md` (append Pixel 7 manual smoke section)

**Steps:**

- [ ] **Step 1: Full test surface**

```powershell
pwsh -c "./gradlew :data:library:desktopTest :data:library:testAndroidHostTest :data:library:verifyCommonMainKilnDatabaseMigration :ui:components:desktopTest :app-android:testDebugUnitTest :app-android:assembleDebug :app-desktop:test :app-desktop:assemble :audio:playback:desktopTest --console=plain"
```
Expected: BUILD SUCCESSFUL across all 9 tasks.

- [ ] **Step 2: Canonical verify**

```powershell
pwsh -File .claude\skills\kiln-verify-build\scripts\run-verify.ps1
```
Expected: PASS, 5/5 targets, ≥110+1 tests.

- [ ] **Step 3: Install on Pixel 7 + autonomous launch smoke**

```powershell
pwsh -c "./gradlew :app-android:installDebug"
```
Then (only if device is connected):
```
pwsh -c "& 'C:\Users\chawo\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices"
```
If the Pixel 7 (`2A261FDH300B1P`) is listed:
```
pwsh -c "& 'C:\Users\chawo\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 2A261FDH300B1P shell monkey -p com.clayworks.kiln -c android.intent.category.LAUNCHER 1"
```
Expected: APK installs; LAUNCHER intent fires; logcat clean.

If device is not connected, document the gap in the report and skip.

- [ ] **Step 4: Append Pixel 7 manual smoke checklist to the plan file**

At the bottom of `docs/superpowers/plans/2026-05-22-phase-2a-track-c-proper-ui.md`, append:

```markdown
## Pixel 7 manual smoke checklist (delegated to Clay at session-close)

Device: Pixel 7 Pro / Tensor G2 / Android 14 / serial 2A261FDH300B1P

1. Launch Kiln (LAUNCHER intent already fired by autonomous Step 3).
2. **Expected:** Top app bar "Kiln by Clayworks" with gear icon top-right. Bottom NavigationBar shows 3 tabs: Library / Now Playing / Search. Library is the default tab.
3. **If no tracks scanned yet:** Library shows "No tracks. Run a Library scan from Settings." Tap gear → SettingsScreen → "Add Folder" → pick a folder via SAF (Track B work) → Close → Library still empty until the scan runs (Track C scope doesn't auto-scan).
4. **If tracks scanned:** Library shows a LazyColumn of tracks.
5. Tap a track → app should start playing (queue loaded, Media3 player active).
6. Tap "Now Playing" tab → see current track title + subtitle + position slider + Play/Pause/Skip Prev/Skip Next transport.
7. Tap Pause → playback stops. Tap Play → resumes.
8. Tap "Search" tab → input "Search your library" appears. Type a common word (e.g. "the").
9. **Expected:** After ~300ms debounce, results appear in the list below. Tap a result → playback starts.
10. Tap gear icon (top-right of any tab) → AndroidSettingsRoute opens. Verify the Track A + B settings UX still works (theme toggle, scan-on-launch, "Add Folder" via SAF).
11. Cold-kill the app via `adb -s 2A261FDH300B1P shell am force-stop com.clayworks.kiln`.
12. Relaunch via launcher. **Expected:** opens to Library tab (Voyager doesn't persist tab selection — Track C accepts this).
13. Gear → Settings persists (Track A); folder list preserved (Track B).

Any deviation from these expectations is a Track C bug to capture in the session-close handoff.
```

- [ ] **Step 5: Commit (only if file changes)**

```bash
git add docs/superpowers/plans/2026-05-22-phase-2a-track-c-proper-ui.md
git commit -m "$(cat <<'EOF'
docs(plan): Phase 2a Track C — Pixel 7 manual smoke checklist appendix

Appends a 13-step smoke checklist covering the Track C user flow on
Pixel 7: 3-tab Voyager shell launches, Library renders tracks (or empty
state), play-on-tap works, Now Playing transport works, Search debounce
returns results, Settings (Track A + B) still accessible via gear icon,
cold restart returns to Library default tab.

Phase 2a Track C Task 6 of 6. Track C (scoped) is complete locally;
PR opens after Track B's PR merges.
EOF
)"
```

---

## Final verification (after all 6 tasks)

Same gates as Task 6 Steps 1-3.

## PR-opening notes

- Branch: `phase-2a-track-c-proper-ui` based on `phase-2a-track-b-saf-folder-picker`
- After Tracks A + B merge, rebase Track C onto fresh `main`
- PR description should call out the scoped nature: FFT visualizer, Coil album art, Circuit wiring, mini-player, queue UI are all explicitly deferred to Track C2 / C3 / future session

## Risk register (Track C specifics)

| Risk | Mitigation |
|------|-----------|
| `MusicSource.browse(...).take(500).toList()` blocks indefinitely if SQLDelight asFlow's mapToList doesn't terminate | The take(500) operator caps at 500 emissions; once reached, the upstream is cancelled. asFlow emits the current value immediately on collection, so the LaunchedEffect completes within ~50ms on a populated DB. |
| Voyager Tab.Content() runs in a shared Compose scope per tab — state lost when user switches away | Acceptable for Track C. Voyager's tab.Content is re-composed when the tab is re-selected; LaunchedEffect(Unit) re-fires. For Library, this means re-fetching the first 500 tracks every tab-switch — Track C2 will cache via a remembered ScreenModel. |
| `Icons.Filled.LibraryMusic` / `PlayCircle` / `Search` not in compose-material-icons-core | All three are in `compose-material-icons-core` per Compose-MP 1.11. If empirically missing, fall back to `Icons.Filled.List` / `Icons.Filled.PlayArrow` / `Icons.Filled.Search` (Search IS definitely in core). |
| `delay(300)` debounce inside LaunchedEffect(query) — cancellation on each keystroke fires a new LaunchedEffect | This is the intended behavior: each query change cancels the previous LaunchedEffect (in-flight delay aborts), starts a new one. Net effect: search only fires when the user stops typing for 300ms. Correct UX. |
| `OutlinedTextField` doesn't have a content-description by default; test `onNodeWithText("Search your library")` matches the label, not the input itself | Compose UI test convention is to match the label for OutlinedTextField. `performTextInput` after a focus click works on the labeled field. If the test breaks, use `onNodeWithText("Search your library").performTextInput(...)` directly — it works because the label is the accessible-name. |
| Voyager 1.1.0-beta03 is a beta — possible breaking API changes | Pinned to a specific version in libs.versions.toml; no auto-upgrade. The TabNavigator + Tab + TabOptions API has been stable since 1.0.x. |
| `KilnHomeScreen` instantiates Tab objects via `remember(musicSource, player) { ... }` — if MusicSource implementation changes between recompositions, tabs reset | The DI graph is process-lived (Track A's pattern); musicSource and player references don't change across recompositions. The `remember(musicSource, player)` key is defensive. |
| `Box(modifier = Modifier.fillMaxSize().padding(padding))` inside Scaffold's content slot — older Compose-MP versions don't propagate inner padding correctly | Compose-MP 1.11.0 handles this correctly. If a future version regresses, the Scaffold content lambda can use the `padding` parameter directly without the Box wrapper. |
| The H7 PlayFirstTrackScreen deletion is destructive — Clay's session-close smoke depended on it | The new KilnHomeScreen IS the replacement; smoke now hits the Library tab + tap-to-play. Equivalent or superior UX. |

---

## Out of scope (deliberate for Track C)

- **FFT Fluid Canvas visualizer** in Now Playing — Track C2 (the spec's marquee UI element; needs separate planning + Skia surface integration)
- **Coil-backed album art** on Library/Search rows — Track C2 (album art extraction is plumbing-ready; Coil 3.4.0 dep is in :ui:theme already)
- **Circuit presenter wiring** for Now Playing — Track C3 (the Circuit showcase the spec mandates; replaces manual StateFlow.collectAsState with a Circuit Presenter)
- **Mini-player overlay** that persists across tabs — Track C3 (needs a Voyager Navigator wrapper or a Scaffold floatingActionButton slot)
- **Queue list with reorder** — Track C3 (drag-to-reorder via accompanist-reorderable or Compose's native reorderable APIs)
- **Sectioned search** (Tracks / Albums / Artists / Playlists) — Track C3 (Phase 2a Flight D plan §4)
- **Library sort + filter UI** — Track C2 (drop-down sort: Title / Artist / Album / Date Added; filter by genre / decade)
- **Tab persistence across cold restarts** — accepts the default-Library-tab behavior
- **Voyager transitions / animations** — defer to Track C2 polish
- **`scan_on_launch` actually auto-scanning at app launch** — Track A persists the toggle but doesn't yet wire the auto-scan hook. Trivial Phase 2a Flight follow-up: `if (settings.scanOnLaunch.first()) graph.scanner.scanIncremental()` after the graph is constructed (Application.onCreate on Android; Main.kt on Desktop).

---

End of Phase 2a Track C (scoped) plan. Total: 6 logical tasks, ~6 commits, ~3-5 wall-clock hours with subagent dispatch + two-stage review.
