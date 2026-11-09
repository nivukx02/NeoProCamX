package com.neoproc.neoproccamx.rendering

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import kotlin.math.abs

internal data class PreviewLayerPaintSignature(
    val contrast: Float,
    val saturation: Float,
    val redScale: Float,
    val greenScale: Float,
    val blueScale: Float,
    val redBias: Float,
    val greenBias: Float,
    val blueBias: Float,
    val lutEnabled: Boolean,
    val yuvInputBound: Boolean,
    val nativeCompositorActive: Boolean
)

internal data class PreviewLayerPaintUpdate(
    val changed: Boolean,
    val signature: PreviewLayerPaintSignature
)

internal object PreviewLayerPaintController {
    private const val NEUTRAL_GRADE_EPSILON = 0.0001f
    private const val STRICT_NEUTRAL_GRADE_EPSILON = 0.00005f

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun neutralGradeEpsilon(): Float =
        if (strictHighEndEnabled()) STRICT_NEUTRAL_GRADE_EPSILON else NEUTRAL_GRADE_EPSILON

    fun update(
        paint: Paint,
        lastSignature: PreviewLayerPaintSignature?,
        profile: SceneRenderProfile,
        highLoadPreviewMode: Boolean,
        lutEnabled: Boolean,
        lutSummary: PreviewRenderTuning.LutSummary?,
        yuvInputBound: Boolean,
        nativeCompositorActive: Boolean,
        applyPaint: (Paint) -> Unit
    ): PreviewLayerPaintUpdate {
        val baseAdjustments = PreviewRenderTuning.buildGradeAdjustments(
            profile = profile,
            highLoadPreviewMode = highLoadPreviewMode,
            lutEnabled = lutEnabled,
            lutSummary = lutSummary
        )
        val adjustments = if (nativeCompositorActive) {
            baseAdjustments
        } else {
            // Direct camera-surface fallback cannot faithfully display the full HDR/LOG/LUT
            // grade stack. Keep the fallback preview visually truthful instead of tinting it.
            PreviewRenderTuning.stabilizeForDirectPreviewFallback(baseAdjustments)
        }
        val effectiveLutEnabled = lutEnabled && nativeCompositorActive
        val signature = PreviewLayerPaintSignature(
            contrast = adjustments.contrast,
            saturation = adjustments.saturation,
            redScale = adjustments.redScale,
            greenScale = adjustments.greenScale,
            blueScale = adjustments.blueScale,
            redBias = adjustments.redBias,
            greenBias = adjustments.greenBias,
            blueBias = adjustments.blueBias,
            lutEnabled = effectiveLutEnabled,
            yuvInputBound = yuvInputBound,
            nativeCompositorActive = nativeCompositorActive
        )
        if (signature == lastSignature) {
            return PreviewLayerPaintUpdate(changed = false, signature = signature)
        }

        if (nativeCompositorActive || isNeutralPaintPass(adjustments, effectiveLutEnabled, yuvInputBound)) {
            paint.colorFilter = null
            applyPaint(paint)
            return PreviewLayerPaintUpdate(changed = true, signature = signature)
        }

        val saturationMatrix = ColorMatrix().apply { setSaturation(adjustments.saturation) }
        val translate = 128f * (1f - adjustments.contrast)
        val toneMatrix = ColorMatrix(
            floatArrayOf(
                adjustments.redScale * adjustments.contrast,
                0f,
                0f,
                0f,
                translate + adjustments.redBias,
                0f,
                adjustments.greenScale * adjustments.contrast,
                0f,
                0f,
                translate + adjustments.greenBias,
                0f,
                0f,
                adjustments.blueScale * adjustments.contrast,
                0f,
                translate + adjustments.blueBias,
                0f,
                0f,
                0f,
                1f,
                0f
            )
        )
        saturationMatrix.postConcat(toneMatrix)
        paint.colorFilter = ColorMatrixColorFilter(saturationMatrix)
        applyPaint(paint)
        return PreviewLayerPaintUpdate(changed = true, signature = signature)
    }

    private fun isNeutralPaintPass(
        adjustments: PreviewRenderTuning.GradeAdjustments,
        lutEnabled: Boolean,
        yuvInputBound: Boolean
    ): Boolean {
        val epsilon = neutralGradeEpsilon()
        return !lutEnabled &&
            !yuvInputBound &&
            abs(adjustments.contrast - 1f) < epsilon &&
            abs(adjustments.saturation - 1f) < epsilon &&
            abs(adjustments.redScale - 1f) < epsilon &&
            abs(adjustments.greenScale - 1f) < epsilon &&
            abs(adjustments.blueScale - 1f) < epsilon &&
            abs(adjustments.redBias) < epsilon &&
            abs(adjustments.greenBias) < epsilon &&
            abs(adjustments.blueBias) < epsilon
    }
}
