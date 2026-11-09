package com.neoproc.neoproccamx.ui

import kotlin.math.roundToLong

/**
 * Allocation-free timing and sizing decisions shared by encoded and private-master playback.
 * Keeping the arithmetic Android-free makes the deadline behaviour deterministic in host tests.
 */
internal object VideoPlaybackPerformancePolicy {
    const val LOCAL_MIN_BUFFER_MS = 2_500
    const val LOCAL_MAX_BUFFER_MS = 12_000
    const val PLAYBACK_START_BUFFER_MS = 250
    const val REBUFFER_START_BUFFER_MS = 750
    const val BACK_BUFFER_MS = 1_500
    const val CONTROLS_UPDATE_INTERVAL_MS = 500L
    private const val RAW_PLAYBACK_MIN_LONG_EDGE = 480
    private const val RAW_PLAYBACK_STRICT_MAX_LONG_EDGE = 640
    private const val RAW_PLAYBACK_STANDARD_MAX_LONG_EDGE = 540
    private const val NPV10_PLAYBACK_MIN_LONG_EDGE = 720
    private const val NPV10_PLAYBACK_STRICT_MAX_LONG_EDGE = 1440
    private const val NPV10_PLAYBACK_STANDARD_MAX_LONG_EDGE = 1080

    /**
     * A local camera master must not enter playback through Media3's permissive joining window.
     * During that window MediaCodecVideoRenderer is allowed to report itself ready while dropping
     * late output to catch the already-running media clock. Hardware Main10 startup on Qualcomm can
     * then lose the first two real pictures. Zero makes readiness wait for the video renderer, so
     * playback starts from the decoded preroll frame without changing source cadence or quality.
     */
    const val ALLOWED_VIDEO_JOINING_TIME_MS = 0L

    private const val MIN_DECODE_LEAD_NS = 100_000L
    private const val MAX_DECODE_LEAD_NS = 250_000_000L
    private const val PLAYBACK_MEMORY_NUMERATOR = 40L
    private const val PLAYBACK_MEMORY_DENOMINATOR = 100L
    private const val DISPLAY_BUFFER_COUNT = 4L // source ARGB + three pooled Bitmaps

    data class PlaybackMemoryAdmission(
        val requiredBytes: Long,
        val budgetBytes: Long,
        val admitted: Boolean
    )

    /** Delayed media discovery must not start a decoder after the Activity has left the foreground. */
    fun shouldAutoplay(lifecycleResumed: Boolean, playbackUiVisible: Boolean): Boolean =
        lifecycleResumed && playbackUiVisible

    /**
     * RAW acquisition masters need a responsive monitoring proxy; final conversion always retains
     * full source resolution and the complete quality pipeline. NPV10 is already display-oriented
     * and can use the larger viewport-bound decode target.
     */
    fun privateMasterDecodeLongEdge(
        viewportLongEdge: Int,
        rawMaster: Boolean,
        strictHighEnd: Boolean
    ): Int {
        val minimum = if (rawMaster) RAW_PLAYBACK_MIN_LONG_EDGE else NPV10_PLAYBACK_MIN_LONG_EDGE
        val maximum = when {
            rawMaster && strictHighEnd -> RAW_PLAYBACK_STRICT_MAX_LONG_EDGE
            rawMaster -> RAW_PLAYBACK_STANDARD_MAX_LONG_EDGE
            strictHighEnd -> NPV10_PLAYBACK_STRICT_MAX_LONG_EDGE
            else -> NPV10_PLAYBACK_STANDARD_MAX_LONG_EDGE
        }
        return viewportLongEdge.coerceIn(minimum, maximum)
    }

    /**
     * Fail-closed native/heap admission for direct acquisition-master playback. The two packet
     * buffers and the bounded display pool must leave most of the process heap available to Android,
     * Media3, and UI state; oversized masters are rejected before either direct allocation begins.
     */
    fun playbackMemoryAdmission(
        storedFrameBytes: Long,
        decodedFrameBytes: Long,
        targetLongEdge: Int,
        maxMemoryBytes: Long
    ): PlaybackMemoryAdmission {
        if (storedFrameBytes <= 0L || decodedFrameBytes <= 0L ||
            targetLongEdge <= 0 || maxMemoryBytes <= 0L
        ) {
            return PlaybackMemoryAdmission(0L, 0L, admitted = false)
        }
        val required = runCatching {
            val displayPixels = Math.multiplyExact(targetLongEdge.toLong(), targetLongEdge.toLong())
            val displayBytes = Math.multiplyExact(
                Math.multiplyExact(displayPixels, Int.SIZE_BYTES.toLong()),
                DISPLAY_BUFFER_COUNT
            )
            Math.addExact(Math.addExact(storedFrameBytes, decodedFrameBytes), displayBytes)
        }.getOrElse { Long.MAX_VALUE }
        val budget = (maxMemoryBytes / PLAYBACK_MEMORY_DENOMINATOR) * PLAYBACK_MEMORY_NUMERATOR
        return PlaybackMemoryAdmission(
            requiredBytes = required,
            budgetBytes = budget,
            admitted = required in 1..budget
        )
    }

