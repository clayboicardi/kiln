package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.playback.output.OutputLineException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HResultsTest {
    private val fmt = DecodedAudioFormat(44_100, 24, 2, SampleFormat.PCM_S24_LE)

    @Test
    fun `unsupported format with a known format is FormatRejected`() {
        val e = HResults.toException(HResults.AUDCLNT_E_UNSUPPORTED_FORMAT, "Initialize", fmt)
        assertIs<OutputLineException.FormatRejected>(e)
        assertEquals(fmt, e.format)
    }

    @Test
    fun `device errors are DeviceProblem with a readable reason`() {
        val cases = mapOf(
            HResults.AUDCLNT_E_DEVICE_INVALIDATED to "device invalidated",
            HResults.AUDCLNT_E_DEVICE_IN_USE to "device in use",
            HResults.E_NOTFOUND to "no audio output device",
            HResults.AUDCLNT_E_SERVICE_NOT_RUNNING to "audio service not running",
        )
        for ((hr, words) in cases) {
            val e = HResults.toException(hr, "Op", fmt)
            assertIs<OutputLineException.DeviceProblem>(e)
            assertTrue(words in e.detail, "'${e.detail}' should mention '$words'")
        }
    }

    @Test
    fun `unknown failures carry their hex code`() {
        val e = HResults.toException(0x80004005L.toInt(), "Start", null) // E_FAIL
        assertIs<OutputLineException.DeviceProblem>(e)
        assertTrue("0x80004005" in e.detail)
    }

    @Test
    fun `check passes S_OK and S_FALSE`() {
        HResults.check(HResults.S_OK, "x")
        HResults.check(HResults.S_FALSE, "x")
    }
}
