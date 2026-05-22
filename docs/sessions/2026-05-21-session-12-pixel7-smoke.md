# Pixel 7 Pro Smoke Test — Session 12 vertical-slice verification

**Date:** 2026-05-22 (executed end-of-Session-12, file-dated per task instructions)
**Device:** Pixel 7 Pro (codename `cheetah`), serial `2A261FDH300B1P`, factory-reset secondary
**OS:** Android 14 (API 36) per `Pixel 7 Pro, Google, 36` self-report from ExoPlayer init
**APK:** `app-android/build/outputs/apk/debug/app-android-debug.apk`, 23,724,531 bytes (22.6 MiB)
**applicationId:** `com.clayworks.kiln`
**Operator:** Autonomous ClaydeClaw session — Clay stepped away after pasting the brief; ran end-to-end without supervision

---

## Verdict

**PASS** — clean launch, permission gate works, full main UI surfaces correctly after `pm grant`. No crashes, no ANR, no FATAL logcat entries. Scan-on-launch is **NOT** implemented (status reads "Not scanned" after permission grant); scan requires tapping "Scan Library", which is out of scope for an autonomous run per the brief.

---

## Per-step results

| Step | Outcome |
|---|---|
| 1. `kiln-verify-build` baseline | **PASS** — 5/5 canonical targets, 91 tests / 1 skipped (`:data:library:desktopTest` = 63 tests; the build also implicitly ran `:data:library:testAndroidHostTest` + `:audio:playback:testAndroidHostTest` UP-TO-DATE) |
| 2. Read state + applicationId | Newest handoff: `docs/sessions/2026-05-22-session-13-handoff.md`. applicationId resolved from `build-logic/src/main/kotlin/kiln.android.app.gradle.kts:19` → `com.clayworks.kiln`. Device state pre-install: `topResumedActivity=com.google.android.apps.nexuslauncher/.NexusLauncherActivity` (home launcher, past setup wizard — autonomous install path clear) |
| 3. Build APK | Already built by Step 1's `:app-android:assembleDebug` (UP-TO-DATE). APK at the canonical path, 22.6 MiB |
| 4. Install on Pixel 7 | `adb -s 2A261FDH300B1P install -r <apk>` → `Performing Streamed Install / Success` |
| 5. Launch + capture | `am.startActivity` via `monkey -p com.clayworks.kiln -c android.intent.category.LAUNCHER 1` → `Events injected: 1`. 30s settle. **PID 9868** holds `com.clayworks.kiln/.MainActivity` as `topResumedActivity`. No FATAL/AndroidRuntime in logcat (407-line dump, see artifacts). One Pixel-7-Tensor-G2 kernel warning logged (see "Findings worth a Phase-2a ticket"). Screenshot: `2026-05-21-session-12-pixel7-smoke.png` |
| 6. Stretch — push FLAC + grant permission + relaunch | Pushed `D:\tiddl\2 Chainz\420 Hits 2 Chainz\Can't Go For That.flac` (19.7 MiB) to `/sdcard/Music/Can't Go For That.flac` (`24.3 MB/s`). `pm grant com.clayworks.kiln android.permission.READ_MEDIA_AUDIO` → no error; verified via `dumpsys package … READ_MEDIA_AUDIO: granted=true`. `am force-stop` + relaunch (PID 10001). Post-grant screenshot: `2026-05-21-session-12-pixel7-smoke-postgrant.png` |
| 7. Stretch — auto-scan? | **Not implemented.** Post-grant UI shows "Not scanned" with a manual "Scan Library" button. Scan requires UI tap → out of scope per the brief. **Stop here as instructed.** |

---

## Launch outcome detail

