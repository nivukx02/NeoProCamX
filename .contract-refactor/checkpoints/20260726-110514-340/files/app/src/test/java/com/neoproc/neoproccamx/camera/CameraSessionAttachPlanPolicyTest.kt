package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s1-f05 — reconfigure attach-order lock. Freezes the canonical session attach order and the
 * surface-kind → dynamic-range role mapping so a future edit cannot reorder the live session build
 * (`CameraController.createSession` buildList) into a black gap or mis-assign a 10-bit role. The
 * companion source-text lock is [CameraControllerSessionOrchestrationContractTest]. Pure-JVM.
 */
class CameraSessionAttachPlanPolicyTest {

    @Test
    fun `canonical attach order is frozen preview then encoder then readers`() {
        assertEquals(
            listOf(
                CameraSessionSurfaceKind.PREVIEW,
                CameraSessionSurfaceKind.RECORD,
                CameraSessionSurfaceKind.JPEG,
                CameraSessionSurfaceKind.RAW,
                CameraSessionSurfaceKind.YUV_ANALYSIS,
                CameraSessionSurfaceKind.HARDWARE_IMPORT
            ),
            CameraSessionAttachPlanPolicy.CANONICAL_ORDER
        )
    }

    @Test
    fun `hdr role mapping is frozen for every surface kind`() {
        assertEquals(HdrSurfaceRole.PREVIEW, CameraSessionAttachPlanPolicy.hdrRoleFor(CameraSessionSurfaceKind.PREVIEW))
        assertEquals(HdrSurfaceRole.ENCODER, CameraSessionAttachPlanPolicy.hdrRoleFor(CameraSessionSurfaceKind.RECORD))
        assertEquals(HdrSurfaceRole.JPEG, CameraSessionAttachPlanPolicy.hdrRoleFor(CameraSessionSurfaceKind.JPEG))
        assertEquals(HdrSurfaceRole.RAW, CameraSessionAttachPlanPolicy.hdrRoleFor(CameraSessionSurfaceKind.RAW))
        assertEquals(HdrSurfaceRole.ANALYSIS, CameraSessionAttachPlanPolicy.hdrRoleFor(CameraSessionSurfaceKind.YUV_ANALYSIS))
        assertEquals(HdrSurfaceRole.ENCODER, CameraSessionAttachPlanPolicy.hdrRoleFor(CameraSessionSurfaceKind.HARDWARE_IMPORT))
        assertEquals(HdrSurfaceRole.ANALYSIS, CameraSessionAttachPlanPolicy.hdrRoleFor(CameraSessionSurfaceKind.UNKNOWN))
    }

    @Test
    fun `full stream graph attaches all six surfaces in canonical order with correct roles`() {
        val plan = CameraSessionAttachPlanPolicy.resolveAttachOrder(
            hasPreview = true,
            hasRecord = true,
            includeJpeg = true,
            includeRaw = true,
            includeYuvAnalysis = true,
            includeHardwareImport = true
        )
        assertEquals(
            listOf(
                CameraSessionSurfaceKind.PREVIEW to HdrSurfaceRole.PREVIEW,
                CameraSessionSurfaceKind.RECORD to HdrSurfaceRole.ENCODER,
                CameraSessionSurfaceKind.JPEG to HdrSurfaceRole.JPEG,
                CameraSessionSurfaceKind.RAW to HdrSurfaceRole.RAW,
                CameraSessionSurfaceKind.YUV_ANALYSIS to HdrSurfaceRole.ANALYSIS,
                CameraSessionSurfaceKind.HARDWARE_IMPORT to HdrSurfaceRole.ENCODER
            ),
            plan.map { it.kind to it.hdrRole }
        )
    }

    @Test
    fun `encoded recording attaches preview then encoder only`() {
        val plan = CameraSessionAttachPlanPolicy.resolveAttachOrder(
            hasPreview = true,
            hasRecord = true,
            includeJpeg = false,
            includeRaw = false,
            includeYuvAnalysis = false,
            includeHardwareImport = false
        )
        assertEquals(
            listOf(CameraSessionSurfaceKind.PREVIEW, CameraSessionSurfaceKind.RECORD),
            plan.map { it.kind }
        )
    }

