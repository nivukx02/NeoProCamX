package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * CP-Q1 — the measurement harness must produce real numbers from real pixels: a known-good golden
 * set must satisfy the elite-vision contract, and a degraded set must fail the specific pixel
 * checks (MTF50/retention, SNR/noise, ColorChecker dE2000). Synthetic goldens here stand in for
 * decoded camera/golden frames; the math path is identical.
 */
class EliteVisionMeasurementHarnessTest {

    @Test
    fun goodGoldensMeasureWithinSpecAndPassContract() {
        val frames = goodGoldens()
        val measured = EliteVisionMeasurementHarness.measure(frames, passingBaseline())

        // MTF50 of a sharp edge clears the 0.34 cy/px floor; retention vs RAW reference clears 0.92.
        assertTrue("mtf50=${measured.mtf50CyPerPx}", measured.mtf50CyPerPx >= 0.34)
        assertTrue("retention=${measured.mtf50Retention}", measured.mtf50Retention >= 0.92)
        // Clean flat fields exceed the SNR floors and stay under the IRE noise ceilings.
        assertTrue("baseSnr=${measured.lumaSnrBaseDb}", measured.lumaSnrBaseDb >= 50.0)
        assertTrue("highSnr=${measured.lumaSnrHighIsoDb}", measured.lumaSnrHighIsoDb >= 40.0)
        assertTrue("baseIre=${measured.noiseSigmaIreBase}", measured.noiseSigmaIreBase <= 0.4)
        assertTrue("highIre=${measured.noiseSigmaIreIso3200}", measured.noiseSigmaIreIso3200 <= 0.8)
        // Near-reference ColorChecker → tiny dE2000.
        assertTrue("deMean=${measured.colorCheckerDeltaE2000Mean}", measured.colorCheckerDeltaE2000Mean <= 1.3)
        assertTrue("deMax=${measured.colorCheckerDeltaE2000Max}", measured.colorCheckerDeltaE2000Max <= 3.0)

        val report = EliteVisionQualityContract.evaluate(measured)
        assertTrue(
            "expected pass, failures=${report.results.filterNot { it.passed }.map { it.id }}",
            report.passed
        )
    }

    @Test
    fun degradedGoldensFailThePixelChecks() {
        val frames = degradedGoldens()
        val measured = EliteVisionMeasurementHarness.measure(frames, passingBaseline())

        assertTrue("blurred edge must drop MTF50 below floor", measured.mtf50CyPerPx < 0.34)
        assertTrue("noisy base field must drop SNR below floor", measured.lumaSnrBaseDb < 50.0)
        assertTrue("off ColorChecker must raise mean dE2000", measured.colorCheckerDeltaE2000Mean > 1.3)

        val report = EliteVisionQualityContract.evaluate(measured)
        assertFalse(report.passed)
        assertTrue(report.results.any { it.id == "S4" && !it.passed }) // MTF50 + overshoot
        assertTrue(report.results.any { it.id == "S1" && !it.passed }) // luma SNR
        assertTrue(report.results.any { it.id == "S6" && !it.passed }) // ColorChecker dE2000
    }

    @Test
    fun nonPixelFieldsAreCarriedOverFromBaseline() {
        val baseline = passingBaseline().copy(
            metadataBitrateRatio = 0.00099,
            nlogsRoundTripDeltaE2000Max = 0.11,
            tmbSignatureVerified = true,
            deterministicHashMatch = true
        )
        val measured = EliteVisionMeasurementHarness.measure(goodGoldens(), baseline)
        assertEquals(0.00099, measured.metadataBitrateRatio, 1e-12)
        assertEquals(0.11, measured.nlogsRoundTripDeltaE2000Max, 1e-12)
        assertTrue(measured.tmbSignatureVerified)
        assertTrue(measured.deterministicHashMatch)
    }

