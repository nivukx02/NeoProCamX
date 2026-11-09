package com.neoproc.neoproccamx.quality

import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * s8-f10 (CP-CI1) — the real-pixel golden lever, runnable in the hermetic JVM lane. It regenerates the
 * deterministic ISO-12233 slanted-edge / flat-grey / ColorChecker chart buffers, runs the REAL
 * [ImageQualityMetrics] over them, and asserts MTF50 / SNR / ΔE2000 within committed tolerance bands
 * (a metric drift fails the build). A committed QUANTIZED SHA-256 of the edge buffer is the
 * golden-drift lever — quantized to 4 decimals so it is bit-portable across JVMs (Math.exp is only
 * 1-ULP-portable; a raw float hash would false-RED off-platform).
 *
 * Real on-device capture (owner-run, DEVICE-GATED — never committed as binary media):
 *   1. Frame an ISO-12233 chart + X-Rite ColorChecker + flat grey card at the documented exposure.
 *   2. adb pull the recorded/exported master; decode N frames (MediaExtractor->MediaCodec->ImageReader).
 *   3. Run ImageQualityMetrics over the decoded buffers; compare against the bands below.
 * The committed lever validates the synthetic invariant; the device capture stays owner-run so CI
 * never claims a device-validated PASS it cannot prove.
 */
class RealPixelGoldenMetricCheckTest {

    // Committed golden constants (the in-code manifest). Drift here fails the build.
    private companion object {
        const val MTF50_EXPECTED = 0.44
        const val MTF50_TOL = 0.06
        const val SNR_EXPECTED = 52.4
        const val SNR_TOL = 1.0
        const val DE2000_MEAN_MAX = 1.5
        // Quantized SHA-256 of the deterministic slanted-edge buffer (golden-locked from a real run).
        const val EDGE_BUFFER_QUANTIZED_SHA256 = "b2cad76b6b0b6e6954369215703bccee57958fcc02d2b4e349863795bf8f98d9"
    }

    @Test
    fun mtf50WithinTolerance() {
        val (edge, w, h) = slantedEdge(64, 64, edge0 = 32.0, slope = 0.06, sigma = 0.42)
        val mtf50 = ImageQualityMetrics.slantedEdgeMtf50(edge, w, h)
        assertEquals("MTF50 drifted", MTF50_EXPECTED, mtf50, MTF50_TOL)
    }

    @Test
    fun flatFieldSnrWithinTolerance() {
        val snr = ImageQualityMetrics.flatFieldSnrDb(flatField(0.5f, 0.0012f, 4096))
        assertEquals("Flat-field SNR drifted", SNR_EXPECTED, snr, SNR_TOL)
    }

    @Test
    fun colorCheckerDeltaE2000WithinTolerance() {
        val (mean, _) = ImageQualityMetrics.colorCheckerDeltaE2000(
            colorChecker(perturbation = 0.4f),
            colorChecker(perturbation = 0f)
        )
        assertTrue("ColorChecker dE2000 mean $mean exceeded $DE2000_MEAN_MAX", mean <= DE2000_MEAN_MAX)
    }

    @Test
    fun goldenBufferQuantizedHashMatchesManifest() {
        val (edge, _, _) = slantedEdge(64, 64, edge0 = 32.0, slope = 0.06, sigma = 0.42)
        assertEquals(
            "ISO-12233 golden buffer drifted (quantized hash mismatch)",
            EDGE_BUFFER_QUANTIZED_SHA256,
            quantizedSha256(edge)
        )
    }

    /** SHA-256 over each sample rounded to 4 decimals (Int), absorbing 1-ULP transcendental variance. */
    private fun quantizedSha256(buffer: FloatArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(4)
        for (v in buffer) {
            val q = (v * 10_000f).roundToInt()
            bytes[0] = (q ushr 24).toByte()
            bytes[1] = (q ushr 16).toByte()
            bytes[2] = (q ushr 8).toByte()
            bytes[3] = q.toByte()
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- deterministic golden builders (mirror ImageQualityMetricsTest / harness goldens) ----

    private fun slantedEdge(
        width: Int,
        height: Int,
        edge0: Double,
        slope: Double,
        sigma: Double
    ): Triple<FloatArray, Int, Int> {
        val img = FloatArray(width * height)
        val s = sigma * sqrt(2.0)
        for (y in 0 until height) {
            val edge = edge0 + slope * y
            for (x in 0 until width) {
                img[y * width + x] = (0.5 * (1.0 + erf((x - edge) / s))).toFloat()
            }
        }
        return Triple(img, width, height)
    }

    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return sign(x) * y
    }

    private fun flatField(mean: Float, sigma: Float, n: Int): FloatArray =
        FloatArray(n) { if (it % 2 == 0) mean + sigma else mean - sigma }

    private fun colorChecker(perturbation: Float): List<FloatArray> = listOf(
        floatArrayOf(38f, 14f + perturbation, 15f),
        floatArrayOf(66f, 13f, 17f + perturbation),
        floatArrayOf(51f, -10f - perturbation, -23f),
        floatArrayOf(43f, -16f, 22f + perturbation),
        floatArrayOf(56f, 9f + perturbation, -25f),
        floatArrayOf(72f, -23f, 58f - perturbation)
    )
}
