package com.neoproc.neoproccamx.ui

/** Time policy for a cold splash: honor branding minimums without ever defeating the safety bound. */
internal object SplashVisibilityPolicy {
    fun shouldKeepColdSplash(
        previewPresented: Boolean,
        nowElapsedMs: Long,
        minimumEndElapsedMs: Long,
        maximumEndElapsedMs: Long
    ): Boolean {
        if (nowElapsedMs >= maximumEndElapsedMs) return false
        return !previewPresented || nowElapsedMs < minimumEndElapsedMs
    }
}
