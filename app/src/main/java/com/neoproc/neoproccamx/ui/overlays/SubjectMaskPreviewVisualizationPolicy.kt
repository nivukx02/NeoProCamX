package com.neoproc.neoproccamx.ui.overlays

/**
 * Fail-closed policy for drawing an AI subject mask over the camera preview.
 *
 * Subject masks are production inputs for metering, focus and detail processing. They are not
 * camera image content. Rendering one is therefore an explicit diagnostic action and must never be
 * inferred from mask availability, a detail profile, or a high-quality runtime mode.
 */
internal object SubjectMaskPreviewVisualizationPolicy {
    const val DEFAULT_ENABLED: Boolean = false

    fun resolveLevel(
        visualizationEnabled: Boolean,
        cleanHudEnabled: Boolean,
        requestedLevel: Float
    ): Float {
        if (!visualizationEnabled || cleanHudEnabled || !requestedLevel.isFinite()) return 0f
        return requestedLevel.coerceIn(0f, 1f)
    }

    fun canAcceptMask(
        visualizationEnabled: Boolean,
        cleanHudEnabled: Boolean,
        width: Int,
        height: Int,
        payloadBytes: Int
    ): Boolean {
        if (!visualizationEnabled || cleanHudEnabled || width <= 0 || height <= 0) return false
        val requiredBytes = width.toLong() * height.toLong()
        return requiredBytes in 1L..Int.MAX_VALUE.toLong() && payloadBytes.toLong() >= requiredBytes
    }

    fun shouldDraw(
        visualizationEnabled: Boolean,
        cleanHudEnabled: Boolean,
        level: Float,
        bitmapPresent: Boolean
    ): Boolean = visualizationEnabled &&
        !cleanHudEnabled &&
        level.isFinite() &&
        level > 0f &&
        bitmapPresent
}
