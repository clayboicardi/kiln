# Vendored libFLAC 1.5.0 — Win-x64

## What's here

- `libFLAC.dll` — Xiph libFLAC 1.5.0, Win-x64, BSD-3 (Xiph variant). Vendored.
- `LICENSE-libflac.txt` — full text of the Xiph BSD-3 license (COPYING.Xiph from the upstream distribution).

## Provenance

- Source: GitHub release `xiph/flac` tag `1.5.0`
- Archive: `flac-1.5.0-win.zip` (1,318,000 bytes)
- Archive SHA256: `53F1500F0D6E7C61379D7FEE50D4A9F7F504C650009506D9BA015530D76C0DDE`
- DLL path inside archive: `Win64\libFLAC.dll`
- DLL size: 522,240 bytes
- DLL SHA256: `F93499172875FC2C0DF80B57086F32E3F39E835283952EE2A59A3D4FFB097644`
- Upstream release date: 2025-02-11
- Vendored: 2026-05-19 (Kiln MVP Session 9, H6)

## Why we vendor instead of using a Java FLAC library

Per `docs/decisions/2026-05-18-library-vetting.md` Item 9 addendum:
- `nayuki/FLAC-library-Java` — GPL-3.0 (incompatible with Kiln's Apache 2.0)
- `jflac` — unmaintained + no 24-bit support
- `JustFLAC` — no LICENSE file (legally unusable)

JNA + the official Xiph BSD-3 binary is the cleanest path. The library lifetime
is high-stability (the C ABI hasn't broken in the 1.x series); the JNA bridge
is the engine-swap-shaped boundary (per spec §3.3 / vetting Item 13).

## How it's loaded

`audio/playback/src/desktopMain/.../native/NativeLibraryLoader.kt` extracts
this DLL from the classpath JAR to a temp directory at first call, then
invokes `System.load(absolutePath)`. JNA `Native.load("FLAC", ...)` then
resolves symbols from the loaded DLL.

## Updating

When upgrading libFLAC: download the new `flac-X.Y.Z-win.zip` from
`https://github.com/xiph/flac/releases`, extract `Win64\libFLAC.dll`, replace
this file, update the SHA256 + size + version above. Run the FLAC smoke test
(`./gradlew :audio:playback:desktopTest --tests "*FlacDecodeSmokeTest"`)
against Clay's library to verify no behavioral regression.

## Other platforms

Win-x64 only at MVP. Other Win-arm64 / macOS / Linux Desktop are out-of-scope
per spec §2 hard locks. If they re-enter scope, vendor sibling DLLs/dylibs/sos
in `resources/native/{linux-x64,darwin-arm64,darwin-x64,win-arm64}/`.
