package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NivukxVisionConformance
import com.neoproc.neoproccamx.color.NivukxVisionHouseLook
import com.neoproc.neoproccamx.color.NivukxVisionLookKnobs
import com.neoproc.neoproccamx.color.NivukxVisionMath
import com.neoproc.neoproccamx.color.NivukxVisionPrimaryDelivery
import java.security.PrivateKey
import java.util.Locale

internal data class NivukxVisionMetadataRequest(
    val look: NivukxVisionHouseLook,
    val lookKnobs: NivukxVisionLookKnobs,
    val sceneParameters: NivukxVisionMath.SceneParameters,
    val conformance: NivukxVisionConformance,
    val requestedBitDepth: Int,
    val honoredBitDepth: Int,
    val fallbackReason: String,
    val videoBitrateBps: Long,
    val primaryDelivery: NivukxVisionPrimaryDelivery,
    val frontEndEvidence: NeoHdrFrontEndEvidence = NeoHdrFrontEndEvidence.unverified(),
    val qualityMeasurements: EliteVisionQualityMeasurements? = null
)

internal object NivukxVisionMetadataEmitter {
    fun emit(
        request: NivukxVisionMetadataRequest,
        privateKey: PrivateKey
    ): NivukxTruthMetadataBlock {
        val frontEndResult = NeoHdrFrontEndEvidencePolicy.validate(request.frontEndEvidence)
        val qualityReport = request.qualityMeasurements?.let(EliteVisionQualityContract::evaluate)
        val tiers = listOf(
            "T0_PROVENANCE" to mapOf(
                "profile" to "NIVUKX_VISION_TIER_S",
                "version" to "TIER_S_REFERENCE_HDR_CINEMA",
                "truthBoundary" to "PROJECT_OWNED_NO_VENDOR_PROFILE",
                "transportCarriers" to "BT2020_BT2100_ST2084_HEVC_AV1",
                "vendorProfileUsed" to "false",
                "vendorRpuWritten" to "false"
            ),
            "T1_STREAM" to mapOf(
                "requestedBitDepth" to request.requestedBitDepth.toString(),
                "honoredBitDepth" to request.honoredBitDepth.toString(),
                "primaryDelivery" to request.primaryDelivery.name,
                "fallbackReason" to request.fallbackReason
            ),
            "T2_FRONT_END" to frontEndResult.toMetadata(),
            "T3_SCENE_SOLVE" to mapOf(
                "appearanceSpace" to "JZAZBZ_FP32",
                "workingGamut" to "ACES_AP1_SCENE_LINEAR",
                "omegaScene" to request.sceneParameters.omega.format6(),
                "gammaCompressScene" to request.sceneParameters.gammaCompress.format6()
            ),
            "T4_FRAME_METRICS" to tierSMetricMetadata(qualityReport),
            "T5_R_TILE" to mapOf(
                "tileGrid" to "16x16",
                "localDeltas" to "DELTA_KNEE_DELTA_CHROMA_DELTA_EXPOSURE",
                "toneAnchorSolve" to "SEVEN_PERCENTILE_PROJECT_OWNED"
            ),
            "T6_S_SALIENCY" to mapOf(
                "classes" to "FACE_SKIN_SKY_FOLIAGE_SPECULAR_TEXT_NEON_FIRE_WATER_GENERIC",
                "skinProtection" to "ACTIVE",
                "skyHueLock" to "ACTIVE",
                "foliageProtection" to "ACTIVE"
            ),
            "T7_KALMAN_GOP" to mapOf(
                "state" to "KAPPA_TAU_RHO",
                "pumpingLimitEv" to "0.1",
                "metadataChain" to "ED25519_SIGNED_HASH_CHAINED_TMB"
            ),
            "V0_VISION_PROFILE" to mapOf(
                "productProfile" to "NIVUKX_VISION_ELITE",
                "lookName" to request.look.name,
                "lutAssetPath" to request.look.assetPath,
                "lutHashSha256" to request.look.sha256,
                "truthBoundary" to "PROJECT_OWNED_OPEN_MATH_NO_EXTERNAL_PROFILE"
            ),
            "V1_LOOK_KNOBS" to mapOf(
                "exposureEv" to request.lookKnobs.exposureEv.format6(),
                "contrast" to request.lookKnobs.contrast.format6(),
                "saturation" to request.lookKnobs.saturation.format6(),
                "shadowWarmthDegrees" to request.lookKnobs.shadowWarmthDegrees.format6(),
                "midtoneTintDegrees" to request.lookKnobs.midtoneTintDegrees.format6(),
                "highlightShoulderGain" to request.lookKnobs.highlightShoulderGain.format6()
            ),
            "V2_CLASS_OVERRIDES" to mapOf(
                "classes" to "FACE_SKIN_SKY_FOLIAGE_SPECULAR_TEXT_NEON_FIRE",
                "hueLock" to "SKY_FOLIAGE_NEON_FIRE",
                "rhoFloor" to "CLASS_ADAPTIVE",
                "chromaFloor" to "CLASS_ADAPTIVE"
            ),
            "V3_VISION_SOLVE" to mapOf(
                "omegaScene" to request.sceneParameters.omega.format6(),
                "gammaCompressScene" to request.sceneParameters.gammaCompress.format6(),
                "cmaxLut" to "CMAX_33x33_RADIAL_JZAZBZ",
                "solvePrecision" to "FP32"
            ),
            "V4_NLOGS_FLAG" to mapOf(
                "primaryDelivery" to request.primaryDelivery.name.replace("NLOGS_12", "NLogs-12"),
                "nlogsA" to NivukxVisionMath.NLogs12.A.format6(),
                "nlogsB" to NivukxVisionMath.NLogs12.B.format6(),
                "nlogsC" to NivukxVisionMath.NLogs12.C.format6(),
                "nlogsD" to NivukxVisionMath.NLogs12.D.format6()
            ),
            "V5_PYRAMID_SPEC" to mapOf(
                "levels" to "6",
                "baseOnlyColorVolume" to "true",
                "sharpenBands" to "0,1",
                "maxOvershoot" to "0.04"
            ),
            "V6_DENOISE_SPEC" to mapOf(
                "burstAlign" to "FLOW3_QUARTER_PIXEL_OCCLUSION_REJECT",
                "burstMerge" to "TUKEY_BIWEIGHT",
                "masterStackDepth" to "32",
                "snrTextureThresholdDb" to "12"
            ),
            "V7_RETEXTURE_SPEC" to mapOf(
                "microTextureModel" to "PHOTON_STATISTICS_CORRECT",
                "gainMapBits" to "8",
                "plasticLookGuard" to "true",
                "tmbBinding" to "ED25519_SIGNED_HASH_CHAINED_TMB"
            ),
            "V8_CONFORMANCE_EVIDENCE" to mapOf(
                "conformance" to request.conformance.name,
                "requestedBitDepth" to request.requestedBitDepth.toString(),
                "honoredBitDepth" to request.honoredBitDepth.toString(),
                "fallbackReason" to request.fallbackReason
            )
        )
        val records = NivukxTruthLedger.buildRecords(tiers)
        val payload = NivukxTruthLedger.buildPayloadJson(records)
        return NivukxTruthLedger.sign(
            payloadJson = payload,
            privateKey = privateKey,
            videoBitrateBps = request.videoBitrateBps,
            records = records
        )
    }

    private fun Double.format6(): String =
        String.format(Locale.US, "%.6f", this)

    private fun tierSMetricMetadata(report: EliteVisionQualityReport?): Map<String, String> {
        if (report == null) {
            return mapOf(
                "tierSQualityStatus" to "NOT_PROVEN",
                "tierSRequirement" to "Sec1TargetsMustBeMeasuredBeforeReferenceClaim"
            )
        }
        return buildMap {
            put("tierSQualityStatus", if (report.passed) "PASS" else "FAIL")
            report.results.forEach { result ->
                put("tierS_${result.id}", result.passed.toString())
            }
        }
    }
}