    @Test
    fun regressionLock_measuredValuesStayPinnedForDeterministicGoldens() {
        // CP-Q5: a CI-mandatory regression guard. The goldens are fully deterministic, so the
        // measured metrics must stay pinned — if the measurement path (or, later, the pipeline
        // feeding it) is weakened, these bounds trip CI. This is the "no downgrades" guardrail.
        val m = EliteVisionMeasurementHarness.measure(goodGoldens(), passingBaseline())
        // Flat fields are exact half±sigma → SNR and IRE noise are analytic constants.
        assertEquals(52.4, m.lumaSnrBaseDb, 0.2)            // 20log10(0.5/0.0012)
        assertEquals(41.94, m.lumaSnrHighIsoDb, 0.2)        // 20log10(0.5/0.004)
        assertEquals(0.12, m.noiseSigmaIreBase, 0.01)       // 0.0012*100
        assertEquals(0.40, m.noiseSigmaIreIso3200, 0.01)    // 0.004*100
        // Sharp erf edge (sigma 0.42): MTF50 ~0.45 cy/px, retention clamps to 1.0 vs 0.38 ref.
        assertTrue("mtf50 pinned ~0.40-0.47, got ${m.mtf50CyPerPx}", m.mtf50CyPerPx in 0.40..0.47)
        assertEquals(1.0, m.mtf50Retention, 1e-9)
        // Near-reference ColorChecker → sub-unit dE2000; static scene → flicker == jitter.
        assertTrue("deMean pinned <0.6, got ${m.colorCheckerDeltaE2000Mean}", m.colorCheckerDeltaE2000Mean < 0.6)
        assertEquals(0.005, m.temporalFlicker, 1e-4)
    }

    @Test
    fun tierOmegaMetadataCarriesMeasuredMtf50AndAllRequiredKeys() {
        val measured = EliteVisionMeasurementHarness.measure(goodGoldens(), passingBaseline())
        val metadata = EliteVisionMeasurementHarness.toTierOmegaMetadata(measured)

        // The MTF50 metric key now carries the REAL measured value, not a fixture.
        val mtf50FromMetadata = metadata["tierOmegaMtf50CyPerPx"]?.toDouble()
        assertEquals(measured.mtf50CyPerPx, mtf50FromMetadata!!, 1e-6)

        // Every key the Tier-Omega evidence gate requires must be present (mirrors
        // RecordingQualityEvidencePolicy.TIER_OMEGA_REQUIRED_METRIC_KEYS).
        val required = listOf(
            "tierOmegaLumaSnrBaseDb", "tierOmegaLumaSnrHighIsoDb",
            "tierOmegaChromaNoiseBaseStd", "tierOmegaChromaNoiseHighIsoStd",
            "tierOmegaTemporalFlickerPercent", "tierOmegaMtf50CyPerPx",
            "tierOmegaOvershootPercent", "tierOmegaDetailRetentionRaw",
            "tierOmegaDetailRetentionHighIsoRaw", "tierOmegaDeltaE2000Mean",
            "tierOmegaDeltaE2000Max", "tierOmegaGrayDeltaAAbs", "tierOmegaGrayDeltaBAbs",
            "tierOmegaBandingScore", "tierOmegaDeadHotPixels", "tierOmegaWbApplied",
            "tierOmegaCcmNonIdentity", "tierOmegaPreviewRecordExportParity"
        )
        required.forEach { assertTrue("missing $it", metadata.containsKey(it)) }
        // Booleans are emitted in the strict form the gate parses.
        assertEquals("true", metadata["tierOmegaWbApplied"])
        // Flicker fraction is converted to percent for the gate's percent threshold.
        assertEquals(measured.temporalFlicker * 100.0, metadata["tierOmegaTemporalFlickerPercent"]!!.toDouble(), 1e-6)
    }

    @Test
    fun tierOmegaMtf50KeyReflectsDegradedMeasurement() {
        val measured = EliteVisionMeasurementHarness.measure(degradedGoldens(), passingBaseline())
        val metadata = EliteVisionMeasurementHarness.toTierOmegaMetadata(measured)
        // A blurred edge drives the metric key below the elite MTF50 floor — real signal flows.
        assertTrue(metadata["tierOmegaMtf50CyPerPx"]!!.toDouble() < 0.34)
    }

    // ---- synthetic golden builders ----

