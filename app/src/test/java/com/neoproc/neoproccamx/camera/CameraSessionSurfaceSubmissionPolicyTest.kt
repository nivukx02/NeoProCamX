package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSessionSurfaceSubmissionPolicyTest {
    @Test
    fun `stable current valid surface snapshot is submitted`() {
        val decision = evaluate()

        assertTrue(decision.shouldSubmit)
        assertEquals(CameraSessionSurfaceSubmissionPolicy.Rejection.NONE, decision.rejection)
    }

    @Test
    fun `surface replacement after fingerprint snapshot is rejected`() {
        val decision = evaluate(surfaceOwnershipEpochCurrent = false)

        assertFalse(decision.shouldSubmit)
        assertEquals(
            CameraSessionSurfaceSubmissionPolicy.Rejection.SURFACE_OWNERSHIP_CHANGED,
            decision.rejection
        )
        assertTrue(CameraSessionSurfaceSubmissionPolicy.isRecoverableSurfaceRace(decision.rejection))
    }

    @Test
    fun `abandoned surface is rejected immediately before submission`() {
        val decision = evaluate(allSurfacesValid = false)

        assertFalse(decision.shouldSubmit)
        assertEquals(
            CameraSessionSurfaceSubmissionPolicy.Rejection.INVALID_OR_ABANDONED_SURFACE,
            decision.rejection
        )
        assertTrue(CameraSessionSurfaceSubmissionPolicy.isRecoverableSurfaceRace(decision.rejection))
    }

    @Test
    fun `mode configuration mutation after snapshot is retried`() {
        val decision = evaluate(sessionConfigurationCurrent = false)

        assertFalse(decision.shouldSubmit)
        assertEquals(
            CameraSessionSurfaceSubmissionPolicy.Rejection.SESSION_CONFIGURATION_CHANGED,
            decision.rejection
        )
        assertTrue(CameraSessionSurfaceSubmissionPolicy.isRecoverableSurfaceRace(decision.rejection))
    }

    @Test
    fun `stale generation is rejected but not retried as a surface race`() {
        val decision = evaluate(sessionGenerationCurrent = false)

        assertFalse(decision.shouldSubmit)
        assertEquals(
            CameraSessionSurfaceSubmissionPolicy.Rejection.STALE_SESSION_GENERATION,
            decision.rejection
        )
        assertFalse(CameraSessionSurfaceSubmissionPolicy.isRecoverableSurfaceRace(decision.rejection))
    }

    @Test
    fun `surface abandoned exception is classified without matching unrelated failures`() {
        assertTrue(
            CameraSessionSurfaceSubmissionPolicy.isSurfaceLifecycleFailure(
                IllegalArgumentException("Surface was abandoned")
            )
        )
        assertFalse(
            CameraSessionSurfaceSubmissionPolicy.isSurfaceLifecycleFailure(
                IllegalArgumentException("unsupported stream combination")
            )
        )
    }

    private fun evaluate(
        cameraClosing: Boolean = false,
        cameraDeviceCurrent: Boolean = true,
        sessionGenerationCurrent: Boolean = true,
        surfaceOwnershipEpochCurrent: Boolean = true,
        surfaceIdentitySetCurrent: Boolean = true,
        sessionConfigurationCurrent: Boolean = true,
        allSurfacesValid: Boolean = true
    ) = CameraSessionSurfaceSubmissionPolicy.evaluate(
        cameraClosing = cameraClosing,
        cameraDeviceCurrent = cameraDeviceCurrent,
        sessionGenerationCurrent = sessionGenerationCurrent,
        surfaceOwnershipEpochCurrent = surfaceOwnershipEpochCurrent,
        surfaceIdentitySetCurrent = surfaceIdentitySetCurrent,
        sessionConfigurationCurrent = sessionConfigurationCurrent,
        allSurfacesValid = allSurfacesValid
    )
}
