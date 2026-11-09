package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.rendering.RawBufferDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-0 golden lock for the RAW calibration + heteroscedastic-noise boundary (s1-f03, feeds CP12).
 *
 * Freezes, for fixed metadata fixtures, the resolved black/white level and the exact `(noiseS, noiseO)`
 * the RAW buffer descriptor hands to the cinema-debayer / MFSR / denoise consumer. Any future change to
 * the calibration math or the noise plumbing then surfaces as a deliberate, reviewed delta here —
 * never a silent regression of the values the consumer reads (NO-DOWNGRADE / GOLDEN-FIRST).
 */
class RawCalibrationNoiseGoldenTest {

    @Test
    fun goldenCalibrationPlanForTwelveBitDynamicMetadataFixture() {
        val plan = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 4095,
            dynamicBlackLevel = floatArrayOf(64f, 64f, 64f, 64f),
            blackLevelOffsets = intArrayOf(64, 64, 64, 64),
            strictHighEnd = true
        )

        assertEquals(64, plan.blackLevel)
        assertEquals(4095, plan.whiteLevel)
        assertFalse(plan.usedFallbackBlackLevel)
    }

    @Test
    fun goldenNoiseInputsForFixedProfileFixture() {
        val model = CameraSensorNoiseModelPolicy.resolveDescriptorModel(
            captureNoiseProfile = listOf(
                0.004 to 0.0009,
                0.006 to 0.0011,
                0.005 to 0.0010,
                0.007 to 0.0012
            ),
            sensitivityRange = 50 to 6400
        )
        val inputs = CameraSensorNoiseModelPolicy.descriptorNoiseInputs(model)

        // Channel-averaged shot/read coefficients, locked.
        assertEquals(0.0055f, inputs.noiseS, 1e-5f)
        assertEquals(0.00105f, inputs.noiseO, 1e-5f)
    }

    @Test
    fun goldenDescriptorCarriesResolvedNonZeroNoiseForFixture() {
        val model = CameraSensorNoiseModelPolicy.resolveDescriptorModel(
            captureNoiseProfile = listOf(
                0.004 to 0.0009,
                0.006 to 0.0011,
                0.005 to 0.0010,
                0.007 to 0.0012
            ),
            sensitivityRange = 50 to 6400
        )
        val inputs = CameraSensorNoiseModelPolicy.descriptorNoiseInputs(model)

        val descriptor = RawBufferDescriptor(
            width = 4000,
            height = 3000,
            stride = 8000,
            blackLevel = 64,
            whiteLevel = 4095,
            noiseS = inputs.noiseS,
            noiseO = inputs.noiseO
        )

        // The descriptor the consumer receives carries the real, non-zero profile noise — bit-for-bit.
        assertEquals(inputs.noiseS, descriptor.noiseS, 0f)
        assertEquals(inputs.noiseO, descriptor.noiseO, 0f)
        assertTrue(descriptor.noiseS > 0f)
        assertTrue(descriptor.noiseO > 0f)
    }
}
