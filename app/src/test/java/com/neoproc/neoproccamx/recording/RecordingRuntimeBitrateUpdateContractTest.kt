package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRuntimeBitrateUpdateContractTest {
    private val update = File("src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt")
        .readText().substringAfter("fun updateActiveEncoderBitrate(")
        .substringBefore("fun isAllIntraActive()")

    @Test fun runtimeUsesTheSameStrictCapabilityAdmissionAsStartup() {
        val admission = update.indexOf("StrictBitrateCapabilityAdmission.resolve(")
        val parameterUpdate = update.indexOf("codec.setParameters(")
        assertTrue(admission >= 0 && parameterUpdate > admission)
        val admissionBlock = update.substring(admission, parameterUpdate)
        assertTrue(admissionBlock.contains("requiredBitrateBps = policyResolvedBitrate"))
        assertTrue(admissionBlock.contains("availableBitrateBps = appliedBitrate"))
        assertTrue(admissionBlock.contains("if (!runtimeAdmission.accepted)"))
        assertTrue(admissionBlock.contains("return false"))
    }

    @Test fun failedCodecUpdateCannotReplaceActiveBitrateEvidence() {
        val parameterUpdate = update.indexOf("codec.setParameters(")
        listOf(
            "requestedOutputBitrateForEvidence = requestedBitrate",
            "policyTargetBitrateForEvidence = appliedBitrate",
            "activeOutputBitrate = appliedBitrate",
            "abrNominalBitrate = appliedBitrate",
            "lastHintedBitrate = appliedBitrate"
        ).forEach { assignment ->
            val assignmentIndex = update.indexOf(assignment)
            assertTrue("$assignment must follow codec acceptance", assignmentIndex > parameterUpdate)
            assertTrue(assignmentIndex < update.indexOf("catch (throwable: Throwable)"))
        }
        assertFalse(update.substringBefore("return try").contains("ForEvidence ="))
    }

    @Test fun actualCodecCannotSilentlyReduceThePolicyFloor() {
        val decision = StrictBitrateCapabilityAdmission.resolve(
            strictHighEnd = true,
            requiredBitrateBps = 160_000_000,
            availableBitrateBps = 120_000_000
        )
        assertFalse(decision.accepted)
        assertEquals(160_000_000, decision.requiredBitrateBps)
        assertEquals(120_000_000, decision.availableBitrateBps)
    }

    @Test fun exactSupportedRateRemainsAcceptedWithoutInflationOrReduction() {
        val decision = StrictBitrateCapabilityAdmission.resolve(
            strictHighEnd = true,
            requiredBitrateBps = 160_000_000,
            availableBitrateBps = 160_000_000
        )
        assertTrue(decision.accepted)
        assertEquals(decision.requiredBitrateBps, decision.availableBitrateBps)
    }
}
