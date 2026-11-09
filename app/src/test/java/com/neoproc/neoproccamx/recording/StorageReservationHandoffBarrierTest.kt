package com.neoproc.neoproccamx.recording

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageReservationHandoffBarrierTest {
    @Test
    fun `handoff waits for older storage work to finish`() {
        val executor = Executors.newSingleThreadExecutor()
        val olderWorkFinished = AtomicBoolean(false)
        try {
            executor.execute {
                Thread.sleep(40L)
                olderWorkFinished.set(true)
            }

            StorageReservationHandoffBarrier.await(executor, 2_000L, "test")

            assertTrue(olderWorkFinished.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `handoff fails closed when older storage work cannot retire`() {
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        try {
            executor.execute { release.await(5L, TimeUnit.SECONDS) }

            val failure = runCatching {
                StorageReservationHandoffBarrier.await(executor, 25L, "test")
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("exceeded"))
            assertFalse(release.await(1L, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
