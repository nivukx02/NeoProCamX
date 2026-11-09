package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPreviewStartFailurePolicyTest {

    @Test
    fun closedDeviceDuringTeardownIsIgnored() {
        val decision = CameraPreviewStartFailurePolicy.resolve(
            error = IllegalStateException("CameraDevice was already closed"),
            closingCamera = true
        )

        assertTrue(decision.shouldIgnoreFailure)
        assertFalse(decision.shouldSurfaceError)
        assertEquals(
            "Preview start failed: CameraDevice was already closed",
            decision.message
        )
    }

    @Test
    fun closedDeviceFromStaleSessionIsIgnored() {
        val decision = CameraPreviewStartFailurePolicy.resolve(
            error = IllegalStateException("CameraDevice was already closed"),
            sessionStillCurrent = false
        )

        assertTrue(decision.shouldIgnoreFailure)
        assertFalse(decision.shouldSurfaceError)
    }

    @Test
    fun closedCurrentSessionRequestsRecoveryInsteadOfSurfacingError() {
        val decision = CameraPreviewStartFailurePolicy.resolve(
            error = IllegalStateException("Session has been closed; further changes are illegal.")
        )

        assertFalse(decision.shouldIgnoreFailure)
        assertFalse(decision.shouldSurfaceError)
        assertEquals(
            "Preview start failed: Session has been closed; further changes are illegal.",
            decision.message
        )
    }

    @Test
    fun unrelatedIllegalStateSurfacesAsError() {
        val decision = CameraPreviewStartFailurePolicy.resolve(
            error = IllegalStateException("Surface target is invalid")
        )

        assertFalse(decision.shouldIgnoreFailure)
        assertTrue(decision.shouldSurfaceError)
        assertEquals(
            "Preview start failed: Surface target is invalid",
            decision.message
        )
    }

    @Test
    fun unconfiguredSurfaceFailureIsRecoverableBySessionRebuild() {
        assertTrue(
            CameraPreviewStartFailurePolicy.isUnconfiguredSurfaceFailure(
                IllegalArgumentException("CaptureRequest contains unconfigured Input/Output Surface!")
            )
        )

        assertFalse(
            CameraPreviewStartFailurePolicy.isUnconfiguredSurfaceFailure(
                IllegalArgumentException("Surface target is invalid")
            )
        )
    }
}
