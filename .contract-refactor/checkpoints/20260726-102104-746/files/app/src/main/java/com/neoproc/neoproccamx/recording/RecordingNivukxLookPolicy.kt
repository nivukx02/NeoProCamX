package com.neoproc.neoproccamx.recording

import java.util.Locale

internal data class RecordingNivukxLookPlan(
    val renderPlan: NivukxDrRenderPlan,
    val defaultToneAnchors: NivukxImagingMath.ToneAnchors,
    val noiseChain: String,
    val detailChain: String,
    val colorGradeChain: String
) {
    fun asMetadata(): Map<String, String> {
        val activeMetadata = if (renderPlan.active) {
            mapOf(
                "nivukxDrToneMap" to "THREE_ANCHOR_JZ_TOE_KNEE_SHOULDER",
                "nivukxDrDefaultToeNits" to defaultToneAnchors.toe.format3(),
                "nivukxDrDefaultKneeNits" to defaultToneAnchors.knee.format3(),
                "nivukxDrDefaultShoulderNits" to defaultToneAnchors.shoulder.format3(),
                "nivukxDrGammaToe" to defaultToneAnchors.gammaToe.format3(),
                "nivukxDrKappa" to defaultToneAnchors.kappa.format3(),
                "nivukxDrNoiseChain" to noiseChain,
                "nivukxDrDetailChain" to detailChain,
                "nivukxDrColorGradeChain" to colorGradeChain,
                "nivukxDrTruthMetadataBlock" to "ED25519_SIGNED_HASH_CHAINED_TMB"
            )
        } else {
            emptyMap()
        }
        return renderPlan.asMetadata() + activeMetadata
    }

    private fun Double.format3(): String =
        String.format(Locale.US, "%.3f", this)
}

internal object RecordingNivukxLookPolicy {
    fun resolve(
        request: EliteVideoQualityRequest,
        normalizedHdrVersion: String
    ): RecordingNivukxLookPlan {
        val renderPlan = NivukxVersionRenderPolicy.resolve(
            hdrMode = normalizedHdrVersion,
            codec = request.codec,
            sourceBitDepth = request.sourceBitDepth,
            storageBitDepth = request.storageBitDepth,
            tenBitTransport = request.tenBitTransport
        )
        return RecordingNivukxLookPlan(
            renderPlan = renderPlan,
            defaultToneAnchors = NivukxImagingMath.resolveToneAnchors(
                luminanceNits = listOf(0.02, 0.10, 1.0, 12.0, 90.0, 500.0, 1_000.0, 3_800.0, 6_000.0, 8_000.0),
                peakNits = 10_000.0
            ),
            noiseChain = "FLOW3_TUKEY_BIWEIGHT_JZAZBZ_EDGE_AWARE_MICROTEXTURE",
            detailChain = "LAPLACIAN_6_LEVEL_BASE_ONLY_TONEMAP_BAND_SLOPE_FOLLOW",
            colorGradeChain = "ACES_AP1_TO_JZAZBZ_HUE_LOCKED_RADIAL_COMPRESS"
        )
    }
}
