package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingularityStage2EvidenceMapperTest {

    @Test
    fun mapsCaptureAndOutputAuthoritiesToCanonicalStageRanges() {
        val bundle = SingularityStage2EvidenceMapper.map(
            capture = completeCaptureEvidence(),
            output = ahbGpuOutputEvidence(copyCountEvidenceProven = false)
        )

        assertEquals((0..31).toList(), bundle.captureStages.map { it.globalStageId })
        assertEquals((224..239).toList(), bundle.outputStages.map { it.globalStageId })
        assertEquals(48, bundle.allStages.size)
        assertEquals(
            SingularityTransportClassification.AHB_GPU_COMPATIBILITY,
            bundle.transportDecision.classification
        )
    }

    @Test
    fun ahbZeroCopyRequiresExplicitCopyCountProof() {
        val unproven = SingularityTransportClassifier.classify(
            ahbGpuOutputEvidence(copyCountEvidenceProven = false)
        )
        val proven = SingularityTransportClassifier.classify(
            ahbGpuOutputEvidence(copyCountEvidenceProven = true)
        )

        assertEquals(SingularityTransportClassification.AHB_GPU_COMPATIBILITY, unproven.classification)
        assertEquals(SingularityTransportClassification.AHB_VULKAN_ZERO_COPY, proven.classification)
    }

    @Test
    fun byteBufferCopyPathNeverClaimsSingularityTransport() {
        val decision = SingularityTransportClassifier.classify(
            ahbGpuOutputEvidence(copyCountEvidenceProven = true).copy(
                gpuProcessingValidated = false,
                processedRecordingFramesValidated = true,
                zeroCopyValidated = false,
                byteBufferReadoutDelivery = true,
                p010DirectBufferCopies = 120L
            )
        )

        assertEquals(
            SingularityTransportClassification.CPU_COMPATIBILITY_NON_SINGULARITY,
            decision.classification
        )
        assertTrue(decision.reason.contains("compatibility", ignoreCase = true))
    }

    @Test
    fun missingOutputFinalizationFailsContainerStage() {
        val bundle = SingularityStage2EvidenceMapper.map(
            capture = completeCaptureEvidence(),
            output = ahbGpuOutputEvidence(copyCountEvidenceProven = true).copy(
                finalized = false,
                playable = false,
                recordedFileBytes = 0L
            )
        )
        val stage238 = bundle.outputStages.single { it.globalStageId == 238 }
        val stage239 = bundle.outputStages.single { it.globalStageId == 239 }

        assertEquals(SingularityStageExecutionState.FAILED_RECORDING_STOPPED, stage238.executionState)
        assertEquals(SingularityStageExecutionState.FAILED_WITH_DOWNGRADE, stage239.executionState)
        assertTrue(stage238.fallbackReason.isNotBlank())
        assertTrue(stage239.fallbackReason.isNotBlank())
    }

    private fun completeCaptureEvidence(): SingularityCaptureStage2Evidence =
        SingularityCaptureStage2Evidence(
            frameIdentity = "camera=0;timestampNs=1000",
            sessionIdentity = "camera=0;sessionGeneration=7",
            recordingIntent = "recording=true;raw=false",
            deviceCapabilitySnapshot = "hardware=LEVEL_3;raw=true;manual=true",
            cameraId = "0",
            physicalCameraId = "0",
            physicalCameraStreamProven = true,
            streamCombinationValidated = true,
            streamGraphMode = "ENCODED_CINEMA",
            sensorRequestValidated = true,
            requestTemplate = "TEMPLATE_RECORD",
            requestTargets = "preview,encoder",
            captureIntent = "VIDEO_RECORD",
            manualIso = 100,
            manualExposureNs = 16_666_666L,
            manualWhiteBalanceKelvin = 5600,
            manualFocusDistanceDiopters = 1.2f,
            frameDurationNs = 16_666_666L,
            requestedFps = 60,
            captureTimestampNs = 1_000L,
            previousCaptureTimestampNs = 500L,
            imuSampleCount = 4,
            imuWindowStartNs = 400L,
            imuWindowEndNs = 1_000L,
            rollingShutterSkewNs = 8_000_000L,
            thermalStatus = "LIGHT",
            memoryStatus = "NORMAL",
            captureResultAvailable = true,
            sensorSensitivityIso = 100,
            exposureTimeNs = 8_333_333L,
            focusDistanceDiopters = 1.2f,
            blackLevelAvailable = true,
            whiteLevel = 4095,
            bayerPattern = "RGGB",
            activeArray = "0,0,4000,3000",
            cropRegion = "0,0,4000,3000",
            opticalBlackCorrectionProven = true,
            fixedPatternNoiseProfile = "heteroscedastic",
            hotPixelMapAvailable = true,
            sensorDefectRepairProven = true,
            lensShadingMapAvailable = true,
            vignettingCorrectionProven = true,
            rowColumnBandingModel = "camera2_sensor_noise_profile",
            sensorNoiseProfilePairs = 4,
            whiteSaturationTagged = true,
            calibrationConfidence = 1.0,
            calibratedSensorOutput = "camera2_calibrated_sensor_metadata"
        )

    private fun ahbGpuOutputEvidence(copyCountEvidenceProven: Boolean): SingularityOutputStage2Evidence =
        SingularityOutputStage2Evidence(
            outputModeRequested = "NDR10X-SINGULARITY",
            outputModeActual = "NDR10X-SINGULARITY",
            outputCapabilityValidated = true,
            outputTransform = "BT.2020|ST2084/PQ|BT.2020-NCL",
            encodedBitDepth = 10,
            sourceBitDepth = 10,
            storageBitDepth = 10,
            primaries = "BT.2020",
            transfer = "ST2084/PQ",
            matrix = "BT.2020-NCL",
            range = "LIMITED",
            gpuProcessingValidated = true,
            processedRecordingFramesValidated = true,
            zeroCopyValidated = true,
            frameImportEnabled = true,
            importedFrames = 240L,
            encoderSurfaceBound = true,
            encoderSurfacePrecisionProven = true,
            encoderSurfaceBitDepth = 10,
            encoderSurfaceFormat = "A2B10G10R10",
            codec = "hevc",
            codecProfile = "Main10",
            bitrateBps = 80_000_000,
            expectedFrameCount = 240L,
            actualFrameCount = 240L,
            submittedFrames = 240L,
            muxerSamplesWritten = 240L,
            finalized = true,
            playable = true,
            recordedFileBytes = 4096L,
            mediaStoreValidated = true,
            copyCountEvidenceProven = copyCountEvidenceProven,
            previewHandoffProven = true,
            encoderHandoffProven = true
        )
}
