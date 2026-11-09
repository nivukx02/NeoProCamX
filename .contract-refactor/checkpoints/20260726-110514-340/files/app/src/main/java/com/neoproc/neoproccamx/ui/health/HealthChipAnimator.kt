package com.neoproc.neoproccamx.ui.health

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Owns the single looping [Animator] for one chip and switches it idempotently as the chip's state
 * changes: a slow amber pulse for WARNING, and a continuous glow + small shake for ERROR. Calling
 * [apply] with the same kind twice is a no-op, so it is safe to invoke on every render tick.
 *
 * The animator drives `alpha`/`translationX` only while a pulse/glow is active; for [HealthChipAnimation.NONE]
 * it restores the neutral transform and leaves `alpha` for the binder to set (so INACTIVE can dim).
 */
class HealthChipAnimator {
    private var currentKind: HealthChipAnimation? = null
    private var animator: Animator? = null

    fun apply(view: View, kind: HealthChipAnimation) {
        if (kind == currentKind) return
        animator?.cancel()
        animator = null
        view.translationX = 0f
        currentKind = kind
        when (kind) {
            HealthChipAnimation.NONE -> Unit
            HealthChipAnimation.SLOW_PULSE -> {
                animator = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.5f).apply {
                    duration = 900L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }
            }
            HealthChipAnimation.ERROR_GLOW_SHAKE -> {
                val density = view.resources.displayMetrics.density
                val shakePx = 2f * density
                val glow = ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0.55f).apply {
                    duration = 550L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                }
                val shake = ObjectAnimator.ofFloat(view, View.TRANSLATION_X, 0f, -shakePx, shakePx, 0f).apply {
                    duration = 320L
                    repeatCount = ValueAnimator.INFINITE
                }
                animator = AnimatorSet().apply {
                    interpolator = AccelerateDecelerateInterpolator()
                    playTogether(glow, shake)
                    start()
                }
            }
        }
    }

    /** Cancels any running animation and restores the neutral transform. */
    fun stop(view: View) {
        animator?.cancel()
        animator = null
        currentKind = null
        view.translationX = 0f
    }
}