    @Test
    fun `encoded recording with yuv readout keeps preview encoder then analysis`() {
        val plan = CameraSessionAttachPlanPolicy.resolveAttachOrder(
            hasPreview = true,
            hasRecord = true,
            includeJpeg = false,
            includeRaw = false,
            includeYuvAnalysis = true,
            includeHardwareImport = false
        )
        assertEquals(
            listOf(
                CameraSessionSurfaceKind.PREVIEW,
                CameraSessionSurfaceKind.RECORD,
                CameraSessionSurfaceKind.YUV_ANALYSIS
            ),
            plan.map { it.kind }
        )
    }

    @Test
    fun `raw original recording attaches preview encoder then raw`() {
        val plan = CameraSessionAttachPlanPolicy.resolveAttachOrder(
            hasPreview = true,
            hasRecord = true,
            includeJpeg = false,
            includeRaw = true,
            includeYuvAnalysis = false,
            includeHardwareImport = false
        )
        assertEquals(
            listOf(
                CameraSessionSurfaceKind.PREVIEW,
                CameraSessionSurfaceKind.RECORD,
                CameraSessionSurfaceKind.RAW
            ),
            plan.map { it.kind }
        )
    }

    @Test
    fun `preview still ready attaches preview then still and analysis readers without an encoder`() {
        val plan = CameraSessionAttachPlanPolicy.resolveAttachOrder(
            hasPreview = true,
            hasRecord = false,
            includeJpeg = true,
            includeRaw = true,
            includeYuvAnalysis = true,
            includeHardwareImport = false
        )
        assertEquals(
            listOf(
                CameraSessionSurfaceKind.PREVIEW,
                CameraSessionSurfaceKind.JPEG,
                CameraSessionSurfaceKind.RAW,
                CameraSessionSurfaceKind.YUV_ANALYSIS
            ),
            plan.map { it.kind }
        )
    }

    @Test
    fun `the encoder feed never attaches before the visible preview`() {
        val plan = CameraSessionAttachPlanPolicy.resolveAttachOrder(
            hasPreview = true,
            hasRecord = true,
            includeJpeg = true,
            includeRaw = true,
            includeYuvAnalysis = true,
            includeHardwareImport = true
        )
        val kinds = plan.map { it.kind }
        assertEquals(0, kinds.indexOf(CameraSessionSurfaceKind.PREVIEW))
        assertTrue(
            "record/encoder must attach after preview and before optional readers",
            kinds.indexOf(CameraSessionSurfaceKind.RECORD) == 1
        )
        assertTrue(kinds.indexOf(CameraSessionSurfaceKind.RECORD) < kinds.indexOf(CameraSessionSurfaceKind.RAW))
        assertTrue(kinds.indexOf(CameraSessionSurfaceKind.RECORD) < kinds.indexOf(CameraSessionSurfaceKind.YUV_ANALYSIS))
    }

    @Test
    fun `attach order is derived from the live cinema stream graph membership flags`() {
        // Tie the canonical attach order back to the real per-mode stream plan: an encoded-cinema
        // recording attaches preview, encoder, then its required full-resolution video-snapshot output.
        val graph = CinemaStreamGraphPolicy.resolve(
            request = CinemaStreamGraphRequest(
                recordingActive = true,
                videoPreviewActive = false,
                uncompressedReadoutRecording = false,
                rawVideoCaptureEnabled = false,
                recordingSafeSessionFallback = false,
                hardwareImportRequested = false,
                readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
                analysisTargetSize = CameraStreamSize(1920, 1080),
                recordingReadoutOverrideSize = CameraStreamSize(4096, 3072),
                recordingEncoderTargetSize = CameraStreamSize(3840, 2160)
            ),
            inventory = CinemaStreamGraphInventory(
                supportsRaw = true,
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(3840, 2160)),
                jpegOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160)),
                fallbackPhotoSize = CameraStreamSize(1920, 1080)
            )
        )
        val plan = CameraSessionAttachPlanPolicy.resolveAttachOrder(
            hasPreview = true,
            hasRecord = true,
            includeJpeg = graph.jpegStill.includeInSession,
            includeRaw = graph.rawSensor.includeInSession,
            includeYuvAnalysis = graph.yuvAnalysis.includeInSession,
            includeHardwareImport = graph.hardwareImport.includeInSession
        )
        assertEquals(
            listOf(
                CameraSessionSurfaceKind.PREVIEW,
                CameraSessionSurfaceKind.RECORD,
                CameraSessionSurfaceKind.JPEG
            ),
            plan.map { it.kind }
        )
    }
}
