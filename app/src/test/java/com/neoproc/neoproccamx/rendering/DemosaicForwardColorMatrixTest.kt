package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.ApexColorConfig
import com.neoproc.neoproccamx.color.DualIlluminantSensorMatrices
import com.neoproc.neoproccamx.color.ApexSceneMetadata
import com.neoproc.neoproccamx.color.NeoGamutApexPipeline
import com.neoproc.neoproccamx.color.NeoGamutCat16Math
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * s1-f09 / CP11 — locks that the GPU demosaic (demosaic_vulkan.comp, s1-f08) consumes the CORRECT
 * forward sensor→ACEScg transform and NEVER the inverted DNG ColorMatrix path. Proves on the JVM,
 * no device:
 *   §4.1  the uploaded UBO matrices reconstruct the forward-path product
 *         xyzD50ToAcesAp1Matrix ∘ buildSensorToXyzD50FromForward,
 *   §4.2  a white-balanced neutral stays achromatic in ACEScg across the dual-illuminant CCT range
 *         (a*b* spread bound — the CP-Q4 gate uses ≤ 0.02),
 *   §4.3  the forward matrices are mired-linearly interpolated,
 *   §4.4  the demosaic matrix is NOT derived from SENSOR_COLOR_TRANSFORM / buildSensorToAcesAp1Matrix,
 *   no-downgrade: the default (no-camera-CCM) descriptor yields an identity combined transform.
 *
 * The two legs the live pipeline uploads are [XYZ_D50_TO_ACESCG] (constant) and the descriptor's
 * sensor→XYZ(D50) (per-camera forward, or [DEFAULT_SENSOR_TO_XYZ] fallback) — both defined in
 * VulkanCinemaDebayerPipeline.kt.
 */
class DemosaicForwardColorMatrixTest {

    private val cctA = ApexColorConfig.DUAL_ILLUMINANT_CCT_A
    private val cctD65 = ApexColorConfig.DUAL_ILLUMINANT_CCT_D65
    private val identity3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun maxChannelSpread(v: FloatArray): Float =
        max(max(abs(v[0] - v[1]), abs(v[1] - v[2])), abs(v[0] - v[2]))

    private fun maxAbsDiff(a: FloatArray, b: FloatArray): Float =
        (a.indices).maxOf { abs(a[it] - b[it]) }

    /** A neutral-preserving forward matrix: rows sum to the D50 white point, so sensor (1,1,1) → D50. */
    private fun neutralForward(): FloatArray {
        val d50 = ApexColorConfig.WhitePoint.D50.xyz
        return floatArrayOf(
            d50[0] / 3f, d50[0] / 3f, d50[0] / 3f,
            d50[1] / 3f, d50[1] / 3f, d50[1] / 3f,
            d50[2] / 3f, d50[2] / 3f, d50[2] / 3f
        )
    }

    // ──────────────────────────── §4.1 uploaded matrices == forward product ────────────────────────────

    @Test
    fun demosaicMapperEqualsForwardPathProduct() {
        val fm1 = floatArrayOf(0.80f, 0.10f, 0.10f, 0.20f, 0.70f, 0.10f, 0.05f, 0.10f, 0.90f)
        val fm2 = floatArrayOf(0.60f, 0.20f, 0.20f, 0.30f, 0.60f, 0.10f, 0.10f, 0.05f, 0.95f)
        val cct = 4200f

        val mapper = NeoGamutApexPipeline.buildDemosaicSensorToAcesCg(fm1, fm2, cct, cctA, cctD65)
        val expected = NeoGamutCat16Math.multiply3x3(
            NeoGamutApexPipeline.xyzD50ToAcesAp1Matrix(),
            NeoGamutApexPipeline.buildSensorToXyzD50FromForward(fm1, fm2, cct, cctA, cctD65)
        )
        assertArrayEquals("mapper must equal xyzD50ToAcesAp1 ∘ forward", expected, mapper, 1e-6f)
    }

