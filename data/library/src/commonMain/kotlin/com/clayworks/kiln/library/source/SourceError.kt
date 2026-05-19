// Sealed Arrow Either return type for MusicSource fallible ops.

package com.clayworks.kiln.library.source

sealed interface SourceError {
    /** The item ID is unknown to this source. */
    data class ItemNotFound(val itemId: ItemId) : SourceError

    /** The underlying file/URL is missing or unreachable. */
    data class ResourceUnavailable(val reason: String) : SourceError

    /** The source is temporarily offline (network-only). */
    data class SourceUnavailable(val reason: String) : SourceError

    /** Underlying I/O or parsing failure. */
    data class IoError(val cause: Throwable) : SourceError

    /** Catch-all for source-internal errors. */
    data class Internal(val message: String, val cause: Throwable? = null) : SourceError
}
