package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.utils.OrientationManager

internal object PreviewRotationPolicy {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun snapToNearestQuarterTurn(degrees: Int): Int {
        val normalized = OrientationManager.normalizeDegrees(degrees)
        val snapped = ((normalized + 45) / 90) * 90
        return if (snapped >= 360) 0 else snapped
    }

    @Suppress("UNUSED_PARAMETER")
    fun resolve(
        rotationDegrees: Int,
        sensorOrientation: Int,
        lensFacing: Int
    ): Int {
        val normalizedRotation = OrientationManager.normalizeDegrees(rotationDegrees)
        return if (strictHighEndEnabled()) {
            snapToNearestQuarterTurn(normalizedRotation)
        } else {
            normalizedRotation
        }
    }
}
