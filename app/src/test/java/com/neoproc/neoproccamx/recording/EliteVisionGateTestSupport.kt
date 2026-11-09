package com.neoproc.neoproccamx.recording

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * s8-f06 — shared, pure-JVM test fixtures for exercising the recording quality gate's Tier-Omega
 * path with REAL measured metrics. The request builders mirror the (private) helpers in
 * RecordingQualityEvidencePolicyTest; the golden builders mirror EliteVisionMeasurementHarnessTest.
 * Reused by the f06 real-frame-feed / gate-consumption / absent-decode tests (and available to f07).
 */
internal object EliteVisionGateTestSupport {

    /** A known-good NDR request (PASSes the gate) — the canonical baseline for Tier-Omega tests. */
    fun validNdrRequest(): RecordingQualityEvidenceRequest =
        baseRequest(
            mode = EliteVideoPipelineMode.HDR,
            selectedProfile = "NDR10+",
            activeTransport = "BT.2100_PQ_MAIN10",
            transfer = "ST2084/PQ",
            primaries = "BT.2020",
            matrix = "BT.2020-NCL",
            encodedBitDepth = 10,
            internalMathBitDepth = 12,
            sourceBitDepth = 10,
            storageBitDepth = 10,
            normalizedHdrVersion = "NDR10+",
            captureTruthSignature = "HDR_PQ|HDR_10BIT|Rec.2020-NDR10+|hdr=NDR10+|tenBit=true|log=false|raw=false|hdrMetadata=true",
            previewQualitySignature = "HDR|CAMERA2_PREVIEW_PARITY_RECORD_SURFACE|Rec.2020-NDR10+|HDR_CLEAN|HDR_HIGHLIGHT_SAFE|USER_MANUAL_COLOR",
            recordingQualitySignature = "HDR|CAMERA2_PREVIEW_PARITY_RECORD_SURFACE|Rec.2020-NDR10+|HDR_CLEAN|HDR_HIGHLIGHT_SAFE|USER_MANUAL_COLOR",
            probe = ndrProbe()
        )

    /** A known-good NPV10 private-master request (PASSes the gate) for transport-truth fixtures. */
    fun validNpv10Request(): RecordingQualityEvidenceRequest =
        baseRequest(
            mode = EliteVideoPipelineMode.NPV10,
            selectedProfile = "N-Log Cinema",
            activeTransport = "NPV10_PRIVATE_MASTER",
            transfer = "N-Log",
            primaries = "BT.2020",
            matrix = "YUV420",
            encodedBitDepth = 10,
            internalMathBitDepth = 10,
            sourceBitDepth = 10,
            storageBitDepth = 10,
            npv10PayloadFormat = "P010_LE",
            npv10NativeTenBitClaimed = true,
            npv10MasterAvailable = true,
            captureTruthSignature = "NPV10|PRIVATE_MASTER|Rec.2020-N-Log|hdr=OFF|tenBit=true|log=true|raw=false|hdrMetadata=false",
            previewQualitySignature = "NPV10|PRIVATE_MASTER|Rec.2020-N-Log|LOG_DETAIL|LOG_EDGE_PRESERVE|USER_MANUAL_COLOR",
            recordingQualitySignature = "NPV10|PRIVATE_MASTER|Rec.2020-N-Log|LOG_DETAIL|LOG_EDGE_PRESERVE|USER_MANUAL_COLOR",
            probe = null,
            ffprobeValidated = false,
            mediaInfoValidated = true
        )

    fun tierOmegaPassingMetadata(): Map<String, String> =
        mapOf(
            "tierOmegaLumaSnrBaseDb" to "55.000000",
            "tierOmegaLumaSnrHighIsoDb" to "44.000000",
            "tierOmegaChromaNoiseBaseStd" to "0.200000",
            "tierOmegaChromaNoiseHighIsoStd" to "0.500000",
            "tierOmegaTemporalFlickerPercent" to "0.400000",
            "tierOmegaMtf50CyPerPx" to "0.390000",
            "tierOmegaOvershootPercent" to "1.800000",
            "tierOmegaDetailRetentionRaw" to "0.970000",
            "tierOmegaDetailRetentionHighIsoRaw" to "0.940000",
            "tierOmegaDeltaE2000Mean" to "0.800000",
            "tierOmegaDeltaE2000Max" to "1.800000",
            "tierOmegaGrayDeltaAAbs" to "0.500000",
            "tierOmegaGrayDeltaBAbs" to "0.550000",
            "tierOmegaBandingScore" to "0.000000",
            "tierOmegaDeadHotPixels" to "0",
            "tierOmegaWbApplied" to "true",
            "tierOmegaCcmNonIdentity" to "true",
            "tierOmegaPreviewRecordExportParity" to "true",
            "strictVulkanParityCertified" to "true",
            "camera2FrameLineageCertified" to "true",
            "encoderTransportLevelProven" to "true",
            "encoderTransportAvSyncWithinBound" to "true",
            EliteVisionMeasurementHarness.TIER_OMEGA_METRICS_SOURCE_KEY to
                EliteVisionMeasurementHarness.SOURCE_REAL_PIXEL
        )

