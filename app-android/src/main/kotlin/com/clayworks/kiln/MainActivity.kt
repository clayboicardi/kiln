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
import com.clayworks.kiln.di.AndroidAppGraph
import com.clayworks.kiln.library.settings.ThemeMode
import com.clayworks.kiln.saf.rememberSafFolderPicker
import com.clayworks.kiln.ui.components.home.KilnHomeScreen
import com.clayworks.kiln.ui.components.settings.SettingsScreen
import com.clayworks.kiln.ui.components.settings.SettingsState
import com.clayworks.kiln.ui.theme.KilnTheme
import kotlinx.coroutines.launch

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
                        AndroidSettingsRoute(
                            graph = graph,
                            onClose = { showSettings = false },
                        )
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

/**
 * Phase 2a Track A: Settings route on Android. Hoists the three SettingsRepository
 * flows into Compose state, routes the four SettingsScreen callbacks back to the
 * repo's suspend setters via a rememberCoroutineScope.
 *
 * Phase 2a Track B: folder picker swapped from a Track A Toast stub to a real
 * SAF launcher (rememberSafFolderPicker). On a successful pick the URI is
 * appended to scanFolders (dedup against the current list); the scanner picks
 * up the new entry on the next scan via AndroidMediaStoreScanner's
 * safTreeUrisFlow constructor param.
 */
@Composable
private fun AndroidSettingsRoute(
    graph: AndroidAppGraph,
    onClose: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val themeMode by graph.settings.themeMode.collectAsState(initial = ThemeMode.System)
    val scanOnLaunch by graph.settings.scanOnLaunch.collectAsState(initial = false)
    val scanFolders by graph.settings.scanFolders.collectAsState(initial = emptyList())

    val launchSafPicker = rememberSafFolderPicker(onPicked = { uri ->
        if (uri !in scanFolders) {
            coroutineScope.launch {
                graph.settings.setScanFolders(scanFolders + uri)
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
