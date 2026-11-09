package com.neoproc.neoproccamx.recording

/** Independent Camera2-ingress -> writer-accept -> durable-packet lineage for one strict master. */
data class Npv10CaptureLedgerSnapshot(
    val ingressFrames: Long,
    val acceptedFrames: Long,
    val committedFrames: Long,
    val ingressTimestampHash: Long,
    val acceptedTimestampHash: Long,
    val committedTimestampHash: Long,
    val firstIngressTimestampNs: Long,
    val lastIngressTimestampNs: Long,
    val firstCommittedTimestampNs: Long,
    val lastCommittedTimestampNs: Long,
    val invalidTimestamps: Long,
    val nonMonotonicIngress: Long,
    val missingCadenceFrames: Long,
    val terminalCadenceBoundaryTimestampNs: Long,
    val terminalCadenceExcludedMissingFrames: Long,
    val committedPayloadBytes: Long
) {
    fun failureReasons(expectedPayloadBytesPerFrame: Long): List<String> = buildList {
        if (ingressFrames <= 0L) add("ingressFrames=$ingressFrames")
        if (ingressFrames != acceptedFrames) {
            add("ingressAcceptedMismatch=$ingressFrames/$acceptedFrames")
        }
        if (acceptedFrames != committedFrames) {
            add("acceptedCommittedMismatch=$acceptedFrames/$committedFrames")
        }
        if (ingressTimestampHash != acceptedTimestampHash ||
            acceptedTimestampHash != committedTimestampHash
        ) add("timestampLineageHashMismatch")
        if (firstIngressTimestampNs <= 0L || firstCommittedTimestampNs <= 0L) {
            add("firstTimestampNotPositive")
        }
        if (firstIngressTimestampNs != firstCommittedTimestampNs ||
            lastIngressTimestampNs != lastCommittedTimestampNs
        ) add("timestampBoundaryMismatch")
        if (invalidTimestamps != 0L) add("invalidTimestamps=$invalidTimestamps")
        if (nonMonotonicIngress != 0L) add("nonMonotonicIngress=$nonMonotonicIngress")
        if (missingCadenceFrames != 0L) add("missingCadenceFrames=$missingCadenceFrames")
        val expectedPayloadBytes = multiplySaturated(ingressFrames, expectedPayloadBytesPerFrame)
        if (committedPayloadBytes != expectedPayloadBytes) {
            add("payloadMismatch=$committedPayloadBytes/$expectedPayloadBytes")
        }
    }

    val isExact: Boolean
        get() = failureReasons(0L).none { reason -> !reason.startsWith("payloadMismatch=") }

    private fun multiplySaturated(left: Long, right: Long): Long {
        if (left <= 0L || right <= 0L) return 0L
        return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
    }
}

/**
 * Writer counters are deliberately not consulted. All three stages independently mix the exact
 * sensor timestamp, making a lost prefix, suffix, middle frame, reorder, or async write failure a
 * terminal mismatch even when the surviving file is internally well formed.
 */
internal class Npv10CaptureLedger {
    private var targetFps = 1
    private var ingressFrames = 0L
    private var acceptedFrames = 0L
    private var committedFrames = 0L
    private var ingressTimestampHash = HASH_OFFSET
    private var acceptedTimestampHash = HASH_OFFSET
    private var committedTimestampHash = HASH_OFFSET
    private var firstIngressTimestampNs = 0L
    private var lastIngressTimestampNs = 0L
    private var lastAcceptedTimestampNs = 0L
    private var firstCommittedTimestampNs = 0L
    private var lastCommittedTimestampNs = 0L
    private var lastCadenceTimestampNs = 0L
    private var lastCadenceIntervalEndTimestampNs = 0L
    private var lastCadenceIntervalMissingFrames = 0L
    private var terminalCadenceBoundaryTimestampNs = 0L
    private var terminalCadenceExcludedMissingFrames = 0L
    private var invalidTimestamps = 0L
    private var nonMonotonicIngress = 0L
    private var missingCadenceFrames = 0L
    private var committedPayloadBytes = 0L

    @Synchronized
    fun reset(fps: Int) {
        targetFps = fps.coerceAtLeast(1)
        ingressFrames = 0L
        acceptedFrames = 0L
        committedFrames = 0L
        ingressTimestampHash = HASH_OFFSET
        acceptedTimestampHash = HASH_OFFSET
        committedTimestampHash = HASH_OFFSET
        firstIngressTimestampNs = 0L
        lastIngressTimestampNs = 0L
        lastAcceptedTimestampNs = 0L
        firstCommittedTimestampNs = 0L
        lastCommittedTimestampNs = 0L
        lastCadenceTimestampNs = 0L
        lastCadenceIntervalEndTimestampNs = 0L
        lastCadenceIntervalMissingFrames = 0L
        terminalCadenceBoundaryTimestampNs = 0L
        terminalCadenceExcludedMissingFrames = 0L
        invalidTimestamps = 0L
        nonMonotonicIngress = 0L
        missingCadenceFrames = 0L
        committedPayloadBytes = 0L
    }

