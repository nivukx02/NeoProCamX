package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal enum class PreviewPerformanceProfile {
    DEFAULT,
    STABILIZED,
    AGGRESSIVE
}

internal data class PreviewPerformanceState(
    val profile: PreviewPerformanceProfile = PreviewPerformanceProfile.DEFAULT,
    val lowFpsSamples: Int = 0,
    val healthyFpsSamples: Int = 0,
    val lastProfileChangeElapsedMs: Long = 0L,
    val smoothedPreviewFps: Float = 0f
)

internal data class PreviewPerformanceLimits(
    val previewMaxLongEdge: Int?,
    val previewMaxArea: Int?,
    val analysisMaxLongEdge: Int?,
    val highLoadPreviewMode: Boolean
)

internal data class PreviewPerformanceDecision(
    val state: PreviewPerformanceState,
    val limits: PreviewPerformanceLimits,
    val shouldRefreshPreviewPipeline: Boolean
)

internal object PreviewPerformanceStabilityPolicy {
    private const val MIN_REALTIME_PREVIEW_FPS = 24
    private const val LOW_FPS_THRESHOLD_RATIO = 0.85f // UPGRADED: More tolerant to avoid jarring drops
    private const val SEVERE_LOW_FPS_THRESHOLD_RATIO = 0.72f // UPGRADED: More tolerant
    private const val HEALTHY_FPS_THRESHOLD_RATIO = 0.95f
    private const val FPS_EMA_ALPHA = 0.35f // Smoother EMA
    private const val MIN_PROFILE_CHANGE_INTERVAL_MS = 2_000L // Increased to prevent rapid cycling
    private const val VIDEO_PROFILE_CHANGE_INTERVAL_MS = 3_500L
    private const val LOW_FPS_SAMPLES_FOR_STABILIZED = 3
    private const val RECORDING_LOW_FPS_SAMPLES_FOR_STABILIZED = 2
    private const val LOW_FPS_SAMPLES_FOR_AGGRESSIVE = 2
    private const val HEALTHY_SAMPLES_TO_RECOVER = 3
    private const val AGGRESSIVE_HEALTHY_SAMPLES_TO_RECOVER = 4
    private const val HIGH_RES_OUTPUT_LONG_EDGE = 3840
    private const val ULTRA_HIGH_RES_OUTPUT_LONG_EDGE = 5120

    private const val STABILIZED_VIDEO_PREVIEW_MAX_LONG_EDGE = 1920 // UPGRADED: Higher res fallback
    private const val STABILIZED_VIDEO_PREVIEW_MAX_AREA = 1920 * 1080
    private const val STABILIZED_PHOTO_PREVIEW_MAX_LONG_EDGE = 2048
    private const val STABILIZED_PHOTO_PREVIEW_MAX_AREA = 2048 * 1536
    private const val STABILIZED_ANALYSIS_MAX_LONG_EDGE = 800

    private const val DEFAULT_RECORDING_HIGH_ANALYSIS_MAX_LONG_EDGE = 1280
    private const val DEFAULT_RECORDING_ULTRA_ANALYSIS_MAX_LONG_EDGE = 1280

    private const val AGGRESSIVE_VIDEO_PREVIEW_MAX_LONG_EDGE = 1600
    private const val AGGRESSIVE_VIDEO_PREVIEW_MAX_AREA = 1600 * 900
    private const val AGGRESSIVE_PHOTO_PREVIEW_MAX_LONG_EDGE = 1600
    private const val AGGRESSIVE_PHOTO_PREVIEW_MAX_AREA = 1600 * 1200
    private const val AGGRESSIVE_ANALYSIS_MAX_LONG_EDGE = 640
    private const val AGGRESSIVE_ANALYSIS_MAX_LONG_EDGE_ULTRA = 576

    fun shouldFreezeIdleRetune(
        videoMode: Boolean,
        strictPacketCapturePrearmed: Boolean,
        offscreenEncoderPrearmed: Boolean
    ): Boolean = videoMode && (strictPacketCapturePrearmed || offscreenEncoderPrearmed)

