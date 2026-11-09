package com.neoproc.neoproccamx.ui.health

import com.neoproc.neoproccamx.monitor.HealthState
import com.neoproc.neoproccamx.monitor.ModuleHealthStatus

/** Which looping animation a chip should run. */
enum class HealthChipAnimation {
    NONE,
    /** Warning: slow amber pulse. */
    SLOW_PULSE,
    /** Error: continuous glow + small shake. */
    ERROR_GLOW_SHAKE
}

/**
 * The fully-resolved visual treatment for one chip. Plain data (ARGB ints, flags) so the mapping from
 * [HealthState] to the spec's colour/animation/icon rules is unit-testable without Android views.
 */
data class HealthChipVisual(
    val label: String,
    val textArgb: Int,
    val outlineArgb: Int,
    val alpha: Float,
    val showWarningIcon: Boolean,
    val animation: HealthChipAnimation
)

/**
 * Maps a [ModuleHealthStatus] to its chip appearance, per the spec's per-state display rules:
 *
 *  - ACTIVE   -> vivid green, no animation, no icon (verified live work).
 *  - READY    -> teal, no animation, no icon (armed, idle).
 *  - HEALTHY  -> green text + green outline, no animation, no icon.
 *  - UNKNOWN  -> neutral slate, no animation, no icon (indeterminate / verification pending).
 *  - WARNING  -> amber, slow pulse, warning icon.
 *  - ERROR    -> red, continuous glow + small shake, warning icon.
 *  - INACTIVE -> gray, low opacity, no animation.
 *  - UNSUPPORTED -> dim gray, low opacity, no animation (capability genuinely absent, not a fault).
 *
 * A recovering ERROR (an in-flight non-destructive recovery attempt) is shown distinctly — a blue
 * "recovering" tint with the pulse animation — so the Error -> Recovering -> Healthy progression reads
 * clearly, while the underlying [HealthState] stays one of the canonical states.
 */
object HealthChipVisualPolicy {

    const val COLOR_HEALTHY: Int = 0xFF3DDC84.toInt()
    const val COLOR_ACTIVE: Int = 0xFF00E676.toInt()
    const val COLOR_READY: Int = 0xFF4EC9B0.toInt()
    const val COLOR_UNKNOWN: Int = 0xFF8A8F98.toInt()
    const val COLOR_WARNING: Int = 0xFFF6B23C.toInt()
    const val COLOR_ERROR: Int = 0xFFFF4D4F.toInt()
    const val COLOR_INACTIVE: Int = 0xFF9AA0A6.toInt()
    const val COLOR_UNSUPPORTED: Int = 0xFF6E7379.toInt()
    const val COLOR_RECOVERING: Int = 0xFF4EA1FF.toInt()

    const val ALPHA_ACTIVE: Float = 1.0f
    const val ALPHA_INACTIVE: Float = 0.45f

    fun visual(status: ModuleHealthStatus): HealthChipVisual {
        val label = status.module.shortLabel
        val recovering = status.recovering && status.state == HealthState.ERROR
        return when (status.state) {
            HealthState.ACTIVE -> HealthChipVisual(
                label = label,
                textArgb = COLOR_ACTIVE,
                outlineArgb = COLOR_ACTIVE,
                alpha = ALPHA_ACTIVE,
                showWarningIcon = false,
                animation = HealthChipAnimation.NONE
            )
            HealthState.READY -> HealthChipVisual(
                label = label,
                textArgb = COLOR_READY,
                outlineArgb = COLOR_READY,
                alpha = ALPHA_ACTIVE,
                showWarningIcon = false,
                animation = HealthChipAnimation.NONE
            )
            HealthState.HEALTHY -> HealthChipVisual(
                label = label,
                textArgb = COLOR_HEALTHY,
                outlineArgb = COLOR_HEALTHY,
                alpha = ALPHA_ACTIVE,
                showWarningIcon = false,
                animation = HealthChipAnimation.NONE
            )
            HealthState.UNKNOWN -> HealthChipVisual(
                label = label,
                textArgb = COLOR_UNKNOWN,
                outlineArgb = COLOR_UNKNOWN,
                alpha = ALPHA_ACTIVE,
                showWarningIcon = false,
                animation = HealthChipAnimation.NONE
            )
            HealthState.UNSUPPORTED -> HealthChipVisual(
                label = label,
                textArgb = COLOR_UNSUPPORTED,
                outlineArgb = COLOR_UNSUPPORTED,
                alpha = ALPHA_INACTIVE,
                showWarningIcon = false,
                animation = HealthChipAnimation.NONE
            )
            HealthState.WARNING -> HealthChipVisual(
                label = label,
                textArgb = COLOR_WARNING,
                outlineArgb = COLOR_WARNING,
                alpha = ALPHA_ACTIVE,
                showWarningIcon = true,
                animation = HealthChipAnimation.SLOW_PULSE
            )
            HealthState.ERROR -> if (recovering) {
                HealthChipVisual(
                    label = label,
                    textArgb = COLOR_RECOVERING,
                    outlineArgb = COLOR_RECOVERING,
                    alpha = ALPHA_ACTIVE,
                    showWarningIcon = true,
                    animation = HealthChipAnimation.SLOW_PULSE
                )
            } else {
                HealthChipVisual(
                    label = label,
                    textArgb = COLOR_ERROR,
                    outlineArgb = COLOR_ERROR,
                    alpha = ALPHA_ACTIVE,
                    showWarningIcon = true,
                    animation = HealthChipAnimation.ERROR_GLOW_SHAKE
                )
            }
            HealthState.INACTIVE -> HealthChipVisual(
                label = label,
                textArgb = COLOR_INACTIVE,
                outlineArgb = COLOR_INACTIVE,
                alpha = ALPHA_INACTIVE,
                showWarningIcon = false,
                animation = HealthChipAnimation.NONE
            )
        }
    }
}
