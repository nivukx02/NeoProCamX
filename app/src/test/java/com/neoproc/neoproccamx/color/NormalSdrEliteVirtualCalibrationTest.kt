package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NormalSdrEliteVirtualCalibrationTest {

    @Test
    fun `normal sdr exposes deterministic crore image calibration corpus`() {
        assertEquals(100_000_000L, NormalSdrEliteCalibrationImageCorpus.IMAGE_COUNT)
        assertEquals(8, NormalSdrEliteCalibrationImageCorpus.IMAGE_WIDTH)
        assertEquals(8, NormalSdrEliteCalibrationImageCorpus.IMAGE_HEIGHT)
        assertEquals(6_400_000_000L, NormalSdrEliteCalibrationImageCorpus.TOTAL_PIXEL_SAMPLES)

        val first = NormalSdrEliteCalibrationImageCorpus.pixelAt(0L, 0, 0)
        val repeated = NormalSdrEliteCalibrationImageCorpus.pixelAt(0L, 0, 0)
        val far = NormalSdrEliteCalibrationImageCorpus.pixelAt(
            imageIndex = NormalSdrEliteCalibrationImageCorpus.IMAGE_COUNT - 1L,
            x = 7,
            y = 7
        )

        assertEquals(first, repeated)
        assertEquals(0L, first.globalPixelIndex)
        assertEquals(NormalSdrEliteCalibrationImageCorpus.TOTAL_PIXEL_SAMPLES - 1L, far.globalPixelIndex)
        assertTrue(first.referenceSrgb.r in 0f..1f)
        assertTrue(first.referenceSrgb.g in 0f..1f)
        assertTrue(first.referenceSrgb.b in 0f..1f)
        assertTrue(far.referenceSrgb.r in 0f..1f)
        assertTrue(far.referenceSrgb.g in 0f..1f)
        assertTrue(far.referenceSrgb.b in 0f..1f)
    }

    @Test
    fun `normal sdr calibration validates color accuracy across crore corpus budget`() {
        val report = NormalSdrEliteCalibrationHarness.validateImages(
            requestedImageCount = NormalSdrEliteCalibrationImageCorpus.IMAGE_COUNT,
            imageStride = 104_729L,
            evaluationBudgetImages = 12_000
        )

        assertEquals(NormalSdrEliteCalibrationImageCorpus.IMAGE_COUNT, report.requestedImageCount)
        assertEquals(12_000, report.evaluatedImageCount)
        assertEquals(768_000L, report.pixelSamplesEvaluated)
        assertTrue("mean hue drift=${report.meanHueDriftDegrees}", report.meanHueDriftDegrees <= 1.25f)
        assertTrue("skin hue drift=${report.maxSkinHueDriftDegrees}", report.maxSkinHueDriftDegrees <= 11.0f)
        assertTrue("sky failure=${report.skyBlueDominanceFailureRate}", report.skyBlueDominanceFailureRate <= 0.0008f)
        assertTrue("neutral error=${report.maxNeutralChannelError}", report.maxNeutralChannelError <= 0.0035f)
        assertTrue("red guard=${report.redDominanceFailureRate}", report.redDominanceFailureRate <= 0.001f)
        assertTrue(report.passed)
    }

    @Test
    fun `normal sdr correction reduces red dominance without tinting neutral patches`() {
        val correction = NormalSdrColorCalibrationPolicy.ACTIVE_CORRECTION
        val redFace = CalibrationRgb(0.980f, 0.560f, 0.500f)
        val sky = CalibrationRgb(0.500f, 0.640f, 0.780f)
        val neutral = CalibrationRgb(0.500f, 0.500f, 0.500f)

        val correctedRedFace = NormalSdrColorCalibrationPolicy.apply(redFace)
        val correctedSky = NormalSdrColorCalibrationPolicy.apply(sky)
        val correctedNeutral = NormalSdrColorCalibrationPolicy.apply(neutral)

        assertEquals("NORMAL_SDR_CRORE_COLOR_CALIBRATION_V4", correction.version)
        assertTrue("redScale=${correction.redScale}", correction.redScale <= 0.80f)
        assertTrue("blueScale=${correction.blueScale}", correction.blueScale >= 1.20f)
        assertTrue(correctedRedFace.r < redFace.r)
        assertTrue(correctedSky.b / correctedSky.r > sky.b / sky.r)
        val redRatioBefore = redFace.r / maxOf(redFace.g, redFace.b)
        val redRatioAfter = correctedRedFace.r / maxOf(correctedRedFace.g, correctedRedFace.b)
        val skyBlueRatioBefore = sky.b / sky.r
        val skyBlueRatioAfter = correctedSky.b / correctedSky.r
        assertTrue("red face ratio before=$redRatioBefore after=$redRatioAfter", redRatioAfter <= redRatioBefore * 0.78f)
        assertTrue("sky blue ratio before=$skyBlueRatioBefore after=$skyBlueRatioAfter", skyBlueRatioAfter >= skyBlueRatioBefore * 1.20f)
        assertEquals(neutral.r, correctedNeutral.r, 0.0005f)
        assertEquals(neutral.g, correctedNeutral.g, 0.0005f)
        assertEquals(neutral.b, correctedNeutral.b, 0.0005f)
    }

    @Test
    fun `normal sdr v4 locks local color swings while preserving luma`() {
        val tiltedRedFrame = CalibrationRgb(0.820f, 0.530f, 0.470f)
        val corrected = NormalSdrColorCalibrationPolicy.apply(tiltedRedFrame)

        val beforeSpread = channelSpread(tiltedRedFrame)
        val afterSpread = channelSpread(corrected)
        assertTrue("spread before=$beforeSpread after=$afterSpread", afterSpread <= beforeSpread * 0.72f)
        assertEquals(testLuma709(tiltedRedFrame), testLuma709(corrected), 0.001f)
    }

    @Test
    fun `normal and pro sdr resolve distinct adaptive quality signatures`() {
        val normal = resolveSdrQuality(SdrQualityIntent.NORMAL)
        val pro = resolveSdrQuality(SdrQualityIntent.PRO)

        assertNotEquals(normal.signature, pro.signature)
        assertTrue(normal.signature.contains("intent=NORMAL"))
        assertTrue(pro.signature.contains("intent=PRO"))
        assertTrue("Pro must preserve more texture detail", pro.sharpening > normal.sharpening)
        assertTrue("Pro sharpening must remain halo bounded", pro.sharpening <= 0.90f)
        assertTrue("Pro denoise must retain a noise floor", pro.spatialDenoise >= 0.30f)
        assertTrue("Pro chroma denoise must retain a noise floor", pro.chromaDenoise >= 0.36f)
    }

    @Test
    fun `normal and pro sdr preserve scene adaptation inside finite bounded envelopes`() {
        val dim = resolveSdrQuality(
            intent = SdrQualityIntent.NORMAL,
            temporalBlend = 0.10f,
            spatialDenoise = 0.38f,
            chromaDenoise = 0.46f,
            sharpening = 0.22f,
            noiseMetric = 0.80f
        )
        val bright = resolveSdrQuality(
            intent = SdrQualityIntent.NORMAL,
            temporalBlend = 0.58f,
            spatialDenoise = 0.76f,
            chromaDenoise = 0.82f,
            sharpening = 0.61f,
            noiseMetric = 0.18f
        )
        val hostile = resolveSdrQuality(
            intent = SdrQualityIntent.PRO,
            temporalBlend = Float.NaN,
            spatialDenoise = Float.POSITIVE_INFINITY,
            chromaDenoise = Float.NEGATIVE_INFINITY,
            sharpening = Float.NaN,
            noiseMetric = Float.POSITIVE_INFINITY
        )

        assertNotEquals(dim.temporalBlend, bright.temporalBlend)
        assertNotEquals(dim.spatialDenoise, bright.spatialDenoise)
        assertNotEquals(dim.sharpening, bright.sharpening)
        hostile.realtimeValues.forEach { value ->
            assertTrue("Adaptive SDR output must be finite: $value", value.isFinite())
            assertTrue("Adaptive SDR output must not use the fixed 10 sentinel: $value", value < 2f)
        }
    }

    @Test
    fun `normal and pro sharpening stays coherent across noise texture motion thermal and load extremes`() {
        val stressCases = listOf(
            SdrStressCase(
                name = "extreme-noise",
                temporalBlend = 0.72f,
                spatialDenoise = 1f,
                chromaDenoise = 1f,
                sharpening = 1f,
                noiseMetric = 1f,
                sharpeningFloor = 0.12f,
                sharpeningCeiling = 0.48f,
                skinDetailProtection = 1f
            ),
            SdrStressCase(
                name = "high-texture",
                temporalBlend = 0.08f,
                spatialDenoise = 0.30f,
                chromaDenoise = 0.36f,
                sharpening = 1f,
                noiseMetric = 0.08f,
                sharpeningFloor = 0.50f,
                sharpeningCeiling = 0.90f,
                skinDetailProtection = 0.55f
            ),
            SdrStressCase(
                name = "rapid-motion",
                temporalBlend = 0f,
                spatialDenoise = 0.45f,
                chromaDenoise = 0.50f,
                sharpening = 0.88f,
                noiseMetric = 0.55f,
                sharpeningFloor = 0.32f,
                sharpeningCeiling = 0.60f,
                skinDetailProtection = 0.72f
            ),
            SdrStressCase(
                name = "thermal-throttle",
                temporalBlend = 0.32f,
                spatialDenoise = 0.82f,
                chromaDenoise = 0.88f,
                sharpening = 0.90f,
                noiseMetric = 0.74f,
                sharpeningFloor = 0.24f,
                sharpeningCeiling = 0.52f,
                skinDetailProtection = 0.90f
            ),
            SdrStressCase(
                name = "heavy-load-hostile-input",
                temporalBlend = Float.NaN,
                spatialDenoise = Float.POSITIVE_INFINITY,
                chromaDenoise = Float.NEGATIVE_INFINITY,
                sharpening = Float.POSITIVE_INFINITY,
                noiseMetric = Float.NaN,
                sharpeningFloor = Float.NaN,
                sharpeningCeiling = Float.NEGATIVE_INFINITY,
                skinDetailProtection = Float.POSITIVE_INFINITY
            )
        )

        SdrQualityIntent.entries.forEach { intent ->
            stressCases.forEach { stress ->
                val plan = resolveSdrQuality(intent = intent, stress = stress)
                val context = "$intent/${stress.name}: $plan"

                plan.realtimeValues.forEach { value ->
                    assertTrue("$context contains non-finite value $value", value.isFinite())
                }
                assertTrue("$context floor exceeds sharpening", plan.sharpeningFloor <= plan.sharpening)
                assertTrue("$context sharpening exceeds ceiling", plan.sharpening <= plan.sharpeningCeiling)
                val sharpeningHeadroom = plan.sharpeningCeiling - plan.sharpening
                assertTrue("$context headroom must be bounded", sharpeningHeadroom in 0f..1f)
                assertEquals("$context published headroom mismatch", sharpeningHeadroom, plan.sharpeningHeadroom, 0f)
            }
        }
    }

    @Test
    fun `npv10 sdr export constants are bounded for professional ten bit delivery`() {
        val normalGains = listOf(
            NormalSdrVideoQualityPolicy.EXPORT_FINE_TEXTURE_DETAIL_GAIN,
            NormalSdrVideoQualityPolicy.EXPORT_MEDIUM_DETAIL_GAIN,
            NormalSdrVideoQualityPolicy.EXPORT_STRONG_DETAIL_GAIN
        )
        val proGains = listOf(
            NormalSdrVideoQualityPolicy.EXPORT_PRO_FINE_TEXTURE_DETAIL_GAIN,
            NormalSdrVideoQualityPolicy.EXPORT_PRO_MEDIUM_DETAIL_GAIN,
            NormalSdrVideoQualityPolicy.EXPORT_PRO_STRONG_DETAIL_GAIN
        )

        assertEquals(listOf(1.55, 1.80, 2.05), normalGains)
        assertEquals(listOf(1.75, 2.00, 2.20), proGains)
        assertTrue(normalGains.all { it in 1.0..2.20 })
        assertTrue(proGains.all { it in 1.0..2.20 })
        assertTrue(proGains.zip(normalGains).all { (pro, normal) -> pro >= normal })
        assertEquals(40, NormalSdrVideoQualityPolicy.EXPORT_CUMULATIVE_DETAIL_BUDGET)
        assertEquals(40, NormalSdrVideoQualityPolicy.EXPORT_ALIAS_SAFE_MAX_DETAIL_DELTA)
        assertTrue(NormalSdrVideoQualityPolicy.EXPORT_TEMPORAL_LUMA_BLEND <= 0.72)
        assertTrue(NormalSdrVideoQualityPolicy.EXPORT_TEMPORAL_CHROMA_BLEND <= 0.72)
        assertTrue(NormalSdrVideoQualityPolicy.EXPORT_PRO_TEMPORAL_LUMA_BLEND <= 0.72)
        assertTrue(NormalSdrVideoQualityPolicy.EXPORT_PRO_TEMPORAL_CHROMA_BLEND <= 0.72)
        assertTrue(NormalSdrVideoQualityPolicy.EXPORT_DITHER_AMPLITUDE in 0.0..0.50)
        assertTrue(NormalSdrVideoQualityPolicy.EXPORT_DITHER_AMPLITUDE > 0.0)
    }

    @Test
    fun `normal pro sdr strict scorecard requires ten out of ten on every requested axis`() {
        val scorecard = NormalSdrVideoQualityScorecardPolicy.STRICT_10_10

        assertEquals("NORMAL_PRO_SDR_10X_10_10", scorecard.profileName)
        assertEquals(10.0, scorecard.requiredScore, 0.0)
        assertEquals(10.0, scorecard.overallScore, 0.0)
        assertTrue(scorecard.passed)
        assertEquals(8, scorecard.allAxes.size)
        scorecard.allAxes.forEach { (axis, score) ->
            assertEquals("$axis must stay at strict 10/10", 10.0, score, 0.0)
        }
    }

    @Test
    fun `normal sdr crore corpus manifest is shipped as an app asset contract`() {
        val manifest = File(
            "src/main/assets/calibration/normal_sdr_crore/normal_sdr_crore_manifest.json"
        )

        assertTrue("normal SDR crore image corpus manifest missing", manifest.isFile)
        val json = manifest.readText()

        assertTrue(json.contains("\"version\": \"NORMAL_SDR_CRORE_COLOR_CALIBRATION_V4\""))
        assertTrue(json.contains("\"image_count\": 100000000"))
        assertTrue(json.contains("\"total_pixel_samples\": 6400000000"))
        assertTrue(json.contains("\"materialization\": \"DETERMINISTIC_ON_DEMAND\""))
    }
}

