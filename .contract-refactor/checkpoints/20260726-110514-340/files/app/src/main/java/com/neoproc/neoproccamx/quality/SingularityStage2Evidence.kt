package com.neoproc.neoproccamx.quality

import java.util.Locale

internal data class SingularityCaptureStage2Evidence(
    val frameIdentity: String = "",
    val sessionIdentity: String = "",
    val recordingIntent: String = "",
    val deviceCapabilitySnapshot: String = "",
    val cameraId: String = "",
    val physicalCameraId: String = "",
    val physicalCameraStreamProven: Boolean = false,
    val streamCombinationValidated: Boolean = false,
    val streamCombinationUnsupported: Boolean = false,
    val streamGraphMode: String = "",
    val streamGraphDiagnostics: List<String> = emptyList(),
    val sensorRequestValidated: Boolean = false,
    val requestTemplate: String = "",
    val requestTargets: String = "",
    val captureIntent: String = "",
    val manualIso: Int? = null,
    val manualExposureNs: Long? = null,
    val manualWhiteBalanceKelvin: Int? = null,
    val manualFocusDistanceDiopters: Float? = null,
    val frameDurationNs: Long? = null,
    val requestedFps: Int = 0,
    val captureTimestampNs: Long = 0L,
    val previousCaptureTimestampNs: Long = 0L,
    val imuSampleCount: Int = 0,
    val imuWindowStartNs: Long = 0L,
    val imuWindowEndNs: Long = 0L,
    val rollingShutterSkewNs: Long? = null,
    val thermalStatus: String = "",
    val memoryStatus: String = "",
    val captureResultAvailable: Boolean = false,
    val sensorSensitivityIso: Int? = null,
    val exposureTimeNs: Long? = null,
    val focusDistanceDiopters: Float? = null,
    val blackLevelAvailable: Boolean = false,
    val whiteLevel: Int? = null,
    val bayerPattern: String = "",
    val activeArray: String = "",
    val cropRegion: String = "",
    val opticalBlackCorrectionProven: Boolean = false,
    val fixedPatternNoiseProfile: String = "",
    val hotPixelMapAvailable: Boolean = false,
    val sensorDefectRepairProven: Boolean = false,
    val lensShadingMapAvailable: Boolean = false,
    val vignettingCorrectionProven: Boolean = false,
    val rowColumnBandingModel: String = "",
    val sensorNoiseProfilePairs: Int = 0,
    val whiteSaturationTagged: Boolean = false,
    val calibrationConfidence: Double = 0.0,
    val calibratedSensorOutput: String = "",
    val evidenceReferences: List<String> = emptyList()
) {
    val manualControlPacketAvailable: Boolean
        get() = manualIso != null ||
            manualExposureNs != null ||
            manualWhiteBalanceKelvin != null ||
            manualFocusDistanceDiopters != null

    val timestampAlignmentProven: Boolean
        get() = captureTimestampNs > 0L &&
            (previousCaptureTimestampNs > 0L || requestedFps > 0)

    val thermalMemoryBudgetAvailable: Boolean
        get() = thermalStatus.isNotBlank() || memoryStatus.isNotBlank()
}

internal data class SingularityOutputStage2Evidence(
    val outputModeRequested: String,
    val outputModeActual: String,
    val outputCapabilityValidated: Boolean = false,
    val outputTransform: String = "",
    val encodedBitDepth: Int = 0,
    val sourceBitDepth: Int = 0,
    val storageBitDepth: Int = 0,
    val primaries: String = "",
    val transfer: String = "",
    val matrix: String = "",
    val range: String = "",
    val gpuProcessingValidated: Boolean = false,
    val processedRecordingFramesValidated: Boolean = false,
    val zeroCopyValidated: Boolean = false,
    val frameImportEnabled: Boolean = false,
    val importedFrames: Long = 0L,
    val failedImports: Long = 0L,
    val encoderSurfaceBound: Boolean = false,
    val encoderSurfacePrecisionProven: Boolean = false,
    val encoderSurfaceBitDepth: Int = 0,
    val encoderSurfaceFormat: String = "",
    val codec: String = "",
    val codecProfile: String = "",
    val codecLevel: String = "",
    val bitrateBps: Int = 0,
    val requestedBitrateBps: Int = 0,
    val appliedBitrateBps: Int = 0,
    val measuredOutputBitrateBps: Int = 0,
    val gopPlan: String = "",
    val expectedFrameCount: Long = 0L,
    val actualFrameCount: Long = 0L,
    val droppedFrames: Int = 0,
    val maxAllowedDroppedFrames: Int = 0,
    val submittedFrames: Long = 0L,
    val muxerSamplesWritten: Long = 0L,
    val finalized: Boolean = false,
    val playable: Boolean = false,
    val recordedFileBytes: Long = 0L,
    val mediaStoreValidated: Boolean = false,
    val rawSensorOriginal: Boolean = false,
    val npv10MasterAvailable: Boolean = false,
    val npv10PayloadFormat: String = "",
    val camera2DirectFallback: Boolean = false,
    val byteBufferReadoutDelivery: Boolean = false,
    val p010NativeDirectCopies: Long = 0L,
    val p010DirectBufferCopies: Long = 0L,
    val p010CopyFailures: Long = 0L,
    val readbackCount: Long = 0L,
    val copyCountEvidenceProven: Boolean = false,
    val perFrameAllocationCount: Long = 0L,
    val poolSummary: String = "",
    val syncPolicy: String = "",
    val queueSubmit: String = "",
    val ahbFormat: String = "",
    val ahbUsage: String = "",
    val ahbStride: Int = 0,
    val previewHandoffProven: Boolean = false,
    val encoderHandoffProven: Boolean = false,
    val averageGpuDurationNs: Long = 0L,
    val averageCpuCopyDurationNs: Long = 0L,
    val privateMasterGpuExportProven: Boolean = false,
    val rawPrivateMasterIntegrityProven: Boolean = false,
    val evidenceReferences: List<String> = emptyList()
) {
    val totalCopyCount: Long
        get() = p010NativeDirectCopies + p010DirectBufferCopies

    val outputModeMismatch: Boolean
        get() = outputModeRequested.isNotBlank() &&
            outputModeActual.isNotBlank() &&
            !outputModeRequested.equals(outputModeActual, ignoreCase = true)

    val requiresTenBitOutput: Boolean
        get() {
            val token = "${outputModeRequested}_$outputModeActual".uppercase(Locale.US)
            return token.contains("10") ||
                token.contains("NDR") ||
                token.contains("NIVUKX") ||
                token.contains("LOG") ||
                token.contains("PRO") ||
                token.contains("NPV10")
        }
}

