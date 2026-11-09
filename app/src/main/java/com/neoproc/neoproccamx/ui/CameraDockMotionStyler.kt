package com.neoproc.neoproccamx.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.view.isVisible

/** Presentation-only entrance motion for the flat Home camera docks. */
internal object CameraDockMotionStyler {
    fun animateEntrance(root: View, docks: List<View>) {
        root.postOnAnimation {
            val easing = PathInterpolator(0.2f, 0f, 0f, 1f)
            docks.forEachIndexed { index, dock ->
                if (dock.visibility != View.VISIBLE) return@forEachIndexed
                dock.animate().cancel()
                dock.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                dock.alpha = 0f
                dock.scaleX = 0.97f
                dock.scaleY = 0.97f
                var entranceFinished = false
                fun finishEntrance(cancelled: Boolean) {
                    if (entranceFinished) return
                    entranceFinished = true
                    // ViewPropertyAnimator does not invoke withEndAction after cancel(). Always
                    // retire the temporary hardware layer and scale so a lifecycle/layout update
                    // cannot strand a dock at 0.97x. Translation remains exclusively layout-owned.
                    dock.setLayerType(View.LAYER_TYPE_NONE, null)
                    dock.scaleX = 1f
                    dock.scaleY = 1f
                    if (cancelled && dock.isVisible) dock.alpha = 1f
                    dock.animate().setListener(null)
                }
                dock.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setStartDelay(index * 65L)
                    .setDuration(360L)
                    .setInterpolator(easing)
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationCancel(animation: Animator) {
                            finishEntrance(cancelled = true)
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            finishEntrance(cancelled = false)
                        }
                    })
                    .start()
            }
        }
    }

}
