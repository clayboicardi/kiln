// Native library extraction + pre-load for the JNA libFLAC bridge.
//
// JNA's Native.load(name, ...) cannot read native libraries from inside a JAR,
// so we extract the vendored DLL/SO/DYLIB to a temp directory at first use and
// (a) System.load() the absolute path (puts the library into the JVM's loaded
// modules so the OS dynamic linker can resolve symbols) AND (b) prepend the
// temp directory to jna.library.path so JNA's name-based lookup also finds it.
//
// Belt-and-suspenders: either of (a) or (b) alone is usually sufficient on
// Windows, but the pair makes startup behavior less sensitive to JNA-version
// quirks around when jna.library.path is read.
//
// Package note: named `nativeio` rather than `native` to avoid `native` being a
// Java reserved keyword (Kotlin tolerates `.native.` packages but the bytecode
// emits Java-style segments that some tools/IDEs flag).

package com.clayworks.kiln.audio.playback.nativeio

import co.touchlab.kermit.Logger
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

internal object NativeLibraryLoader {
    private val log = Logger.withTag("NativeLibLoader")

    @Volatile
    private var libFlacExtractedAt: Path? = null

    /**
     * Ensure the vendored libFLAC native library is extracted from the classpath
     * JAR and loaded into the JVM process. Idempotent + thread-safe.
     *
     * On Windows the vendored file at `/native/win-x64/libFLAC.dll` is extracted
     * to a temp dir under the name `FLAC.dll` — matching JNA's platform-canonical
     * library name for `Native.load("FLAC", ...)`.
     */
    @Synchronized
    fun ensureLibFlacLoaded() {
        if (libFlacExtractedAt != null) return
        val platform = detectPlatform()
        val resourcePath = "/native/$platform/libFLAC.dll"
        val extractedFileName = "FLAC.dll"  // JNA's Windows-canonical name (drops "lib" prefix)

        val tempPath = extractResourceToTemp(resourcePath, extractedFileName)

        // (a) Direct System.load — puts the library into the JVM's loaded modules.
        System.load(tempPath.toAbsolutePath().toString())

        // (b) Prepend temp dir to jna.library.path — backup for JNA name lookup.
        val tempDir = tempPath.parent.toAbsolutePath().toString()
        val existing = System.getProperty("jna.library.path")
        val merged = if (existing.isNullOrEmpty()) tempDir else "$tempDir${File.pathSeparator}$existing"
        System.setProperty("jna.library.path", merged)

        libFlacExtractedAt = tempPath
        log.i { "libFLAC extracted to $tempPath (jna.library.path now: $merged)" }
    }

    /** Visible for testing — extracted path of libFLAC, or null if not yet loaded. */
    internal fun extractedPath(): Path? = libFlacExtractedAt

    private fun extractResourceToTemp(resourcePath: String, extractedFileName: String): Path {
        val input = NativeLibraryLoader::class.java.getResourceAsStream(resourcePath)
            ?: throw IOException(
                "Vendored native library not found on classpath: $resourcePath. " +
                    "Expected under :audio:playback/src/desktopMain/resources/native/. " +
                    "If you just pulled this branch, run a clean Gradle build to refresh the JAR.",
            )
        return input.use { stream ->
            val tempDir = Files.createTempDirectory("kiln-native-")
            tempDir.toFile().deleteOnExit()
            val tempFile = tempDir.resolve(extractedFileName)
            Files.copy(stream, tempFile, StandardCopyOption.REPLACE_EXISTING)
            tempFile.toFile().deleteOnExit()
            tempFile
        }
    }

    private fun detectPlatform(): String {
        val osName = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        val osArch = System.getProperty("os.arch", "").lowercase(Locale.ROOT)
        return when {
            // x64 architecture canonical names: "amd64" (most JVMs) or "x86_64" (some).
            // Earlier `osArch.contains("64")` was a false-positive on ARM64 Windows
            // (osArch="aarch64"/"arm64" both contain "64") and would silently load
            // the x64 DLL → crash on first FLAC playback.
            osName.contains("win") && (osArch == "amd64" || osArch == "x86_64") -> "win-x64"
            // Linux-x64 / macOS arm64 / macOS x64 / Windows-arm64 — vendor the
            // corresponding .so/.dylib/.dll and add the case here when those
            // platforms enter scope (out of MVP per spec §2).
            else -> error(
                "Unsupported platform for vendored libFLAC: os.name='$osName', os.arch='$osArch'. " +
                    "MVP supports win-x64 only.",
            )
        }
    }
}