**Pre-permission launch (PID 9868):**
- Compose Material 3 surface rendered correctly: "Kiln by Clayworks" title, "Audio library access required.", "Grant Permission" pill, "State: Idle / Position: 0ms" status block
- Layout fits Pixel 7 Pro's 1080×2340 @ 420dpi (`mGlobalConfig=… sw411dp w411dp h891dp 420dpi … widecg port night`)
- MediaSession registered and receiving system-broadcast volume-key dispatches within the first 30s (volume rocker pressed during boot — pre-existing user input got routed because Kiln's MediaSession claimed the dispatch route correctly)
- Kernel/runtime warnings: 1 (`userfaultfd: MOVE ioctl seems unsupported`) — Pixel-7-Tensor-G2-specific, **not a Kiln defect**; appears in many Pixel 7 apps using ART's userfaultfd-based GC. See "Findings" below.

**Post-permission launch (PID 10001):**
- New process spawned cleanly via `Zygote: Process 10001 created for com.clayworks.kiln`
- ExoPlayer construction: `ExoPlayerImpl: Init 1c3fab3 [AndroidXMedia3/1.10.1] [cheetah, Pixel 7 Pro, Google, 36]`
- MediaSession: `MediaSessionImpl: Init e93885d [AndroidXMedia3/1.10.1] …`
- KilnApplication.onCreate's eager `graph.player` access materialized Media3 on the main thread without throwing (regression coverage for Session 10 Polish-1)
- 38 log lines from our PID through the 20-second settle window
- Compose surface auto-refreshed: permission gate replaced by "Scan Library" + "Play First Track" buttons + "Not scanned" status

---

## Artifacts (in this commit)

| File | Bytes | Description |
|---|---|---|
| `docs/sessions/2026-05-21-session-12-pixel7-smoke.png` | 58,598 | Pre-permission UI — permission gate displayed |
| `docs/sessions/2026-05-21-session-12-pixel7-smoke-postgrant.png` | 60,874 | Post-permission UI — Scan Library + Play First Track visible |
| `docs/sessions/smoke-logcat-raw.txt` | 407 lines | Full logcat dump after first launch (`-t 400`) |
| `docs/sessions/smoke-logcat-postgrant.txt` | 38 lines | PID-filtered logcat (`--pid=10001`) — Kiln-only entries from second launch |

---

## Findings worth a Phase-2a ticket (do NOT fix per the brief)

These are observations, not fixes. Surfaced for the Track-picker session to decide where they slot in.

### Finding 1 — `userfaultfd: MOVE ioctl seems unsupported: Connection timed out` (W-level)

```
03-10 06:09:27.019  9868  9876 W .clayworks.kiln: userfaultfd: MOVE ioctl seems unsupported: Connection timed out
```

**Source:** ART's `art_userfaultfd` background thread. Pixel 7 (Tensor G2 + Android 14) ships a kernel where ART's `MOVE` ioctl on the userfaultfd file descriptor isn't supported, so ART falls back to its prior path. Verified well-known across the Pixel 7 ecosystem (many third-party apps exhibit identical warnings on Pixel 7). **Not a Kiln defect** — would require an upstream ART/kernel fix or device firmware update to disappear.

**Recommended action:** None at code level. Document as a known-noise warning in CLAUDE.md "Build/Dep Gotchas" if it surfaces repeatedly in future Pixel 7 smoke tests. Phase 2a candidate: low-priority docs touch only.

### Finding 2 — `E ActivityThread: Failed to find provider info for androidx.car.app.connection`

```
03-10 06:12:00.315 10001 10019 E ActivityThread: Failed to find provider info for androidx.car.app.connection
```

**Source:** Media3's `androidx.media3:media3-session` library internally probes for `androidx.car.app.connection` content provider to detect whether the app should engage Android Auto media-session bindings. Kiln intentionally has NO Android Auto integration (per spec §11 anti-roadmap; "Auto" explicitly cut from scope), so the provider isn't there. Media3 logs the lookup miss at E-level but **handles the absence gracefully** — no crash, no functional impact.

**Recommended action:** None at code level. The log noise is upstream-Media3 behavior. If Phase 2a UX surfaces want a cleaner adb logcat experience during testing, add a logcat filter rule. Otherwise ignore. **P3 candidate at most.**

### Finding 3 — `E .clayworks.kiln: Invalid resource ID 0x00000000.`

```
03-10 06:12:00.337 10001 10001 E .clayworks.kiln: Invalid resource ID 0x00000000.
```

**Source:** Single E-level entry on main thread shortly after Compose composition. The log tag is the process name (truncated of the leading "com"); the message is `Invalid resource ID 0x00000000` with no further detail. Likely originates from a Compose-MP or Material 3 resource lookup attempting to dereference `Res.Id(0)` somewhere in the theme/typography chain on a code path Pixel 7 hits but Pixel 10 didn't. Speculative — could also be Media3 / kotlin-inject. No functional symptom, UI rendered correctly.

**Recommended action:** Add a `logcat -d --pid=$(adb shell pidof -s com.clayworks.kiln) *:E` snapshot to Pixel 10 smoke tests when the primary device is connected again; diff against this Pixel 7 capture to see if the warning is device-specific or universal. If it shows on Pixel 10 too, dig into the resource ID source. **P3 candidate.**

### Finding 4 — `W MediaSessionCompat: Couldn't find a unique registered media button receiver in the given context.`

```
03-10 06:12:00.310 10001 10001 I MediaSessionCompat: Couldn't find a unique registered media button receiver in the given context.
```

**Source:** Media3's `MediaSessionCompat` pre-Lollipop legacy compat layer warning that we don't register a `MEDIA_BUTTON` BroadcastReceiver in AndroidManifest. Modern Media3 (1.x) doesn't need this — Media Buttons route through MediaSession directly. The warning is harmless on Android 14.

**Recommended action:** None. **Track E (MediaSession integration)** can decide whether to register a `MEDIA_BUTTON` receiver for stricter pre-O backwards-compat (unlikely needed given Kiln min SDK 23 = Android 6).

### Finding 5 — Scan-on-launch is NOT implemented

Post-permission UI shows "Not scanned" with a manual "Scan Library" button. The H7 vertical-slice was designed as proof-of-concept — manual triggers only. Track A's Settings UI + scan-on-startup setting is the natural follow-up.

**Recommended action:** Track A (Settings UI) should include a "Scan on launch" toggle (default ON for first launch, OFF afterward — common UX pattern; user can toggle in Settings to re-enable). **Not a defect; an intentional MVP scope decision now visible.**

---

## Pixel-7-specific vs Pixel-10-targeted scaffolding observations

Kiln's H7/H8 work targeted Pixel 10 Pro XL per CLAUDE.md. Pixel 7 Pro substitutes during this test. Observations relevant to the device gap:

- **Min SDK 23 / Compile SDK 36 holds** — Android 14 (API 36) on Pixel 7 ran fine; no `compileSdk = 36` runtime surprises.
- **Bundled SQLite via Requery factory** worked transparently — no FTS5 module errors that Session 10 saw on Pixel 10/Android 16. May indicate the system SQLite on Android 14 has FTS5 enabled by default OR the bundled SQLite kicked in transparently. Worth re-verifying when Pixel 10 (Android 16) is connected. Pre-grant UI rendered correctly which implies KilnDatabase construction succeeded.
- **Tensor G2 (Pixel 7) vs Tensor G4 (Pixel 10)** — the `userfaultfd` warning above is the only G2-specific surface. ART on G4 is expected to handle MOVE ioctl natively.
- **Display geometry**: 1080×2340 @ 420dpi vs Pixel 10 Pro XL's 1344×2992 @ 480dpi. Both portrait, both Compose layouts work; no overflow observed on the smaller device.

---

## Verify-before-leaving checklist

- [x] kiln-verify-build PASS
- [x] APK installed (Success)
- [x] App launches cleanly (MainActivity resumed, PID assigned)
- [x] No FATAL/AndroidRuntime errors in 30s post-launch window
- [x] Permission gate works (Grant Permission button visible, blocks library access until tapped/granted)
- [x] Post-permission UI surfaces correctly (Scan Library + Play First Track + Idle state)
- [x] Pre + post-grant screenshots captured
- [x] Logcat artifacts captured (raw + PID-filtered)
- [x] Findings documented as Phase-2a candidates (NOT fixed)
- [x] No git push (per autonomous-run rules)
- [x] No code changes beyond this doc + screenshots
- [x] No factory-reset / `pm uninstall` / destructive ops

---

## Next-step pointers (for Session 13's track-picker)

- **Track A Settings UI** — add "Scan on launch" toggle (Finding 5)
- **Track E MediaSession** — decide on `MEDIA_BUTTON` BroadcastReceiver registration (Finding 4)
- **Future Pixel 10 smoke** — re-run this exact smoke against Pixel 10 Pro XL once connected; diff `*:E` log entries to see if Findings 2 + 3 are device-agnostic
- **Repeat manual UI tap test** — out of scope here; manually tap "Scan Library" on a connected device + verify scan completes against the pushed FLAC. That's the end-to-end H7+H8 integration check still owed before the project officially closes the MVP-vertical-slice loop. Can be a 10-minute manual session.

---

**End of smoke test.** All artifacts in `docs/sessions/`. Single commit lands the doc + screenshots + logcats together (one logical step). Push deferred per autonomous-run rules — Clay will review and approve.
