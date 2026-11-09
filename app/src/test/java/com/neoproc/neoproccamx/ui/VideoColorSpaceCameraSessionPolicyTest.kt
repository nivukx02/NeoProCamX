package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoColorSpaceCameraSessionPolicyTest {

    @Test
    fun `sensor linear ndr color change stays downstream and does not rebuild camera`() {
        assertFalse(
            VideoColorSpaceCameraSessionPolicy.shouldMutateCameraSession(
                sensorLinearNdrActive = true
            )
        )
    }

    @Test
    fun `processed camera color change retains normal session refresh behavior`() {
        assertTrue(
            VideoColorSpaceCameraSessionPolicy.shouldMutateCameraSession(
                sensorLinearNdrActive = false
            )
        )
    }
}
