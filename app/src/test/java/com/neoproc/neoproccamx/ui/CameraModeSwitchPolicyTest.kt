package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModeSwitchPolicyTest {

    @Test
    fun `open request is ignored for active camera states`() {
        assertTrue(CameraModeSwitchPolicy.shouldIgnoreOpenRequest(CameraState.Open))
        assertTrue(CameraModeSwitchPolicy.shouldIgnoreOpenRequest(CameraState.Opening))
        assertTrue(CameraModeSwitchPolicy.shouldIgnoreOpenRequest(CameraState.Streaming))
        assertTrue(CameraModeSwitchPolicy.shouldIgnoreOpenRequest(CameraState.Recording))
        assertFalse(CameraModeSwitchPolicy.shouldIgnoreOpenRequest(CameraState.Closed))
    }

    @Test
    fun `facing switch restart is required only for live camera states`() {
        assertTrue(CameraModeSwitchPolicy.shouldRestartForFacingSwitch(CameraState.Open))
        assertTrue(CameraModeSwitchPolicy.shouldRestartForFacingSwitch(CameraState.Opening))
        assertTrue(CameraModeSwitchPolicy.shouldRestartForFacingSwitch(CameraState.Streaming))
        assertFalse(CameraModeSwitchPolicy.shouldRestartForFacingSwitch(CameraState.Closed))
        assertFalse(CameraModeSwitchPolicy.shouldRestartForFacingSwitch(CameraState.Closing))
        assertFalse(CameraModeSwitchPolicy.shouldRestartForFacingSwitch(CameraState.Error("x")))
    }

    @Test
    fun `mode transition to video restores video features and stabilization`() {
        val plan = CameraModeSwitchPolicy.buildModeTransition(AppMode.PRO, AppMode.VIDEO)

        assertEquals(CameraFeatureSet.VIDEO, plan.restoreFeatureSet)
        assertFalse(plan.shouldClearPreviewStabilization)
        assertTrue(plan.shouldApplyVideoStabilization)
        assertTrue(plan.shouldRefreshPreviewPipeline)
        assertTrue(plan.shouldUpdateRuntimeStats)
    }

    @Test
    fun `mode transition to pro preserves video stabilization`() {
        val plan = CameraModeSwitchPolicy.buildModeTransition(AppMode.VIDEO, AppMode.PRO)

        assertEquals(CameraFeatureSet.VIDEO, plan.restoreFeatureSet)
        assertFalse(plan.shouldClearPreviewStabilization)
        assertTrue(plan.shouldApplyVideoStabilization)
        assertTrue(plan.shouldRefreshPreviewPipeline)
    }

    @Test
    fun `camera switching is disabled during recording`() {
        assertTrue(CameraModeSwitchPolicy.canSwitchFacing(false))
        assertFalse(CameraModeSwitchPolicy.canSwitchFacing(true))
    }

    @Test
    fun `rapid preset changes are throttled to keep camera session responsive`() {
        val accepted = CameraModeSwitchPolicy.resolvePresetActivation(
            current = CameraModePreset.VIDEO,
            requested = CameraModePreset.HDR,
            nowUptimeMs = 1_000L,
            lastAcceptedUptimeMs = 0L
        )
        val throttled = CameraModeSwitchPolicy.resolvePresetActivation(
            current = CameraModePreset.HDR,
            requested = CameraModePreset.LOG_VIDEO,
            nowUptimeMs = 1_100L,
            lastAcceptedUptimeMs = 1_000L
        )

        assertTrue(accepted.accepted)
        assertEquals(CameraModePresetActivationOutcome.ACCEPTED, accepted.outcome)
        assertEquals(1_180L, accepted.nextAllowedUptimeMs)
        assertFalse(throttled.accepted)
        assertEquals(CameraModePresetActivationOutcome.THROTTLED, throttled.outcome)
        assertEquals(1_180L, throttled.nextAllowedUptimeMs)
    }
}
