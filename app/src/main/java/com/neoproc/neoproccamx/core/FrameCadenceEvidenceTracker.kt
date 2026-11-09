package com.neoproc.neoproccamx.core

/**
 * Full-session, allocation-free sensor timestamp cadence evidence.
 *
 * This tracker is observational only: it never changes camera requests, frame ownership, pixel
 * data, or scheduling. A rounded interval count tolerates normal sensor-clock jitter while still
 * identifying a 2x (or larger) interval as one or more missing source frames.
 */
internal class FrameCadenceEvidenceTracker {
    private var targetFps: Int = 1
    private var observedFrames: Long = 0L
    private var invalidTimestamps: Long = 0L
    private var nonMonotonicTimestamps: Long = 0L
    private var missingCadenceFrames: Long = 0L
    private var maximumGapNs: Long = 0L
    private var firstTimestampNs: Long = 0L
    private var lastTimestampNs: Long = 0L
    private var firstGapPreviousTimestampNs: Long = 0L
    private var firstGapTimestampNs: Long = 0L
    private var firstGapMissingFrames: Long = 0L
    private var lastIntervalEndTimestampNs: Long = 0L
    private var lastIntervalMissingFrames: Long = 0L
    private var terminalBoundaryTimestampNs: Long = 0L
    private var terminalExcludedMissingFrames: Long = 0L
    private var timestampsAfterTerminal: Long = 0L

    @Synchronized
    fun reset(targetFps: Int) {
        this.targetFps = targetFps.coerceAtLeast(1)
        observedFrames = 0L
        invalidTimestamps = 0L
        nonMonotonicTimestamps = 0L
        missingCadenceFrames = 0L
        maximumGapNs = 0L
        firstTimestampNs = 0L
        lastTimestampNs = 0L
        firstGapPreviousTimestampNs = 0L
        firstGapTimestampNs = 0L
        firstGapMissingFrames = 0L
        lastIntervalEndTimestampNs = 0L
        lastIntervalMissingFrames = 0L
        terminalBoundaryTimestampNs = 0L
        terminalExcludedMissingFrames = 0L
        timestampsAfterTerminal = 0L
    }

    @Synchronized
    fun record(timestampNs: Long) {
        if (terminalBoundaryTimestampNs != 0L) {
            // Evidence collection must never destabilize capture. A late callback after a proven
            // terminal boundary is retained as a diagnostic instead of throwing on the hot path.
            timestampsAfterTerminal = addSaturated(timestampsAfterTerminal, 1L)
            return
        }
        observedFrames = addSaturated(observedFrames, 1L)
        if (timestampNs <= 0L) {
            invalidTimestamps = addSaturated(invalidTimestamps, 1L)
            return
        }
        if (firstTimestampNs == 0L) firstTimestampNs = timestampNs

        val previousTimestampNs = lastTimestampNs
        var intervalMissingFrames = 0L
        if (previousTimestampNs > 0L) {
            if (timestampNs <= previousTimestampNs) {
                nonMonotonicTimestamps = addSaturated(nonMonotonicTimestamps, 1L)
            } else {
                val gapNs = timestampNs - previousTimestampNs
                maximumGapNs = maxOf(maximumGapNs, gapNs)
                val expectedIntervalNs = 1_000_000_000L / targetFps.toLong()
                val representedIntervals =
                    ((gapNs + expectedIntervalNs / 2L) / expectedIntervalNs).coerceAtLeast(1L)
                intervalMissingFrames = (representedIntervals - 1L).coerceAtLeast(0L)
                if (intervalMissingFrames > 0L) {
                    missingCadenceFrames = addSaturated(
                        missingCadenceFrames,
                        intervalMissingFrames
                    )
                    if (firstGapTimestampNs == 0L) {
                        firstGapPreviousTimestampNs = previousTimestampNs
                        firstGapTimestampNs = timestampNs
                        firstGapMissingFrames = intervalMissingFrames
                    }
                }
            }
        }
        lastIntervalEndTimestampNs = timestampNs
        lastIntervalMissingFrames = intervalMissingFrames
        lastTimestampNs = timestampNs
    }

