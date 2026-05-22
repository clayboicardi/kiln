# Kiln by Clayworks — Claude Code Project Guide

## What This Is

Kiln by Clayworks is a from-scratch Android + Windows Desktop music player. Personal-use audiophile player + developer portfolio piece. Owner is Clay Haworth (clayboicardi on GitHub) — analytically strong power user who directs AI to build.

**Status as of 2026-05-19 (post-Session-9):** MVP Sessions 5 + 6 + 7 complete. H6 (JNA libFLAC bridge: vendoring + binding + STREAMINFO + decode + 24-bit packing + seek + JvmFlacDecoderImpl) + H5 (JavaSoundPlayerImpl + DesktopAppGraph player wiring) shipped. Both DI graphs (Android + Desktop) now expose the full chain including PlatformPlayer. **48 tests green** (25 :data:library + 23 :audio:playback). **Empirical FLAC smoke vs. Clay's D:\tiddl library: 10/10 decoded successfully.** Pre-MVP gate cleared earlier this day. Repo public at https://github.com/clayboicardi/kiln; CI green on every push. **Next: pick up at [`docs/sessions/2026-05-19-session-10-handoff.md`](docs/sessions/2026-05-19-session-10-handoff.md)** (H7 single-button play + H8 Pixel install — the vertical-slice milestone, ~2-4 hrs).

## Quick Navigation

**Where to find what:**

