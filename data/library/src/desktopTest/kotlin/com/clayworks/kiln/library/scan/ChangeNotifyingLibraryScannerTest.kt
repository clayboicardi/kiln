// ChangeNotifyingLibraryScanner: bumps LibraryChangeSignal.revision after any scan that may
// have changed rows, so one-shot snapshot readers (#38, e.g. LibraryTab) know to reload.

package com.clayworks.kiln.library.scan

import arrow.core.Either
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChangeNotifyingLibraryScannerTest {

    private class StubScanner(var next: Either<ScanError, ScanResult>) : LibraryScanner {
        override suspend fun scanIncremental() = next
        override suspend fun scanFull() = next
    }

    private fun result(added: Int = 0, updated: Int = 0, deleted: Int = 0) = Either.Right(
        ScanResult(
            tracksAdded = added,
            tracksUpdated = updated,
            tracksSoftDeleted = deleted,
            tracksUnchanged = 10,
            durationMs = 5L,
        ),
    )

    @Test
    fun scanThatChangesRows_bumpsRevision() = runTest {
        val stub = StubScanner(result(added = 3))
        val scanner = ChangeNotifyingLibraryScanner(stub)
        assertEquals(0L, scanner.revision.value)

        scanner.scanIncremental()
        assertEquals(1L, scanner.revision.value)

        stub.next = result(updated = 1)
        scanner.scanIncremental()
        stub.next = result(deleted = 2)
        scanner.scanFull()
        assertEquals(3L, scanner.revision.value)
    }

    @Test
    fun noOpScan_leavesRevisionAlone() = runTest {
        // The usual launch scan on an unchanged library must not trigger a pointless reload.
        val scanner = ChangeNotifyingLibraryScanner(StubScanner(result()))

        scanner.scanIncremental()

        assertEquals(0L, scanner.revision.value)
    }

    @Test
    fun failedScan_bumpsRevision() = runTest {
        // A scan can fail after committing some of its writes, so a Left still means
        // "may have changed". A spurious reload is cheap; a stale library isn't.
        val scanner = ChangeNotifyingLibraryScanner(
            StubScanner(Either.Left(ScanError.Internal("disk went away"))),
        )

        scanner.scanIncremental()

        assertEquals(1L, scanner.revision.value)
    }

    @Test
    fun returnsTheDelegateResultUnchanged() = runTest {
        val expected = result(added = 7)
        val scanner = ChangeNotifyingLibraryScanner(StubScanner(expected))

        assertEquals(expected, scanner.scanIncremental())
    }
}
