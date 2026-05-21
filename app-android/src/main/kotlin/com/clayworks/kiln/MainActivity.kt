// MainActivity — H7 vertical-slice entry point for the Android target.
// Renders the scan + play-first-track UI; observes PlayerState / positionMs /
// queue via Compose collectAsState. Per the spec, this is the *minimum-viable*
// end-to-end demonstration that the full pipeline works:
//   MediaStore → AndroidMediaStoreScanner → SQLDelight → MusicSource →
//   Media3ExoPlayerImpl → ExoPlayer → audio out.
//
// READ_MEDIA_AUDIO (API 33+) or READ_EXTERNAL_STORAGE (API <33, capped via
// AndroidManifest maxSdkVersion=32) gates MediaStore access. Permission flow
// runs at composition time via ActivityResultContracts.RequestPermission.

package com.clayworks.kiln

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import arrow.core.Either
import com.clayworks.kiln.di.AndroidAppGraph
import com.clayworks.kiln.library.scan.LibraryScanner
import com.clayworks.kiln.library.scan.ScanError
import com.clayworks.kiln.library.scan.ScanResult
import com.clayworks.kiln.library.source.BrowseScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as KilnApplication).graph
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayFirstTrackScreen(graph)
                }
            }
        }
    }
}

/**
 * H7 vertical-slice surface. Single composable, no navigation — Settings UI
 * lands at MVP Sessions 26-28. The three buttons cover the smoke path:
 * Grant Permission → Scan Library → Play First Track. State display below
 * shows player state, position, and current item.
 */
@Composable
private fun PlayFirstTrackScreen(graph: AndroidAppGraph) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val playerState by graph.player.state.collectAsState()
    val positionMs by graph.player.positionMs.collectAsState()
    val queueState by graph.player.queue.collectAsState()

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
    var scanStatus by remember { mutableStateOf("Not scanned") }
    var lastError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionGranted = granted
        if (!granted) {
            lastError = "Permission denied — Settings > Apps > Kiln > Permissions to grant manually"
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = "Kiln by Clayworks",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (!permissionGranted) {
            Text("Audio library access required.")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { permissionLauncher.launch(requiredPermission) }) {
                Text("Grant Permission")
            }
        } else {
            Button(onClick = {
                scanStatus = "Scanning…"
                lastError = null
                coroutineScope.launch {
                    graph.scanner.scanLibrary(
                        onResult = { result ->
                            scanStatus = "Scan: ${result.tracksAdded} added, " +
                                "${result.tracksUpdated} updated, " +
                                "${result.tracksUnchanged} unchanged, " +
                                "${result.tracksSoftDeleted} removed " +
                                "(${result.durationMs}ms)"
                        },
                        onError = { err ->
                            scanStatus = "Scan: error"
                            when (err) {
                                is ScanError.PermissionDenied -> {
                                    // Race: permission was revoked from Settings between
                                    // checkSelfPermission and the MediaStore query. Re-show
                                    // the Grant Permission UI instead of presenting it as a
                                    // generic crash.
                                    permissionGranted = false
                                    lastError = "Permission revoked — re-grant via the button above."
                                }
                                is ScanError.IoError -> lastError = "Scan I/O error: ${err.cause.message}"
                                is ScanError.MetadataParseError ->
                                    lastError = "Tag parse failed for ${err.path}: ${err.cause.message}"
                                is ScanError.Internal -> lastError = "Internal scan error: ${err.message}"
                            }
                        },
                    )
                }
            }) { Text("Scan Library") }

            Spacer(modifier = Modifier.height(8.dp))
            Text(scanStatus)
            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = {
                lastError = null
                coroutineScope.launch {
                    runCatching { graph.playFirstTrackFromBrowse() }
                        .onFailure { e -> lastError = "Play failed: ${e.message}" }
                }
            }) { Text("Play First Track") }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("State: $playerState")
        Text("Position: ${positionMs}ms")
        queueState.currentItem?.let { item ->
            Text("Now: ${item.title}")
        }
        lastError?.let { Text("⚠ $it") }
    }
}

/**
 * Tiny convenience: collect the first AllTracks MediaItem and load it as a
 * one-item queue with autoPlay. Exposed as an AndroidAppGraph extension so
 * the composable above stays focused on UI.
 */
private suspend fun AndroidAppGraph.playFirstTrackFromBrowse() {
    val firstTrack = musicSource.browse(BrowseScope.AllTracks()).take(1).toList()
    if (firstTrack.isNotEmpty()) {
        player.loadQueue(items = firstTrack, startIndex = 0, autoPlay = true)
    }
}

/**
 * Tiny convenience: call scanIncremental + fork onResult / onError. Forking via
 * callbacks (instead of throwing on Either.Left) preserves the typed [ScanError]
 * sub-type identity so callers can pattern-match on PermissionDenied vs IoError
 * vs MetadataParseError vs Internal. Throwing collapses everything to a generic
 * exception + string message, losing actionable distinction.
 */
private suspend inline fun LibraryScanner.scanLibrary(
    crossinline onResult: (ScanResult) -> Unit,
    crossinline onError: (ScanError) -> Unit,
) {
    when (val result = scanIncremental()) {
        is Either.Right -> onResult(result.value)
        is Either.Left -> onError(result.value)
    }
}
