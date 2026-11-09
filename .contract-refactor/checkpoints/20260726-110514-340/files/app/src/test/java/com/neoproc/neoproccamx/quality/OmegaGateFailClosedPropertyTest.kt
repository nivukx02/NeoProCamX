package com.neoproc.neoproccamx.quality

import com.neoproc.neoproccamx.recording.EliteVideoPipelineMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * s8-f08 — fail-closed property lock for [OmegaCapabilityGate].
 *
 * The honest-gate contract (§0.6) forbids ONE direction only: an unproven claim must never become
 * AVAILABLE/SUPPORTED. It does NOT require every capability to read NOT_AVAILABLE — `LIMITED` is the
 * honest "present-but-uncertified" verdict (e.g. an un-probed validation, or an SDR path that has not
 * yet matched BT.709). So the fail-closed invariant proved here is precisely:
 *   "under empty/insufficient evidence, NO capability id is SUPPORTED"
 * plus the stronger fact that the six inherently-unprovable ids are UNCONDITIONALLY NOT_AVAILABLE.
 *
 * This is verdict-preserving (no production code changes); it locks today's behaviour so any future
 * loosening of the gate trips CI.
 */
class OmegaGateFailClosedPropertyTest {

    @Test
    fun noCapabilityIsSupportedWithEmptyEvidence() {
        val report = OmegaCapabilityGate.evaluate(emptyEvidenceInput())
        OmegaCapabilityId.entries.forEach { id ->
            assertNotEquals(
                "Empty evidence must never yield a SUPPORTED verdict for $id",
                OmegaCapabilityStatus.SUPPORTED,
                report.statusOf(id)
            )
        }
        // Overall cannot be SUPPORTED either when nothing is proven.
        assertNotEquals(OmegaCapabilityStatus.SUPPORTED, report.overallStatus)
    }

    @Test
    fun inherentlyUnprovableIdsAreAlwaysNotAvailableUnderEmptyEvidence() {
        val report = OmegaCapabilityGate.evaluate(emptyEvidenceInput())
        UNPROVABLE_IDS.forEach { id ->
            assertEquals(
                "Inherently-unprovable capability $id must be NOT_AVAILABLE",
                OmegaCapabilityStatus.NOT_AVAILABLE,
                report.statusOf(id)
            )
        }
    }

    @Test
    fun capabilityIdSetIsLockedSoNewIdsMustBeAudited() {
        // statusFor()'s when(id) is EXHAUSTIVE with no else, so adding a new enum id is a COMPILE
        // error in the gate until it is explicitly mapped — i.e. a new capability cannot be added
        // silently. This count lock makes that addition also visible at test-review time and forces
        // a re-audit of the fail-closed property above for the new id.
        assertEquals(17, OmegaCapabilityId.entries.size)
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

    /** Deliberately barren input: nothing is proven, every validation false, all bit depths zero. */
    private fun emptyEvidenceInput() = OmegaCapabilityInput(
        mode = EliteVideoPipelineMode.NORMAL,
        selectedProfile = "",
        activeTransport = "",
        transfer = "",
        primaries = "",
        matrix = "",
        codec = "",
        encodedBitDepth = 0,
        internalMathBitDepth = 0,
        sourceBitDepth = 0,
        storageBitDepth = 0,
        width = 0,
        height = 0,
        fps = 0,
        bitrateBps = 0,
        selectedResolution = "",
        actualResolution = "",
        rawSensorOriginal = false,
        npv10PayloadFormat = "",
        npv10NativeTenBitClaimed = false,
        npv10MasterAvailable = false,
        ffprobeValidated = false,
        mediaStoreValidated = false,
        deviceLogValidated = false,
        gpuProcessingValidated = false,
        gpuProcessingPath = "",
        encoderCapabilityCheck = "",
        claimedFeatures = emptySet()
    )
}
