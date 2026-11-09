package com.neoproc.neoproccamx.recording

import java.util.Locale

internal data class NeoProfileTruthMetadata(
    val selectedProfile: String,
    val activeTransport: String,
    val transfer: String,
    val primaries: String,
    val matrix: String,
    val encodedBitDepth: Int,
    val internalMathBitDepth: Int,
    val dynamicMetadataWritten: Boolean,
    val npv10MasterAvailable: Boolean,
    val officialHdr10PlusMetadataWritten: Boolean = false,
    val vendorHdrCertification: Boolean = false,
    val vendorCertifiedHdr10Plus: Boolean = false,
    val vendorCertifiedDolbyVision: Boolean = false,
    val dolbyRpuWritten: Boolean = false,
    val externalHdrStandardSubstitution: Boolean = false,
    val proprietaryProfilePreserved: Boolean = true,
    val androidHdrTransportOnly: Boolean = false,
    val neoHdrNoVendorDependency: Boolean = false,
    val nLogVendorDependency: Boolean = false,
    val neoDynamicMetadataSidecarWritten: Boolean = false,
    val transportFallbackReason: String = "NONE",
    val highlightRolloff: String = "NOT_APPLICABLE",
    val skinToneProtection: String = "NOT_APPLICABLE",
    val redClippingGuard: String = "NOT_APPLICABLE",
    val cyanShadowGuard: String = "NOT_APPLICABLE",
    val selectedColorSpace: String = "Rec.709",
    val selectedColorScience: String = "Rec.709 Standard",
    val selectedColorFamily: String = "REC709",
    val selectedColorPrimaries: String = "BT.709",
    val selectedColorWhitePoint: String = "D65",
    val selectedColorTransfer: String = "BT.1886/sRGB",
    val selectedColorMatrix: String = "BT.709",
    val selectedColorWorkingGamut: String = "BT.709",
    val selectedContainerPrimaries: String = "BT.709",
    val selectedContainerTransfer: String = "SDR_VIDEO",
    val selectedContainerMatrix: String = "BT.709",
    val selectedRecommendedTransport: String = "REC709_SDR",
    val selectedNeoProfile: String = "Rec.709",
    val vendorManagedColorScience: Boolean = false,
    val proprietaryProfileAuthority: String = SovereignVideoProfilePolicy.AUTHORITY_STANDARD,
    val bt2100TransportCarrierOnly: Boolean = false,
    val bt2100CarrierMode: String = SovereignVideoProfilePolicy.CARRIER_NONE,
    val vendorDynamicRangeProfileUsed: Boolean = false,
    val vendorDynamicMetadataWritten: Boolean = false,
    val dolbyVisionOrHdr10PlusSubstitutionBlocked: Boolean = true,
    val previewRecordingNpv10ParityPolicy: String = SovereignVideoProfilePolicy.PARITY_LOCK,
    val rawPrivateMasterUnmodified: Boolean = false,
    val rawMonitorPipeline: String = SovereignVideoProfilePolicy.NOT_APPLICABLE,
    val rawMonitorDestructiveToMaster: Boolean = false,
    val npv10PrivateMasterUnmodified: Boolean = false
) {
    fun asMap(): Map<String, String> = mapOf(
        "selectedProfile" to selectedProfile,
        "activeTransport" to activeTransport,
        "transfer" to transfer,
        "primaries" to primaries,
        "matrix" to matrix,
        "encodedBitDepth" to encodedBitDepth.toString(),
        "internalMathBitDepth" to internalMathBitDepth.toString(),
        "dynamicMetadataWritten" to dynamicMetadataWritten.toString(),
        "npv10MasterAvailable" to npv10MasterAvailable.toString(),
        "officialHdr10PlusMetadataWritten" to officialHdr10PlusMetadataWritten.toString(),
        "vendorHdrCertification" to vendorHdrCertification.toString(),
        "vendorCertifiedHdr10Plus" to vendorCertifiedHdr10Plus.toString(),
        "vendorCertifiedDolbyVision" to vendorCertifiedDolbyVision.toString(),
        "dolbyRpuWritten" to dolbyRpuWritten.toString(),
        "externalHdrStandardSubstitution" to externalHdrStandardSubstitution.toString(),
        "proprietaryProfilePreserved" to proprietaryProfilePreserved.toString(),
        "androidHdrTransportOnly" to androidHdrTransportOnly.toString(),
        "neoHdrNoVendorDependency" to neoHdrNoVendorDependency.toString(),
        "nLogVendorDependency" to nLogVendorDependency.toString(),
        "neoDynamicMetadataSidecarWritten" to neoDynamicMetadataSidecarWritten.toString(),
        "transportFallbackReason" to transportFallbackReason,
        "highlightRolloff" to highlightRolloff,
        "skinToneProtection" to skinToneProtection,
        "redClippingGuard" to redClippingGuard,
        "cyanShadowGuard" to cyanShadowGuard,
        "selectedColorSpace" to selectedColorSpace,
        "selectedColorScience" to selectedColorScience,
        "selectedColorFamily" to selectedColorFamily,
        "selectedColorPrimaries" to selectedColorPrimaries,
        "selectedColorWhitePoint" to selectedColorWhitePoint,
        "selectedColorTransfer" to selectedColorTransfer,
        "selectedColorMatrix" to selectedColorMatrix,
        "selectedColorWorkingGamut" to selectedColorWorkingGamut,
        "selectedContainerPrimaries" to selectedContainerPrimaries,
        "selectedContainerTransfer" to selectedContainerTransfer,
        "selectedContainerMatrix" to selectedContainerMatrix,
        "selectedRecommendedTransport" to selectedRecommendedTransport,
        "selectedNeoProfile" to selectedNeoProfile,
        "vendorManagedColorScience" to vendorManagedColorScience.toString(),
        "proprietaryProfileAuthority" to proprietaryProfileAuthority,
        "bt2100TransportCarrierOnly" to bt2100TransportCarrierOnly.toString(),
        "bt2100CarrierMode" to bt2100CarrierMode,
        "vendorDynamicRangeProfileUsed" to vendorDynamicRangeProfileUsed.toString(),
        "vendorDynamicMetadataWritten" to vendorDynamicMetadataWritten.toString(),
        "dolbyVisionOrHdr10PlusSubstitutionBlocked" to dolbyVisionOrHdr10PlusSubstitutionBlocked.toString(),
        "previewRecordingNpv10ParityPolicy" to previewRecordingNpv10ParityPolicy,
        "rawPrivateMasterUnmodified" to rawPrivateMasterUnmodified.toString(),
        "rawMonitorPipeline" to rawMonitorPipeline,
        "rawMonitorDestructiveToMaster" to rawMonitorDestructiveToMaster.toString(),
        "npv10PrivateMasterUnmodified" to npv10PrivateMasterUnmodified.toString()
    )
}

