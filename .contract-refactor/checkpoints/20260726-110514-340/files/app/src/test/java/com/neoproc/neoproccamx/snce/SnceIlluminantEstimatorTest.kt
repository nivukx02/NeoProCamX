package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceIlluminantEstimatorTest {

    /** A flat scene of constant chromaticity (gray * light) with mild brightness texture. */
    private fun tintedGray(w: Int, h: Int, light: FloatArray, base: Float = 0.4f): SnceThumbnail {
        val rgb = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            val b = base * (0.6f + 0.5f * ((i % 7) / 6f))
            rgb[i * 3] = b * light[0]
            rgb[i * 3 + 1] = b * light[1]
            rgb[i * 3 + 2] = b * light[2]
        }
        return SnceThumbnail(w, h, rgb)
    }

    @Test
    fun `recovered gains neutralise a warm illuminant`() {
        val warm = floatArrayOf(1.35f, 1.0f, 0.65f)
        val result = SnceIlluminantEstimator.estimate(tintedGray(16, 16, warm))
        // applying the gains to the light should return near-neutral
        val neutralisedR = result.gains[0] * warm[0]
        val neutralisedB = result.gains[2] * warm[2]
        assertEquals("R neutralised", 1f, neutralisedR / (warm[1] * result.gains[1]), 0.06f)
        assertEquals("B neutralised", 1f, neutralisedB / (warm[1] * result.gains[1]), 0.06f)
        assertTrue("warm light -> lower CCT", result.cct < 5500f)
    }

    @Test
    fun `neutral scene fuses to near unity and ~6500K`() {
        val result = SnceIlluminantEstimator.estimate(tintedGray(16, 16, floatArrayOf(1f, 1f, 1f)))
        assertEquals(1f, result.illuminantRgb[0], 0.08f)
        assertEquals(1f, result.illuminantRgb[2], 0.08f)
        assertEquals(6500f, result.cct, 900f)
        assertTrue(result.cct in 2000f..20000f)
    }

    @Test
    fun `cool illuminant yields higher CCT than warm`() {
        val warm = SnceIlluminantEstimator.estimate(tintedGray(16, 16, floatArrayOf(1.4f, 1f, 0.6f)))
        val cool = SnceIlluminantEstimator.estimate(tintedGray(16, 16, floatArrayOf(0.7f, 1f, 1.5f)))
        assertTrue(cool.cct > warm.cct)
    }

    @Test
    fun `split warm-cool scene is flagged as mixed lighting`() {
        val w = 8; val h = 12
        val rgb = FloatArray(w * h * 3)
        val warm = floatArrayOf(1.5f, 1f, 0.55f)
        val cool = floatArrayOf(0.6f, 1f, 1.6f)
        for (y in 0 until h) for (x in 0 until w) {
            val light = if (x < w / 2) warm else cool
            val b = 0.4f * (0.7f + 0.3f * ((x + y) % 5) / 4f)
            val i = (y * w + x) * 3
            rgb[i] = b * light[0]; rgb[i + 1] = b * light[1]; rgb[i + 2] = b * light[2]
        }
        val result = SnceIlluminantEstimator.estimate(SnceThumbnail(w, h, rgb))
        assertTrue("dual-illuminant scene must set mixedLight", result.mixedLight)
        assertTrue("secondary CCT reported", result.secondaryCct > 0f)
    }

    @Test
    fun `CNN hook participates in fusion when present`() {
        val model = SnceIlluminantModel { SnceModelIlluminant(floatArrayOf(0.6f, 1f, 1.6f), 0.95f) }
        val warm = floatArrayOf(1.4f, 1f, 0.6f)
        val withModel = SnceIlluminantEstimator.estimate(tintedGray(16, 16, warm), cnnModel = model)
        val withoutModel = SnceIlluminantEstimator.estimate(tintedGray(16, 16, warm))
        assertTrue(withModel.perMethod.any { it.method == "CNN" })
        // a strong cool CNN estimate pulls the fused blue component up vs. the classical-only run
        assertTrue(withModel.illuminantRgb[2] > withoutModel.illuminantRgb[2])
    }

    @Test
    fun `temporal and imu priors are included`() {
        val result = SnceIlluminantEstimator.estimate(
            tintedGray(12, 12, floatArrayOf(1.1f, 1f, 0.9f)),
            temporalPrior = floatArrayOf(1.0f, 1f, 1.0f),
            imuPrior = SnceIlluminantPrior(floatArrayOf(0.8f, 1f, 1.3f), 0.5f)
        )
        assertTrue(result.perMethod.any { it.method == "Temporal" })
        assertTrue(result.perMethod.any { it.method == "IMU" })
    }
}
