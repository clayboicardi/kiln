// LibraryScanner — populates the SQLDelight tables from the platform's
// underlying audio-file source (MediaStore on Android, filesystem on
// Desktop). One implementation per platform; injected via kotlin-inject.
//
// `MusicSource.refresh()` calls `scanIncremental()`; a UI "Rescan library"
// button (lands at MVP Sessions 26-28) drives `scanFull()`. The scanner
// owns FTS5 maintenance internally — callers don't touch track_search.
//
// Per scaffold prep §6 + Session 7 handoff (H2). MVP shape; richer progress
// observation (Flow<ScanProgress>) defers to Phase 2 polish work.

package com.clayworks.kiln.library.scan

import arrow.core.Either

interface LibraryScanner {

    /**
     * Walk all known scan locations, comparing each candidate file's mtime
     * against the corresponding `track.file_mtime_ms`. Files unchanged since
     * the last scan get only a `last_scanned_ms` touch. Changed files have
     * their metadata fully rewritten (play_count / last_played_ms / etc. are
     * preserved). Files no longer present get soft-deleted. FTS5 index is
     * rebuilt at scan end.
     *
     * Default user path — invoked on app start + the explicit "Refresh"
     * button.
     */
    suspend fun scanIncremental(): Either<ScanError, ScanResult>

    /**
     * Soft-delete every live track, then walk + re-index from scratch as if
     * every file were new. Equivalent to `scanIncremental()` after the
     * `last_scanned_ms` epoch was reset. Used for explicit "Rebuild library"
     * UI action; rare.
     */
    suspend fun scanFull(): Either<ScanError, ScanResult>
}
