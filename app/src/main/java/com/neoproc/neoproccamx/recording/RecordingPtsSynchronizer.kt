package com.neoproc.neoproccamx.recording

import kotlin.math.roundToLong

/**
 * Maps camera sensor timestamps onto the encoder clock.
 *
 * The first sensor timestamp becomes the origin. A small sliding-window latency
 * term accounts for GPU dispatch and surface presentation delay, and output PTS
 * is forced monotonic so muxers never receive backward timestamps after clock
 * jitter or vendor timestamp glitches.
 */
internal class RecordingPtsSynchronizer(
    initialLatencyCompensationUs: Long = 0L,
    private val minimumStepUs: Long = 1L
) {
    private var firstSensorTimestampNs: Long = Long.MIN_VALUE
    private var lastPtsUs: Long = Long.MIN_VALUE

    var currentLatencyCompensationUs: Long = initialLatencyCompensationUs.coerceAtLeast(0L)
        private set

    fun reset(initialLatencyCompensationUs: Long = currentLatencyCompensationUs) {
        firstSensorTimestampNs = Long.MIN_VALUE
        lastPtsUs = Long.MIN_VALUE
        currentLatencyCompensationUs = initialLatencyCompensationUs.coerceAtLeast(0L)
    }

    fun ptsUsForSensorTimestamp(sensorTimestampNs: Long): Long {
        val safeSensorTimestampNs = sensorTimestampNs.coerceAtLeast(0L)
        if (firstSensorTimestampNs == Long.MIN_VALUE) {
            firstSensorTimestampNs = safeSensorTimestampNs
        }
        val rawPtsUs =
            ((safeSensorTimestampNs - firstSensorTimestampNs) / 1_000L) + currentLatencyCompensationUs
        val monotonicPtsUs =
            if (lastPtsUs == Long.MIN_VALUE) {
                rawPtsUs.coerceAtLeast(0L)
            } else {
                rawPtsUs.coerceAtLeast(lastPtsUs + minimumStepUs.coerceAtLeast(1L))
            }
        lastPtsUs = monotonicPtsUs
        return monotonicPtsUs
    }

    fun recordDispatchLatency(sensorTimestampNs: Long, completionNs: Long) {
        val observedUs = ((completionNs - sensorTimestampNs).coerceAtLeast(0L) / 1_000L)
        currentLatencyCompensationUs =
            if (currentLatencyCompensationUs <= 0L) {
                observedUs
            } else {
                (currentLatencyCompensationUs * 0.75 + observedUs * 0.25).roundToLong()
            }.coerceAtLeast(0L)
    }

    fun signature(): String =
        "sensor-to-encoder-pts=linear|clock=SENSOR_TIMESTAMP|latency=sliding-window|monotonic=true"
}
