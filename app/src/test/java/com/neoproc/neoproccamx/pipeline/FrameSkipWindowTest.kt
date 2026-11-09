package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FrameSkipWindowTest {

    @Test
    fun shouldSkipFramesWhenWindowExceedsTargetFps() {
        val window = FrameSkipWindow(targetFps = 3, windowSizeMs = 1_000)

        assertTrue(window.shouldProcessFrame(0))
        assertTrue(window.shouldProcessFrame(100))
        assertFalse(window.shouldProcessFrame(200))
    }

    @Test
    fun concurrentAccess_doesNotThrowIndexErrors() {
        val window = FrameSkipWindow(targetFps = 120, windowSizeMs = 1_000)
        val failure = AtomicReference<Throwable?>(null)
        val pool = Executors.newFixedThreadPool(4)

        repeat(1_000) {
            pool.execute {
                try {
                    window.shouldProcessFrame(System.currentTimeMillis())
                    window.getCurrentFps()
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }

        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)
        assertNull("FrameSkipWindow should be thread-safe under concurrent calls", failure.get())
    }
}
