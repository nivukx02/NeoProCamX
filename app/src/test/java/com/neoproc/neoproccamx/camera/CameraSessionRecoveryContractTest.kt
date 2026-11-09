package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s1-f05 — session recovery contract. Locks that a simulated HAL error recovers to a valid session
 * within a bounded window and that the recovery sequence releases surfaces/readers BEFORE rebuilding
 * (no surface leak by construction). Pure-JVM; complements the live close-on-failure paths in
 * CameraController (which the source-text contract test pins).
 */
class CameraSessionRecoveryContractTest {

    @Test
    fun `device-closed illegal state during a current session triggers a camera reopen`() {
        val decision = CameraSessionCreateFailurePolicy.resolve(
            error = IllegalStateException("Camera device was closed"),
            sessionGenerationCurrent = true,
            cameraDeviceStillCurrent = true,
            closingCamera = false
        )
        assertTrue(decision.shouldRecoverCamera)
        assertFalse(decision.shouldIgnoreFailure)
    }

    @Test
    fun `failure during teardown is ignored not recovered`() {
        val decision = CameraSessionCreateFailurePolicy.resolve(
            error = IllegalStateException("Camera device was closed"),
            sessionGenerationCurrent = true,
            cameraDeviceStillCurrent = true,
            closingCamera = true
        )
        assertFalse(decision.shouldRecoverCamera)
        assertTrue(decision.shouldIgnoreFailure)
    }

    @Test
    fun `stale generation or replaced device is ignored not recovered`() {
        val staleGeneration = CameraSessionCreateFailurePolicy.resolve(
            error = IllegalStateException("camera has been closed"),
            sessionGenerationCurrent = false,
            cameraDeviceStillCurrent = true,
            closingCamera = false
        )
        assertTrue(staleGeneration.shouldIgnoreFailure)
        assertFalse(staleGeneration.shouldRecoverCamera)

        val replacedDevice = CameraSessionCreateFailurePolicy.resolve(
            error = IllegalStateException("already closed"),
            sessionGenerationCurrent = true,
            cameraDeviceStillCurrent = false,
            closingCamera = false
        )
        assertTrue(replacedDevice.shouldIgnoreFailure)
        assertFalse(replacedDevice.shouldRecoverCamera)
    }

    @Test
    fun `a non camera-loss illegal state is neither ignored nor a reopen`() {
        val decision = CameraSessionCreateFailurePolicy.resolve(
            error = IllegalStateException("Surface was abandoned"),
            sessionGenerationCurrent = true,
            cameraDeviceStillCurrent = true,
            closingCamera = false
        )
        assertFalse(decision.shouldRecoverCamera)
        assertFalse(decision.shouldIgnoreFailure)
    }

    @Test
    fun `isolated session reset is bounded and non blocking`() {
        val contract = CameraSessionResetContract.resolve(reason = "hal-error", nowElapsedMs = 1_000L)
        assertEquals(200L, contract.targetRecoveryWindowMs)
        assertTrue(contract.nonBlocking)
        // A non-blocking recovery must never include the UI-thread block step.
        assertFalse(contract.steps.contains(CameraSessionResetStep.BLOCK_UI_THREAD))
        assertTrue(contract.signature.contains("target=200ms"))
        assertTrue(contract.signature.contains("nonBlocking=true"))
    }

    @Test
    fun `recovery releases surfaces and readers before rebuilding the session (no leak by construction)`() {
        val steps = CameraSessionResetContract.resolve(reason = "hal-error", nowElapsedMs = 0L).steps
        val closeReaders = steps.indexOf(CameraSessionResetStep.CLOSE_FAILING_READERS)
        val releaseSurfaces = steps.indexOf(CameraSessionResetStep.RELEASE_BOUND_SURFACES)
        val flushBuffers = steps.indexOf(CameraSessionResetStep.FLUSH_NATIVE_BUFFERS)
        val rebuild = steps.indexOf(CameraSessionResetStep.REBUILD_CAPTURE_SESSION)

        assertTrue("readers must close before the rebuild", closeReaders in 0 until rebuild)
        assertTrue("surfaces must be released before the rebuild", releaseSurfaces in 0 until rebuild)
        assertTrue("native buffers must flush before the rebuild", flushBuffers in 0 until rebuild)
        // The release ordering is close-readers → release-surfaces → flush-buffers → … → rebuild.
        assertTrue(closeReaders < releaseSurfaces)
        assertTrue(releaseSurfaces < flushBuffers)
        assertTrue(flushBuffers < rebuild)
    }
}
