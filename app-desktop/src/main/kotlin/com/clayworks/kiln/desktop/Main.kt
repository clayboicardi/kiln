// Desktop entry point. Graph instantiated once at process start; userDataDir
// is injected directly (DB must exist before settings can be read). Scan
// folders flow from SettingsRepository (Phase 2a Track A rewire): on first
// launch (empty settings.scanFolders), seed D:\tiddl so Clay's existing
// workflow keeps working. Subsequent launches read the persisted list; the
// SettingsScreen edits it via the gear icon.
//
// Phase 2a Track C: the H7 PlayFirstTrackScreen dev surface is replaced by
// KilnHomeScreen — the Voyager TabNavigator-hosted 3-tab shell (Library /
// Now Playing / Search). KilnTheme wraps content via SettingsRepository
// .themeMode (Light/Dark/System dispatch). The gear icon in KilnHomeScreen's
// TopAppBar flips a boolean route to DesktopSettingsRoute, which renders the
// shared SettingsScreen composable from :ui:components. The folder picker is
// a real javax.swing.JFileChooser in DIRECTORIES_ONLY mode, invoked on the
// Swing EDT via Dispatchers.Swing so it doesn't violate Swing's single-thread
// rule when called from a Compose coroutine context.

package com.clayworks.kiln.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.clayworks.kiln.desktop.di.DesktopAppGraph
import com.clayworks.kiln.desktop.di.UserDataDir
import com.clayworks.kiln.desktop.di.create
import com.clayworks.kiln.library.settings.ThemeMode
import com.clayworks.kiln.ui.components.home.KilnHomeScreen
import com.clayworks.kiln.ui.components.settings.SettingsScreen
import com.clayworks.kiln.ui.components.settings.SettingsState
import com.clayworks.kiln.ui.theme.KilnTheme
import java.nio.file.Path
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

fun main() {
    // Process-lifetime graph — hoisted OUTSIDE application { } so its scope is
    // not Composable-bound. application { } is a Composable-scoped coroutine
    // environment; remember{} inside it ties the graph to the Compose runtime
    // and is not guaranteed identity across recomposition (especially future
    // multi-window scenarios). The graph holds native resources (JavaSound +
    // libFLAC); making its lifecycle explicit matches the Android side's
    // KilnApplication ownership pattern.
    val graph = DesktopAppGraph::class.create(
        userDataDir = UserDataDir(Path.of(System.getProperty("user.home"), ".kiln")),
    )

    // First-launch seed: if no scan_folders row exists yet, populate
    // D:\tiddl as the default (Clay's library root per CLAUDE.md). After
    // the first write — including a deliberate empty save once the
    // SettingsScreen is wired — this is a no-op because
    // SettingsRepository.scanFolders.first() returns the persisted value.
    //
    // Known Track A limit: an empty-list save and "no row yet" are
    // currently indistinguishable. The conflation is documented in the
    // Phase 2a Track A plan §Task 7 and revisited if/when a "clear all
    // folders" flow ships — a bootstrap_complete=true setting key is the
    // expected differentiation. SettingsRepository.scanFolders.first()
    // emits the current value immediately from SQLDelight's asFlow(), so
    // this completes in <50ms on cold start.
    runBlocking {
        val existing = graph.settings.scanFolders.first()
        if (existing.isEmpty()) {
            graph.settings.setScanFolders(listOf("D:\\tiddl"))
        }
    }

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Kiln by Clayworks",
        ) {
            val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.System)
            KilnTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) {
                        DesktopSettingsRoute(
                            graph = graph,
                            onClose = { showSettings = false },
                        )
                    } else {
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

/**
 * Phase 2a Track A: Settings route on Desktop. Mirrors the Android shape from
 * AndroidSettingsRoute exactly — three SettingsRepository flows hoisted into
 * Compose state, four SettingsScreen callbacks routed back to the repo's
 * suspend setters via a rememberCoroutineScope. The only divergence: the
 * folder-picker callback launches a real JFileChooser via pickFolderDialog()
 * (Swing EDT-scoped suspend helper) instead of the Android Toast stub.
 *
 * The picker guard (`picked !in scanFolders`) prevents adding the same folder
 * twice — JFileChooser doesn't expose a "preselected disabled paths" feature,
 * so the cheapest UX is silent dedupe at the callback seam.
 */
@Composable
private fun DesktopSettingsRoute(
    graph: DesktopAppGraph,
    onClose: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.System)
    val scanOnLaunch by graph.settings.scanOnLaunch.collectAsState(initial = false)
    val scanFolders by graph.settings.scanFolders.collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Kiln by Clayworks", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = onClose) { Text("Close") }
        }
        SettingsScreen(
            state = SettingsState(
                themeMode = themeMode,
                scanOnLaunch = scanOnLaunch,
                scanFolders = scanFolders,
            ),
            onThemeModeChange = { mode ->
                coroutineScope.launch { graph.settings.setThemeMode(mode) }
            },
            onScanOnLaunchChange = { enabled ->
                coroutineScope.launch { graph.settings.setScanOnLaunch(enabled) }
            },
            onPickFolder = {
                coroutineScope.launch {
                    val picked = pickFolderDialog()
                    if (picked != null && picked !in scanFolders) {
                        graph.settings.setScanFolders(scanFolders + picked)
                    }
                }
            },
            onRemoveFolder = { folder ->
                coroutineScope.launch {
                    graph.settings.setScanFolders(scanFolders - folder)
                }
            },
        )
    }
}

/**
 * JFileChooser in directory-select mode. Returns the chosen path as a String
 * or null if the user cancelled. The dialog itself is a Swing component that
 * MUST run on the EDT — we're called from a Compose coroutine context (the
 * rememberCoroutineScope launch in DesktopSettingsRoute), so wrap in
 * Dispatchers.Swing to jump threads before showing the dialog. Compose's
 * Window is Swing-backed under the hood, so the EDT exists and is the right
 * thread for modal interaction.
 */
private suspend fun pickFolderDialog(): String? =
    withContext(Dispatchers.Swing) {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Add Kiln library folder"
        }
        when (chooser.showOpenDialog(null)) {
            JFileChooser.APPROVE_OPTION -> chooser.selectedFile.absolutePath
            else -> null
        }
    }
