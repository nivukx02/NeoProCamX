package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2SessionGraphParityPolicyTest {

    @Test
    fun `processed recording uses hardware import encoder while keeping preview size independent`() {
        val plan = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            encoderSize = Size(7680, 4320),
            proposedReadoutOverrideSize = Size(4032, 3024),
            recordSurfaceAttachedToSession = true,
            stage = Camera2SessionGraphStage.STARTING
        )

        assertEquals(Camera2SessionGraphKind.PROCESSED_HARDWARE_IMPORT_ENCODER, plan.graphKind)
        assertEquals(VideoDeliveryKind.SDR, plan.deliveryKind)
        assertSizeEquals(Size(1920, 1080), plan.previewSize)
        assertSizeEquals(Size(7680, 4320), plan.encoderSize)
        assertNull(plan.readoutOverrideSize)
        assertFalse(plan.rawSensorOwnsReadout)
        assertFalse(plan.attachRecordSurfaceToSession)
        assertFalse(plan.enablePreviewContinuityRecordSurface)
        assertEquals(Camera2SessionRefreshAction.REBUILD_SESSION, plan.refreshAction)
    }

    @Test
    fun `camera2 fallback recording without preattached encoder surface requests one session rebuild`() {
        val plan = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            recordSurfaceAttachedToSession = false,
            vulkanRuntimeSupported = false,
            stage = Camera2SessionGraphStage.STARTING
        )

        assertEquals(Camera2SessionGraphKind.PROCESSED_PREVIEW_ENCODER_PARITY, plan.graphKind)
        assertTrue(plan.attachRecordSurfaceToSession)
        assertEquals(Camera2SessionRefreshAction.REBUILD_SESSION, plan.refreshAction)
    }

    @Test
    fun `prearmed processed graph and recording start share one session signature`() {
        val prearmed = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            recordSurfaceAttachedToSession = true,
            vulkanRuntimeSupported = false,
            stage = Camera2SessionGraphStage.PREARMED
        )
        val starting = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            recordSurfaceAttachedToSession = true,
            vulkanRuntimeSupported = false,
            previousSessionSignature = prearmed.sessionSignature,
            stage = Camera2SessionGraphStage.STARTING
        )

        assertEquals(prearmed.sessionSignature, starting.sessionSignature)
        assertEquals(Camera2SessionRefreshAction.REPEATING_REQUEST_ONLY, starting.refreshAction)
    }

    @Test
    fun `prearmed hardware import graph starts recording without rebuilding camera2 session`() {
        val prearmed = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            recordSurfaceAttachedToSession = true,
            vulkanRuntimeSupported = true,
            stage = Camera2SessionGraphStage.PREARMED
        )
        val starting = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            recordSurfaceAttachedToSession = true,
            vulkanRuntimeSupported = true,
            previousSessionSignature = prearmed.sessionSignature,
            stage = Camera2SessionGraphStage.STARTING
        )

        assertEquals(Camera2SessionGraphKind.PROCESSED_HARDWARE_IMPORT_ENCODER, starting.graphKind)
        assertEquals(prearmed.sessionSignature, starting.sessionSignature)
        assertEquals(Camera2SessionRefreshAction.REPEATING_REQUEST_ONLY, starting.refreshAction)
    }

    @Test
    fun `hdr mode change requests session rebuild for dynamic range output configuration`() {
        val sdr = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            encoderSurfaceAvailable = false,
            stage = Camera2SessionGraphStage.IDLE
        )
        val hdr = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "HLG",
            tenBitRequested = true,
            encoderSurfaceAvailable = false,
            previousSessionSignature = sdr.sessionSignature,
            stage = Camera2SessionGraphStage.IDLE
        )

        assertEquals(VideoDeliveryKind.HDR, hdr.deliveryKind)
        assertEquals(Camera2SessionRefreshAction.REBUILD_SESSION, hdr.refreshAction)
    }

    @Test
    fun `log processed recording stays out of hdr transport`() {
        val plan = resolvePlan(
            cinemaMode = CinemaVideoMode.LOG,
            logEnabled = true,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            recordSurfaceAttachedToSession = true,
            stage = Camera2SessionGraphStage.STARTING
        )

        assertEquals(VideoDeliveryKind.LOG, plan.deliveryKind)
        assertEquals(Camera2SessionGraphKind.PROCESSED_HARDWARE_IMPORT_ENCODER, plan.graphKind)
        assertTrue(plan.sessionSignature.contains("LOG"))
        assertTrue(plan.sessionSignature.contains("OFF"))
        assertFalse(plan.sessionSignature.contains("HDR10"))
        assertFalse(plan.attachRecordSurfaceToSession)
        assertFalse(plan.enablePreviewContinuityRecordSurface)
        assertEquals(Camera2SessionRefreshAction.REBUILD_SESSION, plan.refreshAction)
    }

    @Test
    fun `raw recording owns raw sensor readout and rebuilds readers`() {
        val plan = resolvePlan(
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            proposedReadoutOverrideSize = Size(4032, 3024),
            recordSurfaceAttachedToSession = true,
            stage = Camera2SessionGraphStage.STARTING
        )

        assertEquals(Camera2SessionGraphKind.RAW_SENSOR_ORIGINAL, plan.graphKind)
        assertEquals(VideoDeliveryKind.RAW, plan.deliveryKind)
        assertSizeEquals(Size(4032, 3024), plan.readoutOverrideSize)
        assertTrue(plan.rawSensorOwnsReadout)
        assertFalse(plan.enablePreviewContinuityRecordSurface)
        assertEquals(Camera2SessionRefreshAction.REBUILD_SESSION_AND_READERS, plan.refreshAction)
    }

    @Test
    fun `processed open gate recording locks one crop scale matrix for preview and recorder`() {
        val plan = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            previewSize = Size(1920, 1080),
            encoderSize = Size(4032, 3024),
            graphPreviewWidth = 1920,
            graphPreviewHeight = 1080,
            graphEncoderWidth = 4032,
            graphEncoderHeight = 3024,
            recordSurfaceAttachedToSession = true,
            stage = Camera2SessionGraphStage.STARTING
        )

        assertEquals(Camera2SessionGraphKind.PROCESSED_HARDWARE_IMPORT_ENCODER, plan.graphKind)
        assertTrue("dualSwapchain=${plan.dualSwapchainParityRequired}", plan.dualSwapchainParityRequired)
        assertEquals(9, plan.geometryCropScaleMatrix.size)
        assertTrue(
            "matrix=${plan.geometryCropScaleMatrix.joinToString()}",
            plan.geometryCropScaleMatrix[0] < 1f || plan.geometryCropScaleMatrix[4] < 1f
        )
        assertTrue(plan.geometryMatrixSignature.startsWith("geom="))
        assertTrue(plan.sessionSignature.contains(plan.geometryMatrixSignature))
        assertEquals(plan.parityPlanViewportScale, plan.viewportResolutionScale, 0.0001f)
    }

    private fun resolvePlan(
        cinemaMode: CinemaVideoMode,
        rawVideoActive: Boolean = false,
        logEnabled: Boolean = false,
        requestedHdrMode: String = "OFF",
        tenBitRequested: Boolean = false,
        previewSize: Size = Size(1920, 1080),
        encoderSize: Size = Size(3840, 2160),
        graphPreviewWidth: Int = previewSize.width,
        graphPreviewHeight: Int = previewSize.height,
        graphEncoderWidth: Int = encoderSize.width,
        graphEncoderHeight: Int = encoderSize.height,
        proposedAnalysisSize: Size = Size(1920, 1080),
        proposedReadoutOverrideSize: Size? = null,
        recordSurfaceAttachedToSession: Boolean = false,
        encoderSurfaceAvailable: Boolean = true,
        vulkanRuntimeSupported: Boolean = true,
        previousSessionSignature: String? = null,
        stage: Camera2SessionGraphStage
    ): Camera2SessionGraphParityPlan {
        val graphContract = VideoPipelineGraphController.resolve(
            VideoPipelineGraphRequest(
                appMode = AppMode.VIDEO,
                cinemaMode = cinemaMode,
                rawVideoActive = rawVideoActive,
                logEnabled = logEnabled,
                requestedHdrMode = requestedHdrMode,
                preferredColorSpace = "Rec.709",
                tenBitRequested = tenBitRequested,
                previewWidth = graphPreviewWidth,
                previewHeight = graphPreviewHeight,
                encoderWidth = graphEncoderWidth,
                encoderHeight = graphEncoderHeight,
                fps = 30,
                recordingState = GpuRecordingState.Idle
            )
        )
        val parityPlan = RecordingPipelineParityPolicy.resolve(
            graphContract = graphContract,
            rawSupported = true,
            vulkanRuntimeSupported = vulkanRuntimeSupported
        )
        return Camera2SessionGraphParityPolicy.resolve(
            Camera2SessionGraphParityRequest(
                graphContract = graphContract,
                parityPlan = parityPlan,
                previewSize = previewSize,
                encoderSize = encoderSize,
                proposedAnalysisSize = proposedAnalysisSize,
                proposedReadoutOverrideSize = proposedReadoutOverrideSize,
                recordSurfaceAttachedToSession = recordSurfaceAttachedToSession,
                encoderSurfaceAvailable = encoderSurfaceAvailable,
                previousSessionSignature = previousSessionSignature,
                stage = stage
            )
        )
    }

    private fun assertSizeEquals(expected: Size, actual: Size?) {
        assertEquals(expected.width, actual?.width)
        assertEquals(expected.height, actual?.height)
    }
}
