package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectiveIsoRequestPolicyTest {

    @Test
    fun effectiveUpperIso_usesPostRawBoostRange() {
        assertEquals(102368, EffectiveIsoRequestPolicy.effectiveUpperIso(3200, 3199))
        assertEquals(3200, EffectiveIsoRequestPolicy.effectiveUpperIso(3200, 100))
    }

    @Test
    fun resolve_usesSensorIsoWithoutBoostInsideAnalogRange() {
        val request = EffectiveIsoRequestPolicy.resolve(
            requestedIso = 1600,
            sensorLowerIso = 72,
            sensorUpperIso = 3200,
            boostLower = 100,
            boostUpper = 3199
        )

        assertEquals(1600, request.sensorIso)
        assertEquals(100, request.postRawSensitivityBoost)
        assertEquals(1600, request.effectiveIso)
    }

    @Test
    fun resolve_usesPostRawBoostAboveAnalogRange() {
        val request = EffectiveIsoRequestPolicy.resolve(
            requestedIso = 51200,
            sensorLowerIso = 72,
            sensorUpperIso = 3200,
            boostLower = 100,
            boostUpper = 3199
        )

        assertEquals(3200, request.sensorIso)
        assertEquals(1600, request.postRawSensitivityBoost)
        assertEquals(51200, request.effectiveIso)
    }

    @Test
    fun resolve_clampsToMaximumEffectiveIso() {
        val request = EffectiveIsoRequestPolicy.resolve(
            requestedIso = 409600,
            sensorLowerIso = 72,
            sensorUpperIso = 3200,
            boostLower = 100,
            boostUpper = 3199
        )

        assertEquals(3200, request.sensorIso)
        assertEquals(3199, request.postRawSensitivityBoost)
        assertEquals(102368, request.effectiveIso)
    }
}
