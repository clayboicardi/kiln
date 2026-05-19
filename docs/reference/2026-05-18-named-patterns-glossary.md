# Named Patterns Glossary

**Date:** 2026-05-18 (living reference; updated as new patterns are named)
**Type:** Reference (not a decision log; multiple sessions append/refine)
**Source:** Consolidates pattern vocabulary from [`../../CLAUDE.md`](../../CLAUDE.md), [spec §2](../superpowers/specs/2026-05-18-kiln-rebuild-design.md), [plan §9](../superpowers/plans/2026-05-18-kiln-execution-plan.md), [vetting log](../decisions/2026-05-18-library-vetting.md).

This file is the canonical definition for every Named Pattern in Kiln's working vocabulary. Patterns are debugging handles, not decoration — using them by name shortens decision-making in future sessions. When a session detects a pattern in play, calling it by name + applying its action triggers consistent behavior across Clay, Claude, and any future collaborator.

---

## How to use this glossary

**Reading mode:** treat each entry as a 3-part contract:
1. **Definition** — what the pattern means in plain language
2. **In play when** — the detection sentence; if you can answer "yes" you're inside the pattern
3. **Action** — what to do when you notice you're inside the pattern

**Writing mode:** if you find yourself naming a pattern that isn't here, add an entry. Pattern naming earns its keep when it has at least 3 instances of use across the codebase or docs.

**Don't write:** patterns for the sake of patterns. The 3-instance threshold from Clay's Evidence-Grounded Governance trait applies here too.

---

## Strategic / portfolio patterns

### Software-as-Self-Portrait

**Definition:** Kiln serves two purposes simultaneously — the player Clay actually uses daily, AND the engineering artifact that demonstrates how Clay builds. Every decision is evaluated through both lenses; one without the other is incomplete.

**In play when:** A decision can be made cheaper/faster OR more architecturally clean, and the trade-off is real.

**Action:** Pick the architecturally cleaner option unless the user-facing cost is material. The portfolio narrative is load-bearing — half-built modules with clean shape signal "this dev knows architecture"; quick hacks with shipped features signal "this dev knows velocity." Kiln biases toward the former because the audience for portfolio review will judge structure, not feature count.

**Examples:**
- Concentric Modules invariant (spec §3.4) — `:audio:dsp` stays platform-free in commonMain even though a quick androidx import would be cheaper
- Engine-swap-shaped boundary at MVP (Item 13) — abstraction cost ~10-15 hrs even though MVP may never use the alternative engine
- Pre-MVP Research as a formal phase — Pre-MVP could be skipped and ad-hoc'd; doing it as a documented phase IS part of the portfolio

**Cross-refs:** plan §1 motivation; CLAUDE.md "Named Patterns" section; spec §1 anchor.

---

### Personal OS for Listening

**Definition:** Kiln is not just a music app — it's an integrated listening environment Clay inhabits. The Hardware Spec Sheet, room correction, EQ, library views, system integration, blurred album art are all parts of one organism. Integration points between modules matter more than individual feature polish.

**In play when:** A new feature is being scoped and could be built either as a self-contained unit or as a deeper integration with existing surfaces.

**Action:** Bias toward the deeper integration — but only when the integration earns its keep (user-visible value). Don't integrate for integration's sake. Example: the FFT visualizer is much more compelling integrated into Now Playing's background than as a separate "visualizer" screen.

**Examples:**
- Now Playing surface absorbs blurred album art, EQ curve overlay, visualizer, queue context, Circuit Presenter showcase — one screen does five things coherently
- Hardware Spec Sheet replaces a conventional About screen — uses an existing slot in a deeper way
- Kiln Dynamic theming bleeds across library + Now Playing + Settings + About when music plays — total immersion not compartmentalized features

**Cross-refs:** spec §5.5 (Dynamic theming behavioral notes); plan §3.2 Sessions 12-15 (Now Playing integration).

---

### Mastering Engineer's Apartment

**Definition:** Kiln's aesthetic frame — clinical instruments arranged with care, not sterile lab. The Kiln Warmth idle palette (warm near-black, warm cream, kiln-fired clay red) embodies this. Plex Sans + Plex Mono. Generous whitespace. Data readouts with units (`24/96`, `+0.3 dB`).

**In play when:** Making a design call about typography, color, layout density, or visual ornamentation.

**Action:** Keep clinical. Reject decorative flourishes. Embrace data readouts and technical labels. Never trade legibility for aesthetics. Body text primary `#F5EBE0` NEVER changes — it's the readability anchor (spec §5.4).

**Examples:**
- Display formats: `FLAC — 24/96 — 6:42 — +0.3 dB ReplayGain` — clinical, data-dense
- Kiln Warmth idle palette — warm but not playful
- Hardware Spec Sheet About screen — DSP internal bit-depth, filter algorithms, pipeline latency listed like hi-fi product specs

**Cross-refs:** spec §5 design system in full.

---

## Architectural / invariant patterns

### Concentric Modules

