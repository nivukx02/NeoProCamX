package com.neoproc.neoproccamx.recording

internal data class NivukxDrRenderPlan(
    val active: Boolean,
    val sourceProfile: String,
    val productProfile: String,
    val transport: String,
    val transfer: String,
    val primaries: String,
    val range: String,
    val workingGamut: String,
    val appearanceModel: String,
    val internalPrecision: String,
    val effectiveBitDepth: Int,
    val requestedBitDepth: Int,
    val fallbackReason: String,
    val metadataStack: String,
    val vendorCertified: Boolean,
    val vendorRpuWritten: Boolean
) {
    fun asMetadata(): Map<String, String> = mapOf(
        "nivukxDrEliteActive" to active.toString(),
        "nivukxDrSourceProfile" to sourceProfile,
        "nivukxDrProductProfile" to productProfile,
        "nivukxDrTransport" to transport,
        "nivukxDrTransfer" to transfer,
        "nivukxDrPrimaries" to primaries,
        "nivukxDrRange" to range,
        "nivukxDrWorkingGamut" to workingGamut,
        "nivukxDrAppearanceModel" to appearanceModel,
        "nivukxDrInternalPrecision" to internalPrecision,
        "nivukxDrEffectiveBitDepth" to effectiveBitDepth.toString(),
        "nivukxDrRequestedBitDepth" to requestedBitDepth.toString(),
        "nivukxDrFallbackReason" to fallbackReason,
        "nivukxDrMetadataStack" to metadataStack,
        "nivukxDrVendorCertified" to vendorCertified.toString(),
        "nivukxDrVendorRpuWritten" to vendorRpuWritten.toString()
    )
}

internal object NivukxVersionRenderPolicy {
    private const val PRODUCT_PROFILE = "NIVUKX-DR_ELITE"

    fun resolve(
        hdrMode: String,
        codec: GpuVideoCodec,
        sourceBitDepth: Int,
        storageBitDepth: Int,
        tenBitTransport: Boolean,
        hevcMain12Supported: Boolean = false,
        av1Main12Supported: Boolean = false
    ): NivukxDrRenderPlan {
        val sourceProfile = normalizeSourceProfile(hdrMode)
        val active = sourceProfile == "NDR10+" || sourceProfile == "NIVUKXVISION"
        if (!active) {
            return inactive(sourceProfile)
        }

        val requestedBitDepth = 12
        val hasTwelveBitSource = sourceBitDepth >= 12 && storageBitDepth >= 12
        val av1Main12 = codec == GpuVideoCodec.AV1 && av1Main12Supported && hasTwelveBitSource
        val hevcMain12 = codec == GpuVideoCodec.HEVC && hevcMain12Supported && hasTwelveBitSource
        val hasMain10 = tenBitTransport && sourceBitDepth >= 10 && storageBitDepth >= 10
        val transport = when {
            av1Main12 -> "AV1_MAIN12_BT2020_PQ"
            hevcMain12 -> "HEVC_MAIN12_BT2020_PQ"
            hasMain10 && codec == GpuVideoCodec.AV1 -> "AV1_MAIN10_LITE_FALLBACK"
            hasMain10 -> "HEVC_MAIN10_LITE_FALLBACK"
            else -> "UNSUPPORTED_TRANSPORT_FALLBACK"
        }
        val effectiveBitDepth = when {
            av1Main12 || hevcMain12 -> 12
            hasMain10 -> 10
            else -> storageBitDepth.coerceAtMost(8)
        }
        val fallbackReason = when {
            av1Main12 || hevcMain12 -> "NONE"
            hasMain10 -> "MAIN12_OR_AV1_12_UNAVAILABLE"
            else -> "MAIN10_TRANSPORT_UNAVAILABLE"
        }

        return NivukxDrRenderPlan(
            active = true,
            sourceProfile = sourceProfile,
            productProfile = PRODUCT_PROFILE,
            transport = transport,
            transfer = "ST2084_PQ",
            primaries = "BT2020",
            range = "LIMITED",
            workingGamut = "ACES_AP1_FP16_SCENE_LINEAR",
            appearanceModel = "JZAZBZ_FP32",
            internalPrecision = "FP32_TONE_SOLVE_FP16_LINEAR_STORAGE",
            effectiveBitDepth = effectiveBitDepth,
            requestedBitDepth = requestedBitDepth,
            fallbackReason = fallbackReason,
            metadataStack = "T0_T1_T2_T3_T4_T5_RTILE_T6_SALIENCY_T7_KALMAN_TMB",
            vendorCertified = false,
            vendorRpuWritten = false
        )
    }

    private fun inactive(sourceProfile: String): NivukxDrRenderPlan =
        NivukxDrRenderPlan(
            active = false,
            sourceProfile = sourceProfile,
            productProfile = "NONE",
            transport = "NOT_APPLICABLE",
            transfer = "NOT_APPLICABLE",
            primaries = "NOT_APPLICABLE",
            range = "NOT_APPLICABLE",
            workingGamut = "NOT_APPLICABLE",
            appearanceModel = "NOT_APPLICABLE",
            internalPrecision = "NOT_APPLICABLE",
            effectiveBitDepth = 0,
            requestedBitDepth = 0,
            fallbackReason = "NOT_APPLICABLE",
            metadataStack = "NOT_APPLICABLE",
            vendorCertified = false,
            vendorRpuWritten = false
        )

    private fun normalizeSourceProfile(hdrMode: String): String =
        when (RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(hdrMode)) {
            "NIVUKXVISION" -> "NIVUKXVISION"
            "OFF" -> "OFF"
            else -> "NDR10+"
        }
}
