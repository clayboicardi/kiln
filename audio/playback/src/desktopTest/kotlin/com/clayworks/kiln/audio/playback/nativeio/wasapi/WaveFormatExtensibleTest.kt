// WAVEFORMATEXTENSIBLE is #pragma pack(1), 40 bytes, little-endian. Encoded by hand (not a JNA
// Structure) so the layout is exact. These tests pin every field.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.playback.output.MixFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WaveFormatExtensibleTest {

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    @Test
    fun `stereo 24-bit 96 kHz in a 24-bit container`() {
        val b = WaveFormatExtensible.encode(96_000, channels = 2, containerBits = 24, validBits = 24)
        assertEquals(40, b.size)
        assertEquals(0xFFFE, u16(b, 0))        // WAVE_FORMAT_EXTENSIBLE
        assertEquals(2, u16(b, 2))             // nChannels
        assertEquals(96_000, u32(b, 4))        // nSamplesPerSec
        assertEquals(576_000, u32(b, 8))       // nAvgBytesPerSec = 96000 * 6
        assertEquals(6, u16(b, 12))            // nBlockAlign
        assertEquals(24, u16(b, 14))           // wBitsPerSample (container)
        assertEquals(22, u16(b, 16))           // cbSize
        assertEquals(24, u16(b, 18))           // wValidBitsPerSample
        assertEquals(0x3, u32(b, 20))          // SPEAKER_FRONT_LEFT | SPEAKER_FRONT_RIGHT
        assertContentEquals(WaveFormatExtensible.SUBTYPE_PCM, b.copyOfRange(24, 40))
    }

    @Test
    fun `mono 16-bit 44_1 kHz uses the front-center mask`() {
        val b = WaveFormatExtensible.encode(44_100, channels = 1, containerBits = 16, validBits = 16)
        assertEquals(1, u16(b, 2))
        assertEquals(88_200, u32(b, 8))
        assertEquals(2, u16(b, 12))
        assertEquals(0x4, u32(b, 20))          // SPEAKER_FRONT_CENTER
    }

    @Test
    fun `24 valid bits in a 32-bit container`() {
        val b = WaveFormatExtensible.encode(192_000, channels = 2, containerBits = 32, validBits = 24)
        assertEquals(8, u16(b, 12))
        assertEquals(1_536_000, u32(b, 8))
        assertEquals(32, u16(b, 14))
        assertEquals(24, u16(b, 18))
    }

    @Test
    fun `the PCM subtype GUID is in Windows memory order`() {
        // {00000001-0000-0010-8000-00AA00389B71}: Data1..3 little-endian, Data4 as written.
        val expected = byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71)
        assertContentEquals(expected, WaveFormatExtensible.SUBTYPE_PCM)
    }

    @Test
    fun `decodeMix reads an extensible float mix`() {
        val b = WaveFormatExtensible.encode(48_000, 2, 32, 32)
        WaveFormatExtensible.SUBTYPE_IEEE_FLOAT.copyInto(b, 24)
        assertEquals(MixFormat(48_000, 32, isFloat = true), WaveFormatExtensible.decodeMix(b))
    }

    @Test
    fun `decodeMix reads a plain 18-byte float header`() {
        val b = ByteArray(18)
        b[0] = 3 // WAVE_FORMAT_IEEE_FLOAT
        b[4] = 0x80.toByte(); b[5] = 0xBB.toByte() // 48000
        b[14] = 32
        val m = WaveFormatExtensible.decodeMix(b)
        assertEquals(48_000, m.sampleRateHz)
        assertEquals(true, m.isFloat)
    }
}
