package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelProcessingRealityPolicyTest {

    @Test
    fun `internal hardware import marks tone color denoise and sharpness as real GPU stages for recording`() {
        val report = PixelProcessingRealityPolicy.resolve(
            request = baseRequest(
                feedPath = RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
                nativePreviewActive = true,
                vulkanRuntimeSupported = true,
                aiFrameProcessingActive = false,
                multiFrameVideoProcessingActive = false
            )
        )

        assertTrue(report.previewUsesCustomGpu)
        assertTrue(report.recordingUsesCustomGpu)
        assertTrue(report.customGpuActive)
        assertFalse(report.camera2DirectBypassesCustomProcessing)
        assertEquals(
            PixelProcessingStageReality.REAL_GPU_SHADER,
            report.stage(PixelProcessingStageName.TONE_MAP).recordingReality
        )
        assertEquals(
            PixelProcessingStageReality.REAL_GPU_SHADER,
            report.stage(PixelProcessingStageName.COLOR_PROFILE).recordingReality
        )
        assertEquals(
            PixelProcessingStageReality.REAL_GPU_SHADER,
            report.stage(PixelProcessingStageName.SHARPNESS).recordingReality
        )
        assertEquals(
            PixelProcessingStageReality.REAL_GPU_SHADER,
            report.stage(PixelProcessingStageName.DENOISE).recordingReality
        )
        assertEquals(
            PixelProcessingStageReality.UNSUPPORTED,
            report.stage(PixelProcessingStageName.AI_ENHANCEMENT).recordingReality
        )
        assertEquals(
            PixelProcessingStageReality.PLANNED_ONLY,
            report.stage(PixelProcessingStageName.MULTI_FRAME_HDR).recordingReality
        )
        assertTrue(report.realStageNames.contains("TONE_MAP"))
        assertTrue(report.plannedOnlyStageNames.contains("MULTI_FRAME_HDR"))
        assertTrue(report.unsupportedStageNames.contains("AI_ENHANCEMENT"))
        assertTrue(report.reportSignature.contains("customGpuActive=true"))
        assertTrue(report.reportSignature.contains("customGpuBypass=false"))
    }

    @Test
    fun `offscreen ahb egl encoder marks processed recording stages real only after submitted frames`() {
        val report = PixelProcessingRealityPolicy.resolve(
            request = baseRequest(
                feedPath = RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER,
                nativePreviewActive = false,
                vulkanRuntimeSupported = true,
                aiFrameProcessingActive = false,
                multiFrameVideoProcessingActive = false,
                recordingGpuFrameImportValidated = true
            )
        )

        assertFalse(report.previewUsesCustomGpu)
        assertTrue(report.recordingUsesCustomGpu)
        assertTrue(report.customGpuActive)
        assertFalse(report.camera2DirectBypassesCustomProcessing)
        assertEquals(
            PixelProcessingStageReality.REAL_GPU_SHADER,
            report.stage(PixelProcessingStageName.SHARPNESS).recordingReality
        )
        assertEquals(
            PixelProcessingStageReality.REAL_GPU_SHADER,
            report.stage(PixelProcessingStageName.DENOISE).recordingReality
        )
        assertTrue(report.reportSignature.contains("feed=OFFSCREEN_AHB_EGL_ENCODER"))
        assertTrue(report.reportSignature.contains("recordingGpu=true"))
    }

    @Test
    fun `camera2 preview parity path reports recording bypass instead of claiming custom GPU processing`() {
        val report = PixelProcessingRealityPolicy.resolve(
            request = baseRequest(
                feedPath = RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE,
                nativePreviewActive = true,
                vulkanRuntimeSupported = true,
                aiFrameProcessingActive = false,
                multiFrameVideoProcessingActive = false
            )
        )

        assertTrue(report.previewUsesCustomGpu)
        assertFalse(report.recordingUsesCustomGpu)
        assertFalse(report.customGpuActive)
        assertTrue(report.camera2DirectBypassesCustomProcessing)
        assertEquals(
            PixelProcessingStageReality.CAMERA2_ISP,
            report.stage(PixelProcessingStageName.TONE_MAP).recordingReality
        )
        assertEquals(
            PixelProcessingStageReality.CAMERA2_ISP,
            report.stage(PixelProcessingStageName.COLOR_PROFILE).recordingReality
        )
        assertEquals(
            PixelProcessingStageReality.UNSUPPORTED,
            report.stage(PixelProcessingStageName.SHARPNESS).recordingReality
        )
        assertTrue(report.reportSignature.contains("recordingGpu=false"))
        assertTrue(report.reportSignature.contains("customGpuBypass=true"))
        assertFalse(report.reportSignature.contains("|bypass="))
    }

    @Test
    fun `internal feed is not reported as active gpu recording until imported frames are validated`() {
        val report = PixelProcessingRealityPolicy.resolve(
            request = baseRequest(
                feedPath = RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
                nativePreviewActive = true,
                vulkanRuntimeSupported = true,
                aiFrameProcessingActive = false,
                multiFrameVideoProcessingActive = false,
                recordingGpuFrameImportValidated = false
            )
        )

        assertFalse(report.recordingUsesCustomGpu)
        assertFalse(report.customGpuActive)
        assertTrue(report.reportSignature.contains("recordingGpu=false"))
        assertTrue(report.reportSignature.contains("customGpuActive=false"))
        assertTrue(report.reportSignature.contains("customGpuBypass=false"))
        assertFalse(report.reportSignature.contains("|bypass="))
        assertFalse(report.camera2DirectBypassesCustomProcessing)
    }

    @Test
    fun `ai and multi frame are real only when actual frame processing is active`() {
        val report = PixelProcessingRealityPolicy.resolve(
            request = baseRequest(
                feedPath = RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
                nativePreviewActive = true,
                vulkanRuntimeSupported = true,
                aiFrameProcessingActive = true,
                multiFrameVideoProcessingActive = true
            )
        )

        assertEquals(
            PixelProcessingStageReality.REAL_CPU_ANALYSIS,
            report.stage(PixelProcessingStageName.AI_ENHANCEMENT).recordingReality
        )
        assertEquals(
            PixelProcessingStageReality.REAL_CPU_PROCESSING,
            report.stage(PixelProcessingStageName.MULTI_FRAME_HDR).recordingReality
        )
    }

    private fun PixelProcessingRealityReport.stage(
        name: PixelProcessingStageName
    ): PixelProcessingStageEvidence =
        stages.first { it.stage == name }

    private fun baseRequest(
        feedPath: RecordingFeedPath,
        nativePreviewActive: Boolean,
        vulkanRuntimeSupported: Boolean,
        aiFrameProcessingActive: Boolean,
        multiFrameVideoProcessingActive: Boolean,
        recordingGpuFrameImportValidated: Boolean = true
    ): PixelProcessingRealityRequest {
        val graphContract = VideoPipelineGraphController.resolve(
            VideoPipelineGraphRequest(
                appMode = AppMode.VIDEO,
                cinemaMode = CinemaVideoMode.PRO,
                rawVideoActive = false,
                logEnabled = false,
                requestedHdrMode = "OFF",
                preferredColorSpace = "Rec.709",
                tenBitRequested = true,
                previewWidth = 1920,
                previewHeight = 1080,
                encoderWidth = 3840,
                encoderHeight = 2160,
                fps = 30,
                recordingState = GpuRecordingState.Idle
            )
        )
        val parityPlan = RecordingPipelineParityPolicy.resolve(
            graphContract = graphContract,
            rawSupported = true,
            vulkanRuntimeSupported = vulkanRuntimeSupported,
            actualFeedPathOverride = feedPath
        )
        return PixelProcessingRealityRequest(
            parityPlan = parityPlan,
            nativePreviewActive = nativePreviewActive,
            vulkanRuntimeSupported = vulkanRuntimeSupported,
            aiFrameProcessingActive = aiFrameProcessingActive,
            multiFrameVideoProcessingActive = multiFrameVideoProcessingActive,
            recordingGpuFrameImportValidated = recordingGpuFrameImportValidated
        )
    }
}