    @Test
    fun uploadedTwoLegsReconstructForwardProduct() {
        // The pipeline uploads M_XYZ_TO_ACESCG = XYZ_D50_TO_ACESCG and M_SENSOR_TO_XYZ = the forward
        // sensor→XYZ(D50). The shader product of the two legs MUST equal the full forward mapper.
        val fm1 = floatArrayOf(0.78f, 0.12f, 0.10f, 0.22f, 0.68f, 0.10f, 0.04f, 0.11f, 0.92f)
        val fm2 = floatArrayOf(0.62f, 0.18f, 0.20f, 0.28f, 0.62f, 0.10f, 0.09f, 0.06f, 0.96f)
        val cct = 5200f

        val sensorToXyzLeg = NeoGamutApexPipeline.buildSensorToXyzD50FromForward(fm1, fm2, cct, cctA, cctD65)
        val shaderProduct = NeoGamutCat16Math.multiply3x3(XYZ_D50_TO_ACESCG, sensorToXyzLeg)
        val mapper = NeoGamutApexPipeline.buildDemosaicSensorToAcesCg(fm1, fm2, cct, cctA, cctD65)
        assertArrayEquals("uploaded legs must reconstruct the forward product", mapper, shaderProduct, 1e-6f)

        // And the constant second leg IS the project's D50→ACEScg chain (not a parallel copy).
        assertArrayEquals(NeoGamutApexPipeline.xyzD50ToAcesAp1Matrix(), XYZ_D50_TO_ACESCG, 1e-6f)
    }

    @Test
    fun defaultDescriptorIsIdentityCombinedNoDowngrade() {
        // No camera CCM → DEFAULT_SENSOR_TO_XYZ. The combined demosaic transform (second leg · default)
        // must be exactly identity so the gated demosaic stays neutral / byte-identical to pre-s1-f09.
        val combined = NeoGamutCat16Math.multiply3x3(XYZ_D50_TO_ACESCG, DEFAULT_SENSOR_TO_XYZ)
        assertArrayEquals("default combined transform must be identity", identity3, combined, 1e-4f)
    }

    // ──────────────────────────── §4.2 neutral preservation across CCT ────────────────────────────

    @Test
    fun whiteBalancedNeutralStaysAchromaticAcrossCct() {
        val fm = neutralForward()
        val neutral = floatArrayOf(1f, 1f, 1f)
        for (cct in listOf(cctA, (cctA + cctD65) * 0.5f, cctD65)) {
            val m = NeoGamutApexPipeline.buildDemosaicSensorToAcesCg(fm, fm, cct, cctA, cctD65)
            val ap1 = NeoGamutCat16Math.multiplyVector(m, neutral)
            assertTrue("neutral spread must be <= 0.02 at cct=$cct (got ${ap1.toList()})",
                maxChannelSpread(ap1) <= 0.02f)
            assertTrue("neutral must be positive at cct=$cct", ap1[0] > 0f)
        }
    }

    // ──────────────────────────── §4.3 mired-linear interpolation ────────────────────────────

    @Test
    fun forwardMatricesAreMiredLinearInterpolated() {
        val fm1 = floatArrayOf(0.80f, 0.10f, 0.10f, 0.20f, 0.70f, 0.10f, 0.05f, 0.10f, 0.90f)
        val fm2 = floatArrayOf(0.60f, 0.20f, 0.20f, 0.30f, 0.60f, 0.10f, 0.10f, 0.05f, 0.95f)
        val cct = 4000f
        val t = NeoGamutApexPipeline.miredInterpolationWeight(cct, cctA, cctD65)
        val analytic = FloatArray(9) { fm2[it] * (1f - t) + fm1[it] * t }
        val actual = NeoGamutApexPipeline.buildSensorToXyzD50FromForward(fm1, fm2, cct, cctA, cctD65)
        assertArrayEquals("forward interpolation must be analytic mired-linear", analytic, actual, 1e-5f)
        // Sanity: t is strictly interior at an intermediate CCT.
        assertTrue("weight must be interior at an intermediate CCT", t > 0.01f && t < 0.99f)
    }

    // ──────────────────────────── §4.4 inversion lock-out ────────────────────────────