internal object NeoProfileTruthMetadataPolicy {
    fun resolve(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String,
        normalizedLogVersion: String,
        dynamicMetadataWritten: Boolean = false,
        npv10MasterAvailable: Boolean = request.mode == EliteVideoPipelineMode.NPV10,
        neoDynamicMetadataSidecarWritten: Boolean = false
    ): NeoProfileTruthMetadata {
        val manualColorProfile = ManualVideoColorSelectionPolicy.resolveColorProfile(
            colorSpace = request.colorSpace,
            colorScience = request.colorScience
        )
        val selectedProfile = selectedProfile(
            request,
            normalizedHdrVersion,
            normalizedLogVersion,
            manualColorProfile
        )
        val neoHdrActive = isNeoHdr(request, normalizedHdrVersion)
        val main10Supported = neoHdrActive && main10PqTransportSupported(request)
        val activeTransport = activeTransport(request, normalizedHdrVersion, main10Supported)
        val proprietaryAuthority = SovereignVideoProfilePolicy.authorityForRequest(
            request = request,
            normalizedHdrVersion = normalizedHdrVersion,
            normalizedLogVersion = normalizedLogVersion
        )
        val carrierMode = SovereignVideoProfilePolicy.carrierFor(activeTransport)
        return NeoProfileTruthMetadata(
            selectedProfile = selectedProfile,
            activeTransport = activeTransport,
            transfer = transfer(request, normalizedHdrVersion, normalizedLogVersion, main10Supported, manualColorProfile),
            primaries = primaries(request, normalizedHdrVersion, normalizedLogVersion, main10Supported, manualColorProfile),
            matrix = matrix(request, normalizedHdrVersion, normalizedLogVersion, main10Supported, manualColorProfile),
            encodedBitDepth = encodedBitDepth(request, normalizedHdrVersion),
            internalMathBitDepth = internalMathBitDepth(request, normalizedHdrVersion, normalizedLogVersion),
            dynamicMetadataWritten = dynamicMetadataWritten,
            npv10MasterAvailable = npv10MasterAvailable,
            officialHdr10PlusMetadataWritten = false,
            vendorHdrCertification = false,
            vendorCertifiedHdr10Plus = false,
            vendorCertifiedDolbyVision = false,
            dolbyRpuWritten = false,
            externalHdrStandardSubstitution = false,
            proprietaryProfilePreserved = true,
            androidHdrTransportOnly = neoHdrActive &&
                activeTransport.contains("PQ", ignoreCase = true) &&
                activeTransport.contains("MAIN10", ignoreCase = true),
            neoHdrNoVendorDependency = neoHdrActive,
            nLogVendorDependency = false,
            neoDynamicMetadataSidecarWritten = neoHdrActive && neoDynamicMetadataSidecarWritten,
            transportFallbackReason = transportFallbackReason(
                request,
                normalizedHdrVersion,
                normalizedLogVersion,
                main10Supported
            ),
            highlightRolloff = if (neoHdrActive) "BT2390_SOFT_SHOULDER_SCENE_ADAPTIVE" else "NOT_APPLICABLE",
            skinToneProtection = if (neoHdrActive) "BT2100_SKIN_RED_RATIO_PROTECTION" else "NOT_APPLICABLE",
            redClippingGuard = if (neoHdrActive) "ACTIVE_CHANNEL_HEADROOM_LIMIT" else "NOT_APPLICABLE",
            cyanShadowGuard = if (neoHdrActive) "ACTIVE_SHADOW_CHROMA_FLOOR" else "NOT_APPLICABLE",
            selectedColorSpace = manualColorProfile.canonicalColorSpace,
            selectedColorScience = manualColorProfile.canonicalColorScience,
            selectedColorFamily = manualColorProfile.family,
            selectedColorPrimaries = manualColorProfile.selectedPrimaries,
            selectedColorWhitePoint = manualColorProfile.whitePoint,
            selectedColorTransfer = manualColorProfile.transfer,
            selectedColorMatrix = manualColorProfile.matrix,
            selectedColorWorkingGamut = manualColorProfile.workingGamut,
            selectedContainerPrimaries = manualColorProfile.containerPrimaries,
            selectedContainerTransfer = manualColorProfile.containerTransfer,
            selectedContainerMatrix = manualColorProfile.containerMatrix,
            selectedRecommendedTransport = manualColorProfile.recommendedTransport,
            selectedNeoProfile = manualColorProfile.neoProfile,
            vendorManagedColorScience = manualColorProfile.vendorManaged,
            proprietaryProfileAuthority = proprietaryAuthority,
            bt2100TransportCarrierOnly = proprietaryAuthority == SovereignVideoProfilePolicy.AUTHORITY_NDR &&
                carrierMode != SovereignVideoProfilePolicy.CARRIER_NONE,
            bt2100CarrierMode = carrierMode,
            vendorDynamicRangeProfileUsed = false,
            vendorDynamicMetadataWritten = false,
            dolbyVisionOrHdr10PlusSubstitutionBlocked = true,
            previewRecordingNpv10ParityPolicy = SovereignVideoProfilePolicy.PARITY_LOCK,
            rawPrivateMasterUnmodified = request.mode == EliteVideoPipelineMode.RAW ||
                request.payloadFormat?.isRawPacked == true,
            rawMonitorPipeline = if (request.mode == EliteVideoPipelineMode.RAW) {
                SovereignVideoProfilePolicy.RAW_MONITOR_PREVIEW_ONLY
            } else {
                SovereignVideoProfilePolicy.NOT_APPLICABLE
            },
            rawMonitorDestructiveToMaster = false,
            npv10PrivateMasterUnmodified = request.mode == EliteVideoPipelineMode.NPV10
        )
    }

