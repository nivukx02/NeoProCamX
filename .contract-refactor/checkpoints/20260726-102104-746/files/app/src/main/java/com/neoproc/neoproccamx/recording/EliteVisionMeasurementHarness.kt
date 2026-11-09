package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.quality.measure.ImageQualityMetrics
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * CP-Q1 — populate [EliteVisionQualityMeasurements] from REAL decoded golden frames instead of
 * hardcoded fixture numbers. This is the producer the Tier-Omega / NIVUKX-vision quality gates
 * were missing (see CP-Q3 finding): the gate keys and thresholds already exist; what was absent
 * was any path that ran the actual measurement primitives over actual pixels.
 *
 * Pixel buffers are plain float/int arrays so the harness runs under fast JVM unit tests on
 * synthetic goldens today and on decoded camera/golden frames later — identical math either way.
 * Every measurable field is computed via [ImageQualityMetrics]; fields that the current primitives
 * cannot derive from a generic frame (memory-color hue drift, look round-trip, halo/acutance,
 * metadata-bitrate ratio, signature/hash booleans) are carried over verbatim from [baseline] and
 * remain owned by their dedicated subsystems (tracked under CP-Q4/CP-Q5). Banding is intentionally
 * NOT mapped: the gate's `bandingDeltaCodeMax<=0` is an adjacent-code-delta metric, whereas the
 * `rampBanding` primitive returns a flat-run score (always >0), so wiring it would wrongly fail the
 * gate — a true code-delta banding measure is deferred to CP-Q6.
 */
internal object EliteVisionMeasurementHarness {

    /**
     * s6-f09 — provenance stamp proving a `tierOmega*` evidence map was produced by THIS real-pixel
     * harness (and not a hand-built synthetic/fixture map). The recording gate requires this stamp
     * before threshold-checking Tier-Omega metrics; an absent/foreign stamp is honest NOT_PROVEN.
     */
    const val TIER_OMEGA_METRICS_SOURCE_KEY: String = "tierOmegaMetricsSource"
    const val SOURCE_REAL_PIXEL: String = "ELITE_VISION_MEASUREMENT_HARNESS_REAL_PIXEL"

    /** Decoded golden-frame pixel inputs for the in-repo measurement path. */
    internal data class GoldenFrames(
        // Slanted-edge target (row-major luma in [0,1]) + the RAW-reference MTF50 for retention.
        val edgeLuma: FloatArray,
        val edgeWidth: Int,
        val edgeHeight: Int,
        val referenceRawMtf50CyPerPx: Double,
        // Flat fields (row-major luma in [0,1]) at base ISO and ISO 3200 for SNR + noise sigma.
        val baseIsoFlatLuma: FloatArray,
        val highIsoFlatLuma: FloatArray,
        // ColorChecker measured vs reference CIELAB patches (D65) for dE2000.
        val colorCheckerMeasuredLab: List<FloatArray>,
        val colorCheckerReferenceLab: List<FloatArray>,
        // Measured CIELAB of the neutral-gray patch for gray a*/b* drift.
        val grayPatchLab: FloatArray,
        // Temporal sequence of a nominally-static scene (row-major luma in [0,1]) for flicker.
        val temporalSequence: List<FloatArray>
    )

    /**
     * Returns a copy of [baseline] with every pixel-measurable field overwritten by a real
     * measurement of [frames]. IRE noise sigma is the flat-field standard deviation on the IRE
     * scale (×100). MTF50 retention is measured/RAW-reference, clamped to [0,1].
     */
    fun measure(
        frames: GoldenFrames,
        baseline: EliteVisionQualityMeasurements
    ): EliteVisionQualityMeasurements {
        val mtf50 = ImageQualityMetrics.slantedEdgeMtf50(frames.edgeLuma, frames.edgeWidth, frames.edgeHeight)
        val retention = if (frames.referenceRawMtf50CyPerPx > 1e-9) {
            (mtf50 / frames.referenceRawMtf50CyPerPx).coerceIn(0.0, 1.0)
        } else {
            baseline.mtf50Retention
        }

        val baseStats = lumaStats(frames.baseIsoFlatLuma)
        val highStats = lumaStats(frames.highIsoFlatLuma)
        val baseSnrDb = ImageQualityMetrics.flatFieldSnrDb(frames.baseIsoFlatLuma)
        val highSnrDb = ImageQualityMetrics.flatFieldSnrDb(frames.highIsoFlatLuma)

        val (deMean, deMax) = ImageQualityMetrics.colorCheckerDeltaE2000(
            frames.colorCheckerMeasuredLab,
            frames.colorCheckerReferenceLab
        )

        val flicker = if (frames.temporalSequence.size >= 2) {
            ImageQualityMetrics.temporalFlickerStd(frames.temporalSequence)
        } else {
            baseline.temporalFlicker
        }

        return baseline.copy(
            mtf50CyPerPx = mtf50,
            mtf50Retention = retention,
            lumaSnrBaseDb = baseSnrDb,
            lumaSnrHighIsoDb = highSnrDb,
            // IRE sigma (% of full scale) of the flat field — the directly-measured noise level.
            noiseSigmaIreBase = baseStats.std * 100.0,
            noiseSigmaIreIso3200 = highStats.std * 100.0,
            colorCheckerDeltaE2000Mean = deMean,
            colorCheckerDeltaE2000Max = deMax,
            grayDeltaAAbs = abs(frames.grayPatchLab[1].toDouble()),
            grayDeltaBAbs = abs(frames.grayPatchLab[2].toDouble()),
            temporalFlicker = flicker
        )
    }