**Definition:** The module dependency graph has an inner core (`:audio:dsp`, `:audio:visualizer`) that is platform-free Kotlin with zero `androidx.*` imports. Outer rings (`androidMain` adapters) add platform deps. Strict invariant on inner modules.

**In play when:** Adding any new dependency to an `:audio:*` module, OR considering an `androidx` import in `commonMain`.

**Action:** Hard reject. The adapter exists in `androidMain` for exactly this reason — it does not "save" a wrapper class. If a PR adds `androidx.*` to `commonMain` of either inner module, fix the PR before merge.

**Examples:**
- `:audio:dsp/src/commonMain/` has only `kotlin-stdlib` + `arrow-core` (showcase)
- Media3 `BaseAudioProcessor` wrappers live in `:audio:dsp/src/androidMain/`
- Java Sound integration lives in `:audio:dsp/src/jvmMain/`

**Why this matters for the portfolio:** these modules will eventually publish to JitPack as standalone libraries (Phase 2b Flight G). They become unpublishable the moment they pick up an `androidx` dep.

**Cross-refs:** spec §3.4 (strict invariant); plan §4 Flight G (library extraction); CLAUDE.md Hard Rules section.

---

### The Source Protocol

**Definition:** A single `MusicSource` interface in `:data:library/commonMain` plus a `SourceCapabilities` flag struct. All source-specific behavior dispatches through this interface; no `if (source is XxxSource)` branches anywhere in the codebase.

**In play when:** Building a feature whose behavior should vary by source type (e.g., "show genre browser only if source has genres").

**Action:** Add a capability flag to `SourceCapabilities` if one doesn't exist. Branch on the flag, never on the source's concrete type. If you find yourself wanting `if (source is LocalLibrarySource)`, the interface is wrong; fix the interface.

**Examples:**
- `source.capabilities.canBrowseByGenre` not `source is LocalLibrarySource`
- `source.capabilities.supportsOfflineCache` for streaming-source features
- `Decoder.supports(codec)` for decoder selection, not `if (player is JavaSoundPlayer)`

**Why this matters:** the interface preserves optionality. Today only `LocalLibrarySource` exists; tomorrow Subsonic/Navidrome might. Source-type branches accumulate and make future expansion painful.

**Cross-refs:** spec §3.3; CLAUDE.md Hard Rules; vertical-slice prep [§2 + §3](../scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md).

---

### Engine-Swap-Shaped Boundary

**Definition:** The `PlatformPlayer` interface in `:audio:playback/commonMain` is shaped so the underlying audio engine can swap behind it. MVP uses Media3 ExoPlayer (Android) + Java Sound (Desktop); Phase 2b Flights H+I MAY replace these with AAudio MMAP + WASAPI. The boundary is built into MVP at ~10-15 hrs cost to preserve the option without committing to building the alternatives.

**In play when:** Adding a method to `PlatformPlayer` or its implementations; considering exposing an ExoPlayer-specific or Java-Sound-specific concept.

**Action:** Keep `PlatformPlayer` shape engine-agnostic. If a method requires engine-specific knowledge, ask: would AAudio/WASAPI need a meaningfully different shape? If yes, refactor toward a shape both could satisfy. If no, the abstraction may be over-engineered and a leaky abstraction is fine.

**Examples:**
- `loadQueue(items, startIndex)` — engine-agnostic
- `addAudioProcessor(processor)` — engine-agnostic (processors apply equally to both)
- An ExoPlayer-specific renderer-factory configuration call — wrong; route via constructor injection of platform-specific impls

