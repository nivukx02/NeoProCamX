package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f02 (CP-U2) — locks the shutter angle↔exposure conversion exact for the active fps. The cinema
 * convention is 180° at 24p == 1/48 s; full-open 360° == the frame time. Pure JVM.
 */
class ShutterAngleConversionTest {

    @Test
    fun oneEightyAt24pIsOneOver48Second() {
        val ns = ManualControlValueMapper.shutterAngleToExposureNs(180f, 24f)
        // 1/48 s within float tolerance.
        assertEquals(1.0 / 48.0, ns / 1e9, 1e-3)
    }

    @Test
    fun fullOpen360At24pIsFrameTime() {
        val frame = ManualControlValueMapper.shutterAngleToExposureNs(360f, 24f)
        assertEquals(1.0 / 24.0, frame / 1e9, 1e-3)
        // 360° exposure equals the full frame interval.
        assertEquals((1_000_000_000L / 24f).toLong(), frame)
    }

    @Test
    fun angleRoundTripsBackToAngleWithinHalfDegree() {
        for (angle in listOf(45f, 90f, 172.8f, 180f, 270f, 360f)) {
            val ns = ManualControlValueMapper.shutterAngleToExposureNs(angle, 24f)
            val back = ManualControlValueMapper.exposureNsToShutterAngle(ns, 24f)
            assertEquals("angle round-trip $angle", angle, back, 0.5f)
        }
    }

    @Test
    fun exposureScalesLinearlyWithAngle() {
        // Half the angle ⇒ half the exposure (within 1 ns truncation slack).
        val full = ManualControlValueMapper.shutterAngleToExposureNs(180f, 24f)
        val half = ManualControlValueMapper.shutterAngleToExposureNs(90f, 24f)
        assertTrue("90° should be ~half of 180°", kotlin.math.abs(full - 2 * half) <= 2)
    }
}
