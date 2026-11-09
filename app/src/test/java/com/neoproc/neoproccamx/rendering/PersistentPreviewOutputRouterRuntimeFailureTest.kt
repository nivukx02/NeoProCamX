package com.neoproc.neoproccamx.rendering

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentPreviewOutputRouterRuntimeFailureTest {

    @Test
    fun `runtime EGL failure remains typed in the strict admission result channel`() {
        val result = PersistentPreviewOutputRouter.StartResult.failure(
            PersistentPreviewOutputRouter.StartFailure.RUNTIME_EGL_FAILURE,
            "output router eglSwapBuffers failed"
        )

        assertFalse(result.started)
        assertEquals(PersistentPreviewOutputRouter.StartFailure.RUNTIME_EGL_FAILURE, result.failure)
    }

    @Test
    fun `failure gate is inert until a successful strict start and then claims once`() {
        val gate = PreviewRuntimeFailureGate()

        assertFalse(gate.tryClaimActiveFailure())
        gate.armAfterSuccessfulStart()
        assertTrue(gate.tryClaimActiveFailure())
        assertFalse(gate.tryClaimActiveFailure())

        gate.armAfterSuccessfulStart()
        assertTrue(gate.tryClaimActiveFailure())
        assertFalse(gate.tryClaimActiveFailure())
    }

    @Test
    fun `concurrent failures produce exactly one claim per successful start`() {
        val gate = PreviewRuntimeFailureGate()
        val pool = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(8)
        val claims = AtomicInteger(0)
        gate.armAfterSuccessfulStart()

        repeat(8) {
            pool.execute {
                try {
                    ready.countDown()
                    start.await()
                    if (gate.tryClaimActiveFailure()) claims.incrementAndGet()
                } finally {
                    finished.countDown()
                }
            }
        }

        try {
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
        assertEquals(1, claims.get())
    }

    @Test
    fun `router reports both runtime EGL failures and fails closed before callback`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/PersistentPreviewOutputRouter.kt"
        ).readText()
        val drawBlock = source
            .substringAfter("private fun drawFrame()")
            .substringBefore("private fun reportRuntimeFailure(")
        val reportBlock = source
            .substringAfter("private fun reportRuntimeFailure(")
            .substringBefore("private fun emitIfComplete(")

        assertTrue(drawBlock.contains("PreviewRuntimeOperation.EGL_MAKE_CURRENT"))
        assertTrue(drawBlock.contains("PreviewRuntimeOperation.EGL_SWAP_BUFFERS"))
        assertTrue(drawBlock.contains("if (!running) return"))
        assertEquals(
            2,
            Regex("val eglError = EGL14\\.eglGetError\\(\\)").findAll(drawBlock).count()
        )
        assertTrue(reportBlock.contains("runtimeFailureGate.tryClaimActiveFailure()"))
        assertTrue(reportBlock.contains("PreviewRuntimeComponent.OUTPUT_ROUTER"))
        assertTrue(reportBlock.contains("StartFailure.RUNTIME_EGL_FAILURE"))
        assertTrue(reportBlock.indexOf("running = false") < reportBlock.indexOf("listener(failure)"))
        assertFalse(reportBlock.contains("startWithResult("))
        assertFalse(reportBlock.contains("fallback", ignoreCase = true))
    }
}
