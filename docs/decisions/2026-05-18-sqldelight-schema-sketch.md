# SQLDelight Schema Sketch — Kiln Local Library Cache

**Date:** 2026-05-18 (Pre-MVP Research session 2 — Item 6 deliverable)
**Author:** Claude Opus 4.7 (1M context) for Clay Haworth
**Spec ref:** [`docs/superpowers/specs/2026-05-18-kiln-rebuild-design.md`](../superpowers/specs/2026-05-18-kiln-rebuild-design.md) §6.1 (MVP scope)
**Plan ref:** [`docs/superpowers/plans/2026-05-18-kiln-execution-plan.md`](../superpowers/plans/2026-05-18-kiln-execution-plan.md) §2.1 item 6
**Vetting log:** [`./2026-05-18-library-vetting.md`](./2026-05-18-library-vetting.md) Item 6 (pointer)

This document is the Pre-MVP deliverable for SQLDelight schema design. It is a **sketch** — actual `.sq` files land at MVP Sessions 4-7 (Library + playback vertical slice). The sketch establishes the table shape, index strategy, FTS5 plan, and performance-projection rationale so that scaffold-time work proceeds with a clear target.

---

## 1. Library version

**Pinned: SQLDelight 2.3.2** (released 2026-03-16).

| Version | Date | Notes |
|---|---|---|
| 2.3.2 | 2026-03-16 | **Pinned for Kiln MVP.** Latest stable; added synthesized columns in FTS5 virtual tables. |
| 2.2.1 | 2025-11-14 | Previous stable. |
| 2.1.0 | 2025-05-16 | Earlier stable; ~1-year-old. |
| 2.0.x | 2023-07 to 2024-04 | Original 2.0 line. |

Active release cadence (~6 months between minor versions; patches in between). Authoritative source: https://github.com/sqldelight/sqldelight/releases.

`libs.versions.toml` additions at MVP Session 1-3 scaffold time:
```toml
sqldelight = "2.3.2"

sqldelight-android-driver = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
sqldelight-sqlite-driver   = { module = "app.cash.sqldelight:sqlite-driver",  version.ref = "sqldelight" }   # JVM desktop
sqldelight-coroutines      = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
sqldelight-primitive-adapters = { module = "app.cash.sqldelight:primitive-adapters", version.ref = "sqldelight" }
```

