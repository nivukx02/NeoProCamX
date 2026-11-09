package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.BeyondImaxEliteProfile
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics
import com.neoproc.neoproccamx.quality.OmegaCapabilityGate
import com.neoproc.neoproccamx.quality.OmegaCapabilityId
import com.neoproc.neoproccamx.quality.OmegaCapabilityInput
import com.neoproc.neoproccamx.quality.OmegaQualityTruthReport
import java.util.Locale

internal enum class RecordingQualityEvidenceStatus {
    PASS,
    FAIL,
    NOT_PROVEN
}

internal enum class RecordingQualityGateCode {
    MISSING_RECORDED_FILE_PROOF,
    MISSING_DEVICE_LOG_PROOF,
    RECORDED_FILE_EMPTY,
    ADB_RUNTIME_ERROR,
    NORMAL_OR_PRO_SAVED_AS_HDR,
    HDR_TRANSPORT_NOT_10BIT_BT2020,
    LOG_SAVED_AS_HDR,
    LOG_TRANSPORT_NOT_10BIT_SDR,
    RAW_NOT_RAW_SENSOR,
    NPV10_FALLBACK_MISLABELED_NATIVE_10BIT,
    NPV10_NATIVE_10BIT_NOT_PROVEN,
    NPV10_MASTER_NOT_AVAILABLE,
    PREVIEW_RECORDING_PARITY_MISMATCH,
    RESOLUTION_MISMATCH,
    FPS_MISMATCH,
    FRAME_DROP_LIMIT_EXCEEDED,
    FRAME_COUNT_INVALID,
    CODEC_MISMATCH,
    BIT_DEPTH_MISMATCH,
    COLOR_PRIMARIES_MISMATCH,
    TRANSFER_MISMATCH,
    MATRIX_MISMATCH,
    ORIENTATION_MISMATCH,
    MIRRORED_OUTPUT_DETECTED,
    CHROMA_CHANNELS_MISSING,
    GPU_PROCESSING_NOT_PROVEN,
    PROCESSED_RECORDING_NOT_PROVEN,
    ZERO_COPY_CLAIM_NOT_PROVEN,
    REALTIME_PERFORMANCE_NOT_PROVEN,
    CPU_BUDGET_EXCEEDED,
    FRAME_TIME_BUDGET_EXCEEDED,
    PREVIEW_RECORDING_ENHANCEMENT_MISMATCH,
    RECORDING_ENHANCEMENT_NOT_PROVEN,
    RECORDING_ENHANCEMENT_IDENTITY,
    NORMAL_PRO_SDR_ENHANCEMENT_LOCK_NOT_PROVEN,
    TIER_OMEGA_METRICS_NOT_PROVEN,
    TIER_OMEGA_METRIC_THRESHOLD_FAILED,
    BITRATE_MEASUREMENT_NOT_PROVEN,
    BITRATE_FLOOR_NOT_MET,
    MANUAL_BITRATE_CAPPED_BELOW_QUALITY_FLOOR,
    STRICT_VULKAN_PARITY_NOT_CERTIFIED,
    CAMERA2_FRAME_LINEAGE_NOT_CERTIFIED,
    ENCODER_TRANSPORT_LEVEL_NOT_PROVEN,
    ENCODER_TRANSPORT_AV_SYNC_NOT_PROVEN,
    OMEGA_UNSUPPORTED_FEATURE_CLAIMED
}

internal data class RecordingQualityGateFailure(
    val code: RecordingQualityGateCode,
    val message: String,
    val blocking: Boolean = true
)

internal data class RecordingQualityEvidenceRequest(
    val mode: EliteVideoPipelineMode,
    val selectedProfile: String,
    val activeTransport: String,
    val transfer: String,
    val primaries: String,
    val matrix: String,
    val codec: String,
    val encodedBitDepth: Int,
    val internalMathBitDepth: Int,
    val sourceBitDepth: Int,
    val storageBitDepth: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val requestedBitrateBps: Int = 0,
    val policyTargetBitrateBps: Int = 0,
    val appliedBitrateBps: Int = 0,
    val measuredOutputBitrateBps: Int = 0,
    val encoderCapabilityUpperBoundBps: Int? = null,
    val manualBitrateSelectionEnabled: Boolean = false,
    val selectedResolution: String,
    val actualResolution: String,
    val normalizedHdrVersion: String,
    val normalizedLogVersion: String,
    val expectedRotationDegrees: Int = 0,
    val expectedMirrored: Boolean = false,
    val rawSensorOriginal: Boolean,
    val captureTruthSignature: String,
    val previewQualitySignature: String,
    val recordingQualitySignature: String,
    val expectedFrameCount: Long,
    val actualFrameCount: Long,
    val droppedFrames: Int,
    val maxAllowedDroppedFrames: Int,
    val probe: RecordingProbeMetadata?,
    val ffprobeValidated: Boolean,
    val mediaInfoValidated: Boolean,
    val deviceLogValidated: Boolean,
    val npv10PayloadFormat: String = "",
    val npv10NativeTenBitClaimed: Boolean = false,
    val npv10MasterAvailable: Boolean = false,
    val recordingId: String = "",
    val recordedFileName: String = "",
    val recordedFileBytes: Long = 1L,
    val ffprobeEvidencePath: String = "",
    val mediaInfoEvidencePath: String = "",
    val adbLogEvidencePath: String = "",
    val adbFatalErrorCount: Int = 0,
    val adbCodecErrorCount: Int = 0,
    val mediaStoreValidated: Boolean = true,
    val noiseMetrics: VideoNoiseMetrics = VideoNoiseMetrics.EMPTY,
    val detailMetadata: Map<String, String> = emptyMap(),
    val encoderCapabilityCheck: String = "",
    val encoderSceneComplexity: Float = 0f,
    val encoderEstimatedLux: Float? = null,
    val encoderBitrateMultiplier: Float = 1f,
    val encoderSceneComplexityBoostApplied: Boolean = false,
    val encoderLowLightBoostApplied: Boolean = false,
    val encoderHdrOrLogBoostApplied: Boolean = false,
    val encoderThermalDowngradeActive: Boolean = false,
    val allGpuProcessingClaimed: Boolean = false,
    val zeroCopyProcessingClaimed: Boolean = false,
    val gpuProcessingValidated: Boolean = false,
    val processedRecordingFramesValidated: Boolean = false,
    val zeroCopyValidated: Boolean = false,
    val performanceTelemetryValidated: Boolean = false,
    val gpuProcessingPath: String = "",
    val zeroCopyEvidencePath: String = "",
    val enhancementUniformSignature: String = "",
    val previewEnhancementUniformSignature: String = "",
    val recordingEnhancementUniformSignature: String = "",
    val enhancementTemporalBlend: Float = 0f,
    val enhancementSpatialDenoise: Float = 0f,
    val enhancementChromaDenoise: Float = 0f,
    val enhancementSharpening: Float = 0f,
    val enhancementSubjectDetailGain: Float = 0f,
    val enhancementDetailProfile: String = "",
    val cpuUsagePercent: Float? = null,
    val maxFrameTimeMs: Float? = null,
    val targetCpuUsagePercent: Float = 10f,
    val omegaClaimedFeatures: Set<OmegaCapabilityId> = emptySet(),
    /**
     * s1-f05: set by the camera layer when a requested 10-bit Neo dynamic-range mode silently
     * negotiated an 8-bit transport on capable hardware. Forwarded into the Omega gate so an 8-bit
     * master can never be certified as a 10-bit transport. Default false = today's behavior.
     */
    val dynamicRangeSilentFallback: Boolean = false,
    val generatedAtEpochMs: Long = 0L
)

internal data class RecordingQualityEvidenceCertificate(
    val status: RecordingQualityEvidenceStatus,
    val failures: List<RecordingQualityGateFailure>,
    val request: RecordingQualityEvidenceRequest,
    val metadata: Map<String, String>
) {
    fun toCertificateText(): String {
        val failureLines = failures.mapIndexed { index, failure ->
            "failure[$index]=${failure.code.name}|blocking=${failure.blocking}|${failure.message}"
        }
        val metadataLines = metadata.entries
            .sortedBy { it.key }
            .map { (key, value) -> "$key=$value" }
        return (metadataLines + failureLines).joinToString(separator = "\n", postfix = "\n")
    }
}

internal object RecordingQualityEvidencePolicy {
    const val GENERATION = "V15_STRICT_QUALITY_EVIDENCE_GATE"
    const val V10_GENERATION = "V10_EVIDENCE_CALIBRATED_GATE"
    private const val ADAPTIVE_SDR_PACKET_TOLERANCE = 0.0002f
    private const val NOISE_MODEL_V6 = "MOTION_AWARE_TEMPORAL_LUMA_CHROMA_V6"
    private const val CHROMA_MODEL_V6 = "SHADOW_CHROMA_LOCK_SKIN_TEXTURE_GUARD_V6"
    private const val DETAIL_MODEL_V6 = "EDGE_CONFIDENCE_ANTI_HALO_DETAIL_V6"
    private const val SENSOR_MODEL_V6 = "GENERIC_SENSOR_PROFILE_WITH_DEVICE_OVERRIDE_V6"
    private const val ENCODER_MODEL_V6 = "SCENE_COMPLEXITY_SUSTAINED_BITRATE_GUARD_V6"
    // Process.getElapsedCpuTime() is cumulative across cores: 200% is a hard two-logical-core
    // ceiling, not an unlimited/device-total percentage. Real I2405 4K25 Main10 profiling places
    // the zero-copy camera + render + codec path around 1.75 cores while sustaining every frame.
    private const val STRICT_4K25_PROCESS_CPU_TARGET_PERCENT = 200f
    private val ADAPTIVE_SDR_PACKET_RANGES = mapOf(
        4 to 0f..1f,
        5 to 0f..1f,
        6 to 0f..1f,
        7 to 0f..1f,
        8 to 0f..1f,
        9 to -1f..1f,
        10 to 0f..2f,
        11 to 0f..2f,
        12 to 0f..1f,
        26 to 0f..1f,
        27 to 0f..1f,
        28 to 0f..1f,
        29 to 0f..1f,
        30 to 0f..1f
    )

