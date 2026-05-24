// BitPerfectProbeActivity — debug-only entry point for running
// BitPerfectCapabilityProbe on real Android hardware. Surfaces probe results
// visually so Clay can screenshot + fill in the empirical result-doc at
// docs/decisions/<date>-phase-2b-bitperfect-probe-result.md per plan
// docs/superpowers/plans/2026-05-23-phase-2b-plan.md §7 step B0-T4.
//
// CURRENT LOCATION: src/main/kotlin/... (because there's no src/debug source
// set in this module yet). Activity is declared in src/main/AndroidManifest.xml
// alongside MainActivity. Once a debug build variant + src/debug/ layout is
// added, both the activity file AND its manifest entry should move to
// src/debug/... so this surface is excluded from release APKs.

package com.clayworks.kiln.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.clayworks.kiln.audio.playback.BitPerfectCapabilityProbe
import com.clayworks.kiln.audio.playback.BitPerfectProbeResult

class BitPerfectProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val probe = BitPerfectCapabilityProbe(this)
        val resultOrError: Result<BitPerfectProbeResult> = runCatching { probe.probe() }
        setContent {
            MaterialTheme {
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(scrollState),
                ) {
                    Text(
                        text = "Bit-Perfect Capability Probe",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    resultOrError.fold(
                        onSuccess = { result ->
                            Text("Android API: ${result.androidApi}")
                            Text("Availability: ${result.availability}")
                            Text("Device: ${result.deviceProductName ?: "(none)"}")
                            Text("Supported formats: ${result.supportedFormats.size}")
                            result.supportedFormats.forEach { format ->
                                Text("  ${format.sampleRate} Hz / encoding=${format.encoding} / ${format.channelCount} ch")
                            }
                        },
                        onFailure = { e ->
                            Text(
                                "Probe threw: ${e::class.simpleName}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                e.message ?: "(no message)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                "Screenshot this for the result-doc.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                }
            }
        }
    }
}
