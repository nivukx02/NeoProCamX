package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2PipelineRequestPolicyTest {

    @Test
    fun `sensor linear ndr distinguishes raw truth from raw original isp bypass`() {
        val contract = CameraRawRequestContractPolicy.resolve(
            recordingActive = true,
            rawVideoCaptureEnabled = false,
            rawVideoPrearmEnabled = false,
            sensorLinearNdrCaptureEnabled = true
        )

        assertTrue(contract.rawSensorActive)
        assertFalse(contract.rawOriginalCaptureActive)
        assertTrue(contract.sensorLinearNdrActive)
    }

    @Test
    fun `raw original prearm retains public isp bypass ownership`() {
        val contract = CameraRawRequestContractPolicy.resolve(
            recordingActive = false,
            rawVideoCaptureEnabled = false,
            rawVideoPrearmEnabled = true,
            sensorLinearNdrCaptureEnabled = false
        )

        assertTrue(contract.rawSensorActive)
        assertTrue(contract.rawOriginalCaptureActive)
        assertFalse(contract.sensorLinearNdrActive)
    }

    @Test
    fun `raw prearm and active capture keep an identical Camera2 request contract`() {
        fun resolve(recording: Boolean, capturing: Boolean, prearmed: Boolean) =
            Camera2PipelineRequestPolicy.resolve(
                recordingActive = recording,
                videoPreviewActive = true,
                rawVideoCaptureEnabled = capturing,
                rawVideoPrearmEnabled = prearmed,
                rawPreviewActive = false,
                hasRawReader = true,
                hasYuvReader = true,
                yuvReaderConfiguredAsStandby = true,
                recordingReadoutOverrideSize = Size(4096, 3072),
                analysisTargetSize = Size(4096, 3072),
                readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
                requestedFps = 25,
                availableAntibandingModes = intArrayOf(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO),
                manualExposureActive = true,
                powerGridCountryCode = "IN"
            )

        val idle = resolve(recording = false, capturing = false, prearmed = true)
        val active = resolve(recording = true, capturing = true, prearmed = true)

        assertEquals(active, idle)
        assertTrue(idle.targets.includeRawSensor)
        assertFalse(idle.targets.includeYuvReadout)
        assertEquals(Camera2PipelineMode.RAW_SENSOR_RECORDING, idle.pipelineMode)
    }

    @Test
    fun `encoded recording uses record request contract while targeting encoder`() {
        val plan = Camera2PipelineRequestPolicy.resolve(
            recordingActive = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1920, 1080),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
            requestedFps = 60,
            availableAntibandingModes = intArrayOf(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            ),
            manualExposureActive = false,
            powerGridCountryCode = "US"
        )

        assertEquals(CameraDevice.TEMPLATE_RECORD, plan.template)
        assertFalse(plan.targets.includeRawSensor)
        assertFalse(plan.targets.includeYuvReadout)
        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ, plan.antibandingMode)
        assertEquals(CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD, plan.captureIntent)
        assertEquals(Camera2PipelineMode.VIDEO_PREVIEW_RECORDING_PARITY, plan.pipelineMode)
        assertEquals(Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT, plan.noiseCleanupProfile)
    }

    @Test
    fun `raw recording uses record template and never double targets yuv sidecar`() {
        val plan = Camera2PipelineRequestPolicy.resolve(
            recordingActive = true,
            rawVideoCaptureEnabled = true,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1920, 1080),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
            requestedFps = 24,
            availableAntibandingModes = intArrayOf(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            ),
            manualExposureActive = false,
            powerGridCountryCode = "IN"
        )

        assertEquals(CameraDevice.TEMPLATE_RECORD, plan.template)
        assertTrue(plan.targets.includeRawSensor)
        assertFalse(plan.targets.includeYuvReadout)
        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ, plan.antibandingMode)
        assertEquals(CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD, plan.captureIntent)
        assertEquals(Camera2PipelineMode.RAW_SENSOR_RECORDING, plan.pipelineMode)
        assertEquals(Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL, plan.noiseCleanupProfile)
    }

    @Test
    fun `manual video exposure still reports antibanding for flicker safe sensor timing`() {
        val plan = Camera2PipelineRequestPolicy.resolve(
            recordingActive = true,
            uncompressedReadoutRecording = false,
            rawVideoCaptureEnabled = false,
            hasRawReader = false,
            hasYuvReader = true,
            recordingReadoutOverrideSize = null,
            analysisTargetSize = Size(1920, 1080),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
            requestedFps = 60,
            availableAntibandingModes = intArrayOf(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO),
            manualExposureActive = true,
            powerGridCountryCode = "US"
        )

        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO, plan.antibandingMode)
        assertEquals(CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD, plan.captureIntent)
        assertEquals(Camera2PipelineMode.VIDEO_PREVIEW_RECORDING_PARITY, plan.pipelineMode)
        assertEquals(Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT, plan.noiseCleanupProfile)
    }

    @Test
    fun `uncompressed recording targets yuv readout with record request contract`() {
        val plan = Camera2PipelineRequestPolicy.resolve(
            recordingActive = true,
            uncompressedReadoutRecording = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = false,
            hasYuvReader = true,
            recordingReadoutOverrideSize = null,
            analysisTargetSize = Size(3840, 2160),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
            requestedFps = 60,
            availableAntibandingModes = intArrayOf(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO),
            manualExposureActive = false,
            powerGridCountryCode = "US"
        )

        assertEquals(CameraDevice.TEMPLATE_RECORD, plan.template)
        assertFalse(plan.targets.includeRawSensor)
        assertTrue(plan.targets.includeYuvReadout)
        assertEquals(CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD, plan.captureIntent)
        assertEquals(Camera2PipelineMode.ENCODED_RECORDING_WITH_READOUT, plan.pipelineMode)
        assertEquals(Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT, plan.noiseCleanupProfile)
    }

    @Test
    fun `niv99 preview analysis targets the actual prearmed p010 request input`() {
        val plan = Camera2PipelineRequestPolicy.resolve(
            recordingActive = false,
            videoPreviewActive = true,
            uncompressedReadoutRecording = true,
            previewAnalysisRequested = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = true,
            hasYuvReader = true,
            yuvReaderConfiguredAsStandby = false,
            recordingReadoutOverrideSize = null,
            analysisTargetSize = Size(1920, 1080),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
            requestedFps = 25,
            availableAntibandingModes = intArrayOf(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO),
            manualExposureActive = false,
            powerGridCountryCode = "IN"
        )

        assertTrue(plan.targets.includeYuvReadout)
        assertFalse(plan.targets.includeRawSensor)
        assertEquals(Camera2PipelineMode.VIDEO_PREVIEW_RECORDING_PARITY, plan.pipelineMode)
        assertEquals(CameraDevice.TEMPLATE_RECORD, plan.template)
        assertEquals(CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD, plan.captureIntent)
    }

    @Test
    fun `sensor linear ndr request rejects stale npv10 target and keeps raw exclusive`() {
        val plan = Camera2PipelineRequestPolicy.resolve(
            recordingActive = true,
            videoPreviewActive = true,
            uncompressedReadoutRecording = true,
            rawVideoCaptureEnabled = false,
            sensorLinearNdrCaptureEnabled = true,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = null,
            analysisTargetSize = Size(4096, 2304),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
            requestedFps = 25,
            availableAntibandingModes = intArrayOf(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO),
            manualExposureActive = false,
            powerGridCountryCode = "IN"
        )

        assertTrue(plan.targets.includeRawSensor)
        assertFalse(plan.targets.includeYuvReadout)
        assertEquals(Camera2PipelineMode.SENSOR_LINEAR_NDR_RECORDING, plan.pipelineMode)
        assertEquals(CameraDevice.TEMPLATE_RECORD, plan.template)
        assertEquals(Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL, plan.noiseCleanupProfile)
    }

    @Test
    fun `preview mode uses preview template with strict high end cleanup`() {
        val plan = Camera2PipelineRequestPolicy.resolve(
            recordingActive = false,
            rawVideoCaptureEnabled = false,
            hasRawReader = true,
            // Plain preview with no HUD-monitor analysis stream in session -> no readout sidecar.
            hasYuvReader = false,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1920, 1080),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
            requestedFps = 60,
            availableAntibandingModes = intArrayOf(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO),
            manualExposureActive = false,
            powerGridCountryCode = "US"
        )

        assertEquals(CameraDevice.TEMPLATE_PREVIEW, plan.template)
        assertFalse(plan.targets.hasReadoutSidecar)
        assertEquals(CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW, plan.captureIntent)
        assertEquals(Camera2PipelineMode.PREVIEW, plan.pipelineMode)
        assertEquals(Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT, plan.noiseCleanupProfile)
    }

    @Test
    fun `balanced preview mode can keep lightweight preview cleanup profile`() {
        val plan = Camera2PipelineRequestPolicy.resolve(
            recordingActive = false,
            rawVideoCaptureEnabled = false,
            hasRawReader = true,
            // Plain preview with no HUD-monitor analysis stream in session -> no readout sidecar.
            hasYuvReader = false,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1920, 1080),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
            requestedFps = 60,
            availableAntibandingModes = intArrayOf(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO),
            manualExposureActive = false,
            powerGridCountryCode = "US",
            strictHighEndEnabled = false
        )

        assertEquals(CameraDevice.TEMPLATE_PREVIEW, plan.template)
        assertFalse(plan.targets.hasReadoutSidecar)
        assertEquals(CaptureRequest.CONTROL_CAPTURE_INTENT_PREVIEW, plan.captureIntent)
        assertEquals(Camera2PipelineMode.PREVIEW, plan.pipelineMode)
        assertEquals(Camera2NoiseCleanupProfile.PREVIEW, plan.noiseCleanupProfile)
    }

    @Test
    fun `armed video preview uses the same video ISP request contract as recording`() {
        val plan = Camera2PipelineRequestPolicy.resolve(
            recordingActive = false,
            videoPreviewActive = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = false,
            hasYuvReader = false,
            recordingReadoutOverrideSize = null,
            analysisTargetSize = null,
            readoutSidecarMode = CameraReadoutSidecarMode.DISABLED,
            requestedFps = 60,
            availableAntibandingModes = intArrayOf(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ,
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
            ),
            manualExposureActive = false,
            powerGridCountryCode = "IN"
        )

        assertEquals(CameraDevice.TEMPLATE_RECORD, plan.template)
        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ, plan.antibandingMode)
        assertEquals(CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD, plan.captureIntent)
        assertEquals(Camera2PipelineMode.VIDEO_PREVIEW_RECORDING_PARITY, plan.pipelineMode)
        assertEquals(Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT, plan.noiseCleanupProfile)
    }

    @Test
    fun `raw preview does not spend isp bandwidth on configured p010 standby`() {
        val plan = Camera2PipelineRequestPolicy.resolve(
            recordingActive = false,
            videoPreviewActive = true,
            rawVideoCaptureEnabled = false,
            rawPreviewActive = true,
            hasRawReader = true,
            hasYuvReader = true,
            yuvReaderConfiguredAsStandby = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(4096, 3072),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
            requestedFps = 25,
            availableAntibandingModes = intArrayOf(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO),
            manualExposureActive = true,
            powerGridCountryCode = "IN"
        )

        assertTrue(plan.targets.includeRawSensor)
        assertFalse(plan.targets.includeYuvReadout)
        assertEquals(Camera2PipelineMode.VIDEO_PREVIEW_RECORDING_PARITY, plan.pipelineMode)
        assertEquals(CameraDevice.TEMPLATE_RECORD, plan.template)
    }

    @Test
    fun `video preview and recording do not switch HAL template or capture intent`() {
        val previewPlan = Camera2PipelineRequestPolicy.resolve(
            recordingActive = false,
            videoPreviewActive = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = false,
            hasYuvReader = false,
            recordingReadoutOverrideSize = null,
            analysisTargetSize = null,
            readoutSidecarMode = CameraReadoutSidecarMode.DISABLED,
            requestedFps = 60,
            availableAntibandingModes = intArrayOf(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO),
            manualExposureActive = false,
            powerGridCountryCode = "US"
        )
        val recordingPlan = Camera2PipelineRequestPolicy.resolve(
            recordingActive = true,
            videoPreviewActive = true,
            rawVideoCaptureEnabled = false,
            hasRawReader = false,
            hasYuvReader = false,
            recordingReadoutOverrideSize = null,
            analysisTargetSize = null,
            readoutSidecarMode = CameraReadoutSidecarMode.DISABLED,
            requestedFps = 60,
            availableAntibandingModes = intArrayOf(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO),
            manualExposureActive = false,
            powerGridCountryCode = "US"
        )

        assertEquals(recordingPlan.template, previewPlan.template)
        assertEquals(recordingPlan.captureIntent, previewPlan.captureIntent)
        assertEquals(recordingPlan.noiseCleanupProfile, previewPlan.noiseCleanupProfile)
        assertEquals(recordingPlan.antibandingMode, previewPlan.antibandingMode)
    }

    @Test
    fun `manual raw sensor recording advertises manual capture intent for truthful sensor timing`() {
        val plan = Camera2PipelineRequestPolicy.resolve(
            recordingActive = true,
            rawVideoCaptureEnabled = true,
            hasRawReader = true,
            hasYuvReader = true,
            recordingReadoutOverrideSize = Size(4096, 3072),
            analysisTargetSize = Size(1920, 1080),
            readoutSidecarMode = CameraReadoutSidecarMode.MAXIMUM,
            requestedFps = 24,
            availableAntibandingModes = intArrayOf(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO),
            manualExposureActive = true,
            powerGridCountryCode = "US"
        )

        assertEquals(CaptureRequest.CONTROL_CAPTURE_INTENT_MANUAL, plan.captureIntent)
        assertEquals(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO, plan.antibandingMode)
        assertEquals(Camera2PipelineMode.RAW_SENSOR_RECORDING, plan.pipelineMode)
        assertEquals(Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL, plan.noiseCleanupProfile)
    }
}
