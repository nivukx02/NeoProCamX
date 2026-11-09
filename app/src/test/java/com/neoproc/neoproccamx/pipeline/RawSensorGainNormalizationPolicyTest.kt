package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSensorGainNormalizationPolicyTest {

    @Test
    fun baseIsoAndUnityPostRawBoost_resolveToUnityGain() {
        val decision = RawSensorGainNormalizationPolicy.resolve(
            sensorSensitivityIso = 100,
            postRawSensitivityBoostPercent = 100,
            advertisedMinimumSensitivityIso = 100,
            advertisedMaximumAnalogSensitivityIso = 800
        )

        assertEquals(1f, decision.analogGain, 0f)
        assertEquals(1f, decision.digitalGain, 0f)
        assertEquals(1f, decision.totalOutputGain, 0f)
        assertTrue(decision.usedAdvertisedBaseSensitivity)
        assertTrue(decision.usedAdvertisedMaxAnalogSensitivity)
    }

    @Test
    fun isoIsSplitAtAdvertisedMaximumAnalogSensitivity() {
        val decision = RawSensorGainNormalizationPolicy.resolve(
            sensorSensitivityIso = 1600,
            postRawSensitivityBoostPercent = 100,
            advertisedMinimumSensitivityIso = 100,
            advertisedMaximumAnalogSensitivityIso = 800
        )

        assertEquals(8f, decision.analogGain, 0f)
        assertEquals(2f, decision.digitalGain, 0f)
        assertEquals(16f, decision.totalOutputGain, 0f)
    }

    @Test
    fun postRawBoostUsesPercentUnitsInsteadOfBeingTreatedAsRawMultiplier() {
        val decision = RawSensorGainNormalizationPolicy.resolve(
            sensorSensitivityIso = 400,
            postRawSensitivityBoostPercent = 150,
            advertisedMinimumSensitivityIso = 100,
            advertisedMaximumAnalogSensitivityIso = 800
        )

        assertEquals(4f, decision.analogGain, 0f)
        assertEquals(1.5f, decision.digitalGain, 0f)
        assertEquals(6f, decision.totalOutputGain, 0f)
    }

    @Test
    fun missingCharacteristicsDoNotInventAnAbsoluteGain() {
        val decision = RawSensorGainNormalizationPolicy.resolve(
            sensorSensitivityIso = 640,
            postRawSensitivityBoostPercent = null,
            advertisedMinimumSensitivityIso = null,
            advertisedMaximumAnalogSensitivityIso = null
        )

        assertEquals(1f, decision.analogGain, 0f)
        assertEquals(1f, decision.digitalGain, 0f)
        assertFalse(decision.usedAdvertisedBaseSensitivity)
        assertFalse(decision.usedAdvertisedMaxAnalogSensitivity)
    }
}
