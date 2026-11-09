package com.neoproc.neoproccamx.color

internal enum class NivukxVisionConformance {
    LITE,
    PRO,
    ELITE
}

internal enum class NivukxVisionPrimaryDelivery {
    PQ,
    NLOGS_12
}

internal data class NivukxVisionRenderRequest(
    val active: Boolean,
    val requestedBitDepth: Int,
    val sourceBitDepth: Int,
    val storageBitDepth: Int,
    val codec: String,
    val classProtectionAvailable: Boolean,
    val metadataSigningAvailable: Boolean,
    val nlogs12Requested: Boolean
)

internal data class NivukxVisionRenderPlan(
    val active: Boolean,
    val productProfile: String,
    val conformance: NivukxVisionConformance,
    val requestedBitDepth: Int,
    val honoredBitDepth: Int,
    val primaryDelivery: NivukxVisionPrimaryDelivery,
    val workingGamut: String,
    val workingPixelPrecision: String,
    val solvePrecision: String,
    val colorAppearanceModel: String,
    val chromaCompression: String,
    val metadataTiers: List<String>,
    val fallbackReason: String
) {
    val eliteHonored: Boolean
        get() = conformance == NivukxVisionConformance.ELITE && fallbackReason == "NONE"
}

internal object NivukxVisionRenderPolicy {
    const val PRODUCT_PROFILE = "NIVUKX_VISION_ELITE"

    fun resolve(request: NivukxVisionRenderRequest): NivukxVisionRenderPlan {
        if (!request.active) {
            return basePlan(
                active = false,
                conformance = NivukxVisionConformance.LITE,
                requestedBitDepth = request.requestedBitDepth,
                honoredBitDepth = 0,
                primaryDelivery = NivukxVisionPrimaryDelivery.PQ,
                fallbackReason = "VISION_NOT_SELECTED"
            )
        }
        val codecAllowsTwelve = request.codec.equals("AV1", ignoreCase = true) ||
            request.codec.contains("HEVC", ignoreCase = true)
        val twelveBitHonored = request.requestedBitDepth >= 12 &&
            request.sourceBitDepth >= 12 &&
            request.storageBitDepth >= 12 &&
            codecAllowsTwelve
        val conformance = when {
            twelveBitHonored && request.classProtectionAvailable && request.metadataSigningAvailable ->
                NivukxVisionConformance.ELITE
            twelveBitHonored && request.metadataSigningAvailable ->
                NivukxVisionConformance.PRO
            else -> NivukxVisionConformance.LITE
        }
        val fallbackBitDepth = minOf(
            request.requestedBitDepth,
            request.sourceBitDepth,
            request.storageBitDepth
        ).coerceIn(0, 10)
        val honoredBitDepth = if (twelveBitHonored) 12 else fallbackBitDepth
        val fallbackReason = when {
            conformance == NivukxVisionConformance.ELITE -> "NONE"
            honoredBitDepth < 10 -> "MAIN10_OR_MAIN12_UNAVAILABLE"
            honoredBitDepth < 12 -> "MAIN12_OR_AV1_12_UNAVAILABLE"
            !request.classProtectionAvailable -> "CLASS_PROTECTION_EVIDENCE_UNAVAILABLE"
            !request.metadataSigningAvailable -> "TMB_SIGNING_UNAVAILABLE"
            else -> "LIMITED_CONFORMANCE"
        }
        val delivery = if (request.nlogs12Requested && honoredBitDepth >= 12) {
            NivukxVisionPrimaryDelivery.NLOGS_12
        } else {
            NivukxVisionPrimaryDelivery.PQ
        }
        return basePlan(
            active = true,
            conformance = conformance,
            requestedBitDepth = request.requestedBitDepth,
            honoredBitDepth = honoredBitDepth,
            primaryDelivery = delivery,
            fallbackReason = fallbackReason
        )
    }

    private fun basePlan(
        active: Boolean,
        conformance: NivukxVisionConformance,
        requestedBitDepth: Int,
        honoredBitDepth: Int,
        primaryDelivery: NivukxVisionPrimaryDelivery,
        fallbackReason: String
    ): NivukxVisionRenderPlan =
        NivukxVisionRenderPlan(
            active = active,
            productProfile = PRODUCT_PROFILE,
            conformance = conformance,
            requestedBitDepth = requestedBitDepth,
            honoredBitDepth = honoredBitDepth,
            primaryDelivery = primaryDelivery,
            workingGamut = "ACES_AP1",
            workingPixelPrecision = "FP16_LINEAR_SCENE_REFERRED",
            solvePrecision = "FP32",
            colorAppearanceModel = "JZAZBZ",
            chromaCompression = "HUE_PRESERVED_RADIAL_CMAX_33x33_NEAR_WHITE_GUARD",
            metadataTiers = (0..7).map { "V$it" },
            fallbackReason = fallbackReason
        )
}
