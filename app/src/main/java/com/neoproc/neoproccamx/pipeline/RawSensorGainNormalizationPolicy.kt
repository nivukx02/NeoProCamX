package com.neoproc.neoproccamx.pipeline

import kotlin.math.min

/**
 * Converts Camera2 ISO-domain metadata into dimensionless gain multipliers.
 *
 * SENSOR_SENSITIVITY is an ISO value, not a multiplier, and
 * CONTROL_POST_RAW_SENSITIVITY_BOOST uses 100 == 1x. Passing either value through verbatim made a
 * base-ISO frame appear to have thousands of times more gain, forcing downstream noise estimation
 * into its maximum clamp and destroying legitimate fine detail.
 */
internal object RawSensorGainNormalizationPolicy {
    private const val UNITY_POST_RAW_BOOST = 100

    data class Decision(
        val analogGain: Float,
        val digitalGain: Float,
        val totalOutputGain: Float,
        val baseSensitivityIso: Int,
        val sensorSensitivityIso: Int,
        val postRawSensitivityBoostPercent: Int,
        val usedAdvertisedBaseSensitivity: Boolean,
        val usedAdvertisedMaxAnalogSensitivity: Boolean
    )

    fun resolve(
        sensorSensitivityIso: Int?,
        postRawSensitivityBoostPercent: Int?,
        advertisedMinimumSensitivityIso: Int?,
        advertisedMaximumAnalogSensitivityIso: Int?
    ): Decision {
        val validSensorIso = sensorSensitivityIso?.takeIf { it > 0 }
        val validMinimumIso = advertisedMinimumSensitivityIso?.takeIf { it > 0 }
        // When the HAL omits its range, use the current ISO as the reference. This honestly resolves
        // an unknown absolute gain to unity instead of inventing an arbitrary ISO-100 base.
        val baseIso = validMinimumIso ?: validSensorIso ?: 1
        val sensorIso = (validSensorIso ?: baseIso).coerceAtLeast(baseIso)
        val validMaxAnalogIso = advertisedMaximumAnalogSensitivityIso
            ?.takeIf { it >= baseIso }
        val analogIso = min(sensorIso, validMaxAnalogIso ?: sensorIso)
        val analogGain = (analogIso.toDouble() / baseIso.toDouble())
            .toFloat()
            .takeIf { it.isFinite() && it >= 1f }
            ?: 1f
        val sensorDigitalGain = (sensorIso.toDouble() / analogIso.toDouble())
            .toFloat()
            .takeIf { it.isFinite() && it >= 1f }
            ?: 1f
        val boostPercent = postRawSensitivityBoostPercent
            ?.takeIf { it > 0 }
            ?: UNITY_POST_RAW_BOOST
        val postRawGain = (boostPercent.toDouble() / UNITY_POST_RAW_BOOST.toDouble())
            .toFloat()
            .takeIf { it.isFinite() && it > 0f }
            ?: 1f
        val digitalGain = sensorDigitalGain * postRawGain
        val totalGain = analogGain * digitalGain

        return Decision(
            analogGain = analogGain,
            digitalGain = digitalGain,
            totalOutputGain = totalGain,
            baseSensitivityIso = baseIso,
            sensorSensitivityIso = sensorIso,
            postRawSensitivityBoostPercent = boostPercent,
            usedAdvertisedBaseSensitivity = validMinimumIso != null,
            usedAdvertisedMaxAnalogSensitivity = validMaxAnalogIso != null
        )
    }
}
