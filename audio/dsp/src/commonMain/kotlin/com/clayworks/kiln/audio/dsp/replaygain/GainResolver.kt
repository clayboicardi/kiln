package com.clayworks.kiln.audio.dsp.replaygain

import kotlin.math.pow

/**
 * Mirror of [com.clayworks.kiln.library.settings.ReplayGainMode] kept in
 * `:audio:dsp` to avoid a back-dep on `:data:library`. JavaSoundPlayerImpl
 * (and any other PlatformPlayer impl) translates one to the other at the
 * call site — the two enums are isomorphic.
 */
enum class ReplayGainPipelineMode { Off, Track, Album }

/**
 * Compute the linear gain multiplier to apply to PCM samples for ReplayGain
 * v2 consumer-side gain.
 *
 * Inputs are the per-track and per-album fields from the `track` table (any
 * of which may be null on tracks that haven't been analyzed yet) plus the
 * user's settings (mode + pre-amp).
 *
 * Returns 1.0 (no gain change) when:
 *  - [mode] is [ReplayGainPipelineMode.Off]
 *  - The mode-specific gain field is null with no fallback available
 *
 * Otherwise:
 *   effectiveDb = (mode == Album ? albumDb ?? trackDb : trackDb) ?? 0.0
 *   totalDb     = effectiveDb + preAmpDb
 *   linearGain  = 10^(totalDb / 20)
 *
 * Peak-limit guard: if [linearGain] * (matching peak) > 1.0, the gain is
 * capped at 1.0 / peak so the loudest sample stays within the 0 dBFS
 * envelope. Null peaks default to 1.0 conservatively (= "treat as full
 * scale; no limit needed").
 */
fun resolveGainLinear(
    trackDb: Double?,
    trackPeak: Double?,
    albumDb: Double?,
    albumPeak: Double?,
    mode: ReplayGainPipelineMode,
    preAmpDb: Double,
): Double {
    if (mode == ReplayGainPipelineMode.Off) return 1.0

    val (effectiveDb, effectivePeak) = when (mode) {
        ReplayGainPipelineMode.Off -> return 1.0  // unreachable; here for exhaustiveness
        ReplayGainPipelineMode.Track -> trackDb to trackPeak
        ReplayGainPipelineMode.Album -> (albumDb ?: trackDb) to (albumPeak ?: trackPeak)
    }

    if (effectiveDb == null) return 1.0  // no usable gain → no-op

    val totalDb = effectiveDb + preAmpDb
    val linearGain = 10.0.pow(totalDb / 20.0)
    val peak = effectivePeak ?: 1.0  // conservative default (no peak → assume full-scale)

    return if (linearGain * peak > 1.0) {
        1.0 / peak
    } else {
        linearGain
    }
}
