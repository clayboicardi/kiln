# Phase 2b Native Audio Research — AAudio + WASAPI

**Date:** 2026-05-23 (Session 20)
**Author:** ClaydeClaw (CC) on Clay Haworth's behalf
**Wrapper:** `/multi:research`
**Status:** Research complete. **Caveat: single-provider source (codex-heavy); claude synthesized but did not independently web-research today; gemini quota-burned. Re-fan recommended before locking ADR via `/multi:decide`.**
**Spec ref:** [`../superpowers/specs/2026-05-18-kiln-rebuild-design.md`](../superpowers/specs/2026-05-18-kiln-rebuild-design.md) §13
**Vetting ref:** [`./2026-05-18-library-vetting.md`](./2026-05-18-library-vetting.md) Item 13
**Plan ref:** [`../superpowers/plans/2026-05-18-kiln-execution-plan.md`](../superpowers/plans/2026-05-18-kiln-execution-plan.md) §5 (Phase 2b)

This document records the Phase 2b kickoff research findings on current-state (2026-05) AAudio (Android 14+) and WASAPI (Windows 11 24H2) low-latency native audio APIs, intended to inform the upcoming `/multi:decide` on Stream A/B sequencing. Append-only per the [Append-only Decision Log](../reference/2026-05-18-named-patterns-glossary.md#append-only-decision-log) pattern.

---

## Method

- `/multi:research` fan to claude + codex (gemini quota-burned, retried 2× with timeouts on first attempt; codex+claude succeeded on retry with `-t 540s`)
- Prompt: `/tmp/phase-2b-research-prompt.txt` (2733 bytes, 12+ specific questions across Android/Windows/KMP)
- Synthesis: `/tmp/multi-ask-UXAwhW/synthesis.txt` (claude-opus-4-7)
- Codex used Firecrawl deep-research workflow → live developer.android.com + learn.microsoft.com + mvnrepository.com + GitHub release-page lookups on 2026-05-23
- Run ID: `20260523T235111Z-2448578`, fan duration 290s

**Single-provider warning:** codex did substantive web-grounded research. Claude's "primary" response on this run was actually a meta-summary of codex (claude's standalone web access failed). Triangulation gap acknowledged.

---

## Source Freshness Snapshot (verified 2026-05-23)

