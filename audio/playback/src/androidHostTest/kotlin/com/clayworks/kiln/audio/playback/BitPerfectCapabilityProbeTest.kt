// BitPerfectCapabilityProbe coverage — Phase 2b Stream B-B0 (capability probe spike).
// Robolectric provides the default AudioManager stub which has no USB output
// device attached, so the probe should return NoUsbDac. Real-device verification
// for Available / UnsupportedDevice paths lives in BitPerfectProbeActivity per
// plan docs/superpowers/plans/2026-05-23-phase-2b-plan.md §7 step B0-T4.
//
// SDK pinned to 34 (UPSIDE_DOWN_CAKE) — the AudioMixerAttributes /
// MIXER_BEHAVIOR_BIT_PERFECT API was introduced at API 34. Without this pin,
// Robolectric may fall back to a different SDK and the probe would return
// UnsupportedApi, masking the NoUsbDac code path under test.

package com.clayworks.kiln.audio.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BitPerfectCapabilityProbeTest {

    @Test
    fun `probe returns NoUsbDac when no USB output device attached`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val probe = BitPerfectCapabilityProbe(ctx)
        val result = probe.probe()
        // Robolectric default has no USB device attached → NoUsbDac.
        assertEquals(BitPerfectAvailability.NoUsbDac, result.availability)
    }

    @Test
    fun `probe result includes Android API level (sanity check)`() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        val probe = BitPerfectCapabilityProbe(ctx)
        val result = probe.probe()
        // We pinned @Config(sdk = [34]), so Robolectric reports Build.VERSION.SDK_INT = 34.
        assertEquals(34, result.androidApi)
    }
}