    fun displayHdrProfile(normalizedHdrVersion: String): String =
        when (normalizedHdrVersion) {
            "NIVUKXVISION" -> "NIVUKX Vision"
            "APEX_SHARP" -> "NIVUKX APEX SHARP"
            "APEX_ZERO" -> "NIVUKX APEX ZERO"
            "APEX_CINEMA" -> "NIVUKX APEX CINEMA"
            "NDR10+" -> "NDR10+"
            else -> normalizedHdrVersion
        }

    private fun selectedProfile(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String,
        normalizedLogVersion: String,
        manualColorProfile: ManualVideoColorProfile
    ): String =
        when (request.mode) {
            EliteVideoPipelineMode.HDR -> displayHdrProfile(normalizedHdrVersion)
            EliteVideoPipelineMode.NPV10 -> when {
                Bt2100TransportPolicy.isNeoBt2100HdrVersion(normalizedHdrVersion) ->
                    displayHdrProfile(normalizedHdrVersion)
                isNpv10Log(request, normalizedLogVersion) -> normalizedLogVersion
                else -> manualColorProfile.neoProfile
            }
            EliteVideoPipelineMode.LOG -> normalizedLogVersion
            EliteVideoPipelineMode.RAW -> "RAW"
            EliteVideoPipelineMode.PRO -> "Pro SDR"
            EliteVideoPipelineMode.NORMAL -> "Normal SDR"
        }