    fun unverifiedRequirementMetadata(): Map<String, String> = mapOf(
        "qualityEvidenceGeneration" to GENERATION,
        "v10QualityEvidenceGeneration" to V10_GENERATION,
        "qualityEvidenceStatus" to "NOT_PROVEN_WITHOUT_RECORDED_FILE_PROBE_AND_DEVICE_LOGS",
        "v10EvidenceCalibratedGateApplied" to "true",
        "v15QualityEvidenceGateApplied" to "true",
        "strict10QualityRequiresV10Evidence" to "true",
        "strict10QualityRequiresV15Evidence" to "true",
        "strict10QualityRequiresV6Evidence" to "true",
        "strict10QualityProven" to "false",
        "strict10DeviceVerified" to "false",
        "tierOmegaEvidenceGateApplied" to "true",
        "tierOmegaQualityProven" to "false",
        "tierOmegaProfile" to BeyondImaxEliteProfile.PROFILE_NAME,
        "beyondImaxQualityProfile" to BeyondImaxEliteProfile.PROFILE_NAME,
        "beyondImaxBaselineProfile" to BeyondImaxEliteProfile.BASELINE_PROFILE_NAME,
        "beyondImaxEvidenceGateApplied" to "true",
        "beyondImaxQualityProven" to "false",
        "tierOmegaMinLumaSnrBaseDb" to BeyondImaxEliteProfile.MIN_LUMA_SNR_BASE_DB.formatDouble(),
        "tierOmegaMinLumaSnrHighIsoDb" to BeyondImaxEliteProfile.MIN_LUMA_SNR_HIGH_ISO_DB.formatDouble(),
        "tierOmegaMaxChromaNoiseBaseStd" to BeyondImaxEliteProfile.MAX_CHROMA_NOISE_BASE_STD.formatDouble(),
        "tierOmegaMaxChromaNoiseHighIsoStd" to BeyondImaxEliteProfile.MAX_CHROMA_NOISE_HIGH_ISO_STD.formatDouble(),
        "tierOmegaMaxTemporalFlickerPercent" to BeyondImaxEliteProfile.MAX_TEMPORAL_FLICKER_PERCENT.formatDouble(),
        "tierOmegaMinMtf50CyPerPx" to BeyondImaxEliteProfile.MIN_MTF50_CY_PER_PX.formatDouble(),
        "noiseModelV6" to NOISE_MODEL_V6,
        "chromaNoiseModelV6" to CHROMA_MODEL_V6,
        "detailModelV6" to DETAIL_MODEL_V6,
        "sensorCalibrationModelV6" to SENSOR_MODEL_V6,
        "encoderQualityModelV6" to ENCODER_MODEL_V6,
        "nativeOutputResolutionFaked" to "false"
    )

    fun evaluate(request: RecordingQualityEvidenceRequest): RecordingQualityEvidenceCertificate {
        val omegaReport = OmegaCapabilityGate.evaluate(request.toOmegaCapabilityInput())
        val failures = buildList {
            addProofFailures(request)
            addParityFailures(request)
            addFrameFailures(request)
            addModeTruthFailures(request)
            addProbeFailures(request)
            addRuntimeProofFailures(request)
            addEliteDeliveryEvidenceFailures(request)
            addTierOmegaQualityFailures(request)
            addOmegaCapabilityFailures(omegaReport)
        }
        val status = when {
            failures.any { it.blocking } -> RecordingQualityEvidenceStatus.FAIL
            failures.isNotEmpty() -> RecordingQualityEvidenceStatus.NOT_PROVEN
            else -> RecordingQualityEvidenceStatus.PASS
        }
        return RecordingQualityEvidenceCertificate(
            status = status,
            failures = failures,
            request = request,
            metadata = metadataFor(request, status, failures, omegaReport)
        )
    }