internal data class SingularityTransportClassificationDecision(
    val classification: SingularityTransportClassification,
    val reason: String,
    val copyCount: Long,
    val readbackCount: Long,
    val copyCountEvidenceProven: Boolean,
    val hardwareBacked: Boolean
)

internal data class SingularityStage2EvidenceBundle(
    val captureStages: List<SingularityStageEvidence>,
    val outputStages: List<SingularityStageEvidence>,
    val transportDecision: SingularityTransportClassificationDecision
) {
    init {
        require(captureStages.map { it.globalStageId } == (0..31).toList()) {
            "Stage 2 capture evidence must cover stages 000..031 in order"
        }
        require(outputStages.map { it.globalStageId } == (224..239).toList()) {
            "Stage 2 output evidence must cover stages 224..239 in order"
        }
    }

    val allStages: List<SingularityStageEvidence>
        get() = captureStages + outputStages

    fun toMetadata(prefix: String = "singularityStage2"): Map<String, String> = buildMap {
        put("${prefix}Version", VERSION)
        put("${prefix}MappedStageCount", allStages.size.toString())
        put("${prefix}CaptureStageCount", captureStages.size.toString())
        put("${prefix}OutputStageCount", outputStages.size.toString())
        put("${prefix}CaptureStageIds", captureStages.stageIdsCsv())
        put("${prefix}OutputStageIds", outputStages.stageIdsCsv())
        put("${prefix}TransportClassification", transportDecision.classification.name)
        put("${prefix}TransportReason", transportDecision.reason)
        put("${prefix}TransportCopyCount", transportDecision.copyCount.toString())
        put("${prefix}TransportReadbackCount", transportDecision.readbackCount.toString())
        put("${prefix}CopyCountEvidenceProven", transportDecision.copyCountEvidenceProven.toString())
        put("${prefix}HardwareBacked", transportDecision.hardwareBacked.toString())
        put("${prefix}ExecutionSummary", allStages.executionSummary())
        put("${prefix}ReductionStageIds", allStages.filter { it.isReduction }.stageIdsCsv())
        put("${prefix}FailureStageIds", allStages.filter { it.isBlockingFailure }.stageIdsCsv())
        allStages.forEach { stage ->
            put("${prefix}.stage${stage.globalStageId.toStageIdToken()}.${stage.stageName}", stage.compactRecord())
        }
    }

    companion object {
        const val VERSION = "SINGULARITY_STAGE2_CAPTURE_TRANSPORT_V1"
    }
}

