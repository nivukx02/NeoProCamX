package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VulkanCameraImportSessionGateTest {
    @Test
    fun `only the current camera generation can acquire an import lease`() {
        val gate = VulkanCameraImportSessionGate()

        assertTrue(gate.beginSession(7L))
        assertNotNull(gate.acquireSubmission(7L)?.also { it.close() })
        assertNull(gate.acquireSubmission(6L))
        assertFalse(gate.beginSession(6L))
        assertEquals(7L, gate.activeGeneration())
    }

    @Test
    fun `session replacement waits for admitted import and retires its generation atomically`() {
        val gate = VulkanCameraImportSessionGate()
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacementAttempted = CountDownLatch(1)
        val retired = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        assertTrue(gate.beginSession(3L))
        try {
            val submission = executor.submit {
                gate.acquireSubmission(3L)!!.use {
                    admitted.countDown()
                    assertTrue(release.await(1L, TimeUnit.SECONDS))
                }
            }
            assertTrue(admitted.await(1L, TimeUnit.SECONDS))
            val replacement = executor.submit {
                replacementAttempted.countDown()
                assertTrue(gate.beginSession(4L))
                retired.countDown()
            }

            assertTrue(replacementAttempted.await(1L, TimeUnit.SECONDS))
            assertFalse(retired.await(50L, TimeUnit.MILLISECONDS))
            release.countDown()
            submission.get(1L, TimeUnit.SECONDS)
            replacement.get(1L, TimeUnit.SECONDS)

            assertNull(gate.acquireSubmission(3L))
            assertNotNull(gate.acquireSubmission(4L)?.also { it.close() })
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1L, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `exclusive reset cannot race an admitted native import`() {
        val gate = VulkanCameraImportSessionGate()
        val admitted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resetAttempted = CountDownLatch(1)
        val resetEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        assertTrue(gate.beginSession(11L))
        try {
            val submission = executor.submit {
                gate.acquireSubmission(11L)!!.use {
                    admitted.countDown()
                    assertTrue(release.await(1L, TimeUnit.SECONDS))
                }
            }
            assertTrue(admitted.await(1L, TimeUnit.SECONDS))
            val reset = executor.submit {
                resetAttempted.countDown()
                gate.withExclusiveMutation { resetEntered.countDown() }
            }

            assertTrue(resetAttempted.await(1L, TimeUnit.SECONDS))
            assertFalse(resetEntered.await(50L, TimeUnit.MILLISECONDS))
            release.countDown()
            submission.get(1L, TimeUnit.SECONDS)
            reset.get(1L, TimeUnit.SECONDS)
            assertTrue(resetEntered.await(1L, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1L, TimeUnit.SECONDS))
        }
    }
}