    private fun MutableList<RecordingQualityGateFailure>.addProofFailures(
        request: RecordingQualityEvidenceRequest
    ) {
        if (request.recordedFileBytes <= 0L) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.RECORDED_FILE_EMPTY,
                    message = "Recorded-file evidence must prove a non-empty output file."
                )
            )
        }
        val hasFinalizedOutputProof =
            request.mediaStoreValidated &&
                request.recordedFileBytes > 1L &&
                request.recordedFileName.isNotBlank()
        val hasFileProof = hasFinalizedOutputProof ||
            (request.probe != null && (request.ffprobeValidated || request.mediaInfoValidated)) ||
            (request.mode == EliteVideoPipelineMode.NPV10 && request.mediaInfoValidated && request.npv10MasterAvailable)
        if (!hasFileProof) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.MISSING_RECORDED_FILE_PROOF,
                    message = "Recorded-file probe or private-master evidence is required before strict 10/10 can be proven.",
                    blocking = false
                )
            )
        }
        if (!request.deviceLogValidated) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.MISSING_DEVICE_LOG_PROOF,
                    message = "ADB log evidence is required before strict 10/10 can be proven.",
                    blocking = false
                )
            )
        }
        if (request.adbFatalErrorCount > 0 || request.adbCodecErrorCount > 0) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.ADB_RUNTIME_ERROR,
                    message = "ADB log evidence contains fatal/runtime or codec errors " +
                        "(fatal=${request.adbFatalErrorCount}, codec=${request.adbCodecErrorCount})."
                )
            )
        }
    }

    private fun MutableList<RecordingQualityGateFailure>.addParityFailures(
        request: RecordingQualityEvidenceRequest
    ) {
        if (request.previewQualitySignature.isBlank() ||
            request.recordingQualitySignature.isBlank() ||
            (
                request.previewQualitySignature != request.recordingQualitySignature &&
                    !request.hasAcceptablePreviewRecordingParityCertificate()
                )
        ) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.PREVIEW_RECORDING_PARITY_MISMATCH,
                    message = "Preview and recording quality signatures must match exactly unless a parity certificate proves endpoint equivalence or explicitly marks the difference non-blocking."
                )
            )
        }
    }

    private fun MutableList<RecordingQualityGateFailure>.addFrameFailures(
        request: RecordingQualityEvidenceRequest
    ) {
        if (request.selectedResolution != request.actualResolution ||
            request.actualResolution != "${request.width}x${request.height}"
        ) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.RESOLUTION_MISMATCH,
                    message = "Selected resolution ${request.selectedResolution} does not match actual ${request.actualResolution}."
                )
            )
        }
        if (request.actualFrameCount <= 0L || request.actualFrameCount < request.expectedFrameCount) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.FRAME_COUNT_INVALID,
                    message = "Recorded frame count ${request.actualFrameCount} is below expected ${request.expectedFrameCount}."
                )
            )
        }
        if (request.droppedFrames > request.maxAllowedDroppedFrames) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.FRAME_DROP_LIMIT_EXCEEDED,
                    message = "Dropped frames ${request.droppedFrames} exceed limit ${request.maxAllowedDroppedFrames}."
                )
            )
        }
    }

    private fun MutableList<RecordingQualityGateFailure>.addModeTruthFailures(
        request: RecordingQualityEvidenceRequest
    ) {
        when (request.mode) {
            EliteVideoPipelineMode.NORMAL,
            EliteVideoPipelineMode.PRO -> {
                if (request.transfer.isHdrTransfer() || request.activeTransport.contains("PQ", ignoreCase = true)) {
                    add(
                        RecordingQualityGateFailure(
                            code = RecordingQualityGateCode.NORMAL_OR_PRO_SAVED_AS_HDR,
                            message = "Normal/Pro video must remain SDR unless HDR/NDR/NIVUKX is selected."
                        )
                    )
                }
                if (request.encodedBitDepth < 10 ||
                    request.sourceBitDepth < 10 ||
                    request.storageBitDepth < 10 ||
                    !request.activeTransport.isMain10OrMain12Transport()
                ) {
                    add(
                        RecordingQualityGateFailure(
                            code = RecordingQualityGateCode.BIT_DEPTH_MISMATCH,
                            message = "Normal/Pro SDR must prove real 10-bit source, storage, and encoded SDR transport."
                        )
                    )
                }
            }
            EliteVideoPipelineMode.HDR -> {
                val bt2100PqRequired = request.requiresNeoBt2100Pq()
                val hdrTransportValid =
                    if (bt2100PqRequired) {
                        Bt2100TransportPolicy.isBt2100PqEvidence(
                            activeTransport = request.activeTransport,
                            transfer = request.transfer,
                            primaries = request.primaries,
                            matrix = request.matrix
                        ) && request.activeTransport.isMain10OrMain12Transport()
                    } else {
                        request.primaries == "BT.2020" &&
                            request.transfer.isHdrTransfer() &&
                            request.activeTransport.isMain10OrMain12Transport()
                    }
                if (request.encodedBitDepth < 10 ||
                    request.sourceBitDepth < 10 ||
                    request.storageBitDepth < 10 ||
                    !hdrTransportValid ||
                    request.normalizedHdrVersion == "OFF"
                ) {
                    add(
                        RecordingQualityGateFailure(
                            code = RecordingQualityGateCode.HDR_TRANSPORT_NOT_10BIT_BT2020,
                            message = "NDR10+/NIVUKX must use BT.2100 PQ evidence: " +
                                "BT.2020 primaries, ST2084/PQ transfer, BT.2020-NCL matrix, and Main10/Main12."
                        )
                    )
                }
            }
            EliteVideoPipelineMode.LOG -> {
                if (request.transfer.isHdrTransfer()) {
                    add(
                        RecordingQualityGateFailure(
                            code = RecordingQualityGateCode.LOG_SAVED_AS_HDR,
                            message = "Log video must be Log SDR, not HDR-tagged video."
                        )
                    )
                }
                if (request.encodedBitDepth < 10 ||
                    request.sourceBitDepth < 10 ||
                    request.storageBitDepth < 10 ||
                    !request.normalizedLogVersion.startsWith("N-Log") ||
                    request.primaries != "BT.2020"
                ) {
                    add(
                        RecordingQualityGateFailure(
                            code = RecordingQualityGateCode.LOG_TRANSPORT_NOT_10BIT_SDR,
                            message = "N-Log requires 10-bit wide-gamut SDR transport."
                        )
                    )
                }
            }
            EliteVideoPipelineMode.RAW -> {
                if (!request.rawSensorOriginal || request.sourceBitDepth < 12 || request.storageBitDepth < 12) {
                    add(
                        RecordingQualityGateFailure(
                            code = RecordingQualityGateCode.RAW_NOT_RAW_SENSOR,
                            message = "RAW mode must be original RAW_SENSOR data with at least 12-bit storage."
                        )
                    )
                }
            }
            EliteVideoPipelineMode.NPV10 -> addNpv10Failures(request)
        }
    }

    private fun MutableList<RecordingQualityGateFailure>.addNpv10Failures(
        request: RecordingQualityEvidenceRequest
    ) {
        if (!request.npv10MasterAvailable) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.NPV10_MASTER_NOT_AVAILABLE,
                    message = "NPV10 private master evidence is required."
                )
            )
        }
        when (request.npv10PayloadFormat) {
            "YUV420_8BIT_EXPANDED" -> {
                if (request.npv10NativeTenBitClaimed || request.sourceBitDepth > 8) {
                    add(
                        RecordingQualityGateFailure(
                            code = RecordingQualityGateCode.NPV10_FALLBACK_MISLABELED_NATIVE_10BIT,
                            message = "8-bit expanded NPV10 fallback must not be labeled native 10-bit."
                        )
                    )
                }
            }
            "P010_LE",
            "YUV420P10_LE" -> {
                if (!request.npv10NativeTenBitClaimed || request.sourceBitDepth < 10 || request.storageBitDepth < 10) {
                    add(
                        RecordingQualityGateFailure(
                            code = RecordingQualityGateCode.NPV10_NATIVE_10BIT_NOT_PROVEN,
                            message = "Native NPV10 requires 10-bit source, 10-bit storage, and native-10-bit evidence."
                        )
                    )
                }
            }
            else -> {
                add(
                    RecordingQualityGateFailure(
                        code = RecordingQualityGateCode.NPV10_NATIVE_10BIT_NOT_PROVEN,
                        message = "NPV10 payload format must be explicit."
                    )
                )
            }
        }
        if (request.requiresNeoBt2100Pq() &&
            !Bt2100TransportPolicy.isBt2100PqEvidence(
                activeTransport = request.activeTransport,
                transfer = request.transfer,
                primaries = request.primaries,
                matrix = request.matrix
            )
        ) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.HDR_TRANSPORT_NOT_10BIT_BT2020,
                    message = "NDR10+/NIVUKX NPV10 private masters must carry BT.2100 PQ evidence: " +
                        "BT.2020 primaries, ST2084/PQ transfer, BT.2020-NCL matrix, and BT.2100 PQ transport."
                )
            )
        }
    }

    private fun MutableList<RecordingQualityGateFailure>.addProbeFailures(
        request: RecordingQualityEvidenceRequest
    ) {
        val probe = request.probe ?: return
        if (probe.codec.isNotBlank() && !request.codec.equals(probe.codec, ignoreCase = true)) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.CODEC_MISMATCH,
                    message = "Probe codec ${probe.codec} does not match requested ${request.codec}."
                )
            )
        }
        if (probe.width != request.width || probe.height != request.height) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.RESOLUTION_MISMATCH,
                    message = "Probe resolution ${probe.width}x${probe.height} does not match ${request.width}x${request.height}."
                )
            )
        }
        if (probe.fps != 0 && probe.fps != request.fps) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.FPS_MISMATCH,
                    message = "Probe fps ${probe.fps} does not match requested ${request.fps}."
                )
            )
        }
        if (probe.encodedBitDepth < request.encodedBitDepth) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.BIT_DEPTH_MISMATCH,
                    message = "Probe bit depth ${probe.encodedBitDepth} is below expected ${request.encodedBitDepth}."
                )
            )
        }
        if (probe.primaries != request.primaries) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.COLOR_PRIMARIES_MISMATCH,
                    message = "Probe primaries ${probe.primaries} do not match ${request.primaries}."
                )
            )
        }
        if (!transferMatches(request, probe)) {
            add(
                RecordingQualityGateFailure(
                    code = if (request.mode == EliteVideoPipelineMode.LOG && probe.transfer.isHdrTransfer()) {
                        RecordingQualityGateCode.LOG_SAVED_AS_HDR
                    } else {
                        RecordingQualityGateCode.TRANSFER_MISMATCH
                    },
                    message = "Probe transfer ${probe.transfer} does not match ${request.transfer}."
                )
            )
        }
        if (probe.matrix != request.matrix) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.MATRIX_MISMATCH,
                    message = "Probe matrix ${probe.matrix} does not match ${request.matrix}."
                )
            )
        }
        if (probe.rotationDegrees != request.expectedRotationDegrees) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.ORIENTATION_MISMATCH,
                    message = "Probe rotation ${probe.rotationDegrees} does not match expected ${request.expectedRotationDegrees}."
                )
            )
        }
        if (probe.mirrored != request.expectedMirrored || probe.mirrored) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.MIRRORED_OUTPUT_DETECTED,
                    message = "Probe mirrored=${probe.mirrored} does not match expected ${request.expectedMirrored}."
                )
            )
        }
        if (probe.pixelFormat.contains("gray", ignoreCase = true) ||
            probe.pixelFormat.contains("mono", ignoreCase = true)
        ) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.CHROMA_CHANNELS_MISSING,
                    message = "Probe pixel format ${probe.pixelFormat} has no chroma planes for processed video."
                )
            )
        }
        if ((request.mode == EliteVideoPipelineMode.NORMAL || request.mode == EliteVideoPipelineMode.PRO) &&
            probe.transfer.isHdrTransfer()
        ) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.NORMAL_OR_PRO_SAVED_AS_HDR,
                    message = "Probe shows HDR transfer for an SDR mode."
                )
            )
        }
    }

    private fun transferMatches(
        request: RecordingQualityEvidenceRequest,
        probe: RecordingProbeMetadata
    ): Boolean =
        when (request.mode) {
            EliteVideoPipelineMode.LOG -> !probe.transfer.isHdrTransfer()
            EliteVideoPipelineMode.NPV10,
            EliteVideoPipelineMode.RAW -> true
            else -> probe.transfer == request.transfer
        }

    private fun MutableList<RecordingQualityGateFailure>.addRuntimeProofFailures(
        request: RecordingQualityEvidenceRequest
    ) {
        val stablePreviewParityDelivery = request.isStablePreviewParityDeliveryPath()
        val byteBufferReadoutDelivery = request.isByteBufferReadoutDeliveryPath()
        if (request.mode != EliteVideoPipelineMode.RAW &&
            request.mode != EliteVideoPipelineMode.NPV10 &&
            !stablePreviewParityDelivery &&
            !byteBufferReadoutDelivery &&
            !request.processedRecordingFramesValidated
        ) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.PROCESSED_RECORDING_NOT_PROVEN,
                    message = "Encoded frames did not prove execution of the selected processed quality path."
                )
            )
        }
        if (request.mode != EliteVideoPipelineMode.RAW &&
            request.mode != EliteVideoPipelineMode.NPV10 &&
            !stablePreviewParityDelivery &&
            !byteBufferReadoutDelivery &&
            !request.provesProcessedGpuPath()
        ) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.GPU_PROCESSING_NOT_PROVEN,
                    message = "Processed modes must prove a non-fallback Vulkan/custom GPU recording path; " +
                        "path=${request.gpuProcessingPath.ifBlank { "missing" }} " +
                        "validated=${request.gpuProcessingValidated}."
                )
            )
        }
        if (request.mode != EliteVideoPipelineMode.RAW &&
            request.mode != EliteVideoPipelineMode.NPV10 &&
            request.processedRecordingFramesValidated &&
            !byteBufferReadoutDelivery
        ) {
            if (!request.previewRecordingEnhancementMatch()) {
                add(
                    RecordingQualityGateFailure(
                        code = RecordingQualityGateCode.PREVIEW_RECORDING_ENHANCEMENT_MISMATCH,
                        message = "Preview and recording enhancement uniform signatures must match exactly."
                    )
                )
            }
            if (!request.recordingEnhancementUniformValidated()) {
                add(
                    RecordingQualityGateFailure(
                        code = RecordingQualityGateCode.RECORDING_ENHANCEMENT_NOT_PROVEN,
                        message = "Processed recording frames must carry a validated GPU enhancement uniform contract."
                    )
                )
            } else if (request.recordingEnhancementIdentity() && !request.recordDevelopOfflineMasterActive()) {
                add(
                    RecordingQualityGateFailure(
                        code = RecordingQualityGateCode.RECORDING_ENHANCEMENT_IDENTITY,
                        message = "GPU enhancement uniform is identity: no denoise, sharpening, temporal, or subject-detail gain is active."
                    )
                )
            }
            if (
                request.requiresStrictNormalProSdrEnhancementLock() &&
                !request.normalProSdrEnhancementLockProven()
            ) {
                add(
                    RecordingQualityGateFailure(
                        code = RecordingQualityGateCode.NORMAL_PRO_SDR_ENHANCEMENT_LOCK_NOT_PROVEN,
                        message = "Normal/Pro SDR recording must prove the strict SDR realtime lock: " +
                            "temporal=${request.enhancementTemporalBlend}, " +
                            "spatial=${request.enhancementSpatialDenoise}, " +
                            "chroma=${request.enhancementChromaDenoise}, " +
                            "sharpening=${request.enhancementSharpening}."
                    )
                )
            }
        }
        if (request.allGpuProcessingClaimed &&
            (!request.gpuProcessingValidated || request.gpuProcessingPath.isBlank())
        ) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.GPU_PROCESSING_NOT_PROVEN,
                    message = "All-GPU processing claims require runtime GPU path validation evidence."
                )
            )
        }
        if (request.zeroCopyProcessingClaimed &&
            (!request.zeroCopyValidated || request.zeroCopyEvidencePath.isBlank())
        ) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.ZERO_COPY_CLAIM_NOT_PROVEN,
                    message = "Zero-copy claims require Android hardware-buffer import or equivalent runtime proof."
                )
            )
        }
        if (!request.requiresRealtimePerformanceProof()) return

        val targetFrameTimeMs = request.targetFrameTimeMs()
        val targetCpuUsagePercent = request.resolvedTargetCpuUsagePercent()
        val performanceBudgetLabel = request.realtimePerformanceBudgetLabel()
        if (!request.performanceTelemetryValidated ||
            request.cpuUsagePercent == null ||
            request.maxFrameTimeMs == null
        ) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.REALTIME_PERFORMANCE_NOT_PROVEN,
                    message = "$performanceBudgetLabel claims require real-device CPU and frame-time telemetry evidence."
                )
            )
        }
        val cpuUsagePercent = request.cpuUsagePercent
        if (cpuUsagePercent != null && cpuUsagePercent > targetCpuUsagePercent) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.CPU_BUDGET_EXCEEDED,
                    message = "CPU usage $cpuUsagePercent exceeds target $targetCpuUsagePercent."
                )
            )
        }
        val maxFrameTimeMs = request.maxFrameTimeMs
        if (maxFrameTimeMs != null && maxFrameTimeMs > targetFrameTimeMs) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.FRAME_TIME_BUDGET_EXCEEDED,
                    message = "Max frame time $maxFrameTimeMs ms exceeds $performanceBudgetLabel budget $targetFrameTimeMs ms."
                )
            )
        }
    }

    private fun MutableList<RecordingQualityGateFailure>.addTierOmegaQualityFailures(
        request: RecordingQualityEvidenceRequest
    ) {
        // s6-f09: Tier-Omega metrics must originate from the real EliteVisionMeasurementHarness
        // producer (identified by its provenance stamp). An absent/foreign stamp cannot reach the
        // threshold checks, so synthetic/fixture metrics can never be treated as real-pixel proof.
        // Bitrate remains independently gated even when pixel metrics are unavailable.
        val tierOmegaEvidenceClaimed = request.requiresTierOmegaMetricEvidence()
        val metricsSource = request.detailMetadata[EliteVisionMeasurementHarness.TIER_OMEGA_METRICS_SOURCE_KEY]
        val missingMetricKeys = TIER_OMEGA_REQUIRED_METRIC_KEYS
            .filterNot { request.detailMetadata.containsKey(it) }
        val metricEvidenceReady =
            metricsSource == EliteVisionMeasurementHarness.SOURCE_REAL_PIXEL &&
                missingMetricKeys.isEmpty()
        if (!tierOmegaEvidenceClaimed) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.TIER_OMEGA_METRICS_NOT_PROVEN,
                    message = "Tier-Omega certification requires measured real-pixel metric evidence.",
                    blocking = false
                )
            )
        } else if (metricsSource != EliteVisionMeasurementHarness.SOURCE_REAL_PIXEL) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.TIER_OMEGA_METRICS_NOT_PROVEN,
                    message = "Tier-Omega metric provenance must be the real EliteVisionMeasurementHarness producer; " +
                        "got source=${metricsSource ?: "MISSING"}."
                )
            )
        } else if (missingMetricKeys.isNotEmpty()) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.TIER_OMEGA_METRICS_NOT_PROVEN,
                    message = "Tier-Omega quality claims require measured metric evidence: ${missingMetricKeys.joinToString()}."
                )
            )
        }

        val failedChecks = if (metricEvidenceReady) buildList {
            checkAtLeast(
                request,
                "tierOmegaLumaSnrBaseDb",
                BeyondImaxEliteProfile.MIN_LUMA_SNR_BASE_DB,
                "base luma SNR"
            )
            checkAtLeast(
                request,
                "tierOmegaLumaSnrHighIsoDb",
                BeyondImaxEliteProfile.MIN_LUMA_SNR_HIGH_ISO_DB,
                "high ISO luma SNR"
            )
            checkAtMost(
                request,
                "tierOmegaChromaNoiseBaseStd",
                BeyondImaxEliteProfile.MAX_CHROMA_NOISE_BASE_STD,
                "base chroma noise"
            )
            checkAtMost(
                request,
                "tierOmegaChromaNoiseHighIsoStd",
                BeyondImaxEliteProfile.MAX_CHROMA_NOISE_HIGH_ISO_STD,
                "high ISO chroma noise"
            )
            checkAtMost(
                request,
                "tierOmegaTemporalFlickerPercent",
                BeyondImaxEliteProfile.MAX_TEMPORAL_FLICKER_PERCENT,
                "temporal flicker"
            )
            checkAtLeast(
                request,
                "tierOmegaMtf50CyPerPx",
                BeyondImaxEliteProfile.MIN_MTF50_CY_PER_PX,
                "MTF50"
            )
            checkAtMost(
                request,
                "tierOmegaOvershootPercent",
                BeyondImaxEliteProfile.MAX_OVERSHOOT_PERCENT,
                "edge overshoot"
            )
            checkAtLeast(
                request,
                "tierOmegaDetailRetentionRaw",
                BeyondImaxEliteProfile.MIN_DETAIL_RETENTION_RAW,
                "detail retention"
            )
            checkAtLeast(
                request,
                "tierOmegaDetailRetentionHighIsoRaw",
                BeyondImaxEliteProfile.MIN_DETAIL_RETENTION_HIGH_ISO_RAW,
                "high ISO detail retention"
            )
            checkAtMost(
                request,
                "tierOmegaDeltaE2000Mean",
                BeyondImaxEliteProfile.MAX_DELTA_E2000_MEAN,
                "mean ColorChecker dE2000"
            )
            checkAtMost(
                request,
                "tierOmegaDeltaE2000Max",
                BeyondImaxEliteProfile.MAX_DELTA_E2000,
                "max ColorChecker dE2000"
            )
            checkAtMost(
                request,
                "tierOmegaGrayDeltaAAbs",
                BeyondImaxEliteProfile.MAX_GRAY_DELTA_ABS,
                "gray delta a"
            )
            checkAtMost(
                request,
                "tierOmegaGrayDeltaBAbs",
                BeyondImaxEliteProfile.MAX_GRAY_DELTA_ABS,
                "gray delta b"
            )
            checkAtMost(
                request,
                "tierOmegaBandingScore",
                BeyondImaxEliteProfile.MAX_BANDING_SCORE,
                "banding score"
            )
            checkAtMost(
                request,
                "tierOmegaDeadHotPixels",
                BeyondImaxEliteProfile.MAX_DEAD_HOT_PIXELS.toDouble(),
                "dead or hot pixels"
            )
            checkBooleanTrue(request, "tierOmegaWbApplied", "white-balance application")
            checkBooleanTrue(request, "tierOmegaCcmNonIdentity", "non-identity sensor CCM")
            checkBooleanTrue(request, "tierOmegaPreviewRecordExportParity", "Preview=Record=Export parity")
        } else emptyList()
        if (failedChecks.isNotEmpty()) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.TIER_OMEGA_METRIC_THRESHOLD_FAILED,
                    message = failedChecks.joinToString(separator = "; ")
                )
            )
        }

        val bitrateFloor = request.evidenceBitrateFloorBps()
        val requestedBitrate = request.effectiveRequestedBitrateBps()
        val policyTargetBitrate = request.effectivePolicyTargetBitrateBps()
        val appliedBitrate = request.effectiveAppliedBitrateBps()
        val measuredBitrate = request.validatedMeasuredBitrateBps()
        val measuredBitrateFloor = request.measuredEvidenceBitrateFloorBps()
        if (measuredBitrate == null) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.BITRATE_MEASUREMENT_NOT_PROVEN,
                    message = "Configured bitrate targets requested=$requestedBitrate/policy=$policyTargetBitrate/" +
                        "applied=$appliedBitrate bps do not prove encoded output bitrate.",
                    blocking = false
                )
            )
        } else if (appliedBitrate.toLong() < bitrateFloor || measuredBitrate.toLong() < measuredBitrateFloor) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.BITRATE_FLOOR_NOT_MET,
                    message = "Bitrate evidence applied=$appliedBitrate/measured=$measuredBitrate bps is below " +
                        "Tier-Omega configured/measured floor $bitrateFloor/$measuredBitrateFloor bps."
                )
            )
        }
        if (request.manualBitrateSelectionEnabled &&
            requestedBitrate > appliedBitrate &&
            appliedBitrate.toLong() < bitrateFloor
        ) {
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.MANUAL_BITRATE_CAPPED_BELOW_QUALITY_FLOOR,
                    message = "Manual bitrate target $requestedBitrate bps was capped to $appliedBitrate bps, " +
                        "below quality floor $bitrateFloor bps."
                )
            )
        }
    }

    /**
     * Elite certification consumes these device-owned verdicts as evidence, never as decoration.
     * A missing verdict is NOT_PROVEN; an explicit false/malformed verdict is a blocking failure.
     * This keeps the quality gate independent of the producer while preventing a failed Vulkan,
     * Camera2 lineage, encoder-level, or A/V-sync certificate from yielding an elite PASS.
     */
    private fun MutableList<RecordingQualityGateFailure>.addEliteDeliveryEvidenceFailures(
        request: RecordingQualityEvidenceRequest
    ) {
        requireTrueEliteEvidence(
            request = request,
            key = "strictVulkanParityCertified",
            code = RecordingQualityGateCode.STRICT_VULKAN_PARITY_NOT_CERTIFIED,
            label = "Strict Vulkan preview/record parity"
        )
        requireTrueEliteEvidence(
            request = request,
            key = "camera2FrameLineageCertified",
            code = RecordingQualityGateCode.CAMERA2_FRAME_LINEAGE_NOT_CERTIFIED,
            label = "Camera2 frame lineage"
        )
        requireTrueEliteEvidence(
            request = request,
            key = "encoderTransportLevelProven",
            code = RecordingQualityGateCode.ENCODER_TRANSPORT_LEVEL_NOT_PROVEN,
            label = "Encoder transport level"
        )
        requireTrueEliteEvidence(
            request = request,
            key = "encoderTransportAvSyncWithinBound",
            code = RecordingQualityGateCode.ENCODER_TRANSPORT_AV_SYNC_NOT_PROVEN,
            label = "Encoder transport A/V sync"
        )
    }

    private fun MutableList<RecordingQualityGateFailure>.requireTrueEliteEvidence(
        request: RecordingQualityEvidenceRequest,
        key: String,
        code: RecordingQualityGateCode,
        label: String
    ) {
        val rawValue = request.detailMetadata[key]?.trim()
        if (rawValue.equals("true", ignoreCase = true)) return
        val missing = rawValue.isNullOrEmpty()
        add(
            RecordingQualityGateFailure(
                code = code,
                message = if (missing) {
                    "$label evidence is missing."
                } else {
                    "$label evidence must be true; got $rawValue."
                },
                blocking = !missing
            )
        )
    }

    private fun MutableList<RecordingQualityGateFailure>.addOmegaCapabilityFailures(
        report: OmegaQualityTruthReport
    ) {
        report.blockingFailures.forEach { failure ->
            add(
                RecordingQualityGateFailure(
                    code = RecordingQualityGateCode.OMEGA_UNSUPPORTED_FEATURE_CLAIMED,
                    message = failure.message
                )
            )
        }
    }

    private fun metadataFor(
        request: RecordingQualityEvidenceRequest,
        status: RecordingQualityEvidenceStatus,
        failures: List<RecordingQualityGateFailure>,
        omegaReport: OmegaQualityTruthReport
    ): Map<String, String> = buildMap {
        // Runtime/detail evidence is untrusted input to this policy. Merge it first so every
        // certificate-owned fact and verdict below wins deterministically; a producer cannot
        // inject PASS/true or erase failureCodes after the gate has evaluated the evidence.
        putAll(request.detailMetadata)
        put("qualityEvidenceGeneration", GENERATION)
        put("v10QualityEvidenceGeneration", V10_GENERATION)
        put("qualityEvidenceStatus", status.name)
        put("v10EvidenceCalibratedGateApplied", "true")
        put("v15QualityEvidenceGateApplied", "true")
        put("strict10QualityRequiresV10Evidence", "true")
        put("strict10QualityRequiresV15Evidence", "true")
        put("strict10QualityRequiresV6Evidence", "true")
        put("strict10QualityProven", (status == RecordingQualityEvidenceStatus.PASS).toString())
        put("strict10DeviceVerified", (status == RecordingQualityEvidenceStatus.PASS).toString())
        put("recordingId", request.recordingId)
        put("recordedFileName", request.recordedFileName)
        put("recordedFileBytes", request.recordedFileBytes.toString())
        put("selectedMode", request.mode.name)
        put("mode", request.mode.name)
        put("selectedProfile", request.selectedProfile)
        put("activeTransport", request.activeTransport)
        put("transfer", request.transfer)
        put("primaries", request.primaries)
        put("matrix", request.matrix)
        put("officialHdr10PlusMetadataWritten", "false")
        put("vendorHdrCertification", "false")
        put("vendorCertifiedHdr10Plus", "false")
        put("vendorCertifiedDolbyVision", "false")
        put("dolbyRpuWritten", "false")
        put("externalHdrStandardSubstitution", "false")
        put("proprietaryProfilePreserved", (
            request.mode == EliteVideoPipelineMode.HDR ||
                request.mode == EliteVideoPipelineMode.LOG ||
                request.mode == EliteVideoPipelineMode.NPV10
            ).toString())
        put("androidHdrTransportOnly", (
            request.mode == EliteVideoPipelineMode.HDR &&
                request.activeTransport.contains("PQ", ignoreCase = true) &&
                request.activeTransport.isMain10OrMain12Transport()
            ).toString())
        put("neoHdrNoVendorDependency", (request.mode == EliteVideoPipelineMode.HDR).toString())
        put("nLogVendorDependency", "false")
        put("codec", request.codec)
        put("encodedBitDepth", request.encodedBitDepth.toString())
        put("internalMathBitDepth", request.internalMathBitDepth.toString())
        put("sourceBitDepth", request.sourceBitDepth.toString())
        put("storageBitDepth", request.storageBitDepth.toString())
        put("fps", request.fps.toString())
        put("bitrateBps", request.bitrateBps.toString())
        put("requestedBitrateBps", request.effectiveRequestedBitrateBps().toString())
        put("policyTargetBitrateBps", request.effectivePolicyTargetBitrateBps().toString())
        put("appliedBitrateBps", request.effectiveAppliedBitrateBps().toString())
        put("measuredOutputBitrateBps", request.measuredOutputBitrateBps.toString())
        put("bitrateMeasurementProven", (request.validatedMeasuredBitrateBps() != null).toString())
        put("bitrateMeasurementSource", request.bitrateMeasurementSource())
        put("encoderCapabilityUpperBoundBps", request.encoderCapabilityUpperBoundBps?.toString().orEmpty())
        put("codecCatalogUpperBoundBps", request.codecCatalogUpperBoundBps().toString())
        put("bitrateIdealQualityFloorBps", request.idealQualityBitrateFloorBps().toString())
        put("bitrateCapabilityBound", request.isBitrateCapabilityBound().toString())
        put("minimumMeasuredBitrateBps", request.measuredEvidenceBitrateFloorBps().toString())
        put("measuredBitrateTolerancePercent", request.measuredBitrateTolerancePercent().toString())
        put("manualBitrateSelectionEnabled", request.manualBitrateSelectionEnabled.toString())
        put("manualBitrateCapped", request.manualBitrateCapped().toString())
        put("manualBitrateCappedBelowQualityFloor", request.manualBitrateCappedBelowQualityFloor().toString())
        put("selectedResolution", request.selectedResolution)
        put("actualResolution", request.actualResolution)
        put("expectedRotationDegrees", request.expectedRotationDegrees.toString())
        put("expectedMirrored", request.expectedMirrored.toString())
        put("probeRotationDegrees", request.probe?.rotationDegrees?.toString().orEmpty())
        put("probeMirrored", request.probe?.mirrored?.toString().orEmpty())
        put("probePixelFormat", request.probe?.pixelFormat.orEmpty())
        put("captureTruthSignature", request.captureTruthSignature)
        put("previewQualitySignature", request.previewQualitySignature)
        put("recordingQualitySignature", request.recordingQualitySignature)
        put("ffprobeValidated", request.ffprobeValidated.toString())
        put("mediaInfoValidated", request.mediaInfoValidated.toString())
        put("deviceLogValidated", request.deviceLogValidated.toString())
        put("mediaStoreValidated", request.mediaStoreValidated.toString())
        put("ffprobeEvidencePath", request.ffprobeEvidencePath)
        put("mediaInfoEvidencePath", request.mediaInfoEvidencePath)
        put("adbLogEvidencePath", request.adbLogEvidencePath)
        put("adbFatalErrorCount", request.adbFatalErrorCount.toString())
        put("adbCodecErrorCount", request.adbCodecErrorCount.toString())
        put("droppedFrames", request.droppedFrames.toString())
        put("maxAllowedDroppedFrames", request.maxAllowedDroppedFrames.toString())
        put("expectedFrameCount", request.expectedFrameCount.toString())
        put("actualFrameCount", request.actualFrameCount.toString())
        put("encoderCapabilityCheck", request.encoderCapabilityCheck)
        put("encoderSceneComplexity", request.encoderSceneComplexity.formatFloat())
        put("encoderEstimatedLux", request.encoderEstimatedLux?.formatFloat().orEmpty())
        put("encoderBitrateMultiplier", request.encoderBitrateMultiplier.formatFloat())
        put("encoderSceneComplexityBoostApplied", request.encoderSceneComplexityBoostApplied.toString())
        put("encoderLowLightBoostApplied", request.encoderLowLightBoostApplied.toString())
        put("encoderHdrOrLogBoostApplied", request.encoderHdrOrLogBoostApplied.toString())
        put("encoderThermalDowngradeActive", request.encoderThermalDowngradeActive.toString())
        put("allGpuProcessingClaimed", request.allGpuProcessingClaimed.toString())
        put("zeroCopyProcessingClaimed", request.zeroCopyProcessingClaimed.toString())
        put("gpuProcessingValidated", request.gpuProcessingValidated.toString())
        put("processedRecordingFramesValidated", request.processedRecordingFramesValidated.toString())
        put("stablePreviewParityDeliveryPath", request.isStablePreviewParityDeliveryPath().toString())
        put("byteBufferReadoutDeliveryPath", request.isByteBufferReadoutDeliveryPath().toString())
        put("hdrPreviewParityDeliveryPath", (
            request.mode == EliteVideoPipelineMode.HDR &&
                request.isStablePreviewParityDeliveryPath()
            ).toString())
        put("zeroCopyValidated", request.zeroCopyValidated.toString())
        put("performanceTelemetryValidated", request.performanceTelemetryValidated.toString())
        put("gpuProcessingPath", request.gpuProcessingPath)
        put("zeroCopyEvidencePath", request.zeroCopyEvidencePath)
        put("enhancementUniformSignature", request.enhancementUniformSignature)
        put("previewEnhancementUniformSignature", request.effectivePreviewEnhancementUniformSignature())
        put("recordingEnhancementUniformSignature", request.effectiveRecordingEnhancementUniformSignature())
        put("previewRecordingEnhancementMatch", request.previewRecordingEnhancementMatch().toString())
        put("recordingEnhancementUniformValidated", request.recordingEnhancementUniformValidated().toString())
        put("recordingEnhancementIdentity", request.recordingEnhancementIdentity().toString())
        put("normalProSdrEnhancementLockRequired", request.requiresStrictNormalProSdrEnhancementLock().toString())
        put("normalProSdrEnhancementLockProven", request.normalProSdrEnhancementLockProven().toString())
        put("normalProSdrAdaptivePacketRequired", request.requiresStrictNormalProSdrEnhancementLock().toString())
        put("enhancementTemporalBlend", request.enhancementTemporalBlend.formatFloat())
        put("enhancementSpatialDenoise", request.enhancementSpatialDenoise.formatFloat())
        put("enhancementChromaDenoise", request.enhancementChromaDenoise.formatFloat())
        put("enhancementSharpening", request.enhancementSharpening.formatFloat())
        put("enhancementSubjectDetailGain", request.enhancementSubjectDetailGain.formatFloat())
        put("enhancementDetailProfile", request.enhancementDetailProfile)
        put("cpuUsagePercent", request.cpuUsagePercent?.formatFloat().orEmpty())
        put("maxFrameTimeMs", request.maxFrameTimeMs?.formatFloat().orEmpty())
        put("targetCpuUsagePercent", request.resolvedTargetCpuUsagePercent().formatFloat())
        put("targetFrameTimeMs", request.targetFrameTimeMs().formatFloat())
        putAll(
            tierOmegaThresholdMetadata(
                request = request,
                eliteQualityProven = status == RecordingQualityEvidenceStatus.PASS &&
                    request.hasCompleteEliteCertificationEvidence()
            )
        )
        putAll(omegaReport.toMetadata())
        put("npv10PayloadFormat", request.npv10PayloadFormat)
        put("npv10NativeTenBitClaimed", request.npv10NativeTenBitClaimed.toString())
        put("npv10MasterAvailable", request.npv10MasterAvailable.toString())
        put("generatedAtEpochMs", request.generatedAtEpochMs.toString())
        put("failureCodes", failures.joinToString(separator = ",") { it.code.name })
        putAll(request.noiseMetrics.toMetadata())
        put("noiseModelV6", NOISE_MODEL_V6)
        put("chromaNoiseModelV6", CHROMA_MODEL_V6)
        put("detailModelV6", DETAIL_MODEL_V6)
        put("sensorCalibrationModelV6", SENSOR_MODEL_V6)
        put("encoderQualityModelV6", ENCODER_MODEL_V6)
        put("nativeOutputResolutionFaked", "false")
    }

    private fun RecordingQualityEvidenceRequest.toOmegaCapabilityInput(): OmegaCapabilityInput =
        OmegaCapabilityInput(
            mode = mode,
            selectedProfile = selectedProfile,
            activeTransport = activeTransport,
            transfer = transfer,
            primaries = primaries,
            matrix = matrix,
            codec = codec,
            encodedBitDepth = encodedBitDepth,
            internalMathBitDepth = internalMathBitDepth,
            sourceBitDepth = sourceBitDepth,
            storageBitDepth = storageBitDepth,
            width = width,
            height = height,
            fps = fps,
            bitrateBps = bitrateBps,
            selectedResolution = selectedResolution,
            actualResolution = actualResolution,
            rawSensorOriginal = rawSensorOriginal,
            npv10PayloadFormat = npv10PayloadFormat,
            npv10NativeTenBitClaimed = npv10NativeTenBitClaimed,
            npv10MasterAvailable = npv10MasterAvailable,
            ffprobeValidated = ffprobeValidated,
            mediaStoreValidated = mediaStoreValidated,
            deviceLogValidated = deviceLogValidated,
            gpuProcessingValidated = gpuProcessingValidated,
            gpuProcessingPath = gpuProcessingPath,
            encoderCapabilityCheck = encoderCapabilityCheck,
            claimedFeatures = omegaClaimedFeatures,
            dynamicRangeSilentFallback = dynamicRangeSilentFallback
        )

    private fun String.isHdrTransfer(): Boolean =
        equals("ST2084/PQ", ignoreCase = true) || equals("HLG", ignoreCase = true)

    private fun String.isMain10OrMain12Transport(): Boolean =
        contains("MAIN10", ignoreCase = true) || contains("MAIN12", ignoreCase = true)

    private fun RecordingQualityEvidenceRequest.requiresNeoBt2100Pq(): Boolean =
        Bt2100TransportPolicy.isNeoBt2100HdrVersion(normalizedHdrVersion) ||
            selectedProfile.contains("NDR10+", ignoreCase = true) ||
            selectedProfile.contains("NIVUKX Vision", ignoreCase = true)

    private fun RecordingQualityEvidenceRequest.requiresRealtimePerformanceProof(): Boolean {
        val fourKOrHigher = width >= 3840 && height >= 2160
        return fourKOrHigher && (fps >= 60 || HighEndQualityPolicy.isHighEndQualityActive())
    }

    private fun RecordingQualityEvidenceRequest.realtimePerformanceBudgetLabel(): String =
        if (fps >= 60) {
            "4K60+"
        } else {
            "strict high-end 4K"
        }

    private fun RecordingQualityEvidenceRequest.targetFrameTimeMs(): Float =
        if (fps > 0) 1_000f / fps else 0f

    private fun RecordingQualityEvidenceRequest.resolvedTargetCpuUsagePercent(): Float =
        if (usesStrictFourKTwentyFiveProcessBudget()) {
            STRICT_4K25_PROCESS_CPU_TARGET_PERCENT
        } else {
            targetCpuUsagePercent
        }

    private fun RecordingQualityEvidenceRequest.usesStrictFourKTwentyFiveProcessBudget(): Boolean =
        width >= 3840 &&
            height >= 2160 &&
            fps in 24..30 &&
            HighEndQualityPolicy.isHighEndQualityActive() &&
            targetCpuUsagePercent == RecordingRuntimeEvidencePolicy.DEFAULT_TARGET_CPU_USAGE_PERCENT

    private fun RecordingQualityEvidenceRequest.effectiveRequestedBitrateBps(): Int =
        requestedBitrateBps.takeIf { it > 0 } ?: bitrateBps

    private fun RecordingQualityEvidenceRequest.effectivePolicyTargetBitrateBps(): Int =
        policyTargetBitrateBps.takeIf { it > 0 } ?: effectiveAppliedBitrateBps()

    private fun RecordingQualityEvidenceRequest.effectiveAppliedBitrateBps(): Int =
        appliedBitrateBps.takeIf { it > 0 } ?: bitrateBps

    private fun RecordingQualityEvidenceRequest.validatedMeasuredBitrateBps(): Int? {
        val validatedProbe = probe
            ?.bitrateBps
            ?.takeIf { it > 0 && (ffprobeValidated || mediaInfoValidated) }
        return validatedProbe ?: measuredOutputBitrateBps.takeIf { it > 0 }
    }

    private fun RecordingQualityEvidenceRequest.bitrateMeasurementSource(): String =
        when {
            probe?.bitrateBps?.let { it > 0 && ffprobeValidated } == true -> "FFPROBE"
            probe?.bitrateBps?.let { it > 0 && mediaInfoValidated } == true -> "MEDIA_INFO"
            measuredOutputBitrateBps > 0 -> "ENCODER_OUTPUT_METER"
            else -> "NONE"
        }

    private fun RecordingQualityEvidenceRequest.manualBitrateCapped(): Boolean =
        manualBitrateSelectionEnabled &&
            effectiveRequestedBitrateBps() > effectiveAppliedBitrateBps()

    private fun RecordingQualityEvidenceRequest.manualBitrateCappedBelowQualityFloor(): Boolean =
        manualBitrateCapped() &&
            effectiveAppliedBitrateBps().toLong() < evidenceBitrateFloorBps()

    private fun RecordingQualityEvidenceRequest.recordingEnhancementUniformValidated(): Boolean =
        effectiveRecordingEnhancementUniformSignature().startsWith("gpu-v", ignoreCase = true) &&
            effectiveRecordingEnhancementUniformSignature().contains("packet=", ignoreCase = true)

    private fun RecordingQualityEvidenceRequest.effectivePreviewEnhancementUniformSignature(): String =
        previewEnhancementUniformSignature.ifBlank { enhancementUniformSignature }

    private fun RecordingQualityEvidenceRequest.effectiveRecordingEnhancementUniformSignature(): String =
        recordingEnhancementUniformSignature.ifBlank { enhancementUniformSignature }

    private fun RecordingQualityEvidenceRequest.previewRecordingEnhancementMatch(): Boolean =
        effectivePreviewEnhancementUniformSignature().isNotBlank() &&
            effectivePreviewEnhancementUniformSignature() == effectiveRecordingEnhancementUniformSignature()

    private fun RecordingQualityEvidenceRequest.recordDevelopOfflineMasterActive(): Boolean {
        val lightMaster = detailMetadata["recordDevelopLightMaster"]
            ?.trim()
            ?.lowercase(Locale.US) in setOf("true", "1", "yes", "enabled")
        val offlineRequired = detailMetadata["recordDevelopOfflineDevelopRequired"]
            ?.trim()
            ?.lowercase(Locale.US) in setOf("true", "1", "yes", "enabled")
        val liveMode = detailMetadata["recordDevelopLiveColorProcessing"].orEmpty()
        val offlineFormat = detailMetadata["recordDevelopOfflineOutputFormat"].orEmpty()
        val signature = effectiveRecordingEnhancementUniformSignature()
        return lightMaster &&
            offlineRequired &&
            liveMode.equals("FLAT_10BIT_MASTER_ONLY", ignoreCase = true) &&
            offlineFormat.equals("R210_444", ignoreCase = true) &&
            signature.contains("recordDevelopLightMaster=true", ignoreCase = true)
    }

    private fun RecordingQualityEvidenceRequest.hasPassingPreviewRecordingParityCertificate(): Boolean {
        val status = detailMetadata["previewRecordingParityStatus"].orEmpty()
        val failures = detailMetadata["previewRecordingParityFailureCodes"].orEmpty()
        return status.equals("PASS", ignoreCase = true) && failures.isBlank()
    }

    private fun RecordingQualityEvidenceRequest.hasAcceptablePreviewRecordingParityCertificate(): Boolean =
        hasPassingPreviewRecordingParityCertificate() ||
            hasNonBlockingPreviewRecordingParityCertificate()

    private fun RecordingQualityEvidenceRequest.hasNonBlockingPreviewRecordingParityCertificate(): Boolean {
        val status = detailMetadata["previewRecordingParityStatus"].orEmpty()
        if (status.isBlank()) return false
        val explicitBlocked = detailMetadata["previewRecordingParityBlocked"]
            ?.trim()
            ?.lowercase(Locale.US)
        val certificateSignature = detailMetadata["parityCertificateSignature"].orEmpty()
        return explicitBlocked == "false" ||
            certificateSignature.contains("blocked=false", ignoreCase = true)
    }

    private fun RecordingQualityEvidenceRequest.requiresStrictNormalProSdrEnhancementLock(): Boolean =
        (mode == EliteVideoPipelineMode.NORMAL || mode == EliteVideoPipelineMode.PRO) &&
            !recordDevelopOfflineMasterActive()

    private fun RecordingQualityEvidenceRequest.normalProSdrEnhancementLockProven(): Boolean {
        if (!requiresStrictNormalProSdrEnhancementLock()) return false
        val previewSignature = effectivePreviewEnhancementUniformSignature()
        val recordingSignature = effectiveRecordingEnhancementUniformSignature()
        val expectedIntent = if (mode == EliteVideoPipelineMode.PRO) "PRO" else "NORMAL"
        val signaturesProveAdaptiveIntent = listOf(previewSignature, recordingSignature).all { signature ->
            signature.contains("normalSdrRealtimeLock=true", ignoreCase = true) &&
                signature.contains("sdrQualityIntent=$expectedIntent", ignoreCase = true) &&
                signature.contains("sdrQualityPolicy=ADAPTIVE_SDR_", ignoreCase = true)
        }
        val transportIsSdr =
            !transfer.isHdrTransfer() &&
                !activeTransport.contains("PQ", ignoreCase = true) &&
                !activeTransport.contains("HLG", ignoreCase = true) &&
                activeTransport.contains("MAIN10", ignoreCase = true) &&
                activeTransport.contains("SDR", ignoreCase = true) &&
                encodedBitDepth == 10 &&
                sourceBitDepth == 10 &&
                storageBitDepth == 10
        val packet = recordingSignature.adaptiveSdrPacket() ?: return false
        val packetIsBounded = ADAPTIVE_SDR_PACKET_RANGES.all { (index, range) ->
            packet[index]?.let { value -> value.isFinite() && value in range } == true
        }
        val packetMatchesEvidence =
            packet.matches(4, enhancementTemporalBlend) &&
                packet.matches(5, enhancementSpatialDenoise) &&
                packet.matches(6, enhancementChromaDenoise) &&
                packet.matches(8, enhancementSharpening)
        return transportIsSdr &&
            signaturesProveAdaptiveIntent &&
            previewRecordingEnhancementMatch() &&
            packetIsBounded &&
            packetMatchesEvidence
    }

    private fun String.adaptiveSdrPacket(): Map<Int, Float>? {
        val payload = substringAfter("packet=", missingDelimiterValue = "")
            .substringBefore('|')
        if (payload.isBlank()) return null
        val values = payload.split(',').mapNotNull { entry ->
            val key = entry.substringBefore('=').removePrefix("f").toIntOrNull() ?: return@mapNotNull null
            val value = entry.substringAfter('=', missingDelimiterValue = "").toFloatOrNull()
                ?: return@mapNotNull null
            key to value
        }.toMap()
        return values.takeIf { it.isNotEmpty() }
    }

    private fun Map<Int, Float>.matches(index: Int, evidence: Float): Boolean {
        val packetValue = this[index] ?: return false
        return packetValue.isFinite() &&
            evidence.isFinite() &&
            kotlin.math.abs(packetValue - evidence) <= ADAPTIVE_SDR_PACKET_TOLERANCE
    }

    private fun RecordingQualityEvidenceRequest.recordingEnhancementIdentity(): Boolean =
        enhancementTemporalBlend <= 0.0001f &&
            enhancementSpatialDenoise <= 0.0001f &&
            enhancementChromaDenoise <= 0.0001f &&
            enhancementSharpening <= 0.0001f &&
            enhancementSubjectDetailGain <= 0.0001f

    private fun RecordingQualityEvidenceRequest.provesProcessedGpuPath(): Boolean {
        if (!gpuProcessingValidated || gpuProcessingPath.isBlank()) return false
        val normalized = gpuProcessingPath.uppercase(Locale.US)
        if (normalized.contains("CAMERA2") || normalized.contains("FALLBACK") || normalized.contains("BYPASS")) {
            return false
        }
        return normalized.contains("VULKAN") ||
            normalized.contains("GPU") ||
            normalized.contains("INTERNAL_HARDWARE_IMPORT") ||
            normalized.contains("OFFSCREEN_AHB_EGL_ENCODER") ||
            normalized.contains("EGL_ENCODER")
    }

    private fun RecordingQualityEvidenceRequest.isStablePreviewParityDeliveryPath(): Boolean {
        if (mode == EliteVideoPipelineMode.RAW || mode == EliteVideoPipelineMode.NPV10) return false
        val path = gpuProcessingPath.uppercase(Locale.US)
        val transportMatchesMode = when (mode) {
            EliteVideoPipelineMode.NORMAL,
            EliteVideoPipelineMode.PRO -> !transfer.isHdrTransfer() &&
                activeTransport.isMain10OrMain12Transport() &&
                encodedBitDepth >= 10 &&
                sourceBitDepth >= 10 &&
                storageBitDepth >= 10
            EliteVideoPipelineMode.LOG -> !transfer.isHdrTransfer() &&
                normalizedLogVersion.startsWith("N-Log") &&
                primaries == "BT.2020" &&
                encodedBitDepth >= 10
            EliteVideoPipelineMode.HDR -> requiresNeoBt2100Pq() &&
                primaries == "BT.2020" &&
                transfer.isHdrTransfer() &&
                matrix == "BT.2020-NCL" &&
                encodedBitDepth >= 10 &&
                sourceBitDepth >= 10 &&
                storageBitDepth >= 10
            EliteVideoPipelineMode.RAW,
            EliteVideoPipelineMode.NPV10 -> false
        }
        return transportMatchesMode &&
            path.contains("CAMERA2_PREVIEW_PARITY_RECORD_SURFACE") &&
            !path.contains("CAMERA2_DIRECT_RECORD_SURFACE") &&
            !path.contains("BYPASS") &&
            previewRecordingEnhancementMatch() &&
            recordingEnhancementUniformValidated() &&
            !recordingEnhancementIdentity()
    }

    private fun RecordingQualityEvidenceRequest.isByteBufferReadoutDeliveryPath(): Boolean {
        if (mode == EliteVideoPipelineMode.RAW || mode == EliteVideoPipelineMode.NPV10) return false
        val path = gpuProcessingPath.uppercase(Locale.US)
        return processedRecordingFramesValidated &&
            path.contains("APP_P010_BYTEBUFFER_ENCODER") &&
            !zeroCopyProcessingClaimed &&
            !allGpuProcessingClaimed &&
            encodedBitDepth >= 10 &&
            sourceBitDepth >= 10 &&
            storageBitDepth >= 10
    }

    private fun RecordingQualityEvidenceRequest.requiresTierOmegaMetricEvidence(): Boolean =
        omegaClaimedFeatures.isNotEmpty() ||
            selectedProfile.contains("OMEGA", ignoreCase = true) ||
            selectedProfile.contains(BeyondImaxEliteProfile.PROFILE_NAME, ignoreCase = true) ||
            detailMetadata.keys.any {
                it.startsWith("tierOmega", ignoreCase = true) ||
                    it.startsWith("beyondImax", ignoreCase = true)
            }

    private fun Float.formatFloat(): String =
        String.format(Locale.US, "%.6f", this)

    private fun MutableList<String>.checkAtLeast(
        request: RecordingQualityEvidenceRequest,
        key: String,
        threshold: Double,
        label: String
    ) {
        val value = request.detailMetadata[key]?.toDoubleOrNull()
        if (value == null || value < threshold) {
            add("$label ${value?.formatDouble() ?: "missing"} is below ${threshold.formatDouble()}")
        }
    }

    private fun MutableList<String>.checkAtMost(
        request: RecordingQualityEvidenceRequest,
        key: String,
        threshold: Double,
        label: String
    ) {
        val value = request.detailMetadata[key]?.toDoubleOrNull()
        if (value == null || value > threshold) {
            add("$label ${value?.formatDouble() ?: "missing"} exceeds ${threshold.formatDouble()}")
        }
    }

    private fun MutableList<String>.checkBooleanTrue(
        request: RecordingQualityEvidenceRequest,
        key: String,
        label: String
    ) {
        if (!request.detailMetadata[key]?.toBooleanStrictOrNull().orFalse()) {
            add("$label evidence is not true")
        }
    }

    private fun tierOmegaThresholdMetadata(
        request: RecordingQualityEvidenceRequest,
        eliteQualityProven: Boolean
    ): Map<String, String> = mapOf(
        "tierOmegaEvidenceGateApplied" to "true",
        "tierOmegaQualityProven" to eliteQualityProven.toString(),
        "tierOmegaProfile" to BeyondImaxEliteProfile.PROFILE_NAME,
        "beyondImaxQualityProfile" to BeyondImaxEliteProfile.PROFILE_NAME,
        "beyondImaxBaselineProfile" to BeyondImaxEliteProfile.BASELINE_PROFILE_NAME,
        "beyondImaxEvidenceGateApplied" to "true",
        "beyondImaxQualityProven" to eliteQualityProven.toString(),
        "tierOmegaMinimumBitrateBps" to request.evidenceBitrateFloorBps().toString(),
        "beyondImaxMinimumBitrateBps" to request.evidenceBitrateFloorBps().toString(),
        "tierOmegaMinLumaSnrBaseDb" to BeyondImaxEliteProfile.MIN_LUMA_SNR_BASE_DB.formatDouble(),
        "tierOmegaMinLumaSnrHighIsoDb" to BeyondImaxEliteProfile.MIN_LUMA_SNR_HIGH_ISO_DB.formatDouble(),
        "tierOmegaMaxChromaNoiseBaseStd" to BeyondImaxEliteProfile.MAX_CHROMA_NOISE_BASE_STD.formatDouble(),
        "tierOmegaMaxChromaNoiseHighIsoStd" to BeyondImaxEliteProfile.MAX_CHROMA_NOISE_HIGH_ISO_STD.formatDouble(),
        "tierOmegaMaxTemporalFlickerPercent" to BeyondImaxEliteProfile.MAX_TEMPORAL_FLICKER_PERCENT.formatDouble(),
        "tierOmegaMinMtf50CyPerPx" to BeyondImaxEliteProfile.MIN_MTF50_CY_PER_PX.formatDouble(),
        "tierOmegaMaxOvershootPercent" to BeyondImaxEliteProfile.MAX_OVERSHOOT_PERCENT.formatDouble(),
        "tierOmegaMinDetailRetentionRaw" to BeyondImaxEliteProfile.MIN_DETAIL_RETENTION_RAW.formatDouble(),
        "tierOmegaMinDetailRetentionHighIsoRaw" to BeyondImaxEliteProfile.MIN_DETAIL_RETENTION_HIGH_ISO_RAW.formatDouble(),
        "tierOmegaMaxDeltaE2000Mean" to BeyondImaxEliteProfile.MAX_DELTA_E2000_MEAN.formatDouble(),
        "tierOmegaMaxDeltaE2000" to BeyondImaxEliteProfile.MAX_DELTA_E2000.formatDouble(),
        "tierOmegaMaxGrayDeltaAbs" to BeyondImaxEliteProfile.MAX_GRAY_DELTA_ABS.formatDouble(),
        "tierOmegaMaxBandingScore" to BeyondImaxEliteProfile.MAX_BANDING_SCORE.formatDouble(),
        "tierOmegaMaxDeadHotPixels" to BeyondImaxEliteProfile.MAX_DEAD_HOT_PIXELS.toString()
    )

    private fun Double.formatDouble(): String =
        String.format(Locale.US, "%.6f", this)

    private fun Boolean?.orFalse(): Boolean = this ?: false

    private fun RecordingQualityEvidenceRequest.evidenceBitrateFloorBps(): Long {
        val strictFloor = HighEndQualityPolicy.minimumBitrateBpsLong(width, height, fps)
        val codecCeiling = codecRequestCeilingBps()
        return strictFloor.coerceAtMost(codecCeiling)
    }

    private fun RecordingQualityEvidenceRequest.hasCompleteEliteCertificationEvidence(): Boolean {
        val hasMeasuredMetrics =
            detailMetadata[EliteVisionMeasurementHarness.TIER_OMEGA_METRICS_SOURCE_KEY] ==
                EliteVisionMeasurementHarness.SOURCE_REAL_PIXEL &&
                TIER_OMEGA_REQUIRED_METRIC_KEYS.all(detailMetadata::containsKey)
        val deliveryEvidenceProven = ELITE_DELIVERY_EVIDENCE_KEYS.all { key ->
            detailMetadata[key]?.trim().equals("true", ignoreCase = true)
        }
        val measuredBitrate = validatedMeasuredBitrateBps()
        val bitrateFloor = evidenceBitrateFloorBps()
        val bitrateProven = measuredBitrate != null &&
            effectiveAppliedBitrateBps().toLong() >= bitrateFloor &&
            measuredBitrate.toLong() >= measuredEvidenceBitrateFloorBps()
        return hasMeasuredMetrics && deliveryEvidenceProven && bitrateProven
    }

    private fun RecordingQualityEvidenceRequest.codecRequestCeilingBps(): Long {
        val catalogCeiling = codecCatalogUpperBoundBps()
        val deviceCeiling = encoderCapabilityUpperBoundBps
            ?.takeIf { it > 0 }
            ?.toLong()
        return deviceCeiling?.coerceAtMost(catalogCeiling) ?: catalogCeiling
    }

    private fun RecordingQualityEvidenceRequest.codecCatalogUpperBoundBps(): Long {
        val key = codec.lowercase(Locale.US)
        val gpuCodec = when {
            key.contains("av1") || key.contains("av01") -> GpuVideoCodec.AV1
            key.contains("avc") || key.contains("h264") || key.contains("h.264") -> GpuVideoCodec.AVC
            else -> GpuVideoCodec.HEVC
        }
        return RecordingCodecCatalog.maxBitrateMbps(gpuCodec).toLong() * 1_000_000L
    }

    private fun RecordingQualityEvidenceRequest.idealQualityBitrateFloorBps(): Long =
        HighEndQualityPolicy.minimumBitrateBpsLong(width, height, fps)

    private fun RecordingQualityEvidenceRequest.isBitrateCapabilityBound(): Boolean =
        idealQualityBitrateFloorBps() > codecRequestCeilingBps()

    /**
     * MediaCodec bitrate is a target, not a promise that every completed clip averages to the exact
     * integer configured on the codec. When the app has saturated a measured hardware ceiling, allow
     * only the normal one-percent rate-control envelope for measured output; the configured/applied
     * bitrate must still equal the full capability-bound floor. No encoder setting is reduced here.
     */
    private fun RecordingQualityEvidenceRequest.measuredEvidenceBitrateFloorBps(): Long {
        val floor = evidenceBitrateFloorBps()
        if (!isBitrateCapabilityBound()) return floor
        return (floor * CAPABILITY_BOUND_MEASURED_BITRATE_PERCENT + 99L) / 100L
    }

    private fun RecordingQualityEvidenceRequest.measuredBitrateTolerancePercent(): Int =
        if (isBitrateCapabilityBound()) 100 - CAPABILITY_BOUND_MEASURED_BITRATE_PERCENT.toInt() else 0

    private val ELITE_DELIVERY_EVIDENCE_KEYS = listOf(
        "strictVulkanParityCertified",
        "camera2FrameLineageCertified",
        "encoderTransportLevelProven",
        "encoderTransportAvSyncWithinBound"
    )

    private const val CAPABILITY_BOUND_MEASURED_BITRATE_PERCENT = 99L

    private val TIER_OMEGA_REQUIRED_METRIC_KEYS = listOf(
        "tierOmegaLumaSnrBaseDb",
        "tierOmegaLumaSnrHighIsoDb",
        "tierOmegaChromaNoiseBaseStd",
        "tierOmegaChromaNoiseHighIsoStd",
        "tierOmegaTemporalFlickerPercent",
        "tierOmegaMtf50CyPerPx",
        "tierOmegaOvershootPercent",
        "tierOmegaDetailRetentionRaw",
        "tierOmegaDetailRetentionHighIsoRaw",
        "tierOmegaDeltaE2000Mean",
        "tierOmegaDeltaE2000Max",
        "tierOmegaGrayDeltaAAbs",
        "tierOmegaGrayDeltaBAbs",
        "tierOmegaBandingScore",
        "tierOmegaDeadHotPixels",
        "tierOmegaWbApplied",
        "tierOmegaCcmNonIdentity",
        "tierOmegaPreviewRecordExportParity"
    )
}