internal object SingularityTransportClassifier {
    fun classify(output: SingularityOutputStage2Evidence): SingularityTransportClassificationDecision {
        val copyCount = output.totalCopyCount
        val readbackCount = output.readbackCount
        val hardwareBackedAhb =
            output.gpuProcessingValidated &&
                output.processedRecordingFramesValidated &&
                output.importedFrames > 0L &&
                output.encoderSurfaceBound

        return when {
            output.rawSensorOriginal &&
                output.rawPrivateMasterIntegrityProven &&
                output.finalized &&
                output.recordedFileBytes > 0L &&
                output.sourceBitDepth >= 10 ->
                SingularityTransportClassificationDecision(
                    classification = SingularityTransportClassification.RAW_SENSOR_PRIVATE_MASTER,
                    reason = "RAW sensor/private-master output finalized with source bit-depth evidence.",
                    copyCount = copyCount,
                    readbackCount = readbackCount,
                    copyCountEvidenceProven = output.copyCountEvidenceProven,
                    hardwareBacked = true
                )

            output.npv10MasterAvailable &&
                output.privateMasterGpuExportProven &&
                output.finalized &&
                output.recordedFileBytes > 0L ->
                SingularityTransportClassificationDecision(
                    classification = SingularityTransportClassification.PRIVATE_MASTER_GPU_EXPORT,
                    reason = "NPV10 private master finalized with validated GPU/private-master path.",
                    copyCount = copyCount,
                    readbackCount = readbackCount,
                    copyCountEvidenceProven = output.copyCountEvidenceProven,
                    hardwareBacked = true
                )

            hardwareBackedAhb &&
                output.zeroCopyValidated &&
                output.frameImportEnabled &&
                output.copyCountEvidenceProven &&
                copyCount == 0L &&
                readbackCount == 0L ->
                SingularityTransportClassificationDecision(
                    classification = SingularityTransportClassification.AHB_VULKAN_ZERO_COPY,
                    reason = "AHardwareBuffer import, Vulkan processing, encoder handoff, and zero-copy counters are proven.",
                    copyCount = copyCount,
                    readbackCount = readbackCount,
                    copyCountEvidenceProven = true,
                    hardwareBacked = true
                )

            hardwareBackedAhb ->
                SingularityTransportClassificationDecision(
                    classification = SingularityTransportClassification.AHB_GPU_COMPATIBILITY,
                    reason = "Hardware-buffer GPU path is proven but copy/readback or zero-copy proof is not clean.",
                    copyCount = copyCount,
                    readbackCount = readbackCount,
                    copyCountEvidenceProven = output.copyCountEvidenceProven,
                    hardwareBacked = true
                )

            output.camera2DirectFallback && output.finalized ->
                SingularityTransportClassificationDecision(
                    classification = SingularityTransportClassification.ENCODER_SURFACE_DIRECT,
                    reason = "Camera2 encoder surface direct path is visible and finalized.",
                    copyCount = copyCount,
                    readbackCount = readbackCount,
                    copyCountEvidenceProven = output.copyCountEvidenceProven,
                    hardwareBacked = true
                )

            output.byteBufferReadoutDelivery ||
                copyCount > 0L ||
                output.p010CopyFailures > 0L ||
                (output.npv10MasterAvailable && output.finalized) ->
                SingularityTransportClassificationDecision(
                    classification = SingularityTransportClassification.CPU_COMPATIBILITY_NON_SINGULARITY,
                    reason = "Byte-buffer/readout copy path is compatibility output and cannot claim Singularity zero-copy.",
                    copyCount = copyCount,
                    readbackCount = readbackCount,
                    copyCountEvidenceProven = output.copyCountEvidenceProven,
                    hardwareBacked = false
                )

            else ->
                SingularityTransportClassificationDecision(
                    classification = SingularityTransportClassification.UNPROVEN,
                    reason = "Runtime transport evidence is incomplete.",
                    copyCount = copyCount,
                    readbackCount = readbackCount,
                    copyCountEvidenceProven = output.copyCountEvidenceProven,
                    hardwareBacked = false
                )
        }
    }
}

internal object SingularityStage2EvidenceMapper {
    fun map(
        capture: SingularityCaptureStage2Evidence,
        output: SingularityOutputStage2Evidence
    ): SingularityStage2EvidenceBundle =
        SingularityStage2EvidenceBundle(
            captureStages = mapCaptureStages(capture),
            outputStages = mapOutputStages(output),
            transportDecision = SingularityTransportClassifier.classify(output)
        )

