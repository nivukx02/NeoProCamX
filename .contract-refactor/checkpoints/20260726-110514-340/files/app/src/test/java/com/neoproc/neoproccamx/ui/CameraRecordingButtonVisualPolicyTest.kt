package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRecordingButtonVisualPolicyTest {

    @Test
    fun resolve_returnsIdleCircleStateWhenNotRecording() {
        val result = CameraRecordingButtonVisualPolicy.resolve(recording = false)

        assertEquals(1.0f, result.centerScale, 0.001f)
        assertEquals(0.50f, result.cornerRadiusRatio, 0.001f)
        assertTrue(result.outerRingVisible)
        assertEquals(1.0f, result.outerRingAlpha, 0.001f)
        assertFalse(result.pulseVisible)
    }

    @Test
    fun resolve_returnsRedStopStateWhenRecording() {
        val result = CameraRecordingButtonVisualPolicy.resolve(recording = true)

        assertEquals(0.50f, result.centerScale, 0.001f)
        assertEquals(0.18f, result.cornerRadiusRatio, 0.001f)
        assertTrue(result.outerRingVisible)
        assertEquals(1.0f, result.outerRingAlpha, 0.001f)
        assertTrue(result.pulseVisible)
    }
}
