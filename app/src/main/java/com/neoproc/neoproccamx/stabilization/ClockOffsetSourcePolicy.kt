package com.neoproc.neoproccamx.stabilization

/**
 * ELITE Rank-5 — pure decision policy mapping the camera's timestamp source to a clock-offset strategy.
 *
 * `CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE` tells us whether the camera's `SENSOR_TIMESTAMP`
 * shares a clock with the gyro's `elapsedRealtimeNanos`:
 *   - REALTIME (value 1): same monotonic clock ⇒ the gyro↔frame offset is exactly 0 and can be trusted.
 *   - UNKNOWN  (value 0): a device-private clock ⇒ the offset is unmeasured and MUST be estimated
 *     ([GyroFrameClockOffsetEstimator]) before EIS is engaged, else a wrong offset ADDS wobble.
 *
 * Kept Android-free (the raw int is read once at the call site) so it is fully host-unit-testable.
 */
object ClockOffsetSourcePolicy {

    /** Mirrors `CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN`. */
    const val TIMESTAMP_SOURCE_UNKNOWN: Int = 0

    /** Mirrors `CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`. */
    const val TIMESTAMP_SOURCE_REALTIME: Int = 1

    enum class TimestampSourceKind { REALTIME, UNKNOWN }

    data class ClockOffsetDecision(
        /** Offset (ns) to use immediately; only meaningful when [trusted]. */
        val offsetNs: Long,
        /** True when the offset is known exactly (REALTIME source) — safe to enable EIS on. */
        val trusted: Boolean,
        /** True when the offset must be measured by the estimator before EIS can be trusted. */
        val needsEstimation: Boolean
    )

    /** Classify a raw `SENSOR_INFO_TIMESTAMP_SOURCE` value (null ⇒ UNKNOWN). */
    fun classify(sensorInfoTimestampSource: Int?): TimestampSourceKind =
        if (sensorInfoTimestampSource == TIMESTAMP_SOURCE_REALTIME) {
            TimestampSourceKind.REALTIME
        } else {
            TimestampSourceKind.UNKNOWN
        }

    /** Resolve the offset strategy for a timestamp-source kind. */
    fun resolve(kind: TimestampSourceKind): ClockOffsetDecision = when (kind) {
        TimestampSourceKind.REALTIME -> ClockOffsetDecision(offsetNs = 0L, trusted = true, needsEstimation = false)
        TimestampSourceKind.UNKNOWN -> ClockOffsetDecision(offsetNs = 0L, trusted = false, needsEstimation = true)
    }

    /** Convenience: classify + resolve directly from the raw characteristic value. */
    fun resolve(sensorInfoTimestampSource: Int?): ClockOffsetDecision =
        resolve(classify(sensorInfoTimestampSource))
}