    fun mapCaptureStages(capture: SingularityCaptureStage2Evidence): List<SingularityStageEvidence> =
        listOf(
            stage(
                id = 0,
                backend = "CAMERA2_METADATA",
                state = capture.frameIdentity.isNotBlank().toState("FRAME_IDENTITY_MISSING"),
                inputs = listOf("CaptureResult.frameNumber", "CaptureResult.SENSOR_TIMESTAMP"),
                outputs = listOf(capture.frameIdentity.ifBlank { "frame_identity_unavailable" }),
                references = capture.refs("frameIdentity=${capture.frameIdentity}")
            ),
            stage(
                id = 1,
                backend = "CAMERA2_SESSION",
                state = capture.sessionIdentity.isNotBlank().toState("SESSION_IDENTITY_MISSING"),
                inputs = listOf("CameraCaptureSession", "recordingId"),
                outputs = listOf(capture.sessionIdentity.ifBlank { "session_identity_unavailable" }),
                references = capture.refs("sessionIdentity=${capture.sessionIdentity}")
            ),
            stage(
                id = 2,
                backend = "RECORDING_POLICY",
                state = capture.recordingIntent.isNotBlank().toState("RECORDING_INTENT_MISSING"),
                inputs = listOf("selectedVideoMode", "recordingProfile"),
                outputs = listOf(capture.recordingIntent.ifBlank { "recording_intent_unavailable" }),
                references = capture.refs("recordingIntent=${capture.recordingIntent}")
            ),
            stage(
                id = 3,
                backend = "CAMERA2_CHARACTERISTICS",
                state = capture.deviceCapabilitySnapshot.isNotBlank().toState("DEVICE_CAPABILITY_SNAPSHOT_MISSING"),
                inputs = listOf("CameraCharacteristics", "StreamConfigurationMap"),
                outputs = listOf(capture.deviceCapabilitySnapshot.ifBlank { "capability_snapshot_unavailable" }),
                references = capture.refs("deviceCapabilitySnapshot=${capture.deviceCapabilitySnapshot}")
            ),
            stage(
                id = 4,
                backend = "SINGULARITY_STAGE2",
                state = SingularityStageExecutionState.EXECUTED,
                outputs = listOf("stage2_capture_transport_bundle"),
                references = capture.refs(SingularityStage2EvidenceBundle.VERSION)
            ),
            stage(
                id = 5,
                backend = "CAMERA2_CHARACTERISTICS",
                state = capture.cameraId.isNotBlank().toState("CAMERA_ID_MISSING"),
                outputs = listOf(capture.cameraId.ifBlank { "camera_id_unavailable" }),
                references = capture.refs("cameraId=${capture.cameraId}")
            ),
            stage(
                id = 6,
                backend = "CAMERA2_PHYSICAL_STREAM",
                state = when {
                    capture.physicalCameraStreamProven -> SingularityStageExecutionState.EXECUTED
                    capture.physicalCameraId.isNotBlank() -> SingularityStageExecutionState.FALLBACK_EXECUTED
                    else -> SingularityStageExecutionState.DISABLED_BY_DEVICE
                },
                outputs = listOf(capture.physicalCameraId.ifBlank { "logical_camera_only" }),
                fallbackId = if (capture.physicalCameraStreamProven) "" else "PHYSICAL_CAMERA_STREAM_NOT_PROVEN",
                fallbackReason = if (capture.physicalCameraStreamProven) {
                    ""
                } else {
                    "Physical camera stream or active physical capture-result identity is not proven."
                },
                references = capture.refs("physicalCameraId=${capture.physicalCameraId}")
            ),
            stage(
                id = 7,
                backend = "CAMERA2_STREAM_GRAPH",
                state = when {
                    capture.streamCombinationValidated -> SingularityStageExecutionState.EXECUTED
                    capture.streamCombinationUnsupported -> SingularityStageExecutionState.FAILED_WITH_DOWNGRADE
                    else -> SingularityStageExecutionState.DISABLED_BY_DEVICE
                },
                inputs = listOf("CinemaStreamGraphPlan"),
                outputs = listOf(capture.streamGraphMode.ifBlank { "stream_graph_unavailable" }),
                fallbackId = if (capture.streamCombinationValidated) "" else "STREAM_COMBINATION_NOT_PROVEN",
                fallbackReason = if (capture.streamCombinationValidated) {
                    ""
                } else {
                    capture.streamGraphDiagnostics.joinToString("; ").ifBlank {
                        "Camera2 stream combination was not proven by accepted session evidence."
                    }
                },
                references = capture.refs(capture.streamGraphDiagnostics.joinToString("; "))
            ),
            stage(
                id = 8,
                backend = "CAMERA2_CAPTURE_REQUEST",
                state = capture.sensorRequestValidated.toState("SENSOR_REQUEST_NOT_VALIDATED"),
                inputs = listOf("CaptureRequest.Builder"),
                outputs = listOf(capture.requestTemplate, capture.captureIntent, capture.requestTargets).filter { it.isNotBlank() },
                references = capture.refs("requestTemplate=${capture.requestTemplate};captureIntent=${capture.captureIntent}")
            ),
            stage(
                id = 9,
                backend = "CAMERA2_CAPTURE_REQUEST",
                state = if (capture.sensorRequestValidated) {
                    if (capture.manualControlPacketAvailable) {
                        SingularityStageExecutionState.EXECUTED
                    } else {
                        SingularityStageExecutionState.IDENTITY_PASS
                    }
                } else {
                    SingularityStageExecutionState.DISABLED_BY_DEVICE
                },
                inputs = listOf("manualIso", "manualExposureNs", "manualWbKelvin", "manualFocusDistance"),
                outputs = listOf(capture.manualControlSummary()),
                references = capture.refs(capture.manualControlSummary())
            ),
            stage(
                id = 10,
                backend = "CAMERA2_CAPTURE_REQUEST",
                state = ((capture.frameDurationNs ?: 0L) > 0L && capture.requestedFps > 0)
                    .toState("FRAME_DURATION_CONTRACT_MISSING"),
                inputs = listOf("SENSOR_FRAME_DURATION", "requestedFps"),
                outputs = listOf("frameDurationNs=${capture.frameDurationNs ?: 0L};fps=${capture.requestedFps}"),
                references = capture.refs("frameDurationNs=${capture.frameDurationNs ?: 0L}")
            ),
            stage(
                id = 11,
                backend = "CAMERA2_CAPTURE_RESULT",
                state = capture.timestampAlignmentProven.toState("TIMESTAMP_ALIGNMENT_MISSING"),
                inputs = listOf("SENSOR_TIMESTAMP", "frameDurationContract"),
                outputs = listOf("timestampNs=${capture.captureTimestampNs};previous=${capture.previousCaptureTimestampNs}"),
                references = capture.refs("timestampNs=${capture.captureTimestampNs}")
            ),
            stage(
                id = 12,
                backend = "IMU_OIS_TELEMETRY",
                state = when {
                    capture.imuSampleCount > 0 -> SingularityStageExecutionState.EXECUTED
                    capture.rollingShutterSkewNs != null -> SingularityStageExecutionState.FALLBACK_EXECUTED
                    else -> SingularityStageExecutionState.DISABLED_BY_DEVICE
                },
                inputs = listOf("gyroscope", "accelerometer", "OIS samples"),
                outputs = listOf("imuSamples=${capture.imuSampleCount};window=${capture.imuWindowStartNs}-${capture.imuWindowEndNs}"),
                fallbackId = if (capture.imuSampleCount > 0) "" else "IMU_WINDOW_NOT_BOUND",
                fallbackReason = if (capture.imuSampleCount > 0) {
                    ""
                } else {
                    "Per-frame IMU/OIS window was not available; rolling-shutter metadata is the only motion timing fallback."
                },
                references = capture.refs("imuSampleCount=${capture.imuSampleCount}")
            ),
            stage(
                id = 13,
                backend = "CAMERA2_CAPTURE_RESULT",
                state = (capture.rollingShutterSkewNs != null).toState("ROLLING_SHUTTER_METADATA_MISSING"),
                inputs = listOf("SENSOR_ROLLING_SHUTTER_SKEW"),
                outputs = listOf("rollingShutterSkewNs=${capture.rollingShutterSkewNs ?: 0L}"),
                references = capture.refs("rollingShutterSkewNs=${capture.rollingShutterSkewNs ?: 0L}")
            ),
            stage(
                id = 14,
                backend = "THERMAL_MEMORY_POLICY",
                state = capture.thermalMemoryBudgetAvailable.toState("THERMAL_MEMORY_SNAPSHOT_MISSING"),
                inputs = listOf("ThermalRuntimePolicyResolver", "MemoryProfiler"),
                outputs = listOf("thermal=${capture.thermalStatus};memory=${capture.memoryStatus}"),
                references = capture.refs("thermal=${capture.thermalStatus};memory=${capture.memoryStatus}")
            ),
            stage(
                id = 15,
                backend = "SINGULARITY_STAGE2",
                state = SingularityStageExecutionState.EXECUTED,
                outputs = listOf("capture_evidence_record_initialized"),
                references = capture.refs("captureStage2Record=initialized")
            )
        ) + mapSensorCalibrationStages(capture)

