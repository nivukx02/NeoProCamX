package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * s8-f02 (CP-U2) — contract lock for the pure manual-control mappers: monotonicity, detent counts,
 * and set→readback round-trip per axis (ISO / SS / EV / WB / MF). These are real arithmetic
 * identities computed from the production mapper, so any regression trips CI. Pure JVM.
 */
class ManualControlMapperContractTest {

    @Test
    fun isoIsMonotonicNonDecreasingWith43DetentsAndRoundTrips() {
        val iso = ManualControlValueMapper.isoValues
        assertEquals("ISO detent count", 43, iso.size)
        assertEquals(25, iso.first())
        assertEquals(409600, iso.last())
        for (i in 0 until iso.lastIndex) {
            assertTrue("ISO must be non-decreasing at $i", iso[i] <= iso[i + 1])
        }
        // set value -> readback progress -> value round-trips at every detent.
        for (i in iso.indices) {
            assertEquals("ISO round-trip at index $i", i, ManualControlValueMapper.progressForIsoValue(iso[i]))
            assertEquals(iso[i], ManualControlValueMapper.isoValueForProgress(i))
        }
    }

    @Test
    fun whiteBalanceIsMonotonicAndRoundTrips() {
        val maxP = ManualControlValueMapper.whiteBalanceProgressMax
        var prev = Int.MIN_VALUE
        for (p in 0..maxP) {
            val k = ManualControlValueMapper.whiteBalanceKelvinForProgress(p)
            assertTrue("WB monotonic at $p", k > prev)
            prev = k
            assertEquals("WB round-trip at $p", p, ManualControlValueMapper.progressForWhiteBalanceKelvin(k))
        }
    }

    @Test
    fun tintIsMonotonicAndExactRoundTrip() {
        val maxP = ManualControlValueMapper.tintProgressMax
        var prev = Int.MIN_VALUE
        for (p in 0..maxP) {
            val t = ManualControlValueMapper.tintValueForProgress(p)
            assertTrue("tint monotonic at $p", t > prev)
            prev = t
            assertEquals("tint round-trip at $p", p, ManualControlValueMapper.progressForTintValue(t))
        }
        assertEquals(-150, ManualControlValueMapper.tintValueForProgress(0))
        assertEquals(150, ManualControlValueMapper.tintValueForProgress(maxP))
    }

    @Test
    fun evIsMonotonicAndExactRoundTripOverRange() {
        val lower = -6
        val upper = 6
        var prev = Int.MIN_VALUE
        for (p in 0..(upper - lower)) {
            val v = ManualControlValueMapper.evValueForProgress(p, lower, upper)
            assertTrue("EV monotonic at $p", v > prev)
            prev = v
            assertEquals("EV round-trip at $p", p, ManualControlValueMapper.evProgressForValue(v, lower, upper))
        }
    }

    @Test
    fun shutterAngleExposureIsStrictlyAscendingWithAngle() {
        val fps = 24f
        var prev = Long.MIN_VALUE
        for (angle in ManualControlValueMapper.shutterAngles) {
            val ns = ManualControlValueMapper.shutterAngleToExposureNs(angle, fps)
            assertTrue("shutter ns must rise with angle ($angle)", ns > prev)
            prev = ns
        }
    }

    @Test
    fun focusDistanceIsMonotonicDecreasingAndRoundTripsAtEndpointsAndDetents() {
        val minFocus = 4f
        // Endpoints are exact.
        assertEquals(0, ManualControlValueMapper.progressForFocusDistance(
            ManualControlValueMapper.focusDistanceForProgress(0, minFocus), minFocus))
        assertEquals(
            ManualControlValueMapper.focusProgressMax,
            ManualControlValueMapper.progressForFocusDistance(
                ManualControlValueMapper.focusDistanceForProgress(ManualControlValueMapper.focusProgressMax, minFocus), minFocus)
        )
        // Monotonic non-increasing distance as progress rises.
        var prev = Float.MAX_VALUE
        for (p in 0..ManualControlValueMapper.focusProgressMax step 50) {
            val d = ManualControlValueMapper.focusDistanceForProgress(p, minFocus)
            assertTrue("focus distance must not increase at $p", d <= prev + 1e-4f)
            prev = d
        }
        // Round-trip within a small tolerance at sampled detents (float pow asymmetry + truncation).
        for (p in listOf(100, 250, 500, 750, 900)) {
            val rt = ManualControlValueMapper.progressForFocusDistance(
                ManualControlValueMapper.focusDistanceForProgress(p, minFocus), minFocus)
            assertTrue("focus round-trip near $p (got $rt)", abs(rt - p) <= 3)
        }
    }
}
