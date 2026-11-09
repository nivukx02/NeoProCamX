package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics

internal enum class CameraHardwareLevelTier(val label: String) {
    LEVEL_3("LEVEL_3"),
    FULL("FULL"),
    EXTERNAL("EXTERNAL"),
    LIMITED("LIMITED"),
    LEGACY("LEGACY"),
    UNKNOWN("UNKNOWN");

    companion object {
        fun fromCamera2Level(level: Int?): CameraHardwareLevelTier =
            when (level) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> LEVEL_3
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> FULL
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> EXTERNAL
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> LIMITED
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> LEGACY
                else -> UNKNOWN
            }
    }
}

internal enum class CameraHalProfileTier(val displayName: String) {
    ULTRA_RAW_CINEMA("Ultra RAW Cinema"),
    ELITE_VIDEO_HAL("Elite Video HAL"),
    PRO_CINEMA("Pro Cinema"),
    COMPUTATIONAL_VIDEO("Computational Video"),
    BASIC("Basic Camera2"),
    VENDOR_LIMITED("Vendor Limited")
}

internal data class CameraHalProfile(
    val tier: CameraHalProfileTier,
    val score: Int,
    val hardwareLevel: CameraHardwareLevelTier,
    val rawScore: BypassCapabilityScore,
    val videoScore: BypassCapabilityScore,
    val fullSensorScore: BypassCapabilityScore,
    val truthfulRawSensor: Boolean,
    val supportsTenBitHdr: Boolean,
    val supportsManualSensor: Boolean,
    val supportsStabilization: Boolean,
    val largestRawSize: CameraStreamSize?,
    val largestYuvSize: CameraStreamSize?,
    val largestEncoderSize: CameraStreamSize?,
    val physicalLensCount: Int,
    val auxiliaryLensLabels: List<String>,
    val exposedVendorRequestKeyCount: Int,
    val limitations: List<String>,
    val evidence: List<String>
) {
    val badgeLabel: String
        get() = "${tier.displayName} $score/100"

    fun toLogLine(): String =
        "tier=${tier.displayName} score=$score hardware=${hardwareLevel.label} " +
            "raw=${rawScore.label} video=${videoScore.label} fullSensor=${fullSensorScore.label} " +
            "tenBit=$supportsTenBitHdr manual=$supportsManualSensor stabilization=$supportsStabilization " +
            "rawSize=${largestRawSize?.label() ?: "none"} yuvSize=${largestYuvSize?.label() ?: "none"} " +
            "encoderSize=${largestEncoderSize?.label() ?: "none"} " +
            "physicalLenses=$physicalLensCount auxiliary=${auxiliaryLensLabels.joinToString(", ")} " +
            "vendorRequestKeys=$exposedVendorRequestKeyCount " +
            "limitations=${limitations.joinToString("; ")} evidence=${evidence.joinToString("; ")}"
}

internal object CameraHalProfilePolicy {
    private const val SENSOR_COVERAGE_TOLERANCE = 0.98

