package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRecordingCadenceAdmissionGateTest {

    @Test
    fun `admits only after ten consecutive physical 25 fps intervals`() {
        val gate = CameraRecordingCadenceAdmissionGate(targetFps = 25)
        var timestampNs = 1_000_000_000L

        assertFalse(gate.observe(timestampNs).admitted)
        repeat(9) {
            timestampNs += 40_000_000L
            assertFalse(gate.observe(timestampNs).admitted)
        }
        timestampNs += 40_000_000L
        val admitted = gate.observe(timestampNs)

        assertTrue(admitted.admitted)
        assertEquals(10, admitted.consecutiveIntervals)
        assertEquals(25.0, admitted.measuredFps, 0.0001)
    }

    @Test
    fun `vendor startup pause resets the evidence window instead of admitting a damaged clip`() {
        val gate = CameraRecordingCadenceAdmissionGate(targetFps = 25)
        var timestampNs = 2_000_000_000L
        gate.observe(timestampNs)
        repeat(7) {
            timestampNs += 40_000_000L
            gate.observe(timestampNs)
        }

        timestampNs += 437_000_000L
        val rejected = gate.observe(timestampNs)
        assertFalse(rejected.admitted)
        assertEquals(0, rejected.consecutiveIntervals)
        assertEquals(1, rejected.rejectedIntervals)

        repeat(9) {
            timestampNs += 40_000_000L
            assertFalse(gate.observe(timestampNs).admitted)
        }
        timestampNs += 40_000_000L
        val recovered = gate.observe(timestampNs)
        assertTrue(recovered.admitted)
        assertEquals(437_000_000L, recovered.largestGapNs)
    }

    @Test
    fun `average rate must match target even when individual gaps are inside broad bounds`() {
        val gate = CameraRecordingCadenceAdmissionGate(targetFps = 25)
        var timestampNs = 3_000_000_000L
        gate.observe(timestampNs)
        repeat(20) {
            timestampNs += 48_000_000L
            assertFalse(gate.observe(timestampNs).admitted)
        }

        val snapshot = gate.snapshot()
        assertFalse(snapshot.admitted)
        assertEquals(10, snapshot.consecutiveIntervals)
        assertEquals(20.8333, snapshot.measuredFps, 0.001)
    }

    @Test
    fun `non monotonic timestamp invalidates accumulated cadence proof`() {
        val gate = CameraRecordingCadenceAdmissionGate(
            targetFps = 25,
            requiredConsecutiveIntervals = 2
        )
        gate.observe(4_000_000_000L)
        gate.observe(4_040_000_000L)

        val rejected = gate.observe(4_020_000_000L)

        assertFalse(rejected.admitted)
        assertEquals(0, rejected.consecutiveIntervals)
        assertEquals(1, rejected.rejectedIntervals)
    }
}
