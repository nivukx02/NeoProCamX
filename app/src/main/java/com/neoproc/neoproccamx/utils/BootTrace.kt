package com.neoproc.neoproccamx.utils

import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.BuildConfig

/**
 * Lightweight cold-start profiler. Logs each boot milestone with its elapsed time since the first
 * reference (≈ Application.onCreate / process start). Tag "NeoBoot" so it can be captured cheaply with
 * `adb logcat -d -s NeoBoot:I` without dumping the whole buffer. Negligible overhead (one Log per
 * milestone); intended to find where cold-start time goes, then to be removed.
 */
object BootTrace {
    private val timeline = BootTraceTimeline(SystemClock.elapsedRealtime())

    /** Starts a new Activity-relative launch timeline without losing the process cold-start baseline. */
    fun beginActivityLaunch() {
        if (!BuildConfig.DEBUG) return
        timeline.beginActivityLaunch(SystemClock.elapsedRealtime())
    }

    fun mark(event: String) {
        if (!BuildConfig.DEBUG) return // no-op in release; boot profiler is a debug-only aid
        val timing = timeline.timingFor(event, SystemClock.elapsedRealtime())
        val launchSuffix = timing.activityLaunchGeneration?.let { " launch=$it" }.orEmpty()
        Log.i(
            "NeoBoot",
            "$event +${timing.elapsedMs}ms scope=${timing.scope.name} " +
                "process+${timing.processElapsedMs}ms$launchSuffix"
        )
    }

    /** First camera frame can fire repeatedly; log once for each Activity launch generation. */
    fun markFirstFrameOnce() {
        if (!BuildConfig.DEBUG || !timeline.claimFirstFrame()) return
        mark("camera.firstFrame")
    }
}
