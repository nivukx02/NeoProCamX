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
    fun `raw prearm keeps final raw reader untargeted in video preview and removes p010 standby`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = false,
                videoPreviewActive = true,
                uncompressedReadoutRecording = true,
                rawVideoCaptureEnabled = false,
                rawVideoPrearmEnabled = true,
                recordingReadoutOverrideSize = CameraStreamSize(4096, 3072)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CinemaStreamGraphMode.VIDEO_PREVIEW_READY, plan.mode)
        assertTrue(plan.rawSensor.createReader)
        assertTrue(plan.rawSensor.includeInSession)
        assertEquals(CameraStreamSize(4096, 3072), plan.rawSensor.size)
        assertEquals(CinemaStreamRequirement.OPTIONAL, plan.rawSensor.requirement)
        assertFalse(plan.yuvAnalysis.createReader)
        assertFalse(plan.yuvAnalysis.includeInSession)
        assertTrue(plan.rawSensor.reason.contains("prearm", ignoreCase = true))
    }

    @Test
    fun `raw prearm retains exact p010 standby reader without attaching its surface`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = false,
                videoPreviewActive = true,
                rawVideoCaptureEnabled = false,
                rawVideoPrearmEnabled = true,
                recordingReadoutOverrideSize = CameraStreamSize(8192, 6144),
                previewAnalysisRequested = true,
                analysisTargetSize = CameraStreamSize(4096, 3072),
                modeSwitchAnalysisStandbyRequested = true
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(8192, 6144), CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072), CameraStreamSize(1280, 720)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 3072))
            )
        )

        assertTrue(plan.rawSensor.includeInSession)
        assertTrue(plan.rawSensorSurfaceActive)
        assertTrue(plan.yuvAnalysis.createReader)
        assertFalse(plan.yuvAnalysis.includeInSession)
        assertEquals(CinemaStreamRequirement.OPTIONAL, plan.yuvAnalysis.requirement)
        assertEquals(CameraStreamSize(1280, 720), plan.yuvAnalysis.size)
        assertTrue(plan.yuvAnalysis.reason.contains(MODE_SWITCH_YUV_STANDBY_REASON))
    }

    @Test
    fun `processed post-record preview keeps standby analysis at capped resolution`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = false,
                videoPreviewActive = true,
                rawVideoCaptureEnabled = false,
                previewAnalysisRequested = true,
                analysisTargetSize = CameraStreamSize(3840, 2160),
                modeSwitchAnalysisStandbyRequested = true
            ),
            inventory = baseInventory(
                rawOutputSizes = emptyList(),
                yuvOutputSizes = listOf(
                    CameraStreamSize(3840, 2160),
                    CameraStreamSize(1280, 720),
                    CameraStreamSize(800, 480)
                ),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertTrue(plan.yuvAnalysis.includeInSession)
        assertEquals(CameraStreamSize(1280, 720), plan.yuvAnalysis.size)
        assertTrue(plan.yuvAnalysis.reason.contains(MODE_SWITCH_YUV_STANDBY_REASON))
    }

    @Test
    fun `processed preview attaches p010 standby while retaining raw reader without its surface`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = false,
                videoPreviewActive = true,
                rawVideoCaptureEnabled = false,
                analysisTargetSize = CameraStreamSize(1920, 1080),
                modeSwitchRawStandbyRequested = true,
                modeSwitchAnalysisStandbyRequested = true
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(1920, 1080)),
                encoderOutputSizes = listOf(CameraStreamSize(1920, 1080))
            )
        )

        assertEquals(CinemaStreamGraphMode.VIDEO_PREVIEW_READY, plan.mode)
        assertTrue(plan.rawSensor.createReader)
        assertFalse(plan.rawSensor.includeInSession)
        assertFalse(plan.rawSensorSurfaceActive)
        assertTrue(plan.rawSensor.reason.contains("reader retained"))
        assertTrue(plan.yuvAnalysis.createReader)
        assertTrue(plan.yuvAnalysis.includeInSession)
        assertTrue(plan.yuvAnalysis.reason.contains(MODE_SWITCH_YUV_STANDBY_REASON))
    }

    @Test
    fun `raw recording retains preconfigured p010 standby without targeting it`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                rawVideoCaptureEnabled = true,
                rawVideoPrearmEnabled = true,
                recordingReadoutOverrideSize = CameraStreamSize(8192, 6144),
                analysisTargetSize = CameraStreamSize(4096, 3072),
                modeSwitchAnalysisStandbyRequested = true
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(8192, 6144), CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072), CameraStreamSize(1280, 720)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 3072))
            )
        )

        assertEquals(CinemaStreamGraphMode.RAW_ORIGINAL_RECORDING, plan.mode)
        assertTrue(plan.rawSensor.includeInSession)
        assertTrue(plan.rawSensorSurfaceActive)
        assertTrue(plan.yuvAnalysis.createReader)
        assertFalse(plan.yuvAnalysis.includeInSession)
        assertEquals(CinemaStreamRequirement.OPTIONAL, plan.yuvAnalysis.requirement)
        assertEquals(CameraStreamSize(4096, 3072), plan.yuvAnalysis.size)
        assertTrue(plan.yuvAnalysis.reason.contains(MODE_SWITCH_YUV_STANDBY_REASON))
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

        // No-downgrade: the default video-preview graph is unchanged without scene analysis.
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
    fun `sensor linear ndr preview required raw route excludes stale npv10 p010`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = false,
                videoPreviewActive = true,
                uncompressedReadoutRecording = true,
                sensorLinearNdrCaptureEnabled = true,
                rawVideoCaptureEnabled = false,
                analysisTargetSize = CameraStreamSize(4096, 2304)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 2304)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 2304))
            )
        )

        assertEquals(CinemaStreamGraphMode.SENSOR_LINEAR_NDR_PREVIEW, plan.mode)
        assertTrue(plan.rawSensor.includeInSession)
        assertEquals(CinemaStreamRequirement.REQUIRED, plan.rawSensor.requirement)
        assertTrue(plan.rawSensorSurfaceActive)
        assertFalse(plan.yuvAnalysis.createReader)
        assertFalse(plan.yuvAnalysis.includeInSession)
        assertEquals(CinemaStreamRequirement.OMITTED, plan.yuvAnalysis.requirement)
    }

    @Test
    fun `sensor linear ndr keeps full active array raw when delivery override is cropped`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = false,
                videoPreviewActive = true,
                sensorLinearNdrCaptureEnabled = true,
                rawVideoCaptureEnabled = false,
                recordingReadoutOverrideSize = CameraStreamSize(4096, 2304),
                recordingEncoderTargetSize = CameraStreamSize(4096, 2304)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(
                    CameraStreamSize(4096, 2304),
                    CameraStreamSize(4096, 3072)
                ),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 2304)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 2304)),
                activeArrayRawSize = CameraStreamSize(4096, 3072)
            )
        )

        assertEquals(CinemaStreamGraphMode.SENSOR_LINEAR_NDR_PREVIEW, plan.mode)
        assertEquals(CameraStreamSize(4096, 3072), plan.rawSensor.size)
        assertTrue(plan.rawSensor.includeInSession)
    }

    @Test
    fun `raw original recording still honors an explicitly supported cropped raw size`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                rawVideoCaptureEnabled = true,
                recordingReadoutOverrideSize = CameraStreamSize(4096, 2304)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(
                    CameraStreamSize(4096, 2304),
                    CameraStreamSize(4096, 3072)
                ),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 2304)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 2304)),
                activeArrayRawSize = CameraStreamSize(4096, 3072)
            )
        )

        assertEquals(CinemaStreamGraphMode.RAW_ORIGINAL_RECORDING, plan.mode)
        assertEquals(CameraStreamSize(4096, 2304), plan.rawSensor.size)
    }

    @Test
    fun `sensor linear ndr recording required raw route excludes stale npv10 p010`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                videoPreviewActive = true,
                uncompressedReadoutRecording = true,
                sensorLinearNdrCaptureEnabled = true,
                rawVideoCaptureEnabled = false,
                analysisTargetSize = CameraStreamSize(4096, 2304)
            ),
            inventory = baseInventory(
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 2304)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 2304))
            )
        )

        assertEquals(CinemaStreamGraphMode.SENSOR_LINEAR_NDR_RECORDING, plan.mode)
        assertTrue(plan.rawSensor.includeInSession)
        assertEquals(CinemaStreamRequirement.REQUIRED, plan.rawSensor.requirement)
        assertTrue(plan.rawSensorSurfaceActive)
        assertFalse(plan.yuvAnalysis.createReader)
        assertFalse(plan.yuvAnalysis.includeInSession)
        assertEquals(CinemaStreamRequirement.OMITTED, plan.yuvAnalysis.requirement)
        assertFalse(plan.encoder.includeInSession)
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

        // Internal NDR scene analysis re-adds the stream, capped to a small (<=1280 long edge)
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

    @Test
    fun `thermal recording isolation detaches only optional analysis and preserves encoder and jpeg`() {
        val plan = CinemaStreamGraphPolicy.resolve(
            request = baseRequest(
                recordingActive = true,
                videoPreviewActive = true,
                rawVideoCaptureEnabled = false,
                hardwareImportRequested = true,
                internalEncoderHardwareImportActive = true,
                recordingEncoderTargetSize = CameraStreamSize(4096, 3072),
                previewAnalysisRequested = true,
                optionalAnalysisSuspendedForRecording = true
            ),
            inventory = baseInventory(
                rawOutputSizes = emptyList(),
                yuvOutputSizes = listOf(CameraStreamSize(1280, 960), CameraStreamSize(4096, 3072)),
                jpegOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 3072))
            )
        )

        assertEquals(CinemaStreamGraphMode.ENCODED_CINEMA, plan.mode)
        assertTrue(plan.encoder.includeInSession)
        assertEquals(CameraStreamSize(4096, 3072), plan.encoder.size)
        assertTrue(plan.hardwareImport.includeInSession)
        assertTrue(plan.jpegStill.includeInSession)
        assertEquals(CameraStreamSize(8192, 6144), plan.jpegStill.size)
        assertTrue(plan.yuvAnalysis.createReader)
        assertFalse(plan.yuvAnalysis.includeInSession)
        assertTrue(plan.yuvAnalysis.reason.contains("thermally isolated"))
    }

    private fun baseRequest(
        recordingActive: Boolean,
        rawVideoCaptureEnabled: Boolean,
        rawVideoPrearmEnabled: Boolean = false,
        videoPreviewActive: Boolean = false,
        uncompressedReadoutRecording: Boolean = false,
        sensorLinearNdrCaptureEnabled: Boolean = false,
        recordingSafeSessionFallback: Boolean = false,
        hardwareImportRequested: Boolean = false,
        internalEncoderHardwareImportActive: Boolean = false,
        recordingReadoutOverrideSize: CameraStreamSize? = null,
        recordingEncoderTargetSize: CameraStreamSize? = null,
        readoutSidecarMode: CameraReadoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
        previewAnalysisRequested: Boolean = false,
        optionalAnalysisSuspendedForRecording: Boolean = false,
        analysisTargetSize: CameraStreamSize = CameraStreamSize(1920, 1080),
        modeSwitchRawStandbyRequested: Boolean = false,
        modeSwitchAnalysisStandbyRequested: Boolean = false
    ): CinemaStreamGraphRequest =
        CinemaStreamGraphRequest(
            recordingActive = recordingActive,
            videoPreviewActive = videoPreviewActive,
            uncompressedReadoutRecording = uncompressedReadoutRecording,
            rawVideoCaptureEnabled = rawVideoCaptureEnabled,
            sensorLinearNdrCaptureEnabled = sensorLinearNdrCaptureEnabled,
            rawVideoPrearmEnabled = rawVideoPrearmEnabled,
            recordingSafeSessionFallback = recordingSafeSessionFallback,
            hardwareImportRequested = hardwareImportRequested,
            internalEncoderHardwareImportActive = internalEncoderHardwareImportActive,
            readoutSidecarMode = readoutSidecarMode,
            analysisTargetSize = analysisTargetSize,
            recordingReadoutOverrideSize = recordingReadoutOverrideSize,
            recordingEncoderTargetSize = recordingEncoderTargetSize,
            previewAnalysisRequested = previewAnalysisRequested,
            optionalAnalysisSuspendedForRecording = optionalAnalysisSuspendedForRecording,
            modeSwitchRawStandbyRequested = modeSwitchRawStandbyRequested,
            modeSwitchAnalysisStandbyRequested = modeSwitchAnalysisStandbyRequested
        )

    private fun baseInventory(
        rawOutputSizes: List<CameraStreamSize>,
        yuvOutputSizes: List<CameraStreamSize>,
        jpegOutputSizes: List<CameraStreamSize> = listOf(CameraStreamSize(4096, 3072)),
        encoderOutputSizes: List<CameraStreamSize>,
        activeArrayRawSize: CameraStreamSize? = rawOutputSizes.maxByOrNull { it.area }
    ): CinemaStreamGraphInventory =
        CinemaStreamGraphInventory(
            supportsRaw = rawOutputSizes.isNotEmpty(),
            rawOutputSizes = rawOutputSizes,
            yuvOutputSizes = yuvOutputSizes,
            jpegOutputSizes = jpegOutputSizes,
            encoderOutputSizes = encoderOutputSizes,
            fallbackPhotoSize = CameraStreamSize(1920, 1080),
            activeArrayRawSize = activeArrayRawSize
        )
}