Gradle plugin applied via `build-logic` convention plugin (the `:data:library` module is the only consumer at MVP; other modules get generated classes via SqlDelight's generated Kotlin sources output).

---

## 2. Design goals (what this schema must enable)

The schema serves a 39,500-track local FLAC library that Clay imports from his existing Gramophone/JAMZ collection. Spec §6.1 and plan §2.1 set the targets:

1. **Sub-100ms search** across track titles, album names, artist names, and album-artist names — type-ahead UX where every keystroke triggers a query
2. **Sub-50ms drill-down** from album/artist to tracks (the library views in MVP Sessions 8-11 must feel instant)
3. **Incremental rescan** — re-scanning Clay's library folders should detect added/modified/removed files without rebuilding the entire index
4. **Preserve listening history through file moves** — file deletions during rescan must not destroy historical play data (Clay's "Don't drop below current listening experience" constraint)
5. **Audiophile-relevant metadata stored** — codec / bit_depth / sample_rate_hz / bitrate_kbps / channels, all displayable in mini-player and Now Playing, all needed for the Phase 2b Flight F Hardware Spec Sheet identity move
6. **ReplayGain preserved** — Clay uses ReplayGain in JAMZ; the JAMZ-parity exit criterion (plan §4 Flight E end) is hollow without it
7. **Future-proofed for additional `MusicSource` implementations** — the schema must accommodate `source` provenance even though MVP only has `LocalLibrarySource`

The schema does NOT need to support: multi-user accounts (single-user app); cloud sync (out of scope per anti-roadmap §11); tag editing (anti-roadmap §11 — Kiln reads tags, never writes them); lyrics, Last.fm scrobbling, or podcast feeds.

---

## 3. Tables

Six core tables. SQLDelight `.sq` syntax is used below (very close to plain SQLite); each table will live in its own `.sq` file under `:data:library/src/commonMain/sqldelight/com/clayworks/kiln/library/` at MVP scaffold time.

### 3.1 `artist`

```sql
CREATE TABLE artist (
    id                       INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    name                     TEXT    NOT NULL,
    name_sort                TEXT    NOT NULL,
    musicbrainz_artist_id    TEXT    DEFAULT NULL
);

-- Dedup by (name_sort, mbid) with NULL mbid treated as empty for uniqueness.
-- Two "John Smith" artists with distinct MBIDs are allowed; two with no MBID at all collide and get merged.
CREATE UNIQUE INDEX artist_name_mbid_unique
    ON artist(name_sort, COALESCE(musicbrainz_artist_id, ''));

CREATE INDEX artist_name_sort
    ON artist(name_sort);
```

**Notes:**
- `name_sort` carries the sortable form ("Beatles, The" for "The Beatles") — populated from MusicBrainz `Artist Sort Name` tag if present, otherwise computed by a deterministic rule (strip leading "The /A /An ", move article to end).
- `musicbrainz_artist_id` is nullable — most local-library files won't have it. Carrying the column now is free; integrating MusicBrainz lookup is a future-work item not in scope for MVP.
- AUTOINCREMENT is intentional — IDs must never reuse. `listening_history` rows reference artist IDs transitively (via tracks), so a recycled ID could corrupt history attribution.

### 3.2 `album`

```sql
CREATE TABLE album (
    id                       INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    artist_id                INTEGER NOT NULL REFERENCES artist(id),  -- album-artist
    name                     TEXT    NOT NULL,
    name_sort                TEXT    NOT NULL,
    year                     INTEGER,
    date                     TEXT,                                    -- ISO 8601 if known
    musicbrainz_release_id   TEXT    DEFAULT NULL,
    catalog_number           TEXT    DEFAULT NULL,
    label                    TEXT    DEFAULT NULL,
    art_path                 TEXT    DEFAULT NULL,                    -- external folder.jpg / cover.png; null if art is per-track-embedded
    compilation              INTEGER NOT NULL DEFAULT 0                -- 0/1; "Various Artists" comps set this 1
);

CREATE UNIQUE INDEX album_artist_name_unique
    ON album(artist_id, name_sort);

CREATE INDEX album_year_desc
    ON album(year DESC);

CREATE INDEX album_artist_id
    ON album(artist_id);
```

**Notes:**
- `artist_id` is the **album-artist** (Beatles, not "George Harrison" for a Harrison track on a Beatles album). Per-track artist is on `track.artist_id`.
- Compilations create an artist row "Various Artists" and set `compilation = 1` on the album. JAMZ uses this convention.
- `art_path` is nullable: if present, points to filesystem path of external art (folder.jpg pattern). If null, art is embedded per-track in the audio file's metadata.

### 3.3 `track`

The big one. ~30 columns; all justified below.

```sql
CREATE TABLE track (
    id                       INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    album_id                 INTEGER REFERENCES album(id),
    artist_id                INTEGER NOT NULL REFERENCES artist(id),  -- track artist (may differ from album-artist for compilations)

    title                    TEXT    NOT NULL,
    title_sort               TEXT    NOT NULL,
    duration_ms              INTEGER NOT NULL,
    track_number             INTEGER,
    disc_number              INTEGER,
    year                     INTEGER,
    date                     TEXT,                                    -- ISO 8601 if known
    genre                    TEXT,                                    -- denormalized; multiple genres collapsed to ';'-delimited
    composer                 TEXT,
    bpm                      INTEGER,                                 -- for future BPM-based queries

    -- Audio format (drives mini-player display + Phase 2b Hardware Spec Sheet)
    codec                    TEXT    NOT NULL,                        -- 'FLAC', 'MP3', 'ALAC', 'OGG', etc.
    bitrate_kbps             INTEGER,                                 -- null for lossless variable-rate; populated for lossy
    sample_rate_hz           INTEGER NOT NULL,                        -- 44100, 48000, 96000, 192000, ...
    bit_depth                INTEGER,                                 -- 16, 24, 32 for PCM; null for lossy formats
    channels                 INTEGER NOT NULL,                        -- 1, 2, 6 (5.1), ...

    -- File location + change detection
    file_path                TEXT    NOT NULL UNIQUE,                 -- absolute path on disk
    file_size_bytes          INTEGER NOT NULL,
    file_mtime_ms            INTEGER NOT NULL,                        -- last-modified for rescan detection

    -- ReplayGain (JAMZ-parity requirement)
    replay_gain_track_db     REAL    DEFAULT NULL,
    replay_gain_album_db     REAL    DEFAULT NULL,
    replay_gain_track_peak   REAL    DEFAULT NULL,
    replay_gain_album_peak   REAL    DEFAULT NULL,

    -- Embedded art handling
    has_embedded_art         INTEGER NOT NULL DEFAULT 0,              -- 0/1
    art_path                 TEXT    DEFAULT NULL,                    -- null if has_embedded_art = 1 OR album.art_path is set

    -- Provenance / audit
    source                   TEXT    NOT NULL DEFAULT 'local',        -- matches MusicSource id; future-proofing
    date_added_ms            INTEGER NOT NULL,
    date_modified_ms         INTEGER NOT NULL,
    last_scanned_ms          INTEGER NOT NULL,                        -- when rescan last verified file presence
    deleted_at_ms            INTEGER DEFAULT NULL,                    -- soft-delete: NULL = active

    -- Lazy stats
    play_count               INTEGER NOT NULL DEFAULT 0,
    skip_count               INTEGER NOT NULL DEFAULT 0,
    last_played_ms           INTEGER DEFAULT NULL
);
```

**Soft-delete pattern (`deleted_at_ms`):**

When rescan finds a file missing from disk, the track row is **NOT** deleted — `deleted_at_ms` is set to the current timestamp. Most queries add `WHERE deleted_at_ms IS NULL`. This preserves `listening_history` references when files move or temporarily disappear (USB drive unplugged, cloud-synced folder out of sync, etc.). If the file later reappears at the same path, the same row is reactivated (`deleted_at_ms = NULL`, `last_scanned_ms = now`).

**Provenance (`source`):**

Every track records which `MusicSource` produced it. MVP only has `LocalLibrarySource` (`source = 'local'`), so this is a stub today. The column is in the schema now because adding it later would require a migration touching every row. Future Subsonic / Navidrome / etc. sources would set `source = 'subsonic'` etc.

### 3.4 `track` indexes

```sql
CREATE INDEX track_album_id           ON track(album_id) WHERE deleted_at_ms IS NULL;
CREATE INDEX track_artist_id          ON track(artist_id) WHERE deleted_at_ms IS NULL;
CREATE INDEX track_title_sort         ON track(title_sort) WHERE deleted_at_ms IS NULL;
CREATE INDEX track_date_added_desc    ON track(date_added_ms DESC) WHERE deleted_at_ms IS NULL;
CREATE INDEX track_last_played_desc   ON track(last_played_ms DESC) WHERE deleted_at_ms IS NULL AND last_played_ms IS NOT NULL;
CREATE INDEX track_play_count_desc    ON track(play_count DESC) WHERE deleted_at_ms IS NULL AND play_count > 0;
CREATE INDEX track_file_mtime_ms      ON track(file_mtime_ms) WHERE deleted_at_ms IS NULL;
```

**All track-list indexes are PARTIAL** — `WHERE deleted_at_ms IS NULL` is part of the index definition, so soft-deleted rows don't bloat the index. This is the SQLite partial-index pattern.

**Rationale per index:**

| Index | Query served | Justification |
|---|---|---|
| `track_album_id` | `SELECT … FROM track WHERE album_id = ?` | Drill-down from album to its tracks. Hot in library view. |
| `track_artist_id` | `SELECT … FROM track WHERE artist_id = ?` | Artist detail page; "songs by this artist". |
| `track_title_sort` | `ORDER BY title_sort` paginated full-track-list | Songs view sorted alphabetically. 39.5k rows sort in <10ms via index. |
| `track_date_added_desc` | `ORDER BY date_added_ms DESC LIMIT 50` | "Recently added" view. Sorted scan via index. |
| `track_last_played_desc` | `ORDER BY last_played_ms DESC LIMIT 50` | "Recently played" view. Partial index excludes never-played rows. |
| `track_play_count_desc` | `ORDER BY play_count DESC LIMIT 50` | "Most played" view. Partial index excludes zero-play rows. |
| `track_file_mtime_ms` | `WHERE file_mtime_ms > ?` for incremental rescan | Find files modified since last scan. |

The UNIQUE constraint on `file_path` provides an implicit index for path lookup (used heavily during rescan).

### 3.5 `playlist`

```sql
CREATE TABLE playlist (
    id                INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    name              TEXT    NOT NULL UNIQUE,
    description       TEXT    DEFAULT NULL,
    date_created_ms   INTEGER NOT NULL,
    date_modified_ms  INTEGER NOT NULL,
    sort_order        TEXT    NOT NULL DEFAULT 'manual'   -- 'manual' | 'by_artist' | 'by_album' | 'by_added' | 'by_year'
);
```

**Sort order is stored on the playlist**, not per-row. When `sort_order != 'manual'`, the UI sorts by computed columns at query time; `playlist_track.position` is ignored. When `sort_order = 'manual'`, position drives the order.

### 3.6 `playlist_track`

```sql
CREATE TABLE playlist_track (
    playlist_id     INTEGER NOT NULL REFERENCES playlist(id) ON DELETE CASCADE,
    track_id        INTEGER NOT NULL REFERENCES track(id),
    position        INTEGER NOT NULL,
    date_added_ms   INTEGER NOT NULL,

    PRIMARY KEY (playlist_id, position)
);

-- For "what playlists is this track in?" reverse-lookup. Forward lookup uses PK.
CREATE INDEX playlist_track_track_id ON playlist_track(track_id);
```

**Notes:**
- The PK `(playlist_id, position)` covers ordered iteration of a playlist's tracks (the hot read pattern).
- The same track may appear multiple times in a playlist (no UNIQUE on `track_id`) — some users want repetition for vinyl-style A-side/B-side flows or queue building.
- ON DELETE CASCADE on `playlist_id`: deleting a playlist removes its rows. No CASCADE on `track_id` (matches soft-delete semantics for track).

### 3.7 `listening_history`

```sql
CREATE TABLE listening_history (
    id                  INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    track_id            INTEGER NOT NULL REFERENCES track(id),   -- no CASCADE; soft-delete on track preserves history
    played_at_ms        INTEGER NOT NULL,
    play_duration_ms    INTEGER NOT NULL,                        -- actual listening duration (≤ track.duration_ms)
    completed           INTEGER NOT NULL DEFAULT 0,              -- 1 if listened >50% OR >4min (scrobble convention)
    source              TEXT    NOT NULL DEFAULT 'local'         -- denormalized from track.source at play time
);

CREATE INDEX history_played_at_desc        ON listening_history(played_at_ms DESC);
CREATE INDEX history_track_played_at_desc  ON listening_history(track_id, played_at_ms DESC);
```

**Append-only.** Never UPDATE; rows are immutable once written. Hot queries: recent global history, per-track history.

The composite index `(track_id, played_at_ms DESC)` serves both "all listens for a track in reverse order" and (via prefix) "all listens for a track."

---

## 4. FTS5 strategy

### 4.1 Virtual table

```sql
CREATE VIRTUAL TABLE track_search USING fts5(
    title,
    album_name,
    artist_name,
    album_artist_name,
    content='',                                  -- contentless: rowid maps to track.id; payload stored elsewhere
    tokenize='unicode61 remove_diacritics 2'     -- handles "Beyoncé" matches "Beyonce"
);
```

**Why contentless?**

- Source data already lives in `track` / `album` / `artist`. Duplicating it in the FTS table wastes ~5-10 MB on a 39.5k-row library.
- Contentless FTS5 stores only the inverted index, not the source text. Reads JOIN back to source tables to fetch the actual rows.
- Saves disk; complicates writes (we must keep the index in sync manually).

**Why `unicode61 remove_diacritics 2`?**

- `unicode61` is the default Unicode-aware tokenizer.
- `remove_diacritics 2` strips combining marks ("Beyoncé" tokenizes the same as "Beyonce"). Critical for a multi-language music library; "Sigur Rós", "Café Tacvba", "Björk", etc., all need to be searchable by ASCII transliteration.

### 4.2 Population — application-managed, not trigger-managed

Two options exist:

| Option | Approach | Verdict |
|---|---|---|
| (A) SQL triggers | `CREATE TRIGGER track_after_insert AFTER INSERT ON track …` populate FTS5 from JOIN against album+artist | More "correct"; everything in SQL. But triggers are hard to debug, fail silently on schema mismatch, and the JOIN-from-trigger pattern is awkward in SQLite. |
| (B) Application code | Library scanner inserts track row → in same transaction, INSERT INTO track_search | **Chosen.** Simpler to reason about; batchable; easier to rebuild from scratch (full-library reindex job). |

**Application-managed update pattern (pseudocode):**

```kotlin
// In :data:library Repository, inside a SQLDelight transaction:
database.transaction {
    trackQueries.upsert(track)
    val ids = trackQueries.lookupNames(track.id).executeAsOne()
    trackSearchQueries.replace(
        rowid       = track.id,
        title       = track.title,
        albumName   = ids.album_name ?: "",
        artistName  = ids.artist_name,
        albumArtist = ids.album_artist_name ?: ""
    )
}
```

`trackSearchQueries.replace` runs an `INSERT OR REPLACE INTO track_search(rowid, title, album_name, artist_name, album_artist_name) VALUES (?, ?, ?, ?, ?)`. For contentless FTS5, REPLACE handles both insert and update cases.

### 4.3 Search query

```sql
searchTracks:
SELECT
    track.id,
    track.title,
    album.name AS album_name,
    artist.name AS artist_name,
    track.duration_ms,
    bm25(track_search) AS rank
FROM track_search
JOIN track  ON track.id = track_search.rowid
LEFT JOIN album  ON track.album_id  = album.id
JOIN artist ON track.artist_id = artist.id
WHERE track_search MATCH :query
  AND track.deleted_at_ms IS NULL
ORDER BY bm25(track_search)
LIMIT 50;
```

**Prefix matching for type-ahead:** the application layer wraps user input in `* ` suffix for prefix tokens — e.g., user types `"radioh"` → query becomes `"radioh*"` for partial-word matching. SQLite FTS5 supports this natively.

### 4.4 Sectioned search (Phase 2a Flight D)

For the sectioned search view (Phase 2a Flight D, plan §4), the same FTS5 table powers all three sections — the application issues three queries against `track_search` with different result-grouping logic, or one query with `GROUP BY` on the derived section. Schema doesn't change for sectioning.

---

## 5. Foreign-key enforcement (driver configuration)

SQLite does NOT enable foreign keys by default. Both drivers must opt in:

**Android (`AndroidSqliteDriver`):**
```kotlin
AndroidSqliteDriver(
    schema   = KilnDatabase.Schema,
    context  = androidContext,
    name     = "kiln.db",
    callback = object : AndroidSqliteDriver.Callback(KilnDatabase.Schema) {
        override fun onOpen(db: SupportSQLiteDatabase) {
            super.onOpen(db)
            db.execSQL("PRAGMA foreign_keys = ON")
        }
    }
)
```

**JVM desktop (`JdbcSqliteDriver`):**
```kotlin
JdbcSqliteDriver(
    url        = "jdbc:sqlite:${kilnDataDir}/kiln.db",
    properties = Properties().apply { put("foreign_keys", "true") }
)
```

Per SQLDelight docs (`/sqldelight/sqldelight` Context7 corpus, "Enable Foreign Keys for JVM SQLite Driver"). Both wiring blocks live in platform-specific source sets within `:data:library`.

Additional PRAGMAs to set at startup (both platforms):
- `PRAGMA journal_mode = WAL` — write-ahead logging for concurrent reads during writes (library scan vs. UI queries)
- `PRAGMA synchronous = NORMAL` — durability/perf trade-off; NORMAL is safe with WAL
- `PRAGMA temp_store = MEMORY` — keep temp tables off disk
- `PRAGMA cache_size = -32000` — 32MB page cache (fits Clay's 32GB RAM with room to spare)

---

## 6. Migration strategy

SQLDelight 2.x migration files are `.sqm` files numbered by target version. Initial schema is v1 (no migration needed). Future changes:

- Adding a column → `.sqm` file with `ALTER TABLE … ADD COLUMN …`
- Renaming a column → multi-step migration (rename via temporary table, copy data, drop old, rename new) — SQLite does support `ALTER TABLE … RENAME COLUMN` as of SQLite 3.25.0 but SQLDelight's migration verifier prefers the explicit temp-table pattern
- Adding an index → `.sqm` file with `CREATE INDEX …` (cheap; runs on app upgrade)
- FTS5 reindex (if tokenizer changes) → `INSERT INTO track_search(track_search) VALUES('rebuild')`

**Build-time verification:**

```kotlin
// In :data:library/build.gradle.kts
sqldelight {
    databases {
        create("KilnDatabase") {
            packageName.set("com.clayworks.kiln.library.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)   // CI runs this on every PR
        }
    }
}
```

`verifyMigrations` runs `:data:library:verifySqlDelightMigration` task on every CI build. If a `.sqm` migration produces a different schema than the current `.sq` files, the build fails. Catches schema drift before it ships.

---

## 7. Performance projection — 39,500 tracks

**Storage estimates:**

| Object | Per-row size | Count | Total |
|---|---|---|---|
| `track` rows | ~400 bytes (30 cols, mix of INT/TEXT) | 39,500 | ~16 MB |
| `album` rows | ~200 bytes | ~3,000 | ~600 KB |
| `artist` rows | ~80 bytes | ~500 | ~40 KB |
| `track_search` index | ~200 bytes per row (compressed inverted index) | 39,500 | ~8 MB |
| Other indexes | ~30 bytes per indexed col per row | 39,500 × 7 | ~8 MB |
| `playlist_track` rows | ~30 bytes | variable | trivial |
| `listening_history` rows | ~50 bytes | grows ~50/day | ~1 MB/year |

**Total DB on disk: ~35-40 MB at MVP launch with full library indexed.** Trivial for Clay's hardware (2TB SSD).

**Query latency projections:**

| Query | Method | Projection |
|---|---|---|
| Type-ahead search ("radio*" returns 50 tracks) | FTS5 MATCH + bm25 ORDER BY + JOIN | **<20 ms** (FTS5 is designed for millions of rows; 39.5k is tiny) |
| Full-track-list paginated by title | Index scan on `track_title_sort` | **<5 ms** for any LIMIT/OFFSET window |
| All tracks in an album (avg 13 tracks/album) | Index `track_album_id` | **<2 ms** |
| All tracks by an artist (avg 80 tracks) | Index `track_artist_id` | **<5 ms** |
| Recently added (LIMIT 50) | Index `track_date_added_desc` | **<2 ms** |
| Most played (LIMIT 50) | Partial index `track_play_count_desc` | **<2 ms** |
| Incremental rescan: "files modified since T" | Index `track_file_mtime_ms` | **<10 ms** for typical incremental delta |
| Full library re-scan (cold) | Application-managed batch INSERTs | **~30-60 sec** for 39.5k files (limited by tag-reader I/O, not DB) |

All read queries comfortably clear the sub-100ms goal. The MVP exit-criteria target ("library scans his FLAC folders, indexes 39.5k tracks within ~5 minutes") is also clear with margin — the 30-60 sec projection above is the DB-write portion only; tag parsing + filesystem walk adds the rest of the budget.

---

## 8. Multi-platform considerations

| Concern | Android | JVM Desktop |
|---|---|---|
| Driver | `AndroidSqliteDriver` (uses platform SQLite) | `JdbcSqliteDriver` with `sqlite-jdbc` (Xerial) bundled |
| DB file location | `context.getDatabasePath("kiln.db")` (default app-data dir) | `${user.home}/.kiln/kiln.db` on Windows; OS-conventional path |
| Concurrency | WAL mode handles UI reads + scanner writes concurrently | Same; JdbcSqliteDriver supports WAL |
| Migration runner | SQLDelight schema callback (built-in) | Same |
| FTS5 availability | Bundled in Android's SQLite (API 21+, since pre-MVP API floor) | Bundled in `sqlite-jdbc` (Xerial); confirmed in 3.x line of `sqlite-jdbc` |

**FTS5 caveat for Android:** Some very old AOSP forks shipped SQLite without FTS5. Mainline Android API 21+ is FTS5-clean. Verify on Pixel 10 Pro XL at MVP Session 4 (sanity check, not expected to fail).

---

## 9. Open questions deferred to scaffold time

| Question | Decided when | Notes |
|---|---|---|
| Exact SQLDelight package path under `:data:library/src/commonMain/sqldelight/` | MVP Session 1-3 (scaffold) | Convention: `com/clayworks/kiln/library/db/` |
| Whether to add a `genre` lookup table or keep denormalized TEXT | MVP Session 4-5 | Decision driven by how genre is presented in library views; denormalized is fine for MVP, normalize if a "browse by genre" view ships |
| Whether `playlist_track` should support track repetition | MVP Session 12-15 | Currently allowed (no UNIQUE); confirm with Clay before Now Playing queue work |
| MusicBrainz integration | Future work; not MVP | The columns exist; the integration doesn't |
| Whether to add a `track_artist` many-to-many table for "featuring" artists | Phase 2a or 2b if surfaced | MVP treats featured artists as part of the title string |
| Backup / export format for listening history | Phase 2b or later | History is precious; consider a JSON export action |

---

## 10. What this sketch does NOT do

- Does NOT define the actual `.sq` files (those land at MVP Session 4-7 with the real package paths and SqlDelight-specific syntax annotations)
- Does NOT specify the exact `Repository` API in `:data:library` that consumes generated SQLDelight queries — that's a session 4-5 design call
- Does NOT mandate a specific JVM SQLite version (Xerial sqlite-jdbc 3.x family; pin at scaffold time)
- Does NOT prescribe a specific `Repository`-layer caching strategy (`StateFlow<List<Track>>` from `coroutines-extensions` is the likely path, but the exact shape is downstream)

---

End of schema sketch.
