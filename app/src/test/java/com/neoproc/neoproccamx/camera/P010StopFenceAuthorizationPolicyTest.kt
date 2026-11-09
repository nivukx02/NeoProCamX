package com.neoproc.neoproccamx.camera

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P010StopFenceAuthorizationPolicyTest {
    @Test
    fun `null or stale token can never execute delayed stop against replacement`() {
        val retiredToken = Any()
        val replacementToken = Any()
        val lock = Any()
        var stops = 0

        assertFalse(
            P010StopFenceAuthorizationPolicy.executeDelayedStop(
                requestedToken = null,
                activeToken = { replacementToken },
                serializationLock = lock
            ) { stops += 1 }
        )
        assertFalse(
            P010StopFenceAuthorizationPolicy.executeDelayedStop(
                requestedToken = retiredToken,
                activeToken = { replacementToken },
                serializationLock = lock
            ) { stops += 1 }
        )
        assertEquals(0, stops)
    }

    @Test
    fun `exact active token executes stop once`() {
        val exactToken = Any()
        val lock = Any()
        var stops = 0

        assertTrue(
            P010StopFenceAuthorizationPolicy.executeDelayedStop(
                requestedToken = exactToken,
                activeToken = { exactToken },
                serializationLock = lock
            ) { stops += 1 }
        )
        assertEquals(1, stops)
    }

    @Test
    fun `replacement committed before delayed callback authorization rejects stale stop`() {
        val retiredToken = Any()
        val replacementToken = Any()
        val lock = Any()
        val activeToken = AtomicReference<Any?>(retiredToken)
        val callbackReady = CountDownLatch(1)
        val stopExecuted = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val callback = synchronized(lock) {
                val future = executor.submit<Boolean> {
                    callbackReady.countDown()
                    P010StopFenceAuthorizationPolicy.executeDelayedStop(
                        requestedToken = retiredToken,
                        activeToken = activeToken::get,
                        serializationLock = lock
                    ) { stopExecuted.set(true) }
                }
                assertTrue(callbackReady.await(2L, TimeUnit.SECONDS))
                assertTrue(activeToken.compareAndSet(retiredToken, replacementToken))
                future
            }

            assertFalse(callback.get(2L, TimeUnit.SECONDS))
            assertFalse(stopExecuted.get())
            assertTrue(activeToken.get() === replacementToken)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `exact owner stop action is atomic with replacement admission`() {
        val retiringToken = Any()
        val replacementToken = Any()
        val lock = Any()
        val activeToken = AtomicReference<Any?>(retiringToken)
        val stopEntered = CountDownLatch(1)
        val replacementAttempting = CountDownLatch(1)
        val replacementCommitted = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val stop = executor.submit<Boolean> {
                P010StopFenceAuthorizationPolicy.executeDelayedStop(
                    requestedToken = retiringToken,
                    activeToken = activeToken::get,
                    serializationLock = lock
                ) {
                    stopEntered.countDown()
                    assertTrue(replacementAttempting.await(2L, TimeUnit.SECONDS))
                    assertFalse(replacementCommitted.get())
                }
            }
            val replacement = executor.submit {
                assertTrue(stopEntered.await(2L, TimeUnit.SECONDS))
                replacementAttempting.countDown()
                synchronized(lock) {
                    assertTrue(activeToken.compareAndSet(retiringToken, replacementToken))
                    replacementCommitted.set(true)
                }
            }

            assertTrue(stop.get(2L, TimeUnit.SECONDS))
            replacement.get(2L, TimeUnit.SECONDS)
            assertTrue(replacementCommitted.get())
            assertTrue(activeToken.get() === replacementToken)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `controller never posts a null token cleanup`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt"
        ).readText()
        val stop = source
            .substringAfter("private fun stopP010RecordingStateWithoutRetarget(")
            .substringBefore("/** Returns true when [request]")
        val nullBranch = stop
            .substringAfter("if (token == null) {")
            .substringBefore("if (handler == null || timeoutNanos <= 0L)")
        val post = stop.substringAfter("val posted = handler.post {")

        assertTrue(nullBranch.contains("stopVideoCapture(retargetRepeating = false)"))
        assertFalse(nullBranch.contains("handler.post"))
        assertFalse(stop.contains("token == null ||"))
        assertTrue(post.contains("P010StopFenceAuthorizationPolicy.executeDelayedStop("))
        assertTrue(post.contains("activeToken = activeP010StopBoundary::get"))
        assertTrue(post.contains("serializationLock = cameraSubmissionLock"))
        assertTrue(post.contains("stopVideoCapture(retargetRepeating = false)"))
    }
}
