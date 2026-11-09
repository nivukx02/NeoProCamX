package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityCaptureStage2Evidence
import com.neoproc.neoproccamx.quality.SingularityOutputStage2Evidence
import com.neoproc.neoproccamx.quality.SingularityStage2EvidenceBundle
import com.neoproc.neoproccamx.quality.SingularityStage2EvidenceMapper
import java.util.Locale

internal object SingularityStage2RecordingEvidencePolicy {
    fun fromQualityRequest(
        request: RecordingQualityEvidenceRequest,
        runtimeMetadata: Map<String, String>
    ): SingularityStage2EvidenceBundle {
        val capture = captureEvidence(runtimeMetadata)
        val output = outputEvidence(
            request = request,
            runtimeMetadata = runtimeMetadata
        )
        return SingularityStage2EvidenceMapper.map(capture, output)
    }

    fun metadataFor(
        request: RecordingQualityEvidenceRequest,
        runtimeMetadata: Map<String, String>
    ): Map<String, String> =
        fromQualityRequest(request, runtimeMetadata).toMetadata()

    private fun captureEvidence(
        metadata: Map<String, String>
    ): SingularityCaptureStage2Evidence {
        val cameraId = metadata.clean("singularityCameraId")
            ?: metadata.clean("cameraId")
            ?: metadata.clean("camera_id")
            ?: ""
        val sessionGeneration = metadata.long("singularityCameraSessionGeneration")
            .takeIf { it > 0L }
            ?.toString()
            ?: metadata.clean("cameraSessionGeneration").orEmpty()
        val captureTimestampNs = metadata.long("singularityCaptureTimestampNs")
            .takeIf { it > 0L }
            ?: metadata.long("captureTimestampNs")
        val frameIdentity = metadata.clean("singularityFrameIdentity")
            ?: listOfNotNull(
                cameraId.takeIf { it.isNotBlank() }?.let { "camera=$it" },
                captureTimestampNs.takeIf { it > 0L }?.let { "timestampNs=$it" }
            ).joinToString(separator = ";")
        val streamGraphMode = metadata.clean("singularityStreamGraphMode")
            ?: metadata.clean("streamGraphMode")
            ?: if (metadata.boolean("camera2DirectFallback")) "ENCODER_SURFACE_DIRECT" else ""
        val requestTemplate = metadata.clean("singularityRequestTemplate")
            ?: metadata.clean("camera2RequestTemplate")
            ?: ""
        val requestTargets = metadata.clean("singularityRequestTargets")
            ?: metadata.clean("camera2RequestTargets")
            ?: ""
        val captureIntent = metadata.clean("singularityCaptureIntent")
            ?: metadata.clean("camera2CaptureIntent")
            ?: ""
        val requestedFps = metadata.int("singularityRequestedFps")
            .takeIf { it > 0 }
            ?: metadata.int("fps")
        val frameDurationNs = metadata.long("singularityFrameDurationNs")
            .takeIf { it > 0L }
            ?: requestedFps.takeIf { it > 0 }?.let { 1_000_000_000L / it.toLong() }
        val thermalStatus = metadata.clean("singularityThermalStatus")
            ?: metadata.clean("thermalStatus")
            ?: metadata.clean("thermalLabel")
            ?: ""
        val memoryStatus = metadata.clean("singularityMemoryStatus")
            ?: metadata.clean("memoryStatus")
            ?: metadata.clean("memoryWarning")
            ?: ""

        return SingularityCaptureStage2Evidence(
            frameIdentity = frameIdentity,
            sessionIdentity = metadata.clean("singularitySessionIdentity")
                ?: listOfNotNull(
                    cameraId.takeIf { it.isNotBlank() }?.let { "camera=$it" },
                    sessionGeneration.takeIf { it.isNotBlank() }?.let { "sessionGeneration=$it" }
                ).joinToString(separator = ";"),
            recordingIntent = metadata.clean("singularityRecordingIntent")
                ?: metadata.clean("selectedMode")
                ?: metadata.clean("mode")
                ?: "",
            deviceCapabilitySnapshot = metadata.clean("singularityDeviceCapabilitySnapshot")
                ?: metadata.clean("deviceCapabilitySnapshot")
                ?: "",
            cameraId = cameraId,
            physicalCameraId = metadata.clean("singularityPhysicalCameraId")
                ?: metadata.clean("physicalCameraId")
                ?: "",
            physicalCameraStreamProven = metadata.boolean("singularityPhysicalCameraStreamProven") ||
                metadata.boolean("physicalCameraStreamProven"),
            streamCombinationValidated = metadata.boolean("singularityStreamCombinationValidated") ||
                metadata.boolean("streamCombinationValidated") ||
                streamGraphMode.isNotBlank(),
            streamCombinationUnsupported = metadata.boolean("singularityStreamCombinationUnsupported") ||
                streamGraphMode.equals("UNSUPPORTED", ignoreCase = true),
            streamGraphMode = streamGraphMode,
            streamGraphDiagnostics = splitList(
                metadata.clean("singularityStreamGraphDiagnostics")
                    ?: metadata.clean("streamGraphDiagnostics")
                    ?: ""
            ),
            sensorRequestValidated = metadata.boolean("singularitySensorRequestValidated") ||
                requestTemplate.isNotBlank() ||
                requestTargets.isNotBlank() ||
                captureIntent.isNotBlank(),
            requestTemplate = requestTemplate,
            requestTargets = requestTargets,
            captureIntent = captureIntent,
            manualIso = metadata.intNullable("singularityManualIso") ?: metadata.intNullable("manualIso"),
            manualExposureNs = metadata.longNullable("singularityManualExposureNs") ?: metadata.longNullable("manualExposureNs"),
            manualWhiteBalanceKelvin = metadata.intNullable("singularityManualWhiteBalanceKelvin") ?: metadata.intNullable("manualWhiteBalanceKelvin"),
            manualFocusDistanceDiopters = metadata.floatNullable("singularityManualFocusDistanceDiopters") ?: metadata.floatNullable("manualFocusDistanceDiopters"),
            frameDurationNs = frameDurationNs,
            requestedFps = requestedFps,
            captureTimestampNs = captureTimestampNs,
            previousCaptureTimestampNs = metadata.long("singularityPreviousCaptureTimestampNs"),
            imuSampleCount = metadata.int("singularityImuSampleCount"),
            imuWindowStartNs = metadata.long("singularityImuWindowStartNs"),
            imuWindowEndNs = metadata.long("singularityImuWindowEndNs"),
            rollingShutterSkewNs = metadata.longNullable("singularityRollingShutterSkewNs")
                ?: metadata.longNullable("rollingShutterSkewNs"),
            thermalStatus = thermalStatus,
            memoryStatus = memoryStatus,
            captureResultAvailable = metadata.boolean("singularityCaptureResultAvailable") ||
                captureTimestampNs > 0L,
            sensorSensitivityIso = metadata.intNullable("singularitySensorSensitivityIso") ?: metadata.intNullable("sensorSensitivityIso"),
            exposureTimeNs = metadata.longNullable("singularityExposureTimeNs") ?: metadata.longNullable("exposureTimeNs"),
            focusDistanceDiopters = metadata.floatNullable("singularityFocusDistanceDiopters") ?: metadata.floatNullable("focusDistanceDiopters"),
            blackLevelAvailable = metadata.boolean("singularityBlackLevelAvailable") ||
                metadata.clean("singularityBlackLevelPattern").orEmpty().isNotBlank(),
            whiteLevel = metadata.intNullable("singularityWhiteLevel"),
            bayerPattern = metadata.clean("singularityBayerPattern") ?: "",
            activeArray = metadata.clean("singularityActiveArray") ?: "",
            cropRegion = metadata.clean("singularityCropRegion") ?: "",
            opticalBlackCorrectionProven = metadata.boolean("singularityOpticalBlackCorrectionProven"),
            fixedPatternNoiseProfile = metadata.clean("singularityFixedPatternNoiseProfile") ?: "",
            hotPixelMapAvailable = metadata.boolean("singularityHotPixelMapAvailable"),
            sensorDefectRepairProven = metadata.boolean("singularitySensorDefectRepairProven"),
            lensShadingMapAvailable = metadata.boolean("singularityLensShadingMapAvailable"),
            vignettingCorrectionProven = metadata.boolean("singularityVignettingCorrectionProven"),
            rowColumnBandingModel = metadata.clean("singularityRowColumnBandingModel") ?: "",
            sensorNoiseProfilePairs = metadata.int("singularitySensorNoiseProfilePairs"),
            whiteSaturationTagged = metadata.boolean("singularityWhiteSaturationTagged"),
            calibrationConfidence = metadata.double("singularityCalibrationConfidence"),
            calibratedSensorOutput = metadata.clean("singularityCalibratedSensorOutput") ?: "",
            evidenceReferences = listOfNotNull(
                "camera2Stage2Metadata",
                cameraId.takeIf { it.isNotBlank() }?.let { "cameraId=$it" },
                streamGraphMode.takeIf { it.isNotBlank() }?.let { "streamGraph=$it" }
            )
        )
    }