    fun frameAtPosition(positionUs: Long, fps: Int, frameCount: Int): Int {
        if (frameCount <= 0) return 0
        val safeFps = fps.coerceAtLeast(1)
        val safePositionUs = positionUs.coerceAtLeast(0L)
        val endPositionUs = (
            frameCount.toLong() * 1_000_000L + safeFps.toLong() - 1L
            ) / safeFps.toLong()
        if (safePositionUs >= endPositionUs) return frameCount - 1
        val wholeSeconds = safePositionUs / 1_000_000L
        val remainderUs = safePositionUs % 1_000_000L
        val frame = wholeSeconds * safeFps.toLong() +
            (remainderUs * safeFps.toLong()) / 1_000_000L
        return frame
            .coerceIn(0L, (frameCount - 1).toLong())
            .toInt()
    }

    /** Start decoding early enough that completion, rather than decode start, meets the frame deadline. */
    fun decodeStartDelayNs(
        remainingMediaUs: Long,
        playbackSpeed: Float,
        estimatedDecodeNs: Long
    ): Long {
        require(playbackSpeed.isFinite() && playbackSpeed > 0f) {
            "Playback speed must be positive"
        }
        if (remainingMediaUs <= 0L) return 0L
        val wallDeadlineNs = (remainingMediaUs.toDouble() * 1_000.0 / playbackSpeed.toDouble())
            .roundToLong()
            .coerceAtLeast(0L)
        return (wallDeadlineNs - estimatedDecodeNs.coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    /**
     * Hold an early decoded frame until its media deadline. Decode lead improves deadline reliability,
     * but it must never become early presentation or uneven cadence on the display.
     */
    fun presentationDelayNs(
        frameIndex: Int,
        currentPositionUs: Long,
        fps: Int,
        playbackSpeed: Float
    ): Long {
        require(playbackSpeed.isFinite() && playbackSpeed > 0f) {
            "Playback speed must be positive"
        }
        if (frameIndex <= 0) return 0L
        val safeFps = fps.coerceAtLeast(1)
        val frameDeadlineUs = (frameIndex.toLong() * 1_000_000L) / safeFps.toLong()
        val remainingMediaUs = frameDeadlineUs - currentPositionUs.coerceAtLeast(0L)
        if (remainingMediaUs <= 0L) return 0L
        return (remainingMediaUs.toDouble() * 1_000.0 / playbackSpeed.toDouble())
            .roundToLong()
            .coerceAtLeast(0L)
    }

    /** A stable EWMA avoids both chronic late presentation and reacting permanently to one I/O spike. */
    fun updatedDecodeLeadNs(previousNs: Long, sampleNs: Long): Long {
        val boundedSample = sampleNs.coerceIn(MIN_DECODE_LEAD_NS, MAX_DECODE_LEAD_NS)
        if (previousNs <= 0L) return boundedSample
        return (previousNs * 3L + boundedSample) / 4L
    }

    fun bayerReductionPlan(
        sourceWidth: Int,
        sourceHeight: Int,
        targetLongEdge: Int
    ): BayerReductionPlan {
        require(sourceWidth >= 2 && sourceHeight >= 2) { "RAW playback geometry must be at least 2x2" }
        require(targetLongEdge >= 2) { "RAW playback target must be at least 2 pixels" }
        val longEdge = maxOf(sourceWidth, sourceHeight)
        val scale = if (longEdge <= targetLongEdge) {
            1
        } else {
            (longEdge + targetLongEdge - 1) / targetLongEdge
        }
        val reducedWidth = if (scale == 1) {
            sourceWidth
        } else {
            (sourceWidth / scale).coerceAtLeast(2).and(-2)
        }
        val reducedHeight = if (scale == 1) {
            sourceHeight
        } else {
            (sourceHeight / scale).coerceAtLeast(2).and(-2)
        }
        val outputSize = Math.multiplyExact(reducedWidth, reducedHeight)
        val indices = IntArray(outputSize)
        if (scale == 1) {
            for (index in indices.indices) indices[index] = index
        } else {
            val cellStride = Math.multiplyExact(scale, 2)
            var outputIndex = 0
            for (y in 0 until reducedHeight) {
                val sourceY = ((y / 2) * cellStride + (y and 1)).coerceAtMost(sourceHeight - 1)
                val sourceRow = sourceY * sourceWidth
                for (x in 0 until reducedWidth) {
                    val sourceX = ((x / 2) * cellStride + (x and 1)).coerceAtMost(sourceWidth - 1)
                    indices[outputIndex++] = sourceRow + sourceX
                }
            }
        }
        return BayerReductionPlan(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            reducedWidth = reducedWidth,
            reducedHeight = reducedHeight,
            sourceSampleIndices = indices
        )
    }
}

internal data class BayerReductionPlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val reducedWidth: Int,
    val reducedHeight: Int,
    val sourceSampleIndices: IntArray
)
