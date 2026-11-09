package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99CalibrationAndStatisticsTest {
    @Test
    fun `valid calibration resolves dual illuminant matrices in mired space`() {
        val calibration = Niv99TestFixtures.calibration()
        calibration.colorTransform1[0] = 0.8f
        calibration.colorTransform2[0] = 1.2f
        val result = Niv99CalibrationSolution()
        assertTrue(Niv99DualIlluminantInterpolator().resolve(calibration, 4_000f, result))
        assertTrue(result.interpolationWeight in 0f..1f)
        assertTrue(result.valid)
        assertTrue(result.xyzToSensor[0] in 0.8f..1.2f)
    }

    @Test
    fun `mired midpoint is not kelvin midpoint`() {
        val calibration = Niv99TestFixtures.calibration()
        val midpointMired = 2f / (1f / 2_850f + 1f / 6_500f)
        val result = Niv99CalibrationSolution()
        assertTrue(Niv99DualIlluminantInterpolator().resolve(calibration, midpointMired, result))
        assertEquals(0.5f, result.interpolationWeight, 1e-4f)
    }

    @Test
    fun `invalid singular color calibration is rejected`() {
        val calibration = Niv99TestFixtures.calibration()
        calibration.colorTransform1.fill(0f)
        assertEquals(Niv99ValidationResult.INVALID_CALIBRATION, calibration.validate())
        assertFalse(
            Niv99DualIlluminantInterpolator().resolve(
                calibration,
                5_000f,
                Niv99CalibrationSolution()
            )
        )
    }

    @Test
    fun `lens shading interpolation is smooth and bounded`() {
        val calibration = Niv99TestFixtures.calibration()
        val gains = floatArrayOf(
            1f, 1f, 1f, 1f, 2f, 2f, 2f, 2f,
            3f, 3f, 3f, 3f, 4f, 4f, 4f, 4f
        )
        assertTrue(calibration.setLensShadingMap(gains, 2, 2))
        assertEquals(1f, calibration.lensShadingGain(0, 0f, 0f), 1e-6f)
        assertEquals(4f, calibration.lensShadingGain(0, 1f, 1f), 1e-6f)
        assertEquals(2.5f, calibration.lensShadingGain(0, 0.5f, 0.5f), 1e-6f)
    }

    @Test
    fun `raw statistics cover entire frame`() {
        val plane = Niv99TestFixtures.rawPlane(width = 96, height = 64)
        val calibration = Niv99TestFixtures.calibration()
        val output = Niv99TileStatistics(16)
        val result = Niv99RawStatistics(tileWidth = 32, tileHeight = 32).compute(
            plane,
            calibration,
            Niv99TestFixtures.noiseSnapshot(),
            output
        )
        assertEquals(Niv99ValidationResult.VALID, result)
        assertEquals(3, output.columns)
        assertEquals(2, output.rows)
        assertEquals(6, output.count)
        assertTrue(output.validCount > 0)
        assertTrue(output.centerX[0] < output.centerX[2])
        assertTrue(output.centerY[0] < output.centerY[3])
    }

    @Test
    fun `raw statistics recover sensor response ratios`() {
        val plane = Niv99TestFixtures.rawPlane(redResponse = 0.8f, blueResponse = 1.4f)
        val output = Niv99TileStatistics(16)
        val status = Niv99RawStatistics().compute(
            plane,
            Niv99TestFixtures.calibration(),
            Niv99TestFixtures.noiseSnapshot(),
            output
        )
        assertEquals(Niv99ValidationResult.VALID, status)
        for (index in 0 until output.count) {
            assertEquals(0.8f, output.redOverGreen[index], 0.06f)
            assertEquals(1.4f, output.blueOverGreen[index], 0.08f)
        }
    }

    @Test
    fun `saturated tiles lose illuminant reliability`() {
        val normal = Niv99TileStatistics(16)
        val clipped = Niv99TileStatistics(16)
        val statistics = Niv99RawStatistics()
        val calibration = Niv99TestFixtures.calibration()
        val noise = Niv99TestFixtures.noiseSnapshot()
        statistics.compute(
            Niv99TestFixtures.rawPlane(baseSignal = 0.25f),
            calibration,
            noise,
            normal
        )
        statistics.compute(
            Niv99TestFixtures.rawPlane(baseSignal = 1f, redResponse = 1f, blueResponse = 1f),
            calibration,
            noise,
            clipped
        )
        assertTrue(clipped.frameClippingFraction > normal.frameClippingFraction)
        assertTrue(clipped.reliability[0] < normal.reliability[0])
    }

    @Test
    fun `wrong CFA identity is rejected rather than silently interpreted`() {
        val plane = Niv99TestFixtures.rawPlane().apply { cfaPattern = Niv99CfaPattern.BGGR }
        val result = Niv99RawStatistics().compute(
            plane,
            Niv99TestFixtures.calibration(),
            null,
            Niv99TileStatistics(16)
        )
        assertEquals(Niv99ValidationResult.INVALID_PLANE, result)
    }

    @Test
    fun `CAT16 maps source white close to target white`() {
        val matrix = FloatArray(9)
        val vector = FloatArray(3)
        assertTrue(
            Niv99Cat16Adaptation().compute(
                0.96422f,
                1f,
                0.82521f,
                0.95047f,
                1f,
                1.08883f,
                1f,
                matrix
            )
        )
        Niv99Math.multiply3Vector(matrix, 0.96422f, 1f, 0.82521f, vector)
        assertEquals(0.95047f, vector[0], 2e-4f)
        assertEquals(1f, vector[1], 2e-4f)
        assertEquals(1.08883f, vector[2], 2e-4f)
    }
}