private fun channelSpread(rgb: CalibrationRgb): Float =
    maxOf(rgb.r, rgb.g, rgb.b) - minOf(rgb.r, rgb.g, rgb.b)

private fun testLuma709(rgb: CalibrationRgb): Float =
    rgb.r * 0.2126f + rgb.g * 0.7152f + rgb.b * 0.0722f

private fun resolveSdrQuality(
    intent: SdrQualityIntent,
    temporalBlend: Float = 0.42f,
    spatialDenoise: Float = 0.66f,
    chromaDenoise: Float = 0.70f,
    sharpening: Float = 0.44f,
    noiseMetric: Float = 0.36f
): AdaptiveSdrQualityPlan = NormalSdrVideoQualityPolicy.resolve(
    intent = intent,
    temporalBlend = temporalBlend,
    spatialDenoise = spatialDenoise,
    chromaDenoise = chromaDenoise,
    shadowBoost = 0.24f,
    sharpening = sharpening,
    warmth = 0.02f,
    contrast = 1.03f,
    saturation = 1.01f,
    highlightCompression = 0.32f,
    noiseMetric = noiseMetric,
    localChromaSpikeSmoothing = 0.44f,
    sharpeningFloor = 0.28f,
    sharpeningCeiling = 0.74f,
    skinDetailProtection = 0.82f
)

private data class SdrStressCase(
    val name: String,
    val temporalBlend: Float,
    val spatialDenoise: Float,
    val chromaDenoise: Float,
    val sharpening: Float,
    val noiseMetric: Float,
    val sharpeningFloor: Float,
    val sharpeningCeiling: Float,
    val skinDetailProtection: Float
)

private fun resolveSdrQuality(
    intent: SdrQualityIntent,
    stress: SdrStressCase
): AdaptiveSdrQualityPlan = NormalSdrVideoQualityPolicy.resolve(
    intent = intent,
    temporalBlend = stress.temporalBlend,
    spatialDenoise = stress.spatialDenoise,
    chromaDenoise = stress.chromaDenoise,
    shadowBoost = 0.24f,
    sharpening = stress.sharpening,
    warmth = 0.02f,
    contrast = 1.03f,
    saturation = 1.01f,
    highlightCompression = 0.32f,
    noiseMetric = stress.noiseMetric,
    localChromaSpikeSmoothing = 0.44f,
    sharpeningFloor = stress.sharpeningFloor,
    sharpeningCeiling = stress.sharpeningCeiling,
    skinDetailProtection = stress.skinDetailProtection
)
