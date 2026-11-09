package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.NdrToneOperator
import kotlin.math.abs

/**
 * Allocation-free JNI packet sourced from [NdrToneOperator], the host-golden NDR authority.
 * Preview and recording receive this same packet at a mode boundary; no tone constants are selected
 * per frame and neither native renderer is allowed to invent its own profile values.
 */
internal data class NdrLiveProfile(
    val sensorDiffuseScene: Float,
    val signalGamma: Float,
    val toeWidth: Float,
    val toeAmount: Float,
    val highlightDesat: Float,
    val shadowMidtoneLift: Float,
    val midtoneContrast: Float,
    val chromaBoost: Float,
    val skyChromaBonus: Float,
    val localDetailStrength: Float,
    val localDetailLowerStops: Float,
    val localDetailUpperStops: Float
)

internal object NdrLiveProfilePolicy {
    /** Uniform ids are shared with [GpuImageEnhancementUniforms.detailProfileId]. */
    private const val FOCUSED_DETAIL_ID = 1f
    private const val NATURAL_DETAIL_ID = 2f
    private const val EXTREME_DETAIL_ID = 3f

    private data class DetailTuning(
        val strengthScale: Float,
        val rangeScale: Float
    )

    fun resolve(
        mode: NdrNivukxMeasuredMode,
        detailProfileId: Float = 0f
    ): NdrLiveProfile {
        val canonicalMode = when (mode) {
            NdrNivukxMeasuredMode.NIVUKX_VISION -> NdrToneOperator.Mode.NIVUKX_VISION
            NdrNivukxMeasuredMode.NDR10_PLUS,
            NdrNivukxMeasuredMode.OFF -> NdrToneOperator.Mode.NDR10_PLUS
        }
        val params = NdrToneOperator.paramsFor(canonicalMode)
        val detail = detailTuning(detailProfileId)
        return NdrLiveProfile(
            sensorDiffuseScene = NdrToneOperator.SENSOR_DIFFUSE_SCENE.toFloat(),
            signalGamma = params.signalGamma.toFloat(),
            toeWidth = params.toeWidth.toFloat(),
            toeAmount = params.toeAmount.toFloat(),
            highlightDesat = params.highlightDesat.toFloat(),
            shadowMidtoneLift = params.shadowMidtoneLift.toFloat(),
            midtoneContrast = params.midtoneContrast.toFloat(),
            chromaBoost = params.chromaBoost.toFloat(),
            skyChromaBonus = params.skyChromaBonus.toFloat(),
            localDetailStrength = (params.localDetailStrength.toFloat() * detail.strengthScale)
                .coerceIn(0f, 0.75f),
            localDetailLowerStops = (params.localDetailLowerStops.toFloat() * detail.rangeScale)
                .coerceIn(-0.20f, 0f),
            localDetailUpperStops = (params.localDetailUpperStops.toFloat() * detail.rangeScale)
                .coerceIn(0f, 0.20f)
        )
    }

    /**
     * Detail profiles alter only the bounded, noise/halo-guarded local-detail window. Tone, colour,
     * diffuse-white and PQ identity remain bit-identical across profiles.
     */
    private fun detailTuning(profileId: Float): DetailTuning = when {
        profileId.isFinite() && abs(profileId - NATURAL_DETAIL_ID) < 0.01f ->
            DetailTuning(strengthScale = 0.80f, rangeScale = 0.85f)
        profileId.isFinite() && abs(profileId - EXTREME_DETAIL_ID) < 0.01f ->
            DetailTuning(strengthScale = 1.12f, rangeScale = 1.08f)
        profileId.isFinite() && abs(profileId - FOCUSED_DETAIL_ID) < 0.01f ->
            DetailTuning(strengthScale = 1.00f, rangeScale = 1.00f)
        else -> DetailTuning(strengthScale = 1.00f, rangeScale = 1.00f)
    }
}