    fun ndrProbe(
        bitrateBps: Int = 460_000_000,
        width: Int = 1920,
        height: Int = 1080
    ): RecordingProbeMetadata =
        RecordingProbeMetadata(
            codec = "hevc",
            pixelFormat = "yuv420p10le",
            encodedBitDepth = 10,
            primaries = "BT.2020",
            transfer = "ST2084/PQ",
            matrix = "BT.2020-NCL",
            colorRange = "LIMITED",
            width = width,
            height = height,
            fps = 30,
            bitrateBps = bitrateBps
        )

    private fun baseRequest(
        mode: EliteVideoPipelineMode,
        selectedProfile: String,
        activeTransport: String,
        transfer: String,
        primaries: String,
        matrix: String,
        encodedBitDepth: Int,
        internalMathBitDepth: Int,
        sourceBitDepth: Int = encodedBitDepth,
        storageBitDepth: Int = encodedBitDepth,
        normalizedHdrVersion: String = "OFF",
        normalizedLogVersion: String = "N-Log Base",
        captureTruthSignature: String,
        previewQualitySignature: String,
        recordingQualitySignature: String,
        probe: RecordingProbeMetadata?,
        ffprobeValidated: Boolean = true,
        mediaInfoValidated: Boolean = true,
        npv10PayloadFormat: String = "",
        npv10NativeTenBitClaimed: Boolean = false,
        npv10MasterAvailable: Boolean = false
    ): RecordingQualityEvidenceRequest =
        RecordingQualityEvidenceRequest(
            mode = mode,
            selectedProfile = selectedProfile,
            activeTransport = activeTransport,
            transfer = transfer,
            primaries = primaries,
            matrix = matrix,
            codec = "hevc",
            encodedBitDepth = encodedBitDepth,
            internalMathBitDepth = internalMathBitDepth,
            sourceBitDepth = sourceBitDepth,
            storageBitDepth = storageBitDepth,
            width = 1920,
            height = 1080,
            fps = 30,
            bitrateBps = 460_000_000,
            selectedResolution = "1920x1080",
            actualResolution = "1920x1080",
            normalizedHdrVersion = normalizedHdrVersion,
            normalizedLogVersion = normalizedLogVersion,
            rawSensorOriginal = mode == EliteVideoPipelineMode.RAW,
            captureTruthSignature = captureTruthSignature,
            previewQualitySignature = previewQualitySignature,
            recordingQualitySignature = recordingQualitySignature,
            expectedFrameCount = 30,
            actualFrameCount = 30,
            droppedFrames = 0,
            maxAllowedDroppedFrames = 0,
            probe = probe,
            ffprobeValidated = ffprobeValidated,
            mediaInfoValidated = mediaInfoValidated,
            deviceLogValidated = true,
            npv10PayloadFormat = npv10PayloadFormat,
            npv10NativeTenBitClaimed = npv10NativeTenBitClaimed,
            npv10MasterAvailable = npv10MasterAvailable,
            detailMetadata = tierOmegaPassingMetadata(),
            enhancementUniformSignature = "gpu-v1|signal=${mode.name}|space=$primaries|science=$selectedProfile|packet=active",
            previewEnhancementUniformSignature = "gpu-v1|signal=${mode.name}|space=$primaries|science=$selectedProfile|packet=active",
            recordingEnhancementUniformSignature = "gpu-v1|signal=${mode.name}|space=$primaries|science=$selectedProfile|packet=active",
            enhancementTemporalBlend = 0.42f,
            enhancementSpatialDenoise = 0.73f,
            enhancementChromaDenoise = 0.96f,
            enhancementSharpening = 0.31f,
            enhancementSubjectDetailGain = 0.18f,
            enhancementDetailProfile = "FOCUSED_DETAIL",
            gpuProcessingValidated = mode != EliteVideoPipelineMode.NPV10 &&
                mode != EliteVideoPipelineMode.RAW,
            gpuProcessingPath = if (mode == EliteVideoPipelineMode.NPV10 || mode == EliteVideoPipelineMode.RAW) {
                "PRIVATE_MASTER"
            } else {
                "VULKAN_PREVIEW_RECORD_EXPORT"
            },
            processedRecordingFramesValidated = mode != EliteVideoPipelineMode.NPV10 &&
                mode != EliteVideoPipelineMode.RAW
        )

    // ---- deterministic golden builders (mirror EliteVisionMeasurementHarnessTest) ----

