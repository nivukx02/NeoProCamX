package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactMetadataWaiterTest {
    @Test
    fun `result first resolves immediately without sleeping`() {
        val lock = ReentrantLock()
        val waiter = ExactMetadataWaiter(lock)

        val resolved = waiter.await(timeoutMs = 1_000L) {
            ExactMetadataWaiter.Probe.Ready("already-exact")
        }

        assertEquals("already-exact", resolved)
    }

    @Test
    fun `image first waits without holding lock and exact result wakes writer`() {
        val lock = ReentrantLock()
        val waiter = ExactMetadataWaiter(lock)
        val firstProbe = CountDownLatch(1)
        val results = mutableMapOf<Long, String>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<String?> {
                waiter.await(timeoutMs = 1_000L) {
                    results[100L]?.let { ExactMetadataWaiter.Probe.Ready(it) }
                        ?: run {
                            firstProbe.countDown()
                            ExactMetadataWaiter.Probe.Pending
                        }
                }
            }

            assertTrue(firstProbe.await(1L, TimeUnit.SECONDS))
            // If await retained the lock, this publication would deadlock until timeout.
            assertTrue(lock.tryLock(1L, TimeUnit.SECONDS))
            try {
                results[100L] = "exact-100"
                waiter.signalChangedLocked()
            } finally {
                lock.unlock()
            }

            assertEquals("exact-100", future.get(1L, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `adjacent timestamp never satisfies authoritative wait`() {
        val lock = ReentrantLock()
        val waiter = ExactMetadataWaiter(lock)
        val results = mutableMapOf(101L to "adjacent")

        val resolved = waiter.await(timeoutMs = 20L) {
            results[100L]?.let { ExactMetadataWaiter.Probe.Ready(it) }
                ?: ExactMetadataWaiter.Probe.Pending
        }

        assertNull(resolved)
        assertEquals("adjacent", results[101L])
    }

    @Test
    fun `terminal teardown signal wakes pending writer fail closed`() {
        val lock = ReentrantLock()
        val waiter = ExactMetadataWaiter(lock)
        val firstProbe = CountDownLatch(1)
        var terminal = false
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<String?> {
                waiter.await(timeoutMs = 10_000L) {
                    if (terminal) {
                        ExactMetadataWaiter.Probe.Terminal
                    } else {
                        firstProbe.countDown()
                        ExactMetadataWaiter.Probe.Pending
                    }
                }
            }

            assertTrue(firstProbe.await(1L, TimeUnit.SECONDS))
            lock.withLock {
                terminal = true
                waiter.signalChangedLocked()
            }
            assertNull(future.get(1L, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `owner replacement cannot relabel waiting image into resumed generation`() {
        val lock = ReentrantLock()
        val waiter = ExactMetadataWaiter(lock)
        val ownerA = Any()
        val ownerB = Any()
        var currentOwner: Any = ownerA
        var expectedOwner: Any? = null
        val firstProbe = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<String?> {
                waiter.await(timeoutMs = 1_000L) {
                    if (expectedOwner == null) expectedOwner = currentOwner
                    if (currentOwner !== expectedOwner) {
                        ExactMetadataWaiter.Probe.Terminal
                    } else {
                        firstProbe.countDown()
                        ExactMetadataWaiter.Probe.Pending
                    }
                }
            }

            assertTrue(firstProbe.await(1L, TimeUnit.SECONDS))
            lock.withLock {
                currentOwner = ownerB
                waiter.signalChangedLocked()
            }
            assertNull(future.get(1L, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }
}
