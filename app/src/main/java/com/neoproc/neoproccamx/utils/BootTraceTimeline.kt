package com.neoproc.neoproccamx.utils

/** Monotonic, Android-independent ownership model for [BootTrace]. */
internal class BootTraceTimeline(
    private val processStartElapsedMs: Long
) {
    enum class Scope {
        PROCESS,
        ACTIVITY
    }

    data class Timing(
        val scope: Scope,
        val elapsedMs: Long,
        val processElapsedMs: Long,
        val activityLaunchGeneration: Long?
    )

    private val lock = Any()
    private var activityStartElapsedMs: Long? = null
    private var activityLaunchGeneration = 0L
    private var firstFrameClaimedGeneration = Long.MIN_VALUE

    fun beginActivityLaunch(nowElapsedMs: Long): Long = synchronized(lock) {
        activityLaunchGeneration = nextGeneration(activityLaunchGeneration)
        activityStartElapsedMs = nowElapsedMs
        activityLaunchGeneration
    }

    fun timingFor(event: String, nowElapsedMs: Long): Timing = synchronized(lock) {
        val processElapsed = nonNegativeDelta(nowElapsedMs, processStartElapsedMs)
        val activityStart = activityStartElapsedMs
        val activityOwned = activityStart != null && !event.startsWith(PROCESS_EVENT_PREFIX)
        if (activityOwned) {
            Timing(
                scope = Scope.ACTIVITY,
                elapsedMs = nonNegativeDelta(nowElapsedMs, requireNotNull(activityStart)),
                processElapsedMs = processElapsed,
                activityLaunchGeneration = activityLaunchGeneration
            )
        } else {
            Timing(
                scope = Scope.PROCESS,
                elapsedMs = processElapsed,
                processElapsedMs = processElapsed,
                activityLaunchGeneration = null
            )
        }
    }

    fun claimFirstFrame(): Boolean = synchronized(lock) {
        val generation = activityLaunchGeneration
        if (firstFrameClaimedGeneration == generation) return@synchronized false
        firstFrameClaimedGeneration = generation
        true
    }

    private fun nonNegativeDelta(nowElapsedMs: Long, baselineElapsedMs: Long): Long =
        (nowElapsedMs - baselineElapsedMs).coerceAtLeast(0L)

    private fun nextGeneration(current: Long): Long =
        if (current == Long.MAX_VALUE) 1L else current + 1L

    private companion object {
        const val PROCESS_EVENT_PREFIX = "app."
    }
}
