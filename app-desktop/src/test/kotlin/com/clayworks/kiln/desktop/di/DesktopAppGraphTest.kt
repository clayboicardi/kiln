// Desktop DI graph wiring test. Verifies all @Provides chains resolve
// without exceptions when create() is called with realistic value-class
// constructor args. Closes review P2-10.
//
// Note: `graph.player` construction goes through createJvmFlacDecoder()
// which calls LibFlacLoader.load() — this extracts and loads the vendored
// FLAC.dll from the :audio:playback resources, identical to the existing
// FlacDecodeSmokeTest path. Idempotent across the JVM lifetime; the load
// already happens for :audio:playback's desktopTest task, so wiring it
// here for :app-desktop's `test` task confirms the path also works when
// invoked from a separate module's test classpath.

package com.clayworks.kiln.desktop.di

import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertNotNull

class DesktopAppGraphTest {

    @Test
    fun graph_provides_full_chain() {
        val tempDir = Files.createTempDirectory("kiln-graph-test-")
        val scanRoot = Files.createTempDirectory(tempDir, "scan-")
        try {
            val graph = DesktopAppGraph::class.create(
                userDataDir = UserDataDir(tempDir),
                scanFolders = ScanFolders(listOf(scanRoot)),
            )
            assertNotNull(graph.musicSource)
            assertNotNull(graph.scanner)
            assertNotNull(graph.player)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun graph_value_class_type_tags_disambiguate_path_params() {
        // UserDataDir + ScanFolders both wrap Path; the value-class tags let
        // kotlin-inject route them to distinct providers. Regression for the
        // CLAUDE.md gotcha "Value-class type-tags distinguish ambiguous JVM-
        // type DI bindings."
        val tempDir = Files.createTempDirectory("kiln-graph-test-")
        try {
            val graph = DesktopAppGraph::class.create(
                userDataDir = UserDataDir(tempDir.resolve("home")),
                scanFolders = ScanFolders(listOf(tempDir.resolve("music"))),
            )
            // If the type tags were broken, kotlin-inject's KSP would have
            // failed compilation, not runtime. But this test pins the contract
            // so a future refactor doesn't silently merge the types.
            assertNotNull(graph)
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }
}
