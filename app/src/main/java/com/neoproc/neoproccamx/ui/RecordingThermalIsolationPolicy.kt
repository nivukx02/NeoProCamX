package com.neoproc.neoproccamx.ui

/**
 * Protects the camera/encoder deadline when Android reports sustained thermal pressure.
 *
 * This policy is deliberately restricted to secondary work. It cannot alter the camera request,
 * encoder dimensions, target frame rate, bitrate, bit depth, colour transform, LUT, audio, or RAW
 * payload. Android and the vendor HAL retain final authority over clocks and sensor delivery, but
 * removing optional app work before CRITICAL gives that mandatory pixel path the largest available
 * thermal and scheduling budget.
 */
internal data class RecordingThermalIsolationDecision(
    val active: Boolean,
    val suspendAiInference: Boolean,
    val suspendSceneAnalysis: Boolean,
    val suspendCameraAnalysisTarget: Boolean,
    val suppressProcessMemoryTelemetry: Boolean,
    val reason: String
)

internal object RecordingThermalIsolationPolicy {
    /** Begin proactively at MODERATE so mitigation is active before Android reaches CRITICAL. */
    private const val PROACTIVE_ISOLATION_SEVERITY = 2

    private val INACTIVE = RecordingThermalIsolationDecision(
        active = false,
        suspendAiInference = false,
        suspendSceneAnalysis = false,
        suspendCameraAnalysisTarget = false,
        suppressProcessMemoryTelemetry = false,
        reason = "inactive"
    )

    fun resolve(
        recordingActive: Boolean,
        thermalSeverity: Int
    ): RecordingThermalIsolationDecision {
        if (!recordingActive || thermalSeverity < PROACTIVE_ISOLATION_SEVERITY) return INACTIVE
        return RecordingThermalIsolationDecision(
            active = true,
            suspendAiInference = true,
            suspendSceneAnalysis = true,
            suspendCameraAnalysisTarget = true,
            suppressProcessMemoryTelemetry = true,
            reason = "recording-thermal-isolation-severity-${thermalSeverity.coerceAtLeast(0)}"
        )
    }
}
