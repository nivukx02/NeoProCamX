package com.neoproc.neoproccamx.camera

/**
 * Closed-loop ADAPTIVE highlight protection for NDR10+/NIVUKX capture.
 *
 * The static [HdrHighlightProtectExposurePolicy] applies a fixed -0.67 EV floor. That is not enough
 * for a bright / high-dynamic-range scene where the HAL auto-exposure lifts by multiple stops — the
 * highlights still clip to white, and warm skin clips the RED channel first (the "red skin" the user
 * saw is an overexposure symptom, not a colour bug). This policy adds an EXTRA negative EV bias, on
 * top of the -0.67 floor, driven by the live scene measurement (average luma + the fraction of
 * near-clipped highlight pixels). It only ever makes the frame DARKER to rescue highlights — never
 * brighter than today — so it is a strict no-regression lever.
 *
 * Pure function of the two measured signals; the caller (CameraController) smooths it over time and
 * only rebuilds the request when the resulting compensation index would actually change.
 */
internal object NdrAdaptiveHighlightExposurePolicy {

    /** Hard cap on the extra protection so the picture can never be driven implausibly dark. */
    const val MAX_EXTRA_STOPS = -1.0f

    /**
     * Conservative protection while the optional live-analysis surface is absent or has stopped.
     * Together with the normal -0.67 baseline this requests about -1.34 EV, preserving outdoor
     * highlights without changing bit depth, gamut, transfer, resolution, frame rate or bitrate.
     */
    const val NO_ANALYSIS_FALLBACK_EXTRA_STOPS = -0.67f

    /** A live meter older than this must not be trusted as the current scene. */
    const val LIVE_METRICS_MAX_AGE_MS = 1_500L

    /** Camera YUV analysis streams normally use 8-bit video range (nominal white = code 235). */
    private const val VIDEO_RANGE_WHITE_CODE_8_BIT = 235

    /** Strong evidence that the supplied histogram is full-range rather than video-range. */
    private const val FULL_RANGE_TAIL_START_CODE_8_BIT = 252

    /**
     * @param averageLuma live frame-average luminance, 0..1 (from the analysis stream `avgLuma`).
     * @param highlightClipFraction fraction of pixels sitting in the top (near-clipped) luma bins, 0..1.
     * @param redHighlightClipFraction fraction of pixels whose reconstructed RED channel is near clip,
     *   0..1 (see [FrameAnalysisMath.computeRedHighlightClipFraction]). Pass 0 to disable this driver.
     * @return the ADDITIONAL highlight-protect bias in stops (<= 0), to add to the -0.67 floor.
     */
    fun resolveExtraStops(
        averageLuma: Float,
        highlightClipFraction: Float,
        redHighlightClipFraction: Float = 0f
    ): Float {
        val luma = averageLuma.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.5f
        val clip = highlightClipFraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val redClip = redHighlightClipFraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f

        // Primary driver: measured highlight clipping. Real clipped area => pull exposure down hard.
        val clipStops = when {
            clip >= 0.25f -> -0.83f
            clip >= 0.12f -> -0.50f
            clip >= 0.05f -> -0.25f
            else -> 0f
        }
        // Secondary driver: a very bright overall scene (protects before hard clipping sets in).
        val lumaStops = when {
            luma >= 0.80f -> -0.33f
            luma >= 0.70f -> -0.18f
            luma >= 0.62f -> -0.08f
            else -> 0f
        }
        // RED-channel highlight-clip driver (the NDR/NIVUKX "red skin" root). Per ITU-T H.Sup15, luma
        // Y' is NOT luminance and BT.2020 weights red at only 0.2627, so a warm subject can hard-clip
        // its RED channel while the two luma drivers above still read moderate and never react. This
        // driver reacts to the actual clipping channel and, because red clips first, it engages at
        // smaller fractions than the luma-clip driver. Only ever darker (highlight rescue).
        val redClipStops = when {
            redClip >= 0.15f -> -1.00f
            redClip >= 0.08f -> -0.67f
            redClip >= 0.03f -> -0.42f
            redClip >= 0.012f -> -0.22f
            else -> 0f
        }
        // Take the STRONGEST (most negative) of the three drivers, capped.
        return minOf(clipStops, lumaStops, redClipStops).coerceAtLeast(MAX_EXTRA_STOPS)
    }

    /**
     * Fast attack saves highlights before they flatten; slow release avoids visible exposure pumping.
     */
    fun smoothExtraStops(currentStops: Float, targetStops: Float): Float {
        val current = currentStops.takeIf(Float::isFinite)?.coerceIn(MAX_EXTRA_STOPS, 0f) ?: 0f
        val target = targetStops.takeIf(Float::isFinite)?.coerceIn(MAX_EXTRA_STOPS, 0f) ?: 0f
        val alpha = if (target < current) 0.60f else 0.12f
        return (current + (target - current) * alpha).coerceIn(MAX_EXTRA_STOPS, 0f)
    }

    /** Use measured protection only while actual frame metrics are fresh. */
    fun resolveAppliedExtraStops(measuredExtraStops: Float, liveMetricsFresh: Boolean): Float =
        if (liveMetricsFresh) {
            measuredExtraStops.takeIf(Float::isFinite)?.coerceIn(MAX_EXTRA_STOPS, 0f) ?: 0f
        } else {
            NO_ANALYSIS_FALLBACK_EXTRA_STOPS
        }

    /**
     * Fraction of a luma histogram that sits at nominal white (near clipping).
     *
     * Camera `YUV_420_888` is normally video-range, where white is code 235—not 255. The old
     * implementation inspected only bins 252..255, so a correctly limited camera stream reported zero
     * highlight clipping even when code 235 was completely saturated. If the terminal bins contain a
     * meaningful population we treat the frame as full-range and keep the old 255-based threshold;
     * otherwise the threshold is anchored to video-range white. This is analysis-only: pixel precision,
     * gamut, metadata, and encoded data are untouched.
     */
    fun highlightClipFraction(histogram: IntArray?, topBins: Int = 4): Float {
        if (histogram == null || histogram.isEmpty()) return 0f
        val bins = histogram.size
        var total = 0L
        for (i in 0 until bins) {
            val v = histogram[i].coerceAtLeast(0)
            total += v
        }
        if (total <= 0L) return 0f

        val scaledFullRangeTailStart =
            (FULL_RANGE_TAIL_START_CODE_8_BIT * (bins - 1) / 255).coerceIn(0, bins - 1)
        var terminalTail = 0L
        for (i in scaledFullRangeTailStart until bins) {
            terminalTail += histogram[i].coerceAtLeast(0)
        }
        val minimumRangeEvidence = maxOf(4L, total / 1_000L)
        val nominalWhite = if (terminalTail >= minimumRangeEvidence) {
            bins - 1
        } else {
            (VIDEO_RANGE_WHITE_CODE_8_BIT * (bins - 1) / 255).coerceIn(0, bins - 1)
        }
        val scaledWidth = maxOf(1, (topBins.coerceAtLeast(1) * bins + 255) / 256)
        val from = (nominalWhite - scaledWidth + 1).coerceIn(0, bins - 1)
        var nearClip = 0L
        for (i in from until bins) {
            nearClip += histogram[i].coerceAtLeast(0)
        }
        return (nearClip.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
    }
}
