// ChangeNotifyingLibraryScanner — wraps the platform LibraryScanner and bumps a revision after
// any scan that may have changed the library's rows. MusicSource.search()/browse() are one-shot
// snapshots (#38), so a reader that loaded before a scan committed (LibraryTab during
// scan-on-launch on a fresh install, or after adding a folder) reloads when the revision moves.
// Bound as both LibraryScanner and LibraryChangeSignal in each app graph, so every scan path
// (launch, "Scan now", folder add) goes through it.

package com.clayworks.kiln.library.scan

import arrow.core.Either
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Signals that the library's contents may have changed. Readers holding a snapshot reload on change. */
interface LibraryChangeSignal {
    val revision: StateFlow<Long>
}

class ChangeNotifyingLibraryScanner(
    private val delegate: LibraryScanner,
) : LibraryScanner, LibraryChangeSignal {

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision.asStateFlow()

    override suspend fun scanIncremental(): Either<ScanError, ScanResult> =
        delegate.scanIncremental().also(::bumpIfMaybeChanged)

    override suspend fun scanFull(): Either<ScanError, ScanResult> =
        delegate.scanFull().also(::bumpIfMaybeChanged)

    // Only a clean no-op scan leaves the revision alone. A failed scan may have committed some
    // writes before failing, and a spurious reload is cheap where a stale library isn't.
    private fun bumpIfMaybeChanged(result: Either<ScanError, ScanResult>) {
        val unchanged = result is Either.Right &&
            result.value.let { it.tracksAdded + it.tracksUpdated + it.tracksSoftDeleted == 0 }
        if (!unchanged) _revision.update { it + 1 }
    }
}
