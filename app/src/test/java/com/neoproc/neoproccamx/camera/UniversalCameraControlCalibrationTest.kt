package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalCameraControlCalibrationTest {
    @Test
    fun `ordinary finite RGGB evidence preserves HAL confidence`() {
        assertEquals(
            0.7f,
            UniversalCameraControlCalibration.whiteBalanceEvidenceConfidence(
                baseConfidence = 0.7f,
                red = 1.8f,
                greenEven = 1.02f,
                greenOdd = 1f,
                blue = 1.45f
            ),
            1e-6f
        )
    }

    @Test
    fun `corrupt WB evidence fails closed`() {
        assertEquals(
            0f,
            UniversalCameraControlCalibration.whiteBalanceEvidenceConfidence(
                1f,
                Float.NaN,
                1f,
                1f,
                1f
            ),
            0f
        )
        assertEquals(
            0f,
            UniversalCameraControlCalibration.whiteBalanceEvidenceConfidence(
                1f,
                1f,
                1f,
                1.5f,
                1f
            ),
            0f
        )
    }

    @Test
    fun `cadence scale follows sensor time and is bounded`() {
        val start = 1_000_000_000L
        assertEquals(1f, UniversalCameraControlCalibration.cadenceScale(null, start), 0f)
        assertEquals(
            0.5f,
            UniversalCameraControlCalibration.cadenceScale(start, start + 16_666_667L),
            1e-5f
        )
        assertEquals(
            2f,
            UniversalCameraControlCalibration.cadenceScale(start, start + 66_666_667L),
            1e-5f
        )
        assertEquals(4f, UniversalCameraControlCalibration.cadenceScale(start, start + 1_000_000_000L), 0f)
    }

    @Test
    fun `cadence adjusted smoothing preserves response per elapsed second`() {
        val halfFrame = UniversalCameraControlCalibration.cadenceAdjustedSmoothing(0.5f, 0.5f)
        val doubleFrame = UniversalCameraControlCalibration.cadenceAdjustedSmoothing(0.5f, 2f)
        assertEquals(0.5f, UniversalCameraControlCalibration.cadenceAdjustedSmoothing(0.5f, 1f), 0f)
        assertTrue(halfFrame < 0.5f)
        assertTrue(doubleFrame > 0.5f)
        assertEquals(0.5f, 1f - (1f - halfFrame) * (1f - halfFrame), 1e-6f)
    }

    @Test
    fun `governor reaches equivalent white point after one second at 24 and 60 fps`() {
        fun run(fps: Int): ColorChromaticity {
            val governor = ColorDriftGovernor()
            val startNs = 1_000_000_000L
            governor.accept(1f, 1f, 1f, 1f, 0.001f, sensorTimestampNs = startNs)
            for (frame in 1..fps) {
                governor.accept(
                    red = 2.2f,
                    greenEven = 1f,
                    greenOdd = 1f,
                    blue = 0.8f,
                    maxFrameToFrameColorDriftUv = 0.001f,
                    sensorTimestampNs = startNs + frame * (1_000_000_000L / fps)
                )
            }
            return requireNotNull(governor.stabilizedWhitePoint)
        }

        val at24 = run(24)
        val at60 = run(60)
        val distance = kotlin.math.sqrt(
            (at24.u - at60.u) * (at24.u - at60.u) +
                (at24.v - at60.v) * (at24.v - at60.v)
        )
        assertTrue("sensor-time normalization must remove FPS-dependent color lag", distance < 0.001f)
    }
}