    private fun outputEvidence(
        request: RecordingQualityEvidenceRequest,
        runtimeMetadata: Map<String, String>
    ): SingularityOutputStage2Evidence {
        val importedFrames = maxOf(
            runtimeMetadata.long("vulkanSessionImportedFrames"),
            runtimeMetadata.long("offscreenAhbImportedFrames"),
            runtimeMetadata.long("offscreenEglSubmittedFrames")
        )
        val submittedFrames = maxOf(
            runtimeMetadata.long("vulkanEncodedProcessedFrames"),
            runtimeMetadata.long("offscreenEglSubmittedFrames"),
            runtimeMetadata.long("byteBufferSubmittedFrames"),
            request.actualFrameCount
        )
        val muxerSamples = maxOf(
            runtimeMetadata.long("muxerSamplesWritten"),
            runtimeMetadata.long("encodedVideoSampleCount"),
            request.actualFrameCount
        )
        val proofPlayable = runtimeMetadata.boolean("stage2PlayableValidated") ||
            runtimeMetadata.boolean("playableOutputValidated") ||
            (
                request.probe != null &&
                    (request.ffprobeValidated || request.mediaInfoValidated)
                ) ||
            (
                request.mode == EliteVideoPipelineMode.NPV10 &&
                    request.mediaInfoValidated &&
                    request.npv10MasterAvailable &&
                    request.recordedFileBytes > 0L
                )
        return SingularityOutputStage2Evidence(
            outputModeRequested = request.requestedSingularityModeName(),
            outputModeActual = request.actualSingularityModeName(),
            outputCapabilityValidated = request.recordedFileBytes > 0L &&
                request.width > 0 &&
                request.height > 0 &&
                request.fps > 0,
            outputTransform = "${request.primaries}|${request.transfer}|${request.matrix}|${request.activeTransport}",
            encodedBitDepth = request.encodedBitDepth,
            sourceBitDepth = request.sourceBitDepth,
            storageBitDepth = request.storageBitDepth,
            primaries = request.primaries,
            transfer = request.transfer,
            matrix = request.matrix,
            range = request.probe?.colorRange.orEmpty()
                .ifBlank { runtimeMetadata.clean("colorRange").orEmpty() },
            gpuProcessingValidated = request.gpuProcessingValidated,
            processedRecordingFramesValidated = request.processedRecordingFramesValidated,
            zeroCopyValidated = request.zeroCopyValidated,
            frameImportEnabled = runtimeMetadata.boolean("vulkanFrameImportEnabled"),
            importedFrames = importedFrames,
            failedImports = maxOf(
                runtimeMetadata.long("vulkanSessionFailedImports"),
                runtimeMetadata.long("offscreenEglFailedFrames")
            ),
            encoderSurfaceBound = runtimeMetadata.boolean("vulkanEncoderSurfaceBound") ||
                runtimeMetadata.boolean("offscreenEglEncoderSurfaceBound") ||
                runtimeMetadata.boolean("vulkanEncoderSurfaceBoundAtValidation"),
            encoderSurfacePrecisionProven =
                runtimeMetadata.boolean("vulkanEncoderSurfacePrecisionProven"),
            encoderSurfaceBitDepth = runtimeMetadata.int("vulkanEncoderSurfaceBitDepth"),
            encoderSurfaceFormat = runtimeMetadata.clean("vulkanEncoderSurfaceFormat") ?: "",
            codec = request.codec,
            codecProfile = runtimeMetadata.clean("codecProfile")
                ?: runtimeMetadata.clean("encoderProfile")
                ?: request.probe?.codec.orEmpty(),
            codecLevel = runtimeMetadata.clean("codecLevel")
                ?: runtimeMetadata.clean("encoderLevel")
                ?: "",
            bitrateBps = request.bitrateBps,
            requestedBitrateBps = request.requestedBitrateBps,
            appliedBitrateBps = request.appliedBitrateBps,
            measuredOutputBitrateBps = request.measuredOutputBitrateBps,
            gopPlan = runtimeMetadata.clean("gopPlan") ?: "",
            expectedFrameCount = request.expectedFrameCount,
            actualFrameCount = request.actualFrameCount,
            droppedFrames = request.droppedFrames,
            maxAllowedDroppedFrames = request.maxAllowedDroppedFrames,
            submittedFrames = submittedFrames,
            muxerSamplesWritten = muxerSamples,
            finalized = request.recordedFileBytes > 0L &&
                (request.mediaStoreValidated || request.mediaInfoValidated || request.mode == EliteVideoPipelineMode.NPV10),
            playable = proofPlayable,
            recordedFileBytes = request.recordedFileBytes,
            mediaStoreValidated = request.mediaStoreValidated,
            rawSensorOriginal = request.rawSensorOriginal,
            npv10MasterAvailable = request.npv10MasterAvailable,
            npv10PayloadFormat = request.npv10PayloadFormat,
            camera2DirectFallback = runtimeMetadata.boolean("camera2DirectFallback") ||
                request.gpuProcessingPath.contains("CAMERA2", ignoreCase = true),
            byteBufferReadoutDelivery = runtimeMetadata.boolean("byteBufferReadoutDeliveryPath") ||
                request.gpuProcessingPath.equals("APP_P010_BYTEBUFFER_ENCODER", ignoreCase = true),
            p010NativeDirectCopies = runtimeMetadata.long("byteBufferP010NativeDirectCopies"),
            p010DirectBufferCopies = runtimeMetadata.long("byteBufferP010DirectBufferCopies"),
            p010CopyFailures = runtimeMetadata.long("byteBufferP010CopyFailures"),
            readbackCount = runtimeMetadata.long("fullFrameCpuReadbackCount"),
            copyCountEvidenceProven = runtimeMetadata.boolean("stage2CopyCountEvidenceProven") ||
                runtimeMetadata.containsKey("fullFrameCpuReadbackCount") ||
                runtimeMetadata.containsKey("byteBufferP010NativeDirectCopies") ||
                runtimeMetadata.containsKey("byteBufferP010DirectBufferCopies"),
            perFrameAllocationCount = runtimeMetadata.long("perFrameAllocationCount"),
            poolSummary = runtimeMetadata.clean("stage2PoolSummary")
                ?: runtimeMetadata.clean("byteBufferP010AdmissionCapacity")
                ?.let { "p010AdmissionCapacity=$it" }
                ?: "",
            syncPolicy = runtimeMetadata.clean("vulkanSyncPolicy")
                ?: runtimeMetadata.clean("syncPolicy")
                ?: "",
            queueSubmit = runtimeMetadata.clean("vulkanQueueSubmit")
                ?: runtimeMetadata.clean("queueSubmit")
                ?: "",
            ahbFormat = runtimeMetadata.clean("vulkanAhbFormat")
                ?: runtimeMetadata.clean("vulkanEncoderSourceFormat")
                ?: "",
            ahbUsage = runtimeMetadata.clean("vulkanAhbUsage") ?: "",
            ahbStride = runtimeMetadata.int("vulkanAhbStride"),
            previewHandoffProven = runtimeMetadata.boolean("vulkanPreviewSurfaceBound"),
            encoderHandoffProven = runtimeMetadata.boolean("vulkanEncoderSurfaceBound") ||
                runtimeMetadata.boolean("offscreenEglEncoderSurfaceBound"),
            averageGpuDurationNs = runtimeMetadata.double("vulkanAverageComputeMs")
                .takeIf { it > 0.0 }
                ?.let { (it * 1_000_000.0).toLong() }
                ?: 0L,
            averageCpuCopyDurationNs = runtimeMetadata.long("byteBufferP010AverageCopyUs")
                .takeIf { it > 0L }
                ?.let { it * 1_000L }
                ?: 0L,
            privateMasterGpuExportProven = runtimeMetadata.boolean("privateMasterGpuExportProven") ||
                runtimeMetadata.boolean("npv10PrivateMasterGpuExportProven"),
            rawPrivateMasterIntegrityProven =
                (
                    request.rawSensorOriginal &&
                        request.recordedFileBytes > 0L &&
                        (
                            request.mediaInfoValidated ||
                                request.ffprobeValidated ||
                                request.npv10MasterAvailable
                            )
                    ) ||
                    runtimeMetadata.boolean("rawPrivateMasterIntegrityProven"),
            evidenceReferences = listOf(
                "recordingQualityEvidenceRequest",
                "runtimeEvidenceMetadata",
                "recordingId=${request.recordingId}"
            )
        )
    }

