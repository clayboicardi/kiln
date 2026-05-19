// Scan outcome accounting. Emitted at the tail of every scan invocation.

package com.clayworks.kiln.library.scan

data class ScanResult(
    /** Brand-new track rows inserted this pass. */
    val tracksAdded: Int,

    /** Existing track rows whose metadata was rewritten (mtime change or
     *  reactivation of a previously soft-deleted row). */
    val tracksUpdated: Int,

    /** Live track rows that were soft-deleted because the underlying file
     *  was no longer present. listening_history rows remain intact. */
    val tracksSoftDeleted: Int,

    /** Files visited whose mtime+size matched the existing row — only
     *  last_scanned_ms got bumped. Useful for "how much of the library
     *  was actually changed" diagnostics. */
    val tracksUnchanged: Int,

    /** Wall-clock duration of the scan, milliseconds. */
    val durationMs: Long,
)
