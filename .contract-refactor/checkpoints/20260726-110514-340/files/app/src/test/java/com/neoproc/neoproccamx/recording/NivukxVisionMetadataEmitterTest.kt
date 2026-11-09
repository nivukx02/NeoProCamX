package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NivukxVisionLookEngine
import com.neoproc.neoproccamx.color.NivukxVisionLookKnobs
import com.neoproc.neoproccamx.color.NivukxVisionMath
import com.neoproc.neoproccamx.color.NivukxVisionConformance
import com.neoproc.neoproccamx.color.NivukxVisionPrimaryDelivery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NivukxVisionMetadataEmitterTest {

    @Test
    fun visionMetadataEmitsSignedV0ToV7RecordsWithoutExternalProfileClaims() {
        val keyPair = NivukxTruthLedger.generateSigningKeyPair()
        val block = NivukxVisionMetadataEmitter.emit(
            request = request(),
            privateKey = keyPair.private
        )

        assertTrue(NivukxTruthLedger.verify(block, keyPair.public))
        (0..7).forEach { index ->
            assertTrue(block.payloadJson.contains("\"T${index}_"))
        }
        (0..7).forEach { index ->
            assertTrue(block.payloadJson.contains("\"V${index}_"))
        }
        assertTrue(block.payloadJson.contains("NIVUKX_VISION_ELITE"))
        assertTrue(block.payloadJson.contains("PROJECT_OWNED_NO_VENDOR_PROFILE"))
        assertTrue(block.payloadJson.contains("frontEndStatus"))
        assertTrue(block.payloadJson.contains("tierSQualityStatus"))
        assertTrue(block.payloadJson.contains("ED25519_SIGNED_HASH_CHAINED_TMB"))
        assertTrue(block.metadataBitrateRatio <= 0.0015)
        assertFalse(block.payloadJson.contains("Dolby"))
        assertFalse(block.payloadJson.contains("HDR10+"))
        assertFalse(block.payloadJson.contains("IMAX"))
        assertFalse(block.payloadJson.contains("RPU"))
    }

    @Test
    fun visionMetadataRecordsLiteFallbackWhenTwelveBitTransportIsUnavailable() {
        val keyPair = NivukxTruthLedger.generateSigningKeyPair()
        val block = NivukxVisionMetadataEmitter.emit(
            request = request(
                conformance = NivukxVisionConformance.LITE,
                requestedBitDepth = 12,
                honoredBitDepth = 10,
                fallbackReason = "MAIN12_OR_AV1_12_UNAVAILABLE"
            ),
            privateKey = keyPair.private
        )

        assertTrue(NivukxTruthLedger.verify(block, keyPair.public))
        assertTrue(block.payloadJson.contains("\"conformance\":\"LITE\""))
        assertTrue(block.payloadJson.contains("\"fallbackReason\":\"MAIN12_OR_AV1_12_UNAVAILABLE\""))
        assertTrue(block.payloadJson.contains("\"primaryDelivery\":\"PQ\""))
    }

    private fun request(
        conformance: NivukxVisionConformance = NivukxVisionConformance.ELITE,
        requestedBitDepth: Int = 12,
        honoredBitDepth: Int = 12,
        fallbackReason: String = "NONE"
    ): NivukxVisionMetadataRequest =
        NivukxVisionMetadataRequest(
            look = NivukxVisionLookEngine.houseLooks().first(),
            lookKnobs = NivukxVisionLookKnobs.DEFAULT,
            sceneParameters = NivukxVisionMath.resolveSceneParameters(
                p50 = 120.0,
                p99 = 4_000.0,
                lPeak = 10_000.0
            ),
            conformance = conformance,
            requestedBitDepth = requestedBitDepth,
            honoredBitDepth = honoredBitDepth,
            fallbackReason = fallbackReason,
            videoBitrateBps = 120_000_000L,
            primaryDelivery = NivukxVisionPrimaryDelivery.PQ,
            frontEndEvidence = NeoHdrFrontEndEvidence(
                blackLevels = listOf(64.0, 64.0, 65.0, 63.0),
                whiteLevel = 4095.0,
                wbGains = listOf(2.08, 1.0, 1.74),
                neutralAfterWb = listOf(1.01, 1.0, 0.99),
                sensorToXyz = listOf(
                    0.684, 0.211, 0.105,
                    0.231, 0.708, 0.061,
                    0.028, 0.118, 0.854
                ),
                cctKelvin = 5600,
                defectCorrectionApplied = true,
                lensShadingCorrectionApplied = true,
                linearizationGuardApplied = true,
                redRunawayClampApplied = true
            ),
            qualityMeasurements = EliteVisionQualityMeasurements(
                hueDeltaE2000Max = 0.42,
                skinDeltaE2000Max = 1.6,
                specularChromaAtClip = 0.18,
                skyHueDriftDegrees = 0.7,
                lookRoundTripDeltaE2000Max = 0.62,
                gamutBoundaryOvershoot = 0.0,
                lightnessDriftMax = 0.0,
                noiseSigmaIreBase = 0.32,
                noiseSigmaIreIso3200 = 0.65,
                mtf50Retention = 0.94,
                acutanceGain = 0.28,
                haloOvershoot = 0.025,
                bandingDeltaCodeMax = 0.0,
                metadataBitrateRatio = 0.0012,
                tmbSignatureVerified = true,
                fallbackEvidenceHonest = true,
                nlogsRoundTripDeltaE2000Max = 0.22,
                deterministicHashMatch = true,
                lumaSnrBaseDb = 49.0,
                lumaSnrHighIsoDb = 39.0,
                chromaNoiseStd = 0.42,
                temporalFlicker = 0.008,
                mtf50CyPerPx = 0.34,
                detailRetentionVsRaw = 0.93,
                colorCheckerDeltaE2000Mean = 1.2,
                colorCheckerDeltaE2000Max = 2.4,
                grayDeltaAAbs = 0.7,
                grayDeltaBAbs = 0.8,
                deadHotPixels = 0,
                redClipBlueShadowSafe = true,
                previewRecordExportParity = true,
                wbApplied = true,
                ccmNonIdentity = true,
                vendorProfileFree = true
            )
        )
}
