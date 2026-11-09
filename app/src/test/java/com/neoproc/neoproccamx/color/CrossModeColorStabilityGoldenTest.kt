package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Golden / characterization lock for the private ColorScience.applyCrossModeColorStability across
 * every mode family (VIDEO/PRO SDR, HDR, NDR, LOG, wide-gamut). The function's only cross-mode
 * divergence is its `baseMix` branch (SDR 0.036 / wide-gamut+HDR 0.054 / LOG 0.055); everything else
 * is mode-independent. These goldens were captured from the current build and FREEZE today's exact
 * per-mode output so a future refactor of the masks, neutral pull, dominance trims, or luma
 * compensation cannot silently shift cross-mode color.
 *
 * Invoked via reflection (the established repo pattern, e.g. QfcaV2CoreTest) since the method is
 * private. The function is pure over its 5 args and reads no instance state. NOTE: the logEnabled
 * and standards-name (Rec.2020) cases are gated OUT of the live applyActiveColorPipeline path
 * (resolveColorPipeline), so they are pinned here as the function's intrinsic contract, not as live
 * production paths. Test-only: no production code changed.
 */
class CrossModeColorStabilityGoldenTest {

    private val cs = ColorScience()
    private val method = ColorScience::class.java.getDeclaredMethod(
        "applyCrossModeColorStability",
        Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
        String::class.java, Boolean::class.javaPrimitiveType
    ).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun stab(profile: String, log: Boolean, r: Float, g: Float, b: Float): Triple<Float, Float, Float> =
        method.invoke(cs, r, g, b, profile, log) as Triple<Float, Float, Float>

    private data class Golden(
        val profile: String, val log: Boolean,
        val r: Float, val g: Float, val b: Float,
        val er: Float, val eg: Float, val eb: Float
    )

    private fun g(profile: String, log: Boolean, r: Float, g: Float, b: Float, er: Float, eg: Float, eb: Float) =
        Golden(profile, log, r, g, b, er, eg, eb)