    private fun mapSensorCalibrationStages(capture: SingularityCaptureStage2Evidence): List<SingularityStageEvidence> =
        listOf(
            stage(16, "CAMERA2_RAW_METADATA", capture.blackLevelAvailable.toState("BLACK_LEVEL_MISSING"), outputs = listOf("blackLevelAvailable=${capture.blackLevelAvailable}"), references = capture.refs("blackLevelAvailable=${capture.blackLevelAvailable}")),
            stage(17, "CAMERA2_RAW_METADATA", ((capture.whiteLevel ?: 0) > 0).toState("WHITE_LEVEL_MISSING"), outputs = listOf("whiteLevel=${capture.whiteLevel ?: 0}"), references = capture.refs("whiteLevel=${capture.whiteLevel ?: 0}")),
            stage(18, "CAMERA2_RAW_METADATA", capture.bayerPattern.isNotBlank().toState("BAYER_PATTERN_MISSING"), outputs = listOf(capture.bayerPattern.ifBlank { "bayer_pattern_unavailable" }), references = capture.refs("bayerPattern=${capture.bayerPattern}")),
            stage(19, "CAMERA2_SENSOR_GEOMETRY", (capture.activeArray.isNotBlank() || capture.cropRegion.isNotBlank()).toState("ACTIVE_ARRAY_OR_CROP_MISSING"), outputs = listOf("active=${capture.activeArray};crop=${capture.cropRegion}"), references = capture.refs("activeArray=${capture.activeArray};crop=${capture.cropRegion}")),
            stage(
                20,
                "RAW_CALIBRATION_POLICY",
                if (capture.opticalBlackCorrectionProven) SingularityStageExecutionState.EXECUTED else if (capture.blackLevelAvailable) SingularityStageExecutionState.FALLBACK_EXECUTED else SingularityStageExecutionState.DISABLED_BY_DEVICE,
                fallbackId = if (capture.opticalBlackCorrectionProven) "" else "OPTICAL_BLACK_METADATA_ONLY",
                fallbackReason = if (capture.opticalBlackCorrectionProven) "" else "Optical black correction is represented by available black-level metadata only.",
                outputs = listOf("opticalBlackCorrectionProven=${capture.opticalBlackCorrectionProven}"),
                references = capture.refs("opticalBlackCorrectionProven=${capture.opticalBlackCorrectionProven}")
            ),
            stage(
                21,
                "SENSOR_CALIBRATION_POLICY",
                capture.fixedPatternNoiseProfile.isNotBlank().toState("FPN_PROFILE_MISSING"),
                outputs = listOf(capture.fixedPatternNoiseProfile.ifBlank { "fpn_profile_unavailable" }),
                references = capture.refs("fixedPatternNoiseProfile=${capture.fixedPatternNoiseProfile}")
            ),
            stage(22, "SENSOR_DEFECT_POLICY", capture.hotPixelMapAvailable.toState("HOT_PIXEL_MAP_MISSING"), outputs = listOf("hotPixelMapAvailable=${capture.hotPixelMapAvailable}"), references = capture.refs("hotPixelMapAvailable=${capture.hotPixelMapAvailable}")),
            stage(23, "SENSOR_DEFECT_POLICY", capture.sensorDefectRepairProven.toState("SENSOR_DEFECT_REPAIR_NOT_PROVEN"), outputs = listOf("sensorDefectRepairProven=${capture.sensorDefectRepairProven}"), references = capture.refs("sensorDefectRepairProven=${capture.sensorDefectRepairProven}")),
            stage(24, "CAMERA2_LENS_SHADING", capture.lensShadingMapAvailable.toState("LENS_SHADING_MAP_MISSING"), outputs = listOf("lensShadingMapAvailable=${capture.lensShadingMapAvailable}"), references = capture.refs("lensShadingMapAvailable=${capture.lensShadingMapAvailable}")),
            stage(
                25,
                "LENS_SHADING_POLICY",
                if (capture.vignettingCorrectionProven) SingularityStageExecutionState.EXECUTED else if (capture.lensShadingMapAvailable) SingularityStageExecutionState.FALLBACK_EXECUTED else SingularityStageExecutionState.DISABLED_BY_DEVICE,
                fallbackId = if (capture.vignettingCorrectionProven) "" else "VIGNETTING_METADATA_ONLY",
                fallbackReason = if (capture.vignettingCorrectionProven) "" else "Vignetting correction is not separately proven beyond lens-shading metadata.",
                outputs = listOf("vignettingCorrectionProven=${capture.vignettingCorrectionProven}"),
                references = capture.refs("vignettingCorrectionProven=${capture.vignettingCorrectionProven}")
            ),
            stage(26, "SENSOR_BANDING_POLICY", capture.rowColumnBandingModel.isNotBlank().toState("ROW_COLUMN_BANDING_MODEL_MISSING"), outputs = listOf(capture.rowColumnBandingModel.ifBlank { "banding_model_unavailable" }), references = capture.refs("rowColumnBandingModel=${capture.rowColumnBandingModel}")),
            stage(27, "CAMERA2_CAPTURE_RESULT", (capture.sensorNoiseProfilePairs > 0).toState("SENSOR_NOISE_PROFILE_MISSING"), outputs = listOf("noiseProfilePairs=${capture.sensorNoiseProfilePairs}"), references = capture.refs("sensorNoiseProfilePairs=${capture.sensorNoiseProfilePairs}")),
            stage(28, "CAMERA2_CAPTURE_RESULT", ((capture.sensorSensitivityIso ?: 0) > 0).toState("ISO_GAIN_METADATA_MISSING"), outputs = listOf("iso=${capture.sensorSensitivityIso ?: 0}"), references = capture.refs("sensorSensitivityIso=${capture.sensorSensitivityIso ?: 0}")),
            stage(29, "RAW_CALIBRATION_POLICY", capture.whiteSaturationTagged.toState("WHITE_SATURATION_TAG_MISSING"), outputs = listOf("whiteSaturationTagged=${capture.whiteSaturationTagged}"), references = capture.refs("whiteSaturationTagged=${capture.whiteSaturationTagged}")),
            stage(30, "SENSOR_CALIBRATION_POLICY", (capture.calibrationConfidence > 0.0).toState("CALIBRATION_CONFIDENCE_MISSING"), outputs = listOf("calibrationConfidence=${capture.calibrationConfidence.formatConfidence()}"), confidence = capture.calibrationConfidence.coerceIn(0.0, 1.0), references = capture.refs("calibrationConfidence=${capture.calibrationConfidence.formatConfidence()}")),
            stage(31, "SENSOR_CALIBRATION_POLICY", capture.calibratedSensorOutput.isNotBlank().toState("CALIBRATED_SENSOR_OUTPUT_MISSING"), outputs = listOf(capture.calibratedSensorOutput.ifBlank { "calibrated_sensor_output_unavailable" }), confidence = capture.calibrationConfidence.coerceIn(0.0, 1.0).takeIf { it > 0.0 } ?: 0.5, references = capture.refs("calibratedSensorOutput=${capture.calibratedSensorOutput}"))
        )

