package com.neoproc.neoproccamx.ui

/**
 * Owns frame-liveness evidence for exactly one ready Camera2 session epoch.
 *
 * A sensor timestamp from the previous session must never be aged against a newly configured
 * session. The first observation after a generation/lifecycle/refresh boundary establishes a new
 * baseline; only a later timestamp or import-count advance proves delivery for that epoch.
 */
internal class FrameDeliveryLivenessTracker {
    data class Input(
        val nowElapsedMs: Long,
        val sessionGeneration: Long,
        val cameraStreaming: Boolean,
        val sessionRefreshPending: Boolean,
        val captureResultTimestampNs: Long,
        val sessionImportedFrames: Long
    )

    data class Snapshot(
        val frameDeliveryExpected: Boolean,
        val lastFrameDeliveryElapsedMs: Long?
    )

    private var initialized = false
    private var observedSessionGeneration = Long.MIN_VALUE
    private var previousReady = false
    private var previousCaptureResultTimestampNs = 0L
    private var previousSessionImportedFrames = -1L
    private var currentEpochLastDeliveryElapsedMs: Long? = null

    @Synchronized
    fun update(input: Input): Snapshot {
        val ready = input.cameraStreaming && !input.sessionRefreshPending
        val generationChanged =
            !initialized || input.sessionGeneration != observedSessionGeneration
        val readinessBoundary = generationChanged || !ready || !previousReady

        val captureResultAdvanced =
            initialized &&
                input.captureResultTimestampNs != 0L &&
                input.captureResultTimestampNs != previousCaptureResultTimestampNs
        val importedFrameAdvanced =
            initialized &&
                previousSessionImportedFrames >= 0L &&
                input.sessionImportedFrames > previousSessionImportedFrames

        if (readinessBoundary) {
            currentEpochLastDeliveryElapsedMs = null
        } else if (captureResultAdvanced || importedFrameAdvanced) {
            currentEpochLastDeliveryElapsedMs = input.nowElapsedMs
        }

        initialized = true
        observedSessionGeneration = input.sessionGeneration
        previousReady = ready
        previousCaptureResultTimestampNs = input.captureResultTimestampNs
        previousSessionImportedFrames = input.sessionImportedFrames

        return Snapshot(
            frameDeliveryExpected = ready,
            lastFrameDeliveryElapsedMs = currentEpochLastDeliveryElapsedMs
        )
    }
}
