package com.neoproc.neoproccamx.ui

import kotlin.math.abs

internal object CameraModeRotarySwipePolicy {
    fun resolveStep(
        downX: Float,
        upX: Float,
        minimumDistancePx: Float
    ): Int {
        val deltaX = upX - downX
        if (abs(deltaX) < minimumDistancePx.coerceAtLeast(0f)) return 0
        return if (deltaX < 0f) 1 else -1
    }
}
