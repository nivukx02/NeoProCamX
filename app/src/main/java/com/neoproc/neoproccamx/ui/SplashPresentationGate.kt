package com.neoproc.neoproccamx.ui

/**
 * One-shot gate for a splash that is waiting for a frame which was actually presented.
 *
 * The sequence baseline rejects a replayed event from before the splash was armed. Session and
 * surface identities reject a late swap from a retired Camera2 graph or preview surface. A rejected
 * event does not consume the arm, so the next valid presentation can still dismiss the splash.
 */
internal class SplashPresentationGate {
    private var armed = false
    private var baselineSequence = 0L
    private var lastConsumedSequence = 0L

    fun arm(afterSequence: Long) {
        baselineSequence = afterSequence.coerceAtLeast(0L)
        armed = true
    }

    fun invalidate() {
        armed = false
    }

    fun accept(
        eventSequence: Long,
        eventSensorTimestampNs: Long,
        eventSessionGeneration: Long,
        eventSurfaceEpoch: Long,
        currentSessionGeneration: Long,
        currentSurfaceEpoch: Long
    ): Boolean {
        if (!armed) return false
        if (!isCurrentPresentation(
                eventSequence = eventSequence,
                eventSensorTimestampNs = eventSensorTimestampNs,
                eventSessionGeneration = eventSessionGeneration,
                eventSurfaceEpoch = eventSurfaceEpoch,
                currentSessionGeneration = currentSessionGeneration,
                currentSurfaceEpoch = currentSurfaceEpoch
            )
        ) return false
        if (eventSequence <= baselineSequence || eventSequence <= lastConsumedSequence) return false

        lastConsumedSequence = eventSequence
        armed = false
        return true
    }

    companion object {
        fun isCurrentPresentation(
            eventSequence: Long,
            eventSensorTimestampNs: Long,
            eventSessionGeneration: Long,
            eventSurfaceEpoch: Long,
            currentSessionGeneration: Long,
            currentSurfaceEpoch: Long
        ): Boolean =
            eventSequence > 0L &&
                eventSensorTimestampNs > 0L &&
                eventSessionGeneration > 0L &&
                eventSurfaceEpoch > 0L &&
                eventSessionGeneration == currentSessionGeneration &&
                eventSurfaceEpoch == currentSurfaceEpoch
    }
}
