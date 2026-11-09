package com.neoproc.neoproccamx.camera

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Evidence produced while waiting for a newly configured Camera2 repeating request to settle.
 * Sensor timestamps are used instead of callback wall time, so scheduler stalls cannot create a
 * false PASS and a real HAL frame gap cannot be hidden by a burst of delayed callbacks.
 */
internal data class CameraRecordingCadenceAdmissionSnapshot(
    val admitted: Boolean,
    val observedFrames: Int,
    val consecutiveIntervals: Int,
    val rejectedIntervals: Int,
    val measuredFps: Double,
    val lastGapNs: Long,
    val largestGapNs: Long
)

/**
 * Fail-closed startup gate for recording routes that consume every physical sensor frame.
 *
 * A session-configured callback and one recent capture result prove liveness, but they do not prove
 * cadence. Some vendor HALs pause for several hundred milliseconds while their RAW request pipeline
 * settles. Opening the lossless writer during that interval permanently records the startup holes.
 * This gate requires a consecutive, target-rate sensor-timestamp window before storage admission.
 */
internal class CameraRecordingCadenceAdmissionGate(
    targetFps: Int,
    private val requiredConsecutiveIntervals: Int = DEFAULT_REQUIRED_INTERVALS,
    private val maximumAcceptedGapNs: Long = DEFAULT_MAXIMUM_ACCEPTED_GAP_NS
) {
    init {
        require(targetFps > 0) { "targetFps must be positive" }
        require(requiredConsecutiveIntervals > 0) {
            "requiredConsecutiveIntervals must be positive"
        }
        require(maximumAcceptedGapNs > 0L) { "maximumAcceptedGapNs must be positive" }
    }

    private val requestedFps = targetFps
    private val targetIntervalNs =
        (NANOS_PER_SECOND.toDouble() / targetFps.toDouble()).roundToLong()
    private val minimumIntervalNs =
        (targetIntervalNs.toDouble() * MINIMUM_INTERVAL_RATIO).roundToLong()
    private val maximumIntervalNs = minOf(
        maximumAcceptedGapNs,
        (targetIntervalNs.toDouble() * MAXIMUM_INTERVAL_RATIO).roundToLong()
    )
    private val acceptedIntervalsNs = ArrayDeque<Long>(requiredConsecutiveIntervals)
    private var previousTimestampNs = 0L
    private var observedFrames = 0
    private var rejectedIntervals = 0
    private var lastGapNs = 0L
    private var largestGapNs = 0L

    fun observe(sensorTimestampNs: Long): CameraRecordingCadenceAdmissionSnapshot {
        if (sensorTimestampNs <= 0L) {
            rejectedIntervals += 1
            acceptedIntervalsNs.clear()
            previousTimestampNs = 0L
            return snapshot()
        }

        observedFrames += 1
        val previous = previousTimestampNs
        previousTimestampNs = sensorTimestampNs
        if (previous <= 0L) return snapshot()

        val gapNs = sensorTimestampNs - previous
        lastGapNs = gapNs
        if (gapNs > largestGapNs) largestGapNs = gapNs
        if (gapNs !in minimumIntervalNs..maximumIntervalNs) {
            rejectedIntervals += 1
            acceptedIntervalsNs.clear()
            return snapshot()
        }

        acceptedIntervalsNs.addLast(gapNs)
        while (acceptedIntervalsNs.size > requiredConsecutiveIntervals) {
            acceptedIntervalsNs.removeFirst()
        }
        return snapshot()
    }

    fun snapshot(): CameraRecordingCadenceAdmissionSnapshot {
        val averageIntervalNs = acceptedIntervalsNs
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?: 0.0
        val measuredFps = if (averageIntervalNs > 0.0) {
            NANOS_PER_SECOND.toDouble() / averageIntervalNs
        } else {
            0.0
        }
        val fpsTolerance = max(MINIMUM_FPS_TOLERANCE, requestedFps * FPS_TOLERANCE_RATIO)
        val admitted = acceptedIntervalsNs.size >= requiredConsecutiveIntervals &&
            abs(measuredFps - requestedFps.toDouble()) <= fpsTolerance
        return CameraRecordingCadenceAdmissionSnapshot(
            admitted = admitted,
            observedFrames = observedFrames,
            consecutiveIntervals = acceptedIntervalsNs.size,
            rejectedIntervals = rejectedIntervals,
            measuredFps = measuredFps,
            lastGapNs = lastGapNs,
            largestGapNs = largestGapNs
        )
    }

    internal companion object {
        const val DEFAULT_REQUIRED_INTERVALS = 10
        const val DEFAULT_MAXIMUM_ACCEPTED_GAP_NS = 60_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val MINIMUM_INTERVAL_RATIO = 0.70
        private const val MAXIMUM_INTERVAL_RATIO = 1.30
        private const val FPS_TOLERANCE_RATIO = 0.04
        private const val MINIMUM_FPS_TOLERANCE = 0.5
    }
}
