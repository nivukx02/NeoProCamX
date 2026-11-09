package com.neoproc.neoproccamx.camera

/**
 * Admission policy for the high-rate Android motion sensors.
 *
 * Camera2 OIS metadata remains available independently through capture results. The separate
 * SENSOR_DELAY_FASTEST stream is started only when the dormant app-owned gyro EIS/MFSR consumer is
 * explicitly enabled. This prevents an unused sensor callback stream from competing with a 4K
 * encoder while retaining immediate, full-rate activation for the feature that needs it.
 */
internal object CameraMotionTelemetryDemandPolicy {
    fun shouldCollect(gyroEisOrMfsrConsumerEnabled: Boolean): Boolean =
        gyroEisOrMfsrConsumerEnabled
}