    @Synchronized
    fun recordIngress(timestampNs: Long) {
        check(terminalCadenceBoundaryTimestampNs == 0L) {
            "NPV10 ingress arrived after terminal cadence boundary=" +
                terminalCadenceBoundaryTimestampNs
        }
        ingressFrames += 1L
        ingressTimestampHash = mix(ingressTimestampHash, timestampNs)
        if (timestampNs <= 0L) {
            invalidTimestamps += 1L
            return
        }
        if (firstIngressTimestampNs == 0L) firstIngressTimestampNs = timestampNs
        if (lastIngressTimestampNs > 0L && timestampNs <= lastIngressTimestampNs) {
            nonMonotonicIngress += 1L
        }
        val previousCadenceTimestampNs = lastCadenceTimestampNs
        var intervalMissingFrames = 0L
        if (previousCadenceTimestampNs > 0L && timestampNs > previousCadenceTimestampNs) {
            val expectedIntervalNs = 1_000_000_000L / targetFps.toLong()
            val representedIntervals =
                ((timestampNs - previousCadenceTimestampNs + expectedIntervalNs / 2L) /
                    expectedIntervalNs).coerceAtLeast(1L)
            intervalMissingFrames = (representedIntervals - 1L).coerceAtLeast(0L)
            missingCadenceFrames = addSaturated(
                missingCadenceFrames,
                intervalMissingFrames
            )
        }
        lastCadenceIntervalEndTimestampNs = timestampNs
        lastCadenceIntervalMissingFrames = intervalMissingFrames
        lastCadenceTimestampNs = timestampNs
        lastIngressTimestampNs = timestampNs
    }

    @Synchronized
    fun recordAccepted(timestampNs: Long) {
        acceptedFrames += 1L
        acceptedTimestampHash = mix(acceptedTimestampHash, timestampNs)
        lastAcceptedTimestampNs = timestampNs
    }

    @Synchronized
    fun recordCommitted(timestampNs: Long, payloadBytes: Long) {
        committedFrames += 1L
        committedTimestampHash = mix(committedTimestampHash, timestampNs)
        committedPayloadBytes = addSaturated(committedPayloadBytes, payloadBytes.coerceAtLeast(0L))
        if (timestampNs > 0L) {
            if (firstCommittedTimestampNs == 0L) firstCommittedTimestampNs = timestampNs
            lastCommittedTimestampNs = timestampNs
        }
    }

    @Synchronized
    fun beginCadenceSegment() {
        check(terminalCadenceBoundaryTimestampNs == 0L) {
            "NPV10 cadence cannot resume after terminal boundary=" +
                terminalCadenceBoundaryTimestampNs
        }
        lastCadenceTimestampNs = 0L
        lastCadenceIntervalEndTimestampNs = 0L
        lastCadenceIntervalMissingFrames = 0L
    }

    /**
     * Closes the final cadence segment at the exact Camera2 captured-tail timestamp.
     * Every persisted interval remains media cadence, including the final interval. This may run only
     * after every accepted packet has committed: exact counts, hashes, and the terminal timestamp must
     * all agree. Earlier cadence gaps are untouched.
     *
     * @return zero excluded media frames, or null
     * when exact terminal ownership is not proven.
     */
    @Synchronized
    fun closeTerminalCadenceSegment(markerTimestampNs: Long): Long? {
        if (markerTimestampNs <= 0L || terminalCadenceBoundaryTimestampNs != 0L) return null
        val exactLineage = ingressFrames > 0L &&
            ingressFrames == acceptedFrames &&
            acceptedFrames == committedFrames &&
            ingressTimestampHash == acceptedTimestampHash &&
            acceptedTimestampHash == committedTimestampHash &&
            lastIngressTimestampNs == markerTimestampNs &&
            lastAcceptedTimestampNs == markerTimestampNs &&
            lastCommittedTimestampNs == markerTimestampNs
        if (!exactLineage ||
            lastCadenceTimestampNs != markerTimestampNs ||
            lastCadenceIntervalEndTimestampNs != markerTimestampNs ||
            lastCadenceIntervalMissingFrames > missingCadenceFrames
        ) {
            return null
        }
        // Preserve all gaps in persisted content, including the final adjacent frame interval.
        // Camera2 now seals its original producer tail rather than recording a delayed marker.
        val excludedMissingFrames = 0L
        terminalCadenceBoundaryTimestampNs = markerTimestampNs
        terminalCadenceExcludedMissingFrames = excludedMissingFrames
        lastCadenceTimestampNs = 0L
        lastCadenceIntervalEndTimestampNs = 0L
        lastCadenceIntervalMissingFrames = 0L
        return excludedMissingFrames
    }

    @Synchronized
    fun snapshot(): Npv10CaptureLedgerSnapshot = Npv10CaptureLedgerSnapshot(
        ingressFrames = ingressFrames,
        acceptedFrames = acceptedFrames,
        committedFrames = committedFrames,
        ingressTimestampHash = ingressTimestampHash,
        acceptedTimestampHash = acceptedTimestampHash,
        committedTimestampHash = committedTimestampHash,
        firstIngressTimestampNs = firstIngressTimestampNs,
        lastIngressTimestampNs = lastIngressTimestampNs,
        firstCommittedTimestampNs = firstCommittedTimestampNs,
        lastCommittedTimestampNs = lastCommittedTimestampNs,
        invalidTimestamps = invalidTimestamps,
        nonMonotonicIngress = nonMonotonicIngress,
        missingCadenceFrames = missingCadenceFrames,
        terminalCadenceBoundaryTimestampNs = terminalCadenceBoundaryTimestampNs,
        terminalCadenceExcludedMissingFrames = terminalCadenceExcludedMissingFrames,
        committedPayloadBytes = committedPayloadBytes
    )

    private fun mix(hash: Long, timestampNs: Long): Long =
        (hash xor timestampNs) * HASH_PRIME

    private fun addSaturated(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private companion object {
        const val HASH_OFFSET = -3_750_763_034_362_895_579L
        const val HASH_PRIME = 1_099_511_628_211L
    }
}
