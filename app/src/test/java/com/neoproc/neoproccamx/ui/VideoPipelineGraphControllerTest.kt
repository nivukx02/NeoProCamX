package com.neoproc.neoproccamx.ui

import android.media.MediaFormat
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPipelineGraphControllerTest {

    @Test
    fun `normal video resolves sdr graph and recording grade preview`() {
        val contract = resolveGraph(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF"
        )

        assertEquals(VideoDeliveryKind.SDR, contract.deliveryKind)
        assertEquals("OFF", contract.effectiveHdrMode)
        assertFalse(contract.logEnabled)
        assertFalse(contract.rawEnabled)
        assertTrue(contract.recordingGradePreview)
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, contract.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, contract.colorTransfer)
        assertEquals(VideoCameraRequestFamily.VIDEO_PREVIEW_RECORDING_PARITY, contract.cameraRequestFamily)
        assertEquals(VideoDenoiseProfile.ENCODED_VIDEO_STRICT, contract.denoiseProfile)
        assertEquals(VideoSharpnessProfile.NATURAL_DETAIL, contract.sharpnessProfile)
    }

    @Test
    fun `pro sdr resolves recording parity graph before recording starts`() {
        val contract = resolveGraph(
            appMode = AppMode.PRO,
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Neo Wide Gamut"
        )

        assertEquals(VideoDeliveryKind.SDR, contract.deliveryKind)
        assertEquals("OFF", contract.effectiveHdrMode)
        assertFalse(contract.logEnabled)
        assertFalse(contract.rawEnabled)
        assertTrue(contract.recordingGradePreview)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, contract.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, contract.colorTransfer)
        assertEquals(VideoCameraRequestFamily.VIDEO_PREVIEW_RECORDING_PARITY, contract.cameraRequestFamily)
        assertEquals(VideoDenoiseProfile.ENCODED_VIDEO_STRICT, contract.denoiseProfile)
        assertEquals(VideoSharpnessProfile.NATURAL_DETAIL, contract.sharpnessProfile)
    }

    @Test
    fun `log graph overrides stale hdr identity and uses log encoder metadata`() {
        val contract = resolveGraph(
            cinemaMode = CinemaVideoMode.LOG,
            logEnabled = true,
            requestedHdrMode = "HDR10"
        )

        assertEquals(VideoDeliveryKind.LOG, contract.deliveryKind)
        assertEquals("OFF", contract.effectiveHdrMode)
        assertTrue(contract.logEnabled)
        assertFalse(contract.rawEnabled)
        assertTrue(contract.tenBitRequested)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, contract.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, contract.colorTransfer)
        assertEquals(VideoDenoiseProfile.LOG_DETAIL, contract.denoiseProfile)
        assertEquals(VideoSharpnessProfile.LOG_EDGE_PRESERVE, contract.sharpnessProfile)
    }

    @Test
    fun `log graph does not resolve as hdr`() {
        val contract = resolveGraph(
            cinemaMode = CinemaVideoMode.LOG,
            logEnabled = true,
            requestedHdrMode = "OFF"
        )

        assertEquals(VideoDeliveryKind.LOG, contract.deliveryKind)
        assertEquals("OFF", contract.effectiveHdrMode)
        assertTrue(contract.logEnabled)
        assertFalse(contract.rawEnabled)
        assertTrue(contract.tenBitRequested)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, contract.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, contract.colorTransfer)
        assertEquals(VideoDenoiseProfile.LOG_DETAIL, contract.denoiseProfile)
        assertEquals(VideoSharpnessProfile.LOG_EDGE_PRESERVE, contract.sharpnessProfile)
    }

    @Test
    fun `wide gamut sdr graph keeps bt2020 color standard without hdr transfer`() {
        val contract = resolveGraph(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            preferredColorSpace = "Rec.2020"
        )

        assertEquals(VideoDeliveryKind.SDR, contract.deliveryKind)
        assertEquals("OFF", contract.effectiveHdrMode)
        assertTrue(contract.tenBitRequested)
        assertEquals(MediaFormat.COLOR_STANDARD_BT2020, contract.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, contract.colorTransfer)
        assertEquals(VideoDenoiseProfile.ENCODED_VIDEO_STRICT, contract.denoiseProfile)
    }

    @Test
    fun `extreme detail sdr graph exposes extreme sharpness in contract signature`() {
        val natural = resolveGraph(videoDetailProfile = VideoDetailProfile.NATURAL_DETAIL)
        val extreme = resolveGraph(videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL)

        assertEquals(VideoSharpnessProfile.MOTION_LOCKED_MICRO_DETAIL_V20, natural.sharpnessProfile)
        assertEquals(VideoSharpnessProfile.EXTREME_DETAIL, extreme.sharpnessProfile)
        assertEquals(VideoDenoiseProfile.ZERO_GRAIN_CLARITY_V20, extreme.denoiseProfile)
        assertTrue(natural.graphSignature.contains("MOTION_LOCKED_MICRO_DETAIL_V20"))
        assertTrue(extreme.graphSignature.contains("EXTREME_DETAIL"))
        assertNotEquals(natural.graphSignature, extreme.graphSignature)
    }

    @Test
    fun `pro sdr graph does not overclaim ten bit when gpu p010 is unavailable`() {
        val contract = resolveGraph(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            gpu10BitSupported = false
        )

        assertEquals(VideoDeliveryKind.SDR, contract.deliveryKind)
        assertEquals("OFF", contract.effectiveHdrMode)
        assertFalse(contract.tenBitRequested)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, contract.colorTransfer)
        assertTrue(contract.deliveryState.pipelineContract.strictSdrTenBitBlocked)
        assertFalse(contract.deliveryState.pipelineContract.requiresHdrContainerMetadata)
    }

    @Test
    fun `raw graph disables hdr and log delivery identities`() {
        val contract = resolveGraph(
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            logEnabled = true,
            requestedHdrMode = "HDR10+"
        )

        assertEquals(VideoDeliveryKind.RAW, contract.deliveryKind)
        assertEquals("OFF", contract.effectiveHdrMode)
        assertFalse(contract.logEnabled)
        assertTrue(contract.rawEnabled)
        assertFalse(contract.tenBitRequested)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, contract.colorTransfer)
        assertEquals(VideoCameraRequestFamily.RAW_SENSOR_TRUTHFUL, contract.cameraRequestFamily)
        assertEquals(VideoDenoiseProfile.RAW_TRUTHFUL, contract.denoiseProfile)
        assertEquals(VideoSharpnessProfile.RAW_MONITORING, contract.sharpnessProfile)
    }

    @Test
    fun `graph signature changes for mode resolution fps and delivery changes`() {
        val base = resolveGraph()
        val differentMode = resolveGraph(appMode = AppMode.PRO)
        val differentResolution = resolveGraph(encoderWidth = 3840, encoderHeight = 2160)
        val differentFps = resolveGraph(fps = 60)
        val hdr = resolveGraph(requestedHdrMode = "HLG")
        val log = resolveGraph(cinemaMode = CinemaVideoMode.LOG, logEnabled = true)
        val raw = resolveGraph(cinemaMode = CinemaVideoMode.RAW, rawVideoActive = true)

        assertNotEquals(base.graphSignature, differentMode.graphSignature)
        assertNotEquals(base.graphSignature, differentResolution.graphSignature)
        assertNotEquals(base.graphSignature, differentFps.graphSignature)
        assertNotEquals(base.graphSignature, hdr.graphSignature)
        assertNotEquals(base.graphSignature, log.graphSignature)
        assertNotEquals(base.graphSignature, raw.graphSignature)
    }

    @Test
    fun `graph signature ignores display only monitoring fields`() {
        val base = resolveGraph(displayMonitoringLabel = "clean")
        val displayOnlyChange = resolveGraph(displayMonitoringLabel = "false-color")

        assertEquals(base.graphSignature, displayOnlyChange.graphSignature)
    }

    @Test
    fun `graph signature changes with physical camera and raw source geometry`() {
        val wideCamera = resolveGraph(
            physicalCameraGeometryIdentity = "camera=0|orientation=90|active=0,0,4096,3072|calibration=A",
            sourceWidth = 4096,
            sourceHeight = 3072
        )
        val teleCamera = resolveGraph(
            physicalCameraGeometryIdentity = "camera=2|orientation=90|active=0,0,8192,6144|calibration=B",
            sourceWidth = 8192,
            sourceHeight = 6144
        )

        assertEquals(4096, wideCamera.sourceWidth)
        assertEquals(3072, wideCamera.sourceHeight)
        assertNotEquals(wideCamera.graphSignature, teleCamera.graphSignature)
    }

    private fun resolveGraph(
        appMode: AppMode = AppMode.VIDEO,
        cinemaMode: CinemaVideoMode = CinemaVideoMode.PRO,
        rawVideoActive: Boolean = false,
        logEnabled: Boolean = false,
        requestedHdrMode: String = "OFF",
        preferredColorSpace: String = "Rec.709",
        tenBitRequested: Boolean = false,
        previewWidth: Int = 1920,
        previewHeight: Int = 1080,
        encoderWidth: Int = 4096,
        encoderHeight: Int = 3072,
        fps: Int = 30,
        recordingState: GpuRecordingState = GpuRecordingState.Idle,
        videoDetailProfile: VideoDetailProfile? = null,
        gpu10BitSupported: Boolean = true,
        physicalCameraGeometryIdentity: String = "camera=0|orientation=90",
        sourceWidth: Int = previewWidth,
        sourceHeight: Int = previewHeight,
        displayMonitoringLabel: String = "default"
    ): VideoPipelineContract =
        VideoPipelineGraphController.resolve(
            VideoPipelineGraphRequest(
                appMode = appMode,
                cinemaMode = cinemaMode,
                rawVideoActive = rawVideoActive,
                logEnabled = logEnabled,
                requestedHdrMode = requestedHdrMode,
                preferredColorSpace = preferredColorSpace,
                tenBitRequested = tenBitRequested,
                previewWidth = previewWidth,
                previewHeight = previewHeight,
                encoderWidth = encoderWidth,
                encoderHeight = encoderHeight,
                fps = fps,
                recordingState = recordingState,
                videoDetailProfile = videoDetailProfile,
                gpu10BitSupported = gpu10BitSupported,
                physicalCameraGeometryIdentity = physicalCameraGeometryIdentity,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                displayMonitoringLabel = displayMonitoringLabel
            )
        )
}