    private fun RecordingQualityEvidenceRequest.requestedSingularityModeName(): String =
        when (mode) {
            EliteVideoPipelineMode.NORMAL -> "NORMAL-SDR-SINGULARITY"
            EliteVideoPipelineMode.PRO -> "PRO-SDR-SINGULARITY"
            EliteVideoPipelineMode.HDR -> "NDR10X-SINGULARITY"
            EliteVideoPipelineMode.LOG -> "NLOG-SINGULARITY"
            EliteVideoPipelineMode.RAW -> "NRAW-SINGULARITY"
            EliteVideoPipelineMode.NPV10 -> "NPV10-SINGULARITY-MASTER"
        }

    private fun RecordingQualityEvidenceRequest.actualSingularityModeName(): String =
        if (selectedResolution == actualResolution) {
            requestedSingularityModeName()
        } else {
            "${requestedSingularityModeName()}-VISIBLE-FALLBACK"
        }

    private fun Map<String, String>.clean(key: String): String? =
        this[key]?.trim()?.takeIf { it.isNotBlank() }

    private fun Map<String, String>.boolean(key: String): Boolean =
        when (clean(key)?.lowercase(Locale.US)) {
            "true", "1", "yes", "supported", "enabled", "pass", "proven" -> true
            else -> false
        }

    private fun Map<String, String>.int(key: String): Int =
        clean(key)?.toIntOrNull() ?: 0

    private fun Map<String, String>.intNullable(key: String): Int? =
        clean(key)?.toIntOrNull()?.takeUnless { it == -1 }

    private fun Map<String, String>.long(key: String): Long =
        clean(key)?.toLongOrNull() ?: 0L

    private fun Map<String, String>.longNullable(key: String): Long? =
        clean(key)?.toLongOrNull()?.takeUnless { it == -1L }

    private fun Map<String, String>.floatNullable(key: String): Float? =
        clean(key)?.toFloatOrNull()?.takeUnless { it == -1f }

    private fun Map<String, String>.double(key: String): Double =
        clean(key)?.toDoubleOrNull() ?: 0.0

    private fun splitList(value: String): List<String> =
        value
            .split(';', '|', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
}