    fun mapOutputStages(output: SingularityOutputStage2Evidence): List<SingularityStageEvidence> =
        listOf(
            stage(
                224,
                "OUTPUT_MODE_POLICY",
                when {
                    output.outputCapabilityValidated && !output.outputModeMismatch -> SingularityStageExecutionState.EXECUTED
                    output.outputModeMismatch -> SingularityStageExecutionState.FAILED_WITH_DOWNGRADE
                    else -> SingularityStageExecutionState.DISABLED_BY_DEVICE
                },
                inputs = listOf("modeSelection", "codecCapability"),
                outputs = listOf("${output.outputModeRequested}->${output.outputModeActual}"),
                fallbackId = if (output.outputCapabilityValidated && !output.outputModeMismatch) "" else "OUTPUT_MODE_CAPABILITY_NOT_PROVEN",
                fallbackReason = if (output.outputCapabilityValidated && !output.outputModeMismatch) "" else "Requested output mode was not proven by capability and actual-output evidence.",
                references = output.refs("outputMode=${output.outputModeRequested}->${output.outputModeActual}")
            ),
            stage(225, "OUTPUT_TRANSFORM_POLICY", output.outputTransform.isNotBlank().toState("OUTPUT_TRANSFORM_MISSING"), outputs = listOf(output.outputTransform.ifBlank { "output_transform_unavailable" }), references = output.refs("outputTransform=${output.outputTransform}")),
            stage(
                226,
                "BIT_DEPTH_POLICY",
                when {
                    output.encodedBitDepth > 0 && (!output.requiresTenBitOutput || output.encodedBitDepth >= 10) -> SingularityStageExecutionState.EXECUTED
                    output.requiresTenBitOutput && output.encodedBitDepth in 1..9 -> SingularityStageExecutionState.FAILED_WITH_DOWNGRADE
                    else -> SingularityStageExecutionState.DISABLED_BY_DEVICE
                },
                outputs = listOf("encoded=${output.encodedBitDepth};source=${output.sourceBitDepth};storage=${output.storageBitDepth};encoderSurface=${output.encoderSurfaceBitDepth}"),
                fallbackId = if (output.requiresTenBitOutput && output.encodedBitDepth in 1..9) "TEN_BIT_OUTPUT_NOT_PROVEN" else "",
                fallbackReason = if (output.requiresTenBitOutput && output.encodedBitDepth in 1..9) "Requested 10-bit-class output resolved to ${output.encodedBitDepth}-bit evidence." else "",
                references = output.refs("encodedBitDepth=${output.encodedBitDepth}")
            ),
            stage(227, "COLOR_METADATA_POLICY", output.primaries.isNotBlank().toState("OUTPUT_PRIMARIES_MISSING"), outputs = listOf(output.primaries.ifBlank { "primaries_unavailable" }), references = output.refs("primaries=${output.primaries}")),
            stage(228, "COLOR_METADATA_POLICY", output.transfer.isNotBlank().toState("OUTPUT_TRANSFER_MISSING"), outputs = listOf(output.transfer.ifBlank { "transfer_unavailable" }), references = output.refs("transfer=${output.transfer}")),
            stage(229, "COLOR_METADATA_POLICY", (output.matrix.isNotBlank() || output.range.isNotBlank()).toState("OUTPUT_RANGE_MATRIX_MISSING"), outputs = listOf("matrix=${output.matrix};range=${output.range}"), references = output.refs("matrix=${output.matrix};range=${output.range}")),
            stage(
                230,
                "VULKAN_OR_PRIVATE_MASTER",
                when {
                    output.gpuProcessingValidated -> SingularityStageExecutionState.EXECUTED
                    output.rawSensorOriginal || output.npv10MasterAvailable -> SingularityStageExecutionState.IDENTITY_PASS
                    else -> SingularityStageExecutionState.FALLBACK_EXECUTED
                },
                outputs = listOf("gpuProcessingValidated=${output.gpuProcessingValidated};processedFrames=${output.processedRecordingFramesValidated}"),
                fallbackId = if (output.gpuProcessingValidated || output.rawSensorOriginal || output.npv10MasterAvailable) "" else "FINAL_GPU_OUTPUT_TRANSFORM_NOT_PROVEN",
                fallbackReason = if (output.gpuProcessingValidated || output.rawSensorOriginal || output.npv10MasterAvailable) "" else "Processed recording did not prove a final Vulkan/private-master output transform.",
                gpuDurationNs = output.averageGpuDurationNs,
                references = output.refs("gpuProcessingValidated=${output.gpuProcessingValidated}")
            ),
            stage(231, "MEDIACODEC_OR_PRIVATE_MASTER", output.codec.isNotBlank().toState("ENCODER_CAPABILITY_PROBE_MISSING"), outputs = listOf(output.codec.ifBlank { "codec_unavailable" }), references = output.refs("codec=${output.codec}")),
            stage(232, "CODEC_PROFILE_POLICY", (output.codecProfile.isNotBlank() || output.codec.isNotBlank()).toState("CODEC_PROFILE_SELECTION_MISSING"), outputs = listOf("codec=${output.codec};profile=${output.codecProfile};level=${output.codecLevel}"), references = output.refs("codecProfile=${output.codecProfile};codecLevel=${output.codecLevel}")),
            stage(233, "BITRATE_GOP_POLICY", (output.bitrateBps > 0 || output.appliedBitrateBps > 0).toState("BITRATE_GOP_PLAN_MISSING"), outputs = listOf("bitrate=${output.bitrateBps};requested=${output.requestedBitrateBps};applied=${output.appliedBitrateBps};measured=${output.measuredOutputBitrateBps};gop=${output.gopPlan}"), references = output.refs("bitrateBps=${output.bitrateBps};applied=${output.appliedBitrateBps}")),
            stage(
                234,
                "ENCODER_SURFACE_POLICY",
                when {
                    output.encoderSurfaceBound && output.encoderSurfacePrecisionProven -> SingularityStageExecutionState.EXECUTED
                    output.rawSensorOriginal || output.npv10MasterAvailable -> SingularityStageExecutionState.IDENTITY_PASS
                    output.encoderSurfaceBound -> SingularityStageExecutionState.FALLBACK_EXECUTED
                    else -> SingularityStageExecutionState.DISABLED_BY_DEVICE
                },
                outputs = listOf("bound=${output.encoderSurfaceBound};format=${output.encoderSurfaceFormat};precisionProven=${output.encoderSurfacePrecisionProven}"),
                fallbackId = if (output.encoderSurfaceBound && !output.encoderSurfacePrecisionProven) "ENCODER_SURFACE_PRECISION_NOT_PROVEN" else "",
                fallbackReason = if (output.encoderSurfaceBound && !output.encoderSurfacePrecisionProven) "Encoder surface was bound but precision proof is incomplete." else "",
                references = output.refs("encoderSurfaceBound=${output.encoderSurfaceBound};precision=${output.encoderSurfacePrecisionProven}")
            ),
            stage(
                235,
                "TIMESTAMP_CADENCE_POLICY",
                (output.expectedFrameCount > 0L && output.actualFrameCount >= output.expectedFrameCount && output.droppedFrames <= output.maxAllowedDroppedFrames)
                    .toState("TIMESTAMP_CADENCE_NOT_PROVEN"),
                outputs = listOf("expected=${output.expectedFrameCount};actual=${output.actualFrameCount};dropped=${output.droppedFrames}"),
                references = output.refs("expectedFrameCount=${output.expectedFrameCount};actualFrameCount=${output.actualFrameCount}")
            ),
            stage(236, "ENCODER_SUBMISSION", (output.submittedFrames > 0L || output.actualFrameCount > 0L).toState("ENCODER_FRAME_SUBMISSION_MISSING"), outputs = listOf("submitted=${output.submittedFrames};actual=${output.actualFrameCount}"), references = output.refs("submittedFrames=${output.submittedFrames};actualFrameCount=${output.actualFrameCount}")),
            stage(237, "MUXER_PRIVATE_MASTER_WRITER", (output.muxerSamplesWritten > 0L || output.npv10MasterAvailable).toState("MUXER_SAMPLE_WRITE_MISSING"), outputs = listOf("muxerSamples=${output.muxerSamplesWritten};npv10=${output.npv10MasterAvailable};payload=${output.npv10PayloadFormat}"), references = output.refs("muxerSamples=${output.muxerSamplesWritten};npv10Master=${output.npv10MasterAvailable}")),
            stage(
                238,
                "CONTAINER_FINALIZATION",
                if (output.finalized && output.recordedFileBytes > 0L) SingularityStageExecutionState.EXECUTED else SingularityStageExecutionState.FAILED_RECORDING_STOPPED,
                outputs = listOf("finalized=${output.finalized};bytes=${output.recordedFileBytes};mediaStore=${output.mediaStoreValidated}"),
                fallbackReason = if (output.finalized && output.recordedFileBytes > 0L) "" else "Output container/private master did not finalize with non-empty file proof.",
                references = output.refs("finalized=${output.finalized};recordedFileBytes=${output.recordedFileBytes}")
            ),
            stage(
                239,
                "PLAYBACK_VALIDATION",
                if (output.playable && output.recordedFileBytes > 0L) SingularityStageExecutionState.EXECUTED else SingularityStageExecutionState.FAILED_WITH_DOWNGRADE,
                outputs = listOf("playable=${output.playable};bytes=${output.recordedFileBytes}"),
                fallbackReason = if (output.playable && output.recordedFileBytes > 0L) "" else "Playable output verification is missing or failed.",
                references = output.refs("playable=${output.playable};recordedFileBytes=${output.recordedFileBytes}")
            )
        )

