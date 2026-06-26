package com.clayworks.kiln.library.db

import com.clayworks.kiln.library.source.TestDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DatabaseWriterTest {

    private val testDb = TestDb()

    @AfterTest fun tearDown() = testDb.close()

    @Test
    fun `concurrent writes never overlap on the single writer thread`() = runBlocking {
        // A REAL single-thread dispatcher (not Unconfined) — serialization is the property under test.
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val writer = DatabaseWriter(testDb.db, dispatcher)
        val inProgress = AtomicBoolean(false)
        val overlapDetected = AtomicBoolean(false)
        try {
            val jobs = (1..64).map {
                launch(Dispatchers.Default) {
                    writer.write {
                        if (!inProgress.compareAndSet(false, true)) overlapDetected.set(true)
                        LockSupport.parkNanos(1_000_000) // ~1 ms; widen the window (no Thread.sleep)
                        inProgress.set(false)
                    }
                }
            }
            jobs.joinAll()
        } finally {
            dispatcher.close()
        }
        assertFalse(overlapDetected.get(), "two writes ran concurrently — serialization is broken")
    }

    @Test
    fun `write returns the block result`() = runBlocking {
        val writer = DatabaseWriter(testDb.db, Dispatchers.Unconfined)
        assertEquals(42, writer.write { 42 })
    }

    @Test
    fun `write propagates exceptions to the caller`() = runBlocking {
        val writer = DatabaseWriter(testDb.db, Dispatchers.Unconfined)
        assertFailsWith<IllegalStateException> { writer.write { error("boom") } }
        Unit
    }
}
