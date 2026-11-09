package com.neoproc.neoproccamx.pipeline

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedDirectBufferPoolTest {

    @Test
    fun exhaustionReturnsNullWithoutAllocatingPastResidentBound() {
        val allocations = AtomicInteger(0)
        val pool = BoundedDirectBufferPool(maxResidentBuffers = 2) { capacity ->
            allocations.incrementAndGet()
            ByteBuffer.allocateDirect(capacity)
        }

        val first = pool.acquire(64, 0L, TimeUnit.NANOSECONDS)
        val second = pool.acquire(64, 0L, TimeUnit.NANOSECONDS)

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(2, pool.allocationCount)
        assertNull(pool.acquire(64, 0L, TimeUnit.NANOSECONDS))
        assertEquals(2, allocations.get())
        assertEquals(2, pool.residentCount)

        pool.recycle(first!!)
        pool.recycle(second!!)
        pool.close()
    }

    @Test
    fun prewarmAllocatesTheCompleteSetOnceAndSubsequentBorrowsReuseIt() {
        val allocations = AtomicInteger(0)
        val pool = BoundedDirectBufferPool(maxResidentBuffers = 2) { capacity ->
            allocations.incrementAndGet()
            ByteBuffer.allocateDirect(capacity)
        }

        assertTrue(pool.prewarm(128))
        assertEquals(2, allocations.get())
        assertEquals(2, pool.availableCount)

        val first = pool.acquire(128, 0L, TimeUnit.NANOSECONDS)!!
        val second = pool.acquire(128, 0L, TimeUnit.NANOSECONDS)!!
        pool.recycle(first)
        pool.recycle(second)

        assertEquals(2, allocations.get())
        assertEquals(2, pool.residentCount)
        pool.close()
        assertEquals(0, pool.residentCount)
    }
}
