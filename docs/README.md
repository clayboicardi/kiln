# Kiln by Clayworks — Documentation Index

This directory holds Kiln's authoritative design, planning, decision, and scaffold-prep documents. The project's executable code lives at the repository root; this directory is the human-readable substrate it sits on.

---

## Reading order for new sessions

If you're a new Claude session (or new contributor) opening Kiln for the first time, read these in this order:

1. [`../CLAUDE.md`](../CLAUDE.md) — project orientation, hard/soft locks, named patterns, "where to find what" table
2. [`superpowers/specs/2026-05-18-kiln-rebuild-design.md`](superpowers/specs/2026-05-18-kiln-rebuild-design.md) — the locked design spec (the "why" and architectural invariants)
3. [`superpowers/plans/2026-05-18-kiln-execution-plan.md`](superpowers/plans/2026-05-18-kiln-execution-plan.md) — the execution plan with effort tables, ship cadence, risk register

Once oriented, jump to the current-phase prep doc:
- **Pre-scaffold** (now): read [`scaffold/2026-05-18-clay-action-items.md`](scaffold/2026-05-18-clay-action-items.md) — what Clay needs to do before MVP Session 1
- **MVP Session 1-3** (scaffold): [`scaffold/2026-05-18-mvp-session-1-prep.md`](scaffold/2026-05-18-mvp-session-1-prep.md) — the 16-step scaffold sequence
- **MVP Session 4-7** (library + playback vertical slice): [`scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md`](scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md) — interface sketches for `MusicSource`, `PlatformPlayer`, `Decoder`

---

## Directory layout

```
docs/
├── README.md                          ← you are here
├── superpowers/
│   ├── specs/
│   │   └── 2026-05-18-kiln-rebuild-design.md      Locked design spec
│   └── plans/
│       └── 2026-05-18-kiln-execution-plan.md      Execution plan + risk register
├── decisions/
│   ├── 2026-05-18-library-vetting.md              Pre-MVP Research — 12 vetting items
│   └── 2026-05-18-sqldelight-schema-sketch.md     6-table schema + FTS5 strategy
├── scaffold/
│   ├── 2026-05-18-clay-action-items.md            Pre-scaffold checklist (Clay-only TODOs)
│   ├── 2026-05-18-mvp-session-1-prep.md           Sessions 1-3 actionable scaffold doc
│   ├── 2026-05-18-mvp-session-4-vertical-slice-prep.md  Sessions 4-7 interface sketches
│   └── 2026-05-18-phase-2b-flight-g-library-extraction-roadmap.md  Library publishing roadmap
└── reference/
    ├── 2026-05-18-named-patterns-glossary.md      12 named patterns: definitions + actions
    ├── 2026-05-18-risk-playbook.md                14 risk-trigger response procedures
    ├── 2026-05-18-test-infrastructure-cookbook.md Test tooling: when + how per tool
    ├── 2026-05-18-performance-budgets.md          Concrete numeric perf targets per phase
    ├── 2026-05-18-error-handling-patterns.md      Arrow Either patterns + anti-patterns
    └── 2026-05-18-logging-conventions.md          kermit wiring + level discipline
```

---

## Document purposes

### `superpowers/specs/` — the locked design

Spec is the contractual answer to "what are we building." Hard locks live here. Soft locks live here with explicit revisit triggers.

**Don't edit hard locks (spec §2 items 1, 3, 5, 6, 8, 9) without strong reason.** Soft locks (items 2, 4, 7) require explicit "this is a soft-lock revisit because [new variable]" conversation with Clay.

### `superpowers/plans/` — the execution plan

Plan is the contractual answer to "how do we get there." Per-phase effort tables, session checklists, ship cadence, risk register. Updated as Pre-MVP outcomes materialize (§2.3 added 2026-05-18 post-completion).

### `decisions/` — decision logs

Append-only. Never edit prior entries; add new ones. Each decision has Question / Method / Findings / Decision / Status structure.

Current files:
- **2026-05-18-library-vetting.md** — Pre-MVP Research vetting log (12 items, 2 sessions). Item 9 has an addendum closing out the FLAC decoder choice. Session 2 summary at the bottom is the quick-tally view.
- **2026-05-18-sqldelight-schema-sketch.md** — Schema artifact per plan §2.2 exit criteria. Six tables (artist, album, track, playlist, playlist_track, listening_history) + FTS5 virtual table + index strategy + performance projections.

### `scaffold/` — pre-scaffold synthesis

Derived artifacts that consolidate decisions into actionable form. These are NOT canonical decision documents; they're synthesis. If a decision in a scaffold doc contradicts the vetting log, the vetting log wins.

