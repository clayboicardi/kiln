// WasapiClient: the COM RenderPort (#37). Opens the DEFAULT multimedia render endpoint on every
// open (so a replugged/changed default device is picked up at the next track), initializes a
// shared-mode stream with AUTOCONVERTPCM, and exposes the render client + session volume. Every
// call happens on the opening thread (COM initialized there by ComInit). close() releases in
// reverse acquisition order and is idempotent.

package com.clayworks.kiln.audio.playback.nativeio.wasapi

import com.clayworks.kiln.audio.dsp.DecodedAudioFormat
import com.clayworks.kiln.audio.playback.output.MixFormat
import com.clayworks.kiln.audio.playback.output.RenderPort
import com.sun.jna.Memory
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WTypes
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

internal class WasapiClient private constructor(
    private val acquired: List<ComPtr>, // enumerator, device, audioClient, renderClient, volume
    private val blockAlign: Int,
    override val bufferFrames: Int,
    override val clientRateHz: Int,
    override val mix: MixFormat,
) : RenderPort {

    private val audioClient = acquired[2]
    private val renderClient = acquired[3]
    private val volume = acquired[4]
    private val padding = IntByReference()
    private val data = PointerByReference()
    private var closed = false

    override fun paddingFrames(): Int {
        HResults.check(audioClient.call(Vt.AC_GET_CURRENT_PADDING, padding), "GetCurrentPadding")
        return padding.value
    }

    override fun writeFrames(src: ByteArray, off: Int, frames: Int) {
        HResults.check(renderClient.call(Vt.RC_GET_BUFFER, frames, data), "GetBuffer")
        data.value.write(0, src, off, frames * blockAlign)
        HResults.check(renderClient.call(Vt.RC_RELEASE_BUFFER, frames, 0), "ReleaseBuffer")
    }

    override fun start() = HResults.check(audioClient.call(Vt.AC_START), "Start")
    override fun stop() = HResults.check(audioClient.call(Vt.AC_STOP), "Stop")
    override fun reset() = HResults.check(audioClient.call(Vt.AC_RESET), "Reset")

    override fun setVolume(level: Float) =
        HResults.check(volume.call(Vt.SAV_SET_MASTER_VOLUME, level, null), "SetMasterVolume")

    override fun close() {
        if (closed) return
        closed = true
        runCatching { audioClient.call(Vt.AC_STOP) }
        acquired.asReversed().forEach { runCatching { it.Release() } }
    }

    companion object {
        /** True if COM + the MMDevice enumerator work on this thread. Endpoint presence isn't checked. */
        fun isAvailable(): Boolean = try {
            ComInit.ensure()
            val ppv = PointerByReference()
            val hr = Ole32.INSTANCE.CoCreateInstance(
                WasapiIds.CLSID_MMDeviceEnumerator, null, WTypes.CLSCTX_ALL, WasapiIds.IID_IMMDeviceEnumerator, ppv,
            ).toInt()
            if (hr >= 0) ComPtr(ppv.value).Release()
            hr >= 0
        } catch (t: Throwable) {
            false // not Windows (UnsatisfiedLinkError), COM unusable, …
        }

        fun open(format: DecodedAudioFormat, wfx: ByteArray, bufferMs: Int): WasapiClient {
            ComInit.ensure()
            val acquired = ArrayList<ComPtr>(5)
            try {
                val ppv = PointerByReference()
                HResults.check(
                    Ole32.INSTANCE.CoCreateInstance(
                        WasapiIds.CLSID_MMDeviceEnumerator, null, WTypes.CLSCTX_ALL, WasapiIds.IID_IMMDeviceEnumerator, ppv,
                    ).toInt(),
                    "CoCreateInstance(MMDeviceEnumerator)",
                )
                val enumerator = ComPtr(ppv.value).also(acquired::add)
                HResults.check(
                    enumerator.call(Vt.ENUM_GET_DEFAULT_AUDIO_ENDPOINT, WasapiConst.E_RENDER, WasapiConst.E_MULTIMEDIA, ppv),
                    "GetDefaultAudioEndpoint",
                )
                val device = ComPtr(ppv.value).also(acquired::add)
                HResults.check(device.call(Vt.DEVICE_ACTIVATE, WasapiIds.IID_IAudioClient, WTypes.CLSCTX_ALL, null, ppv), "Activate")
                val audioClient = ComPtr(ppv.value).also(acquired::add)

                val wfxMem = Memory(wfx.size.toLong()).apply { write(0, wfx, 0, wfx.size) }
                HResults.check(
                    audioClient.call(
                        Vt.AC_INITIALIZE, WasapiConst.AUDCLNT_SHAREMODE_SHARED, WasapiConst.SHARED_STREAM_FLAGS,
                        bufferMs * 10_000L, 0L, wfxMem, null,
                    ),
                    "Initialize",
                    format,
                )
                val frames = IntByReference()
                HResults.check(audioClient.call(Vt.AC_GET_BUFFER_SIZE, frames), "GetBufferSize")
                HResults.check(audioClient.call(Vt.AC_GET_SERVICE, WasapiIds.IID_IAudioRenderClient, ppv), "GetService(render)")
                ComPtr(ppv.value).also(acquired::add)
                HResults.check(audioClient.call(Vt.AC_GET_SERVICE, WasapiIds.IID_ISimpleAudioVolume, ppv), "GetService(volume)")
                ComPtr(ppv.value).also(acquired::add)

                HResults.check(audioClient.call(Vt.AC_GET_MIX_FORMAT, ppv), "GetMixFormat")
                val mixPtr = ppv.value
                val mix = try {
                    val cbSize = mixPtr.getShort(16).toInt() and 0xFFFF
                    WaveFormatExtensible.decodeMix(mixPtr.getByteArray(0, 18 + cbSize))
                } finally {
                    Ole32.INSTANCE.CoTaskMemFree(mixPtr)
                }

                val blockAlign = (wfx[12].toInt() and 0xFF) or ((wfx[13].toInt() and 0xFF) shl 8)
                return WasapiClient(acquired, blockAlign, frames.value, format.sampleRateHz, mix)
            } catch (e: Throwable) {
                acquired.asReversed().forEach { runCatching { it.Release() } }
                throw e
            }
        }
    }
}