    /**
     * Removes only a proven final control/stop-marker interval from media cadence accounting.
     * Earlier gaps remain failures. Returns the excluded count, or null when the supplied marker is
     * not the exact observed tail.
     */
    @Synchronized
    fun closeTerminalInterval(markerTimestampNs: Long): Long? {
        if (markerTimestampNs <= 0L || terminalBoundaryTimestampNs != 0L) return null
        if (lastTimestampNs != markerTimestampNs || lastIntervalEndTimestampNs != markerTimestampNs ||
            lastIntervalMissingFrames > missingCadenceFrames
        ) {
            return null
        }
        val excluded = lastIntervalMissingFrames
        missingCadenceFrames -= excluded
        terminalBoundaryTimestampNs = markerTimestampNs
        terminalExcludedMissingFrames = excluded
        if (missingCadenceFrames == 0L) {
            firstGapPreviousTimestampNs = 0L
            firstGapTimestampNs = 0L
            firstGapMissingFrames = 0L
        }
        lastIntervalEndTimestampNs = 0L
        lastIntervalMissingFrames = 0L
        return excluded
    }

    @Synchronized
    fun snapshot(): FrameCadenceEvidenceSnapshot = FrameCadenceEvidenceSnapshot(
        targetFps = targetFps,
        observedFrames = observedFrames,
        invalidTimestamps = invalidTimestamps,
        nonMonotonicTimestamps = nonMonotonicTimestamps,
        missingCadenceFrames = missingCadenceFrames,
        maximumGapNs = maximumGapNs,
        firstTimestampNs = firstTimestampNs,
        lastTimestampNs = lastTimestampNs,
        firstGapPreviousTimestampNs = firstGapPreviousTimestampNs,
        firstGapTimestampNs = firstGapTimestampNs,
        firstGapMissingFrames = firstGapMissingFrames,
        terminalBoundaryTimestampNs = terminalBoundaryTimestampNs,
        terminalExcludedMissingFrames = terminalExcludedMissingFrames,
        timestampsAfterTerminal = timestampsAfterTerminal
    )

    private fun addSaturated(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}

internal data class FrameCadenceEvidenceSnapshot(
    val targetFps: Int,
    val observedFrames: Long,
    val invalidTimestamps: Long,
    val nonMonotonicTimestamps: Long,
    val missingCadenceFrames: Long,
    val maximumGapNs: Long,
    val firstTimestampNs: Long,
    val lastTimestampNs: Long,
    val firstGapPreviousTimestampNs: Long,
    val firstGapTimestampNs: Long,
    val firstGapMissingFrames: Long,
    val terminalBoundaryTimestampNs: Long,
    val terminalExcludedMissingFrames: Long,
    val timestampsAfterTerminal: Long
) {
    fun toCompactMetadataMap(prefix: String): Map<String, String> = linkedMapOf(
        "${prefix}TargetFps" to targetFps.toString(),
        "${prefix}ObservedFrames" to observedFrames.toString(),
        "${prefix}InvalidTimestamps" to invalidTimestamps.toString(),
        "${prefix}NonMonotonicTimestamps" to nonMonotonicTimestamps.toString(),
        "${prefix}MissingCadenceFrames" to missingCadenceFrames.toString(),
        "${prefix}MaximumGapNs" to maximumGapNs.toString(),
        "${prefix}FirstTimestampNs" to firstTimestampNs.toString(),
        "${prefix}LastTimestampNs" to lastTimestampNs.toString(),
        "${prefix}FirstGapPreviousTimestampNs" to firstGapPreviousTimestampNs.toString(),
        "${prefix}FirstGapTimestampNs" to firstGapTimestampNs.toString(),
        "${prefix}FirstGapMissingFrames" to firstGapMissingFrames.toString(),
        "${prefix}TerminalBoundaryTimestampNs" to terminalBoundaryTimestampNs.toString(),
        "${prefix}TerminalExcludedMissingFrames" to terminalExcludedMissingFrames.toString(),
        "${prefix}TimestampsAfterTerminal" to timestampsAfterTerminal.toString()
    )
}
