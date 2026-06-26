package com.clayworks.kiln.library.db

import com.clayworks.kiln.data.library.db.KilnDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The single-writer seam for [KilnDatabase] (#31 item 3). Every write to the
 * database runs through [write], which marshals it onto one dedicated
 * single-thread [writerDispatcher]. Because only one thread ever performs
 * writes, two writes can never overlap — serialization is structural, with no
 * mutex to forget. Replaces the former library write-lock mutex.
 *
 * [block] is intentionally NON-suspending. A write unit therefore runs
 * start-to-finish on the writer thread with no internal suspension, which
 * (a) guarantees no other write interleaves mid-unit, (b) makes reentrancy
 * structurally impossible (you cannot call this `suspend fun` from inside a
 * non-suspend block), and (c) forces slow suspend work — `analyze()`,
 * `flow.first()` — to stay OUTSIDE the serialized section, where it belongs.
 *
 * INVARIANT: all writes to [KilnDatabase] go through `write { }`. Reads may use
 * the database directly (WAL gives them lock-free committed snapshots). Adding a
 * direct write elsewhere reintroduces the scan↔analyzer race this class removes.
 */
class DatabaseWriter(
    private val db: KilnDatabase,
    private val writerDispatcher: CoroutineDispatcher,
) {
    suspend fun <T> write(block: KilnDatabase.() -> T): T =
        withContext(writerDispatcher) { db.block() }
}
