package com.neoproc.neoproccamx.ai

internal data class FocusResponsivenessProfile(
    val updateIntervalMs: Long,
    val moveThreshold: Float,
    val reacquireThreshold: Float,
    val retriggerIntervalMs: Long,
    val lossTimeoutMs: Long
)

internal object LogFocusResponsivenessPolicy {
    fun resolve(
        logEnabled: Boolean,
        hdrEnabled: Boolean,
        strictHighEnd: Boolean,
        recordingActive: Boolean = false,
        targetFps: Int = 60
    ): FocusResponsivenessProfile {
        val safeFps = targetFps.coerceIn(1, 240)
        val frameIntervalMs = (1_000L + safeFps - 1L) / safeFps
        var profile = FocusResponsivenessProfile(
            updateIntervalMs = frameIntervalMs * if (strictHighEnd) 1L else 2L,
            moveThreshold = if (strictHighEnd) 0.008f else 0.012f,
            reacquireThreshold = if (strictHighEnd) 0.026f else 0.036f,
            retriggerIntervalMs = if (strictHighEnd) 360L else 480L,
            lossTimeoutMs = maxOf(350L, frameIntervalMs * 8L)
        )

        if (hdrEnabled) {
            profile = profile.copy(
                updateIntervalMs = frameIntervalMs,
                moveThreshold = if (strictHighEnd) 0.0075f else 0.010f,
                reacquireThreshold = if (strictHighEnd) 0.024f else 0.030f,
                retriggerIntervalMs = if (strictHighEnd) 320L else 400L,
                lossTimeoutMs = maxOf(320L, frameIntervalMs * 8L)
            )
        }

        if (logEnabled) {
            profile = profile.copy(
                updateIntervalMs = frameIntervalMs,
                moveThreshold = if (strictHighEnd) 0.0075f else 0.010f,
                reacquireThreshold = if (strictHighEnd) 0.024f else 0.030f,
                retriggerIntervalMs = if (strictHighEnd) 320L else 400L,
                lossTimeoutMs = maxOf(320L, frameIntervalMs * 8L)
            )
        }

        if (recordingActive) {
            // Camera2 metering changes rebuild the repeating request. During a take, limit updates
            // to at most every second camera frame and make single-shot AF a deliberate re-acquire,
            // preventing request-thread churn and visible focus pumping.
            profile = profile.copy(
                updateIntervalMs = maxOf(profile.updateIntervalMs, frameIntervalMs * 2L),
                moveThreshold = maxOf(profile.moveThreshold, 0.012f),
                reacquireThreshold = maxOf(profile.reacquireThreshold, 0.040f),
                retriggerIntervalMs = maxOf(1_200L, frameIntervalMs * 24L),
                lossTimeoutMs = maxOf(700L, frameIntervalMs * 12L)
            )
        }

        return profile
    }
}