**Cross-refs:** vetting Item 13; vertical-slice prep [§4](../scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md#4-platformplayer--the-engine-swap-shaped-boundary).

---

### Capability Flags

**Definition:** When a polymorphic interface has implementations with materially different feature sets, capabilities are expressed as a flag struct on the interface rather than as type-discriminator branches. Replaces "is this a XxxSource?" with "does the source support Y?"

**In play when:** Designing any interface where implementations may have different feature subsets.

**Action:** Add a `Capabilities` data class on the interface. Default flags to the conservative ("works everywhere") values; override per implementation. Consumers branch on flags, not on type.

**Examples:**
- `SourceCapabilities.canBrowseByGenre` — local source may not implement genres at MVP; future Subsonic would
- `SourceCapabilities.supportsOfflineCache` — local always has files; streaming may cache
- `Decoder.supports(codec)` — different decoders handle different codec sets; consumers ask "can you?"

**Cross-refs:** vertical-slice prep [§2.5](../scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md#25-capability-flags--the-polymorphism-alternative-to-source-type-discrimination); Source Protocol entry above.

---

## Process / discipline patterns

### Bus-Factor-of-One

**Definition:** Clay is the only developer. Every line of code must be readable to future-Clay in 6 months without the present-session context. Modules must pass an "explain in 200 words" test before extraction as published libraries.

**In play when:** Writing code that uses an idiom, abstraction, or language feature that's load-bearing for understanding the function. Or considering whether to add a layer of indirection.

**Action:** Choose the option that future-Clay can read cold. Add a comment only when the WHY is non-obvious. Don't write "explanation" comments for what the code already says. Prefer named functions over inline magic.

**Examples:**
- `@JvmInline value class SourceId(val value: String)` over raw `String` — compile-time safety AND readability
- `BrowseScope.RecentlyAdded(pageSize: Int = 50)` over a magic `(scope = "recent", limit = 50)` map — readable signature
- JNA Kotlin interface over raw JNI C wrapper — stays in Kotlin/JVM territory

**Cross-refs:** plan §9 trap-detection prompts (session-start checklist item); CLAUDE.md Named Patterns.

---

### Architecture as Performance Art

**Definition:** The satisfaction of polishing module structure, Gradle configuration, and README aesthetics is real but can absorb feature-work hours. Each session has a finite budget; "polish mode" vs "feature mode" must be scheduled consciously.

**In play when:** A session is being framed as "let me clean up the Gradle config" or "let me improve this README" without a downstream feature pulling on it.

**Action:** Switch to a feature/shipping task. Polish work earns its keep ONLY when (a) it directly serves a feature shipping in the next few sessions, OR (b) it's an end-of-phase polish session explicitly scheduled. Otherwise it's avoidance.

**Examples:**
- Pre-MVP Research was scheduled as a formal phase — polish-as-feature, legitimate
- A "let me reorganize :ui:components" mid-session detour during Session 6 (playback work) — Architecture-as-Performance-Art trap; defer
- Sessions 26-28 polish-and-ship phase — explicit slot, legitimate

**Cross-refs:** plan §9 trap-detection prompts; named pattern from kiln/CLAUDE.md.

---

### Curator's Trap

**Definition:** Clay's 39,500-track perfectly-tagged FLAC library is not the canonical music-library use case. Tag completeness, file naming consistency, ReplayGain coverage — Clay has these; most users don't. Assumptions baked from Clay's library will break when `:data:library` is ever published as a library or used by anyone else.

**In play when:** Implementing scanner logic, tag reading, search behavior, or UI that reads track metadata. The implicit question: "does this code assume every track has X?"

**Action:** Make every metadata field optional in the data model. Handle the missing-tag case explicitly with a sensible fallback. Test against synthetic tracks with missing tags, weird Unicode in titles, no album art, no track number, etc. — not just Clay's pristine library.

**Examples:**
- `Track.titleSort` falls back to algorithmic computation if `Artist Sort Name` tag is missing
- Search query must handle FTS5 special chars (user input != Clay's clean library)
- Album art lookup chain: embedded → folder.jpg → null (gracefully degrade)

**Cross-refs:** plan §9 trap-detection prompts; schema sketch §2 (every field nullable where reasonable).

---

### Termux Tax (historical)

**Definition:** A pattern that was active before 2026-05-18 — the silent compounding cost of Python-subprocess dependency for the Tidal integration via Termux on Android. Tidal was cut on 2026-05-18 after Gemini adversarial critique flagged Android 14+ background-process fragility + Clay's usage assessment.

**In play when:** A future feature requires a non-Kotlin/JVM subprocess on Android (Python, native binary, etc.) for ongoing operation.

**Action:** Treat as a major risk. Tidal-via-Termux taught: Android's background-process model is hostile to such patterns. Prefer all-Kotlin/KMP alternatives even at higher implementation cost.

**Status:** Pattern name retained as vocabulary; historical only.

**Cross-refs:** spec §11 anti-roadmap; plan §3.2 (Tidal removed); CLAUDE.md Named Patterns (historical note).

---

### Append-only Decision Log

**Definition:** The vetting log + future decision documents are append-only. Never edit prior entries; add new ones below or in an addendum. Each decision has Question / Method / Findings / Decision / Status structure.

**In play when:** Updating a decision document after the original entry's status has changed (e.g., Item 9 going from PARTIALLY DECIDED to fully DECIDED after the FLAC decoder deep-dive).

**Action:** Append a new section (e.g., "Item 9 follow-up addendum"). Reference the original entry's commit hash. The original entry stays as a historical record of what was known at that time; the addendum captures the update with its own date and provenance.

**Examples:**
- Item 9's PARTIALLY DECIDED → fully DECIDED via the FLAC decoder deep-dive addendum
- Session 2 summary appended below Session 1 summary
- Future MVP-session-N closeout notes append to `docs/sessions/`

**Why this matters:** prevents history rewrites that lose context. A reader returning to the project after months can read the docs chronologically and see how decisions evolved.

**Cross-refs:** CLAUDE.md Workflow section ("Save engram memory entries for decisions"); plan §11 session handoff protocol.

---

## When to update this glossary

- A new pattern is named in conversation or in a decision doc and recurs in 3+ contexts
- An existing pattern's definition is refined based on new variables
- A pattern becomes historical (like Termux Tax) — mark "historical" status, don't delete

To add a pattern: open a PR with a new entry following the Definition / In play when / Action / Examples / Cross-refs format.

---

End of glossary.
