package com.neoproc.neoproccamx.snce

import kotlin.math.abs

/**
 * SNCE v2.0 HVC — per-frame push-constant block (directive Part 5.3 SNCE_FrameConstants).
 * Packs to 23 floats in the documented order for upload to the Vulkan colour pipeline.
 */
class SnceFrameConstants(
    val illuminantXyz: FloatArray,
    val illuminantCct: Float,
    val illuminantDuv: Float,
    val mixedLightFlag: Float,
    val illuminantSecondaryXyz: FloatArray,
    val sceneLuminanceLA: Float,
    val sceneLuminanceYb: Float,
    val fAdaptation: Float,
    val dAdaptation: Float,
    val fL: Float,
    val imuPitch: Float,
    val imuRoll: Float,
    val imuYaw: Float,
    val skyFractionPredicted: Float,
    val alphaAwb: Float,
    val temporalDePrev: Float,
    val sceneChangeScore: Float,
    val pqvgScorePrev: Float,
    val reprocessIteration: Int
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        illuminantXyz[0], illuminantXyz[1], illuminantXyz[2],
        illuminantCct, illuminantDuv, mixedLightFlag,
        illuminantSecondaryXyz[0], illuminantSecondaryXyz[1], illuminantSecondaryXyz[2],
        sceneLuminanceLA, sceneLuminanceYb, fAdaptation, dAdaptation, fL,
        imuPitch, imuRoll, imuYaw, skyFractionPredicted,
        alphaAwb, temporalDePrev, sceneChangeScore, pqvgScorePrev,
        reprocessIteration.toFloat()
    )

    /**
     * s2-f06 / CP41 — the std140 UBO image of this block: `vec4[6]` (24 floats, the 23 documented
     * fields packed contiguously with one trailing pad). This is the byte-exact layout the
     * colour-scene UBO would upload; a GLSL `layout(std140) uniform { vec4 sceneBlock[6]; }` mirrors
     * it field-for-field (vec4 elements are 16-byte aligned and contiguous, so float index == offset).
     * Defining and uploading that UBO + routing it to OCCS is the device-gated step (s2-f07 consumer).
     */
    fun std140Block(): FloatArray = toFloatArray().copyOf(STD140_FLOAT_COUNT)

    companion object {
        const val FLOAT_COUNT = 23

        /** std140 vec4[6] image size (23 fields + 1 trailing pad). */
        const val STD140_FLOAT_COUNT = 24

        /** Inverse of [toFloatArray] — loss-free unpack of the 23-float block. */
        fun fromFloatArray(a: FloatArray): SnceFrameConstants {
            require(a.size >= FLOAT_COUNT) { "scene block needs at least $FLOAT_COUNT floats" }
            return SnceFrameConstants(
                illuminantXyz = floatArrayOf(a[0], a[1], a[2]),
                illuminantCct = a[3], illuminantDuv = a[4], mixedLightFlag = a[5],
                illuminantSecondaryXyz = floatArrayOf(a[6], a[7], a[8]),
                sceneLuminanceLA = a[9], sceneLuminanceYb = a[10], fAdaptation = a[11],
                dAdaptation = a[12], fL = a[13], imuPitch = a[14], imuRoll = a[15], imuYaw = a[16],
                skyFractionPredicted = a[17], alphaAwb = a[18], temporalDePrev = a[19],
                sceneChangeScore = a[20], pqvgScorePrev = a[21], reprocessIteration = a[22].toInt()
            )
        }
    }
}

/**
 * SNCE v2.0 HVC — engine orchestrator (directive Part VI).
 *
 * Stateful per-session object that runs the per-frame analysis chain and exposes both:
 *  - [processFrame]: Stage 10 (IMU prior) -> Stage 01 (9-method illuminant, + CNN hook) ->
 *    Stage 09 (adaptive temporal EWMA) -> CAT16 viewing conditions -> [SnceFrameConstants]
 *    + AWB gains, ready to drive the GPU colour pipeline.
 *  - [processSampleRgb]: the per-pixel reference chain (Stages 02->06->07/11->12) used by
 *    tests and any CPU fallback — XYZ -> CAM16 forward -> appearance phenomena -> optional
 *    region profile -> CAM16 inverse -> working RGB.
 *
 * All learned models are reached through [registry]; with an empty registry the engine is
 * fully classical and self-contained.
 */