    private fun activeTransport(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String,
        main10Supported: Boolean
    ): String =
        when (request.mode) {
            EliteVideoPipelineMode.HDR -> when (normalizedHdrVersion) {
                "NDR10+", "NIVUKXVISION", "APEX_SHARP", "APEX_ZERO", "APEX_CINEMA" -> if (main10Supported) {
                    Bt2100TransportPolicy.TRANSPORT_PQ_MAIN10
                } else {
                    "FALLBACK_SDR_UNSUPPORTED_MAIN10"
                }
                else -> "SDR"
            }
            EliteVideoPipelineMode.LOG -> if (main10PqTransportSupported(request)) {
                "HEVC_MAIN10_LOG"
            } else {
                "FALLBACK_SDR_UNSUPPORTED_MAIN10_LOG"
            }
            EliteVideoPipelineMode.NPV10 -> when {
                Bt2100TransportPolicy.isNeoBt2100HdrVersion(normalizedHdrVersion) && main10Supported ->
                    Bt2100TransportPolicy.TRANSPORT_NPV10_PQ_PRIVATE_MASTER
                Bt2100TransportPolicy.isNeoBt2100HdrVersion(normalizedHdrVersion) ->
                    "NPV10_BT.2100_PQ_UNPROVEN_PRIVATE_MASTER"
                else -> "NPV10_PRIVATE_MASTER"
            }
            EliteVideoPipelineMode.RAW -> "RAW_PRIVATE_MASTER"
            EliteVideoPipelineMode.PRO,
            EliteVideoPipelineMode.NORMAL -> if (main10PqTransportSupported(request)) "HEVC_MAIN10_SDR" else "HEVC_SDR"
        }

    private fun transfer(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String,
        normalizedLogVersion: String,
        main10Supported: Boolean,
        manualColorProfile: ManualVideoColorProfile
    ): String =
        when (request.mode) {
            EliteVideoPipelineMode.HDR -> if (normalizedHdrVersion != "OFF" && main10Supported) {
                Bt2100TransportPolicy.TRANSFER_PQ
            } else {
                "BT.709/OETF"
            }
            EliteVideoPipelineMode.LOG -> if (main10PqTransportSupported(request)) "N-Log" else "BT.709/OETF"
            EliteVideoPipelineMode.NPV10 -> when {
                Bt2100TransportPolicy.isNeoBt2100HdrVersion(normalizedHdrVersion) && main10Supported ->
                    Bt2100TransportPolicy.TRANSFER_PQ
                isNpv10Log(request, normalizedLogVersion) -> "N-Log"
                else -> "BT.709/OETF"
            }
            EliteVideoPipelineMode.RAW -> "Scene-linear"
            EliteVideoPipelineMode.PRO,
            EliteVideoPipelineMode.NORMAL -> "BT.709/OETF"
        }

    private fun primaries(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String,
        normalizedLogVersion: String,
        main10Supported: Boolean,
        manualColorProfile: ManualVideoColorProfile
    ): String {
        if (request.mode == EliteVideoPipelineMode.HDR && normalizedHdrVersion != "OFF" && !main10Supported) {
            return "BT.709"
        }
        if (request.mode == EliteVideoPipelineMode.NPV10) {
            return when {
                Bt2100TransportPolicy.isNeoBt2100HdrVersion(normalizedHdrVersion) && main10Supported -> "BT.2020"
                isNpv10Log(request, normalizedLogVersion) -> "BT.2020"
                else -> manualColorProfile.containerPrimaries
            }
        }
        if (
            request.mode == EliteVideoPipelineMode.LOG &&
            normalizedLogVersion.startsWith("N-Log") &&
            !main10PqTransportSupported(request)
        ) {
            return "BT.709"
        }
        val colorSpace = request.colorSpace.uppercase(Locale.US)
        val wide = colorSpace.contains("2020") ||
            colorSpace.contains("P3") ||
            colorSpace.contains("ACES") ||
            colorSpace.contains("WIDE") ||
            normalizedHdrVersion != "OFF" ||
            (request.mode == EliteVideoPipelineMode.LOG && normalizedLogVersion.startsWith("N-Log")) ||
            request.mode == EliteVideoPipelineMode.HDR ||
            request.mode == EliteVideoPipelineMode.LOG
        return if (wide) "BT.2020" else "BT.709"
    }

