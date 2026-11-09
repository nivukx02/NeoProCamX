package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99NoiseAndDetailTest {
    @Test
    fun `shot plus read variance increases monotonically with signal`() {
        val model = Niv99TestFixtures.noiseSnapshot()
        var previous = -1f
        for (step in 0..100) {
            val variance = model.variance(0, step / 100f)
            assertTrue(variance >= previous)
            previous = variance
        }
    }

    @Test
    fun `higher ISO increases calibrated shot and read variance`() {
        val calibration = Niv99TestFixtures.calibration()
        val lowContext = Niv99TestFixtures.context().apply { iso = 100 }
        val highContext = Niv99TestFixtures.context().apply { iso = 6_400 }
        val low = Niv99NoiseModelSnapshot()
        val high = Niv99NoiseModelSnapshot()
        Niv99NoiseModel().resolve(lowContext, calibration, null, low)
        Niv99NoiseModel().resolve(highContext, calibration, null, high)
        assertTrue(high.shotCoefficient[0] > low.shotCoefficient[0])
        assertTrue(high.readCoefficient[0] > low.readCoefficient[0])
        assertTrue(high.variance(0, 0.2f) > low.variance(0, 0.2f))
    }

    @Test
    fun `higher sensor temperature increases read noise`() {
        val calibration = Niv99TestFixtures.calibration()
        val coolContext = Niv99TestFixtures.context().apply { sensorTemperatureCelsius = 5f }
        val hotContext = Niv99TestFixtures.context().apply { sensorTemperatureCelsius = 65f }
        val cool = Niv99NoiseModelSnapshot()
        val hot = Niv99NoiseModelSnapshot()
        Niv99NoiseModel().resolve(coolContext, calibration, null, cool)
        Niv99NoiseModel().resolve(hotContext, calibration, null, hot)
        assertTrue(hot.readCoefficient[2] > cool.readCoefficient[2])
    }

    @Test
    fun `same-frame measured profile overrides calibrated estimate`() {
        val context = Niv99TestFixtures.context()
        val measured = Niv99MeasuredNoiseProfile().apply {
            valid = true
            shotCoefficient.fill(0.01f)
            readCoefficient.fill(0.0001f)
        }
        val output = Niv99NoiseModelSnapshot()
        assertEquals(
            Niv99ValidationResult.VALID,
            Niv99NoiseModel().resolve(context, Niv99TestFixtures.calibration(), measured, output)
        )
        assertTrue(output.measuredProfile)
        assertEquals(0.01f, output.shotCoefficient[0], 1e-7f)
        assertEquals(0.0001f, output.readCoefficient[0], 1e-7f)
    }

    @Test
    fun `all-zero measured profile is rejected`() {
        val measured = Niv99MeasuredNoiseProfile().apply {
            valid = true
            shotCoefficient.fill(0f)
            readCoefficient.fill(0f)
        }
        assertFalse(measured.validate())
    }

    @Test
    fun `WB covariance propagation increases amplified channel variance`() {
        val model = Niv99TestFixtures.noiseSnapshot()
        val workspace = Niv99NoisePropagationWorkspace()
        val identity = FloatArray(9).also(Niv99Math::setIdentity3)
        assertTrue(
            Niv99NoiseCovariancePropagation.propagate(
                model,
                0.2f,
                0.2f,
                0.2f,
                redGain = 2f,
                greenGain = 1f,
                blueGain = 3f,
                colorMatrix = identity,
                workspace = workspace
            )
        )
        assertTrue(workspace.outputCovariance[0] > workspace.rawCovariance[0])
        assertTrue(workspace.outputCovariance[8] > workspace.rawCovariance[8])
    }

    @Test
    fun `hot pixel threshold is noise and edge aware`() {
        assertTrue(
            Niv99DefectPixelPolicy.isDefective(
                center = 0.9f,
                sameColorMedian = 0.1f,
                localGradient = 0.01f,
                variance = 0.0001f,
                repeatedAcrossFrames = true
            )
        )
        assertFalse(
            Niv99DefectPixelPolicy.isDefective(
                center = 0.5f,
                sameColorMedian = 0.4f,
                localGradient = 0.2f,
                variance = 0.001f,
                repeatedAcrossFrames = false
            )
        )
    }

    @Test
    fun `fixed pattern suppression backs off on scene structure`() {
        val flat = Niv99FixedPatternNoisePolicy.correctionWeight(0.02f, 0.00001f, 0.001f, 1f)
        val edge = Niv99FixedPatternNoisePolicy.correctionWeight(0.02f, 0.00001f, 0.2f, 1f)
        assertTrue(flat > edge)
    }

    @Test
    fun `detail profile changes denoise parameters but not context format`() {
        val base = Niv99TestFixtures.context()
        val width = base.width
        val height = base.height
        val fps = base.targetFps
        val calibration = Niv99TestFixtures.calibration()
        val statistics = statistics(base, calibration)
        val semantics = Niv99SemanticTileMap(16).apply { clear(statistics.count) }
        val wb = wb(base)
        val skin = Niv99SkinMap(16)
        Niv99SkinProtection().resolve(statistics, semantics, wb, skin)
        val detail = Niv99DetailMap(16)
        val policy = Niv99SpatialDenoisePolicy()
        val noise = Niv99TestFixtures.noiseSnapshot(base)
        val focused = Niv99SpatialPlan(16)
        base.detailProfile = Niv99DetailProfile.FOCUSED
        Niv99DetailProtection().classify(statistics, semantics, base.detailProfile, detail)
        policy.resolve(base, statistics, noise, detail, skin, focused)
        val extreme = Niv99SpatialPlan(16)
        base.detailProfile = Niv99DetailProfile.EXTREME
        Niv99DetailProtection().classify(statistics, semantics, base.detailProfile, detail)
        policy.resolve(base, statistics, noise, detail, skin, extreme)
        assertTrue(focused.meanLumaStrength > extreme.meanLumaStrength)
        assertTrue(extreme.meanSharpening >= focused.meanSharpening)
        assertEquals(width, base.width)
        assertEquals(height, base.height)
        assertEquals(fps, base.targetFps)
    }

    @Test
    fun `skin protection requires semantic and chromatic agreement`() {
        val context = Niv99TestFixtures.context()
        val calibration = Niv99TestFixtures.calibration()
        val statistics = statistics(context, calibration)
        val semantics = Niv99SemanticTileMap(16).apply {
            clear(statistics.count)
            skin.fill(1f, 0, count)
        }
        val output = Niv99SkinMap(16)
        Niv99SkinProtection().resolve(statistics, semantics, wb(context), output)
        assertTrue(output.confidence.any { it > 0f })
        semantics.skin.fill(0f, 0, semantics.count)
        Niv99SkinProtection().resolve(statistics, semantics, wb(context), output)
        assertTrue(output.confidence.all { it == 0f })
    }

    @Test
    fun `reconstruction sharpening is suppressed by noise and halo risk`() {
        val clean = Niv99ReconstructionSharpeningPolicy.resolve(
            0.80f,
            0.005f,
            1f,
            1f,
            0f,
            Niv99DetailProfile.EXTREME,
            0.5f
        )
        val noisy = Niv99ReconstructionSharpeningPolicy.resolve(
            0.80f,
            0.12f,
            1f,
            1f,
            0.8f,
            Niv99DetailProfile.EXTREME,
            0.5f
        )
        assertTrue(clean > noisy)
        assertTrue(clean <= 0.5f)
    }

    private fun statistics(
        context: Niv99FrameContext,
        calibration: Niv99SensorCalibration
    ): Niv99TileStatistics {
        val output = Niv99TileStatistics(16)
        Niv99RawStatistics().compute(
            Niv99TestFixtures.rawPlane(noiseAmplitude = 0.01f),
            calibration,
            Niv99TestFixtures.noiseSnapshot(context),
            output
        )
        return output
    }

    private fun wb(context: Niv99FrameContext): Niv99WhiteBalanceSolution =
        Niv99WhiteBalanceSolution().apply {
            identity.copyFrom(context.identity)
            sourceIdentity.copyFrom(context.identity)
            authority = Niv99WhiteBalanceAuthority.NIV99_AUTO
            redGain = 1.35f
            greenGain = 1f
            blueGain = 0.82f
            confidence = 0.9f
        }
}

