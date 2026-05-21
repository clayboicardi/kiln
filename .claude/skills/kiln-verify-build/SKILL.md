---
name: kiln-verify-build
description: This skill should be used when the user asks to "verify the build", "run the canonical build", "check that Kiln still compiles", "run the session-validation build", "verify before commit", "verify before push", or after any logical change set to Kiln's Kotlin source, Gradle config, or `gradle/libs.versions.toml`. Wraps the 5-target canonical Gradle invocation (`:app-android:assembleDebug` + `:app-desktop:assemble` + `:data:library:build` + `:audio:playback:build` + `:data:library:desktopTest`), parses output into per-target pass/fail, surfaces `file:line:column` for Kotlin compile errors, and pulls test counts from the JUnit XML reports. Returns exit 0 if every target succeeds, non-zero otherwise.
---

# kiln-verify-build

Run Kiln's canonical session-validation build and surface the result as a structured summary instead of a 200-line Gradle log scroll. The five targets match CLAUDE.md's "canonical session-validation build" line verbatim — drift between this skill and CLAUDE.md is a bug.

## When to invoke

- After any Kotlin source change (commonMain/androidMain/desktopMain) — per CLAUDE.md workflow §3.
- Pre-commit / pre-push verification.
- After bumping anything in `gradle/libs.versions.toml`, `build-logic/`, or `*.gradle.kts`.
- When unsure if a partial edit broke compilation across modules.

## When NOT to invoke

- For doc-only edits (`docs/**`, `README.md`, `.gitignore` exclusions of doc-only paths).
- When a Gradle build is already in flight (the skill aborts if it detects `.gradle/.lock` or `build-locks/`).
- Mid-edit, before saving — finish the edit first.

## Invocation

From the repo root (or any subdirectory — the script resolves `git rev-parse --show-toplevel`):

```powershell
pwsh -File .claude/skills/kiln-verify-build/scripts/run-verify.ps1
```

Equivalent shortcut via `just`:

```powershell
just verify
```

The `just verify` form calls `gradlew` directly and prints raw output; this skill's wrapper adds the parsed summary on top. Prefer this skill's wrapper when capturing the result for a structured report.

### Flags

| Flag | Effect |
|---|---|
| `-Targets <list>` | Override the 5-target default. Pass as a PowerShell array, e.g. `-Targets @(':data:library:build',':audio:playback:build')`. |
| `-Clean` | Run `./gradlew clean` first. Slows the build to a full cold rebuild — use only after a suspected stale-cache failure. |
| `-Json` | Emit structured JSON to stdout (instead of the human-readable summary). |
| `-NoTests` | Skip `:data:library:desktopTest`. Faster compile-only verification; use only when you've already confirmed tests independently. |

### Output shape

Default = human-readable summary printed to stdout, exit code carries pass/fail. Example pass:

```
==========================================
Kiln Verify-Build summary
==========================================
Verdict:    PASS
Duration:   12847 ms
Targets:
  PASS  :app-android:assembleDebug
  PASS  :app-desktop:assemble
  PASS  :data:library:build
  PASS  :audio:playback:build
  PASS  :data:library:desktopTest  (41/41 tests)
Errors: 0
==========================================
```

`-Json` emits a single JSON document with `build`, `duration_ms`, `targets[]`, and `errors[]`. See `scripts/run-verify.ps1` header for the JSON shape contract.

## Procedure (what the script does)

1. Resolve repo root via `git rev-parse --show-toplevel`. Abort if not in a git repo or if `gradlew.bat` is missing.
2. Detect an in-flight build via `.gradle/.lock` or `.gradle/build-locks/`. Abort with exit code 3 if found.
3. If `-Clean` was passed, run `./gradlew clean` and stream output to the terminal.
4. Invoke Gradle with `--console=plain --warning-mode=none -Dorg.gradle.welcome=never` to suppress decoration that would confuse the parser. Capture stdout + stderr.
5. Hand the captured output array to `scripts/parse-gradle.ps1`, which returns a hashtable.
6. Pull test counts from `**/build/test-results/desktopTest/TEST-*.xml` if a `desktopTest` target ran.
7. Emit JSON or human summary; exit with the underlying Gradle exit code.

## Scripts

- **`scripts/run-verify.ps1`** — main entry. Composes the Gradle command, captures output, dispatches to the parser, prints the summary, exits with Gradle's code.
- **`scripts/parse-gradle.ps1`** — parses captured Gradle plain-console output into a structured hashtable. Detects per-target `FAILED` markers, the overall `BUILD SUCCESSFUL`/`BUILD FAILED` verdict, and Kotlin compiler `e: file://...:line:col message` lines. Also scans `**/build/test-results/desktopTest/TEST-*.xml` for test counts when a desktopTest target is present.

The parser is invokable standalone for an already-captured log:

```powershell
$lines = Get-Content build.log
pwsh -File .claude/skills/kiln-verify-build/scripts/parse-gradle.ps1 -Output $lines -Targets @(':app-android:assembleDebug', ':app-desktop:assemble', ':data:library:build', ':audio:playback:build', ':data:library:desktopTest') -Duration 0 -ExitCode 0 | ConvertTo-Json -Depth 5
```

## Exit codes

| Code | Meaning |
|---|---|
| 0 | `BUILD SUCCESSFUL` — every target passed. |
| 1 | `BUILD FAILED` — at least one target reported failure; see `errors[]` for file:line. |
| 2 | Pre-flight failure (no `gradlew.bat`, not in a git repo, etc.). |
| 3 | A Gradle build is already in flight (lock file present). |

## Acceptance criteria (from the tooling-recommendation spec)

- Against `main` post-Session 9 fixes: returns exit 0, all 5 targets pass, desktopTest count matches the current baseline (41 in `:data:library` as of 2026-05-21).
- Introducing a deliberate compile error to a `:data:library` source file: exits 1, the offending `file:line:column message` appears in `errors[]`.
- Incremental run on a hot Gradle daemon completes in well under 20 seconds.

## Known limitations

- Gradle's plain-console output does not always reliably attribute durations to individual tasks. The skill reports total wall-clock duration; per-target durations populate only when Gradle emits them (typically on cold builds).
- Test counts come from JUnit XML reports. If no `desktopTest` target ran or the XML directory is empty (e.g., a compile failure before tests ran), `tests_run` is omitted.
- The script assumes Windows + PowerShell 7+ (`pwsh`). It will work in WSL with minor path adjustments, but the canonical invocation surface is Windows-native.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `gradlew.bat not found` exit 2 | Not at repo root, or `gradlew` was deleted | `cd C:/Users/chawo/Projects/kiln` and check `gradlew.bat` exists |
| `Gradle build in progress` exit 3 | Another shell is running Gradle (often IntelliJ daemon) | Either wait, or run `./gradlew --stop` and retry |
| `BUILD SUCCESSFUL` but exit 1 | Skill bug — parser disagreed with Gradle | File a bug in this skill's git history; run with `-Json` and attach the output |
| All targets pass, tests show 0/0 | Compile failure prevented tests from running, OR the XML report dir was cleaned | Check `data/library/build/test-results/desktopTest/` for XMLs |