    fun resolve(
        capability: CameraCapabilityInventory,
        requestPlan: IspBypassRequestPlan,
        vendorScanReport: CameraVendorReadoutScanReport = CameraVendorReadoutScanReport.EMPTY
    ): CameraHalProfile {
        val bypass = TruthfulIspBypassCapabilityPolicy.resolve(
            capability = capability,
            requestPlan = requestPlan
        )
        val referenceSize = capability.activeArraySize ?: capability.pixelArraySize
        val rawMax = largest(capability.rawOutputSizes)
        val yuvMax = largest(capability.yuvOutputSizes)
        val encoderMax = largest(capability.encoderOutputSizes)
        val largestPublicStream = largest(
            capability.rawOutputSizes + capability.yuvOutputSizes + capability.encoderOutputSizes
        )
        val publicStreamsAvailable = largestPublicStream != null
        val pixelArrayDownsampled = capability.pixelArraySize != null &&
            largestPublicStream != null &&
            !largestPublicStream.covers(capability.pixelArraySize)
        val highResYuv = referenceSize != null && yuvMax?.covers(referenceSize) == true
        val videoFullSensor = referenceSize != null &&
            listOfNotNull(yuvMax, encoderMax).any { it.covers(referenceSize) }
        val truthfulRaw = bypass.raw == BypassCapabilityScore.TRUE_ORIGINAL_RAW
        val limitedRaw = bypass.raw == BypassCapabilityScore.SENSOR_RAW_LIMITED_ISP
        val supportsStabilization = capability.supportsOis || capability.supportsVideoStabilization
        val vendorGatedFullSensor = referenceSize != null &&
            vendorScanReport.gatedCandidates.any { it.size.covers(referenceSize) }
        val publicPhysicalLensProfiles = capability.physicalLensProfiles.filter { it.publicCamera2Id }
        val auxiliaryLensLabels = publicPhysicalLensProfiles.map { it.label }.distinct()

        val score = computeScore(
            capability = capability,
            requestPlan = requestPlan,
            truthfulRaw = truthfulRaw,
            limitedRaw = limitedRaw,
            fullSensorScore = bypass.fullSensor,
            rawMax = rawMax,
            yuvMax = yuvMax,
            encoderMax = encoderMax,
            highResYuv = highResYuv,
            videoFullSensor = videoFullSensor,
            supportsStabilization = supportsStabilization,
            vendorGatedFullSensor = vendorGatedFullSensor,
            pixelArrayDownsampled = pixelArrayDownsampled,
            publicStreamsAvailable = publicStreamsAvailable,
            publicPhysicalLensCount = publicPhysicalLensProfiles.size,
            publicPhysicalLensTypeCount = publicPhysicalLensProfiles.map { it.lensType }.distinct().size
        )
        val eliteProcessedVideoHal = isEliteProcessedVideoHal(
            score = score,
            capability = capability,
            truthfulRaw = truthfulRaw,
            yuvMax = yuvMax,
            encoderMax = encoderMax,
            highResYuv = highResYuv,
            videoFullSensor = videoFullSensor,
            supportsStabilization = supportsStabilization,
            vendorGatedFullSensor = vendorGatedFullSensor,
            pixelArrayDownsampled = pixelArrayDownsampled,
            publicStreamsAvailable = publicStreamsAvailable
        )

        val tier = resolveTier(
            score = score,
            truthfulRaw = truthfulRaw,
            fullSensorScore = bypass.fullSensor,
            videoFullSensor = videoFullSensor,
            publicStreamsAvailable = publicStreamsAvailable,
            vendorGatedFullSensor = vendorGatedFullSensor,
            eliteProcessedVideoHal = eliteProcessedVideoHal
        )

        return CameraHalProfile(
            tier = tier,
            score = score,
            hardwareLevel = capability.hardwareLevel,
            rawScore = bypass.raw,
            videoScore = bypass.video,
            fullSensorScore = bypass.fullSensor,
            truthfulRawSensor = truthfulRaw,
            supportsTenBitHdr = capability.supportsTenBitHdr,
            supportsManualSensor = capability.supportsManualSensor,
            supportsStabilization = supportsStabilization,
            largestRawSize = rawMax,
            largestYuvSize = yuvMax,
            largestEncoderSize = encoderMax,
            physicalLensCount = publicPhysicalLensProfiles.size,
            auxiliaryLensLabels = auxiliaryLensLabels,
            exposedVendorRequestKeyCount = capability.exposedVendorRequestKeys.size,
            limitations = limitations(
                capability = capability,
                requestPlan = requestPlan,
                referenceSize = referenceSize,
                rawMax = rawMax,
                largestPublicStream = largestPublicStream,
                truthfulRaw = truthfulRaw,
                vendorGatedFullSensor = vendorGatedFullSensor,
                pixelArrayDownsampled = pixelArrayDownsampled,
                supportsStabilization = supportsStabilization,
                publicPhysicalLensCount = publicPhysicalLensProfiles.size
            ),
            evidence = evidence(
                capability = capability,
                requestPlan = requestPlan,
                rawMax = rawMax,
                yuvMax = yuvMax,
                encoderMax = encoderMax,
                highResYuv = highResYuv,
                videoFullSensor = videoFullSensor,
                eliteProcessedVideoHal = eliteProcessedVideoHal,
                auxiliaryLensLabels = auxiliaryLensLabels
            )
        )
    }

