---
name: kiln-session-handoff
description: This skill should be used when the user asks to "write a handoff", "draft the session handoff", "close out the session", "generate the next-session pickup", or at the end of any working session per the plan §11 closeout checklist. Pulls the commit log since the prior session's handoff, in-flight items, decisions made (from commit-body trailers when present), and discovered gotchas, then produces `docs/sessions/YYYY-MM-DD-session-N-handoff.md` as a fully-populated skeleton that a fresh CC instance can pick up cold. Reads — never writes — `git log`, `git status`, `CLAUDE.md` diffs, and `docs/sessions/`.
---

# kiln-session-handoff

Generate the session handoff doc skeleton for the next CC session. Replaces the ~15-minute manual closeout chore at session end with a single command that pulls the structured data from git + the docs tree.

## When to invoke

- At session close, after `kiln-verify-build` passes and the final commit batch is ready.
- Whenever Clay wants to checkpoint and resume cold later.

## When NOT to invoke

- Mid-session (the handoff would be incomplete and re-running overwrites unless `-Force`).
- For sessions with no commits since the prior handoff (nothing to hand off; running it produces a near-empty skeleton).
- When a handoff for the target session number already exists (the script refuses to overwrite unless `-Force` is passed).

## Invocation

From any directory inside the Kiln working tree:

```powershell
# Generate the Session 12 handoff (closes Session 11)
pwsh -File .claude/skills/kiln-session-handoff/scripts/generate-handoff.ps1 -SessionNum 12

# With a one-line goal-summary to seed the doc
pwsh -File .claude/skills/kiln-session-handoff/scripts/generate-handoff.ps1 -SessionNum 12 -Summary 'Phase 2a Track F shipped — desktopTest now gates merges'

# With explicit in-flight items
pwsh -File .claude/skills/kiln-session-handoff/scripts/generate-handoff.ps1 -SessionNum 12 -InFlight 'H1: extend CI yaml to call test-desktop','H2: regression-check on Pixel'

# Overwrite existing
pwsh -File .claude/skills/kiln-session-handoff/scripts/generate-handoff.ps1 -SessionNum 12 -Force
```

### Flags

| Flag | Effect |
|---|---|
| `-SessionNum <N>` | Required. Session number being CREATED (i.e., the next session, not the one closing). |
| `-PrevSession <N>` | Override the auto-detected previous-session number. |
| `-Summary <text>` | One-line summary that seeds the doc's "What this session did" section. |
| `-InFlight <items>` | Array of in-flight item titles. Otherwise pulled from commit-body `todo:` trailers + repo `// TODO(session-N+1)` comments. |
| `-Force` | Overwrite an existing handoff doc. Default refuses to overwrite. |

## Output

Writes `docs/sessions/YYYY-MM-DD-session-<N>-handoff.md` (date = current date in local TZ). Prints the absolute path. Exits 0 on success, 1 if the file already exists and `-Force` was not passed.

## How the data gets gathered

1. **Previous session detection.** Scans `docs/sessions/*-handoff.md`, parses session numbers, picks the highest unless `-PrevSession` is given.
2. **Commit range.** Uses the previous handoff doc's `git log -1 --format=%H -- <handoff-path>` (when committed; falls back to file mtime + `git log --until`) to determine where the prior session closed. Pulls commits in that range via `git log --pretty=format:'%H%n%h%n%an%n%s%n%b%n%xE5%xE5%xE5'` (the `%xE5%xE5%xE5` delimiter is a unique multi-byte sentinel).
3. **Trailer parsing.** Each commit body is scanned line-by-line for soft trailers:
   - `decision: <one-line>` → "Decisions made" section
   - `gotcha: <one-line>` → "Gotchas discovered" section
   - `todo: <one-line>` → "In-flight items"
4. **CLAUDE.md diff.** Runs `git diff <prev-session-commit>..HEAD -- CLAUDE.md` and pulls newly-added lines under the "Build/Dep Gotchas" or "Hard Rules" sections.
5. **Working tree state.** Captures `git status --short`, `git stash list`, `git rev-parse --abbrev-ref HEAD`, and origin sync state via `git rev-list @{u}...HEAD`.
6. **Pending TODOs.** Greps for `// TODO(session-` markers in the source tree (Kotlin + .gradle.kts files).

## Output template structure

The generated handoff doc follows this section order (matching the prevailing convention from sessions 7-11):

