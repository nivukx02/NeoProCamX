package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectOwnedNdrFrameAdmissionTest {
    private val neutralD50 = floatArrayOf(
        0.96422f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 0.82521f
    )

    private fun proof(
        stabilizerGeneration: Long = 1L,
        calibrationGeneration: Long = 7L,
        whiteBalanceR: Float = 2f
    ): ProjectOwnedNdrColorProof = ProjectOwnedNdrColorProof.create(
        stabilizerGeneration = stabilizerGeneration,
        calibrationGeneration = calibrationGeneration,
        whiteBalanceRggb = floatArrayOf(whiteBalanceR, 1f, 1f, 1.5f),
        sensorToXyz = neutralD50
    )

    @Test
    fun `only exact committed epoch and timestamp is admitted`() {
        val gate = ProjectOwnedNdrFrameAdmission()
        val colorProof = proof()
        gate.setEnabled(true)
        gate.begin(7L)

        assertFalse(gate.register(7L, 101L, colorProof))
        assertTrue(gate.commit(7L))
        assertFalse(gate.register(6L, 101L, colorProof))
        assertTrue(gate.register(7L, 101L, colorProof))
        assertNull(gate.admit(100L))
        val admitted = gate.admit(101L)
        assertEquals(7L, admitted?.epoch)
        assertEquals(colorProof, admitted?.colorProof)
        assertNull(gate.admit(101L))
    }

    @Test
    fun `new epoch invalidates in flight frame from previous mode`() {
        val gate = ProjectOwnedNdrFrameAdmission()
        gate.setEnabled(true)
        gate.begin(11L)
        assertTrue(gate.commit(11L))
        assertTrue(gate.register(11L, 501L, proof(calibrationGeneration = 11L)))

        gate.begin(12L)

        assertNull(gate.admit(501L))
        assertFalse(gate.isCommitted(11L))
        assertTrue(gate.commit(12L))
        assertTrue(gate.register(12L, 502L, proof(calibrationGeneration = 12L)))
        assertEquals(12L, gate.admit(502L)?.epoch)
    }

    @Test
    fun `older callback cannot consume a newer submission`() {
        val gate = ProjectOwnedNdrFrameAdmission()
        gate.setEnabled(true)
        gate.begin(3L)
        assertTrue(gate.commit(3L))
        val colorProof = proof(calibrationGeneration = 3L)
        assertTrue(gate.register(3L, 800L, colorProof))
        assertTrue(gate.register(3L, 801L, colorProof))

        assertNull(gate.admit(800L))
        assertEquals(3L, gate.admit(801L)?.epoch)
    }

    @Test
    fun `duplicate session commit preserves an in flight exact timestamp`() {
        val gate = ProjectOwnedNdrFrameAdmission()
        gate.setEnabled(true)
        gate.begin(15L)
        assertTrue(gate.commit(15L))
        assertTrue(gate.register(15L, 1_501L, proof(calibrationGeneration = 15L)))

        assertTrue(gate.commit(15L))

        assertEquals(15L, gate.admit(1_501L)?.epoch)
    }

    @Test
    fun `disable revokes commit and pending timestamp`() {
        val gate = ProjectOwnedNdrFrameAdmission()
        gate.setEnabled(true)
        gate.begin(4L)
        assertTrue(gate.commit(4L))
        assertTrue(gate.register(4L, 900L, proof(calibrationGeneration = 4L)))

        gate.setEnabled(false)

        assertNull(gate.admit(900L))
        assertFalse(gate.isCommitted(4L))
    }

    @Test
    fun `invalid colour proof is rejected before timestamp registration`() {
        val gate = ProjectOwnedNdrFrameAdmission()
        val invalid = proof(stabilizerGeneration = 0L)
        gate.setEnabled(true)
        gate.begin(9L)
        assertTrue(gate.commit(9L))

        assertFalse(gate.register(9L, 901L, invalid))
        assertNull(gate.admit(901L))
    }

    @Test
    fun `two compatible proofs are required and a lineage mismatch restarts the run`() {
        val stability = ProjectOwnedNdrProofStabilityGate(requiredConsecutiveFrames = 2)
        val first = proof(stabilizerGeneration = 4L, calibrationGeneration = 21L, whiteBalanceR = 2f)
        val adapted = proof(stabilizerGeneration = 4L, calibrationGeneration = 21L, whiteBalanceR = 2.05f)
        val newSession = proof(stabilizerGeneration = 5L, calibrationGeneration = 22L)

        val one = stability.observe(first)
        assertFalse(one.stable)
        assertEquals(1, one.consecutiveCompatibleFrames)
        assertTrue(first.isCompatibleWith(adapted))

        val two = stability.observe(adapted)
        assertTrue(two.stable)
        assertEquals(2, two.consecutiveCompatibleFrames)

        val reset = stability.observe(newSession)
        assertFalse(reset.stable)
        assertEquals(1, reset.consecutiveCompatibleFrames)
        assertEquals("incompatible-proof-reset", reset.reason)
        assertTrue(stability.observe(newSession).stable)
    }

    @Test
    fun `project owned dataspace accepts exact pq full or proven unknown only`() {
        val colorProof = proof()
        val exact = ProjectOwnedNdrDataSpaceAdmissionPolicy.evaluate(
            NdrInputTransportPolicy.DATASPACE_BT2020_PQ,
            colorProof
        )
        val unknown = ProjectOwnedNdrDataSpaceAdmissionPolicy.evaluate(
            NdrInputTransportPolicy.DATASPACE_UNKNOWN,
            colorProof
        )
        val limited = ProjectOwnedNdrDataSpaceAdmissionPolicy.evaluate(
            NdrInputTransportPolicy.DATASPACE_BT2020_PQ_LIMITED,
            colorProof
        )
        val hlg = ProjectOwnedNdrDataSpaceAdmissionPolicy.evaluate(
            NdrInputTransportPolicy.DATASPACE_BT2020_HLG,
            colorProof
        )
        val unprovenUnknown = ProjectOwnedNdrDataSpaceAdmissionPolicy.evaluate(
            NdrInputTransportPolicy.DATASPACE_UNKNOWN,
            null
        )

        assertTrue(exact.accepted)
        assertEquals(ProjectOwnedNdrDataSpaceAdmissionSource.BUFFER_DATASPACE, exact.source)
        assertTrue(unknown.accepted)
        assertEquals(ProjectOwnedNdrDataSpaceAdmissionSource.PRODUCER_PROOF, unknown.source)
        assertFalse(limited.accepted)
        assertFalse(hlg.accepted)
        assertFalse(unprovenUnknown.accepted)
    }

    @Test
    fun `i2405 public forward matrix satisfies the neutral proof`() {
        // Camera 0 SENSOR_FORWARD_MATRIX1/2 reported by Camera2 on the connected vivo I2405.
        // Keeping this real, rational-valued matrix in the proof suite prevents a synthetic-only
        // neutral gate from silently rejecting every RAW frame on the validation device.
        val sensorToXyzD50 = floatArrayOf(
            59f / 128f, 38f / 128f, 26f / 128f,
            14f / 128f, 114f / 128f, 1f / 128f,
            -13f / 128f, -61f / 128f, 180f / 128f
        )

        val validation = ProjectOwnedNdrColorProof.validateNeutralAxis(sensorToXyzD50)

        assertTrue("I2405 public forward matrix must remain admissible: $validation", validation.passed)
    }

    @Test
    fun `neutral spread alone cannot admit a wrongly scaled forward matrix`() {
        val scaledD50 = neutralD50.map { it * 1.10f }.toFloatArray()

        val validation = ProjectOwnedNdrColorProof.validateNeutralAxis(scaledD50)

        assertEquals(ProjectOwnedNdrNeutralAxisStatus.CHROMATIC, validation.status)
        assertFalse(validation.passed)
    }
}
