package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.ImaxStrictProfile
import java.util.Locale

internal data class EliteVisionQualityMeasurements(
    val hueDeltaE2000Max: Double,
    val skinDeltaE2000Max: Double,
    val specularChromaAtClip: Double,
    val skyHueDriftDegrees: Double,
    val lookRoundTripDeltaE2000Max: Double,
    val gamutBoundaryOvershoot: Double,
    val lightnessDriftMax: Double,
    val noiseSigmaIreBase: Double,
    val noiseSigmaIreIso3200: Double,
    val mtf50Retention: Double,
    val acutanceGain: Double,
    val haloOvershoot: Double,
    val bandingDeltaCodeMax: Double,
    val metadataBitrateRatio: Double,
    val tmbSignatureVerified: Boolean,
    val fallbackEvidenceHonest: Boolean,
    val nlogsRoundTripDeltaE2000Max: Double,
    val deterministicHashMatch: Boolean,
    val lumaSnrBaseDb: Double = 48.0,
    val lumaSnrHighIsoDb: Double = 38.0,
    val chromaNoiseStd: Double = 0.5,
    val temporalFlicker: Double = 0.01,
    val mtf50CyPerPx: Double = 0.32,
    val detailRetentionVsRaw: Double = 0.92,
    val colorCheckerDeltaE2000Mean: Double = 1.5,
    val colorCheckerDeltaE2000Max: Double = 3.0,
    val grayDeltaAAbs: Double = 1.0,
    val grayDeltaBAbs: Double = 1.0,
    val deadHotPixels: Int = 0,
    val redClipBlueShadowSafe: Boolean = true,
    val previewRecordExportParity: Boolean = true,
    val wbApplied: Boolean = true,
    val ccmNonIdentity: Boolean = true,
    val vendorProfileFree: Boolean = true
)

internal data class EliteVisionQualityResult(
    val id: String,
    val passed: Boolean,
    val measured: String,
    val threshold: String
)

internal data class EliteVisionQualityReport(
    val passed: Boolean,
    val results: List<EliteVisionQualityResult>
) {
    fun toJson(): String =
        buildString {
            append("{\"schema\":\"nivukx-vision-a1-a15-v1\",")
            append("\"passed\":").append(passed).append(",\"results\":[")
            results.forEachIndexed { index, result ->
                if (index > 0) append(',')
                append("{\"id\":\"").append(result.id).append("\",")
                append("\"passed\":").append(result.passed).append(',')
                append("\"measured\":\"").append(result.measured).append("\",")
                append("\"threshold\":\"").append(result.threshold).append("\"}")
            }
            append("]}")
        }
}

