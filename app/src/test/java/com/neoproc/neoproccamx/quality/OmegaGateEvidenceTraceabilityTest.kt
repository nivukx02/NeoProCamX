package com.neoproc.neoproccamx.quality

import com.neoproc.neoproccamx.recording.EliteVideoPipelineMode
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f08 — every SUPPORTED verdict must trace to a real transport/bit-depth fact. The gate records
 * an evidence string per capability ([OmegaCapabilityItem.evidence]); this test asserts that no
 * SUPPORTED item carries a blank evidence string and that the evidence actually cites the proving
 * fact (transport/primaries/bit-depth/proof token), so an "available" verdict can never exist
 * without its evidence populated.
 */
class OmegaGateEvidenceTraceabilityTest {

    @Test
    fun everySupportedItemHasNonBlankEvidence() {
        val reports = listOf(
            OmegaCapabilityGate.evaluate(provenSdrInput()),
            OmegaCapabilityGate.evaluate(provenHdrNdrInput())
        )
        reports.forEach { report ->
            val supported = report.items.filter { it.status == OmegaCapabilityStatus.SUPPORTED }
            assertTrue("Expected at least one SUPPORTED item to make this test non-vacuous", supported.isNotEmpty())
            supported.forEach { item ->
                assertTrue(
                    "SUPPORTED ${item.id} must carry non-blank evidence",
                    item.evidence.isNotBlank()
                )
            }
        }
    }

    @Test
    fun supportedEvidenceCitesTheProvingTransportOrBitDepthFact() {
        val sdr = OmegaCapabilityGate.evaluate(provenSdrInput())
        val sdrEvidence = evidenceOf(sdr, OmegaCapabilityId.SDR_REC709_TRUTH)
        assertTrue("SDR evidence must cite the transfer fact", sdrEvidence.contains("transfer="))
        assertTrue("SDR evidence must cite the primaries fact", sdrEvidence.contains("primaries="))

        val ffprobeEvidence = evidenceOf(sdr, OmegaCapabilityId.FFPROBE_VALIDATION)
        assertTrue("FFprobe evidence must cite the proof boolean", ffprobeEvidence.contains("ffprobeValidated=true"))

        val ndr = OmegaCapabilityGate.evaluate(provenHdrNdrInput())
        val ndrEvidence = evidenceOf(ndr, OmegaCapabilityId.NDR_NEO_TRANSPORT)
        assertTrue("NDR evidence must cite the encoded bit depth", ndrEvidence.contains("bitDepth="))
    }

    private fun evidenceOf(report: OmegaQualityTruthReport, id: OmegaCapabilityId): String =
        report.items.first { it.id == id }.evidence

    /** Proven 10-bit SDR Rec.709 path (mirrors the existing OmegaCapabilityGateTest.baseInput()). */
    private fun provenSdrInput() = OmegaCapabilityInput(
        mode = EliteVideoPipelineMode.NORMAL,
        selectedProfile = "Original SDR",
        activeTransport = "SDR_REC709_MAIN10",
        transfer = "BT.709/OETF",
        primaries = "BT.709",
        matrix = "BT.709",
        codec = "hevc",
        encodedBitDepth = 10,
        internalMathBitDepth = 10,
        sourceBitDepth = 10,
        storageBitDepth = 10,
        width = 1920,
        height = 1080,
        fps = 30,
        bitrateBps = 67_000_000,
        selectedResolution = "1920x1080",
        actualResolution = "1920x1080",
        rawSensorOriginal = false,
        ffprobeValidated = true,
        mediaStoreValidated = true,
        deviceLogValidated = true,
        gpuProcessingValidated = false,
        gpuProcessingPath = "CAMERA2_ISP_ENCODED_FALLBACK",
        encoderCapabilityCheck = "appliedBitrateWithinEncoderBound=true",
        claimedFeatures = emptySet()
    )

    /** Proven 10-bit NDR HDR path (mirrors OmegaCapabilityGateDynamicRangeFallbackTest.tenBitHdrInput()). */
    private fun provenHdrNdrInput() = OmegaCapabilityInput(
        mode = EliteVideoPipelineMode.HDR,
        selectedProfile = "NDR10+",
        activeTransport = "NDR10_PLUS_PQ_MAIN10",
        transfer = "ST2084/PQ",
        primaries = "BT.2020",
        matrix = "BT.2020",
        codec = "hevc",
        encodedBitDepth = 10,
        internalMathBitDepth = 10,
        sourceBitDepth = 10,
        storageBitDepth = 10,
        width = 3840,
        height = 2160,
        fps = 30,
        bitrateBps = 120_000_000,
        selectedResolution = "3840x2160",
        actualResolution = "3840x2160",
        rawSensorOriginal = false,
        ffprobeValidated = true,
        mediaStoreValidated = true,
        deviceLogValidated = true,
        gpuProcessingValidated = false,
        claimedFeatures = emptySet(),
        dynamicRangeSilentFallback = false
    )
}
