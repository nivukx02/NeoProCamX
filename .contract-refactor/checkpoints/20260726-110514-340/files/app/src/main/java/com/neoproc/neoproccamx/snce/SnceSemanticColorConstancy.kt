package com.neoproc.neoproccamx.snce

/**
 * s2-f09 / CP4A — classical semantic-region colour constancy (NO-VENDOR).
 *
 * Derives a SMALL, bounded white-balance nudge keyed to a scene region (skin / sky / foliage) from
 * a segmentation mask. The mask comes from the already-shipped MediaPipe `ImageSegmenter` or a
 * classical mask — the LEARNED segmentation hook stays null. The correction can only ever nudge the
 * region's mean chromaticity a fraction of the way toward its memory-colour anchor, hard-clamped to
 * the engine's ±0.05 WB ceiling (same ceiling as [SnceLiveColorController]).
 *
 * Gated off by default ([gateEnabled] = false ⇒ [NO_OP]); a null/mismatched/empty mask is also a
 * no-op. Live wiring is the device-gated step. Pure and unit-tested.
 */
object SnceSemanticColorConstancy {

    /** Hard ceiling, matching the global ±0.05 WB clamp the whole engine respects. */
    const val MAX_WB_NUDGE = 0.05f

    /** Additive WB nudges (red / blue gain deltas), each guaranteed in [-0.05, +0.05]. */
    class RegionCorrection(val wbR: Float, val wbB: Float)

    val NO_OP = RegionCorrection(0f, 0f)

    // s7-f04/CP4A — per-region memory-colour r/g & b/g anchors. ENGINEERING PLACEHOLDERS, NOT
    // measured; the integrator MUST calibrate these to real device thumbnails before enabling on
    // device. (Do not reuse the CAM16 {J,C,h,M} numbers from SnceMemoryColor — those are a different
    // colour space.)
    const val SKIN_TARGET_ROVERG = 1.18f
    const val SKIN_TARGET_BOVERG = 0.85f
    const val SKY_TARGET_ROVERG = 0.82f
    const val SKY_TARGET_BOVERG = 1.22f
    const val FOLIAGE_TARGET_ROVERG = 0.80f
    const val FOLIAGE_TARGET_BOVERG = 0.78f

    /** Modest per-region pull so several regions summed stay well inside the clamp. */
    const val REGION_STRENGTH = 0.4f

    // MediaPipe selfie-multiclass label indices: Body/Skin = 2, Face/Skin = 3. The live selfie set
    // has NO sky/foliage class, so SKY/FOLIAGE only fire from a future richer mask source; they are
    // exercised in unit tests via a synthetic mask. Class ids 10/11 are placeholders for that source.
    const val CLASS_BODY_SKIN = 2
    const val CLASS_FACE_SKIN = 3
    const val CLASS_SKY = 10
    const val CLASS_FOLIAGE = 11

    /**
     * s7-f04/CP4A — sum the per-region [regionKeyedCorrection]s (skin/sky/foliage) into a SINGLE
     * additive (wbR, wbB), then hard-clamp the TOTAL to ±[MAX_WB_NUDGE] so stacking regions can
     * never exceed the global WB budget. No-op (NO_OP) when gated off or the mask is absent.
     */
    fun regionConstancyNudge(
        mask: SnceSegmentationResult?,
        thumb: SnceThumbnail,
        gateEnabled: Boolean = false
    ): RegionCorrection {
        if (!gateEnabled || mask == null) return NO_OP
        var wbR = 0f
        var wbB = 0f
        val regions = listOf(
            Triple(CLASS_BODY_SKIN, SKIN_TARGET_ROVERG, SKIN_TARGET_BOVERG),
            Triple(CLASS_FACE_SKIN, SKIN_TARGET_ROVERG, SKIN_TARGET_BOVERG),
            Triple(CLASS_SKY, SKY_TARGET_ROVERG, SKY_TARGET_BOVERG),
            Triple(CLASS_FOLIAGE, FOLIAGE_TARGET_ROVERG, FOLIAGE_TARGET_BOVERG)
        )
        for ((classId, rTarget, bTarget) in regions) {
            val c = regionKeyedCorrection(mask, thumb, classId, rTarget, bTarget, REGION_STRENGTH, gateEnabled = true)
            wbR += c.wbR
            wbB += c.wbB
        }
        return RegionCorrection(
            wbR.coerceIn(-MAX_WB_NUDGE, MAX_WB_NUDGE),
            wbB.coerceIn(-MAX_WB_NUDGE, MAX_WB_NUDGE)
        )
    }

    /**
     * Bounded WB nudge that moves the [regionClassId] region's mean chromaticity a [strength]
     * fraction toward the anchor (targetRoverG, targetBoverG). No-op when gated off, the mask is
     * absent / mismatched, or the region is empty.
     */
    fun regionKeyedCorrection(
        mask: SnceSegmentationResult?,
        thumb: SnceThumbnail,
        regionClassId: Int,
        targetRoverG: Float,
        targetBoverG: Float,
        strength: Float = 0.5f,
        gateEnabled: Boolean = false
    ): RegionCorrection {
        if (!gateEnabled || mask == null) return NO_OP
        if (mask.width != thumb.width || mask.height != thumb.height) return NO_OP

        var sumR = 0.0; var sumG = 0.0; var sumB = 0.0; var count = 0
        val n = thumb.pixelCount()
        for (i in 0 until n) {
            if (mask.classMask[i] != regionClassId) continue
            sumR += thumb.rgb[i * 3]; sumG += thumb.rgb[i * 3 + 1]; sumB += thumb.rgb[i * 3 + 2]
            count++
        }
        if (count == 0) return NO_OP

        val meanG = (sumG / count).coerceAtLeast(1e-6).toFloat()
        val rOverG = (sumR / count).toFloat() / meanG
        val bOverG = (sumB / count).toFloat() / meanG
        val s = strength.coerceIn(0f, 1f)
        val wbR = (s * (targetRoverG - rOverG)).coerceIn(-MAX_WB_NUDGE, MAX_WB_NUDGE)
        val wbB = (s * (targetBoverG - bOverG)).coerceIn(-MAX_WB_NUDGE, MAX_WB_NUDGE)
        return RegionCorrection(wbR, wbB)
    }
}
