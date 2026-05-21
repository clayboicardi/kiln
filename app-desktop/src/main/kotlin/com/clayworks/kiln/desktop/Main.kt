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
import com.clayworks.kiln.desktop.di.DesktopAppGraph
import com.clayworks.kiln.desktop.di.ScanFolders
import com.clayworks.kiln.desktop.di.UserDataDir
import com.clayworks.kiln.desktop.di.create
import com.clayworks.kiln.library.scan.LibraryScanner
import com.clayworks.kiln.library.scan.ScanResult
import com.clayworks.kiln.library.source.BrowseScope
import java.nio.file.Path
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

fun main() = application {
    val graph = remember {
        DesktopAppGraph::class.create(
            userDataDir = UserDataDir(Path.of(System.getProperty("user.home"), ".kiln")),
            scanFolders = ScanFolders(listOf(Path.of("D:\\tiddl"))),
        )
    }
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
                runCatching {
                    graph.scanner.scanLibrary { result ->
                        scanStatus = "Scan: ${result.tracksAdded} added, " +
                            "${result.tracksUpdated} updated, " +
                            "${result.tracksUnchanged} unchanged, " +
                            "${result.tracksSoftDeleted} removed " +
                            "(${result.durationMs}ms)"
                    }
                }.onFailure { e ->
                    lastError = "Scan failed: ${e.message}"
                    scanStatus = "Scan: error"
                }
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

private suspend inline fun LibraryScanner.scanLibrary(
    crossinline onResult: (ScanResult) -> Unit,
) {
    when (val result = scanIncremental()) {
        is arrow.core.Either.Right -> onResult(result.value)
        is arrow.core.Either.Left -> throw IllegalStateException("Scan error: ${result.value}")
    }
}
