package com.neoproc.neoproccamx.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactRequestRevisionLedgerTest {
    private data class StructurallyEqualRequest(val value: Int)

    @Test
    fun `each immutable request retains its own revision`() {
        val ledger = ExactRequestRevisionLedger<Any>()
        val first = Any()
        val second = Any()

        assertSame(first, ledger.record(first, 7L))
        assertSame(second, ledger.record(second, 8L))

        assertEquals(7L, ledger.revisionFor(first))
        assertEquals(8L, ledger.revisionFor(second))
        assertEquals(ExactRequestRevisionLedger.UNKNOWN_REVISION, ledger.revisionFor(Any()))
    }

    @Test
    fun `structurally equal requests retain separate identity revisions`() {
        val ledger = ExactRequestRevisionLedger<StructurallyEqualRequest>()
        val first = StructurallyEqualRequest(5)
        val second = StructurallyEqualRequest(5)
        assertTrue(first == second)
        assertTrue(first !== second)

        ledger.record(first, 101L)
        ledger.record(second, 202L)

        assertEquals(101L, ledger.revisionFor(first))
        assertEquals(202L, ledger.revisionFor(second))
    }

    @Test
    fun `session clear invalidates all retired request revisions`() {
        val ledger = ExactRequestRevisionLedger<Any>()
        val request = Any()
        ledger.record(request, 41L)

        ledger.clear()

        assertEquals(ExactRequestRevisionLedger.UNKNOWN_REVISION, ledger.revisionFor(request))
    }

    @Test
    fun `callback lookup is safe while camera thread records requests`() {
        val ledger = ExactRequestRevisionLedger<Any>()
        val requests = List(256) { Any() }
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val pool = Executors.newFixedThreadPool(2)
        val failures = mutableListOf<Throwable>()

        pool.execute {
            try {
                start.await()
                requests.forEachIndexed { index, request -> ledger.record(request, index.toLong()) }
            } catch (failure: Throwable) {
                synchronized(failures) { failures += failure }
            } finally {
                done.countDown()
            }
        }
        pool.execute {
            try {
                start.await()
                repeat(4) {
                    requests.forEach { request -> ledger.revisionFor(request) }
                }
            } catch (failure: Throwable) {
                synchronized(failures) { failures += failure }
            } finally {
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertTrue(failures.isEmpty())
        requests.forEachIndexed { index, request ->
            assertEquals(index.toLong(), ledger.revisionFor(request))
        }
    }
}
