package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraHapticFeedbackPolicyTest {

    @Test
    fun resolve_mapsModeSwitchToTickWithNavigationRateLimit() {
        val result = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.MODE_SWITCH,
            nowMs = 1_000L,
            lastEvent = null
        )

        assertTrue(result.shouldEmit)
        assertEquals(CameraHapticPrimitive.CLOCK_TICK, result.primitive)
        assertEquals(55L, result.minIntervalMs)
        assertEquals(CameraHapticAction.MODE_SWITCH, result.nextEvent.action)
    }

    @Test
    fun resolve_suppressesRepeatedNavigationInsideInterval() {
        val result = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.MODE_SWITCH,
            nowMs = 1_020L,
            lastEvent = CameraHapticEvent(CameraHapticAction.MODE_SWITCH, emittedAtMs = 1_000L)
        )

        assertFalse(result.shouldEmit)
    }

    @Test
    fun resolve_allowsRecordStopAfterDifferentImportantAction() {
        val result = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.RECORD_STOP,
            nowMs = 1_050L,
            lastEvent = CameraHapticEvent(CameraHapticAction.RECORD_START, emittedAtMs = 1_000L)
        )

        assertTrue(result.shouldEmit)
        assertEquals(CameraHapticPrimitive.HEAVY_CLICK, result.primitive)
        assertEquals(28L, result.fallbackDurationMs)
    }

    @Test
    fun resolve_mapsCameraSwitchToDoubleTick() {
        val result = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.CAMERA_SWITCH,
            nowMs = 2_000L,
            lastEvent = null
        )

        assertTrue(result.shouldEmit)
        assertEquals(CameraHapticPrimitive.DOUBLE_TICK, result.primitive)
        assertEquals(80L, result.minIntervalMs)
    }

    @Test
    fun resolve_mapsPauseResumeToDistinctRecordingFeedback() {
        val pause = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.RECORD_PAUSE,
            nowMs = 3_000L,
            lastEvent = null
        )
        val resume = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.RECORD_RESUME,
            nowMs = 3_060L,
            lastEvent = pause.nextEvent
        )

        assertTrue(pause.shouldEmit)
        assertTrue(resume.shouldEmit)
        assertEquals(CameraHapticPrimitive.CONFIRM, pause.primitive)
        assertEquals(CameraHapticPrimitive.CLOCK_TICK, resume.primitive)
    }

    @Test
    fun resolve_rateLimitsManualControlTicks() {
        val first = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.MANUAL_CONTROL_TICK,
            nowMs = 4_000L,
            lastEvent = null
        )
        val second = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.MANUAL_CONTROL_TICK,
            nowMs = 4_010L,
            lastEvent = first.nextEvent
        )

        assertTrue(first.shouldEmit)
        assertFalse(second.shouldEmit)
        assertEquals(CameraHapticPrimitive.CLOCK_TICK, first.primitive)
    }

    @Test
    fun resolve_mapsSliderBoundaryToHeavyStopWithOwnRateLimit() {
        val first = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.SLIDER_BOUNDARY_STOP,
            nowMs = 5_000L,
            lastEvent = null
        )
        val second = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.SLIDER_BOUNDARY_STOP,
            nowMs = 5_060L,
            lastEvent = first.nextEvent
        )

        assertTrue(first.shouldEmit)
        assertFalse(second.shouldEmit)
        assertEquals(CameraHapticPrimitive.HEAVY_CLICK, first.primitive)
        assertEquals(120L, first.minIntervalMs)
    }

    @Test
    fun resolve_mapsSnapToDefaultToConfirmPulse() {
        val result = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.SNAP_TO_DEFAULT,
            nowMs = 6_000L,
            lastEvent = null
        )

        assertTrue(result.shouldEmit)
        assertEquals(CameraHapticPrimitive.CONFIRM, result.primitive)
        assertEquals(16L, result.fallbackDurationMs)
        assertEquals(90L, result.minIntervalMs)
    }

    @Test
    fun resolve_mapsAiTargetLockToDoubleTickWaveform() {
        val result = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.AI_TARGET_LOCK,
            nowMs = 7_000L,
            lastEvent = null
        )

        assertTrue(result.shouldEmit)
        assertEquals(CameraHapticPrimitive.DOUBLE_TICK, result.primitive)
        assertEquals(
            CameraHapticWaveform(
                timingsMs = listOf(0L, 12L, 38L, 12L),
                amplitudes = listOf(0, 190, 0, 160)
            ),
            result.waveform
        )
    }

    @Test
    fun resolve_mapsCriticalPipelineAlertToRateLimitedPulseAlarm() {
        val first = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.CRITICAL_PIPELINE_ALERT,
            nowMs = 8_000L,
            lastEvent = null
        )
        val second = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.CRITICAL_PIPELINE_ALERT,
            nowMs = 8_500L,
            lastEvent = first.nextEvent
        )

        assertTrue(first.shouldEmit)
        assertFalse(second.shouldEmit)
        assertEquals(CameraHapticPrimitive.PULSE_ALERT, first.primitive)
        assertEquals(2_500L, first.minIntervalMs)
        assertEquals(
            CameraHapticWaveform(
                timingsMs = listOf(0L, 26L, 48L, 26L, 64L, 36L),
                amplitudes = listOf(0, 220, 0, 190, 0, 255)
            ),
            first.waveform
        )
    }

    @Test
    fun resolve_recordStopCarriesDeepDualPulseFallbackWaveform() {
        val result = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.RECORD_STOP,
            nowMs = 9_000L,
            lastEvent = null
        )

        assertTrue(result.shouldEmit)
        assertEquals(CameraHapticPrimitive.HEAVY_CLICK, result.primitive)
        assertEquals(28L, result.fallbackDurationMs)
        assertEquals(
            CameraHapticWaveform(
                timingsMs = listOf(0L, 14L, 20L, 14L),
                amplitudes = listOf(0, 255, 0, 220)
            ),
            result.waveform
        )
    }
}
