package com.neoproc.neoproccamx.pipeline

import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * PREDICTIVE THERMAL KALMAN FILTER
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Proactive thermal stability engine that predicts thermal events BEFORE
 * the OS triggers a throttle. Tracks the rate-of-change (slope) of SoC
 * temperature using a 1D Kalman filter.
 *
 * When a thermal event is predicted (temperature slope exceeds threshold),
 * the filter microscopically adjusts MediaCodec encoder complexity
 * (e.g., 10 → 8) to stabilize heat generation. Resolution and bitrate
 * remain locked to the user's preference.
 *
 * Architecture:
 *   PowerManager.thermalStatus → Kalman state update
 *       ↓
 *   Slope prediction → Encoder complexity adjustment
 *       ↓
 *   MediaCodec.setParameters(KEY_COMPLEXITY = adjusted)
 *
 * The filter uses:
 *   • State: [temperature, temperature_slope]
 *   • Process noise Q: tuned for SoC thermal dynamics (~0.1°C/s² variance)
 *   • Measurement noise R: tuned for Android thermal API quantization
 *   • Prediction horizon: 8 seconds (enough to preempt OS throttle)
 */
internal class PredictiveThermalKalmanFilter {

    companion object {
        private const val TAG = "ThermalKalman"

        // Thermal status → normalized temperature mapping
        // Android thermal API reports discrete levels, not continuous temperature.
        // We map to a normalized 0.0–1.0 scale for Kalman state estimation.
        private const val TEMP_NONE = 0.0f       // No thermal pressure
        private const val TEMP_LIGHT = 0.25f     // Light thermal load
        private const val TEMP_MODERATE = 0.50f  // Moderate — approaching limit
        private const val TEMP_SEVERE = 0.75f    // Severe — OS may throttle soon
        private const val TEMP_CRITICAL = 0.90f  // Critical — OS is throttling
        private const val TEMP_EMERGENCY = 1.0f  // Emergency — shutdown imminent

        // Prediction thresholds
        private const val SLOPE_WARNING = 0.015f  // °C/s normalized — start reducing
        private const val SLOPE_DANGER = 0.035f   // °C/s normalized — aggressive reduction
        private const val PREDICTION_HORIZON_SEC = 8.0f
        private const val PASSIVE_COOLDOWN_RATE = 0.004f
        private const val COOLDOWN_TARGET_TEMP = TEMP_LIGHT
        private const val MAX_COOLDOWN_ESTIMATE_SEC = 900

        // Encoder complexity bounds
        const val COMPLEXITY_MAX = 10
        const val COMPLEXITY_MIN = 4
        const val COMPLEXITY_DEFAULT = 10
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // Kalman Filter State
    // ═════════════════════════════════════════════════════════════════════════════

    // State vector: [temperature, temperature_slope]
    private var x0 = 0.0f  // Estimated temperature (normalized 0–1)
    private var x1 = 0.0f  // Estimated temperature slope (per second)

    // Error covariance matrix P (2×2, stored as 4 elements)
    private var p00 = 1.0f
    private var p01 = 0.0f
    private var p10 = 0.0f
    private var p11 = 1.0f

    // Process noise covariance Q
    private val q00 = 0.001f   // Temperature variance
    private val q01 = 0.0f
    private val q10 = 0.0f
    private val q11 = 0.005f   // Slope variance (thermal dynamics)

    // Measurement noise covariance R
    private val r = 0.04f      // Quantization noise of Android thermal API

    // Timing
    private var lastUpdateTimeMs = 0L
    private var initialized = false

    // Output state (thread-safe)
    private val _decision = AtomicReference(ThermalDecision())
    private val active = AtomicBoolean(false)

    // ═════════════════════════════════════════════════════════════════════════════
    // Thermal Decision Output
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Current thermal decision with encoder complexity adjustment.
     */
    enum class ThermalTrend {
        HEATING,
        STABLE,
        COOLING
    }

    data class ThermalDecision(
        /** Recommended encoder complexity (4–10). */
        val encoderComplexity: Int = COMPLEXITY_DEFAULT,
        /** Estimated normalized temperature (0.0–1.0). */
        val estimatedTemperature: Float = 0f,
        /** Estimated temperature slope (per second). */
        val estimatedSlope: Float = 0f,
        /** Predicted temperature in [PREDICTION_HORIZON_SEC] seconds. */
        val predictedTemperature: Float = 0f,
        /** Whether proactive mitigation is active. */
        val mitigationActive: Boolean = false,
        /** Thermal severity level (0=none, 1=light, 2=moderate, 3=severe). */
        val severity: Int = 0,
        /** Whether resolution/bitrate should remain locked. */
        val resolutionLocked: Boolean = true,
        /** Whether bitrate should remain locked. */
        val bitrateLocked: Boolean = true,
        /** Approximate cooldown ETA to return near light thermal pressure. */
        val cooldownEstimateSec: Int? = null,
        /** Thermal trend direction inferred from filtered slope. */
        val trend: ThermalTrend = ThermalTrend.STABLE
    )

    val decision: ThermalDecision get() = _decision.get()

    // ═════════════════════════════════════════════════════════════════════════════
    // Update — Call from thermal monitoring loop (~every 1–2 seconds)
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Update the Kalman filter with a new thermal measurement.
     *
     * @param thermalStatus Android PowerManager thermal status constant.
     * @return Updated thermal decision.
     */
    fun update(thermalStatus: Int): ThermalDecision {
        val measurement = thermalStatusToNormalized(thermalStatus)
        return updateWithMeasurement(measurement)
    }

    /**
     * Update from a thermal label string (e.g., "MODERATE", "THROTTLING").
     */
    fun updateFromLabel(label: String): ThermalDecision {
        val measurement = when (label.uppercase().trim()) {
            "", "NONE" -> TEMP_NONE
            "LIGHT" -> TEMP_LIGHT
            "MODERATE" -> TEMP_MODERATE
            "THROTTLING", "SEVERE" -> TEMP_SEVERE
            "CRITICAL" -> TEMP_CRITICAL
            "EMERGENCY", "SHUTDOWN" -> TEMP_EMERGENCY
            else -> TEMP_NONE
        }
        return updateWithMeasurement(measurement)
    }

    /**
     * Core Kalman update with a normalized temperature measurement.
     */
    @Synchronized
    fun updateWithMeasurement(measurement: Float): ThermalDecision {
        val nowMs = SystemClock.elapsedRealtime()

        if (!initialized) {
            // Initialize state
            x0 = measurement
            x1 = 0.0f
            lastUpdateTimeMs = nowMs
            initialized = true
            active.set(true)
            val d = computeDecision()
            _decision.set(d)
            return d
        }

        val dtMs = (nowMs - lastUpdateTimeMs).coerceAtLeast(1L)
        val dt = dtMs.toFloat() / 1000f  // seconds
        lastUpdateTimeMs = nowMs

        // ── PREDICT ──────────────────────────────────────────────────────
        // State transition: x_k|k-1 = F * x_k-1|k-1
        // F = [[1, dt], [0, 1]]
        val xPred0 = x0 + x1 * dt
        val xPred1 = x1

        // Covariance prediction: P_k|k-1 = F * P * F^T + Q
        val pPred00 = p00 + dt * (p10 + p01) + dt * dt * p11 + q00
        val pPred01 = p01 + dt * p11 + q01
        val pPred10 = p10 + dt * p11 + q10
        val pPred11 = p11 + q11

        // ── UPDATE ───────────────────────────────────────────────────────
        // Innovation: y = z - H * x_pred (H = [1, 0])
        val innovation = measurement - xPred0

        // Innovation covariance: S = H * P_pred * H^T + R
        val s = pPred00 + r

        // Kalman gain: K = P_pred * H^T / S
        val k0 = pPred00 / s
        val k1 = pPred10 / s

        // Updated state: x = x_pred + K * y
        x0 = xPred0 + k0 * innovation
        x1 = xPred1 + k1 * innovation

        // Updated covariance: P = (I - K*H) * P_pred
        p00 = (1f - k0) * pPred00
        p01 = (1f - k0) * pPred01
        p10 = -k1 * pPred00 + pPred10
        p11 = -k1 * pPred01 + pPred11

        // Clamp state to physical bounds
        x0 = x0.coerceIn(0f, 1.0f)
        x1 = x1.coerceIn(-0.1f, 0.1f)  // Max 10% per second change

        val d = computeDecision()
        _decision.set(d)
        return d
    }

    /**
     * Reset the filter to initial state.
     */
    @Synchronized
    fun reset() {
        x0 = 0f; x1 = 0f
        p00 = 1f; p01 = 0f; p10 = 0f; p11 = 1f
        initialized = false
        active.set(false)
        _decision.set(ThermalDecision())
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // Decision Logic
    // ═════════════════════════════════════════════════════════════════════════════

    private fun computeDecision(): ThermalDecision {
        // Predict temperature at horizon
        val predictedTemp = (x0 + x1 * PREDICTION_HORIZON_SEC).coerceIn(0f, 1f)
        val slope = x1
        val trend = when {
            slope > SLOPE_WARNING * 0.5f -> ThermalTrend.HEATING
            slope < -(SLOPE_WARNING * 0.5f) -> ThermalTrend.COOLING
            else -> ThermalTrend.STABLE
        }

        // Determine severity from current + predicted state
        val severity = when {
            predictedTemp >= TEMP_CRITICAL || x0 >= TEMP_SEVERE -> 3
            predictedTemp >= TEMP_SEVERE || x0 >= TEMP_MODERATE -> 2
            predictedTemp >= TEMP_MODERATE || slope > SLOPE_WARNING -> 1
            else -> 0
        }

        // Compute encoder complexity adjustment
        // Key principle: ONLY reduce encoder complexity. Never touch resolution/bitrate.
        val complexity = when {
            // Emergency: minimum complexity to prevent shutdown
            x0 >= TEMP_CRITICAL -> COMPLEXITY_MIN
            // Severe + rising: aggressive reduction
            severity >= 3 || (x0 >= TEMP_SEVERE && slope > 0f) ->
                COMPLEXITY_MIN + 1  // = 5
            // Moderate + fast rise: moderate reduction
            severity >= 2 && slope > SLOPE_DANGER ->
                COMPLEXITY_MIN + 2  // = 6
            // Moderate or predicted severe: gentle reduction
            severity >= 2 || (slope > SLOPE_WARNING && predictedTemp > TEMP_MODERATE) ->
                COMPLEXITY_MAX - 2  // = 8
            // Light thermal pressure with positive slope: minimal reduction
            severity >= 1 && slope > SLOPE_WARNING ->
                COMPLEXITY_MAX - 1  // = 9
            // No thermal pressure: full complexity
            else -> COMPLEXITY_MAX
        }

        val mitigationActive = complexity < COMPLEXITY_MAX
        val cooldownEstimateSec = estimateCooldownSeconds(
            estimatedTemp = x0,
            predictedTemp = predictedTemp,
            slope = slope,
            severity = severity
        )

        if (mitigationActive) {
            Log.d(TAG, "Thermal mitigation: complexity=$complexity, " +
                    "temp=${String.format(Locale.US, "%.3f", x0)}, " +
                    "slope=${String.format(Locale.US, "%.4f", slope)}/s, " +
                    "predicted=${String.format(Locale.US, "%.3f", predictedTemp)}, " +
                    "cooldown=${cooldownEstimateSec?.let { "${it}s" } ?: "n/a"}")
        }

        return ThermalDecision(
            encoderComplexity = complexity,
            estimatedTemperature = x0,
            estimatedSlope = slope,
            predictedTemperature = predictedTemp,
            mitigationActive = mitigationActive,
            severity = severity,
            resolutionLocked = true,   // NEVER reduce resolution
            bitrateLocked = true,      // NEVER reduce bitrate
            cooldownEstimateSec = cooldownEstimateSec,
            trend = trend
        )
    }

    private fun estimateCooldownSeconds(
        estimatedTemp: Float,
        predictedTemp: Float,
        slope: Float,
        severity: Int
    ): Int? {
        if (severity <= 0 && estimatedTemp <= COOLDOWN_TARGET_TEMP) {
            return 0
        }
        val overshoot = (maxOf(estimatedTemp, predictedTemp) - COOLDOWN_TARGET_TEMP).coerceAtLeast(0f)
        if (overshoot <= 0f) {
            return 0
        }
        val effectiveCoolingRate = when {
            slope < -0.001f -> -slope
            else -> PASSIVE_COOLDOWN_RATE
        }
        if (effectiveCoolingRate <= 0f) return null
        val estimateSec = (overshoot / effectiveCoolingRate).toInt().coerceAtLeast(1)
        return estimateSec.coerceAtMost(MAX_COOLDOWN_ESTIMATE_SEC)
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════════

    private fun thermalStatusToNormalized(status: Int): Float = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> TEMP_NONE
        PowerManager.THERMAL_STATUS_LIGHT -> TEMP_LIGHT
        PowerManager.THERMAL_STATUS_MODERATE -> TEMP_MODERATE
        PowerManager.THERMAL_STATUS_SEVERE -> TEMP_SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL -> TEMP_CRITICAL
        PowerManager.THERMAL_STATUS_EMERGENCY -> TEMP_EMERGENCY
        PowerManager.THERMAL_STATUS_SHUTDOWN -> TEMP_EMERGENCY
        else -> TEMP_NONE
    }

    /**
     * Diagnostic string for telemetry overlay.
     */
    fun diagnosticSummary(): String {
        val d = decision
        return "Thermal: T=${String.format(Locale.US, "%.2f", d.estimatedTemperature)}, " +
                "ΔT=${String.format(Locale.US, "%.3f", d.estimatedSlope)}/s, " +
                "pred=${String.format(Locale.US, "%.2f", d.predictedTemperature)}, " +
                "complexity=${d.encoderComplexity}, " +
                "mitigation=${if (d.mitigationActive) "ON" else "OFF"}, " +
                "trend=${d.trend.name.lowercase()}, " +
                "cooldown=${d.cooldownEstimateSec?.let { "${it}s" } ?: "n/a"}"
    }
}
