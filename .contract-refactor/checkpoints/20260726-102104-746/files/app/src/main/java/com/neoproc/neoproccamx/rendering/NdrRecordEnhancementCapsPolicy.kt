package com.neoproc.neoproccamx.rendering

/**
 * ISO/noise-adaptive denoise + sharpen caps for the offscreen-EGL record shader (Fix C).
 *
 * The offscreen record shader already runs an edge-aware bilateral denoise and a halo-guarded
 * unsharp; the problem the user hit ("noise", "not rich detail") is that the HDR/LOG/RAW strength was
 * pinned to a flat, scene-blind cap (denoise 0.35 / sharpen 0.45) regardless of how noisy the frame
 * actually was — so a high-ISO dim frame got the same gentle denoise as a clean daylight frame. That
 * matters even more now that the anti-motion-blur shutter cap deliberately raises ISO in dim scenes.
 *
 * This policy scales the caps with a noise-severity estimate (max of an ISO ramp and the measured
 * noise metric): clean scenes keep today's gentle caps (strict no-downgrade at low ISO), noisy scenes
 * get a higher denoise ceiling (clean up the grain) and a LOWER sharpen ceiling (so the unsharp does
 * not re-amplify the residual grain it could not remove). SDR uses a fixed cinema-balanced cap
 * (denoise 0.45 / sharpen 0.45), intentionally lowered from the legacy 0.50/0.60 to remove over-sharpen
 * crunch and waxy denoise (approved cinema-balance change; matches the offline SDR develop).
 */
object NdrRecordEnhancementCapsPolicy {

    data class Caps(val denoiseCap: Float, val sharpenCap: Float)

    // Clean-scene caps (identical to the previous hard-coded values → no downgrade at low ISO/noise).
    private const val HDR_DENOISE_CAP_CLEAN = 0.35f
    private const val HDR_SHARPEN_CAP_CLEAN = 0.45f
    // Noisy-scene caps: denoise higher (remove grain), sharpen lower (don't re-amplify what remains).
    private const val HDR_DENOISE_CAP_NOISY = 0.72f
    private const val HDR_SHARPEN_CAP_NOISY = 0.30f

    // ISO ramp: clean at/below floor, full severity at/above ceiling.
    private const val ISO_CLEAN = 800f
    private const val ISO_NOISY = 6400f

    /**
     * @param family the signal family; SDR (and null) are returned unchanged.
     * @param iso raw ISO sensitivity (e.g. 100..6400); <=0 treated as clean.
     * @param noiseMetric normalized measured luma-noise metric (0..1).
     */
    fun resolve(family: GpuSignalFamily?, iso: Float, noiseMetric: Float): Caps = when (family) {
        GpuSignalFamily.SDR -> Caps(denoiseCap = 0.45f, sharpenCap = 0.45f)
        null -> Caps(denoiseCap = 0.50f, sharpenCap = 0.55f)
        GpuSignalFamily.HDR, GpuSignalFamily.LOG, GpuSignalFamily.RAW -> {
            val severity = noiseSeverity(iso, noiseMetric)
            Caps(
                denoiseCap = lerp(HDR_DENOISE_CAP_CLEAN, HDR_DENOISE_CAP_NOISY, severity),
                sharpenCap = lerp(HDR_SHARPEN_CAP_CLEAN, HDR_SHARPEN_CAP_NOISY, severity)
            )
        }
    }

    /** 0 (clean) .. 1 (very noisy): the stronger of the ISO ramp and the measured noise metric. */
    fun noiseSeverity(iso: Float, noiseMetric: Float): Float {
        val isoTerm = if (iso <= ISO_CLEAN) {
            0f
        } else {
            ((iso - ISO_CLEAN) / (ISO_NOISY - ISO_CLEAN)).coerceIn(0f, 1f)
        }
        // M4: the measured metric is a frame-global luma stddev, dominated by scene CONTRAST, not grain — a
        // sharp high-contrast scene reads "noisy" and a flat clean scene reads "clean" (backwards), which used
        // to push detailed scenes toward MORE denoise + LESS sharpen (waxy + soft). Cap the metric's share of
        // severity at 0.5 and scale it gently (full 0.5 only at ~30% stddev) so scene contrast can no longer
        // swing the caps hard. Genuine high-ISO noise still drives FULL severity via isoTerm (unchanged) →
        // nothing is under-denoised; offline (iso=0) severity is now bounded to <=0.5, keeping the look STABLE.
        val metricTerm = (noiseMetric / 0.60f).coerceIn(0f, 0.5f)
        return maxOf(isoTerm, metricTerm)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}
