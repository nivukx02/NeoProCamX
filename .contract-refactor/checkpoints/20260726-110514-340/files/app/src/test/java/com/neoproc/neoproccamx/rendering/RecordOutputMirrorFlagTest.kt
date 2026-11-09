package com.neoproc.neoproccamx.rendering

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordOutputMirrorFlagTest {

    @Test
    fun `portrait recording flips encoded vertical axis only`() {
        val plan = RecordOutputMirrorFlag.resolveForRecording(
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            sensorOrientationDegrees = 90,
            orientationHintDegrees = 90
        )

        assertFalse(plan.flipX)
        assertTrue(plan.flipY)
        assertTrue(plan.source.contains("output=90"))
    }

    @Test
    fun `landscape recording flips encoded horizontal axis only`() {
        val plan = RecordOutputMirrorFlag.resolveForRecording(
            lensFacing = CameraCharacteristics.LENS_FACING_BACK,
            sensorOrientationDegrees = 90,
            orientationHintDegrees = 0
        )

        assertTrue(plan.flipX)
        assertFalse(plan.flipY)
        assertTrue(plan.source.contains("output=0"))
    }
}