    private fun goodGoldens(): EliteVisionMeasurementHarness.GoldenFrames {
        val (edge, w, h) = slantedEdge(64, 64, edge0 = 32.0, slope = 0.06, sigma = 0.42)
        return EliteVisionMeasurementHarness.GoldenFrames(
            edgeLuma = edge,
            edgeWidth = w,
            edgeHeight = h,
            referenceRawMtf50CyPerPx = 0.38,
            baseIsoFlatLuma = flatField(mean = 0.5f, sigma = 0.0012f, n = 4096),
            highIsoFlatLuma = flatField(mean = 0.5f, sigma = 0.004f, n = 4096),
            colorCheckerMeasuredLab = colorChecker(perturbation = 0.4f),
            colorCheckerReferenceLab = colorChecker(perturbation = 0f),
            grayPatchLab = floatArrayOf(50f, 0.4f, 0.5f),
            temporalSequence = staticSequence(jitter = 0.005f, n = 1024)
        )
    }

    private fun degradedGoldens(): EliteVisionMeasurementHarness.GoldenFrames {
        val (edge, w, h) = slantedEdge(64, 64, edge0 = 32.0, slope = 0.06, sigma = 2.2)
        return EliteVisionMeasurementHarness.GoldenFrames(
            edgeLuma = edge,
            edgeWidth = w,
            edgeHeight = h,
            referenceRawMtf50CyPerPx = 0.38,
            baseIsoFlatLuma = flatField(mean = 0.5f, sigma = 0.05f, n = 4096),
            highIsoFlatLuma = flatField(mean = 0.5f, sigma = 0.06f, n = 4096),
            colorCheckerMeasuredLab = colorChecker(perturbation = 8f),
            colorCheckerReferenceLab = colorChecker(perturbation = 0f),
            grayPatchLab = floatArrayOf(50f, 0.4f, 0.5f),
            temporalSequence = staticSequence(jitter = 0.005f, n = 1024)
        )
    }

    /** Exact-sigma flat field: half samples at mean+sigma, half at mean-sigma. */
    private fun flatField(mean: Float, sigma: Float, n: Int): FloatArray =
        FloatArray(n) { if (it % 2 == 0) mean + sigma else mean - sigma }

    /** Static scene of two frames at base±jitter → temporal std == jitter. */
    private fun staticSequence(jitter: Float, n: Int): List<FloatArray> {
        val base = FloatArray(n) { 0.4f + 0.2f * (it.toFloat() / n) }
        return listOf(
            FloatArray(n) { base[it] + jitter },
            FloatArray(n) { base[it] - jitter }
        )
    }

    /** Six CIELAB patches; [perturbation] shifts the a/b chroma axes to inject a known error. */
    private fun colorChecker(perturbation: Float): List<FloatArray> = listOf(
        floatArrayOf(38f, 14f + perturbation, 15f),
        floatArrayOf(66f, 13f, 17f + perturbation),
        floatArrayOf(51f, -10f - perturbation, -23f),
        floatArrayOf(43f, -16f, 22f + perturbation),
        floatArrayOf(56f, 9f + perturbation, -25f),
        floatArrayOf(72f, -23f, 58f - perturbation)
    )

    private fun slantedEdge(
        width: Int,
        height: Int,
        edge0: Double,
        slope: Double,
        sigma: Double
    ): Triple<FloatArray, Int, Int> {
        val img = FloatArray(width * height)
        val s = sigma * sqrt(2.0)
        for (y in 0 until height) {
            val edge = edge0 + slope * y
            for (x in 0 until width) {
                img[y * width + x] = (0.5 * (1.0 + erf((x - edge) / s))).toFloat()
            }
        }
        return Triple(img, width, height)
    }

    private fun erf(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * abs(x))
        val y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return sign(x) * y
    }

    private fun passingBaseline(): EliteVisionQualityMeasurements =
        EliteVisionQualityMeasurements(
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
            lumaSnrBaseDb = 51.0,
            lumaSnrHighIsoDb = 41.0,
            chromaNoiseStd = 0.34,
            temporalFlicker = 0.007,
            mtf50CyPerPx = 0.35,
            detailRetentionVsRaw = 0.94,
            colorCheckerDeltaE2000Mean = 1.1,
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
}
