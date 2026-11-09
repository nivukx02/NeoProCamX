package com.neoproc.neoproccamx.camera

import kotlin.math.roundToInt

internal data class RawSensorCalibrationPlan(
    val blackLevel: Int,
    val whiteLevel: Int,
    val usedFallbackBlackLevel: Boolean,
    val diagnostics: List<String>
)

internal object RawSensorCalibrationPolicy {
    private const val DEFAULT_WHITE_LEVEL = 4095
    private const val FALLBACK_BLACK_LEVEL_RATIO = 1f / 64f

    fun resolve(
        whiteLevel: Int?,
        dynamicBlackLevel: FloatArray?,
        blackLevelOffsets: IntArray?,
        strictHighEnd: Boolean
    ): RawSensorCalibrationPlan {
        val safeWhite = (whiteLevel ?: DEFAULT_WHITE_LEVEL).coerceIn(256, 65_535)
        val validDynamicOffsets = dynamicBlackLevel
            ?.filter { it.isFinite() && it >= 0f && it < safeWhite.toFloat() }
            .orEmpty()
        val validOffsets = blackLevelOffsets
            ?.filter { it >= 0 && it < safeWhite }
            .orEmpty()

        val dynamicMetadataBlackLevel =
            if (
                dynamicBlackLevel != null &&
                    validDynamicOffsets.size == dynamicBlackLevel.size &&
                    validDynamicOffsets.isNotEmpty()
            ) {
                validDynamicOffsets.average().roundToInt().coerceIn(0, safeWhite - 1)
            } else {
                null
            }
        val metadataBlackLevel = if (blackLevelOffsets != null && validOffsets.size == blackLevelOffsets.size && validOffsets.isNotEmpty()) {
            validOffsets.average().roundToInt().coerceIn(0, safeWhite - 1)
        } else {
            null
        }
        val fallbackBlackLevel = (safeWhite * FALLBACK_BLACK_LEVEL_RATIO)
            .roundToInt()
            .coerceIn(1, safeWhite - 1)
        val blackLevel = dynamicMetadataBlackLevel ?: metadataBlackLevel ?: fallbackBlackLevel

        val diagnostics = buildList {
            if (dynamicMetadataBlackLevel != null) {
                add("RAW black level calibrated from dynamic per-frame camera metadata")
            } else if (metadataBlackLevel != null) {
                add("RAW black level calibrated from camera metadata offsets")
            } else if (strictHighEnd) {
                add("RAW black level using scaled fallback because complete metadata is unavailable")
            } else {
                add("RAW black level using compatibility fallback")
            }
            if (whiteLevel == null) {
                add("RAW white level missing; using default $DEFAULT_WHITE_LEVEL")
            }
        }

        return RawSensorCalibrationPlan(
            blackLevel = blackLevel,
            whiteLevel = safeWhite,
            usedFallbackBlackLevel = dynamicMetadataBlackLevel == null && metadataBlackLevel == null,
            diagnostics = diagnostics
        )
    }
}
