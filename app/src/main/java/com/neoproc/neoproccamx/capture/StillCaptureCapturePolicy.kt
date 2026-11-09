package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs

internal object StillCaptureCapturePolicy {
    private const val MIN_HDR_EV = -4
    private const val MAX_HDR_EV = 4
    private const val MIN_HDR_FRAME_COUNT = 3
    private const val DEFAULT_MAX_HDR_FRAME_COUNT = 7
    private const val STRICT_MAX_HDR_FRAME_COUNT = 9
    private val supplementalHdrBracketPattern = listOf(0, -1, 1, -2, 2, -3, 3, -4, 4)

    enum class SingleCaptureRawMode {
        JPEG_ONLY,
        NATIVE_RAW_COMPANION,
        PROCESSED_SOURCE_FALLBACK
    }

    fun resolveSingleCaptureRawMode(
        rawRequested: Boolean,
        supportsRaw: Boolean
    ): SingleCaptureRawMode =
        when {
            !rawRequested -> SingleCaptureRawMode.JPEG_ONLY
            supportsRaw -> SingleCaptureRawMode.NATIVE_RAW_COMPANION
            else -> SingleCaptureRawMode.PROCESSED_SOURCE_FALLBACK
        }

    fun shouldCaptureRawCompanion(rawRequested: Boolean, supportsRaw: Boolean): Boolean =
        rawRequested && supportsRaw

    fun shouldCreateProcessedSourceCompanion(rawRequested: Boolean, supportsRaw: Boolean): Boolean =
        rawRequested && !supportsRaw

    fun resolveHdrBracketEvs(
        recommendedBracket: List<Int>?,
        hdrMode: String
    ): List<Int> {
        val frameBudget = resolveHdrFrameBudget(hdrMode)
        val modeDefault = defaultHdrBracket(hdrMode)
        val seed = recommendedBracket?.takeIf { it.isNotEmpty() } ?: modeDefault
        val normalized = normalizeHdrBracket(seed, frameBudget)
        if (normalized.size >= MIN_HDR_FRAME_COUNT &&
            normalized.any { it < 0 } &&
            normalized.any { it > 0 }
        ) {
            return normalized
        }
        return normalizeHdrBracket(seed + modeDefault, frameBudget)
    }

    fun buildSupplementalHdrBracket(missingCount: Int): List<Int> {
        if (missingCount <= 0) return emptyList()
        return buildList(missingCount.coerceAtMost(supplementalHdrBracketPattern.size)) {
            repeat(missingCount.coerceAtMost(supplementalHdrBracketPattern.size)) { index ->
                add(supplementalHdrBracketPattern[index])
            }
        }
    }

    private fun resolveHdrFrameBudget(hdrMode: String): Int =
        when {
            hdrMode.contains("FAST", ignoreCase = true) -> MIN_HDR_FRAME_COUNT
            hdrMode.contains("SMART", ignoreCase = true) -> 4
            hdrMode.contains("ULTRA", ignoreCase = true) || hdrMode.contains("MAX", ignoreCase = true) ->
                STRICT_MAX_HDR_FRAME_COUNT
            else -> if (HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly) 5 else 4
        }

    private fun defaultHdrBracket(hdrMode: String): List<Int> =
        when {
            hdrMode.contains("FAST", ignoreCase = true) -> listOf(0, -1, 1)
            hdrMode.contains("SMART", ignoreCase = true) -> listOf(0, -1, 1, -2)
            hdrMode.contains("ULTRA", ignoreCase = true) || hdrMode.contains("MAX", ignoreCase = true) -> {
                listOf(0, -1, 1, -2, 2, -3, 3, -4, 4)
            }
            else -> listOf(0, -1, 1, -2)
        }

    private fun normalizeHdrBracket(
        bracket: List<Int>,
        frameBudget: Int
    ): List<Int> {
        val budget = frameBudget.coerceIn(MIN_HDR_FRAME_COUNT, STRICT_MAX_HDR_FRAME_COUNT)
        return (bracket + 0)
            .map { it.coerceIn(MIN_HDR_EV, MAX_HDR_EV) }
            .distinct()
            .sortedWith(compareBy<Int>({ abs(it) }, { it }))
            .take(budget)
    }
}