class SnceV2Engine(
    val registry: SnceModelRegistry = SnceModelRegistry(),
    private val rgbToXyz: FloatArray = SnceIlluminantEstimator.REC709_TO_XYZ
) {
    private val xyzToRgb = SnceColorPrimitives.invert3(rgbToXyz)
    private var previousSmoothedRgb: FloatArray? = null
    private var previousIlluminantXyz: FloatArray? = null
    private var previousScore = 100f
    // CP40-4A Track A — per-pixel luma of the previous thumbnail, for the Stage-09 scene-change score.
    private var previousThumbLuma: FloatArray? = null

    class FrameResult(
        val illuminant: SnceIlluminantEstimator.Result,
        val smoothedIlluminantRgb: FloatArray,
        val illuminantXyzNormalised: FloatArray,
        val viewingConditions: SnceCam16.SnceViewingConditions,
        val frameConstants: SnceFrameConstants,
        val awbGains: FloatArray,
        val alphaAwb: Float
    )

    fun processFrame(
        thumb: SnceThumbnail,
        sceneLuminanceLA: Float,
        imuPitchDeg: Float = 0f,
        imuRollDeg: Float = 0f,
        imuYawDeg: Float = 0f,
        backgroundYb: Float = 20f,
        surround: SnceCam16.Surround = SnceCam16.Surround.AVERAGE,
        sceneChangeScore: Float = 0f,
        reprocessIteration: Int = 0
    ): FrameResult {
        // Stage 09 (scene-change) — derive a real frame-to-frame change score from the thumbnail luma
        // (was hardwired 0, leaving the adaptive-alpha reset path dead). The caller may still pass an
        // explicit score; the effective value is the stronger of the two. Bounded: this only sets the
        // temporal AWB adaptation SPEED — the steady-state nudge stays ±MAX_WB_NUDGE clamped upstream.
        val currentThumbLuma = thumbLuma(thumb)
        val autoSceneChange = sceneChangeScoreFrom(previousThumbLuma, currentThumbLuma)
        previousThumbLuma = currentThumbLuma
        val effectiveSceneChange = maxOf(sceneChangeScore, autoSceneChange)

        // Stage 10 — orientation illuminant prior.
        val imuPrior = SnceOrientationColor.illuminantPrior(imuPitchDeg, rgbToXyz = rgbToXyz)
        val skyFraction = SnceOrientationColor.skyFraction(imuPitchDeg)

        // Stage 01 — nine-method fusion (Method 5 via the optional CNN hook).
        val illum = SnceIlluminantEstimator.estimate(
            thumb,
            temporalPrior = previousSmoothedRgb,
            imuPrior = imuPrior,
            cnnModel = registry.illuminantModel,
            config = SnceIlluminantEstimator.Config(rgbToXyz = rgbToXyz)
        )

        // Stage 09 — adaptive temporal EWMA on the illuminant estimate.
        val illChangeDe = previousIlluminantXyz?.let {
            SnceColorPrimitives.deltaE2000Xyz(it, illum.illuminantXyz)
        } ?: 0f
        val alpha = adaptiveAlpha(effectiveSceneChange, illChangeDe)
        val prev = previousSmoothedRgb
        val smoothed = if (prev == null) {
            illum.illuminantRgb.copyOf()
        } else {
            floatArrayOf(
                prev[0] * (1f - alpha) + illum.illuminantRgb[0] * alpha,
                1f,
                prev[2] * (1f - alpha) + illum.illuminantRgb[2] * alpha
            )
        }
        previousSmoothedRgb = smoothed
        previousIlluminantXyz = illum.illuminantXyz

        // Stage 03 — CAT16 viewing conditions; the adapting white is the scene illuminant.
        val illXyz = normaliseY(SnceColorPrimitives.mulV(rgbToXyz, smoothed))
        val vc = SnceCam16.SnceViewingConditions(
            whiteXyz = illXyz,
            adaptingLuminance = sceneLuminanceLA,
            backgroundLuminance = backgroundYb,
            surround = surround
        )

        val secondaryXyz = if (illum.mixedLight) {
            val xy = SnceColorPrimitives.kelvinToXy(illum.secondaryCct.coerceIn(2000f, 20000f))
            normaliseY(SnceColorPrimitives.xyToXyz(xy.first, xy.second))
        } else {
            floatArrayOf(0f, 0f, 0f)
        }

        val constants = SnceFrameConstants(
            illuminantXyz = illXyz,
            illuminantCct = illum.cct,
            illuminantDuv = illum.duv,
            mixedLightFlag = if (illum.mixedLight) 1f else 0f,
            illuminantSecondaryXyz = secondaryXyz,
            sceneLuminanceLA = sceneLuminanceLA,
            sceneLuminanceYb = backgroundYb,
            fAdaptation = surround.f,
            dAdaptation = vc.d,
            fL = vc.fl,
            imuPitch = SnceColorPrimitives.toRadians(imuPitchDeg),
            imuRoll = SnceColorPrimitives.toRadians(imuRollDeg),
            imuYaw = SnceColorPrimitives.toRadians(imuYawDeg),
            skyFractionPredicted = skyFraction,
            alphaAwb = alpha,
            temporalDePrev = illChangeDe,
            sceneChangeScore = effectiveSceneChange,
            pqvgScorePrev = previousScore,
            reprocessIteration = reprocessIteration
        )

        return FrameResult(illum, smoothed, illXyz, vc, constants, illum.gains, alpha)
    }

    /** Stage 09 adaptive alpha (directive 09.1). */
    fun adaptiveAlpha(sceneChangeScore: Float, illuminantChangeDe: Float): Float = when {
        sceneChangeScore > 0.5f -> 1.0f      // hard cut / scene transition -> instant reset
        illuminantChangeDe > 3.0f -> 0.40f   // real illuminant change -> fast adaptation
        sceneChangeScore > 0.2f -> 0.15f     // camera moving -> moderate
        else -> 0.02f                        // steady -> slow, flicker-free
    }

    /** Per-pixel reference chain on a single working-RGB sample. */
    fun processSampleRgb(
        rgb: FloatArray,
        vc: SnceCam16.SnceViewingConditions,
        sceneLuminanceLA: Float,
        semanticClass: SnceNeuralEnhancement.SemanticClass = SnceNeuralEnhancement.SemanticClass.OTHER
    ): FloatArray {
        val xyz = SnceColorPrimitives.mulV(rgbToXyz, rgb)
        var app = SnceCam16.forward(xyz, vc)
        app = SnceAppearancePhenomena.correctAppearance(app, sceneLuminanceLA)
        if (semanticClass != SnceNeuralEnhancement.SemanticClass.OTHER) {
            app = SnceNeuralEnhancement.applyProfile(app, semanticClass)
        }
        val xyzOut = SnceCam16.inverseFromJch(app.j, app.c, app.h, vc)
        return SnceColorPrimitives.mulV(xyzToRgb, xyzOut)
    }

    fun recordScore(score: Float) { previousScore = score.coerceIn(0f, 100f) }

    fun reset() {
        previousSmoothedRgb = null
        previousIlluminantXyz = null
        previousScore = 100f
        previousThumbLuma = null
    }

    /** Per-pixel Rec.709 luma of a linear-RGB thumbnail (length = width*height). */
    private fun thumbLuma(thumb: SnceThumbnail): FloatArray {
        val n = thumb.width * thumb.height
        val luma = FloatArray(n)
        val rgb = thumb.rgb
        var s = 0
        for (i in 0 until n) {
            luma[i] = 0.2126f * rgb[s] + 0.7152f * rgb[s + 1] + 0.0722f * rgb[s + 2]
            s += 3
        }
        return luma
    }

    /**
     * Stage-09 scene-change score in [0,1] from the mean absolute luma change between consecutive
     * thumbnails. 0 on the first frame or a size mismatch. [SCENE_CHANGE_GAIN] is deliberately
     * conservative (a hard cut saturates to 1; gentle panning stays in the "moderate" band) — owner-
     * tunable once validated on device. Pure/testable.
     */
    fun sceneChangeScoreFrom(previous: FloatArray?, current: FloatArray): Float {
        if (previous == null || previous.size != current.size || current.isEmpty()) return 0f
        var sum = 0f
        for (i in current.indices) sum += abs(current[i] - previous[i])
        val mad = sum / current.size
        return (mad * SCENE_CHANGE_GAIN).coerceIn(0f, 1f)
    }

    private fun normaliseY(xyz: FloatArray): FloatArray {
        val y = if (abs(xyz[1]) < SnceColorPrimitives.EPS) SnceColorPrimitives.EPS else xyz[1]
        return floatArrayOf(xyz[0] / y, 1f, xyz[2] / y)
    }

    companion object {
        /** Maps mean-abs linear-luma frame delta to a [0,1] scene-change score (conservative). */
        const val SCENE_CHANGE_GAIN = 4f
    }
}