    private fun matrix(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String,
        normalizedLogVersion: String,
        main10Supported: Boolean,
        manualColorProfile: ManualVideoColorProfile
    ): String =
        when (request.mode) {
            EliteVideoPipelineMode.NPV10 ->
                if (Bt2100TransportPolicy.isNeoBt2100HdrVersion(normalizedHdrVersion) && main10Supported) {
                    Bt2100TransportPolicy.MATRIX_NCL
                } else if (isNpv10Log(request, normalizedLogVersion)) {
                    Bt2100TransportPolicy.MATRIX_NCL
                } else {
                    manualColorProfile.containerMatrix
                }
            EliteVideoPipelineMode.HDR ->
                if (normalizedHdrVersion != "OFF" && main10Supported) Bt2100TransportPolicy.MATRIX_NCL else "BT.709"
            EliteVideoPipelineMode.LOG -> if (main10PqTransportSupported(request)) {
                Bt2100TransportPolicy.MATRIX_NCL
            } else {
                "BT.709"
            }
            EliteVideoPipelineMode.PRO,
            EliteVideoPipelineMode.NORMAL ->
                if (primaries(request, normalizedHdrVersion, "OFF", main10Supported, manualColorProfile) == "BT.2020") {
                    Bt2100TransportPolicy.MATRIX_NCL
                } else {
                    "BT.709"
                }
            else -> "BT.709"
        }

    private fun encodedBitDepth(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String
    ): Int =
        when (request.mode) {
            EliteVideoPipelineMode.HDR -> when {
                normalizedHdrVersion == "OFF" -> 8
                request.tenBitTransport && request.storageBitDepth >= 10 -> 10
                else -> request.storageBitDepth.coerceIn(8, 10)
            }
            EliteVideoPipelineMode.LOG,
            EliteVideoPipelineMode.NORMAL,
            EliteVideoPipelineMode.PRO -> if (main10PqTransportSupported(request)) 10 else 8
            EliteVideoPipelineMode.NPV10 -> request.storageBitDepth
            EliteVideoPipelineMode.RAW -> request.storageBitDepth.coerceAtLeast(request.rawBitDepth)
        }

    private fun internalMathBitDepth(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String,
        normalizedLogVersion: String
    ): Int =
        when {
            request.mode == EliteVideoPipelineMode.HDR && normalizedHdrVersion != "OFF" -> 12
            request.mode == EliteVideoPipelineMode.LOG &&
                normalizedLogVersion.startsWith("N-Log") &&
                main10PqTransportSupported(request) -> 12
            request.mode == EliteVideoPipelineMode.NPV10 -> maxOf(request.sourceBitDepth, request.storageBitDepth)
            request.mode == EliteVideoPipelineMode.RAW -> maxOf(request.rawBitDepth, request.sourceBitDepth, request.storageBitDepth)
            else -> encodedBitDepth(request, normalizedHdrVersion)
        }

    private fun isNeoHdr(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String
    ): Boolean =
        (request.mode == EliteVideoPipelineMode.HDR || request.mode == EliteVideoPipelineMode.NPV10) &&
            Bt2100TransportPolicy.isNeoBt2100HdrVersion(normalizedHdrVersion)

    private fun main10PqTransportSupported(request: EliteVideoQualityRequest): Boolean =
        request.tenBitTransport && request.sourceBitDepth >= 10 && request.storageBitDepth >= 10

    private fun transportFallbackReason(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String,
        normalizedLogVersion: String,
        main10Supported: Boolean
    ): String =
        when {
            request.mode == EliteVideoPipelineMode.LOG &&
                normalizedLogVersion.startsWith("N-Log") &&
                !main10PqTransportSupported(request) -> "LOG_MAIN10_TRANSPORT_UNAVAILABLE"
            !isNeoHdr(request, normalizedHdrVersion) || main10Supported -> "NONE"
            else -> "MAIN10_TRANSPORT_UNAVAILABLE"
        }

