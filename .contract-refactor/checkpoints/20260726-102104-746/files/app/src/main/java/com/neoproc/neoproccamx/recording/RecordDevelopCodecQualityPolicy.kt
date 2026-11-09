package com.neoproc.neoproccamx.recording

import kotlin.math.roundToLong

internal data class RecordDevelopEncoderElitePlan(
    val requestedBitrate: Int,
    val targetBitrate: Int,
    val maximumBitrate: Int,
    val quality: Int,
    val complexity: Int?,
    val operatingRate: Int,
    val maximumQp: Int
)

/** Capability-bounded, deterministic quality contract for offline SDR/NDR/RAW→LOG delivery. */
internal object RecordDevelopEncoderQualityPolicy {
    private const val BITS_PER_PIXEL_PER_FRAME = 1.35
    private const val MINIMUM_BITRATE = 200_000_000
    const val ELITE_QUALITY = 100
    const val ELITE_MAXIMUM_QP = 18

    fun resolve(
        width: Int,
        height: Int,
        fps: Int,
        bitrateLower: Int,
        bitrateUpper: Int,
        complexityLower: Int? = null,
        complexityUpper: Int? = null
    ): RecordDevelopEncoderElitePlan {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val safeFps = fps.coerceAtLeast(1)
        val requested = (safeWidth.toDouble() * safeHeight.toDouble() * safeFps.toDouble() *
            BITS_PER_PIXEL_PER_FRAME)
            .roundToLong()
            .coerceAtLeast(MINIMUM_BITRATE.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val lower = bitrateLower.coerceAtLeast(1)
        val upper = bitrateUpper.coerceAtLeast(lower)
        val target = requested.coerceIn(lower, upper)
        val complexity = if (complexityLower != null && complexityUpper != null) {
            complexityUpper.coerceAtLeast(complexityLower)
        } else {
            null
        }
        return RecordDevelopEncoderElitePlan(
            requestedBitrate = requested,
            targetBitrate = target,
            maximumBitrate = target,
            quality = ELITE_QUALITY,
            complexity = complexity,
            operatingRate = safeFps,
            maximumQp = ELITE_MAXIMUM_QP
        )
    }
}

internal data class RecordDevelopDecoderCandidate(
    val name: String,
    val hardwareAccelerated: Boolean,
    val vendor: Boolean,
    val supportsMain10: Boolean,
    val supportsP010: Boolean,
    val supportsRequestedGeometry: Boolean
)

/** No silent 8-bit decoder fallback: only a genuine Main10 + P010 path is eligible. */
internal object RecordDevelopDecoderQualityPolicy {
    fun select(candidates: List<RecordDevelopDecoderCandidate>): RecordDevelopDecoderCandidate? =
        candidates
            .asSequence()
            .filter { it.supportsMain10 && it.supportsP010 && it.supportsRequestedGeometry }
            .sortedWith(
                compareByDescending<RecordDevelopDecoderCandidate> { it.hardwareAccelerated }
                    .thenByDescending { it.vendor }
                    .thenBy { it.name }
            )
            .firstOrNull()

    fun requireOutput(
        expectedWidth: Int,
        expectedHeight: Int,
        actualWidth: Int,
        actualHeight: Int,
        p010: Boolean,
        strideBytes: Int,
        sliceHeight: Int
    ) {
        require(actualWidth == expectedWidth && actualHeight == expectedHeight) {
            "Decoder geometry changed ${actualWidth}x${actualHeight}; expected ${expectedWidth}x${expectedHeight}"
        }
        require(p010) { "Decoder output is not 10-bit P010" }
        require(strideBytes >= expectedWidth * Short.SIZE_BYTES) {
            "Decoder P010 stride $strideBytes is smaller than ${expectedWidth * Short.SIZE_BYTES}"
        }
        require(sliceHeight >= expectedHeight) {
            "Decoder slice height $sliceHeight is smaller than $expectedHeight"
        }
    }

    fun requireMonotonicPts(previousPtsUs: Long?, currentPtsUs: Long) {
        require(previousPtsUs == null || currentPtsUs > previousPtsUs) {
            "Decoder PTS is not strictly monotonic previous=$previousPtsUs current=$currentPtsUs"
        }
    }
}
