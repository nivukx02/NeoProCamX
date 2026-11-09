package com.neoproc.neoproccamx.ui

internal data class CameraModeRailItemMotion(
    val startScale: Float,
    val endScale: Float,
    val startAlpha: Float,
    val endAlpha: Float,
    val durationMs: Long
)

internal data class CameraModeRailTransitionMotion(
    val direction: Int,
    val contentOffsetDp: Float,
    val startAlpha: Float,
    val endAlpha: Float,
    val durationMs: Long
)

internal object CameraModeRailMotionPolicy {
    fun selectedItemMotion(): CameraModeRailItemMotion =
        CameraModeRailItemMotion(
            startScale = 0.92f,
            endScale = 1.08f,
            startAlpha = 0.66f,
            endAlpha = 1.0f,
            durationMs = 170L
        )

    fun idleItemAlpha(): Float = 0.76f

    fun transitionMotion(
        previous: CameraModePreset,
        next: CameraModePreset,
        order: List<CameraModePreset>
    ): CameraModeRailTransitionMotion {
        val previousIndex = order.indexOf(previous)
        val nextIndex = order.indexOf(next)
        val direction = if (previousIndex >= 0 && nextIndex >= 0 && order.isNotEmpty()) {
            when {
                previousIndex == order.lastIndex && nextIndex == 0 -> 1
                previousIndex == 0 && nextIndex == order.lastIndex -> -1
                nextIndex > previousIndex -> 1
                nextIndex < previousIndex -> -1
                else -> 0
            }
        } else {
            0
        }
        return CameraModeRailTransitionMotion(
            direction = direction,
            contentOffsetDp = 44f,
            startAlpha = 0.28f,
            endAlpha = 1.0f,
            durationMs = 235L
        )
    }
}
