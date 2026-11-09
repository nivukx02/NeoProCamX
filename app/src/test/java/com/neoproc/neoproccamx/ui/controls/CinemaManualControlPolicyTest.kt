package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.camera.CameraLongRange
import com.neoproc.neoproccamx.camera.CameraRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaManualControlPolicyTest {

    @Test
    fun shutterAngleEntriesUseFrameRateFormulaAndKeepFullOpenAngleWhenSupported() {
        val entries = CinemaManualControlPolicy.shutterAngleEntries(
            fps = 24,
            exposureRange = CameraLongRange(100_000L, 1_000_000_000L)
        )

        val openShutter = entries.first { it.angleDegrees == 360f }
        val cinemaStandard = entries.first { it.angleDegrees == 180f }

        assertEquals(41_666_667L, openShutter.exposureNs)
        assertEquals(20_833_333L, cinemaStandard.exposureNs)
        assertEquals("360° 1/24", openShutter.label)
        assertEquals("180° 1/48", cinemaStandard.label)
    }

    @Test
    fun shutterAngleEntriesRemoveAnglesThatTheSensorCannotApply() {
        val entries = CinemaManualControlPolicy.shutterAngleEntries(
            fps = 24,
            exposureRange = CameraLongRange(100_000L, 10_000_000L)
        )

        assertTrue(entries.any { it.angleDegrees == 45f })
        assertFalse(entries.any { it.angleDegrees == 90f })
        assertFalse(entries.any { it.angleDegrees == 180f })
    }

    @Test
    fun whiteBalanceProgressUsesHardwareRangeAndPreservesExactEndpoints() {
        val range = CinemaManualControlPolicy.whiteBalanceRange(2856, 6500)

        assertEquals(2856, CinemaManualControlPolicy.whiteBalanceKelvinForProgress(0, range))
        assertEquals(6500, CinemaManualControlPolicy.whiteBalanceKelvinForProgress(999, range))
        assertEquals(0, CinemaManualControlPolicy.progressForWhiteBalanceKelvin(2500, range))
        assertEquals(
            CinemaManualControlPolicy.whiteBalanceProgressMax(range),
            CinemaManualControlPolicy.progressForWhiteBalanceKelvin(8000, range)
        )
    }

    @Test
    fun meteringRegionsExposeMatrixCenterAndSpotBehavior() {
        val activeArray = CameraRect(0, 0, 4000, 3000)

        assertEquals(
            null,
            CinemaManualControlPolicy.meteringRegion(
                activeArray = activeArray,
                mode = CinemaManualMeteringMode.MATRIX
            )
        )
        assertEquals(
            CameraRect(1000, 750, 3000, 2250),
            CinemaManualControlPolicy.meteringRegion(
                activeArray = activeArray,
                mode = CinemaManualMeteringMode.CENTER_WEIGHTED
            )
        )
        assertEquals(
            CameraRect(1760, 1320, 2240, 1680),
            CinemaManualControlPolicy.meteringRegion(
                activeArray = activeArray,
                mode = CinemaManualMeteringMode.SPOT
            )
        )
    }
}
