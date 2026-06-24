# Kiln — Single-Writer DB + WAL (#31, item 3)

**Date:** 2026-06-23
**Author:** Clay Haworth (clayboicardi) with Claude Opus 4.8 (1M context)
**Status:** Design approved (brainstorming, 2026-06-23). Next step: `superpowers:writing-plans`.
**Issue:** [#31 — Scan/analyzer data-integrity hardening (deferred from #30)](https://github.com/clayboicardi/kiln/issues/31)
**Scope:** Item **3** — the structural concurrency fix. Items 1 + 2 shipped in PR #33 (see [`2026-06-22-issue-31-scan-analyzer-data-integrity-design.md`](2026-06-22-issue-31-scan-analyzer-data-integrity-design.md)). This spec **retires the `LibraryWriteLock`** by serializing all writes through a single-writer executor, and **enables WAL** so reads never contend with the writer.

---

## 1. Context

PR #30 introduced `LibraryWriteLock` — a `@Singleton` `kotlinx` `Mutex` injected into both scanners and `TrackAnalysisRunner` — to serialize the writers of the `track` table. The scanner holds it for a whole scan; the analyzer acquires it per DB-op and releases during `analyze()`. It is a **patch**: correctness depends on every writer *remembering* to take the lock. It does not cover `SettingsRepositoryImpl` (a third writer, today **unserialized** — a latent race).

This spec replaces the lock with a structural guarantee: **a single dedicated writer thread that all writes funnel through.** Two writes cannot overlap because only one thread ever performs them. The lock is deleted.

### 1.1 Verified connection model (corrects the Session-24 handoff)

The handoff and engram `#1933`/`#1938` asserted "one shared SQLite connection" on desktop. **This was verified false** against SQLDelight 2.3.2 source (`JdbcSqliteDriver.kt`):

```kotlin
return when {
  path.isEmpty() || path == ":memory:" || path == "file::memory:" ||
    path.startsWith(":resource:") || url.contains("mode=memory") -> StaticConnectionManager(url, properties)  // single shared
  else -> ThreadedConnectionManager(url, properties)  // per-thread ThreadLocal connections
}
```

Our desktop URL `jdbc:sqlite:<path>/kiln.db` has a non-empty path → **`ThreadedConnectionManager`: every thread gets its own `java.sql.Connection`.** Consequences:

- **The scanner↔analyzer corruption** (`SQLITE_BUSY` / lock-contention in `#1933`) is *cross-connection* contention between two writer threads, not shared-connection re-entrancy. Serializing writers onto **one** thread (→ one writer connection) fixes it. **The single-writer executor is therefore still required.**
- **Read-during-write is `SQLITE_BUSY` contention, not dirty reads.** Reader and writer are on separate connections; in the default rollback-journal mode a reader sees the last *committed* state (no half-scanned data), but can throw `SQLITE_BUSY` when the scan's writer holds `EXCLUSIVE` during a cache-spill or commit (notably on full scans). It is **not corruption.** (Some of #28's desktop symptoms could plausibly be this.)
- **WAL is cheap here.** Because separate per-thread connections *already exist*, `PRAGMA journal_mode=WAL` + a `busy_timeout` is a few lines of connection config — **not** a driver rearchitecture. WAL lets the readers proceed against a committed snapshot while the single writer appends, eliminating the contention. (The handoff's "WAL alone buys little / the real work is the connection split" was premised on the false single-connection model.)
- **Tests use an in-memory DB** → `StaticConnectionManager` (single connection), which does **not** reproduce production's multi-connection behavior. See §7.

### 1.2 The writers on the database (full inventory)

| Writer | Today | Notes |
|---|---|---|
| `JvmFilesystemScanner` (desktop) | holds lock, whole scan | one big `db.transaction` + raw `driver.execute` (bulk reset, FTS rebuild) |
| `AndroidMediaStoreScanner` | holds lock, whole scan | calls `AndroidFormatFactBackfill.runOnce()` inside the scan |
| `TrackAnalysisRunner` | lock per-op, released during `analyze()` | item-2 `updateTrackReplayGainIfUnchanged` guard |
| `AndroidFormatFactBackfill` | inside the Android scan (lock-covered transitively) | own per-page `db.transaction` |
| `rebuildFtsIndex` (ScanInternals) | inside the scanners (lock-covered) | `driver.execute('delete-all')` + bulk insert |
| `SettingsRepositoryImpl` | **NOT under the lock** | `db.settingsQueries.upsert` on `Dispatchers.IO` — latent race |

---

## 2. The invariant

> **Every write to `KilnDatabase` executes on the one writer thread, via `DatabaseWriter.write { }`. Reads may run on any dispatcher against their own (WAL) connection.**

Serialization is structural, not by-convention-take-a-lock. The `LibraryWriteLock` `Mutex` is deleted.

---

## 3. Design

### 3.1 `DatabaseWriter` — the single-writer seam

New, in `com.clayworks.kiln.library.db` (commonMain, `:data:library`) — a new `db` package, since it now serves settings as well as scan:

```kotlin
package com.clayworks.kiln.library.db

class DatabaseWriter(
    private val db: KilnDatabase,
    private val writerDispatcher: CoroutineDispatcher,  // a SINGLE-thread dispatcher
) {
    /** Runs [block] on the single writer thread. The block is NON-suspending by
     *  design: it must complete (one full write unit) before the next is dispatched,
     *  which is what guarantees serialization and structurally forbids nesting. */
    suspend fun <T> write(block: KilnDatabase.() -> T): T =
        withContext(writerDispatcher) { db.block() }
}
```

Two deliberate properties:
- **`block` is non-suspending** (`KilnDatabase.() -> T`, not `suspend`). A write unit runs start-to-finish on the writer thread with no internal suspension → no other write interleaves on that thread mid-unit. It also makes **reentrancy structurally impossible** (you cannot call the `suspend fun write` from inside a non-suspend block), and it forces slow suspend work — `analyze()`, `flow.first()` — to live *outside* the serialized section, which is exactly where it belongs.
- **Receiver is the `KilnDatabase`** → call-sites read `writer.write { trackQueries.update… }`.

### 3.2 Writer dispatcher — provisioning

Provided per DI graph, mirroring the existing `audioDispatcher` precedent. **Construct the dispatcher *inside* the `databaseWriter` provider — do not expose it as a separate `@Provides CoroutineDispatcher`**, because desktop already provides a `CoroutineDispatcher` (`audioDispatcher`) and kotlin-inject cannot disambiguate two same-typed bindings (CLAUDE.md DI gotcha):

```kotlin
@Singleton @Provides
protected fun databaseWriter(db: KilnDatabase): DatabaseWriter =
    DatabaseWriter(
        db = db,
        writerDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "kiln-db-writer").apply { isDaemon = true }
        }.asCoroutineDispatcher(),
    )
```

Daemon, process-lifetime — same lifecycle as `audioDispatcher`; no explicit shutdown. The single thread keeps one stable thread-local connection — ideal for a WAL writer.

### 3.3 WAL + `busy_timeout`

Reads then never block on the writer (WAL snapshot reads); the one writer + concurrent readers compose with §3.1.

- **Desktop** (`DesktopAppGraph.sqlDriver()`): add to the `Properties` passed to `JdbcSqliteDriver` alongside `foreign_keys`: `journal_mode=WAL` and `busy_timeout=5000` (5 s). (xerial applies properties to every per-thread connection it opens, so WAL is re-asserted — idempotent, persists — and `busy_timeout`, which is per-connection, is set on all of them.) *Exact xerial property keys confirmed at implementation.*
- **Android** (`AndroidAppGraph.sqlDriver()` `Callback`): enable WAL the **framework-approved** way — `db.enableWriteAheadLogging()` in `onConfigure` (NOT a raw `PRAGMA journal_mode=WAL` via `execSQL`: that does not configure the framework's connection pool for multi-connection read/write concurrency, and `execSQL` on a row-returning PRAGMA can throw). Keep the per-connection `PRAGMA busy_timeout = 5000` in `onOpen` next to the existing `PRAGMA foreign_keys = ON`. (gemini G1 / codex C3, PR #34.) Verify on-device with `PRAGMA journal_mode` → `wal`.
- **Checkpointing:** rely on SQLite auto-checkpoint (default 1000 pages) on the long-lived writer connection. Manual checkpoint management is a non-goal for a single-user local DB.

### 3.4 Call-site migration

Each `writeLock.mutex.withLock { … }` becomes `writer.write { … }`. Suspend work moves out of the block.

- **`JvmFilesystemScanner` / `AndroidMediaStoreScanner`:** read `scanFolders`/`safTreeUris` via `.first()` **before** the block (suspend), then run the entire scan body — bulk `last_scanned_ms` reset (raw `driver.execute`), file walk + per-file upsert/touch, root-guarded `softDeleteUnscanned`, `rebuildFtsIndex` — inside one `writer.write { }`. The blocking file/cursor I/O runs on the writer thread (identical serialization to today's whole-scan-under-lock).
- **`AndroidFormatFactBackfill`:** gains a `DatabaseWriter`. Native reads (`MediaExtractor` / `MediaMetadataRetriever`) stay off-writer (on `ioDispatcher`); each page's `db.transaction` write goes through `writer.write { }`. **Backfill writes are revalidate-before-persist (codex C1, PR #34) — the same TOCTOU class as item-2.** Because the read → native-read → write is split across the writer, a concurrent *second* scan can reset a row between the backfill's read and its by-`id` write, stamping stale facts over a row the scan just set to `metadata_backfilled_at_ms = NULL`. (The old `LibraryWriteLock` covered scan + backfill as one critical section; single-writer does not, because the slow native read is off-writer.) Add guarded `updateTrackFormatFactsIfUnchanged` **and** `markBackfilledNoMetadataIfUnchanged` (`WHERE id=? AND file_path=? AND file_mtime_ms=? AND file_size_bytes=? AND deleted_at_ms IS NULL`); project `file_mtime_ms` + `file_size_bytes` into `selectTracksNeedingBackfill`. A 0-row guarded write leaves the row un-stamped → still in the worklist; advance the page offset by the unstamped count (mirroring `TrackAnalysisRunner`'s skipped-row discipline) so the loop still terminates. **The Android scanner runs `backfill.runOnce()` inside the same `Either.catch` as the scan and only on scan success (codex C2)** — a failed scan must neither mutate backfill state nor let a backfill exception escape the `Either<ScanError, ScanResult>` contract.
- **`TrackAnalysisRunner`:** worklist page-select, per-track persist, and album rollup each go through `writer.write { }`; **`analyze()` stays between blocks, off the writer thread** — preserving the deliberate release-during-`analyze()` semantics structurally. Constructor takes `DatabaseWriter` in place of `LibraryWriteLock` (and drops the now-redundant `db` field if all access goes through the writer).
- **`SettingsRepositoryImpl`:** each `upsert` write → `writer.write { settingsQueries.upsert(…) }`; the read `Flow`s stay on `ioDispatcher` against `db` directly. Constructor gains `DatabaseWriter`. **The `SettingsRepository` *interface* is unchanged → `StubSettingsRepository` doubles in `:audio:playback` are unaffected** (the documented gotcha). This closes the latent settings race.
- **`rebuildFtsIndex`:** unchanged internally; it simply now runs within the scanner's `writer.write` block, so its `db.transaction` + raw `driver.execute` use the writer thread's connection.

### 3.5 Delete `LibraryWriteLock`

Remove the class, both `@Provides`, every injection, and the ~13 test construction sites (migrated to `DatabaseWriter`).

### 3.6 What stays — the item-2 TOCTOU guard

`updateTrackReplayGainIfUnchanged` (`WHERE id = ? AND file_path = ? AND file_mtime_ms = ? AND file_size_bytes = ? AND deleted_at_ms IS NULL`) is **kept unchanged.** Single-writer serializes *writes*, but the analyzer's read → `analyze()` (1.5–10 s, off-writer) → write is a **read-then-write TOCTOU**, not a write-write race — a concurrent scan can still change the row during `analyze()`. Single-writer does **not** subsume the guard.

### 3.7 Enforcement — pragmatic (per decision)

Write-only call-sites route through `DatabaseWriter`; readers keep `db` for SELECTs. There is no compile-time bar against a future caller writing on `db` directly off-thread — the Mutex backstop is gone. Mitigations: (a) the writer is the single, documented write chokepoint; (b) a `DatabaseWriter` KDoc invariant + a one-line note in CLAUDE.md's gotchas; (c) the migration leaves zero direct-write sites, so any new one stands out in review. (Structural enforcement — handing write-only consumers *only* a `DatabaseWriter`, never `db` — was considered and declined as too invasive against the existing read patterns.)

---

## 4. Data flow (corrected)

- **Scan:** trigger → read scan folders (`.first()`, off-writer) → `writer.write { bulk-reset → walk/cursor → per-file upsert/touch → root-guarded softDelete → FTS rebuild }` → *(Android)* `backfill.runOnce()` (native reads off-writer; per-page `writer.write`).
- **Analyzer:** `writer.write { worklist page }` → `analyze()` *(off-writer, slow)* → `writer.write { guarded persist }` → `writer.write { album rollup }`.
- **Settings write:** `writer.write { upsert }`.
- **Reads** (`LocalLibrarySource`, `LibraryStatsSource`, settings `Flow`s): on `ioDispatcher`, own connections, **WAL snapshot reads — no contention with the writer.**

---

## 5. Error handling / lifecycle

- **Exceptions:** propagate out of `writer.write` to the caller; the scanners' `Either.catch` still wraps the body. The writer thread is unaffected and picks up the next unit.
- **Cancellation:** cancelling the calling coroutine cancels the `withContext`; an already-started blocking write completes (JDBC statements are not cancellable mid-flight) — identical to the Mutex era.
- **Reentrancy:** structurally impossible — `write`'s block is non-suspend, so it cannot call the `suspend write`. Scan→backfill is sequential, not nested.
- **WAL:** crash-safe by design; auto-checkpoint; `-wal`/`-shm` sidecar files are expected.

---

## 6. Known trade-offs (accepted)

- **Settings / analyzer writes stall behind a long scan (falsify D5).** With one writer thread, a settings `upsert` (or analyzer persist) issued during a full scan waits for the scan's `writer.write` block to finish (minutes, worst case). The **root cause is the un-chunked whole-scan transaction** (the code's own deferred "chunked batching" TODO), independent of journal mode. **Accepted** for now: exposure is low (full scans are rare; incremental scans are fast; the *old* settings path was unsafe anyway). **Tracked:** scan-transaction chunking as a follow-up (it also improves mid-scan crash recovery). Not in this scope.
- **Reads are not routed through the writer.** Intentional — WAL makes concurrent reads safe and consistent without serializing them; routing reads through the single writer would needlessly serialize the UI behind scans.

---

## 7. Testing (TDD)

- **`DatabaseWriterTest` (new, desktopTest):** with a **real single-thread dispatcher** (not `Unconfined`), two concurrent `write { }` calls do not interleave (e.g. an overlap-detecting counter); exceptions propagate to the caller; the return value passes through.
- **WAL / multi-connection integration (new, desktopTest — addresses falsify E1):** back it with a **temp file DB** (so `ThreadedConnectionManager` + WAL are actually exercised — an in-memory DB uses the single-connection `StaticConnectionManager` and cannot reproduce the production path). Assert: a read issued on a separate thread *during* a writer transaction succeeds against a committed snapshot with no `SQLITE_BUSY` (WAL + `busy_timeout`).
- **Settings-during-scan (new):** a scanner and `SettingsRepositoryImpl` sharing one `DatabaseWriter` → fire a settings write during a scan → both land, no error (validates the gap closure + documents the D5 stall behavior).
- **Item-2 TOCTOU (kept green):** the existing `TrackAnalysisRunnerTest` stale-row test stays unchanged (guard untouched).
- **Migrated tests:** all `LibraryWriteLock()` sites → `DatabaseWriter(testDb.db, dispatcher)`; serialization-sensitive tests use a single-thread dispatcher, the rest `Unconfined`.
- **Gate:** the canonical 6-target build —
  `:app-android:assembleDebug :app-desktop:assemble :data:library:build :audio:playback:build :data:library:desktopTest :ui:components:desktopTest`.
- **On-device (Pixel 7):** re-smoke a scan + the RG backfill; confirm `PRAGMA journal_mode` returns `wal`; confirm `track` integrity (517 live / 0 spurious soft-deletes) unchanged.

---

## 8. Scope boundary

**In scope:** `DatabaseWriter` + its DI providers (both graphs); WAL + `busy_timeout` (both drivers); migrate all six writers; delete `LibraryWriteLock`; keep the item-2 guard; tests above; the writer-invariant doc.

**Out of scope (deferred / tracked):**
- **Scan-transaction chunking** (falsify D5 root cause; also improves crash recovery) — its own follow-up.
- A dedicated reader-connection abstraction beyond WAL — unnecessary; WAL already gives snapshot reads on the per-thread connections.
- Manual WAL checkpoint tuning.
- MediaStore + SAF duplicate-row dedup ([#32](https://github.com/clayboicardi/kiln/issues/32)).
- **#28 desktop defect cluster** — but **cross-check** whether any of its symptoms are read-during-write `SQLITE_BUSY`; WAL here may incidentally fix some. Note findings on #28.

---

## 9. Files touched + commit plan

Per "one change per commit," sequenced so **every commit is correct and leaves the writers mutually serialized** (the hazard: a half-migrated state where the scanner is on the writer thread but the analyzer is still on the `Mutex` is *not* serialized → corruption window). The scanners + analyzer must therefore switch together.

- **Commit 1 — WAL + `busy_timeout`** (both drivers). Independently valuable + verifiable (`PRAGMA journal_mode = wal`); no executor change yet.
  - `app-desktop/.../di/DesktopAppGraph.kt`, `app-android/.../di/AndroidAppGraph.kt`
- **Commit 2 — introduce `DatabaseWriter` + DI providers** (additive; not yet wired into writers).
  - `data/library/src/commonMain/.../db/DatabaseWriter.kt` (new), both app graphs, `DatabaseWriterTest`
- **Commit 3 — swap the serialization mechanism** (one logical change): migrate both scanners + `AndroidFormatFactBackfill` + `TrackAnalysisRunner` to `writer.write`, **delete `LibraryWriteLock`**, migrate their tests. Keeps writers mutually serialized at all times.
  - the two scanners, `TrackAnalysisRunner`, `AndroidFormatFactBackfill`, `LibraryWriteLock.kt` (deleted), `ScanInternals` (call-site only), affected tests
- **Commit 4 — close the settings gap:** `SettingsRepositoryImpl` writes → `writer.write`; invariant doc in CLAUDE.md. (Settings was never serialized, so deferring it one commit is no regression.)
  - `data/library/src/commonMain/.../settings/SettingsRepositoryImpl.kt`, `CLAUDE.md`

Single push at session close; CI runs Android + Desktop on every push. PR body uses **"address #31"** (never "fix/closes #31") so the squash-merge does not auto-close the issue while the chunking follow-up and #28 cross-check remain.

---

## 10. Verification record

- `JdbcSqliteDriver.kt` (SQLDelight 2.3.2) connection-manager discriminator — quoted in §1.1; desktop file DB → `ThreadedConnectionManager` (per-thread connections). Source fetched verbatim from `raw.githubusercontent.com/sqldelight/sqldelight/2.3.2/...`.
- `JdbcDriver.kt` (2.3.2): `executeQuery` with no active transaction runs in autocommit and does **not** alter transaction state; transactions are tracked in a `ThreadLocal`.
- Repo grep: no read path (`LocalLibrarySource` / `LibraryStatsSource` / settings reads) wraps in `db.transaction` → a reader cannot commit the writer's in-flight transaction (rules out the sharp form of the read hazard).
- Adversarial pre-mortem was run **solo** (the multi-model fan failed on infra: gemini CLI tier-deprecated, codex refresh-token revoked, claude CLI timed out). Findings D5 / E1 / E2 are folded into §6 / §7 / §3.7. An independent multi-model pass (`-p claude,codex` after `codex auth login`) remains available as a pre-implementation check.
