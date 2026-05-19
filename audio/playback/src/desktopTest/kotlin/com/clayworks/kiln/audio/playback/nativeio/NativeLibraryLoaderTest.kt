// Validates the NativeLibraryLoader extraction step in isolation — confirms the
// vendored libFLAC.dll is reachable on the classpath, extractable to temp, and
// the loader is idempotent. No JNA usage yet; that lives in LibFlacBindingTest.

package com.clayworks.kiln.audio.playback.nativeio

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeLibraryLoaderTest {

    @Test
    fun `ensureLibFlacLoaded extracts the vendored DLL and is idempotent`() {
        // First call extracts.
        NativeLibraryLoader.ensureLibFlacLoaded()
        val firstPath = NativeLibraryLoader.extractedPath()
        assertNotNull(firstPath, "expected libFLAC to be extracted after first ensureLibFlacLoaded()")
        assertTrue(Files.exists(firstPath), "extracted file should exist on disk at $firstPath")
        assertEquals("FLAC.dll", firstPath.fileName.toString(), "extracted file should be JNA-canonical FLAC.dll")
        assertTrue(Files.size(firstPath) > 0L, "extracted file should be non-empty")

        // Second call is a no-op — same path.
        NativeLibraryLoader.ensureLibFlacLoaded()
        val secondPath = NativeLibraryLoader.extractedPath()
        assertEquals(firstPath, secondPath, "ensureLibFlacLoaded should be idempotent — same extracted path on repeat call")

        // jna.library.path includes the temp dir of the extracted file.
        val jnaLibPath = System.getProperty("jna.library.path") ?: ""
        val tempDir = firstPath.parent.toAbsolutePath().toString()
        assertTrue(
            tempDir in jnaLibPath,
            "jna.library.path should include extracted temp dir.\n  expected to contain: $tempDir\n  actual: $jnaLibPath",
        )
    }

    @AfterTest
    fun cleanup() {
        // No tear-down — the temp dir is marked deleteOnExit. Subsequent tests in
        // the same JVM (which is typical for Gradle's :test task) will see the
        // already-loaded singleton state, which is correct: this matches production
        // lifetime.
    }
}
