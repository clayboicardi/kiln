package com.clayworks.kiln.library.scan

import kotlinx.coroutines.sync.Mutex

/**
 * Serializes the two writers of the `track` table — the [LibraryScanner] and
 * the [TrackAnalysisRunner] — which share a single SQLite connection
 * (JdbcSqliteDriver on desktop, AndroidSqliteDriver on Android). Without it, a
 * scan (one long `db.transaction { }`) and an analyzer page-write can interleave
 * on that one connection and either corrupt the transaction state machine
 * (`SQLITE_BUSY` / nested BEGIN) or clobber freshly-computed ReplayGain values.
 *
 * Provided as a `@Singleton` and injected into both scanners and the analysis
 * runner. The scanner holds it across the whole scan; the analyzer acquires it
 * per DB access (reads + writes) but releases during the slow `analyze()` calls,
 * so a scan can interleave between the analyzer's individual operations rather
 * than waiting out the entire pass.
 *
 * It also subsumes the scanner's former per-instance single-flight guard:
 * concurrent scan triggers (scan-on-launch / "Scan now" / auto-scan-on-add)
 * still serialize because they all contend for this one mutex.
 */
class LibraryWriteLock {
    val mutex = Mutex()
}
