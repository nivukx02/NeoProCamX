package com.neoproc.neoproccamx.rendering

internal enum class NdrNivukxMeasuredMode(val nativeCode: Int) {
    OFF(0),
    NDR10_PLUS(1),
    NIVUKX_VISION(2)
}

internal data class NdrNivukxMeasuredCorrection(
    val mode: NdrNivukxMeasuredMode,
    val active: Boolean,
    val redScale: Float,
    val greenScale: Float,
    val blueScale: Float,
    val highlightRolloffBias: Float,
    val shadowLiftBias: Float,
    val saturationScale: Float,
    val skinHueCorrectionDegrees: Float,
    val skyHueCorrectionDegrees: Float,
    val calibrationConfidence: Float
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
    private const val MIN_CALIBRATION_CONFIDENCE = 0.55f

    private val NONE = NdrNivukxMeasuredCorrection(
        mode = NdrNivukxMeasuredMode.OFF,
        active = false,
        redScale = 1f,
        greenScale = 1f,
        blueScale = 1f,
        highlightRolloffBias = 0f,
        shadowLiftBias = 0f,
        saturationScale = 1f,
        skinHueCorrectionDegrees = 0f,
        skyHueCorrectionDegrees = 0f,
        calibrationConfidence = 0f
    )

    private val NDR10 = NdrNivukxMeasuredCorrection(
        mode = NdrNivukxMeasuredMode.NDR10_PLUS,
        active = true,
        redScale = 1f,
        greenScale = 1f,
        blueScale = 1f,
        highlightRolloffBias = 0.034662f,
        // Offline simulator evidence already shows a lifted shadow response. Do not add an
        // unmeasured positive lift; retain the scene-linear black point and let the NDR toe operate.
        shadowLiftBias = 0f,
        saturationScale = 1f,
        skinHueCorrectionDegrees = 2.567f,
        skyHueCorrectionDegrees = 6.999f,
        calibrationConfidence = 1f
    )

    private val NIVUKX = NdrNivukxMeasuredCorrection(
        mode = NdrNivukxMeasuredMode.NIVUKX_VISION,
        active = true,
        redScale = 0.974587f,
        greenScale = 0.991713f,
        blueScale = 0.991713f,
        highlightRolloffBias = 0.050035f,
        shadowLiftBias = 0f,
        saturationScale = 1f,
        skinHueCorrectionDegrees = 2.493f,
        skyHueCorrectionDegrees = 8.000f,
        calibrationConfidence = 1f
    )

    fun resolve(normalizedHdrMode: String?): NdrNivukxMeasuredCorrection =
        confidenceGate(when (normalizedHdrMode?.uppercase()) {
            "NDR10+", "NDR10PLUS" -> NDR10
            "NIVUKX", "NIVUKXVISION", "NIVUKX VISION" -> NIVUKX
            else -> NONE
        })

    /**
     * Converts a measured profile into the single effective packet consumed by every GPU/monitor path.
     * Low-confidence calibration never reaches pixels; accepted profiles are smoothly authority-scaled
     * toward identity and clamped before crossing JNI. This is deliberately the only confidence gate so
     * preview and recording cannot apply different strengths.
     */
    internal fun confidenceGate(
        candidate: NdrNivukxMeasuredCorrection
    ): NdrNivukxMeasuredCorrection {
        val confidence = candidate.calibrationConfidence
            .takeIf { it.isFinite() }
            ?.coerceIn(0f, 1f)
            ?: 0f
        if (!candidate.active || candidate.mode == NdrNivukxMeasuredMode.OFF) {
            return NONE.copy(calibrationConfidence = confidence)
        }
        if (confidence < MIN_CALIBRATION_CONFIDENCE) {
            // Calibration confidence controls only the optional correction coefficients.  The selected
            // NDR/NIVUKX mode remains authoritative so a rejected measurement cannot silently turn the
            // RAW monitor back into the ordinary neutral/SDR operator.
            return NONE.copy(
                mode = candidate.mode,
                calibrationConfidence = confidence
            )
        }

        val authority = ((confidence - MIN_CALIBRATION_CONFIDENCE) /
            (1f - MIN_CALIBRATION_CONFIDENCE)).coerceIn(0f, 1f)
        fun finite(value: Float, fallback: Float): Float =
            if (value.isFinite()) value else fallback
        fun fromIdentity(value: Float): Float = 1f + (value - 1f) * authority

        return candidate.copy(
            active = true,
            redScale = fromIdentity(finite(candidate.redScale, 1f).coerceIn(0.85f, 1.15f)),
            greenScale = fromIdentity(finite(candidate.greenScale, 1f).coerceIn(0.85f, 1.15f)),
            blueScale = fromIdentity(finite(candidate.blueScale, 1f).coerceIn(0.85f, 1.15f)),
            highlightRolloffBias = finite(candidate.highlightRolloffBias, 0f)
                .coerceIn(0f, 0.12f) * authority,
            shadowLiftBias = finite(candidate.shadowLiftBias, 0f)
                .coerceIn(-0.08f, 0.08f) * authority,
            saturationScale = fromIdentity(
                finite(candidate.saturationScale, 1f).coerceIn(0.85f, 1.15f)
            ),
            skinHueCorrectionDegrees = finite(candidate.skinHueCorrectionDegrees, 0f)
                .coerceIn(-12f, 12f) * authority,
            skyHueCorrectionDegrees = finite(candidate.skyHueCorrectionDegrees, 0f)
                .coerceIn(-12f, 12f) * authority,
            calibrationConfidence = confidence
        )
    }
}
