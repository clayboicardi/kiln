# Kiln Tooling Recommendation — 2026-05-21

**Status:** Locked. Decisions confirmed by Clay 2026-05-21 in research-session conversation.

**Scope:** MCPs, LSPs, Claude Code skills/plugins, and external CLI/desktop utilities that augment Claude Code-driven development on Kiln. **Out of scope:** project Gradle dev dependencies (detekt, ktlint, kover, dokka, Renovate) — that bucket is a separate future decision.

**Research methodology:** 5 parallel research subagents (Kotlin/KMP LSP, Android device MCPs, audio dev tooling, SQLite tooling, Windows CLI utilities) + Gemini cross-validation + direct GitHub MCP version verification. All version dates verified via `gh api` 2026-05-21, not training data (per CLAUDE.md tool-priorities).

**Headline action:** Enable `kotlin-lsp@claude-plugins-official` workspace-scoped for Kiln. The plugin (currently installed-but-disabled in `~/.claude/settings.json`) wraps JetBrains' official kotlin-lsp v262.4739.0, in which non-standard Gradle source sets were fixed (LSP-835) — making this the first release that correctly handles Kiln's `androidMain`/`desktopMain`/`commonMain` layout. fwcd/kotlin-language-server is officially deprecated (2025-06-02).

---

## Workspace vs global split — the headline matrix

| Tool | Scope | Why this scope |
|---|---|---|
| `kotlin-lsp@plugin` + JDK 25 + IntelliJ-server binary | **Workspace** (kiln/.claude/settings.json) | Server has 500MB+ memory + JDK 25 startup cost; isolating to Kotlin sessions matters. Project settings override global "off" with project "on". |
| `bytebase/dbhub` MCP (write-access, with `--max-rows` + `--query-timeout` guards) | **Workspace** (kiln/.mcp.json) | DSN is Kiln-specific; pinning here keeps the config tied to Kiln's database file. |
| Custom Kiln skills (kiln-flac-golden, kiln-verify-build, kiln-session-handoff) | **Workspace** (kiln/.claude/skills/) | By definition project-specific. |
| `mobile-next/mobile-mcp` | **Workspace** (promotable) | Only Android project right now; promote to global if a second arrives. |
| `hyperb1iss/droidmind` | **Workspace** (promotable, deferred) | Same reasoning. |
| `justfile` | **Workspace** (repo root, checked in) | Recipes are Kiln-specific. |
| scrcpy, just (binary), MediaInfo CLI, jadx, Apktool, DB Browser for SQLite | **Global** (winget) | OS-level CLI utilities; reproducible install regardless of project. |
| JDK 25 (for kotlin-lsp server JVM) | **Global** (winget Adoptium) | System-wide JDK; coexists with Temurin 21. |
| JMC, gradle-profiler, kotlinx-benchmark, Maestro CLI | **Global** | Cross-project profiling / test toolchain. |
| `antarikshc/perfetto-mcp`, `jextract`, LeakCanary, async-profiler (WSL2) | **Workspace** (Phase 2b deferred) | Not needed for MVP. |

---

## Install ladder — sequence by priority

### Tier 0 — Today (~10 min, zero risk)

Five P0 CLIs via PowerShell foreach (winget accepts only one `--id` per invocation, so loop):

```powershell
foreach ($id in 'Genymobile.scrcpy','Casey.Just','MediaArea.MediaInfo','DBBrowserForSQLite.DBBrowserForSQLite','skylot.jadx') {
    winget install --id $id --accept-source-agreements --accept-package-agreements --silent
}
```

**Notes on package IDs (verified 2026-05-21 via `winget search`):**
- `MediaArea.MediaInfo` is the CLI build (NOT `MediaArea.MediaInfo.CLI` — that ID does not exist). `MediaArea.MediaInfo.GUI` is the GUI version.
- **Apktool is NOT in winget.** Closest match is `AlexanderGorishnyak.APKEditorStudio` (GUI app, not the CLI). If you want apktool: `scoop install apktool` (requires scoop) or `choco install apktool` (requires chocolatey) or manual download from <https://bitbucket.org/iBotPeaches/apktool/downloads/>. jadx alone covers ~90% of APK-inspection use cases; defer apktool to Tier 3 / on-demand.

Then drop a starter `justfile` at `C:\Users\chawo\Projects\kiln\justfile`:

```just
# Kiln workflow recipes — invoked via `just <recipe>`
# Requires: just (winget Casey.Just), ./gradlew, adb at C:/Users/chawo/Desktop/platform-tools/adb.exe

default: verify

# Canonical session-validation build per CLAUDE.md
verify:
    ./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest

# Build, install, and launch on attached Pixel
pixel: && _adb-launch
    ./gradlew :app-android:assembleDebug
    "C:/Users/chawo/Desktop/platform-tools/adb.exe" install -r app-android/build/outputs/apk/debug/app-android-debug.apk

_adb-launch:
    "C:/Users/chawo/Desktop/platform-tools/adb.exe" shell am start -n com.clayworks.kiln/.MainActivity

# Launch desktop app
desktop:
    ./gradlew :app-desktop:run

# Run the desktop tests (incl. JvmFlacDecoder smoke)
test-desktop:
    ./gradlew :audio:playback:desktopTest :data:library:desktopTest

# Show ADB-connected devices
devices:
    "C:/Users/chawo/Desktop/platform-tools/adb.exe" devices -l
```

### Tier 1 — This week (~30 min, low risk, highest leverage)

**Step 1 — JDK 25 (OPTIONAL for kotlin-lsp; useful for FFM/jextract):**

```powershell
winget install --id EclipseAdoptium.Temurin.25.JDK
```

**Important:** kotlin-lsp's standalone Windows ZIP **bundles its own JetBrains Runtime (JBR) 25.0.2** at `jbr/bin/java.exe`. The server uses the bundled JBR per `product-info.json`'s `"javaExecutablePath": "jbr/bin/java.exe"`. So the separate system JDK 25 is **NOT required for kotlin-lsp to function**. Reasons to install JDK 25 anyway: (a) jextract (Phase 2b FFM/Panama exploration ships with JDK 22+), (b) future projects that want JDK 25 on PATH. If neither use case matters, skip this step entirely.

**Step 2 — Install kotlin-lsp v262.4739.0:**

The release-body of <https://github.com/Kotlin/kotlin-lsp/releases/tag/kotlin-lsp%2Fv262.4739.0> hosts downloads on JetBrains' CDN (NOT on GitHub release assets — `gh release view` returns `assets:[]`). Direct URLs:

- ZIP: `https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0.win.zip` (~371 MB)
- SHA256: `https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0.win.zip.sha256`

PowerShell install script:

```powershell
New-Item -ItemType Directory -Force -Path "C:\Users\chawo\tools\kotlin-lsp" | Out-Null
$zipPath = "$env:TEMP\kotlin-server-262.4739.0.win.zip"
$url = "https://download-cdn.jetbrains.com/kotlin-lsp/262.4739.0/kotlin-server-262.4739.0.win.zip"
Invoke-WebRequest -Uri $url -OutFile $zipPath -UseBasicParsing
Invoke-WebRequest -Uri "$url.sha256" -OutFile "$zipPath.sha256" -UseBasicParsing
$expectedSha = (Get-Content "$zipPath.sha256" -Raw).Trim().Split(' ')[0]
$actualSha = (Get-FileHash $zipPath -Algorithm SHA256).Hash.ToLower()
if ($expectedSha -ne $actualSha) { throw "SHA256 mismatch — abort" }
Expand-Archive -Path $zipPath -DestinationPath "C:\Users\chawo\tools\kotlin-lsp" -Force
# Add to user PATH (idempotent, reversible)
$kotlinBin = "C:\Users\chawo\tools\kotlin-lsp\bin"
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if (($userPath -split ';') -notcontains $kotlinBin) {
    [Environment]::SetEnvironmentVariable("Path", "$userPath;$kotlinBin", "User")
}
```

**Step 2b — Create binary-name bridge (CRITICAL — discovered 2026-05-21):**

Anthropic's `kotlin-lsp@claude-plugins-official` plugin spawns a binary literally named `kotlin-lsp` (no extension). On macOS this is provided by `brew install JetBrains/utils/kotlin-lsp`. On Windows, the JetBrains ZIP's `bin\` contains `intellij-server.exe` — NOT `kotlin-lsp.exe`. There IS a deprecated `kotlin-lsp.cmd` at the install ROOT (not in `bin\`), but it warns about future removal.

**Critical Windows-spawn gotcha (verified empirically 2026-05-21):** libuv's `uv_spawn` (which CC's LSP tool uses) on Windows applies CreateProcess's extension-resolution rules: when given a bare name like `kotlin-lsp`, it tries the literal name then auto-appends `.exe`. It does **NOT** try `.cmd`, `.bat`, or other PATHEXT extensions. So a `.cmd` wrapper alone is invisible to the plugin's spawn — even though `where.exe` finds it. **You need a `.exe`-extension binary on PATH.**

**Solution:** create a hard link `bin\kotlin-lsp.exe` pointing at the same content as `bin\intellij-server.exe`. Single file on disk, two names. No copy waste, no admin required (hard links — unlike symlinks — work without elevation on Windows when both endpoints are on the same NTFS volume).

```powershell
$src = "C:\Users\chawo\tools\kotlin-lsp\bin\intellij-server.exe"
$dst = "C:\Users\chawo\tools\kotlin-lsp\bin\kotlin-lsp.exe"
if (Test-Path $dst) { Remove-Item $dst -Force }
$null = New-Item -ItemType HardLink -Path $dst -Target $src
```

After running, open a **new** PowerShell session and verify:
```
where.exe kotlin-lsp        # → C:\Users\chawo\tools\kotlin-lsp\bin\kotlin-lsp.exe
kotlin-lsp --version        # → LS-262.4739.0
```

If `where.exe` returns nothing, the link wasn't created or `bin\` isn't on PATH. Re-check Step 2 PATH addition.

**Optional supplemental** `bin\kotlin-lsp.cmd` wrapper for manual shell convenience (PATHEXT prefers `.EXE` over `.CMD`, so the hard link will win for both interactive use AND plugin spawn — the `.cmd` is purely redundant safety belt). Skippable.

**Failure mode this prevents:** Without the `.exe` link, the LSP plugin spawns `kotlin-lsp` and fails immediately with `ENOENT: uv_spawn 'kotlin-lsp'` before any index build can start. Symptoms in CC test: the error appears INSTANTLY (not after a 30-2min index wait); a `.cmd`-only wrapper looks correct via `where.exe` but doesn't fix the spawn. (Observed empirically across two separate fresh CC test sessions; hard link finally resolved it.)

**Forward compatibility note:** This hard link approach binds `kotlin-lsp.exe` to whatever `intellij-server.exe` is at the time of linking. If a future kotlin-lsp ZIP renames or relocates `intellij-server.exe`, recreate the hard link. The PowerShell snippet above is idempotent — safe to re-run.

No winget/scoop bucket exists; manual download is the only Windows path. There is no auto-update; track new releases at <https://github.com/Kotlin/kotlin-lsp/releases>.

**Step 3 — Workspace settings file:**

Create `C:\Users\chawo\Projects\kiln\.claude\settings.json`:

```json
{
  "enabledPlugins": {
    "kotlin-lsp@claude-plugins-official": true
  }
}
```

**Step 4 — Workspace MCP file:**

Create `C:\Users\chawo\Projects\kiln\.mcp.json`. The dbhub entry is **deliberately commented out via a leading-underscore key** (`_kiln-db-desktop`) — uncomment by removing the underscore after first `./gradlew :app-desktop:run` populates `C:\Users\chawo\.kiln\kiln.db`.

**Correction 2026-05-21:** Original draft assumed `%AppData%\kiln\kiln.db` (the standard Windows Roaming convention). Kiln actually uses `~/.kiln/kiln.db` (user-home dir, hidden). Verified empirically: after first `:app-desktop:run` against Clay's `D:\tiddl` library, the DB landed at `C:\Users\chawo\.kiln\kiln.db` (15.26 MB, 27,766 tracks indexed). DSN updated accordingly:

```json
{
  "_doc": "Workspace-scoped MCP servers for Kiln. See docs/decisions/2026-05-21-tooling-recommendation.md for rationale.",
  "_dbhub_note": "kiln-db-desktop is COMMENTED OUT until ./gradlew :app-desktop:run has been invoked at least once to create %AppData%/kiln/kiln.db. After first run, remove the leading underscore from '_kiln-db-desktop' to activate.",
  "mcpServers": {
    "_kiln-db-desktop": {
      "command": "npx",
      "args": [
        "-y",
        "@bytebase/dbhub@0.21.2",
        "--transport", "stdio",
        "--max-rows", "1000",
        "--query-timeout", "10000",
        "--dsn", "sqlite:///C:/Users/chawo/.kiln/kiln.db"
      ]
    }
  }
}
```

Notes on this config:
- **No `--readonly` flag** — Clay explicitly authorized write access. `execute_sql` will accept DDL/DML.
- **`--max-rows 1000`** — bounds runaway SELECTs.
- **`--query-timeout 10000`** — 10-second query timeout; aborts hung queries.
- **Pinned version `@0.21.2`** — latest npm as of 2026-05-21 (verified via `curl npmjs.org/@bytebase/dbhub`). bytebase/dbhub has no GitHub releases tag — npm is the source of truth.
- **DSN path is `~/.kiln/kiln.db`** — Kiln uses the user-home-dir convention (verified empirically, not the standard appdirs Roaming convention). If you ever change Kiln's user-data-dir resolution (e.g., a Settings → custom data dir feature in Phase 2a), update this DSN accordingly.
- **Activation:** rename `"_kiln-db-desktop"` to `"kiln-db-desktop"` in `.mcp.json` after the DB file exists, then restart Claude Code.

**Step 5 — Verify the chain:**

In a new CC session inside Kiln:
1. Ask the `LSP` tool for `documentSymbol` on `data/library/src/commonMain/kotlin/com/clayworks/kiln/data/library/LocalLibrarySource.kt`. Should return a list of class/function symbols.
2. Ask the `kiln-db-desktop` MCP to run `SELECT COUNT(*) FROM track` (after `:app-desktop:run` has scanned at least once). Should return a number.

If LSP returns no symbols, run `./gradlew :data:library:kspKotlinDesktop` to refresh KSP-generated sources and restart the session — the LSP reads from disk and needs the build/generated/ tree populated for kotlin-inject + SQLDelight symbols to resolve.

### Tier 2 — Fresh session after current Session 10 wraps (~2-3 hr authoring)

Three custom skills to live in `C:\Users\chawo\Projects\kiln\.claude\skills/`. Full design specs in the "Custom skill specs" section below. Recommended sequence: Clay invokes `/plugin-dev:skill-development` in a fresh session, points it at this doc's skill spec sections, lets it scaffold.

### Tier 3 — On demand (install when the use case arises)

| Tool | Trigger | Install |
|---|---|---|
| `mobile-next/mobile-mcp` v0.0.55 | First H7/H8 verification on Pixel where you want Claude to read Compose UI elements without OCR | `claude mcp add mobile-mcp -- npx -y @mobilenext/mobile-mcp@latest` (or add to `kiln/.mcp.json`). Set `MOBILEMCP_DISABLE_TELEMETRY=1` env. |
| `hyperb1iss/droidmind` v0.4.0 | First structured logcat/ANR/crash diagnostics session — Apache-2.0 matches Kiln's license. **Smoke-test against Pixel 10 Pro XL's Android 16 first** (Gemini flagged SELinux/dumpsys regressions risk for solo-maintained, 4.5-month-stale codebase) | `uvx --from git+https://github.com/hyperb1iss/droidmind droidmind --transport stdio` |
| `DBeaver CE` 26.0.5 | Schema-diff two .db files, ER diagrams not covered by DB Browser | `winget install dbeaver.dbeaver` |
| **Maestro CLI** | Once `:app-android` has more than one screen — declarative Compose UI E2E in YAML. **Gemini's recommendation; the canonical modern Compose UI test framework.** | `curl -Ls "https://get.maestro.mobile.dev" \| bash` (run from WSL or Git Bash) |
| `flac.exe` v1.5.0 | When kiln-flac-golden skill is authored | `winget install Xiph.FLAC` |
| `ffmpeg` | Already installed per CLAUDE.md — verify with `ffmpeg -version` | N/A |
| **Apktool** v3.0.2 | Smali decompilation / resource inspection beyond what jadx covers. NO winget package — use scoop or chocolatey | `scoop install apktool` OR `choco install apktool` OR manual: <https://bitbucket.org/iBotPeaches/apktool/downloads/> |