    private fun computeScore(
        capability: CameraCapabilityInventory,
        requestPlan: IspBypassRequestPlan,
        truthfulRaw: Boolean,
        limitedRaw: Boolean,
        fullSensorScore: BypassCapabilityScore,
        rawMax: CameraStreamSize?,
        yuvMax: CameraStreamSize?,
        encoderMax: CameraStreamSize?,
        highResYuv: Boolean,
        videoFullSensor: Boolean,
        supportsStabilization: Boolean,
        vendorGatedFullSensor: Boolean,
        pixelArrayDownsampled: Boolean,
        publicStreamsAvailable: Boolean,
        publicPhysicalLensCount: Int,
        publicPhysicalLensTypeCount: Int
    ): Int {
        var score = when (capability.hardwareLevel) {
            CameraHardwareLevelTier.LEVEL_3 -> 18
            CameraHardwareLevelTier.FULL -> 14
            CameraHardwareLevelTier.EXTERNAL -> 10
            CameraHardwareLevelTier.LIMITED -> 6
            CameraHardwareLevelTier.LEGACY -> 2
            CameraHardwareLevelTier.UNKNOWN -> 0
        }

        score += when {
            truthfulRaw -> 30
            limitedRaw -> 16
            rawMax != null -> 10
            else -> 0
        }
        score += when (fullSensorScore) {
            BypassCapabilityScore.TRUE_ORIGINAL_RAW -> 10
            BypassCapabilityScore.SENSOR_RAW_LIMITED_ISP -> 5
            BypassCapabilityScore.PARTIAL_VIDEO_BYPASS -> 4
            BypassCapabilityScore.VENDOR_REQUIRED -> 0
        }
        if (encoderMax != null) score += 8
        if (yuvMax != null) score += 6
        if (highResYuv) score += 8
        if (videoFullSensor) score += 6
        if (capability.supportsTenBitHdr) score += 8
        if (capability.supportsManualSensor) score += 8
        if (capability.supportsTonemapContrastCurve) score += 4
        score += when {
            requestPlan.complete -> 8
            requestPlan.supportedFeatureCount > 0 -> 4
            else -> 0
        }
        if (capability.supportsPrivateReprocessing) score += 2
        if (capability.supportsYuvReprocessing) score += 2
        if (supportsStabilization) score += 4
        if (capability.maxPipelineDepth >= 6) score += 2
        score += publicPhysicalLensCount.coerceAtMost(4) * 2
        if (publicPhysicalLensTypeCount >= 3) score += 2
        if (capability.exposedVendorRequestKeys.isNotEmpty()) score += 2
        if (!requestPlan.complete) score -= 8
        if (pixelArrayDownsampled) score -= 10
        if (vendorGatedFullSensor) score -= 8
        if (!publicStreamsAvailable) score -= 30

        return score.coerceIn(0, 100)
    }

    private fun isEliteProcessedVideoHal(
        score: Int,
        capability: CameraCapabilityInventory,
        truthfulRaw: Boolean,
        yuvMax: CameraStreamSize?,
        encoderMax: CameraStreamSize?,
        highResYuv: Boolean,
        videoFullSensor: Boolean,
        supportsStabilization: Boolean,
        vendorGatedFullSensor: Boolean,
        pixelArrayDownsampled: Boolean,
        publicStreamsAvailable: Boolean
    ): Boolean {
        val hardwareEligible = capability.hardwareLevel == CameraHardwareLevelTier.LEVEL_3 ||
            capability.hardwareLevel == CameraHardwareLevelTier.FULL
        return !truthfulRaw &&
            score >= 74 &&
            hardwareEligible &&
            publicStreamsAvailable &&
            !vendorGatedFullSensor &&
            !pixelArrayDownsampled &&
            capability.supportsTenBitHdr &&
            capability.supportsManualSensor &&
            supportsStabilization &&
            (highResYuv || videoFullSensor) &&
            (yuvMax != null || encoderMax != null)
    }

    private fun resolveTier(
        score: Int,
        truthfulRaw: Boolean,
        fullSensorScore: BypassCapabilityScore,
        videoFullSensor: Boolean,
        publicStreamsAvailable: Boolean,
        vendorGatedFullSensor: Boolean,
        eliteProcessedVideoHal: Boolean
    ): CameraHalProfileTier =
        when {
            !publicStreamsAvailable -> CameraHalProfileTier.VENDOR_LIMITED
            truthfulRaw && score >= 82 -> CameraHalProfileTier.ULTRA_RAW_CINEMA
            eliteProcessedVideoHal -> CameraHalProfileTier.ELITE_VIDEO_HAL
            score >= 68 &&
                !vendorGatedFullSensor &&
                (fullSensorScore == BypassCapabilityScore.TRUE_ORIGINAL_RAW || videoFullSensor) ->
                CameraHalProfileTier.PRO_CINEMA
            score >= 30 -> CameraHalProfileTier.COMPUTATIONAL_VIDEO
            score >= 20 -> CameraHalProfileTier.BASIC
            else -> CameraHalProfileTier.VENDOR_LIMITED
        }

