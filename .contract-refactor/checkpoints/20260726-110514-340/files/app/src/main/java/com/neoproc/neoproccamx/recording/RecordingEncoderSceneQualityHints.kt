package com.neoproc.neoproccamx.recording

import java.util.Locale

internal data class RecordingEncoderSceneQualityHints(
    val sceneComplexity: Float = 0f,
    val estimatedLux: Float? = null,
    val droppedFrames: Int = 0,
    val thermalSeverity: Int = 0,
    val motionRisk: Float = 0f,
    val noiseRisk: Float = 0f,
    val sourceQualityTier: String = DEFAULT_SOURCE_QUALITY_TIER,
    val sourceQualityBudgetMultiplier: Double = 1.0,
    val antiMotionBlurEnabled: Boolean = false
) {
    val lowLight: Boolean
        get() = estimatedLux?.let { it < LOW_LIGHT_LUX_THRESHOLD } == true

    val thermalDowngradeActive: Boolean
        get() = thermalSeverity >= THERMAL_DOWNGRADE_SEVERITY

    val hasSceneSignal: Boolean
        get() =
            sceneComplexity > 0f ||
                estimatedLux != null ||
                droppedFrames > 0 ||
                thermalSeverity > 0 ||
                motionRisk > 0f ||
                noiseRisk > 0f ||
                sourceQualityTier != DEFAULT_SOURCE_QUALITY_TIER ||
                sourceQualityBudgetMultiplier > 1.0 ||
                antiMotionBlurEnabled

    companion object {
        val EMPTY = RecordingEncoderSceneQualityHints()

        fun fromMetadata(description: String): RecordingEncoderSceneQualityHints {
            val values = description
                .split(';')
                .asSequence()
                .map { it.trim() }
                .filter { it.contains('=') }
                .associate { item ->
                    item.substringBefore('=').trim() to item.substringAfter('=').trim()
                }
            if (values.isEmpty()) return EMPTY

            val edge = values.floatValue("edge")
            val dynamicRange = values.floatValue("dynamicRange")
            val motion = values.floatValue("motion")
            val highlight = values.floatValue("highlight")
            val motionRisk = values.firstFloatValue(
                "motionRisk",
                "v20ProcessedVideoMotionRisk",
                "v20MotionRisk"
            ).sanitizedRisk()
            val noiseRisk = values.firstFloatValue(
                "noiseRisk",
                "v20ProcessedVideoNoiseRisk",
                "v20NoiseRisk"
            ).sanitizedRisk()
            val sourceQualityTier = values.firstStringValue(
                "sourceQualityTier",
                "v20ProcessedVideoSourceQualityTier",
                "v20SourceQualityRequestTier"
            )?.takeIf { it.isNotBlank() } ?: DEFAULT_SOURCE_QUALITY_TIER
            val sourceQualityBudgetMultiplier = values.firstDoubleValue(
                "sourceQualityBudgetMultiplier",
                "v20ProcessedVideoSourceQualityBudgetMultiplier",
                "v20BitratePressureMultiplier"
            ).sanitizedBudgetMultiplier()
            val antiMotionBlurEnabled = values.firstBooleanValue(
                "antiMotionBlurEnabled",
                "v20AntiMotionBlurEnabled",
                "v20AntiMotionBlurRequested",
                "v20AntiMotionBlurBudgetApplied"
            )
            val complexity = sceneComplexity(
                edge = edge,
                dynamicRange = dynamicRange,
                motion = motion,
                highlight = highlight
            )
            return RecordingEncoderSceneQualityHints(
                sceneComplexity = complexity,
                estimatedLux = values.floatValue("lux"),
                droppedFrames = values.intValue("dropEstimate").coerceAtLeast(0),
                thermalSeverity = thermalSeverity(values["thermalLabel"]),
                motionRisk = motionRisk,
                noiseRisk = noiseRisk,
                sourceQualityTier = sourceQualityTier,
                sourceQualityBudgetMultiplier = sourceQualityBudgetMultiplier,
                antiMotionBlurEnabled = antiMotionBlurEnabled
            )
        }

        private fun sceneComplexity(
            edge: Float?,
            dynamicRange: Float?,
            motion: Float?,
            highlight: Float?
        ): Float {
            if (edge == null && dynamicRange == null && motion == null && highlight == null) return 0f
            return (
                (edge ?: 0f).coerceIn(0f, 1f) * 0.35f +
                    (dynamicRange ?: 0f).coerceIn(0f, 1f) * 0.35f +
                    (motion ?: 0f).coerceIn(0f, 1f) * 0.20f +
                    (highlight ?: 0f).coerceIn(0f, 1f) * 0.10f
                ).coerceIn(0f, 1f)
        }

        private fun Map<String, String>.floatValue(key: String): Float? =
            get(key)?.replace(',', '.')?.toFloatOrNull()

        private fun Map<String, String>.firstFloatValue(vararg keys: String): Float? =
            keys.firstNotNullOfOrNull { key -> floatValue(key) }

        private fun Map<String, String>.firstDoubleValue(vararg keys: String): Double? =
            keys.firstNotNullOfOrNull { key ->
                get(key)?.replace(',', '.')?.toDoubleOrNull()
            }

        private fun Map<String, String>.firstStringValue(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { key -> get(key)?.trim() }

        private fun Map<String, String>.firstBooleanValue(vararg keys: String): Boolean =
            keys.firstNotNullOfOrNull { key ->
                when (get(key)?.trim()?.lowercase(Locale.US)) {
                    "true", "1", "yes", "on" -> true
                    "false", "0", "no", "off" -> false
                    else -> null
                }
            } ?: false

        private fun Float?.sanitizedRisk(): Float =
            if (this != null && isFinite()) coerceIn(0f, 1f) else 0f

        private fun Double?.sanitizedBudgetMultiplier(): Double =
            if (this != null && isFinite()) coerceIn(1.0, MAX_SOURCE_QUALITY_BUDGET_MULTIPLIER) else 1.0

        private fun Map<String, String>.intValue(key: String): Int =
            get(key)?.toIntOrNull() ?: 0

        private fun thermalSeverity(label: String?): Int =
            when (label?.trim()?.uppercase(Locale.US).orEmpty()) {
                "", "NONE", "NOMINAL", "UNKNOWN" -> 0
                "LIGHT" -> 1
                "MODERATE" -> 2
                "SEVERE", "THROTTLING" -> 4
                "CRITICAL" -> 5
                "EMERGENCY", "SHUTDOWN" -> 6
                else -> 0
            }

        private const val LOW_LIGHT_LUX_THRESHOLD = 12f
        private const val THERMAL_DOWNGRADE_SEVERITY = 4
        private const val DEFAULT_SOURCE_QUALITY_TIER = "STANDARD"
        private const val MAX_SOURCE_QUALITY_BUDGET_MULTIPLIER = 1.85
    }
}

internal data class RecordingEncoderQualityTelemetry(
    val sceneComplexity: Float,
    val estimatedLux: Float?,
    val sceneComplexityBoostApplied: Boolean,
    val lowLightBoostApplied: Boolean,
    val hdrOrLogBoostApplied: Boolean,
    val bitrateMultiplier: Float,
    val capabilityCheck: String,
    val thermalDowngradeActive: Boolean,
    val droppedFrames: Int,
    val logLines: List<String>
) {
    companion object {
        val EMPTY = RecordingEncoderQualityTelemetry(
            sceneComplexity = 0f,
            estimatedLux = null,
            sceneComplexityBoostApplied = false,
            lowLightBoostApplied = false,
            hdrOrLogBoostApplied = false,
            bitrateMultiplier = 1f,
            capabilityCheck = "TEN_BIT_NOT_REQUESTED",
            thermalDowngradeActive = false,
            droppedFrames = 0,
            logLines = emptyList()
        )
    }
}
