package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NdrNivukxEliteVirtualCalibrationTest {

    @Test
    fun `elite virtual corpus exposes lakh million and crore scale deterministic samples`() {
        assertEquals(100_000, NdrNivukxEliteVirtualCalibrationGenerator.LAKH_SAMPLE_COUNT)
        assertEquals(1_000_000, NdrNivukxEliteVirtualCalibrationGenerator.ELITE_SAMPLE_COUNT)
        assertEquals(10_000_000, NdrNivukxEliteVirtualCalibrationGenerator.CRORE_STRESS_SAMPLE_COUNT)

        val first = NdrNivukxEliteVirtualCalibrationGenerator.sampleAt(0)
        val repeated = NdrNivukxEliteVirtualCalibrationGenerator.sampleAt(0)
        val far = NdrNivukxEliteVirtualCalibrationGenerator.sampleAt(9_999_999)

        assertEquals(first, repeated)
        assertEquals(0, first.index)
        assertEquals(9_999_999, far.index)
        assertTrue(first.referenceSrgb.r in 0f..1f)
        assertTrue(first.referenceSrgb.g in 0f..1f)
        assertTrue(first.referenceSrgb.b in 0f..1f)
        assertTrue(far.referenceSrgb.r in 0f..1f)
        assertTrue(far.referenceSrgb.g in 0f..1f)
        assertTrue(far.referenceSrgb.b in 0f..1f)
    }

    @Test
    fun `elite virtual corpus covers all high risk color roles`() {
        val roles = (0 until 4096)
            .map { NdrNivukxEliteVirtualCalibrationGenerator.sampleAt(it).role }
            .toSet()

        assertTrue(roles.contains(EliteCalibrationRole.NEUTRAL_RAMP))
        assertTrue(roles.contains(EliteCalibrationRole.SKIN_MEMORY))
        assertTrue(roles.contains(EliteCalibrationRole.SKY_GRADIENT))
        assertTrue(roles.contains(EliteCalibrationRole.HIGHLIGHT_ROLLOFF))
        assertTrue(roles.contains(EliteCalibrationRole.SHADOW_CHROMA))
        assertTrue(roles.contains(EliteCalibrationRole.FOLIAGE_MEMORY))
        assertTrue(roles.contains(EliteCalibrationRole.SATURATION_BOUNDARY))
        assertTrue(roles.contains(EliteCalibrationRole.WB_ILLUMINANT))
        assertTrue(roles.contains(EliteCalibrationRole.RED_FACE_GUARD))
        assertTrue(roles.contains(EliteCalibrationRole.CYAN_GREEN_GUARD))
        assertTrue(roles.contains(EliteCalibrationRole.TEXTURE_DETAIL))
        assertTrue(roles.contains(EliteCalibrationRole.LOW_NOISE_DARK))
    }

    @Test
    fun `elite virtual validation report enforces strict color gates`() {
        val report = NdrNivukxEliteCalibrationHarness.validate(
            sampleCount = 100_000,
            mode = EliteCalibrationMode.NIVUKX_VISION,
            stride = 37
        )

        assertEquals(100_000, report.sampleCount)
        assertTrue("mean hue drift must stay below strict target", report.meanHueDriftDegrees <= 0.50f)
        assertTrue("max skin hue drift must stay controlled", report.maxSkinHueDriftDegrees <= 2.0f)
        assertTrue("sky blue dominance failures must be near zero", report.skyBlueDominanceFailureRate <= 0.002f)
        assertTrue("highlight clipping must be near zero", report.highlightClipRate <= 0.001f)
        assertTrue("neutral balance must remain stable", report.maxNeutralChannelError <= 0.006f)
        assertTrue("report must pass all gates", report.passed)
    }

    @Test
    fun `elite image corpus exposes more than crore materializable calibration images`() {
        assertEquals(100_000_000L, NdrNivukxEliteCalibrationImageCorpus.IMAGE_COUNT)
        assertTrue(NdrNivukxEliteCalibrationImageCorpus.IMAGE_COUNT > 10_000_000L)
        assertEquals(8, NdrNivukxEliteCalibrationImageCorpus.IMAGE_WIDTH)
        assertEquals(8, NdrNivukxEliteCalibrationImageCorpus.IMAGE_HEIGHT)
        assertEquals(6_400_000_000L, NdrNivukxEliteCalibrationImageCorpus.TOTAL_PIXEL_SAMPLES)

        val first = NdrNivukxEliteCalibrationImageCorpus.pixelAt(imageIndex = 0L, x = 0, y = 0)
        val repeated = NdrNivukxEliteCalibrationImageCorpus.pixelAt(imageIndex = 0L, x = 0, y = 0)
        val far = NdrNivukxEliteCalibrationImageCorpus.pixelAt(
            imageIndex = NdrNivukxEliteCalibrationImageCorpus.IMAGE_COUNT - 1L,
            x = 7,
            y = 7
        )

        assertEquals(first, repeated)
        assertEquals(0L, first.imageIndex)
        assertEquals(NdrNivukxEliteCalibrationImageCorpus.IMAGE_COUNT - 1L, far.imageIndex)
        assertEquals(NdrNivukxEliteCalibrationImageCorpus.TOTAL_PIXEL_SAMPLES - 1L, far.globalPixelIndex)
        assertTrue(first.referenceSrgb.r in 0f..1f)
        assertTrue(first.referenceSrgb.g in 0f..1f)
        assertTrue(first.referenceSrgb.b in 0f..1f)
        assertTrue(far.referenceSrgb.r in 0f..1f)
        assertTrue(far.referenceSrgb.g in 0f..1f)
        assertTrue(far.referenceSrgb.b in 0f..1f)
    }

    @Test
    fun `elite image calibration validates generated image pixels across crore corpus`() {
        val report = NdrNivukxEliteCalibrationHarness.validateImages(
            requestedImageCount = NdrNivukxEliteCalibrationImageCorpus.IMAGE_COUNT,
            mode = EliteCalibrationMode.NIVUKX_VISION,
            imageStride = 104_729L,
            evaluationBudgetImages = 12_000
        )

        assertEquals(NdrNivukxEliteCalibrationImageCorpus.IMAGE_COUNT, report.requestedImageCount)
        assertEquals(12_000, report.evaluatedImageCount)
        assertEquals(768_000L, report.pixelSamplesEvaluated)
        assertTrue("mean hue drift must stay below elite image target", report.meanHueDriftDegrees <= 0.35f)
        assertTrue("max skin hue drift must stay tightly controlled", report.maxSkinHueDriftDegrees <= 1.5f)
        assertTrue("sky failures must be below elite target", report.skyBlueDominanceFailureRate <= 0.001f)
        assertTrue("highlight clipping must be below elite target", report.highlightClipRate <= 0.0005f)
        assertTrue("neutral balance must remain tighter than patch target", report.maxNeutralChannelError <= 0.004f)
        assertTrue("image corpus report must pass all gates", report.passed)
    }

    @Test
    fun `elite image calibration enforces every color role independently`() {
        val report = NdrNivukxEliteCalibrationHarness.validateImages(
            requestedImageCount = NdrNivukxEliteCalibrationImageCorpus.IMAGE_COUNT,
            mode = EliteCalibrationMode.NIVUKX_VISION,
            imageStride = 104_729L,
            evaluationBudgetImages = 12_000
        )
        val roleReports = report.roleReports.associateBy { it.role }

        assertEquals(EliteCalibrationRole.entries.toSet(), roleReports.keys)
        EliteCalibrationRole.entries.forEach { role ->
            val roleReport = roleReports.getValue(role)
            assertTrue("$role must be sampled", roleReport.pixelSamples > 0L)
            assertTrue("$role mean hue drift must stay bounded", roleReport.meanHueDriftDegrees <= 0.60f)
            assertTrue("$role must pass its own gate", roleReport.passed)
        }

        assertTrue(
            "skin roles require tighter hue protection",
            roleReports.getValue(EliteCalibrationRole.SKIN_MEMORY).maxHueDriftDegrees <= 1.5f &&
                roleReports.getValue(EliteCalibrationRole.RED_FACE_GUARD).maxHueDriftDegrees <= 1.5f
        )
        assertTrue(
            "sky role requires blue dominance stability",
            roleReports.getValue(EliteCalibrationRole.SKY_GRADIENT).skyBlueDominanceFailureRate <= 0.001f
        )
        assertTrue(
            "highlight role requires clip control",
            roleReports.getValue(EliteCalibrationRole.HIGHLIGHT_ROLLOFF).highlightClipRate <= 0.0005f
        )
        assertTrue(
            "neutral role requires channel balance",
            roleReports.getValue(EliteCalibrationRole.NEUTRAL_RAMP).maxNeutralChannelError <= 0.004f
        )
    }

    @Test
    fun `elite crore image corpus manifest is shipped as an app asset contract`() {
        val manifest = File(
            "src/main/assets/calibration/ndr_nivukx_crore/ndr_nivukx_crore_manifest.json"
        )

        assertTrue("crore image corpus manifest missing", manifest.isFile)
        val json = manifest.readText()

        assertTrue(json.contains("\"version\": \"NDR_NIVUKX_CRORE_IMAGE_CORPUS_V1\""))
        assertTrue(json.contains("\"image_count\": 100000000"))
        assertTrue(json.contains("\"image_width\": 8"))
        assertTrue(json.contains("\"image_height\": 8"))
        assertTrue(json.contains("\"total_pixel_samples\": 6400000000"))
        assertTrue(json.contains("\"materialization\": \"DETERMINISTIC_ON_DEMAND\""))
    }
}
