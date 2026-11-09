package com.neoproc.neoproccamx.rendering

import android.hardware.camera2.CameraCharacteristics
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewRotationPolicyTest {

    @Test
    fun zeroDegreeRotationStaysZero() {
        assertEquals(
            0,
            PreviewRotationPolicy.resolve(
                rotationDegrees = 0,
                sensorOrientation = 270,
                lensFacing = CameraCharacteristics.LENS_FACING_FRONT
            )
        )
    }

    @Test
    fun ninetyDegreeRotationIsPreserved() {
        assertEquals(
            90,
            PreviewRotationPolicy.resolve(
                rotationDegrees = 90,
                sensorOrientation = 90,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK
            )
        )
    }

    @Test
    fun oneEightyRotationIsPreserved() {
        assertEquals(
            180,
            PreviewRotationPolicy.resolve(
                rotationDegrees = 180,
                sensorOrientation = 270,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK
            )
        )
    }

    @Test
    fun twoSeventyRotationIsPreserved() {
        assertEquals(
            270,
            PreviewRotationPolicy.resolve(
                rotationDegrees = 270,
                sensorOrientation = 270,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK
            )
        )
    }

    @Test
    fun strictMode_snapsNonQuarterRotationToNearestQuarterTurn() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false
            val nonStrict = PreviewRotationPolicy.resolve(
                rotationDegrees = 44,
                sensorOrientation = 90,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK
            )

            HighEndQualityPolicy.strictHighEndOnly = true
            val strict = PreviewRotationPolicy.resolve(
                rotationDegrees = 44,
                sensorOrientation = 90,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK
            )

            assertEquals(44, nonStrict)
            assertEquals(0, strict)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
