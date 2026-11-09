package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualControlPresetRuntimePolicyTest {
    private val proIntent = ManualControlSnapshot(
        iso = ManualControlValue.Manual(800),
        exposureNs = ManualControlValue.Manual(20_000_000L),
        whiteBalanceKelvin = ManualControlValue.Manual(4_300),
        whiteBalanceTint = ManualControlValue.Manual(-12),
        focusDistance = ManualControlValue.Manual(2.5f),
        exposureCompensation = ManualControlValue.Manual(2f)
    )

    @Test
    fun `Video Pro Raw Pro sequence preserves intent and applies expected effective state`() {
        val videoEffective = ManualControlPresetRuntimePolicy.effectiveSnapshot(
            CameraModePreset.VIDEO,
            proIntent
        )
        val firstProEffective = ManualControlPresetRuntimePolicy.effectiveSnapshot(
            CameraModePreset.PRO,
            proIntent
        )
        val rawEffective = ManualControlPresetRuntimePolicy.effectiveSnapshot(
            CameraModePreset.RAW_MODE,
            proIntent
        )
        val secondProEffective = ManualControlPresetRuntimePolicy.effectiveSnapshot(
            CameraModePreset.PRO,
            proIntent
        )

        assertTrue(videoEffective.isFullyAuto)
        assertEquals(ManualControlValue.Manual(0f), videoEffective.exposureCompensation)
        assertEquals(proIntent, firstProEffective)
        assertTrue(rawEffective.isFullyAuto)
        assertEquals(ManualControlValue.Manual(0f), rawEffective.exposureCompensation)
        assertEquals(proIntent, secondProEffective)
        assertEquals(ManualControlValue.Manual(800), proIntent.iso)
        assertEquals(ManualControlValue.Manual(2.5f), proIntent.focusDistance)
    }

    @Test
    fun `recreated persisted Pro snapshot restores every manual axis`() {
        val recreatedIntent = ManualControlSnapshot(
            iso = ManualControlValue.Manual(1_250),
            exposureNs = ManualControlValue.Manual(10_000_000L),
            whiteBalanceKelvin = ManualControlValue.Manual(5_600),
            whiteBalanceTint = ManualControlValue.Manual(8),
            focusDistance = ManualControlValue.Manual(1.25f),
            exposureCompensation = ManualControlValue.Manual(-1f)
        )

        val effective = ManualControlPresetRuntimePolicy.effectiveSnapshot(
            CameraModePreset.PRO,
            recreatedIntent
        )

        assertEquals(recreatedIntent, effective)
        assertEquals(1_250, (effective.iso as ManualControlValue.Manual).toInt())
        assertEquals(10_000_000L, (effective.exposureNs as ManualControlValue.Manual).toLong())
        assertEquals(5_600, (effective.whiteBalanceKelvin as ManualControlValue.Manual).toInt())
        assertEquals(8, (effective.whiteBalanceTint as ManualControlValue.Manual).toInt())
        assertEquals(1.25f, (effective.focusDistance as ManualControlValue.Manual).toFloat())
        assertEquals(-1, (effective.exposureCompensation as ManualControlValue.Manual).toInt())
    }
}
