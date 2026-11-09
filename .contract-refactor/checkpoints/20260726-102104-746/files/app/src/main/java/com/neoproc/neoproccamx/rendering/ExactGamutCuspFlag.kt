package com.neoproc.neoproccamx.rendering

/**
 * CP-CUSP — process-global, runtime-flippable gate for the EXACT Rec.2020 Oklab gamut cusp in the live
 * preview knee boundary (neoGamutApexHueBoundary). Was a hand-tuned analytic cosine guard; the exact
 * cusp is computed per (lightness,hue) by a full-cube bisection against the Rec.2020 gamut, driven by
 * the uNeoGamutCuspParams.x UBO lane via [VulkanBridge.setExactGamutCuspEnabled], forwarded on the
 * VulkanRuntimeManager render tick.
 *
 * Default **ON** — approved on a saturated-scene look review (vivo I2405). It is a TRUE gamut CLAMP:
 * because the prior analytic guard deliberately sat ABOVE the real gamut, the exact Rec.2020 cusp is
 * TIGHTER, so it REDUCES out-of-gamut oversaturation (more accurate gamut mapping) — not a vibrance
 * boost (mathematically it can only reduce chroma), with skin/sky/foliage memory hues protected
 * downstream. Flip to false (or broadcast debug.exact_gamut_cusp false) to restore the prior analytic
 * guard's looser/punchier look.
 */
internal object ExactGamutCuspFlag {

    @Volatile
    var enabled: Boolean = true
}
