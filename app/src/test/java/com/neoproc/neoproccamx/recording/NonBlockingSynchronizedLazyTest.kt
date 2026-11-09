package com.neoproc.neoproccamx.recording

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NonBlockingSynchronizedLazyTest {
    @Test
    fun `unfinished initialization can be observed without waiting for its monitor`() {
        val initializerStarted = CountDownLatch(1)
        val releaseInitializer = CountDownLatch(1)
        val value = NonBlockingSynchronizedLazy {
            initializerStarted.countDown()
            assertTrue(releaseInitializer.await(5, TimeUnit.SECONDS))
            "ready"
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val initialization = executor.submit<String> { value.get() }
            assertTrue(initializerStarted.await(5, TimeUnit.SECONDS))

            val nonBlockingRead = executor.submit<String?> { value.getOrNull() }
            assertNull(nonBlockingRead.get(1, TimeUnit.SECONDS))

            releaseInitializer.countDown()
            assertEquals("ready", initialization.get(5, TimeUnit.SECONDS))
            assertEquals("ready", value.getOrNull())
        } finally {
            releaseInitializer.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent blocking readers execute the initializer exactly once`() {
        val initializationCount = AtomicInteger()
        val value = NonBlockingSynchronizedLazy {
            initializationCount.incrementAndGet()
            Any()
        }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 32).map { executor.submit<Any> { value.get() } }
                .map { it.get(5, TimeUnit.SECONDS) }

            assertTrue(results.all { it === results.first() })
            assertEquals(1, initializationCount.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
