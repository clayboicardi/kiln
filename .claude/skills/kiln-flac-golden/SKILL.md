---
name: kiln-flac-golden
description: This skill should be used when the user asks to "verify FLAC decode parity", "run the FLAC golden corpus", "check the libFLAC bridge", "regression-test JvmFlacDecoder", or after any code change touching `audio/playback/src/{commonMain,desktopMain}/.../flac/`, `audio/playback/src/desktopMain/.../nativeio/`, `audio/playback/src/desktopMain/resources/native/`, or the JNA / libFLAC vendored binary. Synthesizes a deterministic FLAC corpus via `ffmpeg lavfi`, decodes each to raw PCM via `flac.exe -d`, and byte-compares against `JvmFlacDecoderImpl`'s output. On mismatch, surfaces the first divergent sample offset with a 32-sample hex diff window — catches sign-extension regressions, 24-bit packing bugs, callback-GC issues, and silent libFLAC-version-bump breakage.
---

# kiln-flac-golden

Empirically verify byte-for-byte parity between `JvmFlacDecoderImpl` (Kiln's JNA + vendored libFLAC bridge) and the reference `flac.exe -d` decoder, across a synthetic golden corpus that covers the bit-depth / sample-rate / channel-count axes the production code paths exercise.

## When to invoke

- After any code change under `audio/playback/src/{commonMain,desktopMain}/kotlin/com/clayworks/kiln/audio/playback/` that touches the FLAC decode path (decoder, decoded-stream, frame-reader, libFLAC binding, native loader).
- After a `jna` version bump in `gradle/libs.versions.toml`.
- After replacing the vendored `FLAC.dll` under `audio/playback/src/desktopMain/resources/native/win-x64/`.
- Pre-merge verification for any PR labeled `libFLAC` or touching the bridge.

## When NOT to invoke

- For changes unrelated to FLAC decode (UI work, scanner, MediaStore queries, Android-only paths).
- For Android-only changes — this skill is desktop-decoder-specific. Android FLAC goes through Media3's internal decoder, not Kiln's bridge.
- Mid-session while H7 / vertical-slice work is the priority — only invoke if the change touched the bridge.

## Prerequisites

- `flac.exe` on PATH (`winget install Xiph.FLAC`). The skill aborts with a clear install hint if missing.
- `ffmpeg` on PATH (already present per Kiln's tooling baseline; the skill verifies).
- `./gradlew` at the repo root with `:audio:playback:desktopTest` configured.

The companion test class is at `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/flac/GoldenCorpusTest.kt`. The skill assumes this file exists. If you delete it the skill becomes inert — re-author from this skill's design.

## Invocation

```powershell
# Full default run — regenerates corpus if needed, then verifies parity
pwsh -File .claude/skills/kiln-flac-golden/scripts/run-golden-test.ps1

# Force-regenerate the corpus (after manifest edits or for a known-clean baseline)
pwsh -File .claude/skills/kiln-flac-golden/scripts/run-golden-test.ps1 -Regenerate

# Single-file diagnostic (after a specific failure)
pwsh -File .claude/skills/kiln-flac-golden/scripts/run-golden-test.ps1 -File 24bit-96000-sine-440hz.flac

# Structured JSON output
pwsh -File .claude/skills/kiln-flac-golden/scripts/run-golden-test.ps1 -Json
```

### Flags

| Flag | Effect |
|---|---|
| `-Regenerate` | Re-synthesize the entire corpus from `corpus.manifest` before running tests. |
| `-File <name>` | Restrict to one `.flac` (must be in the corpus dir already). |
| `-CorpusDir <path>` | Override the default corpus dir (`audio/playback/build/golden-corpus/`, gitignored). |
| `-Json` | Emit structured JSON to stdout instead of the human-readable summary. |
| `-IncludeTiddl` | Append local-only FLACs from `local-corpus.manifest` (Clay's `D:\tiddl`-sourced; gitignored). |

## Output

Default = human-readable summary. Example:

```
==========================================
Kiln FLAC Golden-Corpus parity check
==========================================
Corpus dir: C:/Users/chawo/Projects/kiln/audio/playback/build/golden-corpus
Files:      5 synthetic + 0 tiddl
==========================================
PASS  16bit-44100-sine-440hz.flac          (441000 samples, 16-bit, 2ch, 282 ms)
PASS  24bit-96000-sine-440hz.flac          (480000 samples, 24-bit, 2ch, 312 ms)
PASS  16bit-44100-sweep.flac               (441000 samples, 16-bit, 2ch, 198 ms)
PASS  24bit-96000-pink-noise.flac          (480000 samples, 24-bit, 2ch, 421 ms)
PASS  16bit-44100-stereo-impulse.flac      (44100  samples, 16-bit, 2ch,  18 ms)
==========================================
Verdict:    PASS (5/5)
Throughput: 84.3 MB/s
Total:      1.43 s
==========================================
```

On failure, each diverging file gets a first-mismatch report:

```
FAIL  16bit-44100-sweep.flac
  first mismatch at byte offset 5368 (sample 1342, channel 1)
  decoded size = 176400 bytes; expected = 176400 bytes
  expected[5360..]: ab 12 34 cd 56 78 9a bc ...
  actual[5360..]:   ab 12 35 cd 56 78 9a bc ...
                          ^^ first divergent byte
```

JSON shape is documented in the `run-golden-test.ps1` header.

## How the corpus is structured

Synthesis recipes live in [`corpus.manifest`](corpus.manifest). Each row defines:

```
<filename>.flac | <ffmpeg recipe args between `ffmpeg` and output filename> | <description>
```

The skill's `generate-reference-pcm.ps1` reads the manifest, synthesizes each FLAC with `ffmpeg`, then produces the matching raw `.pcm` reference by running `flac.exe -d --force-raw-format --endian=little --sign=signed` on each FLAC.

Both `.flac` and `.pcm` outputs land in `audio/playback/build/golden-corpus/` — gitignored, regenerable, reproducible byte-for-byte from the manifest + the installed flac.exe + ffmpeg versions.

## Companion test class

[`audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/flac/GoldenCorpusTest.kt`](../../audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/flac/GoldenCorpusTest.kt) reads the corpus dir from the `kiln.golden.corpus` system property (or `KILN_GOLDEN_CORPUS` env var). When neither is set it auto-skips via `org.junit.Assume.assumeTrue`, so a normal `./gradlew :audio:playback:desktopTest` run does not regress when the corpus is absent.

The skill always sets `KILN_GOLDEN_CORPUS` before invoking Gradle, so the test activates exactly when invoked through this skill.

## Procedure (what the script does)

1. Verify `flac.exe` + `ffmpeg` on PATH. If `flac.exe` is missing, abort with `winget install Xiph.FLAC` hint and exit 4.
2. Resolve corpus dir: `-CorpusDir` if passed, else `audio/playback/build/golden-corpus/` relative to repo root.
3. If `-Regenerate` was passed or the dir is empty, invoke `scripts/generate-reference-pcm.ps1` to populate it.
4. Set `$env:KILN_GOLDEN_CORPUS` to the absolute corpus path so the test JVM inherits it.
5. Invoke `./gradlew :audio:playback:desktopTest --tests "com.clayworks.kiln.audio.playback.flac.GoldenCorpusTest" --console=plain --warning-mode=none`.
6. Parse Gradle output via `scripts/parse-gradle-output.ps1`. Pull per-file pass/fail from JUnit XML at `audio/playback/build/test-results/desktopTest/TEST-*.xml`.
7. Emit JSON or human summary; exit with the underlying Gradle exit code.

## Scripts

- **`scripts/generate-reference-pcm.ps1`** — reads `corpus.manifest`, synthesizes each FLAC via `ffmpeg`, decodes to raw PCM via `flac.exe -d`. Idempotent; safe to re-run.
- **`scripts/run-golden-test.ps1`** — main entry. Verifies deps, optionally regenerates, invokes Gradle with the corpus dir env var, parses output, prints summary.
- **`scripts/parse-gradle-output.ps1`** — parses Gradle's plain-console output + the JUnit XML reports into a structured pass/fail hashtable.

## Acceptance criteria (from the tooling-recommendation spec)

- Clean Kiln checkout (post-Session 10 fixes, libFLAC 1.5.0 vendored): 5/5 synthetic corpus pass.
- Deliberate regression to `JvmFlacDecodedStream.pack24BitLE()` (or its successor) → 24-bit corpus files fail with first-mismatch reports.
- Synthetic-only run completes in well under 30 seconds on a hot Gradle daemon.

## Known limitations

- **Synthetic-only by default.** Real-world FLAC variants (embedded album art, oversized metadata blocks, exotic block sizes, FLAC <8 channel layouts) are NOT covered by the synthetic corpus. Use `-IncludeTiddl` (with a manifest at `.claude/skills/kiln-flac-golden/local-corpus.manifest`, gitignored) to layer in real-world samples from Clay's `D:\tiddl` library.
- **Windows-only.** `flac.exe` lookup and the synthesizer assume Windows shells. Linux/macOS would need a parallel script path.
- **Single-decode-pass test.** The test instantiates a fresh `JvmFlacDecoderImpl` per file. It does NOT exercise long-running multi-file decode sequences (where callback-GC bugs surface). For that, write a dedicated stress test under `audio/playback/src/desktopTest/.../FlacStressTest.kt` separately.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Exit 4 "flac.exe not found" | Xiph.FLAC not installed | `winget install Xiph.FLAC` |
| Exit 5 "ffmpeg not found" | ffmpeg missing | Already required by Kiln tooling baseline; see CLAUDE.md hardware reference |
| Test skips with `Assume failed` | Corpus dir is empty | Pass `-Regenerate` or check `audio/playback/build/golden-corpus/` |
| All files fail with same byte offset | Likely a global sign-extension or endianness regression in `JvmFlacDecodedStream` | Diff against `git log audio/playback/src/desktopMain/.../JvmFlacDecodedStream.kt` |
| One bit-depth fails consistently | Bit-depth-specific bug (24-bit packing is the historical hot spot — see CLAUDE.md gotcha re: libFLAC delivering FLAC__int32 regardless of source depth) | Inspect `extractInterleavedPcm` for that bit depth |
| Pink-noise file fails but sines pass | Likely a bulk-read vs. per-sample path divergence under high-entropy input | Check `Pointer.getIntArray(0, blocksize)` bulk path in FlacFrameReader |
