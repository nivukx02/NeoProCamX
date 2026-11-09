package com.neoproc.neoproccamx.ai

internal data class FocusResponsivenessProfile(
    val updateIntervalMs: Long,
    val moveThreshold: Float,
    val reacquireThreshold: Float,
    val retriggerIntervalMs: Long
)

internal object LogFocusResponsivenessPolicy {
    fun resolve(
        logEnabled: Boolean,
        hdrEnabled: Boolean,
        strictHighEnd: Boolean
    ): FocusResponsivenessProfile {
        var profile = FocusResponsivenessProfile(
            updateIntervalMs = if (strictHighEnd) 8L else 12L,
            moveThreshold = if (strictHighEnd) 0.005f else 0.008f,
            reacquireThreshold = if (strictHighEnd) 0.010f else 0.015f,
            retriggerIntervalMs = if (strictHighEnd) 80L else 120L
        )

        if (hdrEnabled) {
            profile = profile.copy(
                updateIntervalMs = minOf(profile.updateIntervalMs, if (strictHighEnd) 6L else 10L),
                moveThreshold = profile.moveThreshold * 0.8f,
                reacquireThreshold = profile.reacquireThreshold * 0.75f,
                retriggerIntervalMs = minOf(profile.retriggerIntervalMs, if (strictHighEnd) 60L else 90L)
            )
        }

        if (logEnabled) {
            profile = profile.copy(
                updateIntervalMs = minOf(profile.updateIntervalMs, if (strictHighEnd) 4L else 8L),
                moveThreshold = profile.moveThreshold * 0.70f,
                reacquireThreshold = profile.reacquireThreshold * 0.65f,
                retriggerIntervalMs = minOf(profile.retriggerIntervalMs, if (strictHighEnd) 45L else 75L)
            )
        }

        return profile
    }
}
