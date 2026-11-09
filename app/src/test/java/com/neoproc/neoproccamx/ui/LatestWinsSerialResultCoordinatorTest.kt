package com.neoproc.neoproccamx.ui

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestWinsSerialResultCoordinatorTest {
    @Test
    fun `active resolution stays serial and only newest result is applied`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val latestApplied = CountDownLatch(1)
            val active = AtomicInteger()
            val maxActive = AtomicInteger()
            val resolved = CopyOnWriteArrayList<Int>()
            val applied = CopyOnWriteArrayList<Int>()
            val coordinator = LatestWinsSerialResultCoordinator<Int, Int>(
                scheduleResolve = { drain -> executor.execute(drain) },
                scheduleApply = { apply -> apply() },
                resolve = { value ->
                    val activeNow = active.incrementAndGet()
                    maxActive.updateAndGet { previous -> maxOf(previous, activeNow) }
                    try {
                        resolved += value
                        if (value == 1) {
                            firstStarted.countDown()
                            assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                        }
                        value * 10
                    } finally {
                        active.decrementAndGet()
                    }
                },
                apply = { value, result ->
                    applied += result
                    if (value == 3) latestApplied.countDown()
                }
            )

            coordinator.submit(1)
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            coordinator.submit(2)
            coordinator.submit(3)
            releaseFirst.countDown()

            assertTrue(latestApplied.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(1, 3), resolved)
            assertEquals(listOf(30), applied)
            assertEquals(1, maxActive.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
