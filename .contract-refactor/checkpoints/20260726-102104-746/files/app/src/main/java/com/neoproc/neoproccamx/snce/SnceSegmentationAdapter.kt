package com.neoproc.neoproccamx.snce

/**
 * s7-f04 / CP4A — pure CPU adapter from the MediaPipe segmentation category mask to the
 * [SnceSegmentationResult] grid the colour-constancy policy consumes.
 *
 * The live [com.neoproc.neoproccamx.ai.SegmentationEngine] emits a category mask whose byte values
 * index its MULTICLASS_LABELS ([Background, Hair, Body/Skin, Face/Skin, Clothes, Person]). The SNCE
 * colour path works on a tiny [SnceThumbnail] grid; this adapter nearest-neighbour resamples the
 * category mask onto that grid so [SnceSemanticColorConstancy] can average region chromaticity.
 *
 * No allocation beyond the output arrays; never throws on shape mismatch (returns null instead), so
 * a degenerate mask leaves the caller a no-op. NO model, NO GPU — classical resample only.
 */
object SnceSegmentationAdapter {

    /**
     * Resample [categoryMask] (row-major, [srcW]x[srcH], one class byte per pixel) onto a
     * [dstW]x[dstH] grid. [confidenceFor] supplies a per-class confidence in [0,1] (defaults to 1).
     * Returns null when the inputs are degenerate (non-positive dims, buffer too small).
     */
    fun fromCategoryMask(
        categoryMask: ByteArray?,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        confidenceFor: (Int) -> Float = { 1f }
    ): SnceSegmentationResult? {
        if (categoryMask == null) return null
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return null
        if (categoryMask.size < srcW * srcH) return null

        val classMask = IntArray(dstW * dstH)
        val confidence = FloatArray(dstW * dstH)
        for (y in 0 until dstH) {
            val sy = if (dstH == 1) 0 else (y * (srcH - 1)) / (dstH - 1)
            for (x in 0 until dstW) {
                val sx = if (dstW == 1) 0 else (x * (srcW - 1)) / (dstW - 1)
                val cls = categoryMask[sy * srcW + sx].toInt() and 0xFF
                val di = y * dstW + x
                classMask[di] = cls
                confidence[di] = confidenceFor(cls).coerceIn(0f, 1f)
            }
        }
        return SnceSegmentationResult(dstW, dstH, classMask, confidence)
    }
}
