package com.neoproc.neoproccamx.stabilization

/**
 * s9 / CP-QV22 — pure builder for the dormant [StabilizationSessionController.Config] from the raw camera
 * parameters [com.neoproc.neoproccamx.camera.CameraController] actually knows.
 *
 * This is the ONLY new decision the CP-QV22 wiring introduces (the controller and its per-frame maths are
 * already golden-locked by CP-QV18). It derives the geometry the still-dormant `eis_warp.comp` grid needs:
 *
 *  - `focalLengthPx = focalLengthMm / sensorWidthMm · activeArrayWidthPx` — the pinhole focal length in the
 *    active-array pixel grid (rotation → pixel-shift scale). Falls back to [FALLBACK_FOCAL_LENGTH_PX] when
 *    either physical dimension is unreported (LIMITED devices leave `SENSOR_INFO_PHYSICAL_SIZE` / focal
 *    length null), so the seam is never built with a zero/NaN scale.
 *  - `rows` = active-array height (one warp lane per sensor scanline), clamped ≥1.
 *  - `referenceRow` = centre row (the frame pivots about its middle, matching the CP-QV18 test convention).
 *  - `centerX/Y` = active-array centre (rotation pivot).
 *  - `cropMarginPixels` = a fraction of the smaller active-array dimension (headroom for the counter-warp),
 *    defaulting to [DEFAULT_CROP_MARGIN_FRACTION].
 *  - `cutoffHz` = [DEFAULT_CUTOFF_HZ] trajectory-smoothing corner.
 *
 * Everything here is a value derivation with sane, documented fallbacks — no allocation happens on the live
 * path because the caller only invokes this once at camera-configure time and only when the (default-OFF)
 * consumer flag is on. The produced [StabilizationSessionController.Config] drives no pixel until the GPU
 * EIS upload path is turned on device-side.
 */
object StabilizationSessionConfigPolicy {

    /** Corner frequency (Hz) for the one-pole trajectory smoother — lower = steadier, more crop. */
    const val DEFAULT_CUTOFF_HZ = 1.2f

    /** Crop headroom as a fraction of the smaller active-array dimension. */
    const val DEFAULT_CROP_MARGIN_FRACTION = 0.08f

    /** Focal length in px when the physical geometry is unreported — a mid-tele-ish pinhole default. */
    const val FALLBACK_FOCAL_LENGTH_PX = 1000f

    /** Sane active-array fallbacks so a null/zero sensor never yields a degenerate grid. */
    const val FALLBACK_ACTIVE_WIDTH_PX = 1920
    const val FALLBACK_ACTIVE_HEIGHT_PX = 1080

    /**
     * Build the dormant controller config from the raw camera params.
     *
     * @param focalLengthMm `LENS_INFO_AVAILABLE_FOCAL_LENGTHS[0]` (0 / null → fallback focal px).
     * @param sensorWidthMm `SENSOR_INFO_PHYSICAL_SIZE.width` (0 / null → fallback focal px).
     * @param activeArrayWidthPx `SENSOR_INFO_ACTIVE_ARRAY_SIZE.width` (≤0 → fallback width).
     * @param activeArrayHeightPx `SENSOR_INFO_ACTIVE_ARRAY_SIZE.height` (≤0 → fallback height); also the row count.
     * @param cutoffHz smoothing corner; non-positive → [DEFAULT_CUTOFF_HZ].
     * @param cropMarginFraction crop headroom fraction; non-positive → [DEFAULT_CROP_MARGIN_FRACTION].
     */
    fun build(
        focalLengthMm: Float,
        sensorWidthMm: Float,
        activeArrayWidthPx: Int,
        activeArrayHeightPx: Int,
        cutoffHz: Float = DEFAULT_CUTOFF_HZ,
        cropMarginFraction: Float = DEFAULT_CROP_MARGIN_FRACTION,
    ): StabilizationSessionController.Config {
        val widthPx = if (activeArrayWidthPx > 0) activeArrayWidthPx else FALLBACK_ACTIVE_WIDTH_PX
        val heightPx = if (activeArrayHeightPx > 0) activeArrayHeightPx else FALLBACK_ACTIVE_HEIGHT_PX

        val focalPx =
            if (focalLengthMm > 0f && sensorWidthMm > 0f) {
                focalLengthMm / sensorWidthMm * widthPx
            } else {
                FALLBACK_FOCAL_LENGTH_PX
            }

        val marginFraction = if (cropMarginFraction > 0f) cropMarginFraction else DEFAULT_CROP_MARGIN_FRACTION
        val cropMarginPx = minOf(widthPx, heightPx) * marginFraction

        val rows = heightPx.coerceAtLeast(1)

        return StabilizationSessionController.Config(
            cutoffHz = if (cutoffHz > 0f) cutoffHz else DEFAULT_CUTOFF_HZ,
            focalLengthPx = focalPx,
            cropMarginPixels = cropMarginPx,
            rows = rows,
            referenceRow = rows / 2,
            centerX = widthPx * 0.5f,
            centerY = heightPx * 0.5f,
        )
    }
}
