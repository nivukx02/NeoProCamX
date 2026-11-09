package com.neoproc.neoproccamx.recording

/**
 * Converts the monotonic nanosecond timestamps stored in NPRAW packets into a zero-based MOV timeline.
 *
 * The policy is deliberately fail-closed. Reordered, duplicated, missing, or sub-microsecond timestamps
 * are not replaced with an invented constant cadence, because doing so would make a conversion certificate
 * falsely claim that source timing was preserved.
 */
internal class RawConversionPresentationTimeline {
    private var firstSourceTimestampNs: Long? = null
    private var previousSourceTimestampNs: Long? = null
    private var nextIndex: Int = 0
    private var lastPositiveSampleDurationUs: Long? = null

    var lastPresentationTimeUs: Long = -1L
        private set

    val frameCount: Int
        get() = nextIndex

    val sourceOriginTimestampNs: Long?
        get() = firstSourceTimestampNs

    fun normalize(index: Int, sourceTimestampNs: Long): Long {
        require(index == nextIndex) {
            "RAW conversion timestamps must be delivered in order: expected=$nextIndex actual=$index"
        }
        require(sourceTimestampNs >= 0L) { "RAW source timestamp must be non-negative" }

        val first = firstSourceTimestampNs
        val presentationTimeUs = if (first == null) {
            firstSourceTimestampNs = sourceTimestampNs
            0L
        } else {
            val previous = requireNotNull(previousSourceTimestampNs)
            require(sourceTimestampNs > previous) {
                "RAW source timestamps must be strictly increasing: previous=$previous current=$sourceTimestampNs"
            }
            val deltaNs = Math.subtractExact(sourceTimestampNs, first)
            val normalizedUs = deltaNs / NANOS_PER_MICROSECOND
            require(normalizedUs > lastPresentationTimeUs) {
                "RAW source timestamps collapse at MOV microsecond precision: " +
                    "previousUs=$lastPresentationTimeUs currentUs=$normalizedUs"
            }
            normalizedUs
        }

        previousSourceTimestampNs = sourceTimestampNs
        if (lastPresentationTimeUs >= 0L) {
            lastPositiveSampleDurationUs = Math.subtractExact(
                presentationTimeUs,
                lastPresentationTimeUs
            )
        }
        lastPresentationTimeUs = presentationTimeUs
        nextIndex += 1
        return presentationTimeUs
    }

    fun endPresentationTimeUs(nominalFrameDurationUs: Long): Long {
        require(frameCount > 0) { "Cannot resolve end time for an empty RAW conversion" }
        require(nominalFrameDurationUs > 0L) { "Nominal frame duration must be positive" }
        return Math.addExact(
            lastPresentationTimeUs,
            lastPositiveSampleDurationUs ?: nominalFrameDurationUs
        )
    }

    private companion object {
        const val NANOS_PER_MICROSECOND = 1_000L
    }
}
