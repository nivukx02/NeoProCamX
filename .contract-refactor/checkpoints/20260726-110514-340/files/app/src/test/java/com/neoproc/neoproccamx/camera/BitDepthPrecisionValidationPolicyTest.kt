package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitDepthPrecisionValidationPolicyTest {

    @Test
    fun tenBitEvidenceRequiresAnAdvertisedAndroidTenBitTransport() {
        assertFalse(
            BitDepthHardwareEvidencePolicy.supportsTenBitColorTransport(
                HdrDynamicRangePolicy.Support.NONE
            )
        )
        assertTrue(
            BitDepthHardwareEvidencePolicy.supportsTenBitColorTransport(
                HdrDynamicRangePolicy.Support(
                    standardOnly = false,
                    hlg10Supported = true
                )
            )
        )
    }

    @Test
    fun raw12EvidenceRequiresRawCapabilityAndAdvertisedRaw12Output() {
        assertFalse(
            BitDepthHardwareEvidencePolicy.supportsRaw12Capture(
                rawCapabilityAvailable = true,
                raw12OutputAdvertised = false
            )
        )
        assertFalse(
            BitDepthHardwareEvidencePolicy.supportsRaw12Capture(
                rawCapabilityAvailable = false,
                raw12OutputAdvertised = true
            )
        )
        assertTrue(
            BitDepthHardwareEvidencePolicy.supportsRaw12Capture(
                rawCapabilityAvailable = true,
                raw12OutputAdvertised = true
            )
        )
    }

    @Test
    fun strictProcessedVideoDoesNotRequireRawSensorCapabilityAtStartup() {
        val plan = BitDepthPrecisionValidationPolicy.resolve(
            supports10BitColor = true,
            supports12BitRaw = false,
            rawWorkflowRequested = false,
            strictHighEnd = true
        )

        assertTrue(plan.compatible)
        assertFalse(plan.rawWorkflowAllowed)
        assertTrue(plan.diagnostics.any { it.contains("RAW workflows disabled") })
    }

    @Test
    fun strictRawWorkflowRequiresTwelveBitRawSensorCapability() {
        val plan = BitDepthPrecisionValidationPolicy.resolve(
            supports10BitColor = true,
            supports12BitRaw = false,
            rawWorkflowRequested = true,
            strictHighEnd = true
        )

        assertFalse(plan.compatible)
        assertTrue(plan.diagnostics.any { it.contains("12-bit RAW_SENSOR") })
    }

    @Test
    fun strictProcessedVideoStartupDoesNotRequireCameraTenBitTransport() {
        val plan = BitDepthPrecisionValidationPolicy.resolve(
            supports10BitColor = false,
            supports12BitRaw = true,
            rawWorkflowRequested = false,
            strictHighEnd = true
        )

        assertTrue(plan.compatible)
        assertTrue(plan.diagnostics.any { it.contains("SDR strict high-end processed video remains available") })
    }

    @Test
    fun explicitTenBitTransportStillRequiresCameraTenBitEvidence() {
        val plan = BitDepthPrecisionValidationPolicy.resolve(
            supports10BitColor = false,
            supports12BitRaw = true,
            rawWorkflowRequested = false,
            strictHighEnd = true,
            tenBitTransportRequested = true
        )

        assertFalse(plan.compatible)
        assertTrue(plan.diagnostics.any { it.contains("mandatory 10-bit color") })
    }

    @Test
    fun strictModeBitDepthContractRequiresTenBitForEncodedVideoAndTwelveBitOnlyForRaw() {
        listOf(
            "VIDEO",
            "NORMAL SDR VIDEO",
            "PRO SDR",
            "NDR10+",
            "NIVUKX Vision",
            "APEX Sharp",
            "APEX_ZERO",
            "NIVUKX APEX CINEMA",
            "N-LOG"
        ).forEach { mode ->
            val contract = StrictVideoBitDepthContractPolicy.resolve(
                recordingMode = mode,
                supports10BitColor = true,
                supports12BitRaw = false
            )

            assertEquals("mode=$mode", 10, contract.requiredBitDepth)
            assertEquals("mode=$mode", 10, contract.effectiveBitDepth)
            assertTrue("mode=$mode", contract.tenBitTransportRequired)
            assertFalse("mode=$mode", contract.raw12Required)
            assertTrue("mode=$mode", contract.strictMode)
        }

        val rawContract = StrictVideoBitDepthContractPolicy.resolve(
            recordingMode = "RAW",
            supports10BitColor = true,
            supports12BitRaw = true
        )

        assertEquals(12, rawContract.requiredBitDepth)
        assertEquals(12, rawContract.effectiveBitDepth)
        assertFalse(rawContract.tenBitTransportRequired)
        assertTrue(rawContract.raw12Required)
        assertTrue(rawContract.strictMode)
    }

    @Test
    fun strictModeBitDepthContractFailsClosedWhenRequiredEvidenceIsMissing() {
        val logWithoutTenBit = StrictVideoBitDepthContractPolicy.resolve(
            recordingMode = "LOG",
            supports10BitColor = false,
            supports12BitRaw = true
        )
        val rawWithoutRaw12 = StrictVideoBitDepthContractPolicy.resolve(
            recordingMode = "RAW",
            supports10BitColor = true,
            supports12BitRaw = false
        )

        assertEquals(10, logWithoutTenBit.requiredBitDepth)
        assertEquals(0, logWithoutTenBit.effectiveBitDepth)
        assertTrue(logWithoutTenBit.tenBitTransportRequired)
        assertEquals(12, rawWithoutRaw12.requiredBitDepth)
        assertEquals(0, rawWithoutRaw12.effectiveBitDepth)
        assertTrue(rawWithoutRaw12.raw12Required)
    }
}
