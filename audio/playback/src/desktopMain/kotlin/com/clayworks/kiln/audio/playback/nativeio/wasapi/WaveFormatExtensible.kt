// WAVEFORMATEXTENSIBLE encoder + mix-format decoder (#37). mmreg.h declares WAVEFORMATEX under
// #pragma pack(1), so the struct is built byte-by-byte (a JNA Structure would pad it).
// Layout: wFormatTag u16 @0, nChannels u16 @2, nSamplesPerSec u32 @4, nAvgBytesPerSec u32 @8,
// nBlockAlign u16 @12, wBitsPerSample u16 @14, cbSize u16 @16, wValidBitsPerSample u16 @18,
// dwChannelMask u32 @20, SubFormat GUID @24..40.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.playback.output.MixFormat

internal object WaveFormatExtensible {
    const val SIZE = 40
    private const val HEADER = 18
    private const val WAVE_FORMAT_IEEE_FLOAT = 0x0003
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    private const val SPEAKER_FRONT_LEFT = 0x1
    private const val SPEAKER_FRONT_RIGHT = 0x2
    private const val SPEAKER_FRONT_CENTER = 0x4

    /** KSDATAFORMAT_SUBTYPE_PCM {00000001-0000-0010-8000-00AA00389B71}, in GUID memory order. */
    val SUBTYPE_PCM: ByteArray = subtype(0x01)

    /** KSDATAFORMAT_SUBTYPE_IEEE_FLOAT {00000003-0000-0010-8000-00AA00389B71}. */
    val SUBTYPE_IEEE_FLOAT: ByteArray = subtype(0x03)

    fun encode(sampleRateHz: Int, channels: Int, containerBits: Int, validBits: Int): ByteArray {
        require(containerBits % 8 == 0 && validBits in 1..containerBits) { "bad bits $validBits/$containerBits" }
        val blockAlign = channels * containerBits / 8
        val b = ByteArray(SIZE)
        putU16(b, 0, WAVE_FORMAT_EXTENSIBLE)
        putU16(b, 2, channels)
        putU32(b, 4, sampleRateHz)
        putU32(b, 8, sampleRateHz * blockAlign)
        putU16(b, 12, blockAlign)
        putU16(b, 14, containerBits)
        putU16(b, 16, SIZE - HEADER)
        putU16(b, 18, validBits)
        putU32(b, 20, channelMask(channels))
        SUBTYPE_PCM.copyInto(b, 24)
        return b
    }

    fun channelMask(channels: Int): Int = when (channels) {
        1 -> SPEAKER_FRONT_CENTER
        2 -> SPEAKER_FRONT_LEFT or SPEAKER_FRONT_RIGHT
        else -> 0
    }

    /** [bytes] = the header plus cbSize bytes, as read from GetMixFormat's allocation. */
    fun decodeMix(bytes: ByteArray): MixFormat {
        val tag = getU16(bytes, 0)
        val isFloat = when (tag) {
            WAVE_FORMAT_IEEE_FLOAT -> true
            WAVE_FORMAT_EXTENSIBLE -> bytes.size >= SIZE && bytes.copyOfRange(24, SIZE).contentEquals(SUBTYPE_IEEE_FLOAT)
            else -> false
        }
        return MixFormat(sampleRateHz = getU32(bytes, 4), bitsPerSample = getU16(bytes, 14), isFloat = isFloat)
    }

    private fun subtype(data1: Int) = byteArrayOf(
        data1.toByte(), 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71,
    )

    private fun putU16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte() }
    private fun putU32(b: ByteArray, o: Int, v: Int) { putU16(b, o, v and 0xFFFF); putU16(b, o + 2, v ushr 16) }
    private fun getU16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun getU32(b: ByteArray, o: Int) = getU16(b, o) or (getU16(b, o + 2) shl 16)
}
