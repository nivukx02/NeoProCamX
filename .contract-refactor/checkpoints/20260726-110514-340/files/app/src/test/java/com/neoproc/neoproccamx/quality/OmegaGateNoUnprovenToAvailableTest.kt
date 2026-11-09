package com.neoproc.neoproccamx.quality

import com.neoproc.neoproccamx.recording.EliteVideoPipelineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f08 — proves there is NO input that bribes the gate into advertising an inherently-unprovable
 * capability. A "maximal" input sets every boolean true, every bit depth to 12, every transport
 * string to a value that would satisfy any token check, and iterates every pipeline mode — yet the
 * external/licensed/extreme/forensic ids stay NOT_AVAILABLE, and claiming any of them is always a
 * blocking failure that drives the whole report to NOT_AVAILABLE + truthful=false.
 */
class OmegaGateNoUnprovenToAvailableTest {

    @Test
    fun externalAndExtremeAndForensicIdsStayNotAvailableUnderEveryMode() {
        EliteVideoPipelineMode.entries.forEach { mode ->
            val report = OmegaCapabilityGate.evaluate(maximalInput(mode))
            UNPROVABLE_IDS.forEach { id ->
                assertEquals(
                    "Capability $id must remain NOT_AVAILABLE even under a maximal bribe input (mode=$mode)",
                    OmegaCapabilityStatus.NOT_AVAILABLE,
                    report.statusOf(id)
                )
            }
        }
    }

    @Test
    fun claimingAnUnprovableIdIsAlwaysABlockingFailure() {
        val report = OmegaCapabilityGate.evaluate(
            maximalInput(EliteVideoPipelineMode.NORMAL, claimedFeatures = UNPROVABLE_IDS)
        )
        assertFalse("A claim over unprovable capabilities cannot be truthful", report.truthful)
        assertEquals(OmegaCapabilityStatus.NOT_AVAILABLE, report.overallStatus)
        assertTrue(
            "Every unprovable claimed id must surface as a blocking failure",
            report.blockingFailures.map { it.id }.toSet().containsAll(UNPROVABLE_IDS)
        )
    }

    private companion object {
        val UNPROVABLE_IDS = setOf(
            OmegaCapabilityId.EXTERNAL_CERTIFIED_DYNAMIC_METADATA,
            OmegaCapabilityId.EXTERNAL_LICENSED_RAW_CODEC,
            OmegaCapabilityId.EXTERNAL_LICENSED_INTERMEDIATE_CODEC,
            OmegaCapabilityId.UNPROVEN_EXTREME_BITRATE,
            OmegaCapabilityId.UNPROVEN_EXTREME_RESOLUTION,
            OmegaCapabilityId.UNPROVEN_FORENSIC_PROVENANCE
        )
    }

    /**
     * The most generous input we can construct: every proof present, 12-bit everywhere, BT.2020/PQ,
     * a profile/transport string carrying every Neo token, Vulkan path, native P010 — designed to
     * coerce a SUPPORTED out of the gate for the unprovable ids. It must not succeed.
     */
    private fun maximalInput(
        mode: EliteVideoPipelineMode,
        claimedFeatures: Set<OmegaCapabilityId> = emptySet()
    ) = OmegaCapabilityInput(
        mode = mode,
        selectedProfile = "NDR10+ NIVUKX N-LOG ALL TOKENS",
        activeTransport = "NDR10_PLUS_NIVUKX_N_LOG_PQ_MAIN10",
        transfer = "ST2084/PQ",
        primaries = "BT.2020",
        matrix = "BT.2020",
        codec = "hevc",
        encodedBitDepth = 12,
        internalMathBitDepth = 12,
        sourceBitDepth = 12,
        storageBitDepth = 12,
        width = 7680,
        height = 4320,
        fps = 120,
        bitrateBps = 900_000_000,
        selectedResolution = "7680x4320",
        actualResolution = "7680x4320",
        rawSensorOriginal = true,
        npv10PayloadFormat = "NEO_P010_PRIVATE",
        npv10NativeTenBitClaimed = true,
        npv10MasterAvailable = true,
        ffprobeValidated = true,
        mediaStoreValidated = true,
        deviceLogValidated = true,
        gpuProcessingValidated = true,
        gpuProcessingPath = "VULKAN_COMPUTE",
        encoderCapabilityCheck = "appliedBitrateWithinEncoderBound=true",
        claimedFeatures = claimedFeatures
    )
}
