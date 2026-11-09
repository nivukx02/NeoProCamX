package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Project-owned numerical goldens. Values are public color-science references or analytically
 * constructed fixtures; no proprietary vendor calibration or reverse-engineered data is used.
 */
class Niv99GoldenCalibrationTest {
    @Test
    fun `CAT16 D50 to D65 adaptation matches reference matrix`() {
        val output = FloatArray(9)
        assertTrue(
            Niv99Cat16Adaptation().compute(
                sourceX = 0.96422f,
                sourceY = 1f,
                sourceZ = 0.82521f,
                targetX = 0.95047f,
                targetY = 1f,
                targetZ = 1.08883f,
                degreeOfAdaptation = 1f,
                output = output
            )
        )
        val expected = floatArrayOf(
            0.989466f, -0.040263f, 0.044553f,
            -0.005405f, 1.006660f, -0.001756f,
            -0.000404f, 0.015191f, 1.301811f
        )
        assertArrayEquals(expected, output, 8e-4f)
    }

    @Test
    fun `zero CAT16 adaptation is exact identity`() {
        val output = FloatArray(9)
        assertTrue(
            Niv99Cat16Adaptation().compute(
                0.96422f,
                1f,
                0.82521f,
                0.95047f,
                1f,
                1.08883f,
                0f,
                output
            )
        )
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            output,
            2e-6f
        )
    }

    @Test
    fun `dual illuminant endpoint matrices remain exact`() {
        val calibration = analyticalCalibration()
        val first = Niv99CalibrationSolution()
        val second = Niv99CalibrationSolution()
        val interpolator = Niv99DualIlluminantInterpolator()

        assertTrue(interpolator.resolve(calibration, calibration.referenceIlluminant1Kelvin, first))
        assertTrue(interpolator.resolve(calibration, calibration.referenceIlluminant2Kelvin, second))
        assertEquals(0f, first.interpolationWeight, 1e-6f)
        assertEquals(1f, second.interpolationWeight, 1e-6f)
        assertArrayEquals(calibration.colorTransform1, first.xyzToSensor, 1e-6f)
        assertArrayEquals(calibration.colorTransform2, second.xyzToSensor, 1e-6f)
        assertArrayEquals(calibration.forwardMatrix1, first.forwardMatrix, 1e-6f)
        assertArrayEquals(calibration.forwardMatrix2, second.forwardMatrix, 1e-6f)
    }

    @Test
    fun `dual illuminant mired interpolation matches analytical diagonal fixture`() {
        val calibration = Niv99TestFixtures.calibration().apply {
            colorTransform1.fill(0f)
            colorTransform2.fill(0f)
            calibrationTransform1.fill(0f)
            calibrationTransform2.fill(0f)
            forwardMatrix1.fill(0f)
            forwardMatrix2.fill(0f)
            colorTransform1[0] = 0.8f
            colorTransform1[4] = 0.9f
            colorTransform1[8] = 1.1f
            colorTransform2[0] = 1.2f
            colorTransform2[4] = 1.1f
            colorTransform2[8] = 0.9f
            calibrationTransform1[0] = 1f
            calibrationTransform1[4] = 1f
            calibrationTransform1[8] = 1f
            calibrationTransform2[0] = 1f
            calibrationTransform2[4] = 1f
            calibrationTransform2[8] = 1f
            forwardMatrix1[0] = 0.7f
            forwardMatrix1[4] = 0.8f
            forwardMatrix1[8] = 0.9f
            forwardMatrix2[0] = 0.9f
            forwardMatrix2[4] = 0.8f
            forwardMatrix2[8] = 0.7f
            markUpdated()
        }
        val midpointKelvin = 2f /
            (1f / calibration.referenceIlluminant1Kelvin + 1f / calibration.referenceIlluminant2Kelvin)
        val output = Niv99CalibrationSolution()
        assertTrue(Niv99DualIlluminantInterpolator().resolve(calibration, midpointKelvin, output))
        assertEquals(0.5f, output.interpolationWeight, 2e-5f)
        assertArrayEquals(
            floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            output.xyzToSensor,
            1e-6f
        )
        assertArrayEquals(
            floatArrayOf(0.8f, 0f, 0f, 0f, 0.8f, 0f, 0f, 0f, 0.8f),
            output.forwardMatrix,
            1e-6f
        )
        assertArrayEquals(output.forwardMatrix, output.sensorToOutput, 1e-6f)
    }

    @Test
    fun `Planckian locus golden points recover CCT and near zero Duv`() {
        val expectedKelvin = intArrayOf(1_500, 1_850, 2_000, 2_850, 3_200, 4_000, 5_000, 6_500, 8_000, 12_000, 25_000)
        val xy = FloatArray(2)
        val uv = FloatArray(2)
        val result = FloatArray(2)
        for (kelvin in expectedKelvin) {
            Niv99Math.blackbodyXy(kelvin.toFloat(), xy)
            Niv99Math.xyToUvPrime(xy[0], xy[1], uv)
            Niv99Math.estimateCctAndDuv(uv[0], uv[1], result)
            assertEquals("CCT $kelvin", kelvin.toFloat(), result[0], 25f)
            assertEquals("Duv $kelvin", 0f, result[1], 1e-5f)
        }
    }

    @Test
    fun `Duv sign changes across opposite sides of Planckian tangent`() {
        val xy = FloatArray(2)
        val uv = FloatArray(2)
        val above = FloatArray(2)
        val below = FloatArray(2)
        Niv99Math.blackbodyXy(5_000f, xy)
        Niv99Math.xyToUvPrime(xy[0], xy[1], uv)
        Niv99Math.estimateCctAndDuv(uv[0], uv[1] + 0.006f, above)
        Niv99Math.estimateCctAndDuv(uv[0], uv[1] - 0.006f, below)
        assertTrue(above[1] * below[1] < 0f)
        assertTrue(kotlin.math.abs(above[1]) > 0.001f)
        assertTrue(kotlin.math.abs(below[1]) > 0.001f)
    }

    @Test
    fun `lens shading bilinear golden preserves four channel layout`() {
        val calibration = Niv99TestFixtures.calibration()
        val gains = floatArrayOf(
            1f, 2f, 3f, 4f,
            2f, 3f, 4f, 5f,
            3f, 4f, 5f, 6f,
            4f, 5f, 6f, 7f
        )
        assertTrue(calibration.setLensShadingMap(gains, columns = 2, rows = 2))
        assertEquals(2.5f, calibration.lensShadingGain(0, 0.5f, 0.5f), 1e-6f)
        assertEquals(3.5f, calibration.lensShadingGain(1, 0.5f, 0.5f), 1e-6f)
        assertEquals(4.5f, calibration.lensShadingGain(2, 0.5f, 0.5f), 1e-6f)
        assertEquals(5.5f, calibration.lensShadingGain(3, 0.5f, 0.5f), 1e-6f)
    }

    @Test
    fun `noise covariance diagonal golden includes squared WB gain`() {
        val model = Niv99NoiseModelSnapshot().apply {
            shotCoefficient[0] = 0.02f
            shotCoefficient[1] = 0.01f
            shotCoefficient[2] = 0.01f
            shotCoefficient[3] = 0.03f
            readCoefficient.fill(0.001f)
        }
        val identity = FloatArray(9).also(Niv99Math::setIdentity3)
        val workspace = Niv99NoisePropagationWorkspace()
        assertTrue(
            Niv99NoiseCovariancePropagation.propagate(
                model = model,
                redSignal = 0.5f,
                greenSignal = 0.5f,
                blueSignal = 0.5f,
                redGain = 2f,
                greenGain = 1f,
                blueGain = 3f,
                colorMatrix = identity,
                workspace = workspace
            )
        )
        assertEquals((0.02f * 0.5f + 0.001f) * 4f, workspace.outputCovariance[0], 1e-7f)
        assertEquals(0.01f * 0.5f + 0.001f, workspace.outputCovariance[4], 1e-7f)
        assertEquals((0.03f * 0.5f + 0.001f) * 9f, workspace.outputCovariance[8], 1e-7f)
    }

    @Test
    fun `singular calibration transform is rejected by interpolation`() {
        val calibration = analyticalCalibration()
        calibration.calibrationTransform1.fill(0f)
        calibration.calibrationTransform2.fill(0f)
        calibration.markUpdated()
        val output = Niv99CalibrationSolution()
        assertFalse(Niv99DualIlluminantInterpolator().resolve(calibration, 5_000f, output))
        assertFalse(output.valid)
    }

    @Test
    fun `golden synthetic neutral remains neutral after estimated gains`() {
        val context = Niv99TestFixtures.context()
        val calibration = Niv99TestFixtures.calibration()
        val noise = Niv99TestFixtures.noiseSnapshot(context)
        val statistics = Niv99TileStatistics(64)
        val plane = Niv99TestFixtures.rawPlane(
            redResponse = 0.625f,
            greenResponse = 1f,
            blueResponse = 1.375f,
            baseSignal = 0.30f,
            noiseAmplitude = 0f
        )
        assertEquals(
            Niv99ValidationResult.VALID,
            Niv99RawStatistics().compute(plane, calibration, noise, statistics)
        )
        val calibrationSolution = Niv99CalibrationSolution()
        assertTrue(Niv99DualIlluminantInterpolator().resolve(calibration, 5_000f, calibrationSolution))
        val estimate = Niv99IlluminantEstimate()
        assertEquals(
            Niv99ValidationResult.VALID,
            Niv99IlluminantEstimator(64).estimate(
                context,
                statistics,
                calibrationSolution,
                null,
                estimate
            )
        )
        val correctedRed = statistics.meanRed[0] * estimate.redGain
        val correctedGreen = statistics.meanGreen[0] * estimate.greenGain
        val correctedBlue = statistics.meanBlue[0] * estimate.blueGain
        assertEquals(correctedGreen, correctedRed, 0.035f)
        assertEquals(correctedGreen, correctedBlue, 0.035f)
    }

    private fun analyticalCalibration(): Niv99SensorCalibration =
        Niv99TestFixtures.calibration().apply {
            colorTransform1.copyFrom(
                floatArrayOf(
                    0.72f, 0.18f, 0.10f,
                    0.08f, 0.86f, 0.06f,
                    0.02f, 0.12f, 0.86f
                )
            )
            colorTransform2.copyFrom(
                floatArrayOf(
                    0.66f, 0.22f, 0.12f,
                    0.10f, 0.83f, 0.07f,
                    0.03f, 0.15f, 0.82f
                )
            )
            calibrationTransform1.copyFrom(
                floatArrayOf(
                    1.01f, 0f, 0f,
                    0f, 0.99f, 0f,
                    0f, 0f, 1.02f
                )
            )
            calibrationTransform2.copyFrom(
                floatArrayOf(
                    0.99f, 0f, 0f,
                    0f, 1.01f, 0f,
                    0f, 0f, 0.98f
                )
            )
            forwardMatrix1.copyFrom(
                floatArrayOf(
                    0.68f, 0.21f, 0.11f,
                    0.09f, 0.84f, 0.07f,
                    0.02f, 0.13f, 0.85f
                )
            )
            forwardMatrix2.copyFrom(
                floatArrayOf(
                    0.63f, 0.24f, 0.13f,
                    0.11f, 0.81f, 0.08f,
                    0.04f, 0.16f, 0.80f
                )
            )
            markUpdated()
        }

    private fun FloatArray.copyFrom(source: FloatArray) {
        source.copyInto(this)
    }
}
