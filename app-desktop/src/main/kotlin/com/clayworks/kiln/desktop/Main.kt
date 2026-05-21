// Desktop H7 vertical-slice entry point. Mirrors MainActivity's
// PlayFirstTrackScreen but without the Android permission flow.
//
// The graph is instantiated once at process start. userDataDir defaults to
// ~/.kiln; scanFolders defaults to Clay's D:\tiddl per CLAUDE.md gotcha.
// Settings UI (MVP Sessions 26-28) will read these from a Settings table.

package com.clayworks.kiln.desktop

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import arrow.core.Either
import com.clayworks.kiln.desktop.di.DesktopAppGraph
import com.clayworks.kiln.desktop.di.ScanFolders
import com.clayworks.kiln.desktop.di.UserDataDir
import com.clayworks.kiln.desktop.di.create
import com.clayworks.kiln.library.scan.LibraryScanner
import com.clayworks.kiln.library.scan.ScanError
import com.clayworks.kiln.library.scan.ScanResult
import com.clayworks.kiln.library.source.BrowseScope
import java.nio.file.Path
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

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
        scanFolders = ScanFolders(listOf(Path.of("D:\\tiddl"))),
    )
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Kiln by Clayworks",
        ) {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PlayFirstTrackScreen(graph)
                }
            }
        }
    }
}

@Composable
private fun PlayFirstTrackScreen(graph: DesktopAppGraph) {
    val coroutineScope = rememberCoroutineScope()
    val playerState by graph.player.state.collectAsState()
    val positionMs by graph.player.positionMs.collectAsState()
    val queueState by graph.player.queue.collectAsState()

    var scanStatus by remember { mutableStateOf("Not scanned") }
    var lastError by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = "Kiln by Clayworks",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(24.dp))

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
                        lastError = when (err) {
                            is ScanError.PermissionDenied -> "Filesystem access denied: ${err.message}"
                            is ScanError.IoError -> "Scan I/O error: ${err.cause.message}"
                            is ScanError.MetadataParseError ->
                                "Tag parse failed for ${err.path}: ${err.cause.message}"
                            is ScanError.Internal -> "Internal scan error: ${err.message}"
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

        Spacer(modifier = Modifier.height(24.dp))
        Text("State: $playerState")
        Text("Position: ${positionMs}ms")
        queueState.currentItem?.let { item ->
            Text("Now: ${item.title}")
        }
        lastError?.let { Text("⚠ $it") }
    }
}

private suspend fun DesktopAppGraph.playFirstTrackFromBrowse() {
    val firstTrack = musicSource.browse(BrowseScope.AllTracks()).take(1).toList()
    if (firstTrack.isNotEmpty()) {
        player.loadQueue(items = firstTrack, startIndex = 0, autoPlay = true)
    }
}

/**
 * Tiny convenience: call scanIncremental + fork onResult / onError. Forking via
 * callbacks (instead of throwing on Either.Left) preserves the typed [ScanError]
 * sub-type identity so callers can pattern-match.
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
