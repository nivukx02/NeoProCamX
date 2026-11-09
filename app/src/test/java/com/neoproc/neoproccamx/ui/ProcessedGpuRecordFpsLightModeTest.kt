package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.recording.FullSensorConstantFpsFlag
import com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Locks the record-then-develop LIGHT-lane fps lift in [ProcessedGpuRecordFpsPolicy]. The lift lives ONLY
 * in the live 3-arg overload (the pure math overload is untouched, so the golden ProcessedGpuRecordFpsPolicyTest
 * assertions stay green). Contract: with the master switch ON, full-sensor 4096×3072 lifts to 30 fps (never
 * above the request); 4K-class stays at 25; and with the switch OFF the baseline 25 fps cap is byte-identical.
 *
 * These tests exercise the LIGHT-lane lift in isolation, so they disable [FullSensorConstantFpsFlag] (which
 * otherwise, at its production default ON, pins full-sensor to a constant 25 and would mask the 30 lift). The
 * constant-25 default is covered separately by FullSensorConstantFpsPolicyTest.
 */
class ProcessedGpuRecordFpsLightModeTest {

    @Before
    fun setup() {
        // Deterministic baseline for the non-light fall-through path, and switch starts OFF.
        RecordFpsCeilingFlag.set(false)
        HighEndQualityPolicy.strictHighEndOnly = false
        RecordDevelopModeFlag.set(false)
        // Isolate the LIGHT-lane 30 lift from the constant-25 pin (default ON) so it is observable here.
        FullSensorConstantFpsFlag.set(false)
    }

    @After
    fun teardown() {
        RecordDevelopModeFlag.set(false)
        RecordFpsCeilingFlag.set(true) // restore production default, don't leak across classes
        FullSensorConstantFpsFlag.set(false)
        HighEndQualityPolicy.strictHighEndOnly = true
    }

    @Test
    fun lightModeLiftsFullSensorTo30() {
        RecordDevelopModeFlag.set(true)
        assertEquals(30, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 4096, height = 3072))
        assertEquals(30, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 60, width = 4096, height = 3072))
    }

    @Test
    fun lightModeNeverRaisesAboveRequest() {
        RecordDevelopModeFlag.set(true)
        assertEquals(24, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 24, width = 4096, height = 3072))
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 25, width = 4096, height = 3072))
    }

    @Test
    fun lightModeDoesNotLift4K() {
        RecordDevelopModeFlag.set(true)
        // 3840x2160 = 8.29 MP and 4096x2160 = 8.85 MP are below the full-sensor threshold -> stay 25.
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 3840, height = 2160))
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 4096, height = 2160))
    }

    @Test
    fun lightModeClampsSubOneRequest() {
        RecordDevelopModeFlag.set(true)
        assertEquals(1, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 0, width = 4096, height = 3072))
    }

    @Test
    fun switchOffPreservesBaselineFullSensorCap() {
        // Default OFF: full-sensor stays at the shipped 25 fps cinema cap (no downgrade, no upgrade).
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 4096, height = 3072))
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 60, width = 4096, height = 3072))
    }
}
