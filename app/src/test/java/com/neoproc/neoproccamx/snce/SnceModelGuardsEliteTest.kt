package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s2-f09 — the hook architecture's honest-fallback contract (NO-VENDOR). With every hook null the
 * pipeline is fully classical and bit-for-bit unchanged; a throwing or degenerate hook is rejected
 * and the classical path runs; and no learned binary is committed.
 */
class SnceModelGuardsEliteTest {

    private fun gray(light: FloatArray): SnceThumbnail {
        val rgb = FloatArray(12 * 12 * 3)
        for (i in 0 until 12 * 12) {
            rgb[i * 3] = 0.4f * light[0]; rgb[i * 3 + 1] = 0.4f * light[1]; rgb[i * 3 + 2] = 0.4f * light[2]
        }
        return SnceThumbnail(12, 12, rgb)
    }

    @Test
    fun `empty registry is fully classical`() {
        val r = SnceModelRegistry()
        assertTrue(r.isFullyClassical)
        assertFalse(r.hasIlluminantModel)
        assertNull(r.illuminantModel)
    }

    @Test
    fun `a null illuminant hook leaves fusion classical`() {
        assertNull(SnceModelGuards.safeIlluminant(null, gray(floatArrayOf(1f, 1f, 1f))))
        val res = SnceIlluminantEstimator.estimate(gray(floatArrayOf(1.2f, 1f, 0.8f)), cnnModel = null)
        assertFalse(res.perMethod.any { it.method == "CNN" })
    }

    @Test
    fun `a throwing illuminant hook is caught and the classical path is used`() {
        val throwing = SnceIlluminantModel { throw RuntimeException("model blew up") }
        val thumb = gray(floatArrayOf(1.3f, 1f, 0.7f))
        // Guard must swallow the throw.
        assertNull(SnceModelGuards.safeIlluminant(throwing, thumb))
        // And the estimator must not crash, and must produce the classical result bit-for-bit.
        val withThrowing = SnceIlluminantEstimator.estimate(thumb, cnnModel = throwing)
        val classical = SnceIlluminantEstimator.estimate(thumb, cnnModel = null)
        assertFalse(withThrowing.perMethod.any { it.method == "CNN" })
        assertEquals(classical.gains[0], withThrowing.gains[0], 0f)
        assertEquals(classical.gains[2], withThrowing.gains[2], 0f)
    }

    @Test
    fun `degenerate illuminant outputs are rejected`() {
        val thumb = gray(floatArrayOf(1f, 1f, 1f))
        val cases = listOf(
            "NaN rgb" to SnceIlluminantModel { SnceModelIlluminant(floatArrayOf(Float.NaN, 1f, 1f), 0.9f) },
            "Inf rgb" to SnceIlluminantModel { SnceModelIlluminant(floatArrayOf(1f, Float.POSITIVE_INFINITY, 1f), 0.9f) },
            "non-positive rgb" to SnceIlluminantModel { SnceModelIlluminant(floatArrayOf(0f, 1f, 1f), 0.9f) },
            "wrong-size rgb" to SnceIlluminantModel { SnceModelIlluminant(floatArrayOf(1f, 1f), 0.9f) },
            "NaN confidence" to SnceIlluminantModel { SnceModelIlluminant(floatArrayOf(1f, 1f, 1f), Float.NaN) },
            "null result" to SnceIlluminantModel { null }
        )
        cases.forEach { (name, model) ->
            assertNull("$name must be rejected", SnceModelGuards.safeIlluminant(model, thumb))
            assertFalse("$name must not reach fusion",
                SnceIlluminantEstimator.estimate(thumb, cnnModel = model).perMethod.any { it.method == "CNN" })
        }
    }

    @Test
    fun `a valid illuminant hook passes through unchanged`() {
        val thumb = gray(floatArrayOf(1.3f, 1f, 0.7f))
        val model = SnceIlluminantModel { SnceModelIlluminant(floatArrayOf(0.6f, 1f, 1.6f), 0.95f) }
        val guarded = SnceModelGuards.safeIlluminant(model, thumb)
        assertEquals(0.6f, guarded!!.rgb[0], 0f)
        assertEquals(0.95f, guarded.confidence, 0f)
        assertTrue(SnceIlluminantEstimator.estimate(thumb, cnnModel = model).perMethod.any { it.method == "CNN" })
    }

    @Test
    fun `segmentation, lut and face hooks are total and null-safe under failure`() {
        val thumb = gray(floatArrayOf(1f, 1f, 1f))
        assertNull(SnceModelGuards.safeSegmentation(null, thumb))
        assertNull(SnceModelGuards.safeSegmentation(SnceSegmentationModel { throw RuntimeException() }, thumb))
        assertNull(SnceModelGuards.safeSegmentation(SnceSegmentationModel {
            SnceSegmentationResult(2, 2, IntArray(3), FloatArray(4))  // wrong classMask length
        }, thumb))
        assertNull(SnceModelGuards.safeAdaptiveLut(null, thumb))
        assertNull(SnceModelGuards.safeAdaptiveLut(SnceAdaptiveLutModel { throw RuntimeException() }, thumb))
        assertTrue(SnceModelGuards.safeFaces(null, thumb).isEmpty())
        assertTrue(SnceModelGuards.safeFaces(SnceFaceSkinModel { throw RuntimeException() }, thumb).isEmpty())
    }

    // NO-VENDOR guard: no learned binary is committed under assets/models/snce/.
    @Test
    fun `no learned snce binary is bundled`() {
        val dir = listOf(
            File("app/src/main/assets/models/snce"),
            File("../app/src/main/assets/models/snce")
        ).firstOrNull { it.exists() }
        if (dir != null) {
            val binaries = dir.listFiles { f -> f.extension == "tflite" }?.toList() ?: emptyList()
            assertTrue("NO-VENDOR: unexpected learned binaries $binaries", binaries.isEmpty())
        }
        // No directory (or no .tflite) ⇒ fully classical, as required.
    }
}
