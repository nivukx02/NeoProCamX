package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingRuntimeEvidencePolicyTest {

    @Test
    fun `process cpu evidence uses only the supplied recording window`() {
        assertEquals(
            150f,
            RecordingRuntimeEvidencePolicy.processCpuUsagePercent(
                startProcessCpuMs = 1_000L,
                startElapsedNs = 2_000_000_000L,
                endProcessCpuMs = 2_500L,
                endElapsedNs = 3_000_000_000L
            )!!,
            0.0001f
        )
    }

    @Test
    fun `process cpu evidence rejects a missing recording start`() {
        assertEquals(
            null,
            RecordingRuntimeEvidencePolicy.processCpuUsagePercent(
                startProcessCpuMs = 0L,
                startElapsedNs = 0L,
                endProcessCpuMs = 2_500L,
                endElapsedNs = 3_000_000_000L
            )
        )
    }

    @Test
    fun `camera2 direct path is reported as bypass and does not claim gpu or zero copy`() {
        val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "recordingUsesCustomGpu" to "false",
                "customProcessingBypassed" to "true",
                "camera2DirectFallback" to "true",
                "parityFeedPath" to "CAMERA2_DIRECT_RECORD_SURFACE"
            )::get
        )

        assertFalse(evidence.allGpuProcessingClaimed)
        assertFalse(evidence.zeroCopyProcessingClaimed)
        assertFalse(evidence.gpuProcessingValidated)
        assertFalse(evidence.zeroCopyValidated)
        assertEquals("CAMERA2_ISP_ENCODED_FALLBACK", evidence.gpuProcessingPath)
        assertEquals("", evidence.zeroCopyEvidencePath)
        assertFalse(evidence.performanceTelemetryValidated)
        assertFalse(evidence.processedRecordingFramesValidated)
    }

    @Test
    fun `camera2 preview parity route is preserved and can disclose applied isp plan`() {
        val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "recordingUsesCustomGpu" to "false",
                "customProcessingBypassed" to "true",
                "camera2DirectFallback" to "true",
                "parityFeedPath" to "CAMERA2_PREVIEW_PARITY_RECORD_SURFACE",
                "ispFallbackRequestApplied" to "true",
                "ispFallbackRequestSummary" to "edge=HQ;nr=HQ;hotPixel=HQ;shading=HQ"
            )::get
        )

        assertEquals("CAMERA2_PREVIEW_PARITY_RECORD_SURFACE", evidence.gpuProcessingPath)
        assertFalse(evidence.gpuProcessingValidated)
        assertFalse(evidence.processedRecordingFramesValidated)
        assertTrue(evidence.ispFallbackRequestApplied)
        assertTrue(evidence.ispFallbackRequestSummary.contains("nr=HQ"))
    }

    @Test
    fun `vulkan import stats validate gpu and zero copy path without fake all gpu claim`() {
        val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "recordingUsesCustomGpu" to "true",
                "customProcessingBypassed" to "false",
                "vulkanFrameImportEnabled" to "true",
                "vulkanEncoderSurfaceBound" to "true",
                "vulkanSessionImportedFrames" to "90",
                "vulkanEncodedProcessedFrames" to "90"
            )::get,
            cpuUsagePercent = 7.25f,
            maxFrameTimeMs = 12.5f
        )

        assertFalse(evidence.allGpuProcessingClaimed)
        assertTrue(evidence.zeroCopyProcessingClaimed)
        assertTrue(evidence.gpuProcessingValidated)
        assertTrue(evidence.processedRecordingFramesValidated)
        assertTrue(evidence.zeroCopyValidated)
        assertEquals("VULKAN_INTERNAL_HARDWARE_IMPORT_ENCODER", evidence.gpuProcessingPath)
        assertEquals("vulkanImportStats:sessionImportedFrames=90", evidence.zeroCopyEvidencePath)
        assertTrue(evidence.performanceTelemetryValidated)
        assertEquals(7.25f, evidence.cpuUsagePercent)
        assertEquals(12.5f, evidence.maxFrameTimeMs)
    }

    @Test
    fun `device runtime proof validates strict zero drop finalized gpu recording`() {
        val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "recordingUsesCustomGpu" to "true",
                "customProcessingBypassed" to "false",
                "vulkanFrameImportEnabled" to "true",
                "offscreenEglEncoderSurfaceBound" to "true",
                "offscreenAhbEglEncoderActive" to "true",
                "offscreenEglSubmittedFrames" to "544"
            )::get,
            cpuUsagePercent = 91.5f,
            maxFrameTimeMs = 40.0f
        )
        val proof = RecordingDeviceRuntimeProofPolicy.evaluate(
            runtimeEvidence = evidence,
            expectedFrameCount = 498L,
            actualFrameCount = 544L,
            droppedFrames = 0,
            recordedFileName = "CAMX_GPU_strict_sdr_4k25.mov",
            recordedFileBytes = 512L,
            mediaStoreValidated = true
        )

        assertTrue(proof.deviceLogValidated)
        assertEquals(
            "in-process:RECORDING_STOP_VERDICT+runtimeEvidence+MediaStore",
            proof.adbLogEvidencePath
        )
        assertEquals("true", proof.metadata.getValue("deviceRuntimeEvidenceValidated"))
    }

    @Test
    fun `device runtime proof fails closed on dropped frames or missing gallery proof`() {
        val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "recordingUsesCustomGpu" to "true",
                "customProcessingBypassed" to "false",
                "vulkanFrameImportEnabled" to "true",
                "offscreenEglEncoderSurfaceBound" to "true",
                "offscreenAhbEglEncoderActive" to "true",
                "offscreenEglSubmittedFrames" to "544"
            )::get,
            cpuUsagePercent = 91.5f,
            maxFrameTimeMs = 40.0f
        )

        val dropped = RecordingDeviceRuntimeProofPolicy.evaluate(
            runtimeEvidence = evidence,
            expectedFrameCount = 544L,
            actualFrameCount = 544L,
            droppedFrames = 1,
            recordedFileName = "CAMX_GPU_strict_sdr_4k25.mov",
            recordedFileBytes = 512L,
            mediaStoreValidated = true
        )
        val notSaved = RecordingDeviceRuntimeProofPolicy.evaluate(
            runtimeEvidence = evidence,
            expectedFrameCount = 498L,
            actualFrameCount = 544L,
            droppedFrames = 0,
            recordedFileName = "CAMX_GPU_strict_sdr_4k25.mov",
            recordedFileBytes = 512L,
            mediaStoreValidated = false
        )

        assertFalse(dropped.deviceLogValidated)
        assertEquals("", dropped.adbLogEvidencePath)
        assertFalse(notSaved.deviceLogValidated)
        assertEquals("", notSaved.adbLogEvidencePath)
    }

    @Test
    fun `device runtime proof fails closed on offscreen import or dispatch losses`() {
        val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "recordingUsesCustomGpu" to "true",
                "customProcessingBypassed" to "false",
                "vulkanFrameImportEnabled" to "true",
                "offscreenEglEncoderSurfaceBound" to "true",
                "offscreenAhbEglEncoderActive" to "true",
                "offscreenEglSubmittedFrames" to "544"
            )::get,
            cpuUsagePercent = 91.5f,
            maxFrameTimeMs = 40.0f
        )
        val proof = RecordingDeviceRuntimeProofPolicy.evaluate(
            runtimeEvidence = evidence,
            expectedFrameCount = 544L,
            actualFrameCount = 544L,
            droppedFrames = 0,
            recordedFileName = "CAMX_GPU_strict_ndr10_4k25.mov",
            recordedFileBytes = 512L,
            mediaStoreValidated = true,
            runtimeMetadata = mapOf(
                "offscreenEglFailedFrames" to "1",
                "vulkanSessionFailedImports" to "1",
                "realtimeDispatchDroppedFrames" to "0",
                "realtimeDispatchRejectedFrames" to "2"
            )
        )

        assertFalse(proof.deviceLogValidated)
        assertEquals("", proof.adbLogEvidencePath)
        assertEquals("false", proof.metadata.getValue("deviceRuntimeHotPathZeroDrop"))
        assertEquals("3", proof.metadata.getValue("deviceRuntimeHotPathDroppedFrames"))
    }

    @Test
    fun `runtime evidence carries gpu enhancement uniform values`() {
        val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "recordingUsesCustomGpu" to "true",
                "customProcessingBypassed" to "false",
                "vulkanFrameImportEnabled" to "true",
                "vulkanEncoderSurfaceBound" to "true",
                "vulkanSessionImportedFrames" to "45",
                "vulkanEncodedProcessedFrames" to "45",
                "enhancementUniformSignature" to "gpu-v1|signal=HDR|packet=f4=0.4200",
                "previewEnhancementUniformSignature" to "gpu-v1|signal=HDR|packet=f4=0.4200",
                "recordingEnhancementUniformSignature" to "gpu-v1|signal=HDR|packet=f4=0.4200",
                "enhancementTemporalBlend" to "0.42",
                "enhancementSpatialDenoise" to "0.73",
                "enhancementChromaDenoise" to "0.96",
                "enhancementSharpening" to "0.31",
                "enhancementSubjectDetailGain" to "0.18",
                "enhancementDetailProfile" to "FOCUSED_DETAIL"
            )::get
        )

        assertEquals("gpu-v1|signal=HDR|packet=f4=0.4200", evidence.enhancementUniformSignature)
        assertEquals("gpu-v1|signal=HDR|packet=f4=0.4200", evidence.previewEnhancementUniformSignature)
        assertEquals("gpu-v1|signal=HDR|packet=f4=0.4200", evidence.recordingEnhancementUniformSignature)
        assertEquals(0.42f, evidence.enhancementTemporalBlend, 0.0001f)
        assertEquals(0.73f, evidence.enhancementSpatialDenoise, 0.0001f)
        assertEquals(0.96f, evidence.enhancementChromaDenoise, 0.0001f)
        assertEquals(0.31f, evidence.enhancementSharpening, 0.0001f)
        assertEquals(0.18f, evidence.enhancementSubjectDetailGain, 0.0001f)
        assertEquals("FOCUSED_DETAIL", evidence.enhancementDetailProfile)
    }

    @Test
    fun `explicit gpu claim still requires proof through quality gate`() {
        val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "allGpuProcessingClaimed" to "true",
                "recordingUsesCustomGpu" to "true",
                "vulkanFrameImportEnabled" to "false",
                "vulkanEncoderSurfaceBound" to "false",
                "vulkanSessionImportedFrames" to "0"
            )::get
        )
        val certificate = RecordingQualityEvidencePolicy.evaluate(
            RecordingQualityEvidencePolicyTestFixtures.validNdrRequest().copy(
                allGpuProcessingClaimed = evidence.allGpuProcessingClaimed,
                zeroCopyProcessingClaimed = evidence.zeroCopyProcessingClaimed,
                gpuProcessingValidated = evidence.gpuProcessingValidated,
                zeroCopyValidated = evidence.zeroCopyValidated,
                gpuProcessingPath = evidence.gpuProcessingPath,
                zeroCopyEvidencePath = evidence.zeroCopyEvidencePath,
                processedRecordingFramesValidated = evidence.processedRecordingFramesValidated
            )
        )

        assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
        assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.GPU_PROCESSING_NOT_PROVEN })
    }

    @Test
    fun `queued live P010 dispatches prove GPU processing but never prove zero copy`() {
        val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "byteBufferP010EncoderActive" to "true",
                "byteBufferSubmittedFrames" to "100",
                "byteBufferEncodedFrames" to "100",
                "liveP010QueuedEnhancedFrames" to "100",
                "liveP010QueuedTemporalFrames" to "99",
                "gpuProcessingPath" to "APP_P010_BYTEBUFFER_VULKAN_TEMPORAL"
            )::get
        )

        assertTrue(evidence.gpuProcessingValidated)
        assertTrue(evidence.processedRecordingFramesValidated)
        assertFalse(evidence.zeroCopyValidated)
        assertFalse(evidence.zeroCopyProcessingClaimed)
        assertEquals("APP_P010_BYTEBUFFER_VULKAN_TEMPORAL", evidence.gpuProcessingPath)
    }

    @Test
    fun `impossible live P010 processed count fails closed`() {
        val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "byteBufferP010EncoderActive" to "true",
                "byteBufferSubmittedFrames" to "10",
                "byteBufferEncodedFrames" to "10",
                "liveP010QueuedEnhancedFrames" to "11",
                "gpuProcessingValidated" to "true"
            )::get
        )

        assertFalse(evidence.gpuProcessingValidated)
        assertTrue(evidence.processedRecordingFramesValidated)
    }

    @Test
    fun `metadata gpu validated flag cannot replace encoder surface and imported frame proof`() {
        val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "recordingUsesCustomGpu" to "true",
                "customProcessingBypassed" to "false",
                "gpuProcessingValidated" to "true",
                "zeroCopyValidated" to "true",
                "vulkanFrameImportEnabled" to "true",
                "vulkanEncoderSurfaceBound" to "false",
                "vulkanSessionImportedFrames" to "0",
                "vulkanEncodedProcessedFrames" to "90"
            )::get
        )

        assertFalse(evidence.gpuProcessingValidated)
        assertFalse(evidence.zeroCopyValidated)
        assertFalse(evidence.processedRecordingFramesValidated)
        assertEquals("VULKAN_INTERNAL_HARDWARE_IMPORT_ENCODER", evidence.gpuProcessingPath)
    }

    @Test
    fun `niv99 recording evidence validates only exact processed persisted equality`() {
        val complete = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "niv99RecordingRequired" to "true",
                "niv99RecordingProcessedFrames" to "125",
                "niv99RecordingPersistedFrames" to "125",
                "niv99RecordingExpectedFrames" to "125",
                "niv99RecordingFailedFrames" to "0",
                "gpuProcessingPath" to "NIV99_NATIVE_P010_PACKED10"
            )::get
        )
        val missingFrame = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "niv99RecordingRequired" to "true",
                "niv99RecordingProcessedFrames" to "124",
                "niv99RecordingPersistedFrames" to "124",
                "niv99RecordingExpectedFrames" to "125",
                "niv99RecordingFailedFrames" to "0"
            )::get
        )
        val failedFrame = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "niv99RecordingRequired" to "true",
                "niv99RecordingProcessedFrames" to "125",
                "niv99RecordingPersistedFrames" to "125",
                "niv99RecordingExpectedFrames" to "125",
                "niv99RecordingFailedFrames" to "1"
            )::get
        )

        assertTrue(complete.processedRecordingFramesValidated)
        assertFalse(complete.gpuProcessingValidated)
        assertEquals("NIV99_NATIVE_P010_PACKED10", complete.gpuProcessingPath)
        assertFalse(missingFrame.processedRecordingFramesValidated)
        assertFalse(failedFrame.processedRecordingFramesValidated)
    }

    @Test
    fun `camera2 preview parity feed is preserved as hdr parity evidence instead of isp fallback`() {
        val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
            metadataLookup = mapOf(
                "recordingUsesCustomGpu" to "false",
                "customProcessingBypassed" to "true",
                "camera2DirectFallback" to "true",
                "parityFeedPath" to "CAMERA2_PREVIEW_PARITY_RECORD_SURFACE"
            )::get
        )

        assertEquals("CAMERA2_PREVIEW_PARITY_RECORD_SURFACE", evidence.gpuProcessingPath)
        assertEquals(false, evidence.gpuProcessingValidated)
        assertEquals(false, evidence.processedRecordingFramesValidated)
    }

    @Test
    fun `encoded and npv10 managers wire runtime evidence into certificate logs`() {
        val root = listOf(
            File("src/main/java/com/neoproc/neoproccamx/recording"),
            File("app/src/main/java/com/neoproc/neoproccamx/recording")
        ).first { it.isDirectory }
        val uiRoot = listOf(
            File("src/main/java/com/neoproc/neoproccamx/ui"),
            File("app/src/main/java/com/neoproc/neoproccamx/ui")
        ).first { it.isDirectory }
        val recordingManager = File(root, "RecordingManager.kt").readText()
        val npv10Manager = File(root, "Npv10RecordingManager.kt").readText()
        val viewModel = File(uiRoot, "CameraViewModel.kt").readText()
        val runtimeFormatter = File(uiRoot, "RecordingRuntimeEvidenceFormatter.kt").readText()

        assertTrue(recordingManager.contains("RecordingRuntimeEvidencePolicy.fromMetadata"))
        assertTrue(recordingManager.contains("allGpuProcessingClaimed = runtimeEvidence.allGpuProcessingClaimed"))
        assertTrue(recordingManager.contains("gpuProcessingPath=${'$'}{certificate.metadata[\"gpuProcessingPath\"]}"))
        assertTrue(npv10Manager.contains("RecordingRuntimeEvidencePolicy.fromMetadata"))
        assertTrue(npv10Manager.contains("detailMetadata = runtimeMetadata"))
        assertTrue(
            npv10Manager.contains(
                "processedRecordingFramesValidated = runtimeEvidence.processedRecordingFramesValidated"
            )
        )
        assertTrue(
            npv10Manager.contains(
                "enhancementUniformSignature = runtimeEvidence.enhancementUniformSignature"
            )
        )
        assertTrue(
            npv10Manager.contains(
                "enhancementDetailProfile = runtimeEvidence.enhancementDetailProfile"
            )
        )
        assertTrue(npv10Manager.contains("gpuProcessingPath=${'$'}{certificate.metadata[\"gpuProcessingPath\"]}"))
        assertTrue(npv10Manager.contains("finalizedRecordingCpuUsagePercent = calculateRecordingCpuUsagePercent()"))
        assertTrue(
            npv10Manager.indexOf("finalizedRecordingCpuUsagePercent = calculateRecordingCpuUsagePercent()") <
                npv10Manager.indexOf("activeWriter.buildDeviceEvidence")
        )
        assertTrue(
            npv10Manager.contains(
                "cpuUsagePercent = finalizedRecordingCpuUsagePercent ?: calculateRecordingCpuUsagePercent()"
            )
        )
        assertTrue(viewModel.contains("RecordingRuntimeEvidenceFormatter.format("))
        assertTrue(runtimeFormatter.contains("\"vulkanEncodedProcessedFrames\""))
        assertTrue(viewModel.contains("stats.encoderSurfaceBound"))
        assertTrue(runtimeFormatter.contains("\"realtimeDispatchDroppedFrames\""))
        assertTrue(viewModel.contains("pipeline.realtimeDispatchTelemetry()"))
        assertTrue(recordingManager.contains("strictRuntimeHotPathDroppedFramesForEvidence"))
        assertTrue(recordingManager.contains("runtimeMetadata = runtimeMetadata"))
    }
}