1. Title + meta header (date, from/to session, closing commit SHA)
2. **🚀 Pre-flight** — read order + clean-baseline confirmation snippet
3. **Where we are** — repo state (branch, HEAD, CI green/red, test count from JUnit XMLs)
4. **Session N-1 outputs** — commit table since the prior handoff
5. **Review budget** — placeholder if /ultrareview credits change; manual edit point
6. **In-flight items** — from `todo:` trailers + explicit `-InFlight`
7. **Decisions made** — from `decision:` trailers + manual seed via `-Summary`
8. **Gotchas discovered** — from `gotcha:` trailers + CLAUDE.md diff hints
9. **Working tree state** — git status, stash, branch divergence
10. **Verify-before-starting checklist** — boilerplate per plan §11
11. **References** — plan + spec + prior session links
12. **Copy-paste prompt for the next session** — Kiln-standard prompt-frame

[`scripts/handoff-template.md`](scripts/handoff-template.md) is the literal skeleton with `{{placeholder}}` markers; the generator script substitutes via simple string replacement (no Liquid/Mustache dependency).

## Proposal: soft commit-message trailer convention (Clay-optional)

The handoff doc gets its richest content when commit bodies use structured trailers. Kiln does NOT currently enforce these. Adopting them is reversible and zero-risk: commits without trailers still work — the skill falls back to listing commit subjects only.

### Proposed trailers (use only when applicable to that commit)

```
decision: <one-line>    # architectural call, library choice, soft-lock revisit
gotcha:   <one-line>    # discovered surprise / build issue / library quirk
todo:     <one-line>    # deferred-but-noted item for the next session
```

### Example commit

```
fix(scanner): MediaStore.Audio.Media.TRACK encodes disc number in 1NNN form

The Pixel-side scanner persisted disc=0 + track=1042 for an album the user
expects to display as disc=1 + track=42. MediaStore packs disc-number into
the high digits of TRACK when the source FLAC's TRACKNUMBER tag is empty
but the file metadata sets a disc. Modulo 1000 to extract the track number.

decision: continue trusting MediaStore for the bulk-scan path; SAF in Track B will eventually own disc parsing properly
gotcha:   MediaStore.Audio.Media.TRACK can encode disc number as `1NNN` (disc 1 + track NNN) when both are set
todo:     H2: verify the fix against Clay's full library after next sync
```

The skill's parser handles untrailored commits gracefully — they show up under "Commits since last session" as a flat list. Adopting the trailers does not require any commit-msg hook; CC and Clay add them by convention.

### How to adopt

- No tooling change required.
- Optionally append a line to CLAUDE.md "Workflow" section: *"Commit bodies MAY include `decision:`, `gotcha:`, `todo:` trailers to feed kiln-session-handoff."* — but **the kiln-session-handoff skill does not modify CLAUDE.md itself**, per the project's "never modify CLAUDE.md without explicit revise-claude-md skill invocation" rule. Clay applies the rule via `/claude-md-management:revise-claude-md` separately if desired.

### How to reject

- Don't write the trailers. The skill still works — the handoff just shows commit subjects instead of curated sections.

## Scripts

- **`scripts/generate-handoff.ps1`** — main entry. Argument parsing, data gathering, template substitution, file write.
- **`scripts/handoff-template.md`** — the markdown skeleton with `{{placeholder}}` substitution points.

## Acceptance criteria (from the tooling-recommendation spec)

- Running with `-SessionNum 12` after Session 11 closes produces a sensible `docs/sessions/YYYY-MM-DD-session-12-handoff.md` that a fresh CC instance can read cold.
- Handles the no-trailers case (commits with bare subjects only) — falls back to listing commit subjects without crashing.
- Refuses to overwrite an existing handoff doc unless `-Force` is passed.

## Known limitations

- **Heuristic prev-session detection.** The script uses `docs/sessions/*-handoff.md` filename parsing. If a session was closed without a handoff doc (e.g., a one-commit fix-up session), the range may include extra commits. Use `-PrevSession` to override.
- **Template substitution is line-based.** Multi-paragraph placeholders work, but Markdown formatting inside a placeholder is the author's responsibility.
- **Does not push.** Commits the generated file is the author's responsibility — per CLAUDE.md "Push at session-close (not mid-session)" rule.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| "File already exists" exit 1 | A handoff for this session number is present | Use `-Force` or pick a different `-SessionNum` |
| Empty "Commits since last session" | Prev-session detection grabbed the wrong commit, or no commits have landed since | Pass `-PrevSession` explicitly; or skip the skill if there's nothing to hand off |
| Trailer sections all empty | Commit bodies don't include the proposed trailers | Either adopt the convention going forward, or accept the flat commit-subject listing |
| "git: not a git repository" | Script run outside the Kiln working tree | `cd` into the repo first |