| If you're being asked to... | Read this first |
|---|---|
| Pick up the next session's work | `docs/sessions/2026-05-19-session-10-handoff.md` (2 pending items, H7 vertical-slice + H8 Pixel install) |
| Understand the design contract | `docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md` |
| Plan or sequence work | `docs/superpowers/plans/2026-05-18-kiln-execution-plan.md` |
| Continue Pre-MVP Research | `docs/decisions/2026-05-18-library-vetting.md` (append-only log) |
| Execute MVP Session 1-3 scaffold | `docs/scaffold/2026-05-18-mvp-session-1-prep.md` |
| Execute MVP Session 4-7 vertical slice | `docs/scaffold/2026-05-18-mvp-session-4-vertical-slice-prep.md` |
| Look up a Named Pattern definition | `docs/reference/2026-05-18-named-patterns-glossary.md` |
| Respond to a tracked risk | `docs/reference/2026-05-18-risk-playbook.md` |
| Pick a test pattern | `docs/reference/2026-05-18-test-infrastructure-cookbook.md` |
| Check perf/Either/logging conventions | `docs/reference/` (performance-budgets, error-handling-patterns, logging-conventions) |
| Understand the pivot context | Engram memory topic keys `strategy/rebuild-pivot`, `architecture/four-pillars`, `patterns/named-forces`, `kiln/design-spec-locked`, `kiln/plan-revised-2026-05-18` |
| Find JAMZ legacy code (the predecessor) | `C:\Users\chawo\Projects\JAMZ!!!\` |

## Identity

- **Project name:** Kiln by Clayworks (Clay's broader brand: Clayworks)
- **License:** Apache 2.0 across all modules
- **Targets:** Android (min SDK 23, compile SDK 36) + Windows Desktop (JVM 21). Mac/Linux/iOS not blocked architecturally but not planned. (minSdk revised 21 → 23 on 2026-05-19; see vetting log Item 1 addendum.)
- **Language:** Kotlin 100% via Kotlin Multiplatform (KMP)
- **UI:** Compose Multiplatform
- **GitHub:** `clayboicardi/kiln` public at https://github.com/clayboicardi/kiln (Apache 2.0). CI: `.github/workflows/build.yml` runs `:app-android:assembleDebug` on Ubuntu + `:app-desktop:assemble` on Windows on every push to main.

## Predecessor

This project replaces JAMZ!!! at `C:\Users\chawo\Projects\JAMZ!!!\`. JAMZ was a Gramophone fork. **Kiln is a clean re-derivation, NOT a continuation. No GPL code from Gramophone is carried over.** Kiln is Apache 2.0, fresh codebase, derived from specs and first principles. Read Gramophone source code only to understand WHAT it does, then close the file and re-derive HOW yourself.

## Hard Rules — Never Do These

- **Don't add `androidx.*` imports to `commonMain` of `:audio:dsp` or `:audio:visualizer`** — Concentric Modules invariant from spec §3.4. Adapters in `androidMain` only.
- **Don't write `if (source is XxxSource)` branches anywhere** — Source Protocol invariant from spec §3.3. If you find yourself wanting to, the interface is wrong; fix the interface.
- **Don't carry over GPL-licensed code from Gramophone** — Apache 2.0 fresh re-derivation only.
- **Don't propose `jflac`, `JustFLAC`, or `nayuki/FLAC-library-Java` for desktop FLAC decode** — Item 9 addendum committed to JNA + vendored Xiph libFLAC 1.5.0 (BSD-3). nayuki is GPL-3.0; jflac is unmaintained + no 24-bit; JustFLAC has no LICENSE file.
- **Don't change soft locks (spec items 2, 4, 7) without explicit "this is a soft-lock revisit because [new variable]" conversation with Clay.**
- **Don't change hard locks (spec items 1, 3, 5, 6, 8, 9)** without strong reason — they ripple through other decisions.
- **Don't batch multiple changes into one commit.**
- **Don't add features beyond the spec's anti-roadmap (§11).** Explicitly cut: Tidal, Spatial Audio, AI/LLM features, cross-device handoff, MIDI controller for EQ, iOS, Linux, macOS, Wear, Tablet-optimized, Auto, Tag editing, Lyrics, Last.fm scrobbling, BT codec readouts, Podcasts.

## Build/Dep Gotchas (discovered MVP Sessions 1-7)

One-liners that would have prevented friction this past session. Skim before scaffold/build work.

- AGP 9.0 dropped `org.jetbrains.kotlin.android` plugin — Kotlin support is built-in to AGP. Don't re-add it.
- AGP 9.0 + KMP requires `com.android.kotlin.multiplatform.library` (NOT `com.android.library`) for `:audio:*`, `:data:*`, `:ui:*` KMP modules. New DSL: `kotlin { androidLibrary { compileSdk = 36; minSdk = 23; namespace = ... } }`.
- Gradle 9.x: `include(":foo")` REQUIRES `./foo/` directory to exist at settings.gradle.kts evaluation. Empty dirs with `.gitkeep` work; non-existent dirs fail the build.
- `jvm("desktop")` source sets are `desktopMain` / `desktopTest` (NOT `jvmMain` / `jvmTest`). Use the renamed forms in module build.gradle.kts.
- App modules (`:app-android`, `:app-desktop`) need `implementation(libs.bundles.compose.mp.common)` directly — `:ui:*` modules expose Compose deps as `implementation` (not `api`), so they don't propagate via project deps.
- `:app-desktop` additionally needs `implementation(compose.desktop.currentOs)` for platform-specific Skia + window toolkit jars.
- SQLDelight type-narrows queries with `IS NOT NULL` filters on nullable columns — generates a custom row class (e.g., `SelectRecentlyPlayed` distinct from `Track`). Write a separate mapper for the narrowed type.
- SQLDelight can't parse FTS5 control commands (`'delete-all'`, `'delete'`) inline. Issue those via raw `driver.execute(...)` in LocalLibrarySource, not as labeled `.sq` queries.
- Use `kotlinx.coroutines.flow.transform { rows -> rows.forEach { emit(...) } }` for the `Flow<List<T>> → Flow<T>` bridge. Avoid custom helpers (receiver-inference quirks) and `flatMapConcat` (opt-in stability concerns).
- `kmpalette 4.0.0-beta02` is NOT on Maven Central — only tagged on GitHub. Dep is commented out in `:ui:theme/build.gradle.kts`; resolution deferred to Phase 2a Flight A (JitPack OR roll-our-own per Item 3 addendum).
- **minSdk = 23** (revised from 21 on 2026-05-19; Compose-MP 1.11 components-resources-android requires ≥23). See vetting log "Item 1 addendum: minSdk hard-lock revisit 21 → 23".
- `upgradeUuid = "611fd94b-756e-561d-ba94-af658a225268"` is wired in `kiln.desktop.app` convention. **NEVER MODIFY** — future MSI upgrades depend on stability.
- Clay's music library root is `D:\tiddl` (NOT `%USERPROFILE%\Music`) for any scan-folder default at MVP Session 26-28 Settings UI.
- SQLDelight `IS NULL` filters on already-nullable columns do NOT type-narrow (unlike `IS NOT NULL` which does). Existing examples: `selectByAlbum`, `selectTracksOfPlaylist`, `selectAllForFtsRebuild` — all return the default `Track` row class even with `WHERE deleted_at_ms IS NULL` clauses.
- **SQLDelight `.sqm` migration files are named by SOURCE schema version, not target version.** v1→v2 migration is `1.sqm` (not `2.sqm`). Empirical: naming the file `2.sqm` makes SQLDelight interpret it as a v2→v3 migration against a phantom v2 source and emits the snapshot as `3.db`. Source: https://sqldelight.github.io/sqldelight/latest/multiplatform_sqlite/migrations. Discovered Phase 2a Track A Task 3 (2026-05-22).
- **SQLDelight default dialect (sqlite-3-18) rejects `ON CONFLICT(col) DO UPDATE SET ...` upsert syntax.** Modern upsert needs the explicit `sqlite-3-24-dialect:<sqldelight-version>` dialect dependency. For simple key/value tables with no triggers / FK dependents, `INSERT OR REPLACE INTO ... VALUES (...)` is observationally equivalent — Phase 2a Track A Task 2's settings.sq uses this substitute. Bumping the dialect is a separate scope decision (worth doing if Track C / D need richer UPSERT idioms).
- **SQLDelight `value` column → generated `value_` Kotlin property.** `value` is a Kotlin reserved word; SQLDelight 2.x renames generated properties + named bindings by suffixing `_`. The SQL itself keeps `value`; only the Kotlin call-site uses `value_`. Existing example: `settings.sq` queries access `row.value_` and the generated `upsert(key, value_)` parameter.
- **SQLDelight `schemaOutputDirectory` + `verifyMigrations` are opt-in.** Without `schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))` + `verifyMigrations.set(true)` on the `databases { create("...") { ... } }` block, the `verifyCommonMainKilnDatabaseMigration` task runs but doesn't compare anything (silent no-op). Wired into `:data:library` 2026-05-22 (Phase 2a Track A Task 1).
- **MediaStore.Audio.Media.DATE_MODIFIED is seconds-since-epoch**, not milliseconds. Multiply by 1000L when persisting to the schema's millisecond `file_mtime_ms` column. (Filesystem `Files.getLastModifiedTime().toMillis()` is already ms.)
- **MediaStore.Audio.Media.TRACK encodes disc number** when present — values >1000 are typically "1NNN" form (disc 1, track NNN). Modulo 1000 to extract just the track number. Disc number itself is otherwise not directly available via MediaStore.Audio.Media.
- **MediaStore artist/album use literal `"<unknown>"` (with angle brackets)** as the placeholder for missing metadata. Filter alongside blank-string checks — otherwise an `<unknown>` artist accumulates and wins all sort-name comparisons.
- **jaudiotagger's `header.bitRateAsNumber` returns `Long`** (not Int). The other format methods (`sampleRateAsNumber`, `bitsPerSample`, `trackLength`) return `Int`. Don't blanket `.toLong()` — it warns on bitRateAsNumber.
- **`@Suppress` cannot annotate a constructor-call argument label** — `MediaCols(@Suppress("DEPRECATION") data = ..., ...)` parses as "Only expressions are allowed in this context". Hoist the suppress to the enclosing function or class.
- **ExoPlayer methods are single-thread accessed** via the application looper (default `Looper.getMainLooper()`). All suspend wrappers in `Media3ExoPlayerImpl` use `withContext(Dispatchers.Main.immediate) { ... }`. The constructor itself must be called from the main thread.
- **ItemId namespace contract:** tracks are bare numeric (`"42"`); albums/artists/playlists prefix with `"album:"/"artist:"/"playlist:"`. `LocalLibrarySource.getPlayable()` returns `SourceError.ItemNotFound` for any non-numeric (container) ItemId — only tracks are directly playable; containers must be browsed via `TracksOfAlbum/TracksOfArtist/TracksOfPlaylist` first.
- **The scanner does scan-end FTS5 rebuild** (raw `'delete-all'` + bulk INSERT from `selectAllForFtsRebuild`) rather than per-row maintenance during the walk. Avoids the contentless-FTS5 delete-syntax's old-values requirement. **The delete + bulk-insert run inside a single `db.transaction { }`**, so concurrent readers see either the pre-scan FTS or the post-scan FTS — never an empty index during the rebuild window. (Prior to Session 9 ultrareview fix the delete-all auto-committed outside the transaction → empty FTS during rebuild + permanent FTS corruption on crash; corrected.)
- **kotlin-inject `@Scope` annotation needs `@Target(CLASS, FUNCTION, PROPERTY_GETTER)`.** Without all three, scoping a `@get:Provides` constructor param fails KSP validation. Both `app-android/.../di/Singleton.kt` and `app-desktop/.../di/Singleton.kt` follow this convention; copy when adding new scopes.
- **JdbcSqliteDriver(schema = KilnDatabase.Schema)** auto-creates/migrates the schema via PRAGMA user_version on every connect. No need for the `if (!dbFile.exists()) Schema.create(driver)` first-run guard — SQLDelight 2.x handles it. The schema param is the right idiom for persistent JDBC SQLite.
- **AndroidSqliteDriver.Callback.onOpen runs on every connection open, not just first-time creation.** Putting `PRAGMA foreign_keys = ON` here ensures FKs stay enforced after process restarts. The Callback extends `androidx.sqlite.db.SupportSQLiteOpenHelper.Callback`; SupportSQLiteDatabase is from `androidx.sqlite.db`.
- **Value-class type-tags `@JvmInline value class UserDataDir(val path: Path)` distinguish ambiguous JVM-type DI bindings.** When two `@get:Provides` constructor params would both be `Path`, kotlin-inject can't tell them apart. Wrap each in a distinct `@JvmInline value class` — zero runtime cost, compile-time disambiguation. See `app-desktop/.../desktop/di/DesktopAppGraph.kt`.
- **`abstract val` on a kotlin-inject @Component must have a complete provider chain at KSP time.** Adding an abstract member without a provider chain reachable from constructor params + `@Provides` functions fails KSP. Intentionally omit the abstract member until the impl exists (DesktopAppGraph omits `player` until H5 lands).
- **`Application.onCreate` is main-thread by Android contract** → safe place for `AndroidAppGraph::class.create(applicationContext)` since the Media3ExoPlayerImpl provider eventually runs on main thread (ExoPlayer single-thread-access rule). `KilnApplication` registers via `android:name=".KilnApplication"` in AndroidManifest.
- **Vendored *.dll requires explicit !-exception in .gitignore.** The repo's `.gitignore` line 32 (`*.dll`) was added for future native-build output (AAudio/WASAPI phase-2b). Vendored libraries under `:audio:playback/src/desktopMain/resources/native/**` need !-exceptions or git will silently exclude them, leading to JAR-without-native-lib at runtime. Verify via `git check-ignore -v <path>` — empty output = NOT ignored = good.
- **Kotlin packages can't safely use `native` as a segment** (Java reserves `native` as a keyword for native methods). Use `nativeio` (or similar) instead. Existing example: `audio/playback/src/desktopMain/.../nativeio/`.
- **JNA Windows-canonical library names drop the `lib` prefix.** `Native.load("FLAC", ...)` looks for `FLAC.dll` on Windows, NOT `libFLAC.dll`. When vendoring Xiph's `libFLAC.dll`, extract the temp copy as `FLAC.dll` so JNA's name resolution finds it. NativeLibraryLoader does this rename automatically.
- **JNA callbacks need strong references for the lifetime of the native handle.** libFLAC stores raw C function pointers; if the JNI wrapper objects GC mid-decode, the next callback invocation crashes the JVM. Store callbacks as fields on the decoder's owner class (`JvmFlacDecodedStream` does this for write/metadata/error callbacks).
- **libFLAC's STREAMINFO union starts at offset 16 in FLAC__StreamMetadata.** Header layout: type(4) + is_last(4) + length(4) = 12, plus 4 bytes alignment padding (the union's largest natural alignment is 8 for uint64 total_samples). Use `metadataPtr.share(16)` to point a JNA Structure at the union body.
- **libFLAC delivers samples as FLAC__int32 regardless of source bit depth.** Pack down to LE bytes per the declared bit depth. For 24-bit: take low 3 bytes (sign preserved via upper-byte mirroring — libFLAC sign-extends bit 23 into bits 24-31). Bulk read via `Pointer.getIntArray(0, blocksize)` per channel before interleaving — ~10× faster than per-sample getInt at 4096 blocksizes.
- **`ffprobe + FLACs with embedded album art emit duplicate keys.** Without `-select_streams a:0`, ffprobe's default-format output runs through BOTH audio + picture streams, and the picture-stream's bit-depth (often 8) silently overwrites the audio's via key-collision. Always pin `-select_streams a:0` for FLAC introspection in scripts/tests.
- **JUnit 4 @Test methods must return Unit.** `fun foo() = runBlocking { assertNotNull(x) }` breaks because assertNotNull returns T (the non-null value). Use `assertTrue(x != null, ...)` or `assertEquals(...)` (both return Unit) instead, OR add an explicit trailing `Unit`.
- **Kotlin smart-cast can fail through `if (X !is Either.Right)` + lambda-containing branches.** `val r = X(); if (r !is Either.Right) { log.w { ... }; return }; r.value` may not smart-cast because the lambda in `log.w { ... }` defeats cast tracking. Use `when (X()) { is Either.Left -> { ...; return }; is Either.Right -> r.value }` — when-as-expression has cleaner smart-cast propagation.
- **Kotlin's `internal` is module-scoped — separate Gradle modules can NOT access each other's `internal` members.** For DI graphs in `:app-desktop` consuming impls in `:audio:playback`: expose public top-level factory functions returning the public interface type, keeping the impl class itself internal. Existing pattern: `createJvmFlacDecoder(): Decoder` + `createJavaSoundPlayer(...): PlatformPlayer`.

## Workflow

1. **Session start:** Read this CLAUDE.md, then the plan §11 session-start checklist
1a. **Pre-scaffold gate (until passed):** Pre-MVP Research is complete. Before any `gradle/`, `build-logic/`, or module code work — verify Clay has reviewed + acknowledged Pre-MVP decisions per plan §2.2. See `docs/scaffold/2026-05-18-clay-action-items.md`. If unsure: ask.
2. **One change at a time** — each commit small enough to test independently
3. **Build and verify after every change** — when Gradle is set up, run `:app-android:assembleDebug` and `:app-desktop:run` to confirm
4. **Commit after each working change** with a descriptive message
4a. **Push at session-close (not mid-session).** CI runs on every push to main (Ubuntu Android + Windows Desktop). Single push per session is the cadence; individual commits land local first.
5. **Save engram memory entries** for decisions, discoveries, gotchas, convention establishments
6. **Session end:** update `docs/sessions/YYYY-MM-DD-session-N.md` (create the directory on the first session that warrants it)

**Canonical session-validation build:** `./gradlew :app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest` — both apps + load-bearing libs + tests. Run after every logical change set; expect ~5-15s incremental.

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
- **Engine-Swap-Shaped Boundary** — `PlatformPlayer` is shaped so MVP's Media3/Java Sound can swap to Phase 2b's AAudio/WASAPI without consumer churn (vetting Item 13)
- **Capability Flags** — `SourceCapabilities` struct replaces type-discrimination (`if (source is XxxSource)`) for source-feature dispatch
- **Append-only Decision Log** — vetting log + decision docs are append-only; addendums for status updates (Item 9 addendum is the canonical example)

## Tool Usage Priorities

1. **API/library lookups:** Context7 first (e.g., `/jetbrains/compose-multiplatform`, `/androidx/media`, `/adrielcafe/voyager`, `/arkivanov/decompose`), then web search
2. **Library version + maintenance verification:** `gh api repos/<org>/<repo>/releases?per_page=5` + `gh api repos/<org>/<repo>` for authoritative dates + license + last-push (faster than WebFetch on GitHub UI; WebFetch can hallucinate dates)
3. **Complex architectural calls:** Use `/octo:debate` or Gemini second-opinion (`~/.claude/scripts/ask-gemini.sh`) for cross-LLM verification on high-stakes decisions
4. **Cross-validate library stack against `slackhq/circuit` libs.versions.toml** — Slack's KMP/Compose-MP stack mirrors Kiln's planned stack; quick sanity check via `gh api repos/slackhq/circuit/contents/gradle/libs.versions.toml`
5. **Append-only decision discipline:** Update the decision log in append-only style — don't edit prior entries; add new ones below or as addendums (see Item 9 addendum for the canonical pattern)
6. **Avoid reading Gramophone GPL source** — re-derive from specs/first principles. Gramophone reference is for behavior matching only, never code copying.

## Hardware reference

- **Owner's primary device:** Pixel 10 Pro XL (USB-C-to-AUX dongle for speakers)
- **Owner's desktop:** Windows 11 (i5-13400F, RTX 4060 8GB, 32GB DDR5)
- **JDK:** Temurin JDK 21 (NOT JBR — JBR causes TLS/SSL issues with Gradle, per JAMZ-learned lesson)
- **Android SDK:** `C:\Users\chawo\AppData\Local\Android\Sdk` (when Android dev becomes active)

## Effort budget

580-1015 hrs original → **812-1222 hrs revised** after 2026-05-18 Gemini adversarial critique. Phase progression: MVP-1.0 (305-435h) → Phase 2a JAMZ-parity-minus-Tidal (130-195h) → Phase 2b Spec Sheet + libs + low-latency (205-310h) → Phase 3 room correction (150-250h). No fixed timeline.
