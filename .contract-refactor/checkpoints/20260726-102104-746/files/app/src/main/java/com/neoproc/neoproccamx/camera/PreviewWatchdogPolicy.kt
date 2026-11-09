package com.neoproc.neoproccamx.camera

internal object PreviewWatchdogPolicy {
    fun shouldRecover(
        hasReceivedYuvFrame: Boolean,
        hasReceivedCaptureResult: Boolean,
        lastYuvFrameArrivalMs: Long,
        lastCaptureResultArrivalMs: Long,
        nowMs: Long,
        thresholdMs: Long
    ): Boolean {
        val lastActivityMs = resolveLastActivityMs(
            hasReceivedYuvFrame = hasReceivedYuvFrame,
            hasReceivedCaptureResult = hasReceivedCaptureResult,
            lastYuvFrameArrivalMs = lastYuvFrameArrivalMs,
            lastCaptureResultArrivalMs = lastCaptureResultArrivalMs
        ) ?: return false
        return nowMs - lastActivityMs > thresholdMs
    }

    fun hasRecentActivity(
        hasReceivedYuvFrame: Boolean,
        hasReceivedCaptureResult: Boolean,
        lastYuvFrameArrivalMs: Long,
        lastCaptureResultArrivalMs: Long,
        nowMs: Long,
        recentWindowMs: Long
    ): Boolean {
        val lastActivityMs = resolveLastActivityMs(
            hasReceivedYuvFrame = hasReceivedYuvFrame,
            hasReceivedCaptureResult = hasReceivedCaptureResult,
            lastYuvFrameArrivalMs = lastYuvFrameArrivalMs,
            lastCaptureResultArrivalMs = lastCaptureResultArrivalMs
        ) ?: return false
        return nowMs - lastActivityMs <= recentWindowMs
    }

    private fun resolveLastActivityMs(
        hasReceivedYuvFrame: Boolean,
        hasReceivedCaptureResult: Boolean,
        lastYuvFrameArrivalMs: Long,
        lastCaptureResultArrivalMs: Long
    ): Long? {
        val activityTimestamps = buildList {
            if (hasReceivedYuvFrame && lastYuvFrameArrivalMs > 0L) {
                add(lastYuvFrameArrivalMs)
            }
            if (hasReceivedCaptureResult && lastCaptureResultArrivalMs > 0L) {
                add(lastCaptureResultArrivalMs)
            }
        }
        return activityTimestamps.maxOrNull()
    }
}
