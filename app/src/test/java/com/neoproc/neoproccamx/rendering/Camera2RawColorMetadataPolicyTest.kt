package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.NeoGamutCat16Math
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2RawColorMetadataPolicyTest {

    @Test
    fun vivoCaptureResultTransformBecomesNeutralSafeD50SensorMatrix() {
        val vivoSensorToLinearSrgb = floatArrayOf(
            1.34375f, -0.42969f, 0.07813f,
            -0.12500f, 1.33594f, -0.21094f,
            -0.01563f, -0.53906f, 1.55469f
        )

        val result = Camera2RawColorMetadataPolicy.resolveSensorToXyzD50(
            captureResultSensorToLinearSrgb = vivoSensorToLinearSrgb,
            dngForwardFallback = DEFAULT_SENSOR_TO_XYZ
        )

        assertEquals(
            Camera2RawColorTransformSource.CAPTURE_RESULT_LINEAR_SRGB,
            result.source
        )
        assertTrue(ProjectOwnedNdrColorProof.validateNeutralAxis(result.sensorToXyzD50).passed)

        // The result CCM maps a neutral sensor vector to neutral linear sRGB (within its 1/128
        // quantization); after D65->D50 it must land on the D50 profile-connection white.
        val xyz = NeoGamutCat16Math.multiplyVector(
            result.sensorToXyzD50,
            floatArrayOf(1f, 1f, 1f)
        )
        assertEquals(0.96422f, xyz[0], 0.015f)
        assertEquals(1.0f, xyz[1], 0.015f)
        assertEquals(0.82521f, xyz[2], 0.015f)
    }

    @Test
    fun invalidOrChromaticResultTransformRetainsDngForwardFallbackExactly() {
        val fallback = floatArrayOf(
            0.75f, 0.15f, 0.06422f,
            0.20f, 0.70f, 0.10f,
            0.05f, 0.15f, 0.62521f
        )
        val invalid = floatArrayOf(
            Float.NaN, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f
        )

        val result = Camera2RawColorMetadataPolicy.resolveSensorToXyzD50(invalid, fallback)

        assertEquals(Camera2RawColorTransformSource.DNG_FORWARD_MATRIX_FALLBACK, result.source)
        assertArrayEquals(fallback, result.sensorToXyzD50, 0f)
        assertTrue(result.sensorToXyzD50 !== fallback)
    }

    @Test
    fun bggrMapsEvenOddGreensToTheirSemanticRows() {
        val bggr = Camera2RawColorMetadataPolicy.semanticWhiteBalanceRggb(
            red = 2.2f,
            greenEven = 0.91f,
            greenOdd = 1.07f,
            blue = 1.5f,
            bayerPattern = BayerPattern.BGGR
        )
        val rggb = Camera2RawColorMetadataPolicy.semanticWhiteBalanceRggb(
            red = 2.2f,
            greenEven = 0.91f,
            greenOdd = 1.07f,
            blue = 1.5f,
            bayerPattern = BayerPattern.RGGB
        )

        assertArrayEquals(floatArrayOf(2.2f, 1.07f, 0.91f, 1.5f), bggr, 0f)
        assertArrayEquals(floatArrayOf(2.2f, 0.91f, 1.07f, 1.5f), rggb, 0f)
    }
}
