package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NivukxTruthLedgerTest {

    @Test
    fun truthMetadataBlockSignsAndVerifiesHashChain() {
        val keyPair = NivukxTruthLedger.generateSigningKeyPair()
        val block = NivukxMetadataEmitter.emit(
            request = request(honoredBitDepth = 12, fallbackReason = "NONE"),
            privateKey = keyPair.private
        )

        assertEquals("Ed25519", block.signatureAlgorithm)
        assertTrue(NivukxTruthLedger.verify(block, keyPair.public))
        assertTrue(block.payloadJson.contains("\"T0_PROVENANCE\""))
        assertTrue(block.payloadJson.contains("\"T7_KALMAN_GOP\""))
        assertTrue(block.records.zipWithNext().all { (a, b) -> b.previousHash == a.hash })
    }

    @Test
    fun truthMetadataBlockRejectsPayloadTampering() {
        val keyPair = NivukxTruthLedger.generateSigningKeyPair()
        val block = NivukxMetadataEmitter.emit(
            request = request(honoredBitDepth = 12, fallbackReason = "NONE"),
            privateKey = keyPair.private
        )
        val tampered = block.copy(
            payloadJson = block.payloadJson.replace("HEVC_MAIN12_BT2020_PQ", "HEVC_MAIN10_LITE_FALLBACK")
        )

        assertFalse(NivukxTruthLedger.verify(tampered, keyPair.public))
    }

    @Test
    fun truthMetadataBlockRecordsFallbackHonestyWithoutVendorFlags() {
        val keyPair = NivukxTruthLedger.generateSigningKeyPair()
        val block = NivukxMetadataEmitter.emit(
            request = request(
                requestedBitDepth = 12,
                honoredBitDepth = 10,
                fallbackReason = "MAIN12_OR_AV1_12_UNAVAILABLE"
            ),
            privateKey = keyPair.private
        )

        assertTrue(block.payloadJson.contains("\"requestedBitDepth\":12"))
        assertTrue(block.payloadJson.contains("\"honoredBitDepth\":10"))
        assertTrue(block.payloadJson.contains("\"fallbackReason\":\"MAIN12_OR_AV1_12_UNAVAILABLE\""))
        assertFalse(block.payloadJson.contains("Dolby"))
        assertFalse(block.payloadJson.contains("RPU"))
        assertFalse(block.payloadJson.contains("\"vendorCertified\":true"))
        assertTrue(block.metadataBitrateRatio <= 0.004)
    }

    private fun request(
        requestedBitDepth: Int = 12,
        honoredBitDepth: Int,
        fallbackReason: String
    ): NivukxTmbRequest =
        NivukxTmbRequest(
            selectedProfile = "NDR10+",
            productProfile = "NIVUKX-DR_ELITE",
            codec = "HEVC",
            transport = if (honoredBitDepth >= 12) "HEVC_MAIN12_BT2020_PQ" else "HEVC_MAIN10_LITE_FALLBACK",
            requestedBitDepth = requestedBitDepth,
            honoredBitDepth = honoredBitDepth,
            width = 3840,
            height = 2160,
            fps = 60,
            videoBitrateBps = 80_000_000L,
            fallbackReason = fallbackReason,
            toneAnchors = NivukxImagingMath.ToneAnchors(
                p01 = 0.02,
                p10 = 0.10,
                p50 = 90.0,
                p90 = 1_000.0,
                p99 = 3_800.0,
                p999 = 6_000.0,
                p9999 = 8_000.0,
                toe = 0.08,
                knee = 1_980.0,
                shoulder = 7_000.0,
                gammaToe = 1.10,
                kappa = 1.65,
                peakNits = 10_000.0
            )
        )
}
