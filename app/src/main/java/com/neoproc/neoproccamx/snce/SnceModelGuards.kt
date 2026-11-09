package com.neoproc.neoproccamx.snce

/**
 * s2-f09 / CP4A — honest-fallback guards for the optional learned-model hooks.
 *
 * NO-VENDOR contract: every hook is optional and defaults to null; the classical pipeline must
 * never crash or be poisoned by a hook. A hook may be (a) null, (b) present but THROWING, or
 * (c) present but returning a DEGENERATE result (null / NaN-Inf / wrong shape / out-of-range).
 * In every case the guard returns null so the caller transparently falls back to the classical
 * path — a learned model can only ever *improve* on the classical floor, never break it.
 *
 * Pure, allocation-light, side-effect-free. Unit-tested by fuzzing each failure mode.
 */
object SnceModelGuards {

    private fun isFinitePositiveRgb(rgb: FloatArray?): Boolean =
        rgb != null && rgb.size == 3 && rgb.all { it.isFinite() && it > 0f }

    /**
     * Safely invoke a learned illuminant model. Returns the model's estimate ONLY when it is
     * present, did not throw, and produced a finite positive RGB triple with a finite confidence;
     * otherwise null (⇒ classical fusion runs unchanged).
     */
    fun safeIlluminant(model: SnceIlluminantModel?, thumb: SnceThumbnail): SnceModelIlluminant? {
        if (model == null) return null
        val result = try {
            model.estimateIlluminantRgb(thumb)
        } catch (t: Throwable) {
            null
        } ?: return null
        if (!isFinitePositiveRgb(result.rgb) || !result.confidence.isFinite()) return null
        return result
    }

    /**
     * Safely invoke a learned segmentation model. Returns the mask ONLY when present, non-throwing,
     * correctly shaped (classMask & confidence length == w*h, positive dims) and finite-confidence;
     * otherwise null (⇒ MediaPipe / classical masks are used).
     */
    fun safeSegmentation(model: SnceSegmentationModel?, thumb: SnceThumbnail): SnceSegmentationResult? {
        if (model == null) return null
        val r = try {
            model.segment(thumb)
        } catch (t: Throwable) {
            null
        } ?: return null
        if (r.width <= 0 || r.height <= 0) return null
        val n = r.width * r.height
        if (r.classMask.size != n || r.confidence.size != n) return null
        if (r.confidence.any { !it.isFinite() }) return null
        return r
    }

    /**
     * Safely invoke a learned adaptive-LUT model. Returns the LUT ONLY when present, non-throwing,
     * correctly sized and finite; otherwise null (⇒ the analytic look path is used).
     */
    fun safeAdaptiveLut(model: SnceAdaptiveLutModel?, thumb: SnceThumbnail): SnceAdaptiveLut? {
        if (model == null) return null
        val lut = try {
            model.generateLut(thumb)
        } catch (t: Throwable) {
            null
        } ?: return null
        val expected = lut.size * lut.size * lut.size * 3
        if (lut.rgb.size != expected || lut.rgb.any { !it.isFinite() }) return null
        return lut
    }

    /**
     * Safely invoke a learned face/skin model. Returns only the well-formed faces; a throwing model
     * yields an empty list (⇒ MediaPipe / ITA classical skin path). Never throws.
     */
    fun safeFaces(model: SnceFaceSkinModel?, thumb: SnceThumbnail): List<SnceFace> {
        if (model == null) return emptyList()
        val faces = try {
            model.detect(thumb)
        } catch (t: Throwable) {
            null
        } ?: return emptyList()
        return faces.filter { f ->
            f.boundingBox.size == 4 && f.boundingBox.all { it.isFinite() } &&
                f.confidence.isFinite() && f.skinLab.size == 3 && f.skinLab.all { it.isFinite() }
        }
    }
}
