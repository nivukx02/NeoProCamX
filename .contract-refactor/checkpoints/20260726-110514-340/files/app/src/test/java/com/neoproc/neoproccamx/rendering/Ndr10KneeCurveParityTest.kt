package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.exp

/**
 * Numeric characterization of the NDR10 / NIVUKX project-owned tone-mapping knee.
 *
 * The knee math lives only in two GLSL compute shaders — ndr10_knee_curve.comp and knee_curve.comp —
 * with byte-identical curve functions (adaptiveSigmoidKneeWeight + modifiedReinhardKnee). The existing
 * contract tests only assert the shader TEXT contains certain substrings; nothing ever evaluated the
 * curve. This test ports the GLSL math to a pure-Kotlin reference and PINS its current output with
 * golden values, then asserts the load-bearing constants still survive in BOTH shader sources so a
 * future GLSL edit that changes a number is caught.
 *
 * This is a CPU characterization of the algebraic spec, not a guarantee of on-device half-float
 * (rgba16f) results; tolerances absorb float32-vs-reference noise.
 */
class Ndr10KneeCurveParityTest {

    // ── Pure-Kotlin port of the GLSL knee, transcribed verbatim from ndr10_knee_curve.comp:28-50 ──
    private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun adaptiveSigmoidKneeWeight(value: Float, kneePoint: Float, kneeHardness: Float): Float {
        val safeKnee = kneePoint.coerceIn(0.55f, 0.75f)
        val safeHardness = kneeHardness.coerceIn(1.0f, 3.0f)
        val t = ((value - safeKnee) / maxOf(1.0f - safeKnee, 0.00001f)).coerceIn(0.0f, 1.0f)
        val centered = (t - 0.5f) * safeHardness * 4.0f
        val sigmoid = 1.0f / (1.0f + exp(-centered))
        val zeroPoint = 1.0f / (1.0f + exp(0.5f * safeHardness * 4.0f))
        val onePoint = 1.0f / (1.0f + exp(-0.5f * safeHardness * 4.0f))
        return ((sigmoid - zeroPoint) / maxOf(onePoint - zeroPoint, 0.00001f)).coerceIn(0.0f, 1.0f)
    }

    private fun knee(channel: Float, kneePoint: Float, kneeHardness: Float, maxCllNits: Float): Float {
        val x = maxOf(channel, 0.0f)
        val safeKnee = kneePoint.coerceIn(0.55f, 0.75f)
        val safeHardness = kneeHardness.coerceIn(1.0f, 3.0f)
        val safeMaxCll = maxOf(maxCllNits, 203.0f)
        val peakScale = (safeMaxCll / 1000.0f).coerceIn(0.25f, 4.0f)
        val kneeHeadroom = maxOf(peakScale - safeKnee, 0.00001f)
        val over = maxOf(x - safeKnee, 0.0f)
        val reinhard = safeKnee + (over * kneeHeadroom) / (over + kneeHeadroom / safeHardness)
        val weight = adaptiveSigmoidKneeWeight(x, safeKnee, safeHardness)
        return mix(x, reinhard, weight)
    }

    @Test
    fun goldenCurveAtDefaultParams() {
        // Default cinema knee: kneePoint=0.65, kneeHardness=2.0, maxCll=1000 nits.
        val golden = listOf(
            0.0f to 0.0f,
            0.25f to 0.25f,
            0.5f to 0.5f,
            0.65f to 0.65f,
            0.8f to 0.804104f,
            1.0f to 0.883333f,
            1.5f to 0.940244f,
            2.0f to 0.959836f,
            4.0f to 0.982624f
        )
        for ((x, expected) in golden) {
            assertEquals("knee($x)", expected, knee(x, 0.65f, 2.0f, 1000.0f), 1e-4f)
        }
    }

    @Test
    fun belowAndAtKneePassesThroughUnchanged() {
        // For x <= safeKnee the sigmoid weight is exactly 0, so the curve is identity.
        for (x in listOf(0.0f, 0.1f, 0.3f, 0.5f, 0.6f, 0.65f)) {
            assertEquals(x, knee(x, 0.65f, 2.0f, 1000.0f), 1e-6f)
        }
    }

    @Test
    fun defaultParamsAreMonotonicNonDecreasing() {
        var prev = -1f
        var x = 0.0f
        while (x <= 4.0001f) {
            val y = knee(x, 0.65f, 2.0f, 1000.0f)
            assertTrue("monotonic at x=$x: $y < $prev", y >= prev - 1e-6f)
            prev = y
            x += 0.01f
        }
    }

    @Test
    fun outputIsNonNegativeAcrossParameterGrid() {
        // NOTE: this curve is intentionally NOT globally monotonic, and out can exceed x and 1.0
        // (e.g. high maxCll expands; peakScale < knee collapses toward the knee). Only non-negativity
        // and the per-config goldens are universal.
        for (kp in listOf(0.55f, 0.65f, 0.75f)) {
            for (kh in listOf(1.0f, 2.0f, 3.0f)) {
                for (mc in listOf(203.0f, 500.0f, 1000.0f, 4000.0f)) {
                    var x = 0.0f
                    while (x <= 4.0001f) {
                        assertTrue(knee(x, kp, kh, mc) >= 0.0f)
                        x += 0.05f
                    }
                }
            }
        }
    }

    @Test
    fun goldenAtNonDefaultBranches() {
        // High maxCll expands highlights above input (peakScale=4.0 headroom branch).
        assertEquals(1.520312f, knee(1.0f, 0.55f, 3.0f, 4000.0f), 1e-4f)
        // Low maxCll (peakScale clamped to 0.25 < knee) collapses toward the knee point.
        assertEquals(0.75001f, knee(1.0f, 0.75f, 1.0f, 203.0f), 1e-4f)
    }

    @Test
    fun bothShaderSourcesStillCarryTheLoadBearingKneeMath() {
        val files = listOf(
            File("src/main/cpp/shaders/vulkan/ndr10_knee_curve.comp"),
            File("src/main/cpp/shaders/knee_curve.comp")
        )
        val sharedTokens = listOf(
            "float adaptiveSigmoidKneeWeight(",
            "float modifiedReinhardKnee(",
            "clamp(kneePoint, 0.55, 0.75)",
            "clamp(kneeHardness, 1.0, 3.0)",
            "(t - 0.5) * safeHardness * 4.0",
            "1.0 / (1.0 + exp(-centered))",
            "0.25, 4.0)",       // peakScale clamp bounds
            "203.0",            // HDR reference white
            "1000.0",           // default max CLL
            "(over + ",         // reinhard denominator structure
            "/ safeHardness)",
            "mix(x, reinhard, weight)"
        )
        for (file in files) {
            assertTrue("missing shader ${file.path}", file.isFile)
            val src = file.readText()
            for (token in sharedTokens) {
                assertTrue("${file.name} no longer contains: $token", src.contains(token))
            }
        }
    }
}
