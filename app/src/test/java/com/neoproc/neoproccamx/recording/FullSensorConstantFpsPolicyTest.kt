package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag
import com.neoproc.neoproccamx.ui.LegacyP010FpsPolicy
import com.neoproc.neoproccamx.ui.ProcessedGpuRecordFpsPolicy
import com.neoproc.neoproccamx.ui.RecordFpsCeilingFlag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Locks the full-sensor constant-25-fps pin ([FullSensorConstantFpsFlag] / [FullSensorConstantFpsPolicy]).
 *
 * Contract:
 *  - "full-sensor" is the ≥12 MP tier (4096×3072); the 4K-class 3840×2160 / 4096×2304 / 4096×2160 are NOT;
 *  - with the flag ON (production default) full-sensor recording pins to a constant 25 across every fps
 *    policy, and full-sensor offscreen opts into CFR muxing;
 *  - with the flag OFF the prior behaviour is byte-identical: the record-then-develop LIGHT lane lifts
 *    full-sensor to 30 again and the CFR opt-in collapses to VFR.
 */
class FullSensorConstantFpsPolicyTest {

    @Before
    fun setup() {
        RecordDevelopModeFlag.set(false)
        RecordFpsCeilingFlag.set(true)
        FullSensorConstantFpsFlag.set(true)
        HighEndQualityPolicy.strictHighEndOnly = true
    }

    @After
    fun teardown() {
        // Never leak toggled process-global state across test classes; restore production defaults.
        RecordDevelopModeFlag.set(false)
        RecordFpsCeilingFlag.set(true)
        FullSensorConstantFpsFlag.set(false)
        HighEndQualityPolicy.strictHighEndOnly = true
    }

    // ── pure policy ─────────────────────────────────────────────────────────────

    @Test
    fun `full-sensor tier is the 12MP-and-up class only`() {
        assertTrue(FullSensorConstantFpsPolicy.isFullSensor(4096, 3072))  // 12.58 MP
        assertFalse(FullSensorConstantFpsPolicy.isFullSensor(4096, 2304)) // 9.44 MP (4K 16:9)
        assertFalse(FullSensorConstantFpsPolicy.isFullSensor(3840, 2160)) // 8.29 MP (UHD)
        assertFalse(FullSensorConstantFpsPolicy.isFullSensor(4096, 2160)) // 8.85 MP (DCI)
        assertTrue(FullSensorConstantFpsPolicy.isFullSensor(3072, 4096))  // orientation-independent (area)
    }

    @Test
    fun `lightLane fps is the constant 25 when ON and the caller lift when OFF`() {
        FullSensorConstantFpsFlag.set(true)
        assertEquals(FullSensorConstantFpsPolicy.CONSTANT_FPS, FullSensorConstantFpsPolicy.lightLaneFullSensorFps(30))
        FullSensorConstantFpsFlag.set(false)
        assertEquals(30, FullSensorConstantFpsPolicy.lightLaneFullSensorFps(30))
    }

    @Test
    fun `engaged and CFR only for full-sensor and only when the flag is ON`() {
        FullSensorConstantFpsFlag.set(true)
        assertTrue(FullSensorConstantFpsPolicy.engaged(4096, 3072))
        assertTrue(FullSensorConstantFpsPolicy.useConstantMuxCadence(4096, 3072))
        assertFalse(FullSensorConstantFpsPolicy.engaged(4096, 2304)) // 4K 16:9 is not full-sensor
        assertFalse(FullSensorConstantFpsPolicy.useConstantMuxCadence(3840, 2160))
        FullSensorConstantFpsFlag.set(false)
        assertFalse(FullSensorConstantFpsPolicy.engaged(4096, 3072))
        assertFalse(FullSensorConstantFpsPolicy.useConstantMuxCadence(4096, 3072))
    }

    // ── live fps-policy wiring (LIGHT lane on) ──────────────────────────────────

    @Test
    fun `ProcessedGpuRecordFpsPolicy pins full-sensor to constant 25 when ON, restores 30 when OFF`() {
        HighEndQualityPolicy.strictHighEndOnly = false
        RecordDevelopModeFlag.set(true)
        FullSensorConstantFpsFlag.set(true)
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 4096, height = 3072))
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 60, width = 4096, height = 3072))
        FullSensorConstantFpsFlag.set(false)
        assertEquals(30, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 4096, height = 3072))
    }

    @Test
    fun `LegacyP010FpsPolicy pins full-sensor to constant 25 when ON, restores 30 when OFF`() {
        HighEndQualityPolicy.strictHighEndOnly = false
        RecordDevelopModeFlag.set(true)
        FullSensorConstantFpsFlag.set(true)
        assertEquals(25, LegacyP010FpsPolicy.resolve(requestedFps = 30, width = 4096, height = 3072))
        FullSensorConstantFpsFlag.set(false)
        assertEquals(30, LegacyP010FpsPolicy.resolve(requestedFps = 30, width = 4096, height = 3072))
    }

    // NOTE: RecordingPreviewFrameRatePolicy.resolveEncoderFps takes a Size-based ResolvedRecordingSelection,
    // and android.util.Size is stubbed to 0 in plain JVM unit tests (which is why RecordingPreviewFrameRatePolicyTest
    // itself only exercises the raw-Int resolveEncoderFpsForNormalizedWidth). Its full-sensor LIGHT-lane branch
    // calls the SAME FullSensorConstantFpsPolicy.lightLaneFullSensorFps helper proven above, so the constant-25
    // pin there is covered by that helper test rather than a Size-based selection here.

    @Test
    fun `constant-25 pin never touches 4K-class sizes`() {
        HighEndQualityPolicy.strictHighEndOnly = false
        RecordDevelopModeFlag.set(true)
        FullSensorConstantFpsFlag.set(true)
        // 4K 16:9 (4096×2304) and UHD (3840×2160) are below the full-sensor gate -> the shipped 25 cap.
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 4096, height = 2304))
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 3840, height = 2160))
    }

    @Test
    fun `strict recording ignores optional constant cap and preserves request`() {
        HighEndQualityPolicy.strictHighEndOnly = true
        RecordDevelopModeFlag.set(true)
        FullSensorConstantFpsFlag.set(true)
        assertEquals(30, ProcessedGpuRecordFpsPolicy.resolve(30, 4096, 3072))
        assertEquals(60, ProcessedGpuRecordFpsPolicy.resolve(60, 4096, 3072))
        assertEquals(30, LegacyP010FpsPolicy.resolve(30, 4096, 3072))
    }
}
