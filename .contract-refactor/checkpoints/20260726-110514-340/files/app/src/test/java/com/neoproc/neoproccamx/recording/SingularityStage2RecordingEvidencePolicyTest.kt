package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.SingularityTransportClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingularityStage2RecordingEvidencePolicyTest {

    @Test
    fun metadataForQualityRequestPersistsCanonicalStage2Summary() {
        val metadata = SingularityStage2RecordingEvidencePolicy.metadataFor(
            request = baseRequest(),
            runtimeMetadata = runtimeMetadata()
        )

        assertEquals("48", metadata.getValue("singularityStage2MappedStageCount"))
        assertEquals("000,001,002,003,004,005,006,007,008,009,010,011,012,013,014,015,016,017,018,019,020,021,022,023,024,025,026,027,028,029,030,031", metadata.getValue("singularityStage2CaptureStageIds"))
        assertEquals("224,225,226,227,228,229,230,231,232,233,234,235,236,237,238,239", metadata.getValue("singularityStage2OutputStageIds"))
        assertEquals(
            SingularityTransportClassification.AHB_GPU_COMPATIBILITY.name,
            metadata.getValue("singularityStage2TransportClassification")
        )
        assertTrue(metadata.keys.any { it.contains("stage224") })
        assertTrue(metadata.keys.any { it.contains("stage239") })
    }

    @Test
    fun byteBufferRuntimeMetadataClassifiesAsCpuCompatibility() {
        val metadata = SingularityStage2RecordingEvidencePolicy.metadataFor(
            request = baseRequest().copy(
                gpuProcessingValidated = false,
                zeroCopyValidated = false,
                gpuProcessingPath = "APP_P010_BYTEBUFFER_ENCODER"
            ),
            runtimeMetadata = runtimeMetadata() + mapOf(
                "byteBufferReadoutDeliveryPath" to "true",
                "byteBufferP010DirectBufferCopies" to "60",
                "byteBufferP010NativeDirectCopies" to "0",
                "byteBufferP010CopyFailures" to "0"
            )
        )

        assertEquals(
            SingularityTransportClassification.CPU_COMPATIBILITY_NON_SINGULARITY.name,
            metadata.getValue("singularityStage2TransportClassification")
        )
        assertEquals("60", metadata.getValue("singularityStage2TransportCopyCount"))
    }

    private fun baseRequest(): RecordingQualityEvidenceRequest =
        RecordingQualityEvidenceRequest(
            mode = EliteVideoPipelineMode.HDR,
            selectedProfile = "NDR10+",
            activeTransport = "BT.2100_PQ_MAIN10",
            transfer = "ST2084/PQ",
            primaries = "BT.2020",
            matrix = "BT.2020-NCL",
            codec = "hevc",
            encodedBitDepth = 10,
            internalMathBitDepth = 12,
            sourceBitDepth = 10,
            storageBitDepth = 10,
            width = 3840,
            height = 2160,
            fps = 60,
            bitrateBps = 120_000_000,
            requestedBitrateBps = 120_000_000,
            appliedBitrateBps = 120_000_000,
            measuredOutputBitrateBps = 120_000_000,
            selectedResolution = "3840x2160",
            actualResolution = "3840x2160",
            normalizedHdrVersion = "NDR10+",
            normalizedLogVersion = "OFF",
            rawSensorOriginal = false,
            captureTruthSignature = "capture",
            previewQualitySignature = "quality",
            recordingQualitySignature = "quality",
            expectedFrameCount = 120L,
            actualFrameCount = 120L,
            droppedFrames = 0,
            maxAllowedDroppedFrames = 0,
            probe = null,
            ffprobeValidated = false,
            mediaInfoValidated = false,
            deviceLogValidated = false,
            recordingId = "stage2",
            recordedFileName = "stage2.mp4",
            recordedFileBytes = 4096L,
            mediaStoreValidated = true,
            gpuProcessingValidated = true,
            processedRecordingFramesValidated = true,
            zeroCopyValidated = true,
            gpuProcessingPath = "VULKAN_OFFSCREEN_AHB_EGL_ENCODER",
            zeroCopyEvidencePath = "vulkanImportStats:sessionImportedFrames=120"
        )

    private fun runtimeMetadata(): Map<String, String> =
        mapOf(
            "singularityCameraId" to "0",
            "singularityCameraSessionGeneration" to "4",
            "singularityCaptureTimestampNs" to "1000",
            "singularityPreviousCaptureTimestampNs" to "500",
            "singularityRecordingIntent" to "recording=true;raw=false",
            "singularityDeviceCapabilitySnapshot" to "hardware=LEVEL_3;raw=true;manual=true",
            "singularityStreamGraphMode" to "ENCODED_CINEMA",
            "singularitySensorRequestValidated" to "true",
            "singularityRequestTemplate" to "3",
            "singularityRequestTargets" to "preview,encoder",
            "singularityCaptureIntent" to "3",
            "singularityFrameDurationNs" to "16666666",
            "singularityRequestedFps" to "60",
            "singularityImuSampleCount" to "2",
            "singularityRollingShutterSkewNs" to "8000000",
            "singularityThermalStatus" to "LIGHT",
            "singularityMemoryStatus" to "NORMAL",
            "singularityBlackLevelAvailable" to "true",
            "singularityWhiteLevel" to "4095",
            "singularityBayerPattern" to "RGGB",
            "singularityActiveArray" to "0,0,4000,3000",
            "singularityFixedPatternNoiseProfile" to "heteroscedastic",
            "singularitySensorNoiseProfilePairs" to "4",
            "singularityWhiteSaturationTagged" to "true",
            "singularityCalibrationConfidence" to "1.0",
            "singularityCalibratedSensorOutput" to "camera2_calibrated_sensor_metadata",
            "vulkanFrameImportEnabled" to "true",
            "vulkanSessionImportedFrames" to "120",
            "vulkanEncoderSurfaceBound" to "true",
            "vulkanEncoderSurfacePrecisionProven" to "true",
            "vulkanEncoderSurfaceBitDepth" to "10",
            "vulkanEncoderSurfaceFormat" to "A2B10G10R10",
            "vulkanEncodedProcessedFrames" to "120",
            "offscreenEglSubmittedFrames" to "120",
            "stage2CopyCountEvidenceProven" to "false",
            "stage2PlayableValidated" to "false"
        )
}
