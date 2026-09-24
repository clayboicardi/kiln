package com.clayworks.kiln.audio.playback.output

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.dsp.SampleFormat
import com.clayworks.kiln.audio.playback.FakeOutputLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopOutputLineFactoryTest {
    private val fmt = DecodedAudioFormat(44_100, 24, 2, SampleFormat.PCM_S24_LE)

    @Test
    fun `uses WASAPI when it's available`() {
        val opened = mutableListOf<String>()
        val f = DesktopOutputLineFactory({ true }, { opened += "wasapi"; FakeOutputLine() }, { opened += "javasound"; FakeOutputLine() })
        f.open(fmt)
        assertEquals(listOf("wasapi"), opened)
    }

    @Test
    fun `falls back to Java Sound when WASAPI can't initialize`() {
        val opened = mutableListOf<String>()
        val f = DesktopOutputLineFactory({ false }, { opened += "wasapi"; FakeOutputLine() }, { opened += "javasound"; FakeOutputLine() })
        f.open(fmt)
        assertEquals(listOf("javasound"), opened)
    }

    @Test
    fun `decides once per session`() {
        var probes = 0
        val f = DesktopOutputLineFactory({ probes++; true }, { FakeOutputLine() }, { FakeOutputLine() })
        repeat(3) { f.open(fmt) }
        assertEquals(1, probes)
    }

    @Test
    fun `a WASAPI format rejection is not retried on Java Sound`() {
        var javaSoundOpens = 0
        val f = DesktopOutputLineFactory(
            { true },
            { throw OutputLineException.FormatRejected(it, "nope") },
            { javaSoundOpens++; FakeOutputLine() },
        )
        assertFailsWith<OutputLineException.FormatRejected> { f.open(fmt) }
        assertEquals(0, javaSoundOpens)
    }
}
