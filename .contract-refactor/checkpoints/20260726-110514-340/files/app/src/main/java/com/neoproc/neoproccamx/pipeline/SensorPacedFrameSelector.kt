package com.neoproc.neoproccamx.pipeline

/**
 * s7-f01 / CP92 — sensor-paced analysis-frame selection.
 *
 * The legacy path selects analysis frames purely by frame COUNT
 * ([FrameRealtimeAnalysisPlan.shouldAnalyze]): every Nth delivered frame. Under arrival
 * jitter (acquire-latest drops bursts), count-based cadence wobbles and frame age is
 * unbounded. This selector instead paces on SENSOR TIMESTAMP: it admits a frame once the
 * elapsed sensor time since the last admitted frame reaches a target interval derived from
 * the same stride the count-based plan computes, so the realized cadence stays within ±1
 * frame of target regardless of delivery jitter.
 *
 * Pure decision math in [SensorPacedFrameSelectorPolicy.decide]; [SensorPacedFrameSelector]
 * is a thin stateful holder. Dormant unless [SensorPacedFrameSelectorFlag.enabled] is true.
 */
internal object SensorPacedFrameSelectorPolicy {

    /** Nanoseconds per frame at [previewFps] (>= 1 fps). */
    fun frameIntervalNs(previewFps: Float): Long {
        val fps = previewFps.coerceAtLeast(1f)
        return (1_000_000_000.0 / fps).toLong().coerceAtLeast(1L)
    }

    /**
     * Target sensor-time interval between admitted analysis frames, in nanoseconds.
     * Mirrors the count-based cadence ([aiFrameStride] frames) but expressed in time so it
     * is robust to dropped/bursted arrivals.
     */
    fun targetIntervalNs(previewFps: Float, aiFrameStride: Long): Long {
        val stride = aiFrameStride.coerceAtLeast(1L)
        return frameIntervalNs(previewFps) * stride
    }

    data class Decision(val admit: Boolean, val nextAnchorNs: Long)

    /**
     * Decide whether to admit the frame at [sensorTimestampNs].
     *
     * @param lastAdmittedAnchorNs the sensor-time anchor of the last admitted frame, or
     *   [Long.MIN_VALUE] if none has been admitted yet (first frame is always admitted, matching
     *   shouldAnalyze's frameSeq<=1 prime-the-pump behavior).
     * @param targetIntervalNs from [targetIntervalNs].
     * @return admit decision + the anchor to store for next time (advanced by whole intervals
     *   when admitting, so cadence cannot drift or burst-catch-up).
     */
    fun decide(
        sensorTimestampNs: Long,
        lastAdmittedAnchorNs: Long,
        targetIntervalNs: Long
    ): Decision {
        val interval = targetIntervalNs.coerceAtLeast(1L)
        if (lastAdmittedAnchorNs == Long.MIN_VALUE) {
            return Decision(admit = true, nextAnchorNs = sensorTimestampNs)
        }
        val elapsed = sensorTimestampNs - lastAdmittedAnchorNs
        if (elapsed < interval) {
            return Decision(admit = false, nextAnchorNs = lastAdmittedAnchorNs)
        }
        // Advance the anchor by whole intervals toward the present to avoid drift while never
        // bursting more than one admit per call.
        val intervalsPassed = (elapsed / interval).coerceAtLeast(1L)
        val advanced = lastAdmittedAnchorNs + intervalsPassed * interval
        return Decision(admit = true, nextAnchorNs = advanced)
    }
}

/** Per-pipeline stateful wrapper around [SensorPacedFrameSelectorPolicy]. */
internal class SensorPacedFrameSelector {
    @Volatile
    private var lastAdmittedAnchorNs: Long = Long.MIN_VALUE

    /** Resets cadence state (call on start/stop so a new session begins fresh). */
    fun reset() {
        lastAdmittedAnchorNs = Long.MIN_VALUE
    }

    /**
     * @return true to admit this frame for AI analysis. Synchronized so concurrent frame
     *   callbacks observe a single monotonic anchor.
     */
    @Synchronized
    fun shouldAnalyze(
        sensorTimestampNs: Long,
        previewFps: Float,
        aiFrameStride: Long
    ): Boolean {
        val target = SensorPacedFrameSelectorPolicy.targetIntervalNs(previewFps, aiFrameStride)
        val decision = SensorPacedFrameSelectorPolicy.decide(
            sensorTimestampNs = sensorTimestampNs,
            lastAdmittedAnchorNs = lastAdmittedAnchorNs,
            targetIntervalNs = target
        )
        if (decision.admit) {
            lastAdmittedAnchorNs = decision.nextAnchorNs
        }
        return decision.admit
    }
}
