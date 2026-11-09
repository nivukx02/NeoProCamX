package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSensorCalibrationPolicyTest {

    @Test
    fun averagesAllBayerBlackLevelOffsetsInsteadOfUsingFixedFallback() {
        val plan = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 4095,
            dynamicBlackLevel = null,
            blackLevelOffsets = intArrayOf(60, 64, 68, 72),
            strictHighEnd = true
        )

        assertEquals(66, plan.blackLevel)
        assertEquals(4095, plan.whiteLevel)
        assertFalse(plan.usedFallbackBlackLevel)
        assertTrue(plan.diagnostics.any { it.contains("camera metadata") })
    }

    @Test
    fun fallbackBlackLevelScalesWithSensorWhiteLevel() {
        val tenBit = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 1023,
            dynamicBlackLevel = null,
            blackLevelOffsets = null,
            strictHighEnd = true
        )
        val twelveBit = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 4095,
            dynamicBlackLevel = null,
            blackLevelOffsets = null,
            strictHighEnd = true
        )

        assertEquals(16, tenBit.blackLevel)
        assertEquals(64, twelveBit.blackLevel)
        assertTrue(tenBit.usedFallbackBlackLevel)
        assertTrue(tenBit.diagnostics.any { it.contains("scaled fallback") })
    }

    @Test
    fun rejectsInvalidOffsetsAndFallsBackSafelyBelowWhiteLevel() {
        val plan = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 256,
            dynamicBlackLevel = null,
            blackLevelOffsets = intArrayOf(-4, 900, 12, 18),
            strictHighEnd = true
        )

        assertEquals(4, plan.blackLevel)
        assertEquals(256, plan.whiteLevel)
        assertTrue(plan.usedFallbackBlackLevel)
        assertTrue(plan.blackLevel < plan.whiteLevel)
    }

    @Test
    fun dynamicPerFrameBlackLevelOverridesStaticFallbackAndPattern() {
        val plan = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 4095,
            dynamicBlackLevel = floatArrayOf(71.5f, 72.5f, 69.5f, 70.5f),
            blackLevelOffsets = intArrayOf(64, 64, 64, 64),
            strictHighEnd = true
        )

        assertEquals(71, plan.blackLevel)
        assertFalse(plan.usedFallbackBlackLevel)
        assertTrue(plan.diagnostics.any { it.contains("dynamic") })
    }

    @Test
    fun dynamicBlackLevelMatchesReportedValueWithinOneCode() {
        val dynamic = floatArrayOf(63.2f, 64.8f, 63.9f, 64.1f)
        val plan = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 4095,
            dynamicBlackLevel = dynamic,
            blackLevelOffsets = intArrayOf(64, 64, 64, 64),
            strictHighEnd = true
        )

        // Subtraction must be exact to ±1 code vs the metadata-reported value.
        val reportedAverage = dynamic.average()
        assertTrue(Math.abs(plan.blackLevel - reportedAverage) <= 1.0)
        assertFalse(plan.usedFallbackBlackLevel)
    }

    @Test
    fun staticPatternBlackLevelMatchesReportedAverageWithinOneCode() {
        val offsets = intArrayOf(62, 64, 65, 63)
        val plan = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 4095,
            dynamicBlackLevel = null,
            blackLevelOffsets = offsets,
            strictHighEnd = true
        )

        val reportedAverage = offsets.average()
        assertTrue(Math.abs(plan.blackLevel - reportedAverage) <= 1.0)
        assertFalse(plan.usedFallbackBlackLevel)
    }

    @Test
    fun staticPatternBlackLevelRoundsHalfUpNotTruncated() {
        // Average is exactly 63.5 — locks round-half-up; a truncation regression would yield 63 and fail.
        val plan = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 4095,
            dynamicBlackLevel = null,
            blackLevelOffsets = intArrayOf(63, 63, 64, 64),
            strictHighEnd = true
        )

        assertEquals(64, plan.blackLevel)
        assertFalse(plan.usedFallbackBlackLevel)
    }

    @Test
    fun bggrPhysicalTileIsReorderedToSemanticRggbWithoutAveragingChannelsAway() {
        // BGGR physical order is B, Gb, Gr, R. The Vulkan shader contract is R, Gr, Gb, B.
        val plan = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 1023,
            dynamicBlackLevel = null,
            blackLevelOffsets = intArrayOf(12, 18, 25, 41),
            strictHighEnd = true,
            bayerPattern = 3
        )

        assertTrue(plan.blackLevelRggb.contentEquals(floatArrayOf(41f, 25f, 18f, 12f)))
        assertEquals(24, plan.blackLevel)
        assertFalse(plan.usedFallbackBlackLevel)
    }

    @Test
    fun dynamicBggrOffsetsAreAlreadySemanticAndRetainFractionalCalibration() {
        val plan = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 1023,
            dynamicBlackLevel = floatArrayOf(10.5f, 20.5f, 30.5f, 40.5f),
            blackLevelOffsets = null,
            strictHighEnd = true,
            bayerPattern = 3
        )

        assertTrue(plan.blackLevelRggb.contentEquals(floatArrayOf(10.5f, 20.5f, 30.5f, 40.5f)))
    }

    @Test
    fun whiteLevelClampNeverOverflowsAndBlackStaysStrictlyBelowWhite() {
        // Above 16-bit: clamps to 65535, never overflows.
        val huge = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 70_000,
            dynamicBlackLevel = null,
            blackLevelOffsets = null,
            strictHighEnd = true
        )
        assertEquals(65_535, huge.whiteLevel)
        assertTrue(huge.blackLevel in 0 until huge.whiteLevel)

        // Below the floor: clamps up to 256.
        val tiny = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 1,
            dynamicBlackLevel = null,
            blackLevelOffsets = null,
            strictHighEnd = true
        )
        assertEquals(256, tiny.whiteLevel)
        assertTrue(tiny.blackLevel in 0 until tiny.whiteLevel)

        // Offsets at/above white are rejected; black still falls strictly below white (no clip past max).
        val clamped = RawSensorCalibrationPolicy.resolve(
            whiteLevel = 1023,
            dynamicBlackLevel = floatArrayOf(2000f, 2000f, 2000f, 2000f),
            blackLevelOffsets = intArrayOf(2000, 2000, 2000, 2000),
            strictHighEnd = true
        )
        assertTrue(clamped.blackLevel in 0 until clamped.whiteLevel)
        assertTrue(clamped.usedFallbackBlackLevel)
    }
}
