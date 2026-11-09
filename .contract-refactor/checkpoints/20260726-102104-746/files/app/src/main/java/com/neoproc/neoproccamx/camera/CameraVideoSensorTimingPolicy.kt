package com.neoproc.neoproccamx.camera

internal data class CameraVideoSensorTimingPlan(
    val sensorFrameDurationNs: Long?
)

internal object CameraVideoSensorTimingPolicy {
    fun resolve(
        recordingActive: Boolean,
        strictHighEndEnabled: Boolean,
        supportsManualSensor: Boolean,
        manualSensorTimingActive: Boolean,
        fpsRequest: CameraFpsRequestPlan,
        maxFrameDurationNs: Long
    ): CameraVideoSensorTimingPlan {
        if (!recordingActive || !supportsManualSensor || manualSensorTimingActive) {
            return CameraVideoSensorTimingPlan(sensorFrameDurationNs = null)
        }
        val requestedFrameDurationNs = fpsRequest.frameDurationNs.coerceAtLeast(1L)
        val boundedFrameDurationNs = requestedFrameDurationNs.coerceAtMost(
            maxFrameDurationNs.coerceAtLeast(1L)
        )
        return CameraVideoSensorTimingPlan(sensorFrameDurationNs = boundedFrameDurationNs)
    }
}
