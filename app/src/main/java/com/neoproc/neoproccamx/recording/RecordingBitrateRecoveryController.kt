package com.neoproc.neoproccamx.recording

/**
 * Repairs an unhonoured runtime CBR request without introducing a second quality controller.
 *
 * The configuration-only hint policy cannot see an encoder emitting less than its accepted target.
 * Observe complete, non-overlapping output windows and reassert the SAME admitted bitrate at most
 * three times. Never raise the capability ceiling, alter QP/GOP, insert padding, or relabel measured
 * bytes. An encoder that still undershoots remains observably unhonoured; this is not a quality gate.
 */
internal class RecordingBitrateRecoveryController {
    data class OutputWindow(
        val sampleCount: Int,
        val durationUs: Long,
        val lastPresentationTimeUs: Long,
        val bitrateBps: Int
    ) {
        companion object {
            val EMPTY = OutputWindow(0, 0L, Long.MIN_VALUE, 0)
        }
    }

    enum class Action { NONE, REASSERT_SAME_TARGET, REPORT_UNHONOURED_TARGET }

    data class Decision(
        val action: Action,
        val targetBitrateBps: Int,
        val measuredBitrateBps: Int,
        val attempt: Int
    )

    private var targetBitrateBps = 0
    private var lastWindowEndUs = Long.MIN_VALUE
    private var consecutiveDeficitWindows = 0
    private var attempts = 0
    private var unhonouredReported = false

    @Synchronized
    fun observe(targetBps: Int, window: OutputWindow): Decision {
        require(targetBps > 0) { "An admitted positive bitrate is required" }
        if (targetBitrateBps != targetBps) {
            reset()
            targetBitrateBps = targetBps
        }
        fun decision(action: Action = Action.NONE) =
            Decision(action, targetBps, window.bitrateBps, attempts)

        // Empty/config-only output and paused/stale windows cannot provoke a codec mutation.
        if (window.sampleCount < 2 || window.durationUs < WINDOW_US ||
            window.lastPresentationTimeUs < 0L || window.bitrateBps <= 0
        ) return decision()
        if (lastWindowEndUs != Long.MIN_VALUE &&
            (window.lastPresentationTimeUs <= lastWindowEndUs ||
                window.lastPresentationTimeUs - lastWindowEndUs < WINDOW_US)
        ) return decision()
        lastWindowEndUs = window.lastPresentationTimeUs

        // The recording certificate and independent file audit keep their existing thresholds.
        // This 99% trigger only requests recovery; it cannot award a recording PASS.
        val recoveryFloor = (targetBps.toLong() * 99L + 99L) / 100L
        if (window.bitrateBps.toLong() >= recoveryFloor) {
            consecutiveDeficitWindows = 0
            return decision()
        }
        consecutiveDeficitWindows = (consecutiveDeficitWindows + 1).coerceAtMost(2)
        if (consecutiveDeficitWindows < 2) return decision()
        if (attempts < MAX_ATTEMPTS) {
            attempts++
            return decision(Action.REASSERT_SAME_TARGET)
        }
        if (!unhonouredReported) {
            unhonouredReported = true
            return decision(Action.REPORT_UNHONOURED_TARGET)
        }
        return decision()
    }

    @Synchronized
    fun reset() {
        targetBitrateBps = 0
        lastWindowEndUs = Long.MIN_VALUE
        consecutiveDeficitWindows = 0
        attempts = 0
        unhonouredReported = false
    }

    companion object {
        const val WINDOW_US = 2_000_000L
        const val MAX_ATTEMPTS = 3
    }
}
