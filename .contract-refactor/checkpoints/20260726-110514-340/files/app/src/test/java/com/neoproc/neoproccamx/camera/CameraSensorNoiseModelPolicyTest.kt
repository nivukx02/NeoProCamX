package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.core.ImaxStrictProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSensorNoiseModelPolicyTest {

    @Test
    fun `sensor noise profile resolves heteroscedastic shot plus read noise model`() {
        val model = CameraSensorNoiseModelPolicy.fromCoefficients(
            listOf(
                0.004 to 0.0009,
                0.006 to 0.0011,
                0.005 to 0.0010,
                0.007 to 0.0012
            )
        )

        assertEquals(0.0055f, model.shotNoiseCoefficient, 0.0001f)
        assertEquals(0.00105f, model.readNoiseCoefficient, 0.0001f)
        assertEquals(0.0038f, model.varianceForLinearLuma(0.50f), 0.0001f)
        assertTrue(model.signature.contains("heteroscedastic"))
    }

    @Test
    fun `missing hal noise profile returns conservative bounded default`() {
        val model = CameraSensorNoiseModelPolicy.fromNoiseProfile(null)

        assertEquals(ImaxStrictProfile.SHOT_NOISE_COEFFICIENT, model.shotNoiseCoefficient, 0.0000001f)
        assertEquals(ImaxStrictProfile.READ_NOISE_COEFFICIENT, model.readNoiseCoefficient, 0.0000001f)
        assertTrue(model.varianceForLinearLuma(0.02f) > model.readNoiseCoefficient)
        assertTrue(model.signature.contains("fallback"))
    }

    @Test
    fun `variance follows the heteroscedastic O plus S times signal curve at sampled levels`() {
        val model = CameraSensorNoiseModelPolicy.fromCoefficients(listOf(0.012 to 0.003))
        val s = model.shotNoiseCoefficient
        val o = model.readNoiseCoefficient

        for (signal in listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 1.0f)) {
            // σ²(signal) must match O + S·signal (Poisson shot + Gaussian read) within tolerance.
            assertEquals(o + s * signal, model.varianceForLinearLuma(signal), 1e-5f)
        }
    }

    @Test
    fun `descriptor model precedence prefers profile then sensitivity range then fallback`() {
        val profileWins = CameraSensorNoiseModelPolicy.resolveDescriptorModel(
            captureNoiseProfile = listOf(0.01 to 0.002, 0.012 to 0.0025),
            sensitivityRange = 50 to 6400
        )
        assertTrue(profileWins.source.contains("SENSOR_NOISE_PROFILE"))
        assertEquals(0.011f, profileWins.shotNoiseCoefficient, 1e-5f)
        assertEquals(0.00225f, profileWins.readNoiseCoefficient, 1e-5f)

        val rangeWins = CameraSensorNoiseModelPolicy.resolveDescriptorModel(
            captureNoiseProfile = emptyList(),
            sensitivityRange = 50 to 6400
        )
        assertTrue(rangeWins.source.contains("SENSOR_INFO_SENSITIVITY_RANGE"))

        val fallbackWins = CameraSensorNoiseModelPolicy.resolveDescriptorModel(
            captureNoiseProfile = null,
            sensitivityRange = null
        )
        assertEquals(SensorNoiseModel.FALLBACK.shotNoiseCoefficient, fallbackWins.shotNoiseCoefficient, 0f)
        assertEquals(SensorNoiseModel.FALLBACK.readNoiseCoefficient, fallbackWins.readNoiseCoefficient, 0f)
        assertTrue(fallbackWins.source.contains("fallback"))
    }

    @Test
    fun `resolveDescriptorModel equals fromCaptureResult-then-fromCharacteristics precedence`() {
        // Equivalence guard: the pure precedence must mirror the live Camera2 chain it replaces,
        // so routing RawVideoManager through it cannot change today's descriptor noise.
        val profile = listOf(0.004 to 0.0009, 0.006 to 0.0011)
        val viaPure = CameraSensorNoiseModelPolicy.resolveDescriptorModel(profile, 50 to 6400)
        val viaProfile = CameraSensorNoiseModelPolicy.fromCoefficients(profile)
        assertEquals(viaProfile.shotNoiseCoefficient, viaPure.shotNoiseCoefficient, 0f)
        assertEquals(viaProfile.readNoiseCoefficient, viaPure.readNoiseCoefficient, 0f)
    }

    @Test
    fun `noiseS and noiseO reach the consumer strictly positive for every source (closes CP12 noise=0)`() {
        val realProfile = CameraSensorNoiseModelPolicy.resolveDescriptorModel(
            captureNoiseProfile = listOf(0.008 to 0.0015),
            sensitivityRange = null
        )
        val rangeOnly = CameraSensorNoiseModelPolicy.resolveDescriptorModel(
            captureNoiseProfile = null,
            sensitivityRange = 50 to 6400
        )
        val fallback = CameraSensorNoiseModelPolicy.resolveDescriptorModel(
            captureNoiseProfile = null,
            sensitivityRange = null
        )

        for (model in listOf(realProfile, rangeOnly, fallback)) {
            val inputs = CameraSensorNoiseModelPolicy.descriptorNoiseInputs(model)
            // The descriptor carries exactly the resolved model's coefficients...
            assertEquals(model.shotNoiseCoefficient, inputs.noiseS, 0f)
            assertEquals(model.readNoiseCoefficient, inputs.noiseO, 0f)
            // ...and the JVM boundary can never strand the consumer at noise=0.
            assertTrue("noiseS must be > 0 (source=${model.source})", inputs.noiseS > 0f)
            assertTrue("noiseO must be > 0 (source=${model.source})", inputs.noiseO > 0f)
        }
    }

    @Test
    fun `degenerate all-zero noise profile is rejected in favour of the conservative model`() {
        // A profile reporting zero shot AND zero read noise is non-physical. With no sensitivity range it
        // must fall through to the conservative FALLBACK (a realistic variance), NOT a near-zero 1e-5 floor.
        val viaFallback = CameraSensorNoiseModelPolicy.resolveDescriptorModel(
            captureNoiseProfile = listOf(0.0 to 0.0),
            sensitivityRange = null
        )
        assertEquals(SensorNoiseModel.FALLBACK.shotNoiseCoefficient, viaFallback.shotNoiseCoefficient, 0f)
        assertEquals(SensorNoiseModel.FALLBACK.readNoiseCoefficient, viaFallback.readNoiseCoefficient, 0f)
        assertTrue(viaFallback.source.contains("fallback"))
        assertTrue(viaFallback.shotNoiseCoefficient > 0f)
        assertTrue(viaFallback.readNoiseCoefficient > 0f)

        // With a sensitivity range present, a zero profile defers to the range-derived (non-zero) model.
        val viaRange = CameraSensorNoiseModelPolicy.resolveDescriptorModel(
            captureNoiseProfile = listOf(0.0 to 0.0, 0.0 to 0.0),
            sensitivityRange = 50 to 6400
        )
        assertTrue(viaRange.source.contains("SENSOR_INFO_SENSITIVITY_RANGE"))
        assertTrue(viaRange.shotNoiseCoefficient > 0f)
    }

    @Test
    fun `tiny but positive noise profile is honoured and floored strictly positive`() {
        // Sub-floor positive values are coerced UP to the physical floor (the load-bearing guarantee that
        // a real, non-zero profile can never reach the consumer as 0), never down to 0 or to the fallback.
        val model = CameraSensorNoiseModelPolicy.resolveDescriptorModel(
            captureNoiseProfile = listOf(0.0000001 to 0.00000001),
            sensitivityRange = null
        )
        assertEquals(0.00001f, model.shotNoiseCoefficient, 0f)
        assertEquals(0.000001f, model.readNoiseCoefficient, 0f)
        assertTrue(model.source.contains("SENSOR_NOISE_PROFILE"))
    }

    @Test
    fun `empty and null noise profiles both defer to the sensitivity-range branch`() {
        val range = 50 to 6400
        val emptyProfile = CameraSensorNoiseModelPolicy.resolveDescriptorModel(emptyList(), range)
        val nullProfile = CameraSensorNoiseModelPolicy.resolveDescriptorModel(null, range)
        assertEquals(nullProfile.shotNoiseCoefficient, emptyProfile.shotNoiseCoefficient, 0f)
        assertEquals(nullProfile.readNoiseCoefficient, emptyProfile.readNoiseCoefficient, 0f)
        assertTrue(emptyProfile.source.contains("SENSOR_INFO_SENSITIVITY_RANGE"))
        assertTrue(nullProfile.source.contains("SENSOR_INFO_SENSITIVITY_RANGE"))
    }
}
