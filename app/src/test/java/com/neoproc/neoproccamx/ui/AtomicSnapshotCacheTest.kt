package com.neoproc.neoproccamx.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicSnapshotCacheTest {
    @Test
    fun `different cache misses resolve concurrently without a shared monitor`() {
        val cache = AtomicSnapshotCache<String, String>()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val bothResolversStarted = CountDownLatch(2)
            val releaseResolvers = CountDownLatch(1)
            val activeResolvers = AtomicInteger()
            val maximumActiveResolvers = AtomicInteger()

            fun resolve(key: String) = cache.resolve(key) {
                val active = activeResolvers.incrementAndGet()
                maximumActiveResolvers.updateAndGet { previous -> maxOf(previous, active) }
                bothResolversStarted.countDown()
                try {
                    assertTrue(releaseResolvers.await(5, TimeUnit.SECONDS))
                    "value-$key"
                } finally {
                    activeResolvers.decrementAndGet()
                }
            }

            val first = executor.submit<String> { resolve("first") }
            val second = executor.submit<String> { resolve("second") }
            assertTrue(bothResolversStarted.await(5, TimeUnit.SECONDS))
            releaseResolvers.countDown()

            assertEquals("value-first", first.get(5, TimeUnit.SECONDS))
            assertEquals("value-second", second.get(5, TimeUnit.SECONDS))
            assertEquals(2, maximumActiveResolvers.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `published snapshots never mix a key with another keys value`() {
        val cache = AtomicSnapshotCache<Int, String>()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val start = CountDownLatch(1)
            val futures = (0 until 8).map { worker ->
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    repeat(2_000) { iteration ->
                        val key = (worker + iteration) % 11
                        assertEquals("value-$key", cache.resolve(key) { "value-$key" })
                    }
                }
            }
            start.countDown()
            futures.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `matching key reuses the atomically published value`() {
        val cache = AtomicSnapshotCache<String, String>()
        val resolutions = AtomicInteger()

        repeat(20) {
            assertEquals("resolved", cache.resolve("stable") {
                resolutions.incrementAndGet()
                "resolved"
            })
        }

        assertEquals(1, resolutions.get())
    }
}
