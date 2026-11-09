package com.neoproc.neoproccamx.niv99

/**
 * Caller-owned snapshot of the currently observed camera timeline. Counts in this object are
 * intentionally scoped to one camera/session generation so a recording certificate never has to
 * infer current-session behavior from process-lifetime totals.
 */
class Niv99TimelineTelemetrySnapshot {
    var cameraId: String = ""
    var sessionGeneration: Long = -1L
    var captureResultsSeen: Long = 0L
    var captureResultsPublished: Long = 0L
    var captureResultsRejected: Long = 0L
    var imageMatches: Long = 0L
    var imageMisses: Long = 0L
    var tuningAccepted: Long = 0L
    var tuningRejected: Long = 0L
    var pixelEligibleTunings: Long = 0L
    var pixelAppliedFrames: Long = 0L
    var previewPixelAppliedFrames: Long = 0L
    var recordingPixelAppliedFrames: Long = 0L
    var pixelRenderRejectedFrames: Long = 0L
    var previewPixelRenderRejectedFrames: Long = 0L
    var recordingPixelRenderRejectedFrames: Long = 0L
    var latestPublishedFrameNumber: Long = -1L
    var latestPublishedSensorTimestampNs: Long = -1L
    var resetCount: Long = 0L
}

/**
 * Allocation-free mutable telemetry for a single live timeline. Every mutation is identity gated:
 * delayed callbacks from a retired camera or generation cannot contaminate the active counters.
 */
class Niv99TimelineTelemetry {
    private var cameraId: String = ""
    private var sessionGeneration: Long = -1L
    private var captureResultsSeen: Long = 0L
    private var captureResultsPublished: Long = 0L
    private var captureResultsRejected: Long = 0L
    private var imageMatches: Long = 0L
    private var imageMisses: Long = 0L
    private var tuningAccepted: Long = 0L
    private var tuningRejected: Long = 0L
    private var pixelEligibleTunings: Long = 0L
    private var pixelAppliedFrames: Long = 0L
    private var previewPixelAppliedFrames: Long = 0L
    private var recordingPixelAppliedFrames: Long = 0L
    private var pixelRenderRejectedFrames: Long = 0L
    private var previewPixelRenderRejectedFrames: Long = 0L
    private var recordingPixelRenderRejectedFrames: Long = 0L
    private var latestPublishedFrameNumber: Long = -1L
    private var latestPublishedSensorTimestampNs: Long = -1L
    private var resetCount: Long = 0L

    /**
     * Starts or retains a valid timeline. Session generations are monotonic process-local ownership
     * tokens; a delayed callback from a retired generation must never rewind the active counters.
     * Returning true means subsequent scoped updates may run.
     */
    @Synchronized
    fun beginTimeline(cameraId: String, sessionGeneration: Long): Boolean {
        if (cameraId.isBlank() || sessionGeneration < 0L) return false
        if (hasActiveTimeline() && sessionGeneration < this.sessionGeneration) return false
        if (this.cameraId == cameraId && this.sessionGeneration == sessionGeneration) return true
        if (hasActiveTimeline()) resetCount = saturatingIncrement(resetCount)
        this.cameraId = cameraId
        this.sessionGeneration = sessionGeneration
        clearTimelineCounts()
        return true
    }

    @Synchronized
    fun recordCaptureSeen(cameraId: String, sessionGeneration: Long): Boolean {
        if (!matches(cameraId, sessionGeneration)) return false
        captureResultsSeen = saturatingIncrement(captureResultsSeen)
        return true
    }

    @Synchronized
    fun recordCapturePublished(
        cameraId: String,
        sessionGeneration: Long,
        frameNumber: Long,
        sensorTimestampNs: Long
    ): Long {
        if (!matches(cameraId, sessionGeneration) || frameNumber < 0L || sensorTimestampNs <= 0L) {
            return -1L
        }
        captureResultsPublished = saturatingIncrement(captureResultsPublished)
        latestPublishedFrameNumber = frameNumber
        latestPublishedSensorTimestampNs = sensorTimestampNs
        return captureResultsPublished
    }

    @Synchronized
    fun recordCaptureRejected(cameraId: String, sessionGeneration: Long): Boolean {
        if (!matches(cameraId, sessionGeneration)) return false
        captureResultsRejected = saturatingIncrement(captureResultsRejected)
        return true
    }

    @Synchronized
    fun recordImageMatch(cameraId: String, sessionGeneration: Long): Boolean {
        if (!matches(cameraId, sessionGeneration)) return false
        imageMatches = saturatingIncrement(imageMatches)
        return true
    }

    @Synchronized
    fun recordImageMiss(cameraId: String, sessionGeneration: Long): Boolean {
        if (!matches(cameraId, sessionGeneration)) return false
        imageMisses = saturatingIncrement(imageMisses)
        return true
    }