    @Test
    fun demosaicMatrixIsNotTheInvertedColorMatrixPath() {
        // forward (sensor→XYZ) and ColorMatrix (XYZ→sensor) are DIFFERENT directions. Build both from
        // deliberately different fixtures and prove the demosaic mapper does NOT coincide with the
        // dormant inverted buildSensorToAcesAp1Matrix path — guarding the documented inversion at
        // NeoGamutApexPipeline.kt:209 out of the pixel leg.
        val forward = neutralForward()
        val colorMatrix = floatArrayOf(1.20f, -0.30f, -0.10f, -0.40f, 1.50f, -0.10f, 0.00f, -0.20f, 1.30f)
        val cct = 5000f

        val demosaicMatrix = NeoGamutApexPipeline.buildDemosaicSensorToAcesCg(forward, forward, cct, cctA, cctD65)
        val invertedPath = NeoGamutApexPipeline.buildSensorToAcesAp1Matrix(
            DualIlluminantSensorMatrices(colorMatrix, colorMatrix, cctA, cctD65),
            ApexSceneMetadata(cctKelvin = cct)
        )
        assertTrue("demosaic matrix must NOT equal the inverted ColorMatrix path (Δ=${maxAbsDiff(demosaicMatrix, invertedPath)})",
            maxAbsDiff(demosaicMatrix, invertedPath) > 0.05f)

        // The forward path keeps a WB-neutral achromatic; the inverted ColorMatrix path does not — a
        // direct demonstration that wiring the inverse would cast neutrals.
        val neutral = floatArrayOf(1f, 1f, 1f)
        val forwardNeutral = NeoGamutCat16Math.multiplyVector(demosaicMatrix, neutral)
        assertTrue("forward neutral must stay achromatic", maxChannelSpread(forwardNeutral) <= 0.02f)
        val invertedNeutral = NeoGamutCat16Math.multiplyVector(invertedPath, neutral)
        assertTrue("the inverted ColorMatrix path must visibly differ on a neutral",
            maxChannelSpread(invertedNeutral) > maxChannelSpread(forwardNeutral) + 0.02f)
    }

    // ──────────────────────────── selection helper / fallback ────────────────────────────

    @Test
    fun selectDemosaicSensorToXyzPicksForwardWhenAvailableElseFallback() {
        val fm1 = floatArrayOf(0.80f, 0.10f, 0.10f, 0.20f, 0.70f, 0.10f, 0.05f, 0.10f, 0.90f)
        val fm2 = floatArrayOf(0.60f, 0.20f, 0.20f, 0.30f, 0.60f, 0.10f, 0.10f, 0.05f, 0.95f)
        val cct = 4800f
        val fallback = DEFAULT_SENSOR_TO_XYZ

        val available = NeoGamutApexPipeline.selectDemosaicSensorToXyz(
            available = true, forwardMatrix1 = fm1, forwardMatrix2 = fm2,
            cctKelvin = cct, cct1Kelvin = cctA, cct2Kelvin = cctD65, fallback = fallback
        )
        assertArrayEquals("available → forward sensor→XYZ(D50)",
            NeoGamutApexPipeline.buildSensorToXyzD50FromForward(fm1, fm2, cct, cctA, cctD65), available, 1e-6f)

        val unavailable = NeoGamutApexPipeline.selectDemosaicSensorToXyz(
            available = false, forwardMatrix1 = fm1, forwardMatrix2 = fm2,
            cctKelvin = cct, cct1Kelvin = cctA, cct2Kelvin = cctD65, fallback = fallback
        )
        assertSame("unavailable → identity-safe fallback", fallback, unavailable)

        val nonFinite = NeoGamutApexPipeline.selectDemosaicSensorToXyz(
            available = true, forwardMatrix1 = FloatArray(9) { Float.NaN }, forwardMatrix2 = fm2,
            cctKelvin = cct, cct1Kelvin = cctA, cct2Kelvin = cctD65, fallback = fallback
        )
        assertSame("non-finite forward → fallback", fallback, nonFinite)
    }
}