    private fun limitations(
        capability: CameraCapabilityInventory,
        requestPlan: IspBypassRequestPlan,
        referenceSize: CameraStreamSize?,
        rawMax: CameraStreamSize?,
        largestPublicStream: CameraStreamSize?,
        truthfulRaw: Boolean,
        vendorGatedFullSensor: Boolean,
        pixelArrayDownsampled: Boolean,
        supportsStabilization: Boolean,
        publicPhysicalLensCount: Int
    ): List<String> = buildList {
        if (rawMax == null) {
            add("RAW_SENSOR not exposed by Camera2 HAL")
        } else if (referenceSize != null && !rawMax.covers(referenceSize)) {
            add("RAW_SENSOR ${rawMax.label()} is below active sensor ${referenceSize.label()}")
        }
        if (rawMax != null && !truthfulRaw) {
            if (!capability.supportsManualSensor) add("Manual sensor controls unavailable for true RAW contract")
            if (!requestPlan.complete) add("ISP-off request incomplete: ${requestPlan.unsupportedReasons.joinToString("; ")}")
        }
        if (!capability.supportsTenBitHdr) {
            add("10-bit dynamic range profiles not exposed")
        }
        if (!supportsStabilization) {
            add("OIS/EIS stabilization controls unavailable")
        }
        if (referenceSize != null && largestPublicStream != null && !largestPublicStream.covers(referenceSize)) {
            add("Public Camera2 stream map is below active sensor ${referenceSize.label()}")
        }
        if (pixelArrayDownsampled && capability.pixelArraySize != null && largestPublicStream != null) {
            add(
                "Largest safe stream ${largestPublicStream.label()} is below native pixel array " +
                    capability.pixelArraySize.label()
            )
        }
        if (vendorGatedFullSensor) {
            add("Vendor/private full-sensor stream exists but is not safe until exposed by public HAL stream map")
        }
        if (largestPublicStream == null) {
            add("No public RAW, YUV, or encoder stream exposed")
        }
        if (publicPhysicalLensCount == 0) {
            add("Physical sub-lens IDs not exposed by public Camera2 HAL")
        }
        if (capability.exposedVendorRequestKeys.isNotEmpty() ||
            capability.exposedVendorCharacteristicKeys.isNotEmpty()
        ) {
            add("Vendor keys are discovery-only; no dynamic key injection or OEM whitelist spoofing is used")
        }
    }

    private fun evidence(
        capability: CameraCapabilityInventory,
        requestPlan: IspBypassRequestPlan,
        rawMax: CameraStreamSize?,
        yuvMax: CameraStreamSize?,
        encoderMax: CameraStreamSize?,
        highResYuv: Boolean,
        videoFullSensor: Boolean,
        eliteProcessedVideoHal: Boolean,
        auxiliaryLensLabels: List<String>
    ): List<String> = buildList {
        add("safeHalAccess=publicCamera2Only")
        add("hardware=${capability.hardwareLevel.label}")
        rawMax?.let { add("raw=${it.label()}") }
        yuvMax?.let { add("yuv=${it.label()}") }
        encoderMax?.let { add("encoder=${it.label()}") }
        add("physicalLenses=${capability.physicalLensProfiles.count { it.publicCamera2Id }}")
        if (auxiliaryLensLabels.isNotEmpty()) {
            add("auxiliary=${auxiliaryLensLabels.joinToString(", ")}")
        }
        add("vendorRequestKeys=${capability.exposedVendorRequestKeys.size}")
        add("vendorCharacteristicKeys=${capability.exposedVendorCharacteristicKeys.size}")
        add("ispOffKeys=${requestPlan.supportedFeatureCount}/${requestPlan.totalFeatureCount}")
        add("manual=${capability.supportsManualSensor}")
        add("tenBit=${capability.supportsTenBitHdr}")
        add("stabilization=${capability.supportsOis || capability.supportsVideoStabilization}")
        add("highResYuv=$highResYuv")
        add("videoFullSensor=$videoFullSensor")
        add("eliteProcessedVideoHal=$eliteProcessedVideoHal")
        add("pipelineDepth=${capability.maxPipelineDepth}")
    }

    private fun largest(sizes: List<CameraStreamSize>): CameraStreamSize? =
        sizes.maxWithOrNull(compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height })

    private fun CameraStreamSize.covers(reference: CameraStreamSize): Boolean =
        area.toDouble() >= reference.area.toDouble() * SENSOR_COVERAGE_TOLERANCE
}

private fun CameraStreamSize.label(): String = "${width}x${height}"