    fun resolveLimits(
        profile: PreviewPerformanceProfile,
        videoMode: Boolean,
        recordingActive: Boolean,
        requestedOutputLongEdge: Int
    ): PreviewPerformanceLimits {
        val requestedHighLoad = requestedOutputLongEdge >= HIGH_RES_OUTPUT_LONG_EDGE
        val ultraHighLoad = requestedOutputLongEdge >= ULTRA_HIGH_RES_OUTPUT_LONG_EDGE
        return when (profile) {
            PreviewPerformanceProfile.DEFAULT -> PreviewPerformanceLimits(
                previewMaxLongEdge = null,
                previewMaxArea = null,
                analysisMaxLongEdge = when {
                    recordingActive && ultraHighLoad -> DEFAULT_RECORDING_ULTRA_ANALYSIS_MAX_LONG_EDGE
                    recordingActive && requestedHighLoad -> DEFAULT_RECORDING_HIGH_ANALYSIS_MAX_LONG_EDGE
                    recordingActive -> DEFAULT_RECORDING_HIGH_ANALYSIS_MAX_LONG_EDGE
                    else -> null
                },
                highLoadPreviewMode = false
            )

            PreviewPerformanceProfile.STABILIZED -> PreviewPerformanceLimits(
                previewMaxLongEdge = if (videoMode) {
                    STABILIZED_VIDEO_PREVIEW_MAX_LONG_EDGE
                } else {
                    STABILIZED_PHOTO_PREVIEW_MAX_LONG_EDGE
                },
                previewMaxArea = if (videoMode) {
                    STABILIZED_VIDEO_PREVIEW_MAX_AREA
                } else {
                    STABILIZED_PHOTO_PREVIEW_MAX_AREA
                },
                analysisMaxLongEdge = if (recordingActive || ultraHighLoad) {
                    STABILIZED_ANALYSIS_MAX_LONG_EDGE.coerceAtMost(640)
                } else {
                    STABILIZED_ANALYSIS_MAX_LONG_EDGE
                },
                highLoadPreviewMode = true
            )

            PreviewPerformanceProfile.AGGRESSIVE -> PreviewPerformanceLimits(
                previewMaxLongEdge = if (videoMode) {
                    AGGRESSIVE_VIDEO_PREVIEW_MAX_LONG_EDGE
                } else {
                    AGGRESSIVE_PHOTO_PREVIEW_MAX_LONG_EDGE
                },
                previewMaxArea = if (videoMode) {
                    AGGRESSIVE_VIDEO_PREVIEW_MAX_AREA
                } else {
                    AGGRESSIVE_PHOTO_PREVIEW_MAX_AREA
                },
                analysisMaxLongEdge = if (recordingActive || ultraHighLoad) {
                    AGGRESSIVE_ANALYSIS_MAX_LONG_EDGE_ULTRA
                } else {
                    AGGRESSIVE_ANALYSIS_MAX_LONG_EDGE
                },
                highLoadPreviewMode = true
            )
        }
    }

