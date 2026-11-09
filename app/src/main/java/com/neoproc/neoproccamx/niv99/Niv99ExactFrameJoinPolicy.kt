package com.neoproc.neoproccamx.niv99

import kotlin.math.ceil

/**
 * Bounds an Image-first recording join to one configured sensor interval. Camera2 is permitted to
 * deliver an Image before its total result; holding that already acquired Image for longer than one
 * interval would hide a broken metadata stream and increase ImageReader ownership pressure.
 */
internal object Niv99ExactFrameJoinPolicy {
    const val MINIMUM_WAIT_NS = 12_000_000L
    const val MAXIMUM_WAIT_NS = 40_000_000L
    /** Maximum accepted clock-translation skew for one physical Camera2 frame. */
    const val MAXIMUM_CLOCK_ALIGNMENT_NS = 2_000_000L

    fun timeoutNs(targetFps: Float): Long {
        if (!targetFps.isFinite() || targetFps <= 0f) return MAXIMUM_WAIT_NS
        val sensorIntervalNs = ceil(1_000_000_000.0 / targetFps.toDouble()).toLong()
        return sensorIntervalNs.coerceIn(MINIMUM_WAIT_NS, MAXIMUM_WAIT_NS)
    }

    /**
     * Camera2 result and Image timestamps can be translated from different Android monotonic clocks
     * at slightly different instants. Limit reconciliation to at most one eighth of a sensor period
     * and never more than the 2 ms tolerance used by the frame-lineage proof.
     */
    fun timestampAlignmentNs(targetFps: Float): Long {
        if (!targetFps.isFinite() || targetFps <= 0f) return MAXIMUM_CLOCK_ALIGNMENT_NS
        val sensorIntervalNs = ceil(1_000_000_000.0 / targetFps.toDouble()).toLong()
        return (sensorIntervalNs / 8L).coerceAtMost(MAXIMUM_CLOCK_ALIGNMENT_NS).coerceAtLeast(0L)
    }

    fun isSamePhysicalFrame(
        imageTimestampNs: Long,
        metadataTimestampNs: Long,
        targetFps: Float
    ): Boolean {
        if (imageTimestampNs <= 0L || metadataTimestampNs <= 0L) return false
        return absoluteDifference(imageTimestampNs, metadataTimestampNs) <=
            timestampAlignmentNs(targetFps)
    }

    private fun absoluteDifference(first: Long, second: Long): Long {
        if (first == second) return 0L
        val larger = maxOf(first, second)
        val smaller = minOf(first, second)
        return if (smaller < 0L && larger > Long.MAX_VALUE + smaller) {
            Long.MAX_VALUE
        } else {
            larger - smaller
        }
    }
}

/**
 * Preview prefers its exact Camera2 result, but the rendezvous cannot consume most of the next
 * sensor period: doing so makes otherwise stable camera input visibly miss a presentation slot.
 * Seventy percent of one interval preserves the common image-before-result skew while leaving time
 * to render. At expiry, only [Niv99PreviewTuningContinuityPolicy] may admit a recent compatible
 * packet; recording continues to use [Niv99ExactFrameJoinPolicy] unchanged.
 */
internal object Niv99PreviewFrameJoinPolicy {
    const val WAIT_NUMERATOR = 7L
    const val WAIT_DENOMINATOR = 10L
    const val MINIMUM_WAIT_NS = 8_000_000L
    const val MAXIMUM_WAIT_NS = 28_000_000L

    fun timeoutNs(targetFps: Float): Long {
        if (!targetFps.isFinite() || targetFps <= 0f) return MAXIMUM_WAIT_NS
        val sensorIntervalNs = ceil(1_000_000_000.0 / targetFps.toDouble()).toLong()
        val scaledNs = if (sensorIntervalNs > Long.MAX_VALUE / WAIT_NUMERATOR) {
            Long.MAX_VALUE
        } else {
            val numerator = sensorIntervalNs * WAIT_NUMERATOR
            (numerator + WAIT_DENOMINATOR - 1L) / WAIT_DENOMINATOR
        }
        return scaledNs.coerceIn(MINIMUM_WAIT_NS, MAXIMUM_WAIT_NS)
    }
}

/**
 * Pure scheduling policy for the non-blocking preview metadata rendezvous.
 *
 * Camera2 result publication is the authoritative wake-up. The only timer is a single deadline
 * fallback, rounded up to the next millisecond so Handler timing cannot create a tight early-retry
 * loop. A notification for a neighbouring frame is never allowed to wake the held texture.
 */
internal object Niv99PreviewMetadataWakePolicy {
    private const val NANOS_PER_MILLISECOND = 1_000_000L

    fun isExactWake(awaitedSensorTimestampNs: Long, publishedSensorTimestampNs: Long): Boolean =
        awaitedSensorTimestampNs > 0L &&
            publishedSensorTimestampNs > 0L &&
            awaitedSensorTimestampNs == publishedSensorTimestampNs

    fun deadlineDelayMs(nowNs: Long, deadlineNs: Long): Long {
        if (deadlineNs <= nowNs) return 1L
        val remainingNs = deadlineNs - nowNs
        val wholeMilliseconds = remainingNs / NANOS_PER_MILLISECOND
        return wholeMilliseconds +
            if (remainingNs % NANOS_PER_MILLISECOND == 0L) 0L else 1L
    }
}

/**
 * A copied P010 frame no longer owns an ImageReader slot, so it may tolerate the longer callback
 * skew observed on full-sensor Camera2 streams without increasing HAL buffer pressure. The wait is
 * still finite and is measured in configured sensor intervals; a newer published timestamp causes
 * the exact lookup to fail immediately rather than consuming this entire budget.
 */
internal object Niv99DeferredMetadataJoinPolicy {
    const val SENSOR_INTERVALS = 4L
    const val MINIMUM_WAIT_NS = 48_000_000L
    const val MAXIMUM_WAIT_NS = 160_000_000L

    fun timeoutNs(targetFps: Float): Long {
        if (!targetFps.isFinite() || targetFps <= 0f) return MAXIMUM_WAIT_NS
        val sensorIntervalNs = ceil(1_000_000_000.0 / targetFps.toDouble()).toLong()
        return multiplySaturated(sensorIntervalNs, SENSOR_INTERVALS)
            .coerceIn(MINIMUM_WAIT_NS, MAXIMUM_WAIT_NS)
    }

    private fun multiplySaturated(value: Long, multiplier: Long): Long =
        if (value > Long.MAX_VALUE / multiplier) Long.MAX_VALUE else value * multiplier
}