    @Synchronized
    fun recordTuningAccepted(
        cameraId: String,
        sessionGeneration: Long
    ): Boolean {
        if (!matches(cameraId, sessionGeneration)) return false
        tuningAccepted = saturatingIncrement(tuningAccepted)
        return true
    }

    /** Records a tuning packet that is eligible to alter pixels, without claiming it was rendered. */
    @Synchronized
    fun recordPixelEligibleTuning(cameraId: String, sessionGeneration: Long): Boolean {
        if (!matches(cameraId, sessionGeneration)) return false
        pixelEligibleTunings = saturatingIncrement(pixelEligibleTunings)
        return true
    }

    /**
     * Records only a renderer-confirmed frame and returns its current-timeline ordinal. Resolving
     * metadata or merely queueing a shader is never treated as pixel work.
     */
    @Synchronized
    fun recordPixelApplied(
        cameraId: String,
        sessionGeneration: Long,
        recordingPath: Boolean
    ): Long {
        if (!matches(cameraId, sessionGeneration)) return -1L
        pixelAppliedFrames = saturatingIncrement(pixelAppliedFrames)
        if (recordingPath) {
            recordingPixelAppliedFrames = saturatingIncrement(recordingPixelAppliedFrames)
        } else {
            previewPixelAppliedFrames = saturatingIncrement(previewPixelAppliedFrames)
        }
        return pixelAppliedFrames
    }

    /** Records a failed renderer submission only for the active camera/session timeline. */
    @Synchronized
    fun recordPixelRejected(
        cameraId: String,
        sessionGeneration: Long,
        recordingPath: Boolean
    ): Long {
        if (!matches(cameraId, sessionGeneration)) return -1L
        pixelRenderRejectedFrames = saturatingIncrement(pixelRenderRejectedFrames)
        if (recordingPath) {
            recordingPixelRenderRejectedFrames =
                saturatingIncrement(recordingPixelRenderRejectedFrames)
        } else {
            previewPixelRenderRejectedFrames = saturatingIncrement(previewPixelRenderRejectedFrames)
        }
        return pixelRenderRejectedFrames
    }

    @Synchronized
    fun recordTuningRejected(cameraId: String, sessionGeneration: Long): Boolean {
        if (!matches(cameraId, sessionGeneration)) return false
        tuningRejected = saturatingIncrement(tuningRejected)
        return true
    }

    /** Retires the active timeline while preserving the process-lifetime reset count. */
    @Synchronized
    fun retireTimeline() {
        if (hasActiveTimeline()) resetCount = saturatingIncrement(resetCount)
        cameraId = ""
        sessionGeneration = -1L
        clearTimelineCounts()
    }

    /** Copies into caller-owned storage and performs no allocation. */
    @Synchronized
    fun snapshot(output: Niv99TimelineTelemetrySnapshot) {
        output.cameraId = cameraId
        output.sessionGeneration = sessionGeneration
        output.captureResultsSeen = captureResultsSeen
        output.captureResultsPublished = captureResultsPublished
        output.captureResultsRejected = captureResultsRejected
        output.imageMatches = imageMatches
        output.imageMisses = imageMisses
        output.tuningAccepted = tuningAccepted
        output.tuningRejected = tuningRejected
        output.pixelEligibleTunings = pixelEligibleTunings
        output.pixelAppliedFrames = pixelAppliedFrames
        output.previewPixelAppliedFrames = previewPixelAppliedFrames
        output.recordingPixelAppliedFrames = recordingPixelAppliedFrames
        output.pixelRenderRejectedFrames = pixelRenderRejectedFrames
        output.previewPixelRenderRejectedFrames = previewPixelRenderRejectedFrames
        output.recordingPixelRenderRejectedFrames = recordingPixelRenderRejectedFrames
        output.latestPublishedFrameNumber = latestPublishedFrameNumber
        output.latestPublishedSensorTimestampNs = latestPublishedSensorTimestampNs
        output.resetCount = resetCount
    }

    private fun matches(cameraId: String, sessionGeneration: Long): Boolean =
        cameraId.isNotBlank() &&
            this.cameraId == cameraId &&
            this.sessionGeneration == sessionGeneration

    private fun hasActiveTimeline(): Boolean = cameraId.isNotBlank() && sessionGeneration >= 0L

    private fun clearTimelineCounts() {
        captureResultsSeen = 0L
        captureResultsPublished = 0L
        captureResultsRejected = 0L
        imageMatches = 0L
        imageMisses = 0L
        tuningAccepted = 0L
        tuningRejected = 0L
        pixelEligibleTunings = 0L
        pixelAppliedFrames = 0L
        previewPixelAppliedFrames = 0L
        recordingPixelAppliedFrames = 0L
        pixelRenderRejectedFrames = 0L
        previewPixelRenderRejectedFrames = 0L
        recordingPixelRenderRejectedFrames = 0L
        latestPublishedFrameNumber = -1L
        latestPublishedSensorTimestampNs = -1L
    }

    private fun saturatingIncrement(value: Long): Long =
        if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1L
}
