// Sealed Arrow Either return type for LibraryScanner. Mirrors the SourceError
// shape (per scaffold prep §6.1) — handlers can pattern-match exhaustively.

package com.clayworks.kiln.library.scan

sealed interface ScanError {
    /** Underlying filesystem / MediaStore I/O failure. Includes
     *  IOException, SQLite errors, FileNotFoundException, etc. */
    data class IoError(val cause: Throwable) : ScanError

    /** Required permission missing (Android: READ_MEDIA_AUDIO not granted;
     *  Desktop: scan folder unreadable). User-actionable. */
    data class PermissionDenied(val message: String) : ScanError

    /** A specific file's metadata couldn't be parsed by jaudiotagger /
     *  MediaMetadataRetriever / MediaStore. Scan continues — partial results
     *  may still be useful — but the failure is recorded for diagnostics. */
    data class MetadataParseError(val path: String, val cause: Throwable) : ScanError

    /** Catch-all for scanner-internal logic errors. */
    data class Internal(val message: String, val cause: Throwable? = null) : ScanError
}
