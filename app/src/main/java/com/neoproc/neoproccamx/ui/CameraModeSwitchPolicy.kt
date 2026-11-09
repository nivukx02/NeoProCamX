package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal enum class CameraFeatureSet {
    PHOTO,
    VIDEO
}

internal data class CameraModeTransitionPlan(
    val restoreFeatureSet: CameraFeatureSet,
    val shouldClearPreviewStabilization: Boolean,
    val shouldApplyVideoStabilization: Boolean,
    val shouldRefreshPreviewPipeline: Boolean,
    val shouldUpdateRuntimeStats: Boolean
)

internal enum class CameraModePresetActivationOutcome {
    ACCEPTED,
    THROTTLED
}

internal data class CameraModePresetActivationDecision(
    val accepted: Boolean,
    val outcome: CameraModePresetActivationOutcome,
    val nextAllowedUptimeMs: Long
)

internal object CameraModeSwitchPolicy {
    private const val MODE_PRESET_MIN_SWITCH_INTERVAL_MS = 180L

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun shouldIgnoreOpenRequest(state: CameraState): Boolean =
        state == CameraState.Streaming ||
            state == CameraState.Opening ||
            state == CameraState.Open ||
            state == CameraState.Recording

    fun shouldRestartForFacingSwitch(state: CameraState): Boolean {
        return state == CameraState.Open ||
            state == CameraState.Streaming ||
            state == CameraState.Opening
    }

    fun canSwitchFacing(isRecordingOrStopping: Boolean): Boolean = !isRecordingOrStopping

    fun resolvePresetActivation(
        current: CameraModePreset,
        requested: CameraModePreset,
        nowUptimeMs: Long,
        lastAcceptedUptimeMs: Long,
        minimumIntervalMs: Long = MODE_PRESET_MIN_SWITCH_INTERVAL_MS
    ): CameraModePresetActivationDecision {
        val safeMinimumIntervalMs = minimumIntervalMs.coerceAtLeast(0L)
        val nextAllowedUptimeMs = if (lastAcceptedUptimeMs <= 0L) {
            nowUptimeMs
        } else {
            lastAcceptedUptimeMs + safeMinimumIntervalMs
        }
        val throttled = lastAcceptedUptimeMs > 0L && nowUptimeMs < nextAllowedUptimeMs
        if (throttled) {
            return CameraModePresetActivationDecision(
                accepted = false,
                outcome = CameraModePresetActivationOutcome.THROTTLED,
                nextAllowedUptimeMs = nextAllowedUptimeMs
            )
        }
        return CameraModePresetActivationDecision(
            accepted = true,
            outcome = CameraModePresetActivationOutcome.ACCEPTED,
            nextAllowedUptimeMs = nowUptimeMs + safeMinimumIntervalMs
        )
    }

    fun buildModeTransition(
        from: AppMode,
        to: AppMode
    ): CameraModeTransitionPlan {
        val strictHighEnd = strictHighEndEnabled()
        val enteringVideo = to == AppMode.VIDEO || to == AppMode.PRO
        return CameraModeTransitionPlan(
            restoreFeatureSet = if (enteringVideo) CameraFeatureSet.VIDEO else CameraFeatureSet.PHOTO,
            shouldClearPreviewStabilization = !enteringVideo,
            shouldApplyVideoStabilization = enteringVideo,
            shouldRefreshPreviewPipeline = from != to || strictHighEnd,
            shouldUpdateRuntimeStats = true
        )
    }
}
