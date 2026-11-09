package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class HardwareImageReaderAcquisitionGateTest {

    @Test
    fun `listener acquisition is nonblocking and exclusive from deferred acquisition`() {
        val gate = HardwareImageReaderAcquisitionGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val owner = worker.submit {
                gate.enterDeferred().use {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            }

            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertNull(gate.tryEnterListener())
            release.countDown()
            owner.get(5, TimeUnit.SECONDS)

            gate.tryEnterListener().use { lease ->
                assertTrue(lease != null)
            }
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun `dispatch queue remains operable while image reader acquisition is owned`() {
        val gate = HardwareImageReaderAcquisitionGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val closed = AtomicBoolean(false)
        try {
            val owner = worker.submit {
                gate.enterDeferred().use {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            val dispatcher = RealtimeFrameDispatcher(
                capacity = 2,
                acquireGuardNs = 1L,
                closeFrame = { _: String -> closed.set(true) },
                allowsStaleFrameConflation = false
            )
            val offered = dispatcher.offer("frame", sensorTimestampNs = 10L, nowNs = 20L)
            val polled = dispatcher.poll(nowNs = 30L)

            assertTrue(offered.accepted)
            assertEquals("frame", polled?.frame)
            assertFalse(closed.get())
            release.countDown()
            owner.get(5, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }
}
