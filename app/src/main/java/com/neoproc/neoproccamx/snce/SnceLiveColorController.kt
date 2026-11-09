package com.neoproc.neoproccamx.snce

import java.nio.ByteBuffer

import kotlin.math.abs

/** Immutable SNCE authority consumed by one preview, encoder, and P010 render packet. */
data class SnceLiveColorSnapshot(
    val enabled: Boolean,
    val redScale: Float,
    val blueScale: Float,
    val saturationScale: Float,
    val confidence: Float
) {
    fun injectIntoPreviewGrade(uniforms: FloatArray) {
        if (!enabled || uniforms.size < 16) return
        uniforms[4] = sanitize(uniforms[4] * redScale, uniforms[4])
        uniforms[6] = sanitize(uniforms[6] * blueScale, uniforms[6])
        uniforms[1] = sanitize(uniforms[1] * saturationScale, uniforms[1])
    }

    fun p010SaturationMul(): Float = if (enabled) saturationScale else 1f

    fun p010WarmthBias(): Float =
        if (enabled) (redScale - blueScale).coerceIn(-0.1f, 0.1f) else 0f

    fun currentNudge(): FloatArray = floatArrayOf(redScale, blueScale, saturationScale)

    private fun sanitize(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback
}

/**
 * Live integration of the SNCE v2.0 HVC engine into the camera preview/encoder colour grade.
 *
 * Design = bounded + fail-safe by construction:
 *  - [analyze] runs the full [SnceV2Engine] on a small CPU thumbnail off the render thread.
 *    It NEVER throws to the caller; any failure leaves the previous nudge intact.
 *  - It produces only three small, hard-clamped multipliers applied to the 16-float preview
 *    grade: red/blue WB scale (slots 4/6) as a residual-cast correction, and saturation
 *    (slot 1) from the Hunt low-light colourfulness effect.
 *  - [injectIntoPreviewGrade] re-sanitises every write, and the Vulkan bridge re-clamps the
 *    final values, so an out-of-range engine output can neither crash nor blow out colour.
 *
 * Because the analysis stream is the camera's own YUV (not the rendered output), there is no
 * feedback loop; SNCE refines residual cast and low-light colourfulness on top of camera AWB.
 */
class SnceLiveColorController(
    private val engine: SnceV2Engine = SnceV2Engine(),
    /**
     * s2-f05 / CP47: when true, the nudge magnitude is scaled DOWN by the illuminant-fusion
     * confidence (low confidence ⇒ smaller nudge). Default false ⇒ today's behavior bit-for-bit
     * (the ±0.05/±0.06 ceiling is NEVER widened, only ever reduced).
     */
    private val confidenceGatingEnabled: Boolean = false
) {
    @Volatile private var redScaleMul = 1f
    @Volatile private var blueScaleMul = 1f
    @Volatile private var satMul = 1f
    // s7-f04/CP4A: last computed additive region WB nudge (default 0 => no effect; gated OFF).
    @Volatile private var regionWbR = 0f
    @Volatile private var regionWbB = 0f
    /** Last frame's illuminant-fusion confidence in [0,1]; exposed for the s2-f04 drift ceiling. */
    @Volatile private var lastConfidence = 1f

    /** Analyse one YUV_420 analysis frame (preview/live path). */
    fun analyze(
        yuv: SnceYuvThumbnail.Frame,
        estimatedLux: Float,
        imuPitchDeg: Float = 0f
    ): Boolean {
        if (!SnceColorEngineFlag.enabled) return false
        return runEngine(SnceYuvThumbnail.fromYuv(yuv, THUMB_SIZE), estimatedLux, imuPitchDeg)
    }

    /** Analyse one packed P010 frame (NPV10 offline export path). */
    fun analyzeP010(payload: ByteArray, width: Int, height: Int, estimatedLux: Float = 80f): Boolean {
        if (!SnceColorEngineFlag.enabled) return false
        return runEngine(SnceYuvThumbnail.fromP010Payload(payload, width, height, THUMB_SIZE), estimatedLux, 0f)
    }

    fun analyzeP010(payload: ByteBuffer, width: Int, height: Int, estimatedLux: Float = 80f): Boolean {
        if (!SnceColorEngineFlag.enabled) return false
        return runEngine(
            SnceYuvThumbnail.fromP010Payload(payload, width, height, THUMB_SIZE),
            estimatedLux,
            0f
        )
    }

    /**
     * s7-f04 / CP4A — compute the bounded per-region WB nudge from a segmentation mask and store it
     * for [injectIntoPreviewGrade] to fold in. Caller-driven (NOT part of [analyze]) so the YUV
     * analysis path is byte-identical when the feature is off. Stores 0/0 (no-op) unless BOTH the
     * SNCE engine flag AND the CP4A flag are enabled. Never throws (fail-safe, matches runEngine).
     */
    fun applyRegionConstancy(mask: SnceSegmentationResult?, thumb: SnceThumbnail) {
        if (!SnceColorEngineFlag.enabled || !SnceSemanticConstancyFlag.enabled) {
            synchronized(this) {
                regionWbR = 0f
                regionWbB = 0f
            }
            return
        }
        try {
            val correction = SnceSemanticColorConstancy.regionConstancyNudge(mask, thumb, gateEnabled = true)
            synchronized(this) {
                regionWbR = correction.wbR
                regionWbB = correction.wbB
            }
        } catch (t: Throwable) {
            synchronized(this) {
                regionWbR = 0f
                regionWbB = 0f
            }
        }
    }

    /** Shared engine run; returns true if the nudge changed meaningfully. Never throws. */
    private fun runEngine(thumb: SnceThumbnail?, estimatedLux: Float, imuPitchDeg: Float): Boolean {
        return try {
            if (thumb == null) return false
            val sceneLa = (estimatedLux / Math.PI.toFloat()).coerceIn(1f, 1000f)
            val result = engine.processFrame(thumb, sceneLuminanceLA = sceneLa, imuPitchDeg = imuPitchDeg)

            // s2-f05 / CP47: confidence-gated nudge. g(full)=1 ⇒ today; g<1 shrinks the deviation
            // from unity (never widens). Default gate off ⇒ g=1 ⇒ bit-for-bit today.
            val confidence = result.illuminant.confidence.coerceIn(0f, 1f)
            val g = if (confidenceGatingEnabled) confidence else 1f
            val newRed = scaleTowardUnity(boundedGain(result.awbGains[0]), g)
            val newBlue = scaleTowardUnity(boundedGain(result.awbGains[2]), g)
            val hunt = SnceAppearancePhenomena.huntChromaMultiplier(sceneLa)
            val newSat = scaleTowardUnity(hunt.coerceIn(1f - MAX_SAT_NUDGE, 1f + MAX_SAT_NUDGE), g)

            synchronized(this) {
                val changed = abs(newRed - redScaleMul) > CHANGE_EPSILON ||
                    abs(newBlue - blueScaleMul) > CHANGE_EPSILON ||
                    abs(newSat - satMul) > CHANGE_EPSILON
                redScaleMul = newRed
                blueScaleMul = newBlue
                satMul = newSat
                lastConfidence = confidence
                changed
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** Apply the stored bounded nudge to a 16-float preview/encoder grade, in place. Fail-safe. */
    fun injectIntoPreviewGrade(uniforms: FloatArray) {
        snapshot().injectIntoPreviewGrade(uniforms)
    }

    fun reset() {
        synchronized(this) {
            redScaleMul = 1f
            blueScaleMul = 1f
            satMul = 1f
            regionWbR = 0f
            regionWbB = 0f
            lastConfidence = 1f
            engine.reset()
        }
    }

    /** Current nudge (red, blue, saturation) — for diagnostics/tests. */
    fun currentNudge(): FloatArray = snapshot().currentNudge()

    /**
     * Last frame's illuminant-fusion confidence in [0,1] (s2-f10). Exposed so the ColorDriftGovernor
     * (s2-f04 / CP47) can tighten its per-frame WB ceiling when the estimate is unreliable. 1.0
     * (full) until a frame has been analysed, so a consumer that ignores it sees today's behavior.
     */
    fun currentConfidence(): Float = snapshot().confidence

    /**
     * Saturation multiplier for the P010 record/export chroma path (slot 11). 1.0 when neutral
     * or disabled, so the export shader is a no-op until SNCE is active.
     */
    fun p010SaturationMul(): Float = snapshot().p010SaturationMul()

    /**
     * Warmth bias for the P010 chroma path (slot 9). 0 when neutral/disabled; derived from the
     * residual-WB red/blue nudge so recorded pixels get the same cooling/warming as the preview.
     */
    fun p010WarmthBias(): Float = snapshot().p010WarmthBias()

    /** Captures independently-updated analysis values as one immutable render authority. */
    fun snapshot(): SnceLiveColorSnapshot = synchronized(this) {
        val enabled = SnceColorEngineFlag.enabled
        val semanticConstancyEnabled = enabled && SnceSemanticConstancyFlag.enabled
        val resolvedRed = if (semanticConstancyEnabled) {
            (redScaleMul + regionWbR).coerceIn(1f - MAX_WB_NUDGE, 1f + MAX_WB_NUDGE)
        } else {
            redScaleMul
        }
        val resolvedBlue = if (semanticConstancyEnabled) {
            (blueScaleMul + regionWbB).coerceIn(1f - MAX_WB_NUDGE, 1f + MAX_WB_NUDGE)
        } else {
            blueScaleMul
        }
        SnceLiveColorSnapshot(
            enabled = enabled,
            redScale = resolvedRed,
            blueScale = resolvedBlue,
            saturationScale = satMul,
            confidence = lastConfidence
        )
    }

    private fun boundedGain(gain: Float): Float {
        if (!gain.isFinite()) return 1f
        return gain.coerceIn(1f - MAX_WB_NUDGE, 1f + MAX_WB_NUDGE)
    }

    /** Scale a multiplier's deviation from unity by [gain] in [0,1]; gain=1 is identity, gain<1
     *  shrinks toward 1.0 (a smaller nudge). Never widens beyond the input's deviation. */
    private fun scaleTowardUnity(value: Float, gain: Float): Float = 1f + gain * (value - 1f)

    companion object {
        const val THUMB_SIZE = 32
        // Hard fail-safe ceilings on how much SNCE may move the grade per refresh.
        const val MAX_WB_NUDGE = 0.05f
        const val MAX_SAT_NUDGE = 0.06f
        private const val CHANGE_EPSILON = 0.004f
    }
}
