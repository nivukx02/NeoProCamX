package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoColorStabilityPolicyTest {
    @Test
    fun `processed recording locks established auto white balance during focus changes`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = true,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = false,
            ndrOrLogModeActive = false
        )

        // Auto stability path locks WHITE BALANCE but keeps exposure ADAPTIVE so it re-meters on a
        // genuine scene change (an auto AE lock would latch permanently — see policy comment).
        assertFalse(plan.aeLockRequested)
        assertTrue(plan.awbLockRequested)
        assertTrue(plan.allowManualWhiteBalance)
        assertTrue(plan.requiresLockableAutoWhiteBalance)
        assertFalse(plan.sceneAdaptiveColorAllowed)
        assertEquals(0.0015f, plan.maxFrameToFrameColorDriftUv, 0.00001f)
        assertEquals("NORMAL_SDR_VIDEO_RECORDING_AWB_LOCKED_AE_ADAPTIVE", plan.evidenceLabel)
    }

    @Test
    fun `ndr and log preview stays adaptive until auto color converges`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = false,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = false,
            normalSdrVideoPreviewActive = false,
            ndrOrLogVideoPreviewActive = true,
            autoExposureConvergedOrLocked = false,
            autoWhiteBalanceConvergedOrLocked = false,
            ndrOrLogModeActive = true
        )

        assertFalse(plan.aeLockRequested)
        assertFalse(plan.awbLockRequested)
        assertTrue(plan.allowManualWhiteBalance)
        assertFalse(plan.requiresLockableAutoWhiteBalance)
        assertTrue(plan.sceneAdaptiveColorAllowed)
        assertEquals("NDR_LOG_PREVIEW_AE_AWB_ADAPTIVE", plan.evidenceLabel)
    }

    @Test
    fun `ndr and log preview locks white balance only after auto color converges`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = false,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = false,
            normalSdrVideoPreviewActive = false,
            ndrOrLogVideoPreviewActive = true,
            autoExposureConvergedOrLocked = true,
            autoWhiteBalanceConvergedOrLocked = true,
            ndrOrLogModeActive = true
        )

        // AWB-only white-point hold: white balance locks, exposure stays adaptive.
        assertFalse(plan.aeLockRequested)
        assertTrue(plan.awbLockRequested)
        assertTrue(plan.allowManualWhiteBalance)
        // Must NOT engage the normal-SDR governor path — keeps HLG HIGH_QUALITY colour + AUTO AWB.
        assertFalse(plan.requiresLockableAutoWhiteBalance)
        assertTrue(plan.sceneAdaptiveColorAllowed)
        assertEquals(0.0060f, plan.maxFrameToFrameColorDriftUv, 0.00001f)
        assertEquals("NDR_LOG_PREVIEW_AWB_LOCKED", plan.evidenceLabel)
    }

    @Test
    fun `ndr and log recording locks white balance only after auto exposure can adapt`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = true,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = false,
            ndrOrLogModeActive = true
        )

        assertFalse(plan.aeLockRequested)
        assertTrue(plan.awbLockRequested)
        assertTrue(plan.allowManualWhiteBalance)
        assertFalse(plan.requiresLockableAutoWhiteBalance)
        assertTrue(plan.sceneAdaptiveColorAllowed)
        assertEquals("AUTO_WB_SHOT_LOCKED", plan.evidenceLabel)
    }

    @Test
    fun `manual white balance remains available in ndr and log preview`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = false,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = true,
            existingAeAwbLock = false,
            normalSdrVideoPreviewActive = false,
            ndrOrLogModeActive = true
        )

        assertFalse(plan.aeLockRequested)
        assertFalse(plan.awbLockRequested)
        assertTrue(plan.allowManualWhiteBalance)
        assertFalse(plan.requiresLockableAutoWhiteBalance)
        assertTrue(plan.sceneAdaptiveColorAllowed)
        assertEquals("MANUAL_WB_PREVIEW", plan.evidenceLabel)
    }

    @Test
    fun `npv10 capture uses the same stable recording color request path`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = true,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = true,
            existingAeAwbLock = false,
            normalSdrVideoPreviewActive = false,
            ndrOrLogModeActive = false
        )

        assertFalse(plan.aeLockRequested)
        assertFalse(plan.awbLockRequested)
        assertTrue(plan.allowManualWhiteBalance)
    }

    @Test
    fun `raw sensor master keeps raw truth while stabilizing its recording monitor`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = true,
            sensorMasterActive = true,
            manualWhiteBalanceSelected = true,
            existingAeAwbLock = false,
            normalSdrVideoPreviewActive = false,
            ndrOrLogModeActive = true
        )

        assertTrue(plan.awbLockRequested)
        assertFalse(plan.allowManualWhiteBalance)
    }

    @Test
    fun `idle preview may continue adapting white balance`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = false,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = false,
            normalSdrVideoPreviewActive = false,
            ndrOrLogModeActive = false
        )

        assertFalse(plan.aeLockRequested)
        assertFalse(plan.awbLockRequested)
    }

    @Test
    fun `normal sdr video preview locks auto white balance for preview recording parity`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = false,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = false,
            normalSdrVideoPreviewActive = true,
            autoExposureConvergedOrLocked = true,
            autoWhiteBalanceConvergedOrLocked = true,
            ndrOrLogModeActive = false
        )

        assertFalse(plan.aeLockRequested)
        assertTrue(plan.awbLockRequested)
        assertTrue(plan.allowManualWhiteBalance)
        assertTrue(plan.requiresLockableAutoWhiteBalance)
        assertFalse(plan.sceneAdaptiveColorAllowed)
        assertEquals(0.0015f, plan.maxFrameToFrameColorDriftUv, 0.00001f)
        assertEquals("NORMAL_SDR_VIDEO_PREVIEW_AWB_LOCKED_AE_ADAPTIVE", plan.evidenceLabel)
    }

    @Test
    fun `normal sdr video preview stays adaptive before ae and awb convergence`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = false,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = false,
            normalSdrVideoPreviewActive = true,
            autoExposureConvergedOrLocked = false,
            autoWhiteBalanceConvergedOrLocked = false,
            ndrOrLogModeActive = false
        )

        assertFalse(plan.aeLockRequested)
        assertFalse(plan.awbLockRequested)
        assertTrue(plan.allowManualWhiteBalance)
        assertFalse(plan.requiresLockableAutoWhiteBalance)
        assertTrue(plan.sceneAdaptiveColorAllowed)
        assertEquals(0.0060f, plan.maxFrameToFrameColorDriftUv, 0.00001f)
        assertEquals("NORMAL_SDR_VIDEO_PREVIEW_AE_AWB_ADAPTIVE_UNCONVERGED", plan.evidenceLabel)
    }

    @Test
    fun `manual white balance overrides normal sdr preview auto wb lock`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = false,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = true,
            existingAeAwbLock = false,
            normalSdrVideoPreviewActive = true,
            ndrOrLogModeActive = false
        )

        assertFalse(plan.aeLockRequested)
        assertFalse(plan.awbLockRequested)
        assertTrue(plan.allowManualWhiteBalance)
        assertEquals("MANUAL_WB_PREVIEW", plan.evidenceLabel)
    }

    @Test
    fun `tap focus keeps normal sdr preview color locked while moving focus`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = false,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = false,
            normalSdrVideoPreviewActive = true,
            ndrOrLogModeActive = false,
            tapFocusMeteringUnlockActive = true
        )

        assertFalse(plan.aeLockRequested)
        assertTrue(plan.awbLockRequested)
        assertTrue(plan.requiresLockableAutoWhiteBalance)
        assertFalse(plan.sceneAdaptiveColorAllowed)
        assertFalse(plan.tapFocusMeteringUnlockAllowed)
        assertEquals("NORMAL_SDR_VIDEO_PREVIEW_FOCUS_AWB_LOCKED_AE_ADAPTIVE", plan.evidenceLabel)
    }

    @Test
    fun `tap focus temporarily unlocks idle non video metering only`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = false,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = false,
            normalSdrVideoPreviewActive = false,
            ndrOrLogModeActive = false,
            tapFocusMeteringUnlockActive = true
        )

        assertFalse(plan.aeLockRequested)
        assertFalse(plan.awbLockRequested)
        assertFalse(plan.requiresLockableAutoWhiteBalance)
        assertTrue(plan.sceneAdaptiveColorAllowed)
        assertTrue(plan.tapFocusMeteringUnlockAllowed)
        assertEquals("TAP_FOCUS_METERING_UNLOCK", plan.evidenceLabel)
    }

    @Test
    fun `tap focus keeps normal sdr recording color locked while moving focus`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = true,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = true,
            normalSdrVideoPreviewActive = false,
            ndrOrLogModeActive = false,
            tapFocusMeteringUnlockActive = true
        )

        assertTrue(plan.aeLockRequested)
        assertTrue(plan.awbLockRequested)
        assertTrue(plan.requiresLockableAutoWhiteBalance)
        assertFalse(plan.sceneAdaptiveColorAllowed)
        assertTrue(plan.allowManualWhiteBalance)
        assertFalse(plan.tapFocusMeteringUnlockAllowed)
        assertEquals("NORMAL_SDR_VIDEO_RECORDING_FOCUS_AE_AWB_LOCKED", plan.evidenceLabel)
    }
}
