package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

/**
 * Tests for ManualControlValueMapper - Updated for cinema-grade control ranges.
 * 
 * Note: The mapper was upgraded with extended ISO range (25-409600), extended
 * white balance range (1800K-20000K), and finer focus control.
 */
class ManualControlValueMapperTest {

    @Test
    fun isoValueForProgress_clampsIntoKnownTable() {
        // Progress -2 clamps to index 0 -> ISO 25 (extended low)
        assertEquals(25, ManualControlValueMapper.isoValueForProgress(-2))
        // Progress 9 -> index 9 -> ISO 200 (in the middle of native range)
        assertEquals(200, ManualControlValueMapper.isoValueForProgress(9))
        // Progress 999 clamps to last index -> ISO 409600 (max extended)
        assertEquals(409600, ManualControlValueMapper.isoValueForProgress(999))
    }

    @Test
    fun whiteBalanceKelvinForProgress_mapsFiftyKelvinSteps() {
        // Progress 0 -> 1800K (extended low, candlelight)
        assertEquals(1800, ManualControlValueMapper.whiteBalanceKelvinForProgress(0))
        // Progress 45 -> 1800 + 45*50 = 4050K
        assertEquals(4050, ManualControlValueMapper.whiteBalanceKelvinForProgress(45))
        // Progress 60 -> 1800 + 60*50 = 4800K
        assertEquals(4800, ManualControlValueMapper.whiteBalanceKelvinForProgress(60))
        // Progress 76 -> 1800 + 76*50 = 5600K (daylight)
        assertEquals(5600, ManualControlValueMapper.whiteBalanceKelvinForProgress(76))
    }

    @Test
    fun focusDistanceForProgress_mapsInfinityAtMaxProgress() {
        // Progress 0 -> max diopter (closest focus)
        assertEquals(4f, ManualControlValueMapper.focusDistanceForProgress(0, 4f), 0.0001f)
        // Progress 50 -> mid-range with quadratic easing (uses pow 2.2)
        val normalized = 50f / ManualControlValueMapper.focusProgressMax
        val expectedMid = 4f * (1f - normalized.toDouble().pow(2.2).toFloat())
        assertEquals(expectedMid, ManualControlValueMapper.focusDistanceForProgress(50, 4f), 0.01f)
        // Max progress -> 0 diopter (infinity focus)
        assertEquals(0f, ManualControlValueMapper.focusDistanceForProgress(ManualControlValueMapper.focusProgressMax, 4f), 0.0001f)
    }

    @Test
    fun evProgressAndValue_roundTripAcrossDisplayRange() {
        val progress = ManualControlValueMapper.evProgressForValue(2, -4, 4)
        assertEquals(6, progress)
        assertEquals(2, ManualControlValueMapper.evValueForProgress(progress, -4, 4))
    }
    
    @Test
    fun nativeIsoRange_identifiesOptimalQualityIsos() {
        // Native ISO range is 100-3200
        assertEquals(false, ManualControlValueMapper.isNativeIso(50))
        assertEquals(true, ManualControlValueMapper.isNativeIso(100))
        assertEquals(true, ManualControlValueMapper.isNativeIso(800))
        assertEquals(true, ManualControlValueMapper.isNativeIso(3200))
        assertEquals(false, ManualControlValueMapper.isNativeIso(6400))
    }
}
