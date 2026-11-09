package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraImageRecordingExpectationPolicyTest {
    @Test
    fun `only an active encoder with an open live gate owns recording frames`() {
        assertTrue(
            CameraImageRecordingExpectationPolicy.isLiveSubmissionExpected(
                recordingPath = true,
                offscreenEncoderActive = true,
                offscreenSubmissionEnabled = true
            )
        )
    }

    @Test
    fun `persistent prearm and post-stop frames remain outside the clip`() {
        assertFalse(
            CameraImageRecordingExpectationPolicy.isLiveSubmissionExpected(
                recordingPath = true,
                offscreenEncoderActive = false,
                offscreenSubmissionEnabled = true
            )
        )
        assertFalse(
            CameraImageRecordingExpectationPolicy.isLiveSubmissionExpected(
                recordingPath = true,
                offscreenEncoderActive = true,
                offscreenSubmissionEnabled = false
            )
        )
        assertFalse(
            CameraImageRecordingExpectationPolicy.isLiveSubmissionExpected(
                recordingPath = false,
                offscreenEncoderActive = true,
                offscreenSubmissionEnabled = true
            )
        )
    }
}
