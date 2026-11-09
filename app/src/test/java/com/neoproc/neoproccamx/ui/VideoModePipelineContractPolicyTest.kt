package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoModePipelineContractPolicyTest {

    private val processedSdrPipeline = listOf(
        VideoSensorPipelineStage.SENSOR_RAW,
        VideoSensorPipelineStage.DEMOSAIC,
        VideoSensorPipelineStage.AUTO_WHITE_BALANCE,
        VideoSensorPipelineStage.COLOR_CORRECTION_MATRIX,
        VideoSensorPipelineStage.NOISE_REDUCTION,
        VideoSensorPipelineStage.TONE_MAPPING,
        VideoSensorPipelineStage.GAMMA_CONVERSION,
        VideoSensorPipelineStage.OUTPUT
    )

    private val processedHdrPipeline = listOf(
        VideoSensorPipelineStage.SENSOR_RAW,
        VideoSensorPipelineStage.DEMOSAIC,
        VideoSensorPipelineStage.AUTO_WHITE_BALANCE,
        VideoSensorPipelineStage.COLOR_CORRECTION_MATRIX,
        VideoSensorPipelineStage.NOISE_REDUCTION,
        VideoSensorPipelineStage.HDR_MERGE,
        VideoSensorPipelineStage.TONE_MAPPING,
        VideoSensorPipelineStage.GAMMA_CONVERSION,
        VideoSensorPipelineStage.OUTPUT
    )

    @Test
    fun `pro video with hdr off uses sdr preview and standard camera profile`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.709",
            tenBitActive = false
        )

        assertEquals(VideoPipelineDeliveryKind.PRO_SDR, contract.deliveryKind)
        assertEquals(VideoPipelinePreviewKind.SDR, contract.previewKind)
        assertEquals(VideoPipelineToneMap.REC709_NEUTRAL, contract.toneMap)
        assertEquals("OFF", contract.effectiveHdrMode)
        assertEquals("Rec.709", contract.effectiveColorSpace)
        assertEquals(HdrDynamicRangeMode.SDR, contract.cameraDynamicRangeMode)
        assertTrue(contract.truthfulColorDelivery)
        assertFalse(contract.requiresHdrContainerMetadata)
        assertTrue(contract.tenBitTransportRequested)
        assertTrue(contract.strictSdrTenBitRequired)
        assertTrue(contract.strictSdrTenBitSatisfied)
        assertFalse(contract.strictSdrTenBitBlocked)
        assertEquals(processedSdrPipeline, contract.recordingPipelineStages)
        assertEquals(processedSdrPipeline, contract.previewPipelineStages)
        assertFalse(contract.hasRawHdrOutputShortcut)
    }

    @Test
    fun `pro app mode with hdr off stays active pro sdr video pipeline`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.PRO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Neo Wide Gamut",
            tenBitActive = true
        )

        assertTrue(contract.videoModeActive)
        assertEquals(VideoPipelineDeliveryKind.PRO_SDR, contract.deliveryKind)
        assertEquals(VideoPipelinePreviewKind.SDR, contract.previewKind)
        assertEquals("Neo Wide Gamut", contract.effectiveColorSpace)
        assertTrue(contract.tenBitTransportRequested)
        assertTrue(contract.strictSdrTenBitRequired)
        assertTrue(contract.strictSdrTenBitSatisfied)
        assertFalse(contract.strictSdrTenBitBlocked)
        assertEquals(processedSdrPipeline, contract.recordingPipelineStages)
        assertEquals(processedSdrPipeline, contract.previewPipelineStages)
    }

    @Test
    fun `legacy hdr aliases use sensor linear ndr preview and recording identity`() {
        val hlg = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "HLG",
            preferredColorSpace = "Rec.709",
            tenBitActive = true
        )
        val hdr10 = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "HDR10+",
            preferredColorSpace = "Rec.709",
            tenBitActive = true
        )

        assertEquals(VideoPipelineDeliveryKind.HDR_PQ, hlg.deliveryKind)
        assertEquals(VideoPipelinePreviewKind.HDR_10BIT, hlg.previewKind)
        assertEquals(VideoPipelineToneMap.BT2100_PQ, hlg.toneMap)
        assertEquals("NDR10+", hlg.effectiveHdrMode)
        assertEquals("Rec.2020-NDR10+", hlg.effectiveColorSpace)
        assertEquals(HdrDynamicRangeMode.SDR, hlg.cameraDynamicRangeMode)
        assertTrue(hlg.truthfulColorDelivery)
        assertTrue(hlg.requiresHdrContainerMetadata)
        assertTrue(hlg.tenBitTransportRequested)
        assertEquals(processedHdrPipeline, hlg.recordingPipelineStages)
        assertEquals(processedHdrPipeline, hlg.previewPipelineStages)
        assertFalse(hlg.hasRawHdrOutputShortcut)

        assertEquals(VideoPipelineDeliveryKind.HDR_PQ, hdr10.deliveryKind)
        assertEquals(VideoPipelinePreviewKind.HDR_10BIT, hdr10.previewKind)
        assertEquals(VideoPipelineToneMap.BT2100_PQ, hdr10.toneMap)
        assertEquals("NDR10+", hdr10.effectiveHdrMode)
        assertEquals("Rec.2020-NDR10+", hdr10.effectiveColorSpace)
        assertEquals(HdrDynamicRangeMode.SDR, hdr10.cameraDynamicRangeMode)
        assertEquals(processedHdrPipeline, hdr10.recordingPipelineStages)
    }

    @Test
    fun `neo pq delivery ignores advertised hlg camera input without changing output`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "NIVUKXVISION",
            preferredColorSpace = "Rec.2020",
            tenBitActive = true,
            hardwareCapabilities = VideoModeHardwareCapabilities(
                tenBitSupported = true,
                supportedDynamicRangeProfiles = setOf(
                    VideoDynamicRangeProfile.SDR,
                    VideoDynamicRangeProfile.HLG10
                ),
                preferredNeoHdrCaptureTransport = HdrDynamicRangeMode.HLG
            )
        )

        assertEquals(VideoPipelineDeliveryKind.HDR_PQ, contract.deliveryKind)
        assertEquals(VideoPipelineToneMap.BT2100_PQ, contract.toneMap)
        assertEquals("NIVUKXVISION", contract.effectiveHdrMode)
        assertEquals("Rec.2020-NIVUKX", contract.effectiveColorSpace)
        assertEquals(HdrDynamicRangeMode.SDR, contract.cameraDynamicRangeMode)
        assertTrue(contract.requiresHdrContainerMetadata)
        assertTrue(contract.tenBitTransportRequested)
        assertTrue(contract.truthfulColorDelivery)
    }

    @Test
    fun `log mode overrides stale hdr request and stays sdr log`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.LOG,
            rawVideoActive = false,
            logEnabled = true,
            requestedHdrMode = "HDR10",
            preferredColorSpace = "Rec.2020",
            tenBitActive = true
        )

        assertEquals(VideoPipelineDeliveryKind.LOG_10BIT, contract.deliveryKind)
        assertEquals(VideoPipelinePreviewKind.LOG_10BIT, contract.previewKind)
        assertEquals(VideoPipelineToneMap.LOG_ORIGINAL, contract.toneMap)
        assertEquals("OFF", contract.effectiveHdrMode)
        assertEquals("Rec.2020-LOG", contract.effectiveColorSpace)
        assertEquals(HdrDynamicRangeMode.SDR, contract.cameraDynamicRangeMode)
        assertTrue(contract.truthfulColorDelivery)
        assertTrue(contract.tenBitTransportRequested)
        assertTrue(contract.logToneMapActive)
        assertFalse(contract.hdrToneMapActive)
        assertFalse(contract.requiresHdrContainerMetadata)
        assertEquals(processedSdrPipeline, contract.recordingPipelineStages)
        assertEquals(processedSdrPipeline, contract.previewPipelineStages)
        assertFalse(contract.hasRawHdrOutputShortcut)
    }

    @Test
    fun `log mode remains log when hdr is off`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.LOG,
            rawVideoActive = false,
            logEnabled = true,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.2020",
            tenBitActive = true
        )

        assertEquals(VideoPipelineDeliveryKind.LOG_10BIT, contract.deliveryKind)
        assertEquals(VideoPipelinePreviewKind.LOG_10BIT, contract.previewKind)
        assertEquals(VideoPipelineToneMap.LOG_ORIGINAL, contract.toneMap)
        assertEquals("OFF", contract.effectiveHdrMode)
        assertEquals(processedSdrPipeline, contract.recordingPipelineStages)
        assertFalse(contract.recordingPipelineStages.contains(VideoSensorPipelineStage.HDR_MERGE))
    }

    @Test
    fun `raw mode keeps raw sensor original and disables hdr log delivery`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            logEnabled = true,
            requestedHdrMode = "HDR10+",
            preferredColorSpace = "Rec.2020",
            tenBitActive = true
        )

        assertEquals(VideoPipelineDeliveryKind.RAW_SENSOR, contract.deliveryKind)
        assertEquals(VideoPipelinePreviewKind.RAW_SENSOR, contract.previewKind)
        assertEquals(VideoPipelineToneMap.RAW_LINEAR_MONITOR, contract.toneMap)
        assertEquals("OFF", contract.effectiveHdrMode)
        assertEquals("RAW", contract.effectiveColorSpace)
        assertEquals(HdrDynamicRangeMode.SDR, contract.cameraDynamicRangeMode)
        assertTrue(contract.rawSensorOriginal)
        assertTrue(contract.truthfulColorDelivery)
        assertFalse(contract.tenBitTransportRequested)
        assertFalse(contract.encodedMain10TransportRequested)
        assertFalse(contract.logToneMapActive)
        assertFalse(contract.hdrToneMapActive)
        assertFalse(contract.requiresHdrContainerMetadata)
        assertEquals(
            listOf(VideoSensorPipelineStage.SENSOR_RAW, VideoSensorPipelineStage.OUTPUT),
            contract.recordingPipelineStages
        )
        assertEquals(processedSdrPipeline, contract.previewPipelineStages)
        assertFalse(contract.recordingPipelineStages.contains(VideoSensorPipelineStage.HDR_MERGE))
        assertFalse(contract.hasRawHdrOutputShortcut)
        assertEquals(
            VideoPipelineParityClassification.RAW_SENSOR_ORIGINAL_TO_DISPLAY_MONITOR_SPLICE,
            contract.parityEvidence.classification
        )
        assertEquals(VideoSensorPipelineStage.SENSOR_RAW, contract.parityEvidence.divergenceAfterStage)
        assertEquals(10..16, contract.parityEvidence.rawSensorSampleBitDepthRange)
        assertEquals(16, contract.parityEvidence.rawSourceTransportContainerBitDepth)
        assertEquals(12..16, contract.parityEvidence.rawStorageContainerBitDepthRange)
        assertEquals(
            RawStorageSelectionPolicy.SENSOR_WHITE_LEVEL_EXACT,
            contract.parityEvidence.rawStorageSelectionPolicy
        )
        assertEquals(RawMasterFidelity.SENSOR_CODE_EXACT_LINEAR, contract.parityEvidence.rawMasterFidelity)
        assertTrue(contract.parityEvidence.rawStoragePreservesSensorCodes)
        assertTrue(contract.parityEvidence.validationWarning == null)
        assertTrue(contract.sessionSignature.contains("encodedMain10=false"))
        assertTrue(contract.sessionSignature.contains("rawSensorBits=10..16"))
        assertTrue(contract.sessionSignature.contains("rawSourceTransportBits=16"))
        assertTrue(contract.sessionSignature.contains("rawStorageBits=12..16"))
        assertTrue(contract.sessionSignature.contains("rawStoragePolicy=SENSOR_WHITE_LEVEL_EXACT"))
    }

    @Test
    fun `hdr pipeline keeps requested sensor processing order without raw hdr output shortcut`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "HLG",
            preferredColorSpace = "Rec.709",
            tenBitActive = true
        )

        assertEquals(processedHdrPipeline, contract.recordingPipelineStages)
        assertTrue(
            contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.SENSOR_RAW) <
                contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.DEMOSAIC)
        )
        assertTrue(
            contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.AUTO_WHITE_BALANCE) <
                contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.COLOR_CORRECTION_MATRIX)
        )
        assertTrue(
            contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.COLOR_CORRECTION_MATRIX) <
                contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.NOISE_REDUCTION)
        )
        assertTrue(
            contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.NOISE_REDUCTION) <
                contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.HDR_MERGE)
        )
        assertTrue(
            contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.HDR_MERGE) <
                contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.TONE_MAPPING)
        )
        assertTrue(
            contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.TONE_MAPPING) <
                contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.GAMMA_CONVERSION)
        )
        assertTrue(
            contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.GAMMA_CONVERSION) <
                contract.recordingPipelineStages.indexOf(VideoSensorPipelineStage.OUTPUT)
        )
        assertFalse(contract.hasRawHdrOutputShortcut)
    }

    @Test
    fun `session signature changes when the effective preview or delivery pipeline changes`() {
        val pro = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF"
        )
        val hdr = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "HDR10"
        )
        val log = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.LOG,
            rawVideoActive = false,
            logEnabled = true,
            requestedHdrMode = "OFF"
        )
        val raw = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            logEnabled = false,
            requestedHdrMode = "OFF"
        )

        assertNotEquals(pro.sessionSignature, hdr.sessionSignature)
        assertNotEquals(hdr.sessionSignature, log.sessionSignature)
        assertNotEquals(log.sessionSignature, raw.sessionSignature)
    }

    @Test
    fun `capture truth signature distinguishes sdr ndr nivukx log and raw delivery`() {
        val pro = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Display P3",
            tenBitActive = false
        )
        val ndr = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "NDR10+",
            preferredColorSpace = "Rec.2020-NDR10+",
            tenBitActive = true
        )
        val nivukx = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "NIVUKX Vision",
            preferredColorSpace = "Rec.2020-NIVUKX",
            tenBitActive = true
        )
        val log = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.LOG,
            rawVideoActive = false,
            logEnabled = true,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.2020-N-Log",
            tenBitActive = true
        )
        val raw = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            logEnabled = true,
            requestedHdrMode = "NDR10+",
            preferredColorSpace = "RAW",
            tenBitActive = true
        )

        assertTrue(pro.toString().contains("captureTruthSignature=PRO_SDR|SDR|Neo Wide Gamut|hdr=OFF|tenBit=true|log=false|raw=false|hdrMetadata=false"))
        assertTrue(ndr.toString().contains("captureTruthSignature=HDR_PQ|HDR_10BIT|Rec.2020-NDR10+|hdr=NDR10+|tenBit=true|log=false|raw=false|hdrMetadata=true"))
        assertTrue(nivukx.toString().contains("captureTruthSignature=HDR_PQ|HDR_10BIT|Rec.2020-NIVUKX|hdr=NIVUKXVISION|tenBit=true|log=false|raw=false|hdrMetadata=true"))
        assertTrue(log.toString().contains("captureTruthSignature=LOG_10BIT|LOG_10BIT|Rec.2020-LOG|hdr=OFF|tenBit=true|log=true|raw=false|hdrMetadata=false"))
        assertTrue(raw.toString().contains("captureTruthSignature=RAW_SENSOR|RAW_SENSOR|RAW|hdr=OFF|tenBit=false|log=false|raw=true|hdrMetadata=false"))
    }

    @Test
    fun `premium hardware resolves hdr execution graph with bt2020 pq and ten bit precision`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "HDR10+",
            preferredColorSpace = "Rec.709",
            tenBitActive = true,
            hardwareCapabilities = VideoModeHardwareCapabilities.premium(),
            width = 3840,
            height = 2160,
            fps = 60
        )

        assertEquals(emptyList<String>(), contract.validationWarnings)
        assertEquals("NDR10+", contract.effectiveHdrMode)
        assertEquals("Rec.2020-NDR10+", contract.effectiveColorSpace)
        assertTrue(contract.tenBitTransportRequested)
        assertTrue(contract.targetBitrateBps > 1_000_000_000L)
        assertTrue(contract.recordingStagesDetail.isNotEmpty())
        assertEquals(contract.recordingPipelineStages, contract.recordingStagesDetail.map { it.stage })

        // Assert mathematical parameters are correctly computed
        assertEquals(1.5, contract.chromaFactor, 0.001)
        assertEquals(0.18, contract.entropyCoefficient, 0.001)
        assertEquals(10.0 * 1.5 * 0.18, contract.shannonEntropyBitsPerPixel, 0.001)

        val expectedBandwidth = 3840.0 * 2160.0 * 60.0 * (10.0 / 8.0) * 1.5
        assertEquals(expectedBandwidth, contract.requiredBandwidthBytesPerSec, 0.001)

        val expectedFrameSize = (3840.0 * 2160.0 * (10.0 / 8.0) * 1.5).toLong()
        assertEquals(expectedFrameSize, contract.gpuSingleFrameSizeBytes)
        assertEquals(expectedFrameSize * 3, contract.gpuSwapchainTripleBufferSizeBytes)

        // Verify standard conversion matrices, nits and transfer function equations
        val toneMappingStage = contract.recordingStagesDetail.first { it.stage == VideoSensorPipelineStage.TONE_MAPPING }
        assertEquals("BT.2020", toneMappingStage.gamut)
        assertEquals("ST.2084_PQ", toneMappingStage.transferFunction)
        assertEquals("EOTF: L = 10000 * ((max(V^(1/m2) - c1, 0)) / (c2 - c3 * V^(1/m2)))^(1/m1)", toneMappingStage.transferFunctionFormula)
        assertEquals(1000.0f, toneMappingStage.peakNits, 0.01f)
        assertEquals("4:4:4", toneMappingStage.chromaFormat)

        val bt2020ToAcesCg = doubleArrayOf(0.6386, 0.3160, 0.0454, 0.0439, 0.9423, 0.0138, -0.11, 0.0, 0.9) // we check prefix or just presence of array elements
        assertEquals(9, toneMappingStage.transformToAcesCgMatrix.size)
        assertEquals(0.6386, toneMappingStage.transformToAcesCgMatrix[0], 0.001)
        assertEquals(0.3160, toneMappingStage.transformToAcesCgMatrix[1], 0.001)
        assertEquals(1.5836, toneMappingStage.transformFromAcesCgMatrix[0], 0.001)
    }

    @Test
    fun `constrained hardware falls back from hdr request without throwing`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "HDR10+",
            preferredColorSpace = "Rec.2020",
            tenBitActive = true,
            hardwareCapabilities = VideoModeHardwareCapabilities(
                rawSupported = false,
                manualSensorSupported = false,
                tenBitSupported = false,
                supportedDynamicRangeProfiles = setOf(VideoDynamicRangeProfile.SDR)
            )
        )

        assertEquals(VideoPipelineDeliveryKind.PRO_SDR, contract.deliveryKind)
        assertEquals("OFF", contract.effectiveHdrMode)
        assertEquals("Rec.709", contract.effectiveColorSpace)
        assertFalse(contract.requiresHdrContainerMetadata)
        assertFalse(contract.tenBitTransportRequested)
        assertEquals("NDR10+", contract.requestedHdrMode)
        assertEquals(VideoPipelineAdmissionState.REJECTED, contract.admissionState)
        assertTrue(contract.admissionFailures.contains(VideoPipelineAdmissionFailure.HDR_TRANSPORT_UNAVAILABLE))
        assertTrue(contract.admissionFailures.contains(VideoPipelineAdmissionFailure.TEN_BIT_TRANSPORT_UNAVAILABLE))
        assertFalse(contract.recordingAllowed)
        assertTrue(contract.validationWarnings.isNotEmpty())
        assertTrue(contract.validationWarnings.any { it.contains("HDR", ignoreCase = true) })
    }

    @Test
    fun `strict normal pro sdr marks missing p010 as blocked instead of acceptable fallback`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.709",
            tenBitActive = true,
            hardwareCapabilities = VideoModeHardwareCapabilities(
                rawSupported = false,
                manualSensorSupported = false,
                tenBitSupported = false,
                supportedDynamicRangeProfiles = setOf(VideoDynamicRangeProfile.SDR)
            )
        )

        assertEquals(VideoPipelineDeliveryKind.PRO_SDR, contract.deliveryKind)
        assertEquals("OFF", contract.effectiveHdrMode)
        assertEquals(HdrDynamicRangeMode.SDR, contract.cameraDynamicRangeMode)
        assertFalse(contract.requiresHdrContainerMetadata)
        assertFalse(contract.tenBitTransportRequested)
        assertTrue(contract.strictSdrTenBitRequired)
        assertFalse(contract.strictSdrTenBitSatisfied)
        assertTrue(contract.strictSdrTenBitBlocked)
        assertTrue(contract.validationWarnings.any { it.contains("refuse 8-bit SDR fallback") })
    }

    @Test
    fun `wide gamut pro sdr requires gpu p010 support before claiming ten bit transport`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.2020",
            tenBitActive = true,
            gpu10BitSupported = false,
            hardwareCapabilities = VideoModeHardwareCapabilities(
                rawSupported = false,
                manualSensorSupported = false,
                tenBitSupported = true,
                wideGamutSdrSupported = true,
                supportedDynamicRangeProfiles = setOf(VideoDynamicRangeProfile.SDR)
            )
        )

        assertEquals(VideoPipelineDeliveryKind.PRO_SDR, contract.deliveryKind)
        assertEquals("Rec.2020", contract.effectiveColorSpace)
        assertEquals(HdrDynamicRangeMode.SDR, contract.cameraDynamicRangeMode)
        assertFalse(contract.tenBitTransportRequested)
        assertFalse(contract.truthfulColorDelivery)
        assertTrue(contract.strictSdrTenBitBlocked)
        assertTrue(contract.validationWarnings.any { it.contains("refuse 8-bit SDR fallback") })
    }

    @Test
    fun `wide gamut pro sdr uses wide gamut sdr tone map instead of rec709 neutral`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.2020",
            tenBitActive = true
        )

        assertEquals("Rec.2020", contract.effectiveColorSpace)
        assertEquals(VideoPipelineToneMap.WIDE_GAMUT_SDR, contract.toneMap)
        assertEquals(VideoPipelineDeliveryKind.PRO_SDR, contract.deliveryKind)
        assertFalse(contract.requiresHdrContainerMetadata)
    }

    @Test
    fun `neo wide gamut sdr stage details preserve neo working gamut instead of rec709`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Neo Wide Gamut",
            tenBitActive = true
        )

        val output = contract.recordingStagesDetail.last()

        assertEquals("Neo Wide Gamut", contract.effectiveColorSpace)
        assertEquals(VideoPipelineToneMap.WIDE_GAMUT_SDR, contract.toneMap)
        assertEquals("Neo Wide Gamut", output.gamut)
        assertEquals("D55", output.whitePoint)
        assertEquals("A2B10G10R10_UNORM_PACK32", output.vulkanFormat)
        assertTrue(output.transformToAcesCgMatrix.any { kotlin.math.abs(it) > 1.0001 })
        assertTrue(contract.captureTruthSignature.contains("Neo Wide Gamut"))
    }

    @Test
    fun `aces ap0 and ap1 manual sdr selections preserve distinct aces working gamuts`() {
        val ap0 = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "ACES AP0",
            tenBitActive = true
        )
        val ap1 = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "ACEScg",
            tenBitActive = true
        )

        assertEquals("Neo ACES AP0", ap0.effectiveColorSpace)
        assertEquals("ACES2065-1", ap0.recordingStagesDetail.last().gamut)
        assertEquals("D60", ap0.recordingStagesDetail.last().whitePoint)
        assertEquals("Neo ACES AP1", ap1.effectiveColorSpace)
        assertEquals("ACEScg", ap1.recordingStagesDetail.last().gamut)
        assertEquals("D60", ap1.recordingStagesDetail.last().whitePoint)
        assertNotEquals(ap0.sessionSignature, ap1.sessionSignature)
    }

    @Test
    fun `raw request is rejected without rewriting requested mode identity`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "RAW",
            tenBitActive = false,
            hardwareCapabilities = VideoModeHardwareCapabilities(
                rawSupported = false,
                manualSensorSupported = false,
                tenBitSupported = true,
                supportedDynamicRangeProfiles = setOf(VideoDynamicRangeProfile.SDR)
            )
        )

        assertEquals(VideoPipelineDeliveryKind.VIDEO_SDR, contract.deliveryKind)
        assertEquals(CinemaVideoMode.RAW, contract.cinemaMode)
        assertEquals(CinemaVideoMode.RAW, contract.requestedCinemaMode)
        assertFalse(contract.rawSensorOriginal)
        assertEquals("Rec.709", contract.effectiveColorSpace)
        assertEquals(VideoPipelineAdmissionState.REJECTED, contract.admissionState)
        assertTrue(
            contract.admissionFailures.contains(
                VideoPipelineAdmissionFailure.RAW_CAPTURE_UNAVAILABLE
            )
        )
        assertFalse(contract.recordingAllowed)
        assertTrue(contract.validationWarnings.any { it.contains("RAW", ignoreCase = true) })
    }

    @Test
    fun `raw mode is not admitted until the native raw route is active`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "OFF",
            tenBitActive = false,
            hardwareCapabilities = VideoModeHardwareCapabilities.premium()
        )

        assertEquals(CinemaVideoMode.RAW, contract.requestedCinemaMode)
        assertEquals(VideoPipelineAdmissionState.REJECTED, contract.admissionState)
        assertTrue(
            contract.admissionFailures.contains(
                VideoPipelineAdmissionFailure.RAW_CAPTURE_ROUTE_INACTIVE
            )
        )
        assertFalse(contract.recordingAllowed)
    }

    @Test
    fun `log request refuses an eight bit transport without becoming pro`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.LOG,
            rawVideoActive = false,
            logEnabled = true,
            requestedHdrMode = "OFF",
            tenBitActive = true,
            hardwareCapabilities = VideoModeHardwareCapabilities(
                tenBitSupported = false,
                tenBitEncoderSupported = false,
                supportedDynamicRangeProfiles = setOf(VideoDynamicRangeProfile.SDR)
            )
        )

        assertEquals(CinemaVideoMode.LOG, contract.requestedCinemaMode)
        assertEquals(CinemaVideoMode.LOG, contract.cinemaMode)
        assertFalse(contract.logToneMapActive)
        assertFalse(contract.tenBitTransportRequested)
        assertEquals(VideoPipelineAdmissionState.REJECTED, contract.admissionState)
        assertTrue(
            contract.admissionFailures.contains(
                VideoPipelineAdmissionFailure.TEN_BIT_TRANSPORT_UNAVAILABLE
            )
        )
        assertFalse(contract.recordingAllowed)
    }

    @Test
    fun `preview and recording stage details preserve color parity before output transform`() {
        val contract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoActive = false,
            logEnabled = false,
            requestedHdrMode = "HDR10+",
            preferredColorSpace = "Rec.709",
            tenBitActive = true,
            hardwareCapabilities = VideoModeHardwareCapabilities.premium()
        )

        val recordingCore = contract.recordingStagesDetail
            .takeWhile { it.stage != VideoSensorPipelineStage.TONE_MAPPING }
            .map { it.stage to (it.gamut to it.transferFunction) }
        val previewCore = contract.previewStagesDetail
            .takeWhile { it.stage != VideoSensorPipelineStage.TONE_MAPPING }
            .map { it.stage to (it.gamut to it.transferFunction) }

        assertEquals(recordingCore, previewCore)
        assertEquals(
            VideoPipelineParityClassification.MATCHED_BEFORE_OUTPUT_TRANSFORM,
            contract.parityEvidence.classification
        )
        assertTrue(contract.validationWarnings.none { it.contains("parity", ignoreCase = true) })
    }

    @Test
    fun `processed route mismatch retains the generic pre-output parity warning`() {
        val sensorIngress = PipelineStageDetail(
            stage = VideoSensorPipelineStage.SENSOR_RAW,
            gamut = "ACEScg",
            transferFunction = "Linear",
            vulkanFormat = "R16G16B16A16_SFLOAT",
            bitDepth = 16,
            whitePoint = "D60"
        )
        val recording = listOf(
            sensorIngress,
            PipelineStageDetail(
                stage = VideoSensorPipelineStage.DEMOSAIC,
                gamut = "ACEScg",
                transferFunction = "Linear",
                vulkanFormat = "R16G16B16A16_SFLOAT",
                bitDepth = 16,
                whitePoint = "D60"
            )
        )
        val preview = listOf(
            sensorIngress,
            PipelineStageDetail(
                stage = VideoSensorPipelineStage.DEMOSAIC,
                gamut = "BT.2020",
                transferFunction = "Linear",
                vulkanFormat = "R16G16B16A16_SFLOAT",
                bitDepth = 16,
                whitePoint = "D65"
            )
        )

        val evidence = VideoModePipelineContractPolicy.classifyPipelineParity(
            rawSensorOriginal = false,
            encodedMain10TransportRequested = true,
            recording = recording,
            preview = preview
        )

        assertEquals(
            VideoPipelineParityClassification.PROCESSED_ROUTE_DIVERGED_BEFORE_OUTPUT_TRANSFORM,
            evidence.classification
        )
        assertEquals(VideoSensorPipelineStage.SENSOR_RAW, evidence.divergenceAfterStage)
        assertTrue(evidence.validationWarning?.contains("parity", ignoreCase = true) == true)
    }

    @Test
    fun `raw label cannot hide a downgraded non sixteen bit master`() {
        val rawContract = VideoModePipelineContractPolicy.resolve(
            uiMode = AppMode.VIDEO,
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            logEnabled = false,
            requestedHdrMode = "OFF",
            preferredColorSpace = "RAW",
            tenBitActive = false
        )
        val downgradedRecording = rawContract.recordingStagesDetail.map { detail ->
            detail.copy(bitDepth = 12)
        }

        val evidence = VideoModePipelineContractPolicy.classifyPipelineParity(
            rawSensorOriginal = true,
            encodedMain10TransportRequested = false,
            recording = downgradedRecording,
            preview = rawContract.previewStagesDetail
        )

        assertEquals(
            VideoPipelineParityClassification.PROCESSED_ROUTE_DIVERGED_BEFORE_OUTPUT_TRANSFORM,
            evidence.classification
        )
        assertFalse(evidence.rawStoragePreservesSensorCodes)
        assertTrue(evidence.validationWarning?.contains("parity", ignoreCase = true) == true)
    }

    @Test
    fun `extended sensor characteristics validation captures color filter arrangement and awb modes`() {
        val capabilities = VideoModeHardwareCapabilities(
            rawSupported = true,
            manualSensorSupported = true,
            tenBitSupported = true,
            awbAvailableModes = listOf(1, 2, 3),
            manualPostProcessingSupported = true,
            sensorColorFilterArrangement = "BGGR"
        )

        assertEquals("BGGR", capabilities.sensorColorFilterArrangement)
        assertTrue(capabilities.manualPostProcessingSupported)
        assertEquals(listOf(1, 2, 3), capabilities.awbAvailableModes)
    }
}
