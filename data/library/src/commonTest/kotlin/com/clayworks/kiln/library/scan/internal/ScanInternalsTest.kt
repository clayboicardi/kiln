// Tests for the file-scope helpers in ScanInternals.kt. Pure-logic units —
// no DB / filesystem / MediaStore involvement, so they run on every target
// without per-platform fixtures.

package com.clayworks.kiln.library.scan.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScanInternalsTest {

    // ---------- toSortName ----------

    @Test fun toSortName_stripsTheArticle() {
        assertEquals("beatles", toSortName("The Beatles"))
    }

    @Test fun toSortName_stripsAnArticle() {
        assertEquals("acquired taste", toSortName("An Acquired Taste"))
    }

    @Test fun toSortName_stripsAArticle() {
        assertEquals("day in the life", toSortName("A Day in the Life"))
    }

    @Test fun toSortName_isCaseInsensitiveForArticle() {
        assertEquals("who", toSortName("the Who"))
    }

    @Test fun toSortName_leavesNonArticlePrefixAlone() {
        // "Theatre" starts with "The" but is not "The " (no space after) — leave it.
        assertEquals("theatre of pain", toSortName("Theatre of Pain"))
    }

    @Test fun toSortName_lowercasesAlways() {
        assertEquals("oasis", toSortName("OASIS"))
    }

    @Test fun toSortName_trimsWhitespace() {
        assertEquals("beatles", toSortName("  The Beatles  "))
    }

    // ---------- parseChannels ----------

    @Test fun parseChannels_mono() {
        assertEquals(1L, parseChannels("Mono"))
    }

    @Test fun parseChannels_stereo() {
        assertEquals(2L, parseChannels("Stereo"))
    }

    @Test fun parseChannels_caseInsensitive() {
        assertEquals(2L, parseChannels("STEREO"))
        assertEquals(1L, parseChannels("mono"))
    }

    @Test fun parseChannels_surround() {
        assertEquals(6L, parseChannels("5.1"))
        assertEquals(8L, parseChannels("7.1"))
    }

    @Test fun parseChannels_numericFallback() {
        assertEquals(4L, parseChannels("4"))
    }

    @Test fun parseChannels_unknownDefaultsToStereo() {
        assertEquals(2L, parseChannels("garbage"))
        assertEquals(2L, parseChannels(null))
        assertEquals(2L, parseChannels(""))
    }

    // ---------- String.parseLeadingLong ----------

    @Test fun parseLeadingLong_plainInteger() {
        assertEquals(5L, "5".parseLeadingLong())
    }

    @Test fun parseLeadingLong_trackOfTotalForm() {
        // ID3-style "track/total" — take just the track.
        assertEquals(5L, "5/12".parseLeadingLong())
    }

    @Test fun parseLeadingLong_trimsWhitespace() {
        assertEquals(5L, "  5  ".parseLeadingLong())
    }

    @Test fun parseLeadingLong_blankIsNull() {
        assertNull("".parseLeadingLong())
        assertNull("   ".parseLeadingLong())
    }

    @Test fun parseLeadingLong_unparseableIsNull() {
        assertNull("abc".parseLeadingLong())
        assertNull("12.5".parseLeadingLong())  // decimal, not Long
    }

    // ---------- String.parseReplayGainDb ----------

    @Test fun parseReplayGainDb_signedWithSpaceUnit() {
        assertEquals(-6.42, "-6.42 dB".parseReplayGainDb())
    }

    @Test fun parseReplayGainDb_signedNoSpaceUnit() {
        assertEquals(-6.42, "-6.42dB".parseReplayGainDb())
    }

    @Test fun parseReplayGainDb_signedNoUnit() {
        assertEquals(-6.42, "-6.42".parseReplayGainDb())
    }

    @Test fun parseReplayGainDb_positiveValue() {
        assertEquals(3.14, "+3.14 dB".parseReplayGainDb())
    }

    @Test fun parseReplayGainDb_caseInsensitiveUnit() {
        assertEquals(-6.42, "-6.42 DB".parseReplayGainDb())
    }

    @Test fun parseReplayGainDb_blankIsNull() {
        assertNull("".parseReplayGainDb())
        assertNull("   ".parseReplayGainDb())
    }

    @Test fun parseReplayGainDb_unparseableIsNull() {
        assertNull("garbage".parseReplayGainDb())
    }
}
