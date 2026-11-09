package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class Niv99QualityOracleTest {
    @Test
    fun `CIEDE2000 matches published Sharma reference pairs`() {
        val oracle = Niv99QualityOracle()
        val cases = arrayOf(
            doubleArrayOf(50.0000, 2.6772, -79.7751, 50.0000, 0.0000, -82.7485, 2.0425),
            doubleArrayOf(50.0000, 3.1571, -77.2803, 50.0000, 0.0000, -82.7485, 2.8615),
            doubleArrayOf(50.0000, 2.8361, -74.0200, 50.0000, 0.0000, -82.7485, 3.4412),
            doubleArrayOf(50.0000, -1.3802, -84.2814, 50.0000, 0.0000, -82.7485, 1.0000),
            doubleArrayOf(50.0000, 0.0000, 0.0000, 50.0000, -1.0000, 2.0000, 2.3669)
        )
        for (values in cases) {
            val first = Niv99LabColor().apply {
                l = values[0]
                a = values[1]
                b = values[2]
            }
            val second = Niv99LabColor().apply {
                l = values[3]
                a = values[4]
                b = values[5]
            }
            assertEquals(values[6], oracle.deltaE2000(first, second), 0.0002)
        }
    }

    @Test
    fun `identical images have perfect objective quality`() {
        val image = syntheticImage(32, 32)
        val metrics = Niv99DenoiseMetrics()
        val result = Niv99QualityOracle().evaluateDenoise(
            image,
            image,
            image,
            32,
            32,
            32 * 3,
            8,
            8,
            metrics
        )
        assertEquals(Niv99ValidationResult.VALID, result)
        assertEquals(120.0, metrics.psnrDb, 0.001)
        assertEquals(1.0, metrics.ssim, 1e-9)
        assertEquals(1.0, metrics.fineTextureRetention, 1e-9)
        assertEquals(0.0, metrics.edgeOvershoot, 1e-9)
        assertEquals(0.0, metrics.tileBoundaryDiscontinuity, 1e-9)
    }

    @Test
    fun `denoised candidate improves SNR over noisy input`() {
        val width = 64
        val height = 64
        val reference = syntheticImage(width, height)
        val noisy = reference.copyOf()
        val processed = reference.copyOf()
        var state = 17
        for (index in noisy.indices) {
            state = state * 1_103_515_245 + 12_345
            val noise = (((state ushr 8) and 0xffff) / 65_535f - 0.5f) * 0.10f
            noisy[index] = (reference[index] + noise).coerceIn(0f, 1f)
            processed[index] = (reference[index] + noise * 0.25f).coerceIn(0f, 1f)
        }
        val metrics = Niv99DenoiseMetrics()
        Niv99QualityOracle().evaluateDenoise(
            reference,
            noisy,
            processed,
            width,
            height,
            width * 3,
            16,
            16,
            metrics
        )
        assertTrue(metrics.lumaSnrImprovementDb > 10.0)
        assertTrue(metrics.chromaSnrImprovementDb > 10.0)
        assertTrue(metrics.psnrDb > 30.0)
        assertTrue(metrics.ssim > 0.9)
    }

    @Test
    fun `MTF50 detects sharper edge spread`() {
        val count = 128
        val sharp = FloatArray(count)
        val soft = FloatArray(count)
        for (index in 0 until count) {
            val x = index - count / 2f
            sharp[index] = (1.0 / (1.0 + exp(-x * 1.2))).toFloat()
            soft[index] = (1.0 / (1.0 + exp(-x * 0.3))).toFloat()
        }
        val oracle = Niv99QualityOracle()
        val sharpMtf = oracle.mtf50FromEdgeSpread(sharp, count)
        val softMtf = oracle.mtf50FromEdgeSpread(soft, count)
        assertTrue(sharpMtf > softMtf)
        assertTrue(sharpMtf > 0.0)
    }

    @Test
    fun `temporal flicker is zero for stable chromaticity`() {
        val red = floatArrayOf(0.4f, 0.8f, 0.2f, 0.6f)
        val green = floatArrayOf(0.5f, 1f, 0.25f, 0.75f)
        val blue = floatArrayOf(0.3f, 0.6f, 0.15f, 0.45f)
        assertEquals(0.0, Niv99QualityOracle().temporalFlicker(red, green, blue, 4), 1e-7)
    }

    @Test
    fun `temporal flicker detects alternating color cast`() {
        val red = floatArrayOf(0.4f, 0.8f, 0.4f, 0.8f)
        val green = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        val blue = floatArrayOf(0.8f, 0.3f, 0.8f, 0.3f)
        assertTrue(Niv99QualityOracle().temporalFlicker(red, green, blue, 4) > 0.5)
    }

    @Test
    fun `ghosting metric evaluates only moving mask`() {
        val reference = floatArrayOf(0f, 0f, 0f, 0f)
        val candidate = floatArrayOf(1f, 1f, 0f, 1f)
        val motion = floatArrayOf(1f, 0f, 1f, 1f)
        val rate = Niv99QualityOracle().ghostingRate(reference, candidate, motion, 4, 0.1f)
        assertEquals(2.0 / 3.0, rate, 1e-9)
    }

    @Test
    fun `quality verdict cannot claim vendor superiority`() {
        val baseline = Niv99DenoiseMetrics().apply {
            psnrDb = 30.0
            ssim = 0.90
            residualChromaNoise = 0.04
            ghostingRate = 0.03
        }
        val candidate = Niv99DenoiseMetrics().apply {
            psnrDb = 34.0
            ssim = 0.95
            residualChromaNoise = 0.02
            ghostingRate = 0.02
            mtf50Retention = 0.93
            tileBoundaryDiscontinuity = 0.001
            edgeOvershoot = 0.01
        }
        val verdict = Niv99QualityVerdict()
        Niv99QualityOracle().verdict(
            baseline,
            candidate,
            introducedClippingFraction = 0.0,
            historyIsolationPass = true,
            timestampPass = true,
            frameCountPass = true,
            output = verdict
        )
        assertTrue(verdict.allStartingTargetsPass())
        assertFalse(verdict.vendorSuperiorityProven)
    }

    private fun syntheticImage(width: Int, height: Int): FloatArray {
        val output = FloatArray(width * height * 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = (y * width + x) * 3
                val checker = if ((x / 4 + y / 4) and 1 == 0) 0.2f else 0.7f
                output[index] = checker
                output[index + 1] = checker * 0.9f
                output[index + 2] = checker * 0.8f
            }
        }
        return output
    }
}

