package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaStreamGraphPolicyTest {

    @Test
    fun `raw video graph requires raw sensor and keeps yuv sidecar out of session`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                rawVideoCaptureEnabled = true,
                recordingReadoutOverrideSize = CameraStreamSize(4096, 3072)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CinemaStreamGraphMode.RAW_ORIGINAL_RECORDING, plan.mode)
        assertTrue(plan.rawSensor.createReader)
        assertTrue(plan.rawSensor.includeInSession)
        assertEquals(CinemaStreamRequirement.REQUIRED, plan.rawSensor.requirement)
        assertEquals(CameraStreamSize(4096, 3072), plan.rawSensor.size)

        assertTrue(plan.encoder.includeInSession)
        assertEquals(CinemaStreamRequirement.REQUIRED, plan.encoder.requirement)

        assertFalse(plan.yuvAnalysis.createReader)
        assertFalse(plan.yuvAnalysis.includeInSession)
        assertEquals(CinemaStreamRequirement.OMITTED, plan.yuvAnalysis.requirement)
    }

    @Test
    fun `encoded cinema graph keeps maximum jpeg snapshot surface with encoder`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                uncompressedReadoutRecording = false,
                rawVideoCaptureEnabled = false,
                recordingReadoutOverrideSize = CameraStreamSize(4096, 3072),
                recordingEncoderTargetSize = CameraStreamSize(3840, 2160)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072), CameraStreamSize(3840, 2160)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CinemaStreamGraphMode.ENCODED_CINEMA, plan.mode)
        assertEquals(CameraStreamSize(3840, 2160), plan.encoder.size)
        assertEquals(CinemaStreamRequirement.REQUIRED, plan.encoder.requirement)
        assertTrue(plan.encoder.includeInSession)

        assertFalse(plan.rawSensor.createReader)
        assertFalse(plan.rawSensor.includeInSession)
        assertEquals(CinemaStreamRequirement.OMITTED, plan.rawSensor.requirement)
        assertFalse(plan.yuvAnalysis.createReader)
        assertFalse(plan.yuvAnalysis.includeInSession)
        assertEquals(CinemaStreamRequirement.OMITTED, plan.yuvAnalysis.requirement)
        assertTrue(plan.jpegStill.createReader)
        assertTrue(plan.jpegStill.includeInSession)
        assertEquals(CinemaStreamRequirement.REQUIRED, plan.jpegStill.requirement)
        assertFalse(plan.hardwareImport.createReader)
        assertFalse(plan.hardwareImport.includeInSession)
    }

    @Test
    fun `one k recording keeps sensor maximum jpeg snapshot resolution`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                uncompressedReadoutRecording = false,
                rawVideoCaptureEnabled = false,
                recordingEncoderTargetSize = CameraStreamSize(1024, 576)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(1024, 576)),
                jpegOutputSizes = listOf(
                    CameraStreamSize(4096, 3072),
                    CameraStreamSize(1920, 1440)
                ),
                encoderOutputSizes = listOf(CameraStreamSize(1024, 576))
            )
        )

        assertEquals(CameraStreamSize(1024, 576), plan.encoder.size)
        assertEquals(CameraStreamSize(4096, 3072), plan.jpegStill.size)
        assertTrue(plan.jpegStill.includeInSession)
        assertEquals(CinemaStreamRequirement.REQUIRED, plan.jpegStill.requirement)
    }

    @Test
    fun `encoded graph does not upsize beyond requested encoder target when exact size is unavailable`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                uncompressedReadoutRecording = false,
                rawVideoCaptureEnabled = false,
                recordingEncoderTargetSize = CameraStreamSize(3840, 2160)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(3840, 2160), CameraStreamSize(1920, 1080)),
                encoderOutputSizes = listOf(
                    CameraStreamSize(7680, 4320),
                    CameraStreamSize(1920, 1080)
                )
            )
        )

        assertEquals(CinemaStreamGraphMode.ENCODED_CINEMA, plan.mode)
        assertEquals(CameraStreamSize(1920, 1080), plan.encoder.size)
    }

    @Test
    fun `custom packed10 npv10 graph creates p010 readout in session for packet delivery`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                uncompressedReadoutRecording = true,
                rawVideoCaptureEnabled = false,
                recordingReadoutOverrideSize = null,
                recordingEncoderTargetSize = null
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(3840, 2160), CameraStreamSize(1920, 1080)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CinemaStreamGraphMode.ENCODED_CINEMA_WITH_YUV_READOUT, plan.mode)
        assertTrue(plan.yuvAnalysis.createReader)
        assertTrue(plan.yuvAnalysis.includeInSession)
        assertEquals(CameraStreamSize(1920, 1080), plan.yuvAnalysis.size)
        assertFalse(plan.rawSensor.includeInSession)
    }

    @Test
    fun `uncompressed bytebuffer recording keeps yuv readout even when sidecar mode is disabled`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                uncompressedReadoutRecording = true,
                rawVideoCaptureEnabled = false,
                recordingReadoutOverrideSize = null,
                recordingEncoderTargetSize = CameraStreamSize(1920, 1080),
                readoutSidecarMode = CameraReadoutSidecarMode.DISABLED
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(1920, 1080)),
                encoderOutputSizes = listOf(CameraStreamSize(1920, 1080))
            )
        )

        assertEquals(CinemaStreamGraphMode.ENCODED_CINEMA_WITH_YUV_READOUT, plan.mode)
        assertEquals(CinemaStreamRequirement.REQUIRED, plan.yuvAnalysis.requirement)
        assertTrue(plan.yuvAnalysis.createReader)
        assertTrue(plan.yuvAnalysis.includeInSession)
    }

    @Test
    fun `encoded recording omits hardware import even when requested`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                rawVideoCaptureEnabled = false,
                hardwareImportRequested = true,
                recordingReadoutOverrideSize = CameraStreamSize(4096, 3072),
                recordingEncoderTargetSize = CameraStreamSize(3840, 2160)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072), CameraStreamSize(3840, 2160)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CinemaStreamGraphMode.ENCODED_CINEMA, plan.mode)
        assertTrue(plan.encoder.includeInSession)
        assertFalse(plan.hardwareImport.createReader)
        assertFalse(plan.hardwareImport.includeInSession)
    }

    @Test
    fun `internal recording feed keeps visible preview graph and adds hidden hardware import`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = false,
                videoPreviewActive = true,
                rawVideoCaptureEnabled = false,
                hardwareImportRequested = true,
                internalEncoderHardwareImportActive = true,
                recordingEncoderTargetSize = CameraStreamSize(3840, 2160)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(3840, 2160)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CinemaStreamGraphMode.VIDEO_PREVIEW_READY, plan.mode)
        assertTrue(plan.hardwareImport.createReader)
        assertTrue(plan.hardwareImport.includeInSession)
        assertFalse(plan.encoder.includeInSession)
        assertFalse(plan.rawSensor.includeInSession)
        assertFalse(plan.yuvAnalysis.includeInSession)
    }

    @Test
    fun `recording safe fallback retains required photo surface and raw original`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                rawVideoCaptureEnabled = true,
                recordingSafeSessionFallback = true,
                recordingReadoutOverrideSize = CameraStreamSize(4096, 3072)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                jpegOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertTrue(plan.encoder.includeInSession)
        assertTrue(plan.rawSensor.includeInSession)
        assertTrue(plan.jpegStill.includeInSession)
        assertEquals(CinemaStreamRequirement.REQUIRED, plan.jpegStill.requirement)
        assertFalse(plan.yuvAnalysis.includeInSession)
        assertFalse(plan.fallbackDropOrder.contains(CinemaStreamFallbackStep.JPEG_STILL))
        assertTrue(plan.fallbackDropOrder.contains(CinemaStreamFallbackStep.YUV_ANALYSIS))
    }

    @Test
    fun `jpeg still stream remains available in normal preview graph at original photo size`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(recordingActive = false, rawVideoCaptureEnabled = false),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(1920, 1080)),
                jpegOutputSizes = listOf(CameraStreamSize(8192, 6144), CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CinemaStreamGraphMode.PREVIEW_STILL_READY, plan.mode)
        assertEquals(CameraStreamSize(8192, 6144), plan.jpegStill.size)
        assertTrue(plan.jpegStill.createReader)
        assertTrue(plan.jpegStill.includeInSession)
        assertEquals(CinemaStreamRequirement.REQUIRED, plan.jpegStill.requirement)
    }

    @Test
    fun `video preview preconfigures maximum jpeg while omitting analysis sidecars`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = false,
                videoPreviewActive = true,
                rawVideoCaptureEnabled = false,
                hardwareImportRequested = true,
                recordingReadoutOverrideSize = CameraStreamSize(4096, 3072),
                recordingEncoderTargetSize = CameraStreamSize(3840, 2160)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072), CameraStreamSize(1920, 1080)),
                jpegOutputSizes = listOf(CameraStreamSize(8192, 6144), CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CinemaStreamGraphMode.VIDEO_PREVIEW_READY, plan.mode)
        assertFalse(plan.rawSensor.createReader)
        assertFalse(plan.rawSensor.includeInSession)
        assertFalse(plan.yuvAnalysis.createReader)
        assertFalse(plan.yuvAnalysis.includeInSession)
        assertTrue(plan.jpegStill.createReader)
        assertTrue(plan.jpegStill.includeInSession)
        assertEquals(CameraStreamSize(8192, 6144), plan.jpegStill.size)
        assertEquals(CinemaStreamRequirement.REQUIRED, plan.jpegStill.requirement)
        assertFalse(plan.hardwareImport.createReader)
        assertFalse(plan.hardwareImport.includeInSession)
        assertFalse(plan.encoder.includeInSession)
    }

    @Test
    fun `recording fallback order does not include sidecars that are already omitted`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                rawVideoCaptureEnabled = false,
                hardwareImportRequested = true,
                recordingReadoutOverrideSize = CameraStreamSize(4096, 3072)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                jpegOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(
            listOf(
                CinemaStreamFallbackStep.STREAM_USE_CASE_HINTS
            ),
            plan.fallbackDropOrder
        )
    }

    @Test
    fun `video preview omits yuv analysis when no monitor requests the feed`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = false,
                videoPreviewActive = true,
                rawVideoCaptureEnabled = false,
                previewAnalysisRequested = false
            ),
            inventory = baseInventory(
                rawOutputSizes = emptyList(),
                yuvOutputSizes = listOf(CameraStreamSize(1280, 720), CameraStreamSize(3840, 2160)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        // No-downgrade: the default video-preview graph is unchanged when no HUD monitor is active.
        assertFalse(plan.yuvAnalysis.createReader)
        assertFalse(plan.yuvAnalysis.includeInSession)
    }

    @Test
    fun `idle npv10 video preview prearms required p010 readout`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = false,
                videoPreviewActive = true,
                uncompressedReadoutRecording = true,
                rawVideoCaptureEnabled = false,
                previewAnalysisRequested = false
            ),
            inventory = baseInventory(
                rawOutputSizes = emptyList(),
                yuvOutputSizes = listOf(CameraStreamSize(3840, 2160), CameraStreamSize(1920, 1080)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CinemaStreamGraphMode.VIDEO_PREVIEW_READY, plan.mode)
        assertEquals(CinemaStreamRequirement.REQUIRED, plan.yuvAnalysis.requirement)
        assertTrue(plan.yuvAnalysis.createReader)
        assertTrue(plan.yuvAnalysis.includeInSession)
        assertEquals(CameraStreamSize(1920, 1080), plan.yuvAnalysis.size)
    }

    @Test
    fun `video preview attaches a downscaled yuv analysis stream when a monitor requests the feed`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = false,
                videoPreviewActive = true,
                rawVideoCaptureEnabled = false,
                previewAnalysisRequested = true
            ),
            inventory = baseInventory(
                rawOutputSizes = emptyList(),
                yuvOutputSizes = listOf(
                    CameraStreamSize(3840, 2160),
                    CameraStreamSize(1280, 720),
                    CameraStreamSize(640, 480)
                ),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        // The HUD monitor feed re-adds the analysis stream, capped to a small (<=1280 long edge)
        // readout so the extra preview stream stays cheap.
        assertTrue(plan.yuvAnalysis.createReader)
        assertTrue(plan.yuvAnalysis.includeInSession)
        assertEquals(CameraStreamSize(1280, 720), plan.yuvAnalysis.size)
    }

    @Test
    fun `encoded recording keeps optional ndr exposure analysis feed`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                videoPreviewActive = true,
                rawVideoCaptureEnabled = false,
                recordingEncoderTargetSize = CameraStreamSize(3840, 2160),
                previewAnalysisRequested = true
            ),
            inventory = baseInventory(
                rawOutputSizes = emptyList(),
                yuvOutputSizes = listOf(CameraStreamSize(1280, 720), CameraStreamSize(3840, 2160)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertTrue(plan.yuvAnalysis.createReader)
        assertTrue(plan.yuvAnalysis.includeInSession)
        assertEquals(CameraStreamSize(1280, 720), plan.yuvAnalysis.size)
        assertEquals(CinemaStreamGraphMode.ENCODED_CINEMA, plan.mode)
        assertTrue(plan.fallbackDropOrder.contains(CinemaStreamFallbackStep.YUV_ANALYSIS))
    }

    @Test
    fun `recording safe fallback removes optional ndr analysis without reducing encoder`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                videoPreviewActive = true,
                rawVideoCaptureEnabled = false,
                recordingSafeSessionFallback = true,
                recordingEncoderTargetSize = CameraStreamSize(3840, 2160),
                previewAnalysisRequested = true
            ),
            inventory = baseInventory(
                rawOutputSizes = emptyList(),
                yuvOutputSizes = listOf(CameraStreamSize(1280, 720)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertFalse(plan.yuvAnalysis.includeInSession)
        assertTrue(plan.encoder.includeInSession)
        assertEquals(CameraStreamSize(3840, 2160), plan.encoder.size)
        assertEquals(CinemaStreamGraphMode.ENCODED_CINEMA, plan.mode)
    }

    private fun baseRequest(
        recordingActive: Boolean,
        rawVideoCaptureEnabled: Boolean,
        videoPreviewActive: Boolean = false,
        uncompressedReadoutRecording: Boolean = false,
        recordingSafeSessionFallback: Boolean = false,
        hardwareImportRequested: Boolean = false,
        internalEncoderHardwareImportActive: Boolean = false,
        recordingReadoutOverrideSize: CameraStreamSize? = null,
        recordingEncoderTargetSize: CameraStreamSize? = null,
        readoutSidecarMode: CameraReadoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
        previewAnalysisRequested: Boolean = false
    ): CinemaStreamGraphRequest =
        CinemaStreamGraphRequest(
            recordingActive = recordingActive,
            videoPreviewActive = videoPreviewActive,
            uncompressedReadoutRecording = uncompressedReadoutRecording,
            rawVideoCaptureEnabled = rawVideoCaptureEnabled,
            recordingSafeSessionFallback = recordingSafeSessionFallback,
            hardwareImportRequested = hardwareImportRequested,
            internalEncoderHardwareImportActive = internalEncoderHardwareImportActive,
            readoutSidecarMode = readoutSidecarMode,
            analysisTargetSize = CameraStreamSize(1920, 1080),
            recordingReadoutOverrideSize = recordingReadoutOverrideSize,
            recordingEncoderTargetSize = recordingEncoderTargetSize,
            previewAnalysisRequested = previewAnalysisRequested
        )

    private fun baseInventory(
        rawOutputSizes: List<CameraStreamSize>,
        yuvOutputSizes: List<CameraStreamSize>,
        jpegOutputSizes: List<CameraStreamSize> = listOf(CameraStreamSize(4096, 3072)),
        encoderOutputSizes: List<CameraStreamSize>
    ): CinemaStreamGraphInventory =
        CinemaStreamGraphInventory(
            supportsRaw = rawOutputSizes.isNotEmpty(),
            rawOutputSizes = rawOutputSizes,
            yuvOutputSizes = yuvOutputSizes,
            jpegOutputSizes = jpegOutputSizes,
            encoderOutputSizes = encoderOutputSizes,
            fallbackPhotoSize = CameraStreamSize(1920, 1080)
        )
}
