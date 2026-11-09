package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCameraFpsAlignmentPolicyTest {

    @Test
    fun `video recording encoder follows camera applied fps`() {
        val plan = RecordingCameraFpsAlignmentPolicy.resolve(
            requestedEncoderFps = 60,
            appliedCameraFps = 30,
            videoModeActive = true
        )

        assertEquals(30, plan.encoderFps)
        assertEquals(30, plan.previewFps)
        assertTrue(plan.alignedToCamera)
    }

    @Test
    fun `matching camera fps keeps requested encoder fps`() {
        val plan = RecordingCameraFpsAlignmentPolicy.resolve(
            requestedEncoderFps = 60,
            appliedCameraFps = 60,
            videoModeActive = true
        )

        assertEquals(60, plan.encoderFps)
        assertEquals(60, plan.previewFps)
        assertFalse(plan.alignedToCamera)
    }

    @Test
    fun `non video path keeps requested fps`() {
        val plan = RecordingCameraFpsAlignmentPolicy.resolve(
            requestedEncoderFps = 60,
            appliedCameraFps = 30,
            videoModeActive = false
        )

        assertEquals(60, plan.encoderFps)
        assertEquals(60, plan.previewFps)
        assertFalse(plan.alignedToCamera)
    }
}
