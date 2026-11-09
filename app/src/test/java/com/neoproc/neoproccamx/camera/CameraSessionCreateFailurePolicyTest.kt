package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionCreateFailurePolicyTest {

    @Test
    fun recoverableIllegalStateRequestsCameraReopen() {
        val decision = CameraSessionCreateFailurePolicy.resolve(
            IllegalStateException("CameraDevice was already closed")
        )

        assertTrue(decision.shouldRecoverCamera)
        assertFalse(decision.shouldIgnoreFailure)
        assertEquals(
            "Session create failed: CameraDevice was already closed",
            decision.message
        )
    }

    @Test
    fun unrelatedIllegalStateSurfacesAsError() {
        val decision = CameraSessionCreateFailurePolicy.resolve(
            IllegalStateException("Surface configuration is invalid")
        )

        assertFalse(decision.shouldRecoverCamera)
        assertFalse(decision.shouldIgnoreFailure)
        assertEquals(
            "Session create failed: Surface configuration is invalid",
            decision.message
        )
    }

    @Test
    fun staleClosedDeviceFailureIsIgnoredDuringSessionTransition() {
        val decision = CameraSessionCreateFailurePolicy.resolve(
            error = IllegalStateException("CameraDevice was already closed"),
            sessionGenerationCurrent = false,
            cameraDeviceStillCurrent = false
        )

        assertFalse(decision.shouldRecoverCamera)
        assertTrue(decision.shouldIgnoreFailure)
        assertEquals(
            "Session create failed: CameraDevice was already closed",
            decision.message
        )
    }
}
