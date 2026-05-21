// Tests for fileSystemPathToFileUri — pure-string coverage that runs on every
// target. Roundtrip verification via java.net.URI + java.io.File is implicitly
// validated by the existing JvmFlacDecoderImpl + FlacDecodeSmokeTest paths
// (which CALL the producer's output and feed it to the JVM URI parser).

package com.clayworks.kiln.library.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalLibrarySourceMappersTest {

    // ---------- fileSystemPathToFileUri — Windows paths ----------

    @Test fun windowsDriveLetterPath_addsExtraSlashAndPrefix() {
        assertEquals(
            "file:///D:/tiddl/song.flac",
            fileSystemPathToFileUri("D:\\tiddl\\song.flac"),
        )
    }

    @Test fun windowsPathWithSpaces_percentEncodes() {
        assertEquals(
            "file:///D:/tiddl/2%20Chainz/song.flac",
            fileSystemPathToFileUri("D:\\tiddl\\2 Chainz\\song.flac"),
        )
    }

    @Test fun windowsPathWithParenthesesAndApostrophe_percentEncodes() {
        assertEquals(
            "file:///C:/Music/Foo%20%28Live%29/track%27s.flac",
            fileSystemPathToFileUri("C:\\Music\\Foo (Live)\\track's.flac"),
        )
    }

    @Test fun windowsPathLowercaseDrive_accepted() {
        assertEquals(
            "file:///c:/Music/song.flac",
            fileSystemPathToFileUri("c:\\Music\\song.flac"),
        )
    }

    @Test fun windowsForwardSlashes_normalized() {
        // Some scanners may already use forward slashes on Windows.
        assertEquals(
            "file:///D:/tiddl/song.flac",
            fileSystemPathToFileUri("D:/tiddl/song.flac"),
        )
    }

    // ---------- fileSystemPathToFileUri — Unix paths ----------

    @Test fun unixAbsolutePath_addsFileSchemeOnly() {
        assertEquals(
            "file:///storage/Music/song.flac",
            fileSystemPathToFileUri("/storage/Music/song.flac"),
        )
    }

    @Test fun unixPathWithSpaces_percentEncodes() {
        assertEquals(
            "file:///storage/Music/2%20Chainz/song.flac",
            fileSystemPathToFileUri("/storage/Music/2 Chainz/song.flac"),
        )
    }

    @Test fun unixAndroidEmulatedPath_percentEncodes() {
        assertEquals(
            "file:///storage/emulated/0/Music/track%20%2301.flac",
            fileSystemPathToFileUri("/storage/emulated/0/Music/track #01.flac"),
        )
    }

    // ---------- pass-through for already-URI inputs ----------

    @Test fun httpUriPassesThrough() {
        val httpUri = "http://example.com/stream.flac"
        assertEquals(httpUri, fileSystemPathToFileUri(httpUri))
    }

    @Test fun contentUriPassesThrough() {
        // Android MediaStore content:// URIs — future-source readiness; not used at MVP.
        val contentUri = "content://media/external/audio/media/42"
        assertEquals(contentUri, fileSystemPathToFileUri(contentUri))
    }

    // ---------- character classes ----------

    @Test fun unreservedCharactersNotEncoded() {
        // RFC 3986 unreserved: ALPHA / DIGIT / "-" / "." / "_" / "~"
        assertEquals(
            "file:///A-Z_a-z_0-9_dash-dot._tilde~.flac",
            fileSystemPathToFileUri("/A-Z_a-z_0-9_dash-dot._tilde~.flac"),
        )
    }

    @Test fun nonAsciiCharacterEncodedAsUtf8Bytes() {
        // "é" = U+00E9 = 0xC3 0xA9 in UTF-8 → %C3%A9
        val result = fileSystemPathToFileUri("/Music/Beyoncé.flac")
        assertEquals("file:///Music/Beyonc%C3%A9.flac", result)
    }

    @Test fun nonAsciiThreeBytePathEncoded() {
        // "音" = U+97F3 = 0xE9 0x9F 0xB3 in UTF-8 → %E9%9F%B3
        val result = fileSystemPathToFileUri("/Music/音楽.flac")
        // Just verify the produced URI is well-formed and percent-encoded.
        assertTrue(result.startsWith("file:///Music/"), "got: $result")
        assertTrue(result.endsWith(".flac"), "got: $result")
        assertTrue(result.contains("%E9%9F%B3"), "got: $result")
    }
}
