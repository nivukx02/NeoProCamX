package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.AcesColorEngine
import com.neoproc.neoproccamx.color.NeoGamutPipeline
import java.util.Locale

internal object VulkanColorScienceUniformPolicy {
    fun resolve(
        enabled: Boolean,
        hdrMappingEnabled: Boolean,
        logDeliveryEnabled: Boolean,
        nivukxVision: Boolean,
        hdrKnee: Float,
        hdrShoulder: Float,
        volumeCompression: Float,
        colorSpaceName: String? = null,
        colorScienceName: String? = null,
        measuredCorrection: NdrNivukxMeasuredCorrection = NdrNivukxMeasuredCorrectionPolicy.resolve("OFF")
    ): VulkanColorScienceUniforms {
        if (!enabled) return VulkanColorScienceUniforms.disabled()

        val normalized = GpuColorProfileNormalizer.normalize(colorSpaceName, colorScienceName)
        val pipeline = resolveNeoGamutPipeline(colorSpaceName, colorScienceName, normalized)
        val effectiveNivukxVision =
            nivukxVision || normalized.colorScience == GpuColorScience.NIVUKX_VISION
        val effectiveMeasuredCorrection = when {
            measuredCorrection.active -> measuredCorrection
            normalized.colorScience == GpuColorScience.NDR10_PLUS ->
                NdrNivukxMeasuredCorrectionPolicy.resolve("NDR10+")
            normalized.colorScience == GpuColorScience.NIVUKX_VISION ->
                NdrNivukxMeasuredCorrectionPolicy.resolve("NIVUKXVISION")
            else -> measuredCorrection
        }
        val neoAcesHighEnd = normalized.colorScience == GpuColorScience.NEO_ACES_HIGH_END ||
            normalized.colorSpace == GpuColorSpace.NEO_ACES_AP1 ||
            normalized.colorSpace == GpuColorSpace.NEO_ACES_AP0
        val modeCode = when (pipeline) {
            NeoGamutPipeline.V1 -> if (neoAcesHighEnd) 1f else 0f
            NeoGamutPipeline.V2_APEX -> when (normalized.colorSpace) {
                GpuColorSpace.NEO_ACES_AP0 -> 4f
                GpuColorSpace.NEO_ACES_AP1 -> 3f
                else -> 2f
            }
        }
        val referenceMatrices = AcesColorEngine.neoOklabReferenceMatrices()
        return VulkanColorScienceUniforms(
            acesCgToOklabReference = toColumnMajorMat4(referenceMatrices.acesCgToD65ReferenceRgb),
            oklabReferenceToAcesCg = toColumnMajorMat4(referenceMatrices.d65ReferenceRgbToAcesCg),
            hdrParams = floatArrayOf(
                if (hdrMappingEnabled) 1f else 0f,
                // s2-f03 sanitization: NaN/Inf survives coerceIn (NaN<min and NaN>max are both
                // false), which would poison the packed block and throw at the GPU boundary. Replace
                // non-finite inputs with the mid-band default FIRST. Identity for any finite input,
                // so the per-mode golden-lock stays byte-for-byte today (no-downgrade).
                hdrKnee.finiteOr(0.59f).coerceIn(0.25f, 0.95f),
                hdrShoulder.finiteOr(3.72f).coerceIn(0.1f, 8f),
                volumeCompression.finiteOr(0.54f).coerceIn(0f, 1f)
            ),
            modeParams = floatArrayOf(
                1f,
                if (logDeliveryEnabled) 1f else 0f,
                if (effectiveNivukxVision) 1f else 0f,
                modeCode
            ),
            measuredCorrectionParams = floatArrayOf(
                if (effectiveMeasuredCorrection.active) 1f else 0f,
                effectiveMeasuredCorrection.skinHueCorrectionDegrees.coerceIn(-12f, 12f),
                effectiveMeasuredCorrection.skyHueCorrectionDegrees.coerceIn(-12f, 12f),
                effectiveMeasuredCorrection.highlightRolloffBias.coerceIn(0f, 0.12f)
            ),
            identityParams = floatArrayOf(
                normalized.colorSpace.id.toFloat(),
                normalized.colorScience.id.toFloat(),
                normalized.recommendedTransport.id.toFloat(),
                modeCode
            ),
            measuredRgbScaleParams = floatArrayOf(
                effectiveMeasuredCorrection.redScale.coerceIn(0.85f, 1.15f),
                effectiveMeasuredCorrection.greenScale.coerceIn(0.85f, 1.15f),
                effectiveMeasuredCorrection.blueScale.coerceIn(0.85f, 1.15f),
                if (effectiveMeasuredCorrection.active) 1f else 0f
            )
        )
    }

    private fun resolveNeoGamutPipeline(
        colorSpaceName: String?,
        colorScienceName: String?,
        normalized: GpuColorProfileNormalization
    ): NeoGamutPipeline {
        val combined = listOf(colorSpaceName, colorScienceName)
            .filterNotNull()
            .joinToString(separator = " ")
            .lowercase(Locale.US)
        if (Regex("\\bpipeline\\s*=\\s*v1\\b|\\bpipeline\\s+v1\\b").containsMatchIn(combined)) {
            return NeoGamutPipeline.V1
        }
        return when {
            combined.contains("apex") || combined.contains("neogamut") -> NeoGamutPipeline.V2_APEX
            normalized.colorSpace == GpuColorSpace.NEO_WIDE_GAMUT -> NeoGamutPipeline.V2_APEX
            normalized.colorSpace == GpuColorSpace.NEO_ACES_AP1 -> NeoGamutPipeline.V2_APEX
            normalized.colorSpace == GpuColorSpace.NEO_ACES_AP0 -> NeoGamutPipeline.V2_APEX
            else -> NeoGamutPipeline.V1
        }
    }

    /** s2-f03: replace a non-finite scalar with [default]; identity for any finite value. */
    private fun Float.finiteOr(default: Float): Float = if (this.isFinite()) this else default

    private fun toColumnMajorMat4(matrix: FloatArray): FloatArray {
        require(matrix.size == 9) { "Neo color reference matrix must be 3x3" }
        return floatArrayOf(
            matrix[0], matrix[3], matrix[6], 0f,
            matrix[1], matrix[4], matrix[7], 0f,
            matrix[2], matrix[5], matrix[8], 0f,
            0f, 0f, 0f, 1f
        )
    }

}
