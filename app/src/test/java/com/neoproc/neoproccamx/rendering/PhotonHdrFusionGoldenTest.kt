package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * CP32 / s1-f10 — foundation golden for NeoProCamX's photon dual-exposure fusion radiance blend
 * ([PhotonHdrFusionReference], the host port of photon_hdr_fusion.comp's static path). Proves the
 * deterministic DR behaviour on the host (the motion-compensated warp stays device-gated):
 *   - highlights that CLIP the long exposure are recovered from the (scaled) short exposure,
 *   - the cleaner long exposure dominates the shadows / midtones,
 *   - a neutral input stays achromatic.
 */
class PhotonHdrFusionGoldenTest {

    private val ratio = 8f                 // long:short exposure ratio (short is 1/8 the light)
    private val sensorClip = 1.0f          // the long exposure clips here

    private fun params() = PhotonHdrFusionReference.Params(
        highlightThreshold = 0.85f, clipThreshold = 0.98f, longScale = 1f, shortScale = ratio
    )

    /** A neutral scene of radiance [s]: long = min(s, clip) (clips highlights), short = s/ratio. */
    private fun reads(s: Float): Pair<FloatArray, FloatArray> {
        val longV = min(s, sensorClip)
        val shortV = s / ratio
        return floatArrayOf(longV, longV, longV) to floatArrayOf(shortV, shortV, shortV)
    }

    private fun luma(c: FloatArray) = c[0] * 0.2126f + c[1] * 0.7152f + c[2] * 0.0722f

    @Test
    fun highlightClippingTheLongExposureIsRecoveredFromTheShort() {
        val (lng, shrt) = reads(3.0f) // bright → long clips at 1.0, short holds it
        val p = params()
        val c = PhotonHdrFusionReference.longClipConfidence(lng, p)
        assertTrue("long exposure must be detected as clipping (c=$c)", c > 0.9f)

        val fused = PhotonHdrFusionReference.fuseStatic(lng, shrt, p)
        val longScaledLuma = luma(lng) * p.longScale // = 1.0 (clipped)
        // The fused highlight is recovered ABOVE the long-exposure clip via the short read.
        assertTrue("fused must recover highlight beyond the long clip (${luma(fused)} vs $longScaledLuma)",
            luma(fused) > longScaledLuma + 0.5f)
        assertEquals("fused highlight tracks the scaled short exposure", 3.0f, luma(fused), 1e-4f)
    }

    @Test
    fun shadowsAndMidtonesComeFromTheCleanerLongExposure() {
        for (s in listOf(0.05f, 0.2f, 0.5f)) {
            val (lng, shrt) = reads(s)
            val p = params()
            val c = PhotonHdrFusionReference.longClipConfidence(lng, p)
            assertTrue("the long exposure must be selected (clean) at s=$s (c=$c)", c < 0.1f)
            val fused = PhotonHdrFusionReference.fuseStatic(lng, shrt, p)
            assertEquals("fused tracks the long radiance at s=$s", s, luma(fused), 1e-4f)
        }
    }

    @Test
    fun confidenceRampIsMonotonicFromShadowToClip() {
        var prev = -1f
        for (s in listOf(0.2f, 0.6f, 0.85f, 0.95f, 1.5f)) {
            val (lng, _) = reads(s)
            val c = PhotonHdrFusionReference.longClipConfidence(lng, params())
            assertTrue("confidence must be non-decreasing toward the clip at s=$s ($c < $prev)", c >= prev - 1e-5f)
            prev = c
        }
        assertEquals("fully clipped long → full hand-off", 1f, prev, 1e-5f)
    }

    @Test
    fun fusionPreservesNeutral() {
        for (s in listOf(0.1f, 0.5f, 1.2f, 3.0f)) {
            val (lng, shrt) = reads(s)
            val fused = PhotonHdrFusionReference.fuseStatic(lng, shrt, params())
            val spread = max(max(abs(fused[0] - fused[1]), abs(fused[1] - fused[2])), abs(fused[0] - fused[2]))
            assertTrue("neutral input must stay achromatic at s=$s (spread=$spread)", spread < 1e-5f)
        }
    }
}
