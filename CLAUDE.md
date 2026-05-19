# Kiln by Clayworks — Claude Code Project Guide

## What This Is

Kiln by Clayworks is a from-scratch Android + Windows Desktop music player. Personal-use audiophile player + developer portfolio piece. Owner is Clay Haworth (clayboicardi on GitHub) — analytically strong power user who directs AI to build.

**Status as of 2026-05-18:** Pre-MVP Research phase. 4 of 12 library-vetting items decided in session 1. Repo scaffolded as docs-only; no Gradle setup yet. MVP Sessions 1-3 (when ready) will set up the Gradle KMP scaffold and produce a "Hello Kiln" running on both Pixel + Windows.

## Quick Navigation

**Where to find what:**

| If you're being asked to... | Read this first |
|---|---|
| Understand the design contract | `docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md` |
| Plan or sequence work | `docs/superpowers/plans/2026-05-18-kiln-execution-plan.md` |
| Continue Pre-MVP Research | `docs/decisions/2026-05-18-library-vetting.md` (append-only log) |
| Understand the pivot context | Engram memory topic keys `strategy/rebuild-pivot`, `architecture/four-pillars`, `patterns/named-forces`, `kiln/design-spec-locked`, `kiln/plan-revised-2026-05-18` |
| Find JAMZ legacy code (the predecessor) | `C:\Users\chawo\Projects\JAMZ!!!\` |

## Identity

- **Project name:** Kiln by Clayworks (Clay's broader brand: Clayworks)
- **License:** Apache 2.0 across all modules
- **Targets:** Android (min SDK 21, compile SDK 36) + Windows Desktop (JVM 21). Mac/Linux/iOS not blocked architecturally but not planned.
- **Language:** Kotlin 100% via Kotlin Multiplatform (KMP)
- **UI:** Compose Multiplatform
- **GitHub:** TBD — repo not yet created; planned name `clayboicardi/kiln` (or similar — Clay's call when scaffolded). For now: local-only git.

## Predecessor

This project replaces JAMZ!!! at `C:\Users\chawo\Projects\JAMZ!!!\`. JAMZ was a Gramophone fork. **Kiln is a clean re-derivation, NOT a continuation. No GPL code from Gramophone is carried over.** Kiln is Apache 2.0, fresh codebase, derived from specs and first principles. Read Gramophone source code only to understand WHAT it does, then close the file and re-derive HOW yourself.

## Hard Rules — Never Do These

- **Don't add `androidx.*` imports to `commonMain` of `:audio:dsp` or `:audio:visualizer`** — Concentric Modules invariant from spec §3.4. Adapters in `androidMain` only.
- **Don't write `if (source is XxxSource)` branches anywhere** — Source Protocol invariant from spec §3.3. If you find yourself wanting to, the interface is wrong; fix the interface.
- **Don't carry over GPL-licensed code from Gramophone** — Apache 2.0 fresh re-derivation only.
- **Don't change soft locks (spec items 2, 4, 7) without explicit "this is a soft-lock revisit because [new variable]" conversation with Clay.**
- **Don't change hard locks (spec items 1, 3, 5, 6, 8, 9)** without strong reason — they ripple through other decisions.
- **Don't batch multiple changes into one commit.**
- **Don't add features beyond the spec's anti-roadmap (§11).** Explicitly cut: Tidal, Spatial Audio, AI/LLM features, cross-device handoff, MIDI controller for EQ, iOS, Linux, macOS, Wear, Tablet-optimized, Auto, Tag editing, Lyrics, Last.fm scrobbling, BT codec readouts, Podcasts.

## Workflow

1. **Session start:** Read this CLAUDE.md, then the plan §11 session-start checklist
2. **One change at a time** — each commit small enough to test independently
3. **Build and verify after every change** — when Gradle is set up, run `:app-android:assembleDebug` and `:app-desktop:run` to confirm
4. **Commit after each working change** with a descriptive message
5. **Save engram memory entries** for decisions, discoveries, gotchas, convention establishments
6. **Session end:** update `docs/sessions/YYYY-MM-DD-session-N.md` (create the directory on the first session that warrants it)

## Named Patterns (vocabulary for decision-making)

Use these labels by name when you observe their force in play. They are debugging handles, not decoration.

- **Software-as-Self-Portrait** — the portfolio narrative is load-bearing; every decision evaluated through both "serves the library" and "serves the architecture story" lenses
- **Personal OS for Listening** — Kiln is an integrated listening environment, not just an app; integration points matter more than individual feature polish
- **Bus-Factor-of-One** — modules must pass "explain in 200 words" test before extraction as published libraries
- **Curator's Trap** — Clay's perfectly-tagged 39,500-track library doesn't generalize; conscious choice required between personal-tool and audience-tool
- **Architecture as Performance Art** — module polish satisfaction is real but can absorb feature-work hours; schedule polish/feature modes consciously
- **Termux Tax** — silent compounding cost of Python-subprocess dependency. _Historical: avoided by cutting Tidal on 2026-05-18. Pattern name retained for vocabulary continuity if Tidal or similar Python-bridged source is ever reconsidered._
- **Concentric Modules** — inner core (`:audio:dsp`, `:audio:visualizer`) is platform-free Kotlin; outer rings add platform deps. Strict invariant on inner modules.
- **The Source Protocol** — `MusicSource` interface + capability flags; no source-specific branching in the codebase
- **Mastering Engineer's Apartment** — aesthetic frame: clinical instruments arranged with care, not sterile lab

## Tool Usage Priorities

1. **API/library lookups:** Context7 first (e.g., `/jetbrains/compose-multiplatform`, `/androidx/media`, `/adrielcafe/voyager`, `/arkivanov/decompose`), then web search
2. **Complex architectural calls:** Use `/octo:debate` or Gemini second-opinion (`~/.claude/scripts/ask-gemini.sh`) for cross-LLM verification on high-stakes decisions
3. **Pre-MVP Research items:** Update the decision log in append-only style — don't edit prior entries; add new ones below
4. **Avoid reading Gramophone GPL source** — re-derive from specs/first principles. Gramophone reference is for behavior matching only, never code copying.

## Hardware reference

- **Owner's primary device:** Pixel 10 Pro XL (USB-C-to-AUX dongle for speakers)
- **Owner's desktop:** Windows 11 (i5-13400F, RTX 4060 8GB, 32GB DDR5)
- **JDK:** Temurin JDK 21 (NOT JBR — JBR causes TLS/SSL issues with Gradle, per JAMZ-learned lesson)
- **Android SDK:** `C:\Users\chawo\AppData\Local\Android\Sdk` (when Android dev becomes active)

## Effort budget

580-1015 hrs original → **812-1222 hrs revised** after 2026-05-18 Gemini adversarial critique. Phase progression: MVP-1.0 (305-435h) → Phase 2a JAMZ-parity-minus-Tidal (130-195h) → Phase 2b Spec Sheet + libs + low-latency (205-310h) → Phase 3 room correction (150-250h). No fixed timeline.