    private fun Boolean.toState(missingFallbackId: String): SingularityStageExecutionState =
        if (this) SingularityStageExecutionState.EXECUTED else SingularityStageExecutionState.DISABLED_BY_DEVICE

    private fun stage(
        id: Int,
        backend: String,
        state: SingularityStageExecutionState,
        inputs: List<String> = emptyList(),
        outputs: List<String> = emptyList(),
        fallbackId: String = "",
        fallbackReason: String = "",
        gpuDurationNs: Long = 0L,
        cpuDurationNs: Long = 0L,
        confidence: Double = 1.0,
        references: List<String> = emptyList()
    ): SingularityStageEvidence {
        val definition = SingularityFrameGraphRegistry.stageById(id)
            ?: error("Unknown Singularity Stage 2 id $id")
        val visibleFallbackId = when {
            fallbackId.isNotBlank() -> fallbackId
            state == SingularityStageExecutionState.FALLBACK_EXECUTED -> "${definition.stageName.uppercase(Locale.US)}_FALLBACK"
            else -> ""
        }
        val visibleFallbackReason = when {
            fallbackReason.isNotBlank() -> fallbackReason
            state == SingularityStageExecutionState.FALLBACK_EXECUTED -> "Stage executed through a visible compatibility fallback."
            state == SingularityStageExecutionState.DISABLED_BY_DEVICE -> "Stage evidence is unavailable on this device/session."
            else -> ""
        }
        return SingularityStageEvidence.fromDefinition(
            definition = definition,
            executionBackend = backend,
            executionState = state,
            inputResources = inputs.filter { it.isNotBlank() },
            outputResources = outputs.filter { it.isNotBlank() },
            fallbackId = visibleFallbackId,
            fallbackReason = visibleFallbackReason,
            gpuDurationNs = gpuDurationNs,
            cpuControlDurationNs = cpuDurationNs,
            confidence = confidence.coerceIn(0.0, 1.0),
            evidenceReferences = references.filter { it.isNotBlank() }.ifEmpty {
                listOf("stage2:${definition.smfRecordKey}")
            }
        )
    }