Current files:
- **2026-05-18-clay-action-items.md** — Clay's pre-scaffold TODO list (repo name, WiX install, JDK confirmation, etc.).
- **2026-05-18-mvp-session-1-prep.md** — Full `gradle/libs.versions.toml` skeleton + per-module dependency assignments + 16-step scaffold sequence for MVP Session 1-3.
- **2026-05-18-mvp-session-4-vertical-slice-prep.md** — Interface sketches for `MusicSource`, `PlatformPlayer`, `Decoder`, supporting types, threading model, FLAC decoder JNA bridge, FTS5 application-managed population pattern.
- **2026-05-18-phase-2b-flight-g-library-extraction-roadmap.md** — Forward-looking roadmap for extracting `:audio:dsp` and `:audio:visualizer` to JitPack/Maven Central per plan §5 Flight G. Pre-positions module-prep checklists, Maven coordinates strategy, Vanniktech publishing setup, semver discipline, README/CHANGELOG/CONTRIBUTING templates, multi-LLM API review process.

### `reference/` — living reference (multi-session updated)

Reference material that isn't a one-shot decision document. Multiple sessions append/refine these as patterns mature.

Current files:
- **2026-05-18-named-patterns-glossary.md** — Canonical definitions for the 9 Named Patterns (Software-as-Self-Portrait, Personal OS for Listening, Mastering Engineer's Apartment, Concentric Modules, The Source Protocol, Engine-Swap-Shaped Boundary, Capability Flags, Bus-Factor-of-One, Architecture as Performance Art, Curator's Trap, Termux Tax historical, Append-only Decision Log). Each pattern: Definition / In play when / Action / Examples / Cross-refs.
- **2026-05-18-risk-playbook.md** — Operational playbook for 14 tracked risks. Per risk: Detection signals / Triage severity / Response steps / Recovery / Document conventions. Aligned with Compliance-First Architect + Algorithmic Operational Discipline traits.
- **2026-05-18-test-infrastructure-cookbook.md** — Expansion of plan §7 timeline with concrete patterns: kotlin.test, kotlinx-coroutines-test, SQLDelight in-memory, Mokkery, Turbine, Kotest property-based, JMH benchmarks, Roborazzi screenshot, Compose UI test, JNA-libFLAC empirical smoke, mutation testing, accessibility audits. Code snippets per pattern.
- **2026-05-18-performance-budgets.md** — Concrete numeric perf targets per phase: library scan, FTS5 latency, LazyColumn frame budget, palette extraction, EQ throughput, FLAC decode, memory ceilings, distribution artifact sizes. Includes target/stretch/fail thresholds and measurement patterns. Updated as empirical data lands.
- **2026-05-18-error-handling-patterns.md** — When to use Arrow `Either<Error, Success>` vs throw, how to compose Either chains, error-type design (sealed hierarchies vs string messages), surfacing errors to UI, anti-patterns to reject in review. Module-by-module guidance for `:audio:dsp`, `:audio:playback`, `:data:library`, `:ui:theme`, `:ui:components`.
- **2026-05-18-logging-conventions.md** — kermit wiring, per-level discipline (Verbose/Debug/Info/Warn/Error/Assert), what to log where per module, tag conventions, lazy-eval forms, hot-path discipline, surfacing debug telemetry to the Hardware Spec Sheet via StateFlows vs logs.

### Future files (not yet created)

As MVP progresses, expect these additions:
- `docs/sessions/YYYY-MM-DD-session-N.md` — per-session closeout notes per plan §11
- `docs/decisions/YYYY-MM-DD-*.md` — new decision entries as Pre-MVP JIT items resolve and new questions surface during MVP work
- `docs/scaffold/YYYY-MM-DD-mvp-session-N-prep.md` — additional vertical-slice prep docs at major architectural transitions

---

## Engram memory

In addition to the markdown documents here, Pre-MVP Research decisions are persisted to engram memory under topic keys:

- `strategy/rebuild-pivot`
- `architecture/four-pillars`
- `patterns/named-forces`
- `kiln/design-spec-locked`
- `kiln/plan-revised-2026-05-18`
- `kiln/library-vetting/item-{1-13}`
- `kiln/library-vetting/item-9-flac-decoder-final` (the addendum)
- `kiln/scaffold-prep`
- `kiln/scaffold-prep/session-4-vertical-slice`

Future Claude sessions can call `mem_search` against these keys to recover context.

---

## Apache 2.0

All content in this directory and the project codebase is licensed under Apache 2.0 per spec §2 item 8 hard lock.
