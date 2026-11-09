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
    private val t0 = SystemClock.elapsedRealtime()

    @Volatile private var firstFrameLogged = false

    fun mark(event: String) {
        if (!BuildConfig.DEBUG) return // no-op in release; boot profiler is a debug-only aid
        Log.i("NeoBoot", "$event +${SystemClock.elapsedRealtime() - t0}ms")
    }

    /** First camera frame can fire repeatedly; log only the first one. */
    fun markFirstFrameOnce() {
        if (firstFrameLogged) return
        firstFrameLogged = true
        mark("camera.firstFrame")
    }
}
