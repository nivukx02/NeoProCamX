package com.neoproc.neoproccamx.ui.overlays

internal data class FocusOverlayAnimationState(
    val scale: Float,
    val alpha: Float
)

internal object FocusOverlayAnimationPolicy {
    const val DURATION_MS = 220L

    fun resolve(progress: Float): FocusOverlayAnimationState {
        val t = progress.coerceIn(0f, 1f)
        return FocusOverlayAnimationState(
            scale = 1.18f - 0.18f * t,
            alpha = 0.18f + 0.82f * t
        )
    }
}
