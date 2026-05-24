// BitPerfectCapabilityProbe — Phase 2b Stream B-B0 (capability-probe spike).
// Determines whether the active USB audio device supports
// AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT via
// AudioManager.getSupportedMixerAttributes. Result drives the hard gate for all
// subsequent Stream B production code per plan
// docs/superpowers/plans/2026-05-23-phase-2b-plan.md §7. The empirical
// result-doc commit at docs/decisions/<date>-phase-2b-bitperfect-probe-result.md
// is Clay-filled-in after running BitPerfectProbeActivity on the real device
// (B0-T4 / B0-T5 — human tasks).
//
// API surface: BitPerfectAvailability enum (4 cases), BitPerfectProbeResult
// data class, and BitPerfectCapabilityProbe with a single fun probe() entry
// point. Pure read-only; constructs no AudioRecord / AudioTrack, requests no
// runtime permissions, holds no system resources.

package com.clayworks.kiln.audio.playback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import androidx.annotation.RequiresApi

enum class BitPerfectAvailability {
    /** Probe found a USB output device + at least one BIT_PERFECT-eligible format. */
    Available,

    /** USB output device present but vendor HAL did not expose any BIT_PERFECT format. */
    UnsupportedDevice,

    /** No USB output device attached at probe time. */
    NoUsbDac,

    /** Android version below API 34 (MIXER_BEHAVIOR_BIT_PERFECT introduced in API 34). */
    UnsupportedApi,
}

data class BitPerfectProbeResult(
    val availability: BitPerfectAvailability,
    val supportedFormats: List<AudioFormat>,
    val deviceProductName: String?,
    val androidApi: Int,
)

class BitPerfectCapabilityProbe(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun probe(): BitPerfectProbeResult {
        val api = Build.VERSION.SDK_INT
        if (api < Build.VERSION_CODES.UPSIDE_DOWN_CAKE /* 34 */) {
            return BitPerfectProbeResult(
                availability = BitPerfectAvailability.UnsupportedApi,
                supportedFormats = emptyList(),
                deviceProductName = null,
                androidApi = api,
            )
        }
        return probeApi34Plus(api)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun probeApi34Plus(api: Int): BitPerfectProbeResult {
        val usbDevice: AudioDeviceInfo? = audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
            }

        if (usbDevice == null) {
            return BitPerfectProbeResult(
                availability = BitPerfectAvailability.NoUsbDac,
                supportedFormats = emptyList(),
                deviceProductName = null,
                androidApi = api,
            )
        }

        val supported = audioManager.getSupportedMixerAttributes(usbDevice)
        val bitPerfectFormats = supported
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            .map { it.format }

        return BitPerfectProbeResult(
            availability = if (bitPerfectFormats.isEmpty())
                BitPerfectAvailability.UnsupportedDevice
            else
                BitPerfectAvailability.Available,
            supportedFormats = bitPerfectFormats,
            deviceProductName = usbDevice.productName?.toString(),
            androidApi = api,
        )
    }
}
