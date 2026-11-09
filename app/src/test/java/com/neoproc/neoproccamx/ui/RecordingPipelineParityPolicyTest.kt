package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.Camera2CaptureFrameEvidence
import com.neoproc.neoproccamx.camera.Camera2FrameLineageAccumulator
import com.neoproc.neoproccamx.camera.Camera2RequestTag
import com.neoproc.neoproccamx.rendering.VulkanRuntimeManager
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPipelineParityPolicyTest {

    @Test
    fun `sdr recording parity uses internal hardware import when vulkan is supported`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true
        )

        assertEquals(VideoDeliveryKind.SDR, plan.deliveryKind)
        assertEquals(RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER, plan.feedPath)
        assertFalse(plan.hdrRequestedForSessionPlan)
        assertFalse(plan.rawRequestedForSessionPlan)
        assertFalse(plan.logEnabledForSessionPlan)
        assertTrue(plan.tenBitRequestedForSessionPlan)
        assertEquals("OFF", plan.encoderHdrMode)
        assertEquals("Rec.709", plan.encoderColorSpace)
        assertFalse(plan.encoderLogEnabled)
        assertFalse(plan.encoderRawEnabled)
        assertEquals(
            plan.parityCertificate.failureCodes.joinToString(separator = ","),
            PreviewRecordingParityStatus.PASS,
            plan.parityCertificate.status
        )
        assertEquals(plan.sharedDecision.previewSignature, plan.sharedDecision.recordingSignature)
        assertFalse(plan.parityCertificate.camera2DirectFallback)
    }

    @Test
    fun `log recording parity overrides stale hdr request and uses internal hardware import`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.LOG,
            logEnabled = true,
            requestedHdrMode = "HDR10",
            tenBitRequested = true
        )

        assertEquals(VideoDeliveryKind.LOG, plan.deliveryKind)
        assertEquals(RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER, plan.feedPath)
        assertFalse(plan.hdrRequestedForSessionPlan)
        assertFalse(plan.rawRequestedForSessionPlan)
        assertTrue(plan.logEnabledForSessionPlan)
        assertTrue(plan.tenBitRequestedForSessionPlan)
        assertEquals("OFF", plan.encoderHdrMode)
        assertEquals("Rec.2020-LOG", plan.encoderColorSpace)
        assertTrue(plan.encoderLogEnabled)
        assertEquals(
            plan.parityCertificate.failureCodes.joinToString(separator = ","),
            PreviewRecordingParityStatus.PASS,
            plan.parityCertificate.status
        )
        assertFalse(plan.sharedDecision.hdrTransportRequired)
        assertTrue(plan.sharedDecision.logSdrWideGamut)
        assertEquals(VideoPipelineToneMap.LOG_ORIGINAL, plan.sharedDecision.toneMap)
    }

    @Test
    fun `log recording parity forces hdr off while keeping ten bit log identity`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.LOG,
            logEnabled = true,
            requestedHdrMode = "OFF",
            tenBitRequested = true
        )

        assertEquals(VideoDeliveryKind.LOG, plan.deliveryKind)
        assertEquals(RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER, plan.feedPath)
        assertFalse(plan.hdrRequestedForSessionPlan)
        assertFalse(plan.rawRequestedForSessionPlan)
        assertTrue(plan.logEnabledForSessionPlan)
        assertTrue(plan.tenBitRequestedForSessionPlan)
        assertEquals("OFF", plan.encoderHdrMode)
        assertEquals("Rec.2020-LOG", plan.encoderColorSpace)
        assertTrue(plan.encoderLogEnabled)
        assertEquals(PreviewRecordingParityStatus.PASS, plan.parityCertificate.status)
        assertFalse(plan.sharedDecision.hdrTransportRequired)
        assertTrue(plan.sharedDecision.logSdrWideGamut)
        assertEquals(VideoPipelineToneMap.LOG_ORIGINAL, plan.sharedDecision.toneMap)
        assertEquals("OFF", plan.sharedDecision.effectiveHdrMode)
    }

    @Test
    fun `wide gamut sdr recording parity keeps wide color space and ten bit request`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.2020",
            tenBitRequested = false
        )

        assertEquals(VideoDeliveryKind.SDR, plan.deliveryKind)
        assertFalse(plan.hdrRequestedForSessionPlan)
        assertFalse(plan.logEnabledForSessionPlan)
        assertTrue(plan.tenBitRequestedForSessionPlan)
        assertEquals("OFF", plan.encoderHdrMode)
        assertEquals("Rec.2020", plan.encoderColorSpace)
        assertEquals(PreviewRecordingParityStatus.PASS, plan.parityCertificate.status)
        assertEquals("Rec.2020", plan.sharedDecision.selectedColorSpace)
        assertEquals(VideoPipelineToneMap.WIDE_GAMUT_SDR, plan.sharedDecision.toneMap)
        assertTrue(plan.sharedDecision.previewQualitySignature.contains("tone=WIDE_GAMUT_SDR"))
    }

    @Test
    fun `raw recording parity keeps direct camera2 path and disables hdr log ten bit`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            logEnabled = true,
            requestedHdrMode = "HDR10+",
            rawSupported = true,
            tenBitRequested = true
        )

        assertEquals(VideoDeliveryKind.RAW, plan.deliveryKind)
        assertEquals(RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE, plan.feedPath)
        assertFalse(plan.hdrRequestedForSessionPlan)
        assertTrue(plan.rawRequestedForSessionPlan)
        assertFalse(plan.logEnabledForSessionPlan)
        assertFalse(plan.tenBitRequestedForSessionPlan)
        assertEquals("OFF", plan.encoderHdrMode)
        assertEquals("RAW", plan.encoderColorSpace)
        assertTrue(plan.encoderRawEnabled)
        assertEquals(PreviewRecordingParityStatus.PASS, plan.parityCertificate.status)
        assertTrue(plan.parityCertificate.directCamera2PathDetected)
        assertFalse(plan.parityCertificate.camera2DirectFallback)
        assertTrue(plan.parityCertificate.fallbackReason.contains("RAW_SENSOR"))
    }

    @Test
    fun `hdr recording parity uses internal hardware import when vulkan is supported`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "NDR10+",
            tenBitRequested = true
        )

        assertEquals(VideoDeliveryKind.HDR, plan.deliveryKind)
        assertEquals(RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER, plan.feedPath)
        assertTrue(plan.hdrRequestedForSessionPlan)
        assertEquals("NDR10+", plan.encoderHdrMode)
        assertEquals("Rec.2020-NDR10+", plan.encoderColorSpace)
        assertEquals(PreviewRecordingParityStatus.PASS, plan.parityCertificate.status)
    }

    @Test
    fun `unsupported raw collapses to internal hardware import instead of direct raw path`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            requestedHdrMode = "OFF",
            rawSupported = false,
            tenBitRequested = true
        )

        assertEquals(VideoDeliveryKind.SDR, plan.deliveryKind)
        assertEquals(RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER, plan.feedPath)
        assertFalse(plan.rawRequestedForSessionPlan)
        assertFalse(plan.hdrRequestedForSessionPlan)
        assertFalse(plan.logEnabledForSessionPlan)
        assertFalse(plan.encoderRawEnabled)
        assertEquals("OFF", plan.encoderHdrMode)
        assertEquals("Rec.709", plan.encoderColorSpace)
        assertEquals(PreviewRecordingParityStatus.PASS, plan.parityCertificate.status)
    }

    @Test
    fun `parity signature changes when feed path or delivery identity changes`() {
        val sdr = resolveParity()
        val hdr = resolveParity(requestedHdrMode = "HLG", tenBitRequested = true)
        val raw = resolveParity(cinemaMode = CinemaVideoMode.RAW, rawVideoActive = true)

        assertNotEquals(sdr.paritySignature, hdr.paritySignature)
        assertNotEquals(sdr.paritySignature, raw.paritySignature)
        assertNotEquals(hdr.paritySignature, raw.paritySignature)
    }

    @Test
    fun `parity quality signature locks color tone denoise sharpness and manual color identity`() {
        val sdr = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = false,
            selectedColorScience = "Rec.709 Standard"
        )
        val hdr = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "NDR10+",
            tenBitRequested = true,
            selectedColorScience = "NDR10+"
        )
        val log = resolveParity(
            cinemaMode = CinemaVideoMode.LOG,
            logEnabled = true,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            selectedColorScience = "N-Log Cinema"
        )

        assertTrue(sdr.toString().contains("parityQualitySignature=SDR|INTERNAL_HARDWARE_IMPORT_ENCODER|Rec.709|Rec.709 Standard|ENCODED_VIDEO_STRICT|NATURAL_DETAIL"))
        assertTrue(hdr.toString().contains("parityQualitySignature=HDR|INTERNAL_HARDWARE_IMPORT_ENCODER|Rec.2020-NDR10+|NDR10+|HDR_CLEAN|HDR_HIGHLIGHT_SAFE"))
        assertTrue(log.toString().contains("parityQualitySignature=LOG|INTERNAL_HARDWARE_IMPORT_ENCODER|Rec.2020-LOG|N-Log Cinema|LOG_DETAIL|LOG_EDGE_PRESERVE"))
        assertNotEquals(sdr.paritySignature, hdr.paritySignature)
        assertNotEquals(hdr.paritySignature, log.paritySignature)
    }

    @Test
    fun `preview and recording decisions carry the same explicit manual color config`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Display P3",
            tenBitRequested = true,
            selectedColorScience = "ACEScg"
        )

        assertEquals("Neo Wide Gamut", plan.sharedDecision.selectedColorSpace)
        assertEquals("Neo ACES High End", plan.sharedDecision.selectedColorScience)
        assertEquals(plan.sharedDecision.previewSignature, plan.sharedDecision.recordingSignature)
        assertEquals(plan.sharedDecision.previewQualitySignature, plan.sharedDecision.recordingQualitySignature)
        assertTrue(plan.sharedDecision.previewQualitySignature.contains("colorSpace=Neo Wide Gamut"))
        assertTrue(plan.sharedDecision.previewQualitySignature.contains("colorScience=Neo ACES High End"))
        assertTrue(plan.parityCertificate.certificateSignature.contains("colorScience=Neo ACES High End"))
    }

    @Test
    fun `manual neo wide and aces sdr parity preserves canonical selected identity`() {
        val cases = listOf(
            Triple("Display P3", "Natural", "Neo Wide Gamut"),
            Triple("ACES AP0", "Neo ACES High End", "Neo ACES AP0"),
            Triple("ACEScg", "ACEScg", "Neo ACES AP1")
        )

        cases.forEach { (requestedSpace, requestedScience, expectedSpace) ->
            val plan = resolveParity(
                cinemaMode = CinemaVideoMode.PRO,
                requestedHdrMode = "OFF",
                preferredColorSpace = requestedSpace,
                tenBitRequested = true,
                selectedColorScience = requestedScience
            )

            assertEquals(VideoDeliveryKind.SDR, plan.deliveryKind)
            assertEquals(expectedSpace, plan.encoderColorSpace)
            assertEquals(expectedSpace, plan.sharedDecision.selectedColorSpace)
            assertEquals(VideoPipelineToneMap.WIDE_GAMUT_SDR, plan.sharedDecision.toneMap)
            assertEquals(plan.sharedDecision.previewSignature, plan.sharedDecision.recordingSignature)
            assertEquals(plan.sharedDecision.previewQualitySignature, plan.sharedDecision.recordingQualitySignature)
            assertTrue(plan.sharedDecision.previewQualitySignature.contains("colorSpace=$expectedSpace"))
        }
    }

    @Test
    fun `shared decision certificate matches preview and recording quality for every processed mode`() {
        listOf(
            resolveParity(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "OFF", tenBitRequested = true),
            resolveParity(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "NDR10+", tenBitRequested = true),
            resolveParity(cinemaMode = CinemaVideoMode.LOG, logEnabled = true, requestedHdrMode = "OFF", tenBitRequested = true)
        ).forEach { plan ->
            assertEquals(PreviewRecordingParityStatus.PASS, plan.parityCertificate.status)
            assertEquals(plan.sharedDecision.previewSignature, plan.sharedDecision.recordingSignature)
            assertEquals(plan.sharedDecision.previewQualitySignature, plan.sharedDecision.recordingQualitySignature)
            assertTrue(plan.parityCertificate.failureCodes.isEmpty())
            assertTrue(plan.paritySignature.contains(plan.parityCertificate.certificateSignature))
        }
    }

    @Test
    fun `transport truth gates prevent normal pro and log from becoming hdr`() {
        val normal = resolveParity(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "OFF", tenBitRequested = true)
        val pro = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Display P3",
            tenBitRequested = true
        )
        val log = resolveParity(cinemaMode = CinemaVideoMode.LOG, logEnabled = true, requestedHdrMode = "OFF", tenBitRequested = true)
        val ndr = resolveParity(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "NDR10+", tenBitRequested = true)
        val nivukx = resolveParity(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "NIVUKXVISION", tenBitRequested = true)

        listOf(normal, pro).forEach { plan ->
            assertEquals(VideoDeliveryKind.SDR, plan.deliveryKind)
            assertEquals("OFF", plan.encoderHdrMode)
            assertFalse(plan.sharedDecision.hdrTransportRequired)
        }
        assertEquals(VideoPipelineToneMap.REC709_NEUTRAL, normal.sharedDecision.toneMap)
        assertEquals(VideoPipelineToneMap.WIDE_GAMUT_SDR, pro.sharedDecision.toneMap)
        assertEquals(VideoDeliveryKind.LOG, log.deliveryKind)
        assertEquals("OFF", log.encoderHdrMode)
        assertFalse(log.sharedDecision.hdrTransportRequired)
        assertTrue(log.sharedDecision.logSdrWideGamut)
        assertEquals("Rec.2020-LOG", log.sharedDecision.selectedColorSpace)

        listOf(ndr, nivukx).forEach { plan ->
            assertEquals(VideoDeliveryKind.HDR, plan.deliveryKind)
            assertTrue(plan.sharedDecision.hdrTransportRequired)
            assertTrue(plan.encoderHdrMode != "OFF")
            assertTrue(plan.sharedDecision.selectedColorSpace.startsWith("Rec.2020"))
        }
    }

    @Test
    fun `direct camera2 processed record path is blocked because it bypasses custom gpu processing`() {
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

        val plan = RecordingPipelineParityPolicy.resolve(
            graphContract = graphContract,
            rawSupported = true,
            vulkanRuntimeSupported = true,
            actualFeedPathOverride = RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE
        )

        assertEquals(PreviewRecordingParityStatus.FAIL, plan.parityCertificate.status)
        assertTrue(plan.parityCertificate.directCamera2PathDetected)
        assertTrue(plan.parityCertificate.camera2DirectFallback)
        assertTrue(plan.parityCertificate.recordingBlocked)
        assertTrue(plan.parityCertificate.failureCodes.contains(PreviewRecordingParityFailureCode.CAMERA2_DIRECT_FALLBACK_FOR_PROCESSED_MODE.name))
    }

    @Test
    fun `camera2 preview parity encoded route is reported as isp fallback not gpu parity pass`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            actualFeedPathOverride = RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE
        )

        assertEquals(PreviewRecordingParityStatus.ISP_FALLBACK, plan.parityCertificate.status)
        assertFalse(plan.parityCertificate.directCamera2PathDetected)
        assertFalse(plan.parityCertificate.camera2DirectFallback)
        assertFalse(plan.parityCertificate.recordingBlocked)
        assertEquals("PREVIEW_CONTINUITY_ENCODER_FOR_STABLE_FPS", plan.parityCertificate.fallbackReason)
        assertTrue(plan.parityCertificate.failureCodes.isEmpty())
        assertTrue(plan.parityCertificate.certificateSignature.contains("ISP_FALLBACK"))
        assertFalse(plan.parityCertificate.strictVulkanParityCertified)
        assertTrue(plan.parityCertificate.certificateSignature.contains("strictVulkanParityCertified=false"))
    }

    @Test
    fun `render graph lock blocks recording when shader lut stage or bypass parity diverges`() {
        val graphContract = VideoPipelineGraphController.resolve(
            VideoPipelineGraphRequest(
                appMode = AppMode.VIDEO,
                cinemaMode = CinemaVideoMode.PRO,
                rawVideoActive = false,
                logEnabled = false,
                requestedHdrMode = "NDR10+",
                preferredColorSpace = "Rec.2020",
                tenBitRequested = true,
                previewWidth = 1920,
                previewHeight = 1080,
                encoderWidth = 3840,
                encoderHeight = 2160,
                fps = 60,
                recordingState = GpuRecordingState.Idle
            )
        )

        val plan = RecordingPipelineParityPolicy.resolve(
            graphContract = graphContract,
            rawSupported = true,
            vulkanRuntimeSupported = true,
            selectedColorScience = "NIVUKX_VISION",
            renderGraphLock = PreviewRecordingRenderGraphLock(
                previewShaderSha256 = "preview-shader",
                recordingShaderSha256 = "recording-shader",
                previewLutSha256 = "preview-lut",
                recordingLutSha256 = "recording-lut",
                previewColorSpace = "Rec.2020-PQ",
                recordingColorSpace = "Rec.709",
                previewColorScience = "NIVUKX_VISION",
                recordingColorScience = "CAMERA2_BYPASS",
                previewStages = listOf("AHARDWAREBUFFER_INGEST", "RGBA16F_CORE", "JOINT_BILATERAL", "NIVUKX_3D_LUT", "RECORDER_BLIT"),
                recordingStages = listOf("CAMERA2_DIRECT_BYPASS"),
                pixelRealityBypassDetected = true,
                averageFrameProcessingMs = 7.4,
                averageRuntimeBudgetMs = 6.0
            )
        )

        assertEquals(PreviewRecordingParityStatus.FAIL, plan.parityCertificate.status)
        assertTrue(plan.parityCertificate.recordingBlocked)
        assertEquals(1.0f, plan.viewportResolutionScale, 0.0001f)
        assertTrue(plan.parityCertificate.failureCodes.contains(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_SHADER_HASH_MISMATCH.name))
        assertTrue(plan.parityCertificate.failureCodes.contains(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_LUT_HASH_MISMATCH.name))
        assertTrue(plan.parityCertificate.failureCodes.contains(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_STAGE_MISMATCH.name))
        assertTrue(plan.parityCertificate.failureCodes.contains(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_COLOR_SPACE_MISMATCH.name))
        assertTrue(plan.parityCertificate.failureCodes.contains(PreviewRecordingParityFailureCode.PIXEL_REALITY_BYPASS_DETECTED.name))
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.AVERAGE_PERFORMANCE_BUDGET_EXCEEDED.name
            )
        )
    }

    @Test
    fun `render graph lock blocks recording when color uniform packet diverges`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Neo ACES AP1",
            tenBitRequested = true,
            selectedColorScience = "Neo ACES High End",
            renderGraphLock = PreviewRecordingRenderGraphLock(
                previewShaderSha256 = "shared-shader",
                recordingShaderSha256 = "shared-shader",
                previewLutSha256 = "shared-lut",
                recordingLutSha256 = "shared-lut",
                previewColorSpace = "Neo ACES AP1",
                recordingColorSpace = "Neo ACES AP1",
                previewColorScience = "Neo ACES High End",
                recordingColorScience = "Neo ACES High End",
                previewColorUniformsSha256 = "ap1-uniform-packet",
                recordingColorUniformsSha256 = "ap0-uniform-packet",
                previewStages = listOf("AHARDWAREBUFFER_INGEST", "RGBA16F_CORE", "DUAL_SWAPCHAIN_BLIT"),
                recordingStages = listOf("AHARDWAREBUFFER_INGEST", "RGBA16F_CORE", "DUAL_SWAPCHAIN_BLIT")
            )
        )

        assertEquals(PreviewRecordingParityStatus.FAIL, plan.parityCertificate.status)
        assertTrue(plan.parityCertificate.recordingBlocked)
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.PREVIEW_RECORDING_COLOR_UNIFORM_MISMATCH.name
            )
        )
        assertTrue(plan.parityCertificate.certificateSignature.contains("colorUniform=ap1-uniform-packet"))
    }

    @Test
    fun `render graph lock permits explicitly verified log monitor delivery delta`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.LOG,
            logEnabled = true,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.2020",
            tenBitRequested = true,
            selectedColorScience = "N-Log Cinema",
            renderGraphLock = matchingRenderGraphLock(
                previewColorUniformsSha256 = "monitor-packet",
                recordingColorUniformsSha256 = "delivery-packet",
                monitorDeliveryColorUniformDifferenceAllowed = true,
                previewOutputGamut = "BT.2020",
                recordingOutputGamut = "BT.2020"
            ).copy(
                previewColorSpace = "Rec.2020",
                recordingColorSpace = "Rec.2020",
                previewColorScience = "N-Log Cinema",
                recordingColorScience = "N-Log Cinema",
                previewStages = listOf("AHARDWAREBUFFER_INGEST", "RGBA16F_CORE", "DUAL_SWAPCHAIN_BLIT"),
                recordingStages = listOf("AHARDWAREBUFFER_INGEST", "RGBA16F_CORE", "DUAL_SWAPCHAIN_BLIT")
            )
        )

        assertFalse(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.PREVIEW_RECORDING_COLOR_UNIFORM_MISMATCH.name
            )
        )
    }

    @Test
    fun `sdr cannot use log monitor delivery allowance to hide color uniform mismatch`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            renderGraphLock = matchingRenderGraphLock(
                previewColorUniformsSha256 = "preview-sdr-packet",
                recordingColorUniformsSha256 = "recording-sdr-packet",
                monitorDeliveryColorUniformDifferenceAllowed = true
            )
        )

        assertEquals(PreviewRecordingParityStatus.FAIL, plan.parityCertificate.status)
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.PREVIEW_RECORDING_COLOR_UNIFORM_MISMATCH.name
            )
        )
    }

    @Test
    fun `strict processed parity rejects missing endpoint evidence instead of copying preview proof`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            renderGraphLock = PreviewRecordingRenderGraphLock()
        )

        assertEquals(PreviewRecordingParityStatus.FAIL, plan.parityCertificate.status)
        assertFalse(plan.parityCertificate.strictVulkanParityCertified)
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.PREVIEW_RECORDING_ENDPOINT_EVIDENCE_MISSING.name
            )
        )
    }

    @Test
    fun `preview and recording identities are independently built from grade enhancement and output evidence`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            renderGraphLock = matchingRenderGraphLock(
                previewEnhancementIdentity = "preview-enhancement",
                recordingEnhancementIdentity = "recording-enhancement",
                previewGradeIdentity = "preview-grade",
                recordingGradeIdentity = "recording-grade",
                previewOutputFormat = "R8G8B8A8_UNORM",
                recordingOutputFormat = "A2B10G10R10_UNORM_PACK32",
                previewOutputBitDepth = 8,
                recordingOutputBitDepth = 10,
                previewOutputQuantizationLevels = 255,
                recordingOutputQuantizationLevels = 1023
            )
        )

        assertNotEquals(plan.sharedDecision.previewSignature, plan.sharedDecision.recordingSignature)
        assertNotEquals(
            plan.sharedDecision.previewQualitySignature,
            plan.sharedDecision.recordingQualitySignature
        )
        assertEquals(PreviewRecordingParityStatus.FAIL, plan.parityCertificate.status)
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.PREVIEW_RECORDING_ENHANCEMENT_IDENTITY_MISMATCH.name
            )
        )
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.PREVIEW_RECORDING_GRADE_IDENTITY_MISMATCH.name
            )
        )
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.PREVIEW_RECORDING_OUTPUT_FORMAT_MISMATCH.name
            )
        )
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.PREVIEW_RECORDING_OUTPUT_BIT_DEPTH_MISMATCH.name
            )
        )
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.PREVIEW_RECORDING_OUTPUT_QUANTIZATION_MISMATCH.name
            )
        )
        assertTrue(plan.parityCertificate.recordingBlocked)
    }

    @Test
    fun `record develop flat master preview record mismatches do not block recording start`() {
        val flatMasterSignature = "gpu-v1|recordDevelopLightMaster=true|packet=flat-offline-develop"
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            renderGraphLock = matchingRenderGraphLock(
                previewColorUniformsSha256 = "preview-developed-color",
                recordingColorUniformsSha256 = "record-flat-neutral-color",
                previewEnhancementIdentity = flatMasterSignature,
                recordingEnhancementIdentity = flatMasterSignature,
                previewGradeIdentity = "preview-developed-grade",
                recordingGradeIdentity = "record-flat-neutral-grade",
                previewOutputFormat = "R16G16B16A16_SFLOAT",
                recordingOutputFormat = "EGL_RGB10_A2",
                previewOutputBitDepth = 10,
                recordingOutputBitDepth = 10,
                previewOutputQuantizationLevels = 1023,
                recordingOutputQuantizationLevels = 1023,
                runtimeMetadata = validCamera2FrameLineageMetadata() + recordDevelopFlatMasterMetadata()
            )
        )

        assertEquals(PreviewRecordingParityStatus.DEFERRED_DEVELOP, plan.parityCertificate.status)
        assertFalse(plan.parityCertificate.recordingBlocked)
        assertFalse(plan.parityCertificate.strictVulkanParityCertified)
        listOf(
            PreviewRecordingParityFailureCode.PREVIEW_RECORDING_SIGNATURE_MISMATCH,
            PreviewRecordingParityFailureCode.PREVIEW_RECORDING_QUALITY_SIGNATURE_MISMATCH,
            PreviewRecordingParityFailureCode.PREVIEW_RECORDING_COLOR_UNIFORM_MISMATCH,
            PreviewRecordingParityFailureCode.PREVIEW_RECORDING_GRADE_IDENTITY_MISMATCH,
            PreviewRecordingParityFailureCode.PREVIEW_RECORDING_OUTPUT_FORMAT_MISMATCH
        ).forEach { code ->
            assertTrue(
                "${code.name} must be recorded as release-capped evidence",
                plan.parityCertificate.failureCodes.contains(code.name)
            )
        }
        assertTrue(plan.parityCertificate.certificateSignature.contains("recordDevelopFlatMaster=true"))
        assertTrue(plan.parityCertificate.certificateSignature.contains("blocked=false"))
    }

    @Test
    fun `egl rgb10 a2 and vulkan a2b10g10r10 are treated as the same ten bit output`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            renderGraphLock = matchingRenderGraphLock(
                previewOutputFormat = "A2B10G10R10_UNORM_PACK32",
                recordingOutputFormat = "EGL_RGB10_A2",
                previewOutputBitDepth = 10,
                recordingOutputBitDepth = 10,
                previewOutputQuantizationLevels = 1023,
                recordingOutputQuantizationLevels = 1023
            )
        )

        assertEquals(
            plan.parityCertificate.failureCodes.joinToString(separator = ","),
            PreviewRecordingParityStatus.PASS,
            plan.parityCertificate.status
        )
        assertEquals(plan.sharedDecision.previewQualitySignature, plan.sharedDecision.recordingQualitySignature)
        assertTrue(plan.sharedDecision.recordingQualitySignature.contains("outputFormat=A2B10G10R10_UNORM_PACK32"))
        assertFalse(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.PREVIEW_RECORDING_OUTPUT_FORMAT_MISMATCH.name
            )
        )
    }

    @Test
    fun `offscreen encoder format delta is release capped without blocking startup`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            actualFeedPathOverride = RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER,
            renderGraphLock = matchingRenderGraphLock(
                previewOutputFormat = "R16G16B16A16_SFLOAT",
                recordingOutputFormat = "EGL_RGB10_A2",
                previewOutputBitDepth = 10,
                recordingOutputBitDepth = 10,
                previewOutputQuantizationLevels = 1023,
                recordingOutputQuantizationLevels = 1023,
                runtimeMetadata = emptyMap()
            )
        )

        assertEquals(PreviewRecordingParityStatus.FAIL, plan.parityCertificate.status)
        assertFalse(plan.parityCertificate.recordingBlocked)
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.PREVIEW_RECORDING_OUTPUT_FORMAT_MISMATCH.name
            )
        )
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_CERTIFICATE_MISSING.name
            )
        )
        assertTrue(plan.parityCertificate.certificateSignature.contains("blocked=false"))
    }

    @Test
    fun `grade identity drift is recorded and release gated without blocking valid output`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "NDR10+",
            preferredColorSpace = "Rec.2020",
            tenBitRequested = true,
            selectedColorScience = "NIVUKX_VISION",
            renderGraphLock = matchingRenderGraphLock(
                previewGradeIdentity = "preview-monitor-grade",
                recordingGradeIdentity = "ndr10-plus-delivery-grade",
                previewOutputTransfer = "ST.2084_PQ",
                recordingOutputTransfer = "ST.2084_PQ",
                previewOutputGamut = "BT.2020",
                recordingOutputGamut = "BT.2020"
            ).copy(
                previewColorSpace = "Rec.2020-PQ",
                recordingColorSpace = "Rec.2020-PQ",
                previewColorScience = "NIVUKX_VISION",
                recordingColorScience = "NIVUKX_VISION"
            )
        )

        assertEquals(PreviewRecordingParityStatus.FAIL, plan.parityCertificate.status)
        assertFalse(plan.parityCertificate.recordingBlocked)
        assertFalse(plan.parityCertificate.strictVulkanParityCertified)
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.PREVIEW_RECORDING_GRADE_IDENTITY_MISMATCH.name
            )
        )
        assertTrue(plan.parityCertificate.certificateSignature.contains("blocked=false"))
    }

    @Test
    fun `missing camera2 frame lineage certificate caps strict parity without blocking recording`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            renderGraphLock = matchingRenderGraphLock(runtimeMetadata = emptyMap())
        )

        assertEquals(PreviewRecordingParityStatus.FAIL, plan.parityCertificate.status)
        assertFalse(plan.parityCertificate.recordingBlocked)
        assertFalse(plan.parityCertificate.strictVulkanParityCertified)
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_CERTIFICATE_MISSING.name
            )
        )
        assertTrue(plan.parityCertificate.certificateSignature.contains("camera2FrameLineageRequired=true"))
        assertTrue(plan.parityCertificate.certificateSignature.contains("camera2FrameLineageMetadataPresent=false"))
        assertTrue(plan.parityCertificate.certificateSignature.contains("camera2FrameLineageReleaseCapped=true"))
        assertTrue(plan.parityCertificate.certificateSignature.contains("blocked=false"))
    }

    @Test
    fun `failed camera2 frame lineage records runtime proof failures without hard blocking`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "NDR10+",
            preferredColorSpace = "Rec.2020",
            tenBitRequested = true,
            selectedColorScience = "NIVUKX_VISION",
            renderGraphLock = matchingRenderGraphLock(
                runtimeMetadata = validCamera2FrameLineageMetadata(
                    evidenceWindowFrames = 59L,
                    evidenceWindowDurationMs = 1_999L,
                    timestampMatchRatio = 0.994,
                    staleGenerationCount = 1L,
                    nonMonotonicTimestampCount = 1L,
                    duplicateEncoderSubmissionCount = 1L,
                    previewEndpointOmissionCount = 1L,
                    encoderEndpointOmissionCount = 1L,
                    submittedFrameDifference = 2L
                ),
                previewOutputTransfer = "ST.2084_PQ",
                recordingOutputTransfer = "ST.2084_PQ",
                previewOutputGamut = "BT.2020",
                recordingOutputGamut = "BT.2020"
            ).copy(
                previewColorSpace = "Rec.2020-PQ",
                recordingColorSpace = "Rec.2020-PQ",
                previewColorScience = "NIVUKX_VISION",
                recordingColorScience = "NIVUKX_VISION"
            )
        )

        assertEquals(PreviewRecordingParityStatus.FAIL, plan.parityCertificate.status)
        assertFalse(plan.parityCertificate.recordingBlocked)
        assertFalse(plan.parityCertificate.strictVulkanParityCertified)
        assertTrue(plan.parityCertificate.certificateSignature.contains("camera2FrameLineageMetadataPresent=true"))
        assertTrue(plan.parityCertificate.certificateSignature.contains("camera2FrameLineageTimestampMatchRatio=0.994"))
        assertTrue(plan.parityCertificate.certificateSignature.contains("camera2FrameLineageSubmittedFrameDifference=2"))
        listOf(
            PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_WINDOW_INSUFFICIENT,
            PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_TIMESTAMP_MATCH_RATIO_LOW,
            PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_STALE_GENERATION,
            PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_NON_MONOTONIC_TIMESTAMPS,
            PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_DUPLICATE_ENCODER_SUBMISSION,
            PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_ENDPOINT_OMISSION,
            PreviewRecordingParityFailureCode.CAMERA2_FRAME_LINEAGE_PREVIEW_ENCODER_FRAME_DELTA_EXCEEDED
        ).forEach { code ->
            assertTrue(plan.parityCertificate.failureCodes.contains(code.name))
        }
    }

    @Test
    fun `valid camera2 frame lineage preserves ndr log and raw identities`() {
        val ndr = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "NDR10+",
            tenBitRequested = true
        )
        val log = resolveParity(
            cinemaMode = CinemaVideoMode.LOG,
            logEnabled = true,
            requestedHdrMode = "OFF",
            tenBitRequested = true
        )
        val raw = resolveParity(
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            rawSupported = true
        )

        assertEquals(VideoDeliveryKind.HDR, ndr.deliveryKind)
        assertEquals("NDR10+", ndr.encoderHdrMode)
        assertEquals("Rec.2020-NDR10+", ndr.encoderColorSpace)
        assertTrue(ndr.parityCertificate.strictVulkanParityCertified)
        assertEquals(VideoDeliveryKind.LOG, log.deliveryKind)
        assertEquals("OFF", log.encoderHdrMode)
        assertEquals("Rec.2020-LOG", log.encoderColorSpace)
        assertTrue(log.parityCertificate.strictVulkanParityCertified)
        assertEquals(VideoDeliveryKind.RAW, raw.deliveryKind)
        assertEquals("RAW", raw.encoderColorSpace)
        assertEquals(RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE, raw.feedPath)
    }

    @Test
    fun `camera2 frame lineage accumulator metadata certifies strict vulkan parity`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            renderGraphLock = matchingRenderGraphLock(
                runtimeMetadata = validCamera2FrameLineageAccumulatorMetadata()
            )
        )

        assertEquals(PreviewRecordingParityStatus.PASS, plan.parityCertificate.status)
        assertFalse(plan.parityCertificate.recordingBlocked)
        assertTrue(plan.parityCertificate.strictVulkanParityCertified)
        assertTrue(
            plan.parityCertificate.certificateSignature
                .contains("camera2FrameLineageEvidenceWindowFrames=60")
        )
        assertTrue(
            plan.parityCertificate.certificateSignature
                .contains("camera2FrameLineageTimestampMatchRatio=1.0")
        )
    }

    @Test
    fun `unknown gpu timing stays unknown without failing idle parity`() {
        val plan = resolveParity(
            renderGraphLock = matchingRenderGraphLock(
                averageFrameProcessingMs = null,
                averageRuntimeBudgetMs = 6.0
            )
        )

        assertEquals(PreviewRecordingParityStatus.PASS, plan.parityCertificate.status)
        assertFalse(plan.parityCertificate.averagePerformanceMeasurementAvailable)
        assertNull(plan.parityCertificate.measuredAverageFrameProcessingMs)
        assertFalse(plan.parityCertificate.peakPerformanceMeasurementAvailable)
        assertNull(plan.parityCertificate.measuredPeakFrameProcessingMs)
        assertFalse(plan.parityCertificate.peakPerformanceBudgetCertified)
        assertTrue(plan.parityCertificate.strictVulkanParityCertified)
        assertFalse(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.AVERAGE_PERFORMANCE_BUDGET_EXCEEDED.name
            )
        )
        assertTrue(plan.parityCertificate.certificateSignature.contains("averagePerformanceEvidence=UNKNOWN"))
        assertTrue(plan.parityCertificate.certificateSignature.contains("peakPerformanceEvidence=UNKNOWN"))
        assertFalse(plan.parityCertificate.certificateSignature.contains("noLagCertified=true"))
    }

    @Test
    fun `measured average gpu timing is recorded without claiming peak latency`() {
        val plan = resolveParity(
            renderGraphLock = matchingRenderGraphLock(
                averageFrameProcessingMs = 7.25,
                averageRuntimeBudgetMs = 6.0
            )
        )

        assertTrue(plan.parityCertificate.averagePerformanceMeasurementAvailable)
        assertEquals(7.25, plan.parityCertificate.measuredAverageFrameProcessingMs!!, 0.0001)
        assertEquals(6.0, plan.parityCertificate.averageRuntimeBudgetMs, 0.0001)
        assertFalse(plan.parityCertificate.averagePerformanceBudgetSatisfied)
        assertFalse(plan.parityCertificate.peakPerformanceMeasurementAvailable)
        assertNull(plan.parityCertificate.measuredPeakFrameProcessingMs)
        assertFalse(plan.parityCertificate.peakPerformanceBudgetCertified)
        assertTrue(
            plan.parityCertificate.failureCodes.contains(
                PreviewRecordingParityFailureCode.AVERAGE_PERFORMANCE_BUDGET_EXCEEDED.name
            )
        )
        assertTrue(plan.parityCertificate.certificateSignature.contains("averagePerformanceEvidence=MEASURED"))
        assertTrue(plan.parityCertificate.certificateSignature.contains("measuredAverageFrameProcessingMs=7.25"))
        assertTrue(plan.parityCertificate.certificateSignature.contains("peakPerformanceEvidence=UNKNOWN"))
        assertFalse(plan.parityCertificate.certificateSignature.contains("maxFrameProcessingMs"))
    }

    @Test
    fun `avg compute parser requires positive average and positive dispatch count`() {
        val measuredEvidence = VulkanRuntimeManager.parseAverageComputeTimingEvidence(
            "Vulkan supported=true avgComputeMs=3.875 totalDispatches=42 importedFrames=42"
        )

        assertEquals(3.875, measuredEvidence!!.averageComputeMs, 0.0001)
        assertEquals(42L, measuredEvidence.totalDispatches)
        assertEquals(
            3.875,
            VulkanRuntimeManager.parseAverageComputeMs(
                "Vulkan supported=true avgComputeMs=3.875 totalDispatches=42 importedFrames=42"
            )!!,
            0.0001
        )
        assertNull(VulkanRuntimeManager.parseAverageComputeMs("avgComputeMs=0.0 totalDispatches=0"))
        assertNull(VulkanRuntimeManager.parseAverageComputeMs("avgComputeMs=3.875 totalDispatches=0"))
        assertNull(VulkanRuntimeManager.parseAverageComputeMs("avgComputeMs=3.875"))
        assertNull(VulkanRuntimeManager.parseAverageComputeMs("totalDispatches=42"))
        assertNull(VulkanRuntimeManager.parseAverageComputeMs("Vulkan supported=true"))
        assertNull(VulkanRuntimeManager.parseAverageComputeMs("avgComputeMs="))
        assertNull(VulkanRuntimeManager.parseAverageComputeMs("avgComputeMs=invalid"))
        assertNull(VulkanRuntimeManager.parseAverageComputeMs("avgComputeMs=-0.01 totalDispatches=42"))
        assertNull(VulkanRuntimeManager.parseAverageComputeMs("avgComputeMs=NaN totalDispatches=42"))
        assertNull(VulkanRuntimeManager.parseAverageComputeMs("avgComputeMs=Infinity totalDispatches=42"))
        assertNull(VulkanRuntimeManager.parseAverageComputeMs("avgComputeMs=3.875 totalDispatches=invalid"))
        assertNull(VulkanRuntimeManager.parseAverageComputeMs("avgComputeMs=3.875 totalDispatches=-1"))
    }

    @Test
    fun `render graph lock blocks mirrored or rotated recording geometry mismatch`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "NDR10+",
            preferredColorSpace = "Rec.2020",
            tenBitRequested = true,
            selectedColorScience = "NIVUKX_VISION",
            renderGraphLock = PreviewRecordingRenderGraphLock(
                previewShaderSha256 = "shared-shader",
                recordingShaderSha256 = "shared-shader",
                previewLutSha256 = "shared-lut",
                recordingLutSha256 = "shared-lut",
                previewColorSpace = "Rec.2020-PQ",
                recordingColorSpace = "Rec.2020-PQ",
                previewColorScience = "NIVUKX_VISION",
                recordingColorScience = "NIVUKX_VISION",
                previewStages = listOf("AHARDWAREBUFFER_INGEST", "RGBA16F_CORE", "NIVUKX_3D_LUT", "DUAL_SWAPCHAIN_BLIT"),
                recordingStages = listOf("AHARDWAREBUFFER_INGEST", "RGBA16F_CORE", "NIVUKX_3D_LUT", "DUAL_SWAPCHAIN_BLIT"),
                previewGeometryMatrix = floatArrayOf(
                    1f, 0f, 0f,
                    0f, 1f, 0f,
                    0f, 0f, 1f
                ),
                recordingGeometryMatrix = floatArrayOf(
                    -1f, 0f, 1f,
                    0f, 1f, 0f,
                    0f, 0f, 1f
                )
            )
        )

        assertEquals(PreviewRecordingParityStatus.FAIL, plan.parityCertificate.status)
        assertTrue(plan.parityCertificate.recordingBlocked)
        assertTrue(plan.parityCertificate.failureCodes.contains(PreviewRecordingParityFailureCode.PREVIEW_RECORDING_GEOMETRY_MISMATCH.name))
        assertTrue(plan.parityCertificate.geometryMatrixSignature.contains("preview="))
        assertTrue(plan.parityCertificate.geometryMatrixSignature.contains("recording="))
    }

    @Test
    fun `render graph lock passes when shader lut stages and quality identity are shared`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "NDR10+",
            preferredColorSpace = "Rec.2020",
            tenBitRequested = true,
            selectedColorScience = "NIVUKX_VISION",
            renderGraphLock = matchingRenderGraphLock(
                averageFrameProcessingMs = 4.2,
                averageRuntimeBudgetMs = 6.0,
                previewOutputTransfer = "ST.2084_PQ",
                recordingOutputTransfer = "ST.2084_PQ",
                previewOutputGamut = "BT.2020",
                recordingOutputGamut = "BT.2020"
            ).copy(
                previewColorSpace = "Rec.2020-PQ",
                recordingColorSpace = "Rec.2020-PQ",
                previewColorScience = "NIVUKX_VISION",
                recordingColorScience = "NIVUKX_VISION",
                previewStages = listOf("AHARDWAREBUFFER_INGEST", "RGBA16F_CORE", "JOINT_BILATERAL", "NIVUKX_3D_LUT", "DUAL_SWAPCHAIN_BLIT"),
                recordingStages = listOf("AHARDWAREBUFFER_INGEST", "RGBA16F_CORE", "JOINT_BILATERAL", "NIVUKX_3D_LUT", "DUAL_SWAPCHAIN_BLIT")
            )
        )

        assertEquals(PreviewRecordingParityStatus.PASS, plan.parityCertificate.status)
        assertFalse(plan.parityCertificate.recordingBlocked)
        assertEquals("shared-shader", plan.sharedDecision.renderGraphShaderSha256)
        assertEquals("shared-lut", plan.sharedDecision.renderGraphLutSha256)
        assertTrue(plan.sharedDecision.renderGraphStagesSignature.contains("JOINT_BILATERAL>NIVUKX_3D_LUT"))
        assertEquals(1.0f, plan.viewportResolutionScale, 0.0001f)
        assertTrue(plan.parityCertificate.averagePerformanceBudgetSatisfied)
        assertFalse(plan.parityCertificate.peakPerformanceBudgetCertified)
        assertTrue(plan.parityCertificate.strictVulkanParityCertified)
    }

    // --- Unified force-10-bit invariant locks ---------------------------------------------
    // The unified pipeline forces 10-bit for every processed mode (incl. SDR). These lock that
    // (a) forcing 10-bit keeps all four processed modes at parity PASS and does NOT drift them
    // into an HDR transport (HDR/LOG/SDR transport FAIL codes stay clear), and (b) the force
    // never pulls RAW into the unified compressed pipeline.

    @Test
    fun `forced ten bit keeps all four processed modes at parity PASS without hdr transport drift`() {
        val processed = listOf(
            // cinemaMode, logEnabled, requestedHdrMode, preferredColorSpace, expected delivery
            resolveParity(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "OFF", tenBitRequested = true) to VideoDeliveryKind.SDR,
            resolveParity(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "OFF", preferredColorSpace = "Rec.2020", tenBitRequested = true) to VideoDeliveryKind.SDR,
            resolveParity(cinemaMode = CinemaVideoMode.PRO, requestedHdrMode = "NDR10+", tenBitRequested = true) to VideoDeliveryKind.HDR,
            resolveParity(cinemaMode = CinemaVideoMode.LOG, logEnabled = true, requestedHdrMode = "OFF", tenBitRequested = true) to VideoDeliveryKind.LOG
        )

        processed.forEach { (plan, expectedDelivery) ->
            assertEquals(expectedDelivery, plan.deliveryKind)
            // 10-bit forced end-to-end for the session plan and the shared decision.
            assertTrue(plan.tenBitRequestedForSessionPlan)
            assertTrue(plan.sharedDecision.tenBitTransportRequested)
            // Unified selector keeps every processed mode off the RAW-only direct arm.
            assertNotEquals(RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE, plan.feedPath)
            assertFalse(plan.parityCertificate.camera2DirectFallback)
            // Parity still passes and recording stays unblocked with the forced 10-bit request.
            assertEquals(PreviewRecordingParityStatus.PASS, plan.parityCertificate.status)
            assertFalse(plan.parityCertificate.recordingBlocked)
            // Forcing 10-bit is orthogonal to HDR transport: SDR/LOG stay SDR-transport, HDR stays HDR.
            assertFalse(
                plan.parityCertificate.failureCodes.contains(
                    PreviewRecordingParityFailureCode.SDR_OR_LOG_HDR_TRANSPORT_ENABLED.name
                )
            )
            assertFalse(
                plan.parityCertificate.failureCodes.contains(
                    PreviewRecordingParityFailureCode.HDR_TRANSPORT_DISABLED.name
                )
            )
        }
    }

    @Test
    fun `forced ten bit does not pull raw video into the unified compressed pipeline`() {
        val plan = resolveParity(
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            rawSupported = true,
            requestedHdrMode = "OFF",
            tenBitRequested = true
        )

        assertEquals(VideoDeliveryKind.RAW, plan.deliveryKind)
        assertEquals(RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE, plan.feedPath)
        assertEquals("RAW", plan.encoderColorSpace)
        assertTrue(plan.encoderRawEnabled)
        // RAW is the documented exception: the unified 10-bit force must not request P010 transport for it.
        assertFalse(plan.tenBitRequestedForSessionPlan)
        assertFalse(plan.sharedDecision.tenBitTransportRequested)
    }

    @Test
    fun `hybrid default routes processed modes through offscreen p010 while preserving delivery`() {
        val video = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            vulkanRuntimeSupported = false,
            offscreenAhbEglEncoderSupported = true,
            byteBufferP010EncoderSupported = true,
            preferByteBufferP010Encoder = true
        )
        val ndr = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "NDR10+",
            tenBitRequested = true,
            vulkanRuntimeSupported = false,
            offscreenAhbEglEncoderSupported = true,
            byteBufferP010EncoderSupported = true,
            preferByteBufferP010Encoder = true
        )
        val log = resolveParity(
            cinemaMode = CinemaVideoMode.LOG,
            logEnabled = true,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            vulkanRuntimeSupported = false,
            offscreenAhbEglEncoderSupported = true,
            byteBufferP010EncoderSupported = true,
            preferByteBufferP010Encoder = true
        )

        assertEquals(RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER, video.feedPath)
        assertEquals(VideoDeliveryKind.SDR, video.deliveryKind)
        assertEquals("OFF", video.encoderHdrMode)
        assertFalse(video.sharedDecision.hdrTransportRequired)

        assertEquals(RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER, ndr.feedPath)
        assertEquals(VideoDeliveryKind.HDR, ndr.deliveryKind)
        assertEquals("NDR10+", ndr.encoderHdrMode)
        assertTrue(ndr.sharedDecision.hdrTransportRequired)

        assertEquals(RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER, log.feedPath)
        assertEquals(VideoDeliveryKind.LOG, log.deliveryKind)
        assertEquals("OFF", log.encoderHdrMode)
        assertTrue(log.sharedDecision.logSdrWideGamut)
        assertFalse(log.sharedDecision.hdrTransportRequired)
    }

    @Test
    fun `hybrid offscreen unavailable uses p010 for processed modes and raw remains direct`() {
        val video = resolveParity(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            vulkanRuntimeSupported = false,
            byteBufferP010EncoderSupported = true,
            preferByteBufferP010Encoder = true
        )
        val log = resolveParity(
            cinemaMode = CinemaVideoMode.LOG,
            logEnabled = true,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            vulkanRuntimeSupported = false,
            byteBufferP010EncoderSupported = true,
            preferByteBufferP010Encoder = true
        )
        val raw = resolveParity(
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            rawSupported = true,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            vulkanRuntimeSupported = false,
            offscreenAhbEglEncoderSupported = true,
            byteBufferP010EncoderSupported = true,
            preferByteBufferP010Encoder = true
        )

        assertEquals(RecordingFeedPath.BYTEBUFFER_P010_ENCODER, video.feedPath)
        assertEquals(VideoDeliveryKind.SDR, video.deliveryKind)
        assertEquals("OFF", video.encoderHdrMode)
        assertEquals(RecordingFeedPath.BYTEBUFFER_P010_ENCODER, log.feedPath)
        assertEquals(VideoDeliveryKind.LOG, log.deliveryKind)
        assertFalse(log.sharedDecision.hdrTransportRequired)
        assertEquals(RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE, raw.feedPath)
        assertEquals(VideoDeliveryKind.RAW, raw.deliveryKind)
        assertFalse(raw.tenBitRequestedForSessionPlan)
    }

    private fun resolveParity(
        cinemaMode: CinemaVideoMode = CinemaVideoMode.PRO,
        rawVideoActive: Boolean = false,
        logEnabled: Boolean = false,
        requestedHdrMode: String = "OFF",
        rawSupported: Boolean = true,
        preferredColorSpace: String = "Rec.709",
        tenBitRequested: Boolean = false,
        selectedColorScience: String = "USER_MANUAL_COLOR",
        renderGraphLock: PreviewRecordingRenderGraphLock? = null,
        actualFeedPathOverride: RecordingFeedPath? = null,
        vulkanRuntimeSupported: Boolean = true,
        offscreenAhbEglEncoderSupported: Boolean = false,
        byteBufferP010EncoderSupported: Boolean = false,
        preferByteBufferP010Encoder: Boolean = false
    ): RecordingPipelineParityPlan {
        val graphContract = VideoPipelineGraphController.resolve(
            VideoPipelineGraphRequest(
                appMode = AppMode.VIDEO,
                cinemaMode = cinemaMode,
                rawVideoActive = rawVideoActive,
                logEnabled = logEnabled,
                requestedHdrMode = requestedHdrMode,
                preferredColorSpace = preferredColorSpace,
                tenBitRequested = tenBitRequested,
                previewWidth = 1920,
                previewHeight = 1080,
                encoderWidth = 3840,
                encoderHeight = 2160,
                fps = 30,
                recordingState = GpuRecordingState.Idle
            )
        )
        return RecordingPipelineParityPolicy.resolve(
            graphContract = graphContract,
            rawSupported = rawSupported,
            vulkanRuntimeSupported = vulkanRuntimeSupported,
            offscreenAhbEglEncoderSupported = offscreenAhbEglEncoderSupported,
            byteBufferP010EncoderSupported = byteBufferP010EncoderSupported,
            preferByteBufferP010Encoder = preferByteBufferP010Encoder,
            selectedColorScience = selectedColorScience,
            renderGraphLock = renderGraphLock ?: matchingRenderGraphLock(),
            actualFeedPathOverride = actualFeedPathOverride
        )
    }

    private fun matchingRenderGraphLock(
        previewColorUniformsSha256: String = "shared-color-uniforms",
        recordingColorUniformsSha256: String = previewColorUniformsSha256,
        monitorDeliveryColorUniformDifferenceAllowed: Boolean = false,
        previewEnhancementIdentity: String = "shared-enhancement",
        recordingEnhancementIdentity: String = previewEnhancementIdentity,
        previewGradeIdentity: String = "shared-grade",
        recordingGradeIdentity: String = previewGradeIdentity,
        previewOutputFormat: String = "A2B10G10R10_UNORM_PACK32",
        recordingOutputFormat: String = previewOutputFormat,
        previewOutputTransfer: String = "sRGB",
        recordingOutputTransfer: String = previewOutputTransfer,
        previewOutputGamut: String = "BT.709",
        recordingOutputGamut: String = previewOutputGamut,
        previewOutputBitDepth: Int = 10,
        recordingOutputBitDepth: Int = previewOutputBitDepth,
        previewOutputQuantizationLevels: Int = 1023,
        recordingOutputQuantizationLevels: Int = previewOutputQuantizationLevels,
        averageFrameProcessingMs: Double? = null,
        averageRuntimeBudgetMs: Double = 14.0,
        runtimeMetadata: Map<String, String> = validCamera2FrameLineageMetadata()
    ): PreviewRecordingRenderGraphLock =
        PreviewRecordingRenderGraphLock(
            previewShaderSha256 = "shared-shader",
            recordingShaderSha256 = "shared-shader",
            previewLutSha256 = "shared-lut",
            recordingLutSha256 = "shared-lut",
            previewColorSpace = "Rec.709",
            recordingColorSpace = "Rec.709",
            previewColorScience = "Natural",
            recordingColorScience = "Natural",
            previewColorUniformsSha256 = previewColorUniformsSha256,
            recordingColorUniformsSha256 = recordingColorUniformsSha256,
            monitorDeliveryColorUniformDifferenceAllowed =
                monitorDeliveryColorUniformDifferenceAllowed,
            previewEnhancementIdentity = previewEnhancementIdentity,
            recordingEnhancementIdentity = recordingEnhancementIdentity,
            previewGradeIdentity = previewGradeIdentity,
            recordingGradeIdentity = recordingGradeIdentity,
            previewDenoiseProfile = VideoDenoiseProfile.ENCODED_VIDEO_STRICT.name,
            recordingDenoiseProfile = VideoDenoiseProfile.ENCODED_VIDEO_STRICT.name,
            previewSharpnessProfile = VideoSharpnessProfile.NATURAL_DETAIL.name,
            recordingSharpnessProfile = VideoSharpnessProfile.NATURAL_DETAIL.name,
            previewStages = listOf("SENSOR_RAW", "OUTPUT"),
            recordingStages = listOf("SENSOR_RAW", "OUTPUT"),
            previewOutputFormat = previewOutputFormat,
            recordingOutputFormat = recordingOutputFormat,
            previewOutputTransfer = previewOutputTransfer,
            recordingOutputTransfer = recordingOutputTransfer,
            previewOutputGamut = previewOutputGamut,
            recordingOutputGamut = recordingOutputGamut,
            previewOutputBitDepth = previewOutputBitDepth,
            recordingOutputBitDepth = recordingOutputBitDepth,
            previewOutputQuantizationLevels = previewOutputQuantizationLevels,
            recordingOutputQuantizationLevels = recordingOutputQuantizationLevels,
            averageFrameProcessingMs = averageFrameProcessingMs,
            averageRuntimeBudgetMs = averageRuntimeBudgetMs,
            runtimeMetadata = runtimeMetadata
        )

    private fun validCamera2FrameLineageMetadata(
        certificatePresent: Boolean = true,
        evidenceWindowFrames: Long = 90L,
        evidenceWindowDurationMs: Long = 3_000L,
        timestampMatchRatio: Double = 0.998,
        staleGenerationCount: Long = 0L,
        nonMonotonicTimestampCount: Long = 0L,
        duplicateEncoderSubmissionCount: Long = 0L,
        previewEndpointOmissionCount: Long = 0L,
        encoderEndpointOmissionCount: Long = 0L,
        submittedFrameDifference: Long = 1L
    ): Map<String, String> = mapOf(
        "camera2FrameLineageCertificatePresent" to certificatePresent.toString(),
        "camera2FrameLineageEvidenceWindowFrames" to evidenceWindowFrames.toString(),
        "camera2FrameLineageEvidenceWindowDurationMs" to evidenceWindowDurationMs.toString(),
        "camera2FrameLineageTimestampMatchRatio" to timestampMatchRatio.toString(),
        "camera2FrameLineageStaleGenerationCount" to staleGenerationCount.toString(),
        "camera2FrameLineageNonMonotonicTimestampCount" to nonMonotonicTimestampCount.toString(),
        "camera2FrameLineageDuplicateEncoderSubmissionCount" to duplicateEncoderSubmissionCount.toString(),
        "camera2FrameLineagePreviewEndpointOmissionCount" to previewEndpointOmissionCount.toString(),
        "camera2FrameLineageEncoderEndpointOmissionCount" to encoderEndpointOmissionCount.toString(),
        "camera2FrameLineageSubmittedFrameDifference" to submittedFrameDifference.toString()
    )

    private fun recordDevelopFlatMasterMetadata(): Map<String, String> = mapOf(
        "recordDevelopModeEnabled" to "true",
        "recordDevelopLightMaster" to "true",
        "recordDevelopLiveColorProcessing" to "FLAT_10BIT_MASTER_ONLY",
        "recordDevelopOfflineDevelopRequired" to "true",
        "recordDevelopOfflineOutputFormat" to "R210_444",
        "recordDevelopOfflineEnhanceProfile" to "ELITE_SNCE_TEMPORAL_DENOISE_DETAIL"
    )

    private fun validCamera2FrameLineageAccumulatorMetadata(): Map<String, String> {
        val accumulator = Camera2FrameLineageAccumulator(expectedSessionGeneration = 11L)
        repeat(60) { index ->
            val timestamp = 2_000_000_000L + index.toLong() * 35_000_000L
            accumulator.recordCaptureResult(
                Camera2CaptureFrameEvidence(
                    sessionGeneration = 11L,
                    requestRevision = 4L,
                    frameNumber = index.toLong(),
                    sensorTimestampNs = timestamp,
                    sensorFrameDurationNs = 35_000_000L,
                    exposureTimeNs = 17_500_000L,
                    focusDistanceDiopters = 0.5f,
                    whiteBalanceMode = "AUTO",
                    requestTag = Camera2RequestTag(
                        sessionGeneration = 11L,
                        requestRevision = 4L,
                        targetRoles = setOf("preview", "encoder"),
                        pipelineSignature = "V19_PARITY_INTEROP"
                    )
                )
            )
            accumulator.recordImageTimestamp(timestamp)
            accumulator.recordPreviewSubmission(
                sensorTimestampNs = timestamp,
                presentationTimestampNs = timestamp + 1_000_000L,
                frameNumber = index.toLong()
            )
            accumulator.recordEncoderSubmission(
                sensorTimestampNs = timestamp,
                presentationTimestampNs = timestamp + 2_000_000L,
                frameNumber = index.toLong()
            )
        }
        return accumulator.validateStrictCertificate().toCompactMetadataMap()
    }
}
