package com.neoproc.neoproccamx.ui.health

import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import androidx.core.graphics.toColorInt
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.monitor.ModuleHealthStatus

/**
 * Binds one [ModuleHealthStatus] to one chip [TextView]: colours the label + outline, sets the state
 * glyph (● healthy/inactive, ⚠ warning/error), drives the [HealthChipAnimator], and dims when INACTIVE.
 *
 * Diff-guarded — a repeated identical visual is a no-op — so it is cheap to call on every ~150 ms tick
 * without redundant layout/allocation, matching the app's `CameraUiStateDiffPolicy` discipline.
 */
class HealthChipBinder(private val chip: TextView) {

    private val animator = HealthChipAnimator()
    private var lastVisual: HealthChipVisual? = null
    private val background: GradientDrawable

    init {
        val density = chip.resources.displayMetrics.density
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * density
            setColor("#33000000".toColorInt())
            setStroke((1.2f * density).toInt(), HealthChipVisualPolicy.COLOR_INACTIVE)
        }
        chip.background = background
    }

    fun bind(status: ModuleHealthStatus) {
        val v = HealthChipVisualPolicy.visual(status)
        if (v == lastVisual) return
        lastVisual = v

        chip.text = chip.context.getString(
            R.string.health_chip_format,
            if (v.showWarningIcon) "⚠" else "●",
            v.label
        )
        chip.setTextColor(v.textArgb)
        background.setStroke((1.2f * chip.resources.displayMetrics.density).toInt(), v.outlineArgb)

        animator.apply(chip, v.animation)
        if (v.animation == HealthChipAnimation.NONE) {
            chip.alpha = v.alpha
        }
    }

    /**
     * Stops the looping animation and forces a full re-bind on the next [bind] (clears the diff cache)
     * so the animation restarts when the chip returns to view. Call on stop/teardown.
     */
    fun stop() {
        animator.stop(chip)
        lastVisual = null
    }
}
