package com.neoproc.neoproccamx.rendering

internal data class NdrNivukxMeasuredCorrection(
    val active: Boolean,
    val redScale: Float,
    val greenScale: Float,
    val blueScale: Float,
    val highlightRolloffBias: Float,
    val skinHueCorrectionDegrees: Float,
    val skyHueCorrectionDegrees: Float
)

/**
 * Device-captured V2 chart correction from:
 * - artifacts/tone-chart-analysis-20260531-140223/tone_color_analysis_report.json
 * - artifacts/tone-chart-after-analysis-20260531-155816/tone_color_after_report.json
 *
 * The policy only carries low-order uniform corrections. Localized sky, skin,
 * and highlight hue guards stay in the shader so a chart correction cannot add
 * a global color cast across every mode.
 */
internal object NdrNivukxMeasuredCorrectionPolicy {
    private val NONE = NdrNivukxMeasuredCorrection(
        active = false,
        redScale = 1f,
        greenScale = 1f,
        blueScale = 1f,
        highlightRolloffBias = 0f,
        skinHueCorrectionDegrees = 0f,
        skyHueCorrectionDegrees = 0f
    )

    private val NDR10 = NdrNivukxMeasuredCorrection(
        active = true,
        redScale = 1f,
        greenScale = 1f,
        blueScale = 1f,
        highlightRolloffBias = 0.022000f,
        skinHueCorrectionDegrees = 2.567f,
        skyHueCorrectionDegrees = 6.999f
    )

    private val NIVUKX = NdrNivukxMeasuredCorrection(
        active = true,
        redScale = 0.974587f,
        greenScale = 0.991713f,
        blueScale = 0.991713f,
        highlightRolloffBias = 0.024000f,
        skinHueCorrectionDegrees = 2.493f,
        skyHueCorrectionDegrees = 8.000f
    )

    fun resolve(normalizedHdrMode: String?): NdrNivukxMeasuredCorrection =
        when (normalizedHdrMode?.uppercase()) {
            "NDR10+", "NDR10PLUS" -> NDR10
            "NIVUKX", "NIVUKXVISION", "NIVUKX VISION" -> NIVUKX
            else -> NONE
        }
}
