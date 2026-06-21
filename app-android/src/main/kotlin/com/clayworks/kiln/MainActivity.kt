// MainActivity — Android entry point. Hosts KilnHomeScreen (Phase 2a Track C
// 3-tab shell: Library / Now Playing / Search) behind a PermissionGate that
// enforces READ_MEDIA_AUDIO (API 33+) or READ_EXTERNAL_STORAGE (API <33,
// capped via AndroidManifest maxSdkVersion=32) before any library access.
// The gear icon in KilnHomeScreen's TopAppBar flips a boolean route to
// AndroidSettingsRoute (Phase 2a Track A+B).

package com.clayworks.kiln

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import com.clayworks.kiln.di.AndroidAppGraph
import com.clayworks.kiln.library.scan.AnalysisProgress
import com.clayworks.kiln.library.settings.ReplayGainMode
import com.clayworks.kiln.library.settings.ThemeMode
import com.clayworks.kiln.saf.rememberSafFolderPicker
import com.clayworks.kiln.ui.components.home.KilnHomeScreen
import com.clayworks.kiln.ui.components.settings.BackfillUiState
import com.clayworks.kiln.ui.components.settings.ScanUiState
import com.clayworks.kiln.ui.components.specsheet.LocalLibraryStats
import com.clayworks.kiln.ui.components.specsheet.LocalPlayer
import com.clayworks.kiln.ui.components.settings.SettingsScreen
import com.clayworks.kiln.ui.components.settings.SettingsState
import com.clayworks.kiln.ui.theme.KilnTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as KilnApplication).graph

        // Scan-on-launch: MediaStore requires READ_MEDIA_AUDIO, so only scan if
        // it is already granted (first run, pre-grant, no-ops — the scan runs on
        // the next launch once permission + toggle are set). lifecycleScope =
        // once per Activity create, not per recomposition.
        val launchPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        lifecycleScope.launch {
            val granted = ContextCompat.checkSelfPermission(this@MainActivity, launchPermission) ==
                PackageManager.PERMISSION_GRANTED
            if (granted && graph.settings.scanOnLaunch.first()) {
                graph.scanner.scanIncremental()
            }
        }

        setContent {
            val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.System)
            KilnTheme(themeMode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var showSettings by remember { mutableStateOf(false) }
                    if (showSettings) {
                        AndroidSettingsRoute(
                            graph = graph,
                            onClose = { showSettings = false },
                        )
                    } else {
                        PermissionGate {
                            // Provide the non-serializable runtime deps to the
                            // inner Voyager Navigators' Screens (NowPlaying +
                            // Spec Sheet) via CompositionLocals — they can't ride
                            // in Screen constructors (Voyager Screen : Serializable).
                            CompositionLocalProvider(
                                LocalLibraryStats provides graph.libraryStats,
                                LocalPlayer provides graph.player,
                            ) {
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
}

/**
 * Phase 2a Track A+B+D-C: Settings route on Android. Hoists five
 * SettingsRepository flows into Compose state, derives a reactive
 * `missingCount` from `countTracksMissingReplayGain`, and drives a
 * `BackfillUiState` state machine from
 * `TrackAnalysisRunner.runOnceWithProgress()` emissions.
 *
 * Phase 2a Track B: folder picker is a real SAF launcher (rememberSafFolderPicker).
 * On a successful pick the URI is appended to scanFolders (dedup against the
 * current list); the scanner picks up the new entry on the next scan via
 * AndroidMediaStoreScanner's safTreeUrisFlow constructor param.
 */
@Composable
private fun AndroidSettingsRoute(
    graph: AndroidAppGraph,
    onClose: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.System)
    val scanOnLaunch by graph.settings.scanOnLaunch.collectAsState(initial = false)
    val autoScanOnFolderAdd by graph.settings.autoScanOnFolderAdd.collectAsState(initial = true)
    val scanFolders by graph.settings.scanFolders.collectAsState(initial = emptyList())
    val replayGainMode by graph.settings.replayGainMode.collectAsState(initial = ReplayGainMode.Off)
    val replayGainPreAmpDb by graph.settings.replayGainPreAmpDb.collectAsState(initial = 0.0)

    val missingCountFlow = remember(graph) {
        graph.kilnDatabase.trackQueries.countTracksMissingReplayGain()
            .asFlow()
            .mapToOne(Dispatchers.IO)
            .map { it.toInt() }
    }
    val missingCount by missingCountFlow.collectAsState(initial = 0)

    var backfillState: BackfillUiState by remember { mutableStateOf<BackfillUiState>(BackfillUiState.Idle(0)) }
    LaunchedEffect(missingCount) {
        if (backfillState is BackfillUiState.Idle) {
            backfillState = BackfillUiState.Idle(missingCount)
        }
    }

    var scanState: ScanUiState by remember { mutableStateOf<ScanUiState>(ScanUiState.Idle) }
    // One incremental scan, mapping ScanResult/ScanError into the coarse UI state.
    // Uses the route's coroutineScope (matches the backfill button); leaving
    // Settings mid-scan cancels it — acceptable, the scan is short and re-triggerable.
    val runScanNow: () -> Unit = {
        coroutineScope.launch {
            scanState = ScanUiState.Scanning
            scanState = graph.scanner.scanIncremental().fold(
                { err -> ScanUiState.Error(err.toString()) },
                { res ->
                    ScanUiState.Done(
                        added = res.tracksAdded,
                        updated = res.tracksUpdated,
                        softDeleted = res.tracksSoftDeleted,
                        unchanged = res.tracksUnchanged,
                        durationMs = res.durationMs,
                    )
                },
            )
        }
    }

    val launchSafPicker = rememberSafFolderPicker(onPicked = { uri ->
        if (uri !in scanFolders) {
            coroutineScope.launch {
                graph.settings.setScanFolders(scanFolders + uri)
                if (autoScanOnFolderAdd) runScanNow()
            }
        }
    })

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
                replayGainMode = replayGainMode,
                replayGainPreAmpDb = replayGainPreAmpDb,
                backfill = backfillState,
                autoScanOnFolderAdd = autoScanOnFolderAdd,
                scan = scanState,
            ),
            onThemeModeChange = { mode ->
                coroutineScope.launch { graph.settings.setThemeMode(mode) }
            },
            onScanOnLaunchChange = { enabled ->
                coroutineScope.launch { graph.settings.setScanOnLaunch(enabled) }
            },
            onPickFolder = launchSafPicker,
            onRemoveFolder = { folder ->
                coroutineScope.launch {
                    graph.settings.setScanFolders(scanFolders - folder)
                }
            },
            onReplayGainModeChange = { mode ->
                coroutineScope.launch { graph.settings.setReplayGainMode(mode) }
            },
            onReplayGainPreAmpDbChange = { db ->
                coroutineScope.launch { graph.settings.setReplayGainPreAmpDb(db) }
            },
            onTriggerBackfill = {
                coroutineScope.launch {
                    var startedTotal = 0
                    graph.analysisRunner.runOnceWithProgress().collect { progress ->
                        backfillState = when (progress) {
                            is AnalysisProgress.Started -> {
                                startedTotal = progress.total
                                BackfillUiState.InProgress(0, 0, progress.total)
                            }
                            is AnalysisProgress.Progress ->
                                BackfillUiState.InProgress(progress.analyzed, progress.skipped, progress.total)
                            is AnalysisProgress.Complete ->
                                BackfillUiState.Complete(
                                    analyzed = progress.result.tracksAnalyzed,
                                    skipped = progress.result.tracksSkipped,
                                    total = startedTotal,
                                    albumsAggregated = progress.result.albumsAggregated,
                                    durationMs = progress.result.durationMs,
                                )
                        }
                    }
                }
            },
            onAutoScanOnFolderAddChange = { enabled ->
                coroutineScope.launch { graph.settings.setAutoScanOnFolderAdd(enabled) }
            },
            onTriggerScan = runScanNow,
        )
    }
}

/**
 * PermissionGate — wraps content that requires audio-library access.
 * Shows a Grant Permission affordance when READ_MEDIA_AUDIO (or
 * READ_EXTERNAL_STORAGE on API <33) is not granted, otherwise renders
 * the wrapped content. Replaces the H7 PlayFirstTrackScreen's inline
 * permission UI now that the dev surface is gone.
 */
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
            Text("Kiln by Clayworks", style = MaterialTheme.typography.headlineMedium)
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