    private fun SingularityCaptureStage2Evidence.manualControlSummary(): String =
        "iso=${manualIso ?: -1};exposureNs=${manualExposureNs ?: -1L};wbKelvin=${manualWhiteBalanceKelvin ?: -1};focusDiopters=${manualFocusDistanceDiopters ?: -1f}"

    private fun SingularityCaptureStage2Evidence.refs(vararg extra: String): List<String> =
        evidenceReferences + extra.filter { it.isNotBlank() }

    private fun SingularityOutputStage2Evidence.refs(vararg extra: String): List<String> =
        evidenceReferences + extra.filter { it.isNotBlank() }

    private fun Double.formatConfidence(): String =
        String.format(Locale.US, "%.3f", coerceIn(0.0, 1.0))
}

private fun Int.toStageIdToken(): String = toString().padStart(3, '0')

private fun List<SingularityStageEvidence>.stageIdsCsv(): String =
    joinToString(separator = ",") { it.globalStageId.toStageIdToken() }

private fun List<SingularityStageEvidence>.executionSummary(): String =
    SingularityStageExecutionState.entries.joinToString(separator = " ") { state ->
        "${state.name}=${count { it.executionState == state }}"
    }

private fun SingularityStageEvidence.compactRecord(): String =
    buildString {
        append(executionState.name)
        append("|backend=").append(executionBackend)
        append("|confidence=").append(String.format(Locale.US, "%.3f", confidence))
        if (fallbackId.isNotBlank()) append("|fallbackId=").append(fallbackId)
        if (fallbackReason.isNotBlank()) append("|fallbackReason=").append(fallbackReason.replace('|', '/'))
        append("|refs=").append(evidenceReferences.joinToString(separator = ";").replace('|', '/'))
    }