    fun evaluate(
        state: PreviewPerformanceState,
        measuredPreviewFps: Float,
        requestedPreviewFps: Int,
        videoMode: Boolean,
        recordingActive: Boolean,
        requestedOutputLongEdge: Int,
        thermalGuardActive: Boolean,
        nowElapsedMs: Long
    ): PreviewPerformanceDecision {
        val currentLimits = resolveLimits(
            profile = state.profile,
            videoMode = videoMode,
            recordingActive = recordingActive,
            requestedOutputLongEdge = requestedOutputLongEdge
        )
        if (measuredPreviewFps <= 0f) {
            return PreviewPerformanceDecision(
                state = state,
                limits = currentLimits,
                shouldRefreshPreviewPipeline = false
            )
        }

        val targetFpsFloor = HighEndQualityPolicy.previewFpsFloor(
            requestedFps = requestedPreviewFps,
            recording = recordingActive
        ).coerceAtLeast(MIN_REALTIME_PREVIEW_FPS)
        val targetFps = requestedPreviewFps.coerceAtLeast(targetFpsFloor).toFloat()
        val smoothedFps = if (state.smoothedPreviewFps <= 0f) {
            measuredPreviewFps
        } else {
            state.smoothedPreviewFps + (measuredPreviewFps - state.smoothedPreviewFps) * FPS_EMA_ALPHA
        }
        val effectiveDegradeFps = minOf(measuredPreviewFps, smoothedFps)
        val lowFps = effectiveDegradeFps < (targetFps * LOW_FPS_THRESHOLD_RATIO)
        val severeLowFps = effectiveDegradeFps < (targetFps * SEVERE_LOW_FPS_THRESHOLD_RATIO)
        val healthyFps = smoothedFps >= (targetFps * HEALTHY_FPS_THRESHOLD_RATIO)

        var nextLowSamples = if (lowFps) state.lowFpsSamples + 1 else 0
        var nextHealthySamples = if (healthyFps) state.healthyFpsSamples + 1 else 0
        var nextProfile = state.profile
        var profileChanged = false
        val lowSamplesForStabilized = if (recordingActive) {
            RECORDING_LOW_FPS_SAMPLES_FOR_STABILIZED
        } else {
            LOW_FPS_SAMPLES_FOR_STABILIZED
        }
        val lowSamplesForAggressive = if (recordingActive || videoMode) {
            LOW_FPS_SAMPLES_FOR_AGGRESSIVE
        } else {
            LOW_FPS_SAMPLES_FOR_AGGRESSIVE - 1
        }
        val healthySamplesForDefaultRecovery = if (recordingActive || videoMode) {
            HEALTHY_SAMPLES_TO_RECOVER
        } else {
            (HEALTHY_SAMPLES_TO_RECOVER - 1).coerceAtLeast(1)
        }
        val healthySamplesForStabilizedRecovery = if (recordingActive || videoMode) {
            AGGRESSIVE_HEALTHY_SAMPLES_TO_RECOVER
        } else {
            HEALTHY_SAMPLES_TO_RECOVER
        }
        val profileChangeIntervalMs = if (videoMode || recordingActive) {
            VIDEO_PROFILE_CHANGE_INTERVAL_MS
        } else {
            MIN_PROFILE_CHANGE_INTERVAL_MS
        }

        val profileChangeIntervalSatisfied = (
            state.lastProfileChangeElapsedMs == 0L ||
                nowElapsedMs - state.lastProfileChangeElapsedMs >= profileChangeIntervalMs
            )
        val canEscalateProfile = thermalGuardActive || profileChangeIntervalSatisfied
        val canRecoverProfile = !thermalGuardActive && profileChangeIntervalSatisfied

        when (state.profile) {
            PreviewPerformanceProfile.DEFAULT -> {
                if (canEscalateProfile && nextLowSamples >= lowSamplesForStabilized) {
                    nextProfile = PreviewPerformanceProfile.STABILIZED
                    profileChanged = true
                }
            }

            PreviewPerformanceProfile.STABILIZED -> {
                when {
                    canEscalateProfile &&
                        severeLowFps &&
                        nextLowSamples >= lowSamplesForAggressive -> {
                        nextProfile = PreviewPerformanceProfile.AGGRESSIVE
                        profileChanged = true
                    }

                    canRecoverProfile &&
                        nextHealthySamples >= healthySamplesForDefaultRecovery -> {
                        nextProfile = PreviewPerformanceProfile.DEFAULT
                        profileChanged = true
                    }
                }
            }

            PreviewPerformanceProfile.AGGRESSIVE -> {
                if (canRecoverProfile && nextHealthySamples >= healthySamplesForStabilizedRecovery) {
                    nextProfile = PreviewPerformanceProfile.STABILIZED
                    profileChanged = true
                }
            }
        }

        val nextState = if (profileChanged) {
            PreviewPerformanceState(
                profile = nextProfile,
                lowFpsSamples = 0,
                healthyFpsSamples = 0,
                lastProfileChangeElapsedMs = nowElapsedMs,
                smoothedPreviewFps = smoothedFps
            )
        } else {
            state.copy(
                lowFpsSamples = nextLowSamples,
                healthyFpsSamples = nextHealthySamples,
                smoothedPreviewFps = smoothedFps
            )
        }

        return PreviewPerformanceDecision(
            state = nextState,
            limits = resolveLimits(
                profile = nextState.profile,
                videoMode = videoMode,
                recordingActive = recordingActive,
                requestedOutputLongEdge = requestedOutputLongEdge
            ),
            shouldRefreshPreviewPipeline = profileChanged
        )
    }
}