internal object EliteVisionQualityContract {
    fun evaluate(measurements: EliteVisionQualityMeasurements): EliteVisionQualityReport {
        val results = listOf(
            result("A1", measurements.hueDeltaE2000Max < 0.5, measurements.hueDeltaE2000Max, "<0.5 hue dE2000"),
            result("A2", measurements.skinDeltaE2000Max < 2.0, measurements.skinDeltaE2000Max, "<2.0 skin dE2000"),
            result("A3", measurements.specularChromaAtClip >= 0.15, measurements.specularChromaAtClip, ">=0.15 chroma"),
            result("A4", measurements.skyHueDriftDegrees < 1.0, measurements.skyHueDriftDegrees, "<1 degree"),
            result("A5", measurements.lookRoundTripDeltaE2000Max < 0.8, measurements.lookRoundTripDeltaE2000Max, "<0.8 dE2000"),
            result("A6", measurements.gamutBoundaryOvershoot <= 0.0, measurements.gamutBoundaryOvershoot, "<=0 overshoot"),
            result("A7", measurements.lightnessDriftMax <= 0.000001, measurements.lightnessDriftMax, "Jz invariant"),
            result(
                "A8",
                measurements.noiseSigmaIreBase <= 0.4 && measurements.noiseSigmaIreIso3200 <= 0.8,
                "${measurements.noiseSigmaIreBase.format6()}/${measurements.noiseSigmaIreIso3200.format6()}",
                "<=0.4 base and <=0.8 ISO3200"
            ),
            result("A9", measurements.mtf50Retention >= 0.92, measurements.mtf50Retention, ">=0.92 MTF50"),
            result(
                "A10",
                measurements.acutanceGain >= 0.25 && measurements.haloOvershoot <= 0.03,
                "${measurements.acutanceGain.format6()}/${measurements.haloOvershoot.format6()}",
                ">=0.25 acutance and <=0.03 halo"
            ),
            result("A11", measurements.bandingDeltaCodeMax <= 0.0, measurements.bandingDeltaCodeMax, "no banding delta"),
            result("A12", measurements.metadataBitrateRatio <= 0.0015, measurements.metadataBitrateRatio, "<=0.0015 bitrate"),
            result(
                "A13",
                measurements.tmbSignatureVerified && measurements.fallbackEvidenceHonest,
                "${measurements.tmbSignatureVerified}/${measurements.fallbackEvidenceHonest}",
                "TMB verified and fallback honest"
            ),
            result("A14", measurements.nlogsRoundTripDeltaE2000Max < 0.3, measurements.nlogsRoundTripDeltaE2000Max, "<0.3 dE2000"),
            result("A15", measurements.deterministicHashMatch, measurements.deterministicHashMatch.toString(), "bit-identical"),
            result(
                "S1",
                measurements.lumaSnrBaseDb >= ImaxStrictProfile.MIN_LUMA_SNR_BASE_DB &&
                    measurements.lumaSnrHighIsoDb >= ImaxStrictProfile.MIN_LUMA_SNR_HIGH_ISO_DB,
                "${measurements.lumaSnrBaseDb.format6()}/${measurements.lumaSnrHighIsoDb.format6()}",
                ">=50dB base and >=40dB high ISO"
            ),
            result("S2", measurements.chromaNoiseStd <= ImaxStrictProfile.MAX_CHROMA_NOISE_BASE_STD, measurements.chromaNoiseStd, "<=0.4 chroma std"),
            result("S3", measurements.temporalFlicker <= ImaxStrictProfile.MAX_TEMPORAL_FLICKER_PERCENT / 100.0, measurements.temporalFlicker, "<=0.8% temporal flicker"),
            result(
                "S4",
                measurements.mtf50CyPerPx >= ImaxStrictProfile.MIN_MTF50_CY_PER_PX &&
                    measurements.haloOvershoot <= ImaxStrictProfile.MAX_OVERSHOOT_PERCENT / 100.0,
                "${measurements.mtf50CyPerPx.format6()}/${measurements.haloOvershoot.format6()}",
                "MTF50 >=0.34 cy/px and overshoot <=3%"
            ),
            result("S5", measurements.detailRetentionVsRaw >= ImaxStrictProfile.MIN_DETAIL_RETENTION_RAW, measurements.detailRetentionVsRaw, ">=93% vs RAW"),
            result(
                "S6",
                measurements.colorCheckerDeltaE2000Mean <= ImaxStrictProfile.MAX_DELTA_E2000_MEAN &&
                    measurements.colorCheckerDeltaE2000Max <= ImaxStrictProfile.MAX_DELTA_E2000,
                "${measurements.colorCheckerDeltaE2000Mean.format6()}/${measurements.colorCheckerDeltaE2000Max.format6()}",
                "ColorChecker dE2000 mean <=1.3 and max <=3.0"
            ),
            result(
                "S7",
                measurements.grayDeltaAAbs <= ImaxStrictProfile.MAX_GRAY_DELTA_ABS &&
                    measurements.grayDeltaBAbs <= ImaxStrictProfile.MAX_GRAY_DELTA_ABS,
                "${measurements.grayDeltaAAbs.format6()}/${measurements.grayDeltaBAbs.format6()}",
                "gray |da| and |db| <=1.0"
            ),
            result(
                "S8",
                measurements.deadHotPixels <= ImaxStrictProfile.MAX_DEAD_HOT_PIXELS &&
                    measurements.redClipBlueShadowSafe,
                "${measurements.deadHotPixels}/${measurements.redClipBlueShadowSafe}",
                "0 dead/hot pixels and safe skin/shadow color"
            ),
            result(
                "S9",
                measurements.wbApplied && measurements.ccmNonIdentity && measurements.vendorProfileFree,
                "${measurements.wbApplied}/${measurements.ccmNonIdentity}/${measurements.vendorProfileFree}",
                "WB applied, Sensor->XYZ CCM non-identity, project-owned metadata"
            ),
            result("S10", measurements.previewRecordExportParity, measurements.previewRecordExportParity.toString(), "Preview=Record=Export")
        )
        return EliteVisionQualityReport(
            passed = results.all { it.passed },
            results = results
        )
    }

    private fun result(id: String, passed: Boolean, measured: Double, threshold: String): EliteVisionQualityResult =
        result(id, passed, measured.format6(), threshold)

    private fun result(id: String, passed: Boolean, measured: String, threshold: String): EliteVisionQualityResult =
        EliteVisionQualityResult(id, passed, measured, threshold)

    private fun Double.format6(): String =
        String.format(Locale.US, "%.6f", this)
}