    private val goldens = listOf(
        g("Natural", false, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
        g("Natural", false, 0.8f, 0.55f, 0.45f, 0.7862744f, 0.5531335f, 0.45937806f),
        g("Natural", false, 0.3f, 0.45f, 0.78f, 0.31706372f, 0.44951066f, 0.73460126f),
        g("Natural", false, 0.85f, 0.3f, 0.28f, 0.7897108f, 0.31572136f, 0.3017943f),
        g("Natural", false, 0.95f, 0.92f, 0.9f, 0.9477893f, 0.9204671f, 0.9018827f),
        g("Cinematic", false, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
        g("Cinematic", false, 0.8f, 0.55f, 0.45f, 0.7862744f, 0.5531335f, 0.45937806f),
        g("Cinematic", false, 0.3f, 0.45f, 0.78f, 0.31706372f, 0.44951066f, 0.73460126f),
        g("Cinematic", false, 0.85f, 0.3f, 0.28f, 0.7897108f, 0.31572136f, 0.3017943f),
        g("Cinematic", false, 0.95f, 0.92f, 0.9f, 0.9477893f, 0.9204671f, 0.9018827f),
        g("HDR10 Native", false, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
        g("HDR10 Native", false, 0.8f, 0.55f, 0.45f, 0.78383505f, 0.5536819f, 0.46112797f),
        g("HDR10 Native", false, 0.3f, 0.45f, 0.78f, 0.31942853f, 0.4493672f, 0.7290587f),
        g("HDR10 Native", false, 0.85f, 0.3f, 0.28f, 0.7833676f, 0.3174124f, 0.30372143f),
        g("HDR10 Native", false, 0.95f, 0.92f, 0.9f, 0.94736874f, 0.9205492f, 0.9023068f),
        g("Rec.2100 (HDR)", false, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
        g("Rec.2100 (HDR)", false, 0.8f, 0.55f, 0.45f, 0.78383505f, 0.5536819f, 0.46112797f),
        g("Rec.2100 (HDR)", false, 0.3f, 0.45f, 0.78f, 0.31942853f, 0.4493672f, 0.7290587f),
        g("Rec.2100 (HDR)", false, 0.85f, 0.3f, 0.28f, 0.7833676f, 0.3174124f, 0.30372143f),
        g("Rec.2100 (HDR)", false, 0.95f, 0.92f, 0.9f, 0.94736874f, 0.9205492f, 0.9023068f),
        g("NDR10+", false, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
        g("NDR10+", false, 0.8f, 0.55f, 0.45f, 0.78383505f, 0.5536819f, 0.46112797f),
        g("NDR10+", false, 0.3f, 0.45f, 0.78f, 0.31942853f, 0.4493672f, 0.7290587f),
        g("NDR10+", false, 0.85f, 0.3f, 0.28f, 0.7833676f, 0.3174124f, 0.30372143f),
        g("NDR10+", false, 0.95f, 0.92f, 0.9f, 0.94736874f, 0.9205492f, 0.9023068f),
        g("NIVUKX Vision", false, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
        g("NIVUKX Vision", false, 0.8f, 0.55f, 0.45f, 0.78383505f, 0.5536819f, 0.46112797f),
        g("NIVUKX Vision", false, 0.3f, 0.45f, 0.78f, 0.31942853f, 0.4493672f, 0.7290587f),
        g("NIVUKX Vision", false, 0.85f, 0.3f, 0.28f, 0.7833676f, 0.3174124f, 0.30372143f),
        g("NIVUKX Vision", false, 0.95f, 0.92f, 0.9f, 0.94736874f, 0.9205492f, 0.9023068f),
        g("N-Log Cinema", true, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
        g("N-Log Cinema", true, 0.8f, 0.55f, 0.45f, 0.78369945f, 0.55371237f, 0.46122515f),
        g("N-Log Cinema", true, 0.3f, 0.45f, 0.78f, 0.31955993f, 0.44935927f, 0.7287509f),
        g("N-Log Cinema", true, 0.85f, 0.3f, 0.28f, 0.7830152f, 0.31750637f, 0.30382848f),
        g("N-Log Cinema", true, 0.95f, 0.92f, 0.9f, 0.94734544f, 0.92055386f, 0.90233046f),
        g("Natural", true, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
        g("Natural", true, 0.8f, 0.55f, 0.45f, 0.78369945f, 0.55371237f, 0.46122515f),
        g("Natural", true, 0.3f, 0.45f, 0.78f, 0.31955993f, 0.44935927f, 0.7287509f),
        g("Natural", true, 0.85f, 0.3f, 0.28f, 0.7830152f, 0.31750637f, 0.30382848f),
        g("Natural", true, 0.95f, 0.92f, 0.9f, 0.94734544f, 0.92055386f, 0.90233046f),
        g("Rec.2020", false, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f),
        g("Rec.2020", false, 0.8f, 0.55f, 0.45f, 0.78383505f, 0.5536819f, 0.46112797f),
        g("Rec.2020", false, 0.3f, 0.45f, 0.78f, 0.31942853f, 0.4493672f, 0.7290587f),
        g("Rec.2020", false, 0.85f, 0.3f, 0.28f, 0.7833676f, 0.3174124f, 0.30372143f),
        g("Rec.2020", false, 0.95f, 0.92f, 0.9f, 0.94736874f, 0.9205492f, 0.9023068f)
    )

    @Test
    fun pinnedGoldensAcrossAllModes() {
        for (gd in goldens) {
            val label = "${gd.profile}/log=${gd.log} @(${gd.r},${gd.g},${gd.b})"
            val (r, g, b) = stab(gd.profile, gd.log, gd.r, gd.g, gd.b)
            assertEquals("$label R", gd.er, r, 1e-4f)
            assertEquals("$label G", gd.eg, g, 1e-4f)
            assertEquals("$label B", gd.eb, b, 1e-4f)
        }
    }

    @Test
    fun modeDivergenceIsPreserved() {
        // The SDR / HDR / LOG baseMix split must not be flattened — pin a non-trivial gap on a
        // strongly-chromatic skin-tone input.
        val sdr = stab("Natural", false, 0.8f, 0.55f, 0.45f)
        val hdr = stab("HDR10 Native", false, 0.8f, 0.55f, 0.45f)
        val log = stab("N-Log Cinema", true, 0.8f, 0.55f, 0.45f)
        assertTrue("SDR vs HDR must differ", abs(sdr.first - hdr.first) > 1e-4f)
        assertTrue("SDR vs LOG must differ", abs(sdr.first - log.first) > 1e-4f)
    }

    @Test
    fun allOutputsAreFiniteAndInRange() {
        for (gd in goldens) {
            val (r, g, b) = stab(gd.profile, gd.log, gd.r, gd.g, gd.b)
            assertTrue(r.isFinite() && g.isFinite() && b.isFinite())
            assertTrue(r in 0f..1f && g in 0f..1f && b in 0f..1f)
        }
    }
}