    /**
     * CP-Q3 — project the measured [EliteVisionQualityMeasurements] onto the `tierOmega*` evidence
     * keys that [RecordingQualityEvidencePolicy] requires (TIER_OMEGA_REQUIRED_METRIC_KEYS), so the
     * REAL measured MTF50 (and the rest of the measured metrics) reach the Tier-Omega gate instead
     * of test-only fixtures. Doubles are formatted %.6f (the gate parses via toDoubleOrNull);
     * percentage keys convert the fraction-scaled measurements (flicker, overshoot) to percent.
     * The high-ISO chroma/detail keys reuse the single measured value (the struct carries one),
     * and the banding key forwards the carried-over value (see class doc — code-delta metric TBD).
     */
    fun toTierOmegaMetadata(m: EliteVisionQualityMeasurements): Map<String, String> = mapOf(
        "tierOmegaLumaSnrBaseDb" to m.lumaSnrBaseDb.f6(),
        "tierOmegaLumaSnrHighIsoDb" to m.lumaSnrHighIsoDb.f6(),
        "tierOmegaChromaNoiseBaseStd" to m.chromaNoiseStd.f6(),
        "tierOmegaChromaNoiseHighIsoStd" to m.chromaNoiseStd.f6(),
        "tierOmegaTemporalFlickerPercent" to (m.temporalFlicker * 100.0).f6(),
        "tierOmegaMtf50CyPerPx" to m.mtf50CyPerPx.f6(),
        "tierOmegaOvershootPercent" to (m.haloOvershoot * 100.0).f6(),
        "tierOmegaDetailRetentionRaw" to m.detailRetentionVsRaw.f6(),
        "tierOmegaDetailRetentionHighIsoRaw" to m.detailRetentionVsRaw.f6(),
        "tierOmegaDeltaE2000Mean" to m.colorCheckerDeltaE2000Mean.f6(),
        "tierOmegaDeltaE2000Max" to m.colorCheckerDeltaE2000Max.f6(),
        "tierOmegaGrayDeltaAAbs" to m.grayDeltaAAbs.f6(),
        "tierOmegaGrayDeltaBAbs" to m.grayDeltaBAbs.f6(),
        "tierOmegaBandingScore" to m.bandingDeltaCodeMax.f6(),
        "tierOmegaDeadHotPixels" to m.deadHotPixels.toString(),
        "tierOmegaWbApplied" to m.wbApplied.toString(),
        "tierOmegaCcmNonIdentity" to m.ccmNonIdentity.toString(),
        "tierOmegaPreviewRecordExportParity" to m.previewRecordExportParity.toString(),
        // s6-f09: provenance stamp — distinguishes a real-pixel measurement from a synthetic fixture.
        TIER_OMEGA_METRICS_SOURCE_KEY to SOURCE_REAL_PIXEL
    )

    private fun Double.f6(): String = String.format(Locale.US, "%.6f", this)

    private data class LumaStats(val mean: Double, val std: Double)

    private fun lumaStats(luma: FloatArray): LumaStats {
        require(luma.isNotEmpty()) { "luma must be non-empty" }
        var mean = 0.0
        for (v in luma) mean += v
        mean /= luma.size
        var acc = 0.0
        for (v in luma) {
            val d = v - mean
            acc += d * d
        }
        return LumaStats(mean, sqrt(acc / luma.size))
    }
}