    private fun isNpv10Log(
        request: EliteVideoQualityRequest,
        normalizedLogVersion: String
    ): Boolean =
        request.mode == EliteVideoPipelineMode.NPV10 &&
            normalizedLogVersion.startsWith("N-Log") &&
            request.colorSpace.hasLogSignal()

    private fun String?.hasLogSignal(): Boolean {
        val key = orEmpty().trim().lowercase(Locale.US)
        return key.contains("n-log") ||
            key.contains("n log") ||
            key.contains("nlog") ||
            key.contains("log")
    }
}

internal data class NeoHdrDynamicMetadataSidecar(
    val entries: Map<String, String>
) {
    val text: String =
        entries.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) -> "$key=$value" }

    companion object {
        fun build(
            selectedProfile: String,
            averageLuma: Double,
            highlightRatio: Double,
            dynamicRangeScore: Double,
            inlineDynamicMetadataWritten: Boolean,
            activeTransport: String = Bt2100TransportPolicy.TRANSPORT_PQ_MAIN10,
            transportFallbackReason: String = "NONE",
            transfer: String = "ST2084/PQ",
            primaries: String = "BT.2020",
            matrix: String = "BT.2020-NCL",
            encodedBitDepth: Int = 10
        ): NeoHdrDynamicMetadataSidecar {
            val normalizedHdr = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(selectedProfile)
                .let { if (Bt2100TransportPolicy.isNeoBt2100HdrVersion(it)) it else "NDR10+" }
            val truth = NeoProfileTruthMetadata(
                selectedProfile = NeoProfileTruthMetadataPolicy.displayHdrProfile(normalizedHdr),
                activeTransport = activeTransport,
                transfer = transfer,
                primaries = primaries,
                matrix = matrix,
                encodedBitDepth = encodedBitDepth.coerceIn(8, 10),
                internalMathBitDepth = 12,
                dynamicMetadataWritten = inlineDynamicMetadataWritten,
                npv10MasterAvailable = false,
                officialHdr10PlusMetadataWritten = false,
                vendorHdrCertification = false,
                vendorCertifiedHdr10Plus = false,
                vendorCertifiedDolbyVision = false,
                dolbyRpuWritten = false,
                externalHdrStandardSubstitution = false,
                proprietaryProfilePreserved = true,
                androidHdrTransportOnly = activeTransport.contains("PQ", ignoreCase = true) &&
                    activeTransport.contains("MAIN10", ignoreCase = true),
                neoHdrNoVendorDependency = true,
                nLogVendorDependency = false,
                neoDynamicMetadataSidecarWritten = true,
                transportFallbackReason = transportFallbackReason,
                highlightRolloff = "BT2390_SOFT_SHOULDER_SCENE_ADAPTIVE",
                skinToneProtection = "BT2100_SKIN_RED_RATIO_PROTECTION",
                redClippingGuard = "ACTIVE_CHANNEL_HEADROOM_LIMIT",
                cyanShadowGuard = "ACTIVE_SHADOW_CHROMA_FLOOR",
                proprietaryProfileAuthority = SovereignVideoProfilePolicy.AUTHORITY_NDR,
                bt2100TransportCarrierOnly = SovereignVideoProfilePolicy
                    .carrierFor(activeTransport) != SovereignVideoProfilePolicy.CARRIER_NONE,
                bt2100CarrierMode = SovereignVideoProfilePolicy.carrierFor(activeTransport),
                vendorDynamicRangeProfileUsed = false,
                vendorDynamicMetadataWritten = false,
                dolbyVisionOrHdr10PlusSubstitutionBlocked = true,
                previewRecordingNpv10ParityPolicy = SovereignVideoProfilePolicy.PARITY_LOCK
            )
            val entries = buildMap {
                putAll(truth.asMap())
                put("dynamicMetadataSidecarWritten", true.toString())
                put("averageLuma", "%.6f".format(Locale.US, averageLuma.coerceIn(0.0, 1.0)))
                put("highlightRatio", "%.6f".format(Locale.US, highlightRatio.coerceIn(0.0, 1.0)))
                put("dynamicRangeScore", "%.6f".format(Locale.US, dynamicRangeScore.coerceIn(0.0, 1.0)))
            }
            return NeoHdrDynamicMetadataSidecar(entries)
        }
    }
}
