package com.neoproc.neoproccamx.camera

internal enum class ExperimentalCameraInstability {
    HARD_FAILURE,
    SESSION_INSTABILITY,
    SEVERE_LAG,
    UNSTABLE_FPS,
    ENCODER_PRESSURE,
    THERMAL_DEGRADATION
}

internal data class ExperimentalCameraFallbackDecision(
    val shouldFallback: Boolean,
    val requiredElapsedMs: Long,
    val reason: String
)

internal object ExperimentalCameraFallbackPolicy {
    fun resolve(
        signal: ExperimentalCameraInstability,
        elapsedMs: Long
    ): ExperimentalCameraFallbackDecision {
        val requiredElapsedMs = when (signal) {
            ExperimentalCameraInstability.HARD_FAILURE -> 0L
            ExperimentalCameraInstability.SESSION_INSTABILITY -> 3000L
            ExperimentalCameraInstability.SEVERE_LAG -> 3000L
            ExperimentalCameraInstability.UNSTABLE_FPS -> 10000L
            ExperimentalCameraInstability.ENCODER_PRESSURE -> 10000L
            ExperimentalCameraInstability.THERMAL_DEGRADATION -> 30000L
        }
        return ExperimentalCameraFallbackDecision(
            shouldFallback = elapsedMs >= requiredElapsedMs,
            requiredElapsedMs = requiredElapsedMs,
            reason = signal.reasonLabel()
        )
    }

    private fun ExperimentalCameraInstability.reasonLabel(): String = when (this) {
        ExperimentalCameraInstability.HARD_FAILURE -> "Camera hard failure"
        ExperimentalCameraInstability.SESSION_INSTABILITY -> "Camera session instability"
        ExperimentalCameraInstability.SEVERE_LAG -> "Severe preview lag"
        ExperimentalCameraInstability.UNSTABLE_FPS -> "Unstable frame rate"
        ExperimentalCameraInstability.ENCODER_PRESSURE -> "Encoder pressure"
        ExperimentalCameraInstability.THERMAL_DEGRADATION -> "Thermal degradation"
    }
}
