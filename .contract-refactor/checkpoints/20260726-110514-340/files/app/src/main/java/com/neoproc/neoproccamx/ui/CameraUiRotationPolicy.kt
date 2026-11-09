package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.utils.OrientationManager

internal object CameraUiRotationPolicy {
    fun controlRotationDegrees(deviceRotationDegrees: Int): Float =
        when (OrientationManager.normalizeDegrees(deviceRotationDegrees)) {
            90 -> -90f
            180 -> 180f
            270 -> 90f
            else -> 0f
        }
}
