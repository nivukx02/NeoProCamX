package com.neoproc.neoproccamx.camera

import kotlin.math.roundToInt

internal object PreviewMonitoringExposurePolicy {

    private const val AE_STEP_EV = 1f / 3f
    private const val LOG_MONITORING_LIFT_EV = 0.5f
    private const val HDR_MONITORING_LIFT_EV = 1f / 3f
    private const val RAW_MONITORING_LIFT_EV = 1f
    private const val MAX_MONITORING_BIAS_STEPS = 3

    fun resolveEvCompensationBoost(
        logEnabled: Boolean,
        hdrEnabled: Boolean,
        rawEnabled: Boolean,
        exposureCompensationUpperBound: Int
    ): Int {
        if (exposureCompensationUpperBound <= 0) return 0
        val desiredLiftEv = when {
            rawEnabled -> RAW_MONITORING_LIFT_EV
            logEnabled -> LOG_MONITORING_LIFT_EV
            hdrEnabled -> HDR_MONITORING_LIFT_EV
            else -> 0f
        }
        if (desiredLiftEv <= 0f) return 0
        val desiredSteps = (desiredLiftEv / AE_STEP_EV).roundToInt()
        return desiredSteps.coerceIn(
            minimumValue = 0,
            maximumValue = minOf(MAX_MONITORING_BIAS_STEPS, exposureCompensationUpperBound)
        )
    }
}