| Area | State |
|---|---|
| Android API table | Android 14/15/16 = API 34/35/36 ([developer.android.com/guide/appendix/api-levels](https://developer.android.com/guide/appendix/api-levels)) |
| AAudio NDK reference | Updated **2026-01-16** ([developer.android.com/ndk/reference/group/audio](https://developer.android.com/ndk/reference/group/audio)) |
| AAudio guide | Updated **2026-03-06** ([developer.android.com/ndk/guides/audio](https://developer.android.com/ndk/guides/audio)) |
| Oboe | `com.google.oboe:oboe` **v1.10.0** released **2025-09-15** ([mvnrepository](https://mvnrepository.com/artifact/com.google.oboe/oboe/versions)) |
| Oboe low-latency guide | Updated 2026 ([developer.android.google.cn/games/sdk/oboe/low-latency-audio](https://developer.android.google.cn/games/sdk/oboe/low-latency-audio?hl=en)) |
| WASAPI low-latency docs | `IAudioClient3` path still canonical — no 2026 deprecations found ([learn.microsoft.com/.../low-latency-audio](https://learn.microsoft.com/en-us/windows-hardware/drivers/audio/low-latency-audio)) |
| NAudio | v2.3.0 (2026-03-12) — **.NET only, not JVM** ([github.com/naudio/naudio](https://github.com/naudio/naudio)) |
| JNA | Generic native FFI only; Kiln pins 5.17.0 |

---

## ANDROID / AAUDIO

| Q | Finding |
|---|---|
| **1. Sub-20ms output path** | **Oboe 1.10.0 → AAudio**, request `LowLatency` + `Exclusive` performance mode, callback I/O, 2-burst buffer tuning. Apps don't "choose MMAP" directly — exclusive AAudio can map to MMAP/NOIRQ if the HAL supports it. |
| **2. AAudio updates since 2026-01** | No deprecations affecting Kiln. API 36 additions are battery/offload-focused (compressed streams, `POWER_SAVING_OFFLOADED`, `getDeviceIds`, offload delay/padding, playback params, `flushFromFrame`). `setSamplesPerFrame` deprecated → use `setChannelCount`. |
| **3. Oboe state** | Still maintained, still officially recommended over direct AAudio. Wraps AAudio on API 27+, carries device/version workarounds. Direct AAudio is fallback only if Oboe blocks a needed API. |
| **4. Kotlin/JVM wrappers** | **None official-grade.** Oboe FAQ: Java/Kotlin audio data requires JNI; questions whether latency gain justifies the complexity. **Klarinet 0.1.0** exists (delegates to Oboe/miniaudio) but too immature for Kiln's core engine. |
| **5. Pixel 10 Pro XL latency floor** | **Empirically unknown.** No published OboeTester/Superpowered measurement for Pixel 10 Pro XL + USB-C-to-AUX dongle. Android 16 CDD: handhelds must hit 80 ms RTL on ≥1 path; `FEATURE_AUDIO_LOW_LATENCY`=50 ms; `FEATURE_AUDIO_PRO`=20-25 ms. **Must measure on Clay's exact dongle before promising any number.** |
| **6. Gotchas** | **Bluetooth (LDAC/aptX):** do NOT target sub-20 ms — Oboe FAQ lists BT as a class that doesn't support low-latency streams. **USB DAC:** AAudio doesn't enumerate/route/decode — use `AudioManager.getDevices()` for routing. **Audio focus:** keep `AudioFocus`, noisy-route handling, MediaSession OUTSIDE the native engine. **Threading:** high-priority data callback only; normal app threads get preempted. |

### NEW PATH SURFACED: `MIXER_BEHAVIOR_BIT_PERFECT` (Android 14+)

Codex identified a **second, distinct audiophile path** that the kickoff prompt did not name:

- `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT` ([source.android.com/docs/core/audio/preferred-mixer-attr](https://source.android.com/docs/core/audio/preferred-mixer-attr))
- Android 14+ official **bit-perfect USB-DAC path** — bypasses volume scaling, SRC, effects, DSP mixing when device + vendor support it
- Vendor-optional + USB-only
- **NOT a low-latency play** — latency unbounded by mixer policy
- **Distinct goal from Oboe-LowLatency**

**Decision-relevance:** Clay's audiophile FLAC playback on USB-C-to-AUX dongle may align more closely with bit-perfect than with low-latency. The kickoff prompt's framing of Stream B as "low-latency engine swap" may be incomplete — bit-perfect is a separate axis. This is the single most consequential finding for the upcoming `/multi:decide` step.

---

## WINDOWS / WASAPI

| Q | Finding |
|---|---|
| **1. Sub-10ms output path** | **WASAPI event-driven render**: shared mode + `IAudioClient3` low-period default; **exclusive mode for bit-perfect / user-selected**. ASIO is vendor-driver path (defer). WaveRT is driver-side, not app API. |
| **2. WASAPI updates since 2026-01** | **No new COM interfaces or deprecations found.** `IAudioClient3` + `GetSharedModeEnginePeriod` + `GetCurrentSharedModeEnginePeriod` + `InitializeSharedAudioStream` + `AUDCLNT_STREAMOPTIONS_MATCH_FORMAT` remain canonical. Real-Time Work Queue + `Audio`/`ProAudio` thread tagging still recommended. |
| **3. Kotlin/JVM wrappers** | **No mature JVM WASAPI binding identified.** JNA is generic only. **naudio-jna does not exist** (NAudio is .NET-only, v2.3.0 2026-03-12). JNAerator is dormant. Rolling a narrow JNA/COM control layer + a tiny C++ DLL for the realtime render thread is the realistic path. |
| **4. `AudioStreamCategory`** | Use **`AudioCategory_Media`** for music playback (MS-documented default). RAW signal processing only when intentionally bypassing OEM processing AND device supports it. |
| **5. Shared vs exclusive for bit-perfect** | **Exclusive still required** for arbitrary bit-perfect per-track sample-rate / bit-depth / non-PCM. Shared can request `AUDCLNT_STREAMOPTIONS_MATCH_FORMAT` + RAW but remains engine/mixer path. |
| **6. Latency floor (USB DAC)** | MS docs: engine periods 1/2/3/5/10 ms driver-dependent. Shared default 10 ms unless driver advertises smaller periods. Sub-10 ms generally requires pro-class hardware/driver. **CPU/GPU secondary to USB driver/endpoint buffering.** Output-only dongle has no true RTL without loopback capture. |
| **7. Format gotchas** | `IsFormatSupported` + `WAVEFORMATEXTENSIBLE` for 24/96, 24/192, float. Per-track 44.1/48/96/192 negotiation. **24-bit-packed vs 32-bit-container** handling. **DSD-over-PCM:** app/device convention — defer unless Clay wants DSD passthrough explicitly [staleness risk on DSD specifics]. |

---

## KMP-Specific

**Kotlin/Native: viable for shared DSP / small C ABI, NOT the pragmatic primary engine for Kiln Phase 2b.** Adding a third runtime + packaging complexity before proving the engines is wrong sequence.

**Recommended shape:**

1. Android: `AAudioPlayerImpl` using **Oboe C++ + JNI** (or `BitPerfectAudioTrackPlayerImpl` using AudioTrack + AudioMixerAttributes, if Stream A reframes around bit-perfect)
2. Windows: `WasapiPlayerImpl` using **narrow JNA/COM control layer + tiny C++ DLL** for real-time render thread. Microsoft `WASAPIAudio` sample = starting template.
3. `PlatformPlayer` interface unchanged — already shaped for this swap (vetting Item 13 paid off).
4. **Never call Kotlin/JVM from the real-time callback per audio burst.** Lock-free / ring-buffer between native callback and JVM producer; decode + DSP on producer thread.

### Measurement tooling

| Platform | Tool |
|---|---|
| Android | **OboeTester** (Oboe repo recommends), AOSP audio_loopback dongle-style test, AAudio `getXRunCount`, timestamps, CTS Verifier loopback |
| Windows | Physical loopback + WASAPI timestamp/padding telemetry + underrun/glitch counters; compare shared-low-period vs exclusive. **WASAPIAudio** sample = `IAudioClient3` starting point. ETW/WPA for glitches [staleness risk on tooling specifics]. |

---

## Maturity ranking for Kiln Phase 2b

1. **Oboe 1.10.0 over AAudio** (Android low-latency) — highest maturity, official, current, best stack fit
2. **`MIXER_BEHAVIOR_BIT_PERFECT` AudioTrack path** (Android bit-perfect) — Android 14+ official; vendor-optional; **net-new path surfaced by this research**
3. **WASAPI exclusive event-driven** (Windows bit-perfect) — platform-native, mature
4. **WASAPI shared + `IAudioClient3`** (Windows coexistence-friendly low-latency) — same maturity, different goal
5. **Direct AAudio** — acceptable only if Oboe blocks a needed API
6. **ASIO** — defer; useful for pro-interface users, bad default for general music player
7. **Community KMP wrappers** (Klarinet, etc.) — too immature for Kiln's engine core
8. **NAudio / JNAerator / naudio-jna** — disqualified (.NET-only, dormant, or nonexistent)

---

## Latency Floor Estimates

| Path | Estimate | Confidence | Basis |
|---|---|---|---|
| Pixel 10 Pro XL + USB-C-to-AUX dongle, Oboe `LowLatency`+`Exclusive`, native PCM | **20-30 ms RTL** (output ~10-15 ms) | **Low** — no Pixel 10-specific OboeTester data; must measure | Oboe checklist baseline 20 ms; `FEATURE_AUDIO_PRO` target 20-25 ms; USB analog dongle adds path |
| Pixel 10 Pro XL + USB-DAC + Android 14 `MIXER_BEHAVIOR_BIT_PERFECT` | **Latency-agnostic** (bit-perfect goal, not RTL goal) | **Medium** | Separate goal from low-latency Oboe path |
| Windows 11 24H2 + USB DAC + WASAPI exclusive event-driven, pro-class driver | **3-10 ms** output (RTL N/A — output-only dongle) | **Medium** | `IAudioClient3` / exclusive periods 1-10 ms documented; depends on USB driver buffering |
| Windows 11 24H2 + USB DAC + WASAPI shared `IAudioClient3` low-period | **5-15 ms** | **Medium** | MS docs; driver-dependent |

**Loopback measurement required before locking any Phase 2b spec target.**

---

## Disagreements (between providers)

⚠ **Windows engine latency floor figure:** claude cited "1.3 ms engine latency"; codex did not corroborate (cites default 10 ms, driver-exposed 5/3/2/1 ms range). Trust codex's range; treat 1.3 ms as unverified.

⚠ **Android bit-perfect path surfacing:** claude did not surface `MIXER_BEHAVIOR_BIT_PERFECT`; codex identified it as the official audiophile USB path. **Codex's finding is decision-relevant** and re-triangulation with claude (web-grounded) + gemini before ADR-lock is recommended specifically to validate this.

---

## Implicit scope expansion: Android FLAC decode rewrite

Current Android playback uses Media3 ExoPlayer's bundled FLAC decoder. **Oboe is sink-only — does not bundle a decoder.** Phase 2b Android needs one of:

- (a) `MediaExtractor` + `MediaCodec` PCM feed (Android-native path)
- (b) Port the desktop JNA libFLAC bridge to Android (consistent decoder both platforms)

This was **implicit in the 80-120 hr Flight H estimate** in the execution plan but should be **called out explicitly** in the Phase 2b plan doc.

---

## Decision-relevant findings for `/multi:decide` (sequencing step)

The single most consequential **new** input vs the Session 19/20 kickoff framing:

1. **Android has TWO distinct audiophile paths**, not one:
   - **Oboe LowLatency+Exclusive** → ~20 ms RTL, native callback engine (latency goal)
   - **`MIXER_BEHAVIOR_BIT_PERFECT`** (Android 14+) → bit-perfect USB-DAC (bit-perfect goal)
   
   For Clay's audiophile FLAC playback on USB-DAC, **bit-perfect is arguably more aligned than low-latency**. Stream A's current framing as "low-latency engine swap" may be the wrong frame; "bit-perfect engine swap" + "low-latency as bonus" could re-order priorities.

2. **Android FLAC decode rewrite is implicit scope** regardless of which Stream-A sub-path is chosen.

3. **Windows native engine has no off-the-shelf JVM library** — rolling a C++ DLL + JNA control surface is unavoidable. Effort estimate (80-120 hr per current plan) appears realistic.

4. **Single-provider source** → recommend re-fan claude+gemini before locking ADR, specifically to triangulate the bit-perfect-vs-low-latency framing.

### Recommended additional sequencing option for `/multi:decide`

Existing kickoff options (a/b/c): A-only / A+B bundled / B-only.

**Recommended addition (a′):** Android-first **BIT-PERFECT** (`MIXER_BEHAVIOR_BIT_PERFECT`) before low-latency Oboe. Lower-risk than Oboe-JNI, vendor-supported AudioTrack-shaped path, arguably closer to Clay's audiophile use case. Oboe-low-latency follows only if measurement justifies it.

---

## Caveats before locking the Phase 2b ADR

- **Single-provider source.** Codex-driven; claude summarized codex; gemini quota-burned. Re-fan claude+gemini before `/multi:decide` lock to triangulate the bit-perfect path finding.
- **Pixel 10 Pro XL latency is empirically unknown** — must measure on Clay's exact USB-C-to-AUX dongle before promising any RTL target in the Phase 2b kickoff spec.
- **Android FLAC decode rewrite is implicit scope** — kickoff prompt at `docs/sessions/2026-05-23-session-20-kickoff-prompt-phase-2b.md` should call it out explicitly.
- **DSD-over-PCM specifics** flagged `[staleness risk]` — not load-bearing for Kiln's stated scope.

---

## Status

**DECIDED (research only).** Findings recorded. Implementation decisions (Stream A/B sequencing, bit-perfect-vs-low-latency framing, library candidate locking, latency budget) deferred to upcoming `/multi:decide` step, which should ingest this document as input.

---

## G2 Addendum: Gemini cross-check on Pixel 10 BIT_PERFECT vendor support (2026-05-24)

> **Method:** `/multi:research` re-fan targeting only the load-bearing finding from the original 2026-05-23 codex-heavy research — the `MIXER_BEHAVIOR_BIT_PERFECT` vendor-support landscape. Fan attempted claude+gemini+codex with `-t 360`; resulted in 1/3 panel (gemini only — claude + codex both timed out at 360s). Min-panel-size check refused synthesis (exit 6); raw gemini response read directly. Run ID `20260524T043141Z-2528900`.

### Why this still has signal despite single-provider degradation

The original 2026-05-23 research had codex-only-with-web-grounding for the BIT_PERFECT finding. Gemini was specifically the missing voice for cross-validation. This 1/3-panel result is gemini-only-with-web-grounding — which means the missing-voice gap is now filled, just not triangulated. The findings below should be treated as gemini's independent claim, NOT as cross-LLM consensus. Confidence: medium (was: low for codex-only; net rise).

### Confirmed (gemini)

1. **`AudioManager.getSupportedMixerAttributes(AudioDeviceInfo)` is the canonical capability-probe API.** No post-2026-01 AOSP semantic changes. Confirms the B0 probe shape.

2. **Pixel 10 Pro XL (Android 16) ships with `AUDIO_OUTPUT_FLAG_BIT_PERFECT` HAL flag ENABLED on Tensor G5.** Per gemini's sourcing this is "not vendor-optional/off" — confirms that the (a)-only dissent risk (Pixel 10 might lack vendor support) is **likely resolved in the user's favor**. The capability-probe spike (B0) should return PROBE_PASS for at least one format.

3. **UAC2 is sufficient; UAC3 not required.** Confirms basic USB-C-to-AUX dongles work IFF their reported native format matches the requested format. Clay's dongle likely caps at 48/16 → bit-perfect for 96/24+ FLAC will fail OR fall back. The probe will reveal this.

4. **What BIT_PERFECT bypasses:** volume scaling, SRC (resampling), DSP mixing, system effects, **AND format conversion + channel routing** (no mono-to-stereo, no downmix, no bit-depth padding). `AudioTrack` config must EXACTLY match `AudioMixerAttributes`. Mismatch → framework SILENTLY DROPS to default mixer behavior. Confirms F5 — probe necessary, not sufficient; null-test rig (Item 15) is essential.

### NEW failure modes surfaced (NOT in original research; integrate into Stream B-B3 plan)

These are decision-relevant for Phase 2b Stream B-B3 (`BitPerfectAudioTrackPlayerImpl`) and Stream B-B2 (null-test acceptance rig):

| ID | Failure mode | Source (per gemini) | Stream B mitigation |
|---|---|---|---|
| **F24** | **90-Second Disconnect Bug** on Pixel hardware — DAC abruptly disconnects 30-90s into bit-perfect playback. Workaround: "Disable USB audio routing" in Dev Options. | XDA Forums | B3 must add: (a) detection via underrun spike / DAC-disconnect listener; (b) auto-fallback to non-bit-perfect path on detection; (c) user-visible warning + Dev Options pointer. **Empirically test on Pixel 10 Pro XL during B0-T4 — extend the probe activity to play test PCM for >2 min and watch for disconnect.** |
| **F25** | **"Silent" 88.2kHz Bug** — non-48kHz-multiple rates yield total silence due to AAudio backend init failures. | GitHub Oboe issues | B3 must add: per-rate output-validation on first frame (read back via `AudioRecord` or null-test rig); if silence detected, fall back. Or **conservatively exclude 88.2/176.4 kHz from bit-perfect path** for first ship; document as known limitation. |
| **F26** | **Android 14 QPR3 silent-failure regression** — falsely reported bit-perfect success but output at wrong sample rate (pitch/speed defect). | XDA | **Reinforces F1 mitigation** — null-test rig (Item 15) is non-negotiable; this is exactly the silent-failure mode the rig catches. B3 should also log + telemetry-track any `AudioTrack.write` rate vs initialized rate mismatch as a defensive runtime check. |
| **F27** | **Hot-plug requires `OnPreferredMixerAttributesChangedListener`** — disconnect invalidates the stream; app must catch + manually re-apply attributes upon reconnection. | Android Docs | B4 (`BitPerfectDeviceRouter`) already in scope per plan §6.B4. Adds specifically: register the listener, handle re-apply path explicitly. Maps to F8 in falsify integration. |
| **F28** | **Bluetooth: 100% unsupported.** A2DP enforces codec re-encoding (SBC/LDAC/aptX); violates the API contract. | Head-Fi | UI must hard-disable the bit-perfect toggle when active output is Bluetooth. Maps to existing F-list gotcha but now explicitly verified. |

### Decision-relevance

**Phase 2b Option (a-prime) lock is NOT changed.** Gemini's findings CONFIRM the locked path is viable on Pixel 10 Pro XL. The new failure modes (F24-F28) add concrete Stream B work but don't invalidate the sequencing decision.

**Hard updates to the existing plan:**

1. **B0-T4 (Clay's device-side probe)** should be EXTENDED — beyond `getSupportedMixerAttributes` query, also play test PCM for >2 minutes while watching for the F24 90-second disconnect bug. The current BitPerfectProbeActivity is read-only; consider a "B0-T4-extended" debug-build addition that exercises an actual playback session.

2. **Stream B-B3 plan** must include explicit handling for F24-F28. Effort estimate stays within the existing 40-70hr range (these are tactical additions, not architectural changes).

3. **Stream B-B2 null-test rig design** must specifically exercise the F25 (88.2kHz silent failure) and F26 (QPR3 silent-pitch-regression) cases. The rig acceptance criteria expands from "0 bit errors" to "0 bit errors AND output is not silent AND output is at requested sample rate."

### Caveats remaining

- **1/3 panel.** Gemini-only. No triangulation with claude or codex on these specific failure modes. Some F24-F28 details may be over-fitted to gemini's specific source set (e.g., XDA forum reports may be selection-biased). Re-fan with claude+codex when their quotas/timeouts permit.
- **Source freshness:** gemini cited "early 2026 reports" for the Pixel 10 stuttering observation — flag [staleness risk] for that specific claim. Other claims are well-sourced from primary documentation (Android Developer Docs, AOSP).

### Status

**ADDENDUM ADDED.** Falsify-integration engram updated with new failure modes F24-F28. Phase 2b plan §4 risk register should be amended to include these (a follow-up plan-doc edit; can land before Stream B starts).

---

End of research.
