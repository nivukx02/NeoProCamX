package com.neoproc.neoproccamx.recording

/**
 * Preserves decoded record-master timing on a zero-based MOV presentation timeline.
 *
 * Conversion is deliberately fail-closed: decoded frames must arrive in index order. A real timestamp
 * sequence must be non-negative and strictly increasing. The established in-memory-reader contract also
 * permits an all-zero sequence to mean "timing unavailable"; only that complete sentinel sequence receives
 * deterministic nominal cadence. Mixed sentinel/real or regressing timing is rejected.
 */
internal class RecordDevelopPresentationTimeline(
    private val nominalFrameDurationUs: Long
) {
    private var firstSourcePresentationTimeUs: Long? = null
    private var previousSourcePresentationTimeUs: Long? = null
    private var nextIndex: Long = 0L
    private var lastPositiveSampleDurationUs: Long? = null
    private var mode: Mode = Mode.UNRESOLVED

    var lastPresentationTimeUs: Long = -1L
        private set

    val frameCount: Long
        get() = nextIndex

    init {
        require(nominalFrameDurationUs > 0L) { "Nominal frame duration must be positive" }
    }

    fun normalize(index: Long, sourcePresentationTimeUs: Long): Long {
        require(index == nextIndex) {
            "Record-develop timestamps must be delivered in order: expected=$nextIndex actual=$index"
        }
        require(sourcePresentationTimeUs >= 0L) {
            "Record-develop source PTS must be non-negative: actual=$sourcePresentationTimeUs"
        }

        val first = firstSourcePresentationTimeUs
        val normalizedPresentationTimeUs = if (first == null) {
            firstSourcePresentationTimeUs = sourcePresentationTimeUs
            mode = if (sourcePresentationTimeUs == 0L) Mode.UNRESOLVED else Mode.SOURCE_TIMELINE
            0L
        } else {
            when (mode) {
                Mode.UNRESOLVED -> {
                    if (sourcePresentationTimeUs == 0L) {
                        mode = Mode.ALL_ZERO_SENTINEL
                        Math.multiplyExact(index, nominalFrameDurationUs)
                    } else {
                        mode = Mode.SOURCE_TIMELINE
                        Math.subtractExact(sourcePresentationTimeUs, first)
                    }
                }
                Mode.ALL_ZERO_SENTINEL -> {
                    require(sourcePresentationTimeUs == 0L) {
                        "Record-develop timing cannot mix the all-zero sentinel with real PTS: " +
                            "index=$index current=$sourcePresentationTimeUs"
                    }
                    Math.multiplyExact(index, nominalFrameDurationUs)
                }
                Mode.SOURCE_TIMELINE -> {
                    val previous = requireNotNull(previousSourcePresentationTimeUs)
                    require(sourcePresentationTimeUs > previous) {
                        "Record-develop source PTS must be strictly increasing: " +
                            "previous=$previous current=$sourcePresentationTimeUs"
                    }
                    Math.subtractExact(sourcePresentationTimeUs, first)
                }
            }
        }

        if (lastPresentationTimeUs >= 0L) {
            val durationUs = Math.subtractExact(normalizedPresentationTimeUs, lastPresentationTimeUs)
            require(durationUs > 0L) {
                "Record-develop normalized PTS must be strictly increasing: " +
                    "previous=$lastPresentationTimeUs current=$normalizedPresentationTimeUs"
            }
            lastPositiveSampleDurationUs = durationUs
        }

        previousSourcePresentationTimeUs = sourcePresentationTimeUs
        lastPresentationTimeUs = normalizedPresentationTimeUs
        nextIndex = Math.addExact(nextIndex, 1L)
        return normalizedPresentationTimeUs
    }

    /**
     * Resolves the exact zero-based track end. A container-authored [sourceDurationUs] is authoritative
     * because PTS deltas cannot reveal the duration of the final sample. Readers without that metadata
     * retain the measured-last-delta fallback; one-frame clips then use nominal cadence.
     */
    fun endPresentationTimeUs(sourceDurationUs: Long? = null): Long {
        require(frameCount > 0L) { "Cannot resolve end time for an empty record develop" }
        if (sourceDurationUs != null) {
            require(sourceDurationUs > lastPresentationTimeUs) {
                "Record-develop source duration must follow the final normalized PTS: " +
                    "last=$lastPresentationTimeUs duration=$sourceDurationUs"
            }
            return sourceDurationUs
        }
        val finalDurationUs = lastPositiveSampleDurationUs ?: nominalFrameDurationUs
        return Math.addExact(lastPresentationTimeUs, finalDurationUs)
    }

    private enum class Mode {
        UNRESOLVED,
        ALL_ZERO_SENTINEL,
        SOURCE_TIMELINE
    }
}
