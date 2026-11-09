package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.color.NeoLogCurve
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.ui.AppMode
import com.neoproc.neoproccamx.ui.VideoFormatPreviewLookState
import com.neoproc.neoproccamx.ui.VideoModePipelineContract
import com.neoproc.neoproccamx.ui.VideoModePipelineContractPolicy
import com.neoproc.neoproccamx.ui.VideoPipelinePreviewKind
import com.neoproc.neoproccamx.ui.VideoPipelineToneMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanPreviewRenderContractPolicyTest {

    @Test
    fun sdrVideoUsesDirectSdrRenderAndPreservesAspect() {
        val renderContract = resolveRenderContract(
            pipelineContract = pipelineContract(cinemaMode = CinemaVideoMode.PRO)
        )

        assertEquals(VulkanPreviewRenderPath.SDR_DIRECT, renderContract.renderPath)
        assertEquals(HdrDynamicRangeMode.SDR, renderContract.swapchainDynamicRangeMode)
        assertEquals(VulkanToneDeliveryMode.DIRECT, renderContract.toneDeliveryMode)
        assertTrue(renderContract.truthfulColorDelivery)
        assertFalse(renderContract.previewMonitoringEnabled)
        assertTrue(renderContract.preservesPreviewAspect)
    }

    @Test
    fun tenBitSdrRemainsSdrAndCannotEnterNdrOrRawMonitor() {
        val renderContract = resolveRenderContract(
            pipelineContract = pipelineContract(
                cinemaMode = CinemaVideoMode.PRO,
                requestedHdrMode = "OFF",
                tenBitActive = true
            )
        )

        assertEquals(VulkanPreviewRenderPath.SDR_DIRECT, renderContract.renderPath)
        assertEquals(HdrDynamicRangeMode.SDR, renderContract.swapchainDynamicRangeMode)
        assertEquals(VulkanToneDeliveryMode.DIRECT, renderContract.toneDeliveryMode)
        assertNotEquals(VulkanToneDeliveryMode.NDR10_PLUS_MONITOR, renderContract.toneDeliveryMode)
        assertNotEquals(VulkanToneDeliveryMode.RAW_MONITOR, renderContract.toneDeliveryMode)
    }

    @Test
    fun sdrVideoKeepsDirectTruthfulRenderEvenWhenNativeLutIsAvailable() {
        val renderContract = resolveRenderContract(
            pipelineContract = pipelineContract(cinemaMode = CinemaVideoMode.PRO),
            nativePreviewLutEnabled = true,
            nativePreviewLutSize = 33
        )

        assertEquals(VulkanPreviewRenderPath.SDR_DIRECT, renderContract.renderPath)
        assertEquals(VulkanToneDeliveryMode.DIRECT, renderContract.toneDeliveryMode)
        assertFalse(renderContract.nativePreviewLutEnabled)
    }

    @Test
    fun hdrModesUseNativeHdrRenderPathForSelectedVersion() {
        val hlg = resolveRenderContract(
            pipelineContract = pipelineContract(requestedHdrMode = "HLG", tenBitActive = true),
            cameraPreviewHdrTransportMode = HdrDynamicRangeMode.HLG
        )
        val hdr10Plus = resolveRenderContract(
            pipelineContract = pipelineContract(requestedHdrMode = "HDR10+", tenBitActive = true),
            cameraPreviewHdrTransportMode = HdrDynamicRangeMode.HDR10_PLUS
        )

        assertEquals(VulkanPreviewRenderPath.HDR_PQ_NATIVE, hlg.renderPath)
        assertEquals(HdrDynamicRangeMode.HDR10, hlg.swapchainDynamicRangeMode)
        assertEquals(VulkanToneDeliveryMode.NDR10_PLUS_MONITOR, hlg.toneDeliveryMode)
        assertTrue(hlg.truthfulColorDelivery)
        assertTrue(hlg.previewMonitoringEnabled)

        assertEquals(VulkanPreviewRenderPath.HDR_PQ_NATIVE, hdr10Plus.renderPath)
        assertEquals(HdrDynamicRangeMode.HDR10, hdr10Plus.swapchainDynamicRangeMode)
        assertEquals(VulkanToneDeliveryMode.NDR10_PLUS_MONITOR, hdr10Plus.toneDeliveryMode)
        assertTrue(hdr10Plus.truthfulColorDelivery)
        assertTrue(hdr10Plus.previewMonitoringEnabled)
    }

    @Test
    fun selectedNeoHdrProfilesCarryDistinctLiveShaderContracts() {
        val ndr = resolveRenderContract(
            pipelineContract = pipelineContract(requestedHdrMode = "NDR10+", tenBitActive = true),
            cameraPreviewHdrTransportMode = HdrDynamicRangeMode.HDR10_PLUS
        )
        val nivukx = resolveRenderContract(
            pipelineContract = pipelineContract(requestedHdrMode = "NIVUKXVISION", tenBitActive = true),
            cameraPreviewHdrTransportMode = HdrDynamicRangeMode.DOLBY_VISION
        )

        assertEquals(VulkanToneDeliveryMode.NDR10_PLUS_MONITOR, ndr.toneDeliveryMode)
        assertEquals(VulkanToneDeliveryMode.NIVUKX_VISION_MONITOR, nivukx.toneDeliveryMode)
        assertNotEquals(ndr.toneDeliveryMode.shaderModeFlag, nivukx.toneDeliveryMode.shaderModeFlag)
    }

    @Test
    fun hlgCameraInputStillUsesProjectPqPreviewOutput() {
        val renderContract = resolveRenderContract(
            pipelineContract = pipelineContract(requestedHdrMode = "HDR10", tenBitActive = true),
            cameraPreviewHdrTransportMode = HdrDynamicRangeMode.HLG
        )

        assertEquals(VulkanPreviewRenderPath.HDR_PQ_NATIVE, renderContract.renderPath)
        assertEquals(HdrDynamicRangeMode.HDR10, renderContract.swapchainDynamicRangeMode)
        assertEquals(VulkanToneDeliveryMode.NDR10_PLUS_MONITOR, renderContract.toneDeliveryMode)
        assertEquals("NDR10+", renderContract.normalizedHdrMode)
        assertTrue(renderContract.previewMonitoringEnabled)
    }

    @Test
    fun nivukxIdentitySurvivesHlgInputCarrierAndKeepsPqOutput() {
        val renderContract = resolveRenderContract(
            pipelineContract = pipelineContract(requestedHdrMode = "NIVUKXVISION", tenBitActive = true),
            cameraPreviewHdrTransportMode = HdrDynamicRangeMode.HLG
        )

        assertEquals("NIVUKXVISION", renderContract.normalizedHdrMode)
        assertEquals(VulkanToneDeliveryMode.NIVUKX_VISION_MONITOR, renderContract.toneDeliveryMode)
        assertEquals(VulkanPreviewRenderPath.HDR_PQ_NATIVE, renderContract.renderPath)
        assertEquals(HdrDynamicRangeMode.HDR10, renderContract.swapchainDynamicRangeMode)
    }

    @Test
    fun hdrPreviewKeepsShaderMonitorWhenCameraTransportIsStandard() {
        val renderContract = resolveRenderContract(
            pipelineContract = pipelineContract(requestedHdrMode = "HDR10", tenBitActive = true),
            cameraPreviewHdrTransportMode = HdrDynamicRangeMode.SDR
        )

        assertEquals(VulkanPreviewRenderPath.HDR_PQ_NATIVE, renderContract.renderPath)
        assertEquals(HdrDynamicRangeMode.SDR, renderContract.swapchainDynamicRangeMode)
        assertEquals(VulkanToneDeliveryMode.NDR10_PLUS_MONITOR, renderContract.toneDeliveryMode)
        assertEquals("NDR10+", renderContract.normalizedHdrMode)
        assertTrue(renderContract.previewMonitoringEnabled)
    }

    @Test
    fun selectedNeoHdrPreviewKeepsShaderMonitorWhenCameraTransportIsStandard() {
        val ndr = resolveRenderContract(
            pipelineContract = pipelineContract(requestedHdrMode = "NDR10+", tenBitActive = true),
            cameraPreviewHdrTransportMode = HdrDynamicRangeMode.SDR
        )
        val nivukx = resolveRenderContract(
            pipelineContract = pipelineContract(requestedHdrMode = "NIVUKXVISION", tenBitActive = true),
            cameraPreviewHdrTransportMode = HdrDynamicRangeMode.SDR
        )

        assertEquals(VulkanPreviewRenderPath.HDR_PQ_NATIVE, ndr.renderPath)
        assertEquals(HdrDynamicRangeMode.SDR, ndr.swapchainDynamicRangeMode)
        assertEquals("NDR10+", ndr.normalizedHdrMode)
        assertEquals(VulkanToneDeliveryMode.NDR10_PLUS_MONITOR, ndr.toneDeliveryMode)
        assertTrue(ndr.previewMonitoringEnabled)

        assertEquals(VulkanPreviewRenderPath.HDR_PQ_NATIVE, nivukx.renderPath)
        assertEquals(HdrDynamicRangeMode.SDR, nivukx.swapchainDynamicRangeMode)
        assertEquals("NIVUKXVISION", nivukx.normalizedHdrMode)
        assertEquals(VulkanToneDeliveryMode.NIVUKX_VISION_MONITOR, nivukx.toneDeliveryMode)
        assertTrue(nivukx.previewMonitoringEnabled)
    }

    @Test
    fun logRequestUsesOriginalLogPreviewEvenIfHdrToggleIsStale() {
        val renderContract = resolveRenderContract(
            pipelineContract = pipelineContract(
                cinemaMode = CinemaVideoMode.LOG,
                logEnabled = true,
                requestedHdrMode = "HDR10",
                tenBitActive = true
            ),
            logCurve = NeoLogCurve.N_LOG,
            cameraPreviewHdrTransportMode = HdrDynamicRangeMode.HDR10
        )

        assertEquals(VulkanPreviewRenderPath.LOG_ORIGINAL, renderContract.renderPath)
        assertEquals(HdrDynamicRangeMode.SDR, renderContract.swapchainDynamicRangeMode)
        assertEquals(VulkanToneDeliveryMode.LOG_N_LOG_BASE, renderContract.toneDeliveryMode)
        assertEquals("OFF", renderContract.normalizedHdrMode)
        assertTrue(renderContract.logCaptureActive)
        assertFalse(renderContract.previewMonitoringEnabled)
        assertNotEquals(VulkanToneDeliveryMode.HLG_MONITOR, renderContract.toneDeliveryMode)
        assertNotEquals(VulkanToneDeliveryMode.PQ_HDR, renderContract.toneDeliveryMode)
    }

    @Test
    fun rawModeUsesVisibleRawMonitorWithoutChangingOriginalRawDelivery() {
        val renderContract = resolveRenderContract(
            pipelineContract = pipelineContract(
                cinemaMode = CinemaVideoMode.RAW,
                rawVideoActive = true,
                logEnabled = true,
                requestedHdrMode = "HDR10+"
            )
        )

        assertEquals(VulkanPreviewRenderPath.RAW_MONITOR, renderContract.renderPath)
        assertEquals(HdrDynamicRangeMode.SDR, renderContract.swapchainDynamicRangeMode)
        assertEquals(VulkanToneDeliveryMode.RAW_MONITOR, renderContract.toneDeliveryMode)
        assertTrue(renderContract.rawVideoDelivery)
        assertTrue(renderContract.rawVideoActive)
        assertTrue(renderContract.truthfulColorDelivery)
        assertTrue(renderContract.previewMonitoringEnabled)
    }

    @Test
    fun cinemaDebayerChangesRawRenderPathAndSignatureOnlyForRawPreview() {
        val raw = pipelineContract(
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true
        )

        val monitor = resolveRenderContract(raw, cinemaDebayerActive = false)
        val debayer = resolveRenderContract(raw, cinemaDebayerActive = true)

        assertEquals(VulkanPreviewRenderPath.RAW_MONITOR, monitor.renderPath)
        assertEquals(VulkanPreviewRenderPath.CINEMA_DEBAYER_MONITOR, debayer.renderPath)
        assertNotEquals(monitor.signature, debayer.signature)
        assertTrue(debayer.preservesPreviewAspect)
    }

    @Test
    fun renderQualityDropsToFastUnderHighLoadAndUsesMaximumForStrictIdlePreview() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true
            val idle = resolveRenderContract(
                pipelineContract = pipelineContract(),
                highLoadPreviewActive = false,
                previewFpsHint = 60f
            )
            val highLoad = resolveRenderContract(
                pipelineContract = pipelineContract(),
                highLoadPreviewActive = true,
                previewFpsHint = 18f
            )

            assertEquals(VulkanPreviewRenderQuality.MAXIMUM, idle.renderQuality)
            assertEquals(VulkanPreviewRenderQuality.FAST, highLoad.renderQuality)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun renderSignatureChangesWhenOnlyModeContractChanges() {
        val pro = resolveRenderContract(pipelineContract(cinemaMode = CinemaVideoMode.PRO))
        val hdr = resolveRenderContract(pipelineContract(requestedHdrMode = "HDR10"))
        val log = resolveRenderContract(
            pipelineContract(
                cinemaMode = CinemaVideoMode.LOG,
                logEnabled = true,
                requestedHdrMode = "OFF"
            )
        )
        val raw = resolveRenderContract(
            pipelineContract(
                cinemaMode = CinemaVideoMode.RAW,
                rawVideoActive = true
            )
        )

        assertNotEquals(pro.signature, hdr.signature)
        assertNotEquals(hdr.signature, log.signature)
        assertNotEquals(log.signature, raw.signature)
    }

    private fun pipelineContract(
        cinemaMode: CinemaVideoMode = CinemaVideoMode.PRO,
        rawVideoActive: Boolean = false,
        logEnabled: Boolean = false,
        requestedHdrMode: String = "OFF",
        tenBitActive: Boolean = false
    ): VideoModePipelineContract =
        VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = cinemaMode,
            rawVideoActive = rawVideoActive,
            logEnabled = logEnabled,
            requestedHdrMode = requestedHdrMode,
            preferredColorSpace = "Rec.709",
            tenBitActive = tenBitActive
        )

    private fun resolveRenderContract(
        pipelineContract: VideoModePipelineContract,
        nativePreviewLutEnabled: Boolean = false,
        nativePreviewLutSize: Int = 0,
        logCurve: NeoLogCurve = NeoLogCurve.NONE,
        cinemaDebayerActive: Boolean = false,
        highLoadPreviewActive: Boolean = false,
        previewFpsHint: Float = 60f,
        cameraPreviewHdrTransportMode: HdrDynamicRangeMode? = null
    ) = VulkanPreviewRenderContractPolicy.resolve(
        pipelineContract = pipelineContract,
        previewLookState = previewLookFor(pipelineContract),
        nativePreviewLutEnabled = nativePreviewLutEnabled,
        nativePreviewLutSize = nativePreviewLutSize,
        logCurve = logCurve,
        cinemaDebayerActive = cinemaDebayerActive,
        highLoadPreviewActive = highLoadPreviewActive,
        previewFpsHint = previewFpsHint,
        cameraPreviewHdrTransportMode = cameraPreviewHdrTransportMode
    )

    private fun previewLookFor(contract: VideoModePipelineContract): VideoFormatPreviewLookState =
        when (contract.previewKind) {
            VideoPipelinePreviewKind.SDR -> VideoFormatPreviewLookState()
            VideoPipelinePreviewKind.HDR_10BIT -> VideoFormatPreviewLookState(
                hdrEnabled = true,
                hdrMode = contract.effectiveHdrMode,
                displayReferredMonitoring = true,
                previewKind = contract.previewKind,
                toneMap = contract.toneMap
            )
            VideoPipelinePreviewKind.LOG_10BIT -> VideoFormatPreviewLookState(
                logEnabled = true,
                displayReferredMonitoring = false,
                previewKind = contract.previewKind,
                toneMap = VideoPipelineToneMap.LOG_ORIGINAL
            )
            VideoPipelinePreviewKind.RAW_SENSOR -> VideoFormatPreviewLookState(
                rawEnabled = true,
                displayReferredMonitoring = true,
                previewKind = contract.previewKind,
                toneMap = VideoPipelineToneMap.RAW_LINEAR_MONITOR
            )
        }
}