### Tier 4 — Phase 2a (Q3 2026) / Phase 2b (Q4 2026) — defer

| Tool | Purpose | Notes |
|---|---|---|
| JDK Mission Control 9.x | JFR (.jfr) analysis — Windows substitute for async-profiler | Adoptium download <https://adoptium.net/jmc/>; NOT bundled with Temurin 21. JFR itself is in JDK 21. |
| gradle-profiler v0.24.0 | Build benchmarking once times balloon | SDKMAN or manual ZIP. Java 17+ (JDK 21 fine). On Windows, `--profile async-profiler-cpu` is a no-op (no Win binary); build timing still works. |
| kotlinx-benchmark v0.4.17 | `:audio:dsp` microbenchmarks | Verify Gradle 9 compat at install — 0.4.17 supports Gradle 8 explicitly; Gradle 9 untested upstream. |
| `antarikshc/perfetto-mcp` | Low-latency audio jank investigation; NL→PerfettoSQL | Defer until first perf complaint. `uvx perfetto-mcp`; Apache-2.0; no releases tagged. |
| `jextract` (Project Panama / FFM) | **Strategic — Gemini's flag.** JDK 21+'s FFM API is dramatically faster than JNA for audio pipelines. Migrating libFLAC bridge from JNA 5.14.0 → FFM at Phase 2b would unlock latency headroom needed for sub-15ms WASAPI/AAudio. Worth capturing as a vetting-log addendum NOW even if migration waits a quarter. | jextract ships with JDK 22+; verify which JDK distribution has it (typically downloadable separately from <https://jdk.java.net/jextract/>). |
| LeakCanary (Android debug) / Eclipse MAT CLI (Desktop) | Memory-leak diagnostics — audio players inevitably leak JNI buffers / ExoPlayer instances. **Gemini's flag.** | LeakCanary: add `com.squareup.leakcanary:leakcanary-android` to `:app-android` debug deps when first leak surfaces. Eclipse MAT CLI: <https://eclipse.dev/mat/>. |
| WinDbg (modern) | Native libFLAC.dll crash dump triage | `winget install Microsoft.WinDbg`. Only pull in for hard crashes — JDK 21's `hs_err_pid*.log` covers most JNI faults. |
| async-profiler (WSL2 only) | CPU/alloc flame graphs | v4.4 (2026-04-20) has NO Windows binary; 7-year-old issue #188 confirms intentional non-support. Use only if WSL2 enters the dev loop. |
| System Informer 3.x | Process/thread/handle introspection during libFLAC debugging | `winget install WinsiderSS.SystemInformer` |
| ProcMon (Sysinternals) | Filesystem instrumentation during scanner debugging | `winget install Microsoft.Sysinternals.ProcessMonitor` |

---

## Custom skill specs

Three skills to author. Each spec below is designed to be input to `/plugin-dev:skill-development` in a fresh session. Each includes goal, trigger conditions, I/O, dependencies, procedure, file structure, and acceptance criteria.

### Skill 1 — `kiln-flac-golden`

**Goal:** Empirically verify that `JvmFlacDecoderImpl` produces byte-identical PCM output as the reference `flac.exe -d` for a golden corpus. Catches sign-extension regressions, 24-bit packing bugs, callback-GC issues, and any future libFLAC-version-bump silent breakage.

**SKILL.md frontmatter:**
```yaml
---
name: kiln-flac-golden
description: Verify FLAC decode parity between Kiln's JvmFlacDecoderImpl and reference flac.exe across the golden corpus. Use after any change to :audio:playback's libFLAC bridge (JvmFlacDecoder, JvmFlacDecodedStream, NativeLibraryLoader), after JNA version bumps, after vendored libFLAC.dll version bumps, or before merging changes that touch libFLAC. Returns pass/fail per file plus first-mismatch sample offsets with 32-sample diff windows.
---
```

**When to invoke (must be specific enough for auto-trigger):**
- Any code change under `audio/playback/src/{commonMain,desktopMain}/.../flac/` or `audio/playback/src/desktopMain/resources/native/`
- After `jna` version bump in `gradle/libs.versions.toml`
- After vendored `FLAC.dll` replacement
- Pre-merge for libFLAC-bridge PRs

**When NOT to invoke:**
- For code changes unrelated to FLAC decode
- Mid-Session-10 (focus is H7 + H8 — vertical slice)
- For Android-only changes (this skill is desktop-decoder-specific)

**Inputs:**
- Optional: `--corpus-dir <path>` (default: `audio/playback/src/desktopTest/resources/golden-corpus/`)
- Optional: `--file <name>` to test a single file
- Optional: `--regenerate` to recreate reference `.pcm` files from `.flac` sources before testing
- Optional: `--include-tiddl` to include real-world samples from `D:\tiddl` (Clay's local-only; manifest in `.claude/skills/kiln-flac-golden/local-corpus.manifest`, gitignored)

**Outputs (stdout = human-readable summary; exit code conveys pass/fail; structured JSON optional via `--json`):**
```json
{
  "total": 12,
  "passed": 11,
  "failed": 1,
  "duration_ms": 4732,
  "throughput_mb_s": 84.3,
  "files": [
    {
      "name": "24bit-96000-sine-440hz.flac",
      "status": "pass",
      "samples_decoded": 480000,
      "channels": 2,
      "bit_depth": 24,
      "duration_ms": 312
    },
    {
      "name": "16bit-44100-sweep.flac",
      "status": "fail",
      "first_mismatch_sample": 1342,
      "first_mismatch_channel": 1,
      "expected_bytes_hex": "ab 12 34 cd ...",
      "actual_bytes_hex":   "ab 12 35 cd ...",
      "diff_window": "samples 1340-1371"
    }
  ]
}
```

**External dependencies:**
- `flac.exe` on PATH (`winget install Xiph.FLAC`)
- `ffmpeg` on PATH (already installed) — for synthetic-corpus generation
- `./gradlew` — to invoke the companion test
- Companion JUnit test class (to be written): `audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/flac/GoldenCorpusTest.kt`

**Procedure:**
1. Verify dependencies (`flac.exe`, `ffmpeg`) are on PATH; fail-fast with a clear error if missing.
2. If `--regenerate` or `build/golden-corpus/.regen-needed` exists: run `scripts/generate-reference-pcm.ps1` which iterates the manifest and calls `ffmpeg` to synthesize each `.flac`, then `flac.exe -d -f --force-raw-format --endian=little --sign=signed` to produce the matching `.pcm`. Output to `build/golden-corpus/` (gitignored).
3. Invoke `./gradlew :audio:playback:desktopTest --tests "com.clayworks.kiln.audio.playback.flac.GoldenCorpusTest" -Pkiln.golden.corpus=<dir>` from the project root.
4. Companion test reads each `.flac` from corpus dir, decodes via `JvmFlacDecoderImpl.decode()` to in-memory PCM, byte-compares against the matching `.pcm` reference file. Reports first divergence + 32-sample window around it.
5. Parse Gradle output → emit JSON if `--json`, human-readable summary otherwise. Exit 0 if all pass, 1 if any fail.

**Suggested file structure:**
```
kiln/.claude/skills/kiln-flac-golden/
├── SKILL.md
├── scripts/
│   ├── generate-reference-pcm.ps1     # regenerates .pcm files from .flac via flac.exe
│   ├── run-golden-test.ps1            # main entry — verifies deps, optionally regenerates, invokes gradle, parses output
│   └── parse-gradle-output.ps1        # ParseGradleOutput → structured pass/fail JSON
└── corpus.manifest                    # synthesis recipes for synthetic corpus

kiln/audio/playback/src/desktopTest/resources/golden-corpus/
├── README.md                           # how to add new corpus files
└── (no .flac/.pcm files committed — all generated)

kiln/audio/playback/src/desktopTest/kotlin/com/clayworks/kiln/audio/playback/flac/
└── GoldenCorpusTest.kt                 # JUnit class consumed by the skill
```

**`corpus.manifest` initial content (synthesis recipes):**
```
# Format: filename | ffmpeg lavfi recipe (output to stdout, no filename needed in recipe) | description
16bit-44100-sine-440hz.flac | -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=5" -sample_fmt s16 -ar 44100 | 5s 16-bit 44.1kHz pure sine 440Hz
24bit-96000-sine-440hz.flac | -f lavfi -i "sine=frequency=440:sample_rate=96000:duration=5" -sample_fmt s32 -ar 96000 -bits_per_raw_sample 24 | 5s 24-bit 96kHz sine
16bit-44100-sweep.flac | -f lavfi -i "aevalsrc=sin(2*PI*(20+19980*t/10)*t):s=44100:d=10" -sample_fmt s16 | 10s sweep 20Hz-20kHz
24bit-96000-pink-noise.flac | -f lavfi -i "anoisesrc=color=pink:sample_rate=96000:duration=5" -sample_fmt s32 -bits_per_raw_sample 24 | 5s pink noise
16bit-44100-stereo-impulse.flac | -f lavfi -i "aevalsrc=if(eq(n\,0)\,1\,0):s=44100:c=2:d=1" -sample_fmt s16 | 1s impulse (sample 0 = 1, rest = 0)
```

**Acceptance criteria:**
- Running the skill on a clean Kiln checkout (post-Session 9 fixes, libFLAC 1.5.0 vendored) produces 5/5 pass on synthetic corpus + 10/10 pass on tiddl corpus.
- Introducing a deliberate sign-extension regression to `JvmFlacDecodedStream.pack24BitLE()` causes the relevant corpus files (24-bit ones) to fail with first-mismatch reports.
- Skill runs in <30s for synthetic corpus, <2min for full corpus including tiddl samples.

---

### Skill 2 — `kiln-verify-build`

**Goal:** Run the canonical 5-target Gradle session-validation build, parse output into structured pass/fail/duration, surface compilation errors with file:line + first-3-error summary. Replaces the "scroll through 200 lines of Gradle output to find the one error" pattern.

**SKILL.md frontmatter:**
```yaml
---
name: kiln-verify-build
description: Run Kiln's canonical session-validation build (5 targets — :app-android:assembleDebug, :app-desktop:assemble, :data:library:build, :audio:playback:build, :data:library:desktopTest). Parses Gradle output and surfaces per-target pass/fail with file:line errors. Invoke after any logical change set, pre-commit, or pre-push. Returns exit 0 if all pass, 1 if any fail; prints human-readable summary.
---
```

**When to invoke:**
- After any code change beyond doc-only edits (CLAUDE.md workflow step 3)
- Pre-commit verification
- Pre-push verification
- After bumping versions in `gradle/libs.versions.toml`
- After Gradle plugin changes in `build-logic/`

**When NOT to invoke:**
- For trivial doc-only or comment-only edits
- When already mid-build (skill checks for `.gradle/build-locks/`)

**Inputs:**
- Optional: `--targets <comma-list>` (default: all 5)
- Optional: `--clean` flag to invoke `./gradlew clean` first
- Optional: `--json` to emit structured output
- Optional: `--no-tests` to skip `:data:library:desktopTest` (faster, for compile-only verify)

**Outputs (default = human summary; `--json` for structured):**
```json
{
  "build": "pass",
  "duration_ms": 12847,
  "targets": [
    {"name": ":app-android:assembleDebug", "status": "pass", "duration_ms": 4823},
    {"name": ":app-desktop:assemble",      "status": "pass", "duration_ms": 2104},
    {"name": ":data:library:build",        "status": "pass", "duration_ms": 1932},
    {"name": ":audio:playback:build",      "status": "pass", "duration_ms": 1456},
    {"name": ":data:library:desktopTest",  "status": "pass", "duration_ms": 2532, "tests_run": 25, "tests_passed": 25}
  ],
  "errors": []
}
```

For failures, `errors` populated as:
```json
"errors": [
  {
    "file": "data/library/src/commonMain/kotlin/com/clayworks/kiln/data/library/LocalLibrarySource.kt",
    "line": 142,
    "column": 23,
    "severity": "error",
    "message": "Unresolved reference: selectByAlbum"
  }
]
```

**External dependencies:**
- `./gradlew` at repo root
- (Optional, for `--json`) — a JSON-emitting Gradle init script could simplify parsing; skip for v1 and parse Gradle's plain-console output

**Procedure:**
1. Verify `./gradlew` exists and is executable.
2. Check for `.gradle/build-locks/` — if present, error out ("Another build in progress; wait or kill").
3. If `--clean`, run `./gradlew clean` first.
4. Compose target list (default or from `--targets`); skip tests if `--no-tests`.
5. Run `./gradlew <targets> --console=plain --warning-mode=none -Dorg.gradle.welcome=never` from project root.
6. Stream output to user; capture for parsing.
7. Parse for:
   - `BUILD SUCCESSFUL` / `BUILD FAILED` overall verdict
   - Per-task durations (Gradle's `--profile` output if requested, else estimate from log timestamps)
   - Compilation errors: regex `^e: file://([^:]+):(\d+):(\d+) (.*)$` (Kotlin compiler format)
   - Test results: `:data:library:desktopTest > X tests passing / Y failing`
8. Emit structured JSON or human summary; exit code matches BUILD SUCCESSFUL/FAILED.

**Suggested file structure:**
```
kiln/.claude/skills/kiln-verify-build/
├── SKILL.md
└── scripts/
    ├── run-verify.ps1          # main entry
    └── parse-gradle.ps1        # parses Gradle plain-console output into structured form
```

**Acceptance criteria:**
- On the current main branch (post-Session 9 fixes), `kiln-verify-build` returns exit 0 with all 5 targets pass + 25 desktopTests pass.
- Introducing a deliberate compile error to a `:data:library` source file causes the skill to exit 1 with the specific file:line:message surfaced in summary.
- Runs in <20s incremental on a hot Gradle daemon.

---

### Skill 3 — `kiln-session-handoff`

**Goal:** Generate the per-plan-§11 session-handoff document skeleton for the next session. Pulls commit log since last session, in-flight items (TODOs + uncommitted changes), decisions made (from commit messages tagged `decision:`), gotchas discovered (extracted from CLAUDE.md diffs or commit body text). Replaces the ~15-minutes-per-session manual handoff-writing chore.

**SKILL.md frontmatter:**
```yaml
---
name: kiln-session-handoff
description: Generate Kiln's session-handoff doc skeleton for the next CC session. Pulls commit log, in-flight items, decisions, and gotchas discovered. Use at session close — typically after `kiln-verify-build` passes and pre-commit work is done. Writes to docs/sessions/YYYY-MM-DD-session-N-handoff.md.
---
```

**When to invoke:**
- At session close per plan §11
- Before pushing the final commit of a session
- Whenever Clay wants to checkpoint and pick up later cold

**When NOT to invoke:**
- Mid-session
- For sessions with no commits (nothing to hand off)
- For sessions that already have a handoff doc (skill checks for existing file, warns)

**Inputs:**
- Required: `--session-num <N>` (e.g., `11` for "Session 11")
- Optional: `--prev-session <N>` (auto-detected from `docs/sessions/` if not provided)
- Optional: `--summary <one-line>` to seed the doc's "What this session did" section
- Optional: `--in-flight <items>` comma-separated handoff items (e.g., `H1,H2` — corresponds to past Kiln handoff convention)

**Outputs:**
- Writes `docs/sessions/YYYY-MM-DD-session-<N>-handoff.md`
- Returns absolute path of file written
- Exit 0 on success, 1 if file already exists (use `--force` to overwrite)

**External dependencies:**
- `git` (already installed)
- Read access to `docs/sessions/` and CLAUDE.md

**Procedure:**
1. Detect previous session: list `docs/sessions/*-session-*.md`, parse session numbers, pick the highest.
2. Find the commit at which previous session ended:
   - Search for commits tagged `session:<N>:close` OR
   - Use the timestamp of the previous session's handoff file as the cutoff OR
   - Fallback: previous session's last-commit per `git log` heuristic
3. Pull commit log since: `git log <prev>..HEAD --pretty=format:'%h%n%s%n%b%n---'` 
4. Parse commits:
   - Subject = bullet content
   - Body `decision:` lines → "Decisions made" section
   - Body `gotcha:` lines → "Gotchas discovered" section
   - Body `todo:` lines or in-tree `// TODO(session-N+1)` greps → "In-flight items"
5. Diff CLAUDE.md vs previous session: any added lines under "Build/Dep Gotchas" or "Hard Rules" become handoff highlights
6. Read uncommitted git stash + `git status --short` → "Working tree state" section
7. Compose handoff doc per template:
   ```markdown
   # Kiln Session N — Handoff (next session pickup)
   
   **Date:** YYYY-MM-DD
   **From session:** N-1 (closed at commit <sha>)
   **To session:** N (this doc is the entry point)
   
   ## What session N-1 did
   <summary or auto-generated from commits>
   
   ## In-flight items (handoff)
   <H1, H2, ...>
   
   ## Decisions made
   <from commit bodies>
   
   ## Gotchas discovered (CLAUDE.md additions)
   <CLAUDE.md diff highlights>
   
   ## Working tree state at handoff
   <git status, stash count, branch, divergence>
   
   ## Verify before starting
   - [ ] Run `just verify` — should pass clean on main
   - [ ] Read CLAUDE.md for new rules
   - [ ] Check this handoff's "In-flight items"
   
   ## Reference
   - Plan: docs/superpowers/plans/2026-05-18-kiln-execution-plan.md
   - Spec: docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md
   - Prev session: docs/sessions/YYYY-MM-DD-session-(N-1).md
   ```

**Suggested file structure:**
```
kiln/.claude/skills/kiln-session-handoff/
├── SKILL.md
└── scripts/
    ├── generate-handoff.ps1     # main entry
    └── handoff-template.md      # the markdown skeleton
```

**Acceptance criteria:**
- Running `kiln-session-handoff --session-num 11` after Session 10 closes produces a sensible `docs/sessions/2026-05-XX-session-11-handoff.md` that a fresh CC instance can pick up cold.
- Handles the case where there are no `decision:`/`gotcha:` tags in commits — falls back to listing commit subjects without crashing.
- Refuses to overwrite an existing handoff doc unless `--force` is passed.

**Note on commit-message convention:** This skill assumes commits adopt structured trailers like `decision:`, `gotcha:`, `todo:` in their body. Kiln doesn't currently enforce this — adopting it now (as an optional convention, not a hard rule) is the prerequisite for this skill's full value. Worth adding to CLAUDE.md as a soft convention if you keep this skill.

---

## Explicitly rejected (with reasoning)

These came up during research but earned no install. Capturing here so they're not re-litigated in future sessions.

| Tool | Reason rejected | Date verified |
|---|---|---|
| `fwcd/kotlin-language-server` | **Deprecated 2025-06-02**, README pointed at JB-official kotlin-lsp. | 2026-05-21 |
| `serena` polyglot LSP | Orthogonal to Claude Code's native `LSP` tool; running alongside kotlin-lsp would mean two Kotlin servers + double the index footprint. Project README explicitly warns against marketplace installs. | 2026-05-21 |
| `async-profiler` on Windows | No native Windows binary as of v4.4 (2026-04-20); 7-year-old issue #188 confirms intentional non-support. Use JFR + JMC instead. | 2026-05-21 |
| `SoX` | Last release 2015 (14.4.2, 11 years no release). Use `ffmpeg lavfi` filters (already installed) for sine/sweep/pink-noise generation. | 2026-05-21 |
| ffmpeg/ffprobe MCPs (entire ecosystem) | All sub-200-star projects, some without LICENSE files. Write a 30-line `ffprobe -select_streams a:0 -of json` wrapper instead. | 2026-05-21 |
| SQLDelight LSP | Does not exist outside JetBrains IDE plugin. SQL-file editing in Kotlin-only CC sessions stays plain-text. | 2026-05-21 |
| `dbdocs.io` | Hosted service. Prefer SchemaSpy locally if ER diagrams are ever wanted. | 2026-05-21 |
| `Beekeeper Studio CE` | Functional but no FTS5-specific advantage over DB Browser; DB Browser ships SQLite 3.46.1 with FTS5 baked in. | 2026-05-21 |
| Custom Kiln Android MCP | mobile-mcp + droidmind together cover ~80% of the surface. Build a custom one only if (a) audio HAL diagnostics become a recurring `dumpsys audio` parsing need, or (b) it becomes a portfolio piece per Software-as-Self-Portrait. | 2026-05-21 |
| Renovate / Dependabot | Out of scope — Clay excluded project Gradle dev tooling from this research scope. Re-evaluate in a future tooling pass. | 2026-05-21 |

---

## Gotchas worth flagging

1. **kotlin-lsp is Alpha.** Per Gemini cross-check: expect occasional analyzer crashes on malformed ASTs mid-edit. CC's LSP tool auto-restarts on crash, but if it loops, kill the `intellij-server.exe` process manually and reopen the session. File bugs at <https://github.com/Kotlin/kotlin-lsp/issues>.

2. **kotlin-lsp won't index `libs.versions.toml`** — that's just text to the LSP. Same with `.sq` files (SQLDelight schema). However, SQLDelight-generated Kotlin in `build/generated/sqldelight/` IS indexed. Always have generated sources up-to-date before relying on LSP.

3. **First-import IntelliJ index** — 30s–2min cold-start on Kiln's 8 modules + KMP source sets. RocksDB persists across sessions, so this is a one-time cost per repo. v262.4739.0 introduced RocksDB-backed index (more robust than the prior in-memory).

4. **KSP-generated sources** — if symbol resolution fails for a kotlin-inject generated graph (e.g., `AndroidAppGraph::class.create()`) or a Compose runtime stub, run `./gradlew :module:kspKotlinAndroid` (or `kspKotlinDesktop`) to refresh the disk state. The LSP reads from disk and does NOT re-trigger KSP itself.

5. **droidmind + Android 16** — Pixel 10 Pro XL runs Android 16; stricter SELinux + dumpsys-sub-service deprecations may break some droidmind tools. Smoke-test on Pixel 10 before committing to it. Failure modes likely silent (returns empty or partial parse). Gemini explicitly flagged this.

6. **bytebase/dbhub versioning** — no GitHub releases tag; npm-versioned only. Pin a specific version (e.g., `@bytebase/dbhub@0.21.0`) in `.mcp.json` rather than `@latest` to avoid silent breaking-change surprise on subsequent npm publishes.

7. **JDK 25 for kotlin-lsp ≠ project JDK 21.** They coexist on PATH. Kotlin-lsp's `intellij-server.exe` finds its own JDK 25 via its installer; project Gradle still uses Temurin 21. Don't change `gradle/libs.versions.toml`'s `jvmTarget = "21"` — unrelated.

8. **mobile-mcp telemetry** — set `MOBILEMCP_DISABLE_TELEMETRY=1` in the env block of its MCP config to disable.

9. **`Hard rules` cross-check with bytebase/dbhub write access** — Kiln's CLAUDE.md doesn't have a hard rule against AI mutating the database, but the spirit of "Don't add features beyond the spec" and the data-layer being load-bearing suggests caution. Clay explicitly opted into write access; consider adding a soft norm: "AI may run DDL/DML on dev DB only, never on shared/production state" — though Kiln has no shared/production state yet.

---

## Future-work captures (worth a vetting-log addendum)

These came up during research and deserve a dedicated decision-log entry (or a section in the existing `docs/decisions/2026-05-18-library-vetting.md`):

1. **FFM / Project Panama migration of libFLAC bridge (Phase 2b candidate)** — Gemini's flag. JDK 21's Foreign Function & Memory API is dramatically faster than JNA for audio pipelines (no JNI marshaling overhead; structured-buffer access via `MemorySegment`). Migrating Kiln's libFLAC bridge from JNA 5.14.0 to FFM at Phase 2b would unlock latency headroom needed for sub-15ms WASAPI/AAudio. This is a Phase 2b architectural call, but the candidate should be captured in the vetting log NOW so it doesn't disappear.
   - Action: append addendum to Item 9 (libFLAC + JNA) in `docs/decisions/2026-05-18-library-vetting.md`.
   - Tooling needed at migration time: `jextract` (ships with JDK 22+).

2. **JNA bump from 5.14.0 → 5.18.1 (Phase 2a candidate)** — Audio Agent C surfaced this. JNA 5.18.1 (2025-09-30) introduced callback thread-mapping improvements + Structure-constructor deadlock fix directly relevant to Kiln's `JvmFlacDecodedStream` callback-GC pattern documented in CLAUDE.md. Even if FFM migration is the long-term answer, bumping JNA in Phase 2a is a low-risk de-risk.

3. **Maestro for Compose UI E2E** — Gemini's flag. Canonical declarative UI testing for Compose. Worth a separate decision doc when `:app-android` grows beyond a single screen (likely MVP Session 13-15).

4. **LeakCanary + Eclipse MAT CLI for memory-leak diagnostics** — Gemini's flag. Audio players inevitably leak JNI buffers / ExoPlayer instances; async-profiler's Windows gap means JFR + manual heap-dump analysis is the local-dev fallback. Worth instrumenting `:app-android` debug builds with LeakCanary now (zero cost in release).

5. **Commit-message convention** — `kiln-session-handoff` skill assumes structured trailers (`decision:`, `gotcha:`, `todo:`). Worth promoting to a soft convention in CLAUDE.md if the skill is kept.

---

## Verification checklist (after Tier 0 + Tier 1 install)

Run these in order; each should succeed before proceeding to the next.

```powershell
# 1. Verify P0 CLIs (new PowerShell session — winget updates PATH for new shells only)
scrcpy --version          # → expects 4.0 or newer
just --version            # → expects 1.51 or newer
mediainfo --version       # → expects v26.05 or newer
jadx-gui --version        # → expects 1.5.5 or newer (winget aliases the GUI as 'jadx-gui')
sqlitebrowser --version   # DB Browser for SQLite (may not have --version; check Start Menu entry)
# apktool — skip if not installed (no winget package); if installed via scoop/choco: apktool --version

# 2. Verify JDK 25 (OPTIONAL — only if you installed via winget; not needed for kotlin-lsp)
& 'C:\Program Files\Eclipse Adoptium\jdk-25\bin\java.exe' --version

# 3. Verify kotlin-lsp on PATH (kotlin-lsp uses its bundled JBR 25, not the system JDK 25)
where.exe intellij-server.exe   # should resolve to C:\Users\chawo\tools\kotlin-lsp\bin\
& 'C:\Users\chawo\tools\kotlin-lsp\jbr\bin\java.exe' --version  # confirms bundled JBR 25.0.2

# 4. Verify justfile recipes work
cd C:\Users\chawo\Projects\kiln
just devices              # should print connected Pixel
just verify               # full 5-target build — ~12-30s incremental

# 5. Verify workspace settings are loaded
# In a NEW CC session inside C:\Users\chawo\Projects\kiln, ask the LSP tool:
#   LSP(operation="documentSymbol",
#       filePath="data/library/src/commonMain/kotlin/com/clayworks/kiln/data/library/LocalLibrarySource.kt",
#       line=1, character=1)
# Expected: list of symbols from the file

# 6. Verify bytebase/dbhub MCP wired
# In the same CC session, after :app-desktop has run at least once and populated the DB:
#   Ask the kiln-db-desktop MCP to: SELECT name FROM sqlite_master WHERE type='table'
# Expected: list of tables (track, album, artist, playlist, ..., fts_track)
```

If any step fails, re-read the Tier 1 step that introduced it.

---

## Reference — research source citations

All version dates verified 2026-05-21 via GitHub MCP `get_latest_release` / `list_releases` / `get_file_contents` (NOT WebFetch on github.com — hallucinates dates per CLAUDE.md tool-priorities).

**Kotlin LSP:**
- <https://github.com/Kotlin/kotlin-lsp/releases/tag/kotlin-lsp%2Fv262.4739.0> (Alpha, 2026-04-27; LSP-835 KMP source-set fix)
- <https://github.com/anthropics/claude-plugins-official/tree/main/plugins/kotlin-lsp> (manifest: README + LICENSE only, 306 bytes)
- <https://github.com/fwcd/kotlin-language-server/commit/ee0553144068676c218255526a1303725e64b0d5> (deprecation commit 2025-06-02)
- <https://github.com/oraios/serena> (orthogonal; conflicting if installed alongside kotlin-lsp)

**Android MCPs:**
- <https://github.com/mobile-next/mobile-mcp> (v0.0.55, 2026-05-16, 4976★, MIT)
- <https://github.com/hyperb1iss/droidmind> (v0.4.0, 2026-01-07, 399★, Apache-2.0)
- <https://github.com/antarikshc/perfetto-mcp> (no releases tagged; last commit 2026-04-10, 185★, Apache-2.0)

**SQLite MCPs:**
- <https://github.com/bytebase/dbhub> (no GH releases; last commit 2026-04-21, 2805★, MIT)
- <https://github.com/modelcontextprotocol/servers> (official SQLite ref server is ARCHIVED)
- <https://github.com/jparkerweb/mcp-sqlite> (v1.0.9, 2026-04-04, 105★, MIT — runner-up with full CRUD)

**Audio tooling:**
- <https://github.com/xiph/flac/releases/tag/1.5.0> (2025-02-11; matches Kiln's vendored copy)
- <https://github.com/MediaArea/MediaInfo/releases/tag/v26.05> (2026-05-12)
- <https://github.com/async-profiler/async-profiler/releases/tag/v4.4> (2026-04-20; no Windows binary)
- <https://github.com/java-native-access/jna/releases/tag/5.18.1> (2025-09-30; Phase 2a bump candidate)
- <https://github.com/bbc/audiowaveform> (v1.10.3, 2025-08-20)

**External CLIs:**
- <https://github.com/Genymobile/scrcpy/releases/tag/v4.0> (2026-05-12)
- <https://github.com/casey/just/releases/tag/1.51.0> (2026-05-10)
- <https://github.com/skylot/jadx/releases/tag/v1.5.5> (2026-02-25)
- <https://github.com/iBotPeaches/Apktool/releases/tag/v3.0.2> (2026-04-19)
- <https://github.com/gradle/gradle-profiler/releases/tag/0.24.0> (2026-03-02)
- <https://github.com/Kotlin/kotlinx-benchmark/releases/tag/v0.4.17> (2026-05-08)
- <https://github.com/WinSiderSS/SystemInformer/releases/tag/v3.2.25011.2103> (2025-05-14)
- <https://adoptium.net/jmc/> (JMC 9.x download for Temurin users)

**Plugin manifest source:**
- Anthropic's official plugins marketplace: <https://github.com/anthropics/claude-plugins-official>

---

**Authoring note:** This document is canonical for the tooling-stack decision as of 2026-05-21. Append addenda below if revisions are warranted in future sessions; do not edit prior content in-place (per CLAUDE.md "Append-only Decision Log" pattern). Cross-reference any FFM/Panama migration prep with `docs/decisions/2026-05-18-library-vetting.md` Item 9.

---

## Addendum 2026-05-21 (later same day): kotlin-lsp DEFERRED pending upstream fix

**Status change:** kotlin-lsp goes from "Tier 1, install now" to **"DEFERRED — install ready, runtime blocked by upstream analyzer bug."** Install + setup are complete and committed; only the LSP-call path fails.

### Diagnostic trail (chronological)

1. **First test session (post-`af0de49`):** `LSP documentSymbol` → instant `ENOENT: uv_spawn 'kotlin-lsp'`. Plugin's binary-name expectation not met on PATH.
2. **First fix attempt (`dae321e`):** added `bin\kotlin-lsp.cmd` wrapper. Failed identically — libuv's `uv_spawn` on Windows doesn't consult PATHEXT for `.cmd`/`.bat`, only auto-appends `.exe`.
3. **Real spawn fix (`7628685`):** hard link `bin\kotlin-lsp.exe` → `bin\intellij-server.exe`. Verified `where.exe kotlin-lsp` resolves to the `.exe`; `kotlin-lsp --version` returns `LS-262.4739.0`. Manual invocation works end-to-end.
4. **Second test session:** spawn now succeeds, but server init hits `LSP server 'plugin:kotlin-lsp:kotlin-lsp' timed out after 120000ms during initialization`. Server JVM stays alive after CC's pipe-close — 35-minute zombie consuming 1.5 GB but only ~5% average CPU (NOT actively indexing). `%LocalAppData%\JetBrains\kotlin-server2026.2\` never populated.
5. **Heap bump (uncommitted vmoptions edit):** `-Xms128m → -Xms512m`, `-Xmx2048m → -Xmx6144m`. Zombie killed. Backup at `intellij-server.exe.vmoptions.pre-heap-bump.bak`.
6. **Third test session:** init now completes within timeout (heap bump fixed the perf cliff), but the LSP call returns `'kotlin.Nothing' does not have instances` — an internal analyzer error in kotlin-lsp v262.4739.0. Reaches a type-resolution path that materializes Kotlin's bottom type (`Nothing`) where a concrete type was expected. Likely triggered by KSP-generated code (kotlin-inject `*::class.create()`, SQLDelight `Database.Schema`, Compose Compiler generated state-trackers) appearing in commonMain.

### Why deferred (not abandoned)

The Anthropic plugin's binary-name convention is fixed, the hard-link approach is durable, and the heap requirement is documented. The remaining blocker is **squarely upstream** in JetBrains/kotlin-lsp's analyzer. Nothing on Kiln's side needs to change for the LSP to start working — when upstream fixes the analyzer crash, the existing install will Just Work.

### What's been preserved for the resumption case

- All install steps documented (Tier 0 winget + Tier 1 hard link)
- Heap-bump kept in `intellij-server.exe.vmoptions` (Kiln's scale needs it regardless of upstream fix)
- `kiln/.claude/settings.json` keeps plugin enabled — no churn needed
- `kiln/.mcp.json` dbhub remains active (independent of LSP state)

### Resumption checklist (future session)

When kotlin-lsp ships a release that resolves the analyzer bug, recommended re-test:

1. Download new ZIP from <https://github.com/Kotlin/kotlin-lsp/releases>, replace `C:\Users\chawo\tools\kotlin-lsp\` contents
2. Re-create the hard link: `New-Item -ItemType HardLink -Path bin\kotlin-lsp.exe -Target bin\intellij-server.exe` (re-applies; the prior link is broken by the ZIP overwrite)
3. Confirm vmoptions still has `-Xmx6144m` (the JetBrains ZIP overwrites this; re-apply if needed)
4. Fresh CC session in Kiln → `LSP documentSymbol` on any `.kt` file
5. If it works: update this addendum's status to "RESOLVED"; document the version that fixed it
6. If it fails with the same `kotlin.Nothing` error: keep deferred, post on the upstream tracking issue

### Upstream tracking issues to watch

- <https://github.com/Kotlin/kotlin-lsp/issues> — search for "Nothing does not have instances" + "KMP" + "KSP-generated" before filing a new one
- Release notes for kotlin-lsp v262.4740+ — watch for analyzer fixes in the changelog
- If no existing issue matches, worth filing with a Kiln-sized KMP repro (`./gradlew :app-desktop:assemble` + minimal commonMain file triggering the crash). Kiln is on GitHub public — easy reference repo for upstream reproduction.

### What we got from the exercise anyway

- Documented the Windows-spawn extension-resolution gotcha (`uv_spawn` doesn't see `.cmd`) — applies to any `*-lsp@claude-plugins-official` plugin on Windows. Saves future setups from this rabbit hole.
- Captured Anthropic's plugin convention (slug-as-command-name) in engram memory + this doc. Reusable for the next LSP plugin enable.
- Heap-requirement empirically validated for Kiln's scale — sticks in vmoptions regardless of when the analyzer bug is fixed.
- Three real commits land the install + diagnosis: `7628685` (hard link), the dbhub activation `c5a69a9`, and this addendum.

**For now, dev continues without kotlin-lsp.** Read/Grep/Glob plus the active dbhub MCP carry the day; Phase 2a + skill authoring + project review are unblocked.
