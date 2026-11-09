package com.neoproc.neoproccamx.camera

internal data class EffectiveIsoRequest(
    val sensorIso: Int,
    val postRawSensitivityBoost: Int,
    val effectiveIso: Int
)

internal object EffectiveIsoRequestPolicy {

    fun effectiveUpperIso(sensorUpperIso: Int, boostUpper: Int): Int {
        val normalizedBoostUpper = boostUpper.coerceAtLeast(100)
        return ((sensorUpperIso.toLong().coerceAtLeast(1L) * normalizedBoostUpper) / 100L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun resolve(
        requestedIso: Int,
        sensorLowerIso: Int,
        sensorUpperIso: Int,
        boostLower: Int,
        boostUpper: Int
    ): EffectiveIsoRequest {
        val lowerIso = sensorLowerIso.coerceAtMost(sensorUpperIso)
        val upperIso = sensorUpperIso.coerceAtLeast(sensorLowerIso)
        val normalizedBoostLower = boostLower.coerceAtMost(boostUpper).coerceAtLeast(1)
        val normalizedBoostUpper = boostUpper.coerceAtLeast(boostLower).coerceAtLeast(normalizedBoostLower)
        val defaultBoost = 100.coerceIn(normalizedBoostLower, normalizedBoostUpper)
        val maxEffectiveIso = effectiveUpperIso(upperIso, normalizedBoostUpper)
        val requested = requestedIso.coerceIn(lowerIso, maxEffectiveIso)

        if (requested <= upperIso) {
            return EffectiveIsoRequest(
                sensorIso = requested,
                postRawSensitivityBoost = defaultBoost,
                effectiveIso = requested
            )
        }

        val boost = (((requested.toLong() * 100L) + upperIso - 1L) / upperIso)
            .coerceIn(normalizedBoostLower.toLong(), normalizedBoostUpper.toLong())
            .toInt()
        val effective = ((upperIso.toLong() * boost) / 100L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

        return EffectiveIsoRequest(
            sensorIso = upperIso,
            postRawSensitivityBoost = boost,
            effectiveIso = effective
        )
    }
}