    /** A clean golden set with a sharp edge (sigma 0.42) → MTF50 clears the floor; all keys present. */
    fun goodGoldens(): EliteVisionMeasurementHarness.GoldenFrames {
        val (edge, w, h) = slantedEdge(64, 64, edge0 = 32.0, slope = 0.06, sigma = 0.42)
        return EliteVisionMeasurementHarness.GoldenFrames(
            edgeLuma = edge,
            edgeWidth = w,
            edgeHeight = h,
            referenceRawMtf50CyPerPx = 0.38,
            baseIsoFlatLuma = flatField(mean = 0.5f, sigma = 0.0012f, n = 4096),
            highIsoFlatLuma = flatField(mean = 0.5f, sigma = 0.004f, n = 4096),
            colorCheckerMeasuredLab = colorChecker(perturbation = 0.4f),
            colorCheckerReferenceLab = colorChecker(perturbation = 0f),
            grayPatchLab = floatArrayOf(50f, 0.4f, 0.5f),
            temporalSequence = staticSequence(jitter = 0.005f, n = 1024)
        )
    }

    /** A clean golden set EXCEPT for a blurred edge (sigma 2.2) → MTF50 falls below the gate floor. */
    fun blurredEdgeGoldens(): EliteVisionMeasurementHarness.GoldenFrames {
        val (edge, w, h) = slantedEdge(64, 64, edge0 = 32.0, slope = 0.06, sigma = 2.2)
        return EliteVisionMeasurementHarness.GoldenFrames(
            edgeLuma = edge,
            edgeWidth = w,
            edgeHeight = h,
            referenceRawMtf50CyPerPx = 0.38,
            baseIsoFlatLuma = flatField(mean = 0.5f, sigma = 0.0012f, n = 4096),
            highIsoFlatLuma = flatField(mean = 0.5f, sigma = 0.004f, n = 4096),
            colorCheckerMeasuredLab = colorChecker(perturbation = 0.4f),
            colorCheckerReferenceLab = colorChecker(perturbation = 0f),
            grayPatchLab = floatArrayOf(50f, 0.4f, 0.5f),
            temporalSequence = staticSequence(jitter = 0.005f, n = 1024)
        )
    }

    fun passingBaseline(): EliteVisionQualityMeasurements =
        EliteVisionQualityMeasurements(
            hueDeltaE2000Max = 0.42,
            skinDeltaE2000Max = 1.6,
            specularChromaAtClip = 0.18,
            skyHueDriftDegrees = 0.7,
            lookRoundTripDeltaE2000Max = 0.62,
            gamutBoundaryOvershoot = 0.0,
            lightnessDriftMax = 0.0,
            noiseSigmaIreBase = 0.32,
            noiseSigmaIreIso3200 = 0.65,
            mtf50Retention = 0.94,
            acutanceGain = 0.28,
            haloOvershoot = 0.018,
            bandingDeltaCodeMax = 0.0,
            metadataBitrateRatio = 0.0012,
            tmbSignatureVerified = true,
            fallbackEvidenceHonest = true,
            nlogsRoundTripDeltaE2000Max = 0.22,
            deterministicHashMatch = true,
            lumaSnrBaseDb = 55.0,
            lumaSnrHighIsoDb = 44.0,
            chromaNoiseStd = 0.20,
            temporalFlicker = 0.004,
            mtf50CyPerPx = 0.39,
            detailRetentionVsRaw = 0.97,
            colorCheckerDeltaE2000Mean = 0.8,
            colorCheckerDeltaE2000Max = 1.8,
            grayDeltaAAbs = 0.5,
            grayDeltaBAbs = 0.55,
            deadHotPixels = 0,
            redClipBlueShadowSafe = true,
            previewRecordExportParity = true,
            wbApplied = true,
            ccmNonIdentity = true,
            vendorProfileFree = true
        )

    private fun flatField(mean: Float, sigma: Float, n: Int): FloatArray =
        FloatArray(n) { if (it % 2 == 0) mean + sigma else mean - sigma }

    private fun staticSequence(jitter: Float, n: Int): List<FloatArray> {
        val base = FloatArray(n) { 0.4f + 0.2f * (it.toFloat() / n) }
        return listOf(
            FloatArray(n) { base[it] + jitter },
            FloatArray(n) { base[it] - jitter }
        )
    }

    private fun colorChecker(perturbation: Float): List<FloatArray> = listOf(
        floatArrayOf(38f, 14f + perturbation, 15f),
        floatArrayOf(66f, 13f, 17f + perturbation),
        floatArrayOf(51f, -10f - perturbation, -23f),
        floatArrayOf(43f, -16f, 22f + perturbation),
        floatArrayOf(56f, 9f + perturbation, -25f),
        floatArrayOf(72f, -23f, 58f - perturbation)
    )

    private fun slantedEdge(
        width: Int,
        height: Int,
        edge0: Double,
        slope: Double,
        sigma: Double
    ): Triple<FloatArray, Int, Int> {
        val img = FloatArray(width * height)
        val s = sigma * sqrt(2.0)
        for (y in 0 until height) {
            val edge = edge0 + slope * y
            for (x in 0 until width) {
                img[y * width + x] = (0.5 * (1.0 + erf((x - edge) / s))).toFloat()
            }
        }
        return Triple(img, width, height)
    }

    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return sign(x) * y
    }
}
