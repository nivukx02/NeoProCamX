package com.neoproc.neoproccamx.ui

import android.hardware.camera2.CameraCharacteristics
import com.neoproc.neoproccamx.camera.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFacingSwitchPolicyTest {

    @Test
    fun `switch plan blocks while recording`() {
        val plan = CameraFacingSwitchPolicy.buildPlan(
            currentFacing = CameraCharacteristics.LENS_FACING_BACK,
            hasTargetFacing = true,
            isRecordingOrStopping = true,
            cameraState = CameraState.Streaming
        )

        assertFalse(plan.shouldProceed)
        assertEquals("recording", plan.blockedReason)
        assertEquals(CameraCharacteristics.LENS_FACING_FRONT, plan.targetFacing)
    }

    @Test
    fun `switch plan blocks when target facing is unavailable`() {
        val plan = CameraFacingSwitchPolicy.buildPlan(
            currentFacing = CameraCharacteristics.LENS_FACING_FRONT,
            hasTargetFacing = false,
            isRecordingOrStopping = false,
            cameraState = CameraState.Closed
        )

        assertFalse(plan.shouldProceed)
        assertEquals("missing_target_facing", plan.blockedReason)
        assertEquals(CameraCharacteristics.LENS_FACING_BACK, plan.targetFacing)
    }

    @Test
    fun `switch plan requests restart for live states`() {
        val plan = CameraFacingSwitchPolicy.buildPlan(
            currentFacing = CameraCharacteristics.LENS_FACING_BACK,
            hasTargetFacing = true,
            isRecordingOrStopping = false,
            cameraState = CameraState.Open
        )

        assertTrue(plan.shouldProceed)
        assertTrue(plan.shouldRestartCamera)
    }
}