private object RecordingQualityEvidencePolicyTestFixtures {
    fun validNdrRequest(): RecordingQualityEvidenceRequest =
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
            width = 1920,
            height = 1080,
            fps = 30,
            bitrateBps = 160_000_000,
            selectedResolution = "1920x1080",
            actualResolution = "1920x1080",
            normalizedHdrVersion = "NDR10+",
            normalizedLogVersion = "N-Log Base",
            rawSensorOriginal = false,
            captureTruthSignature =
                "HDR_PQ|HDR_10BIT|Rec.2020-NDR10+|hdr=NDR10+|tenBit=true|log=false|raw=false|hdrMetadata=true",
            previewQualitySignature =
                "HDR|CAMERA2_PREVIEW_PARITY_RECORD_SURFACE|Rec.2020-NDR10+|HDR_CLEAN|HDR_HIGHLIGHT_SAFE|USER_MANUAL_COLOR",
            recordingQualitySignature =
                "HDR|CAMERA2_PREVIEW_PARITY_RECORD_SURFACE|Rec.2020-NDR10+|HDR_CLEAN|HDR_HIGHLIGHT_SAFE|USER_MANUAL_COLOR",
            expectedFrameCount = 30,
            actualFrameCount = 30,
            droppedFrames = 0,
            maxAllowedDroppedFrames = 0,
            probe = RecordingProbeMetadata(
                codec = "hevc",
                pixelFormat = "yuv420p10le",
                encodedBitDepth = 10,
                primaries = "BT.2020",
                transfer = "ST2084/PQ",
                matrix = "BT.2020-NCL",
                colorRange = "LIMITED",
                width = 1920,
                height = 1080,
                fps = 30,
                bitrateBps = 180_000_000
            ),
            ffprobeValidated = true,
            mediaInfoValidated = true,
            deviceLogValidated = true,
            processedRecordingFramesValidated = true
        )
}
