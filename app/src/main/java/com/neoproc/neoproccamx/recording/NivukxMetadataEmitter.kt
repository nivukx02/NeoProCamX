package com.neoproc.neoproccamx.recording

import java.security.PrivateKey
import java.util.Locale

internal data class NivukxTmbRequest(
    val selectedProfile: String,
    val productProfile: String,
    val codec: String,
    val transport: String,
    val requestedBitDepth: Int,
    val honoredBitDepth: Int,
    val width: Int,
    val height: Int,
    val fps: Int,
    val videoBitrateBps: Long,
    val fallbackReason: String,
    val toneAnchors: NivukxImagingMath.ToneAnchors
)

internal object NivukxMetadataEmitter {
    fun emit(
        request: NivukxTmbRequest,
        privateKey: PrivateKey
    ): NivukxTruthMetadataBlock {
        val tiers = listOf(
            "T0_PROVENANCE" to mapOf(
                "productProfile" to request.productProfile,
                "selectedProfile" to request.selectedProfile,
                "truthBoundary" to "PROJECT_OWNED_NO_VENDOR_PROFILE"
            ),
            "T1_STREAM" to mapOf(
                "codec" to request.codec,
                "transport" to request.transport,
                "primaries" to "BT2020",
                "transfer" to "ST2084_PQ",
                "range" to "LIMITED",
                "requestedBitDepth" to request.requestedBitDepth.toString(),
                "honoredBitDepth" to request.honoredBitDepth.toString(),
                "fallbackReason" to request.fallbackReason
            ),
            "T2_TITLE" to mapOf(
                "width" to request.width.toString(),
                "height" to request.height.toString(),
                "fps" to request.fps.toString(),
                "videoBitrateBps" to request.videoBitrateBps.toString()
            ),
            "T3_SCENE" to mapOf(
                "workingGamut" to "ACES_AP1_FP16_SCENE_LINEAR",
                "appearanceModel" to "JZAZBZ_FP32",
                "toneMap" to "THREE_ANCHOR_TOE_KNEE_SHOULDER"
            ),
            "T4_FRAME" to mapOf(
                "toeNits" to request.toneAnchors.toe.format3(),
                "kneeNits" to request.toneAnchors.knee.format3(),
                "shoulderNits" to request.toneAnchors.shoulder.format3(),
                "gammaToe" to request.toneAnchors.gammaToe.format3(),
                "kappa" to request.toneAnchors.kappa.format3()
            ),
            "T5_RTILE" to mapOf(
                "tileGrid" to "16x16",
                "deltaFields" to "KNEE_CHROMA_EXPOSURE",
                "maxMetadataBitrateRatio" to "0.004"
            ),
            "T6_SALIENCY" to mapOf(
                "classes" to "FACE_SKIN_SKY_FOLIAGE_SPECULAR_TEXT_NEON_FIRE_WATER_GENERIC",
                "maxMasks" to "16",
                "vendorCertified" to "false"
            ),
            "T7_KALMAN_GOP" to mapOf(
                "state" to "KAPPA_TAU_KNEE_RHO",
                "smoothing" to "CUT_AWARE",
                "vendorRpuWritten" to "false"
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

    private fun Double.format3(): String =
        String.format(Locale.US, "%.3f", this)
}
