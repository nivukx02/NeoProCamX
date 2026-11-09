package com.neoproc.neoproccamx.rendering

/** Pure retry rules for GL preview binding recovery. */
internal object GlPreviewBindingRetryPolicy {
    fun canonicalReason(reason: String): String =
        when {
            reason.contains("pq-recording-blocked") -> "pq-recording-blocked"
            reason.contains("recording-blocked") -> "recording-blocked"
            reason.contains("pq-camera-not-ready") -> "pq-camera-not-ready"
            reason.contains("camera-not-ready") -> "camera-not-ready"
            else -> reason.take(80)
        }

    fun shouldSchedule(shouldComposite: Boolean, attempts: Int, maxRetries: Int): Boolean =
        shouldComposite && attempts < maxRetries

    fun shouldContinue(
        shouldComposite: Boolean,
        sdrCompositorActive: Boolean,
        pqCompositorActive: Boolean,
        transitionInProgress: Boolean = false,
        attempts: Int,
        maxRetries: Int
    ): Boolean =
        shouldComposite &&
            !sdrCompositorActive &&
            !pqCompositorActive &&
            !transitionInProgress &&
            attempts < maxRetries
}
