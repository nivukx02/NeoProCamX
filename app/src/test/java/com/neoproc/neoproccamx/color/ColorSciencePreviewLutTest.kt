package com.neoproc.neoproccamx.color

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSciencePreviewLutTest {

    private fun repoFile(relativePath: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            val candidate = File(dir, relativePath)
            if (candidate.exists()) return candidate
            val parent = dir.parentFile
            if (parent != null) {
                dir = parent
            }
        }
        return File(relativePath)
    }

    private fun expectedHighFidelityPreviewSize(): Int =
        HighEndQualityPolicy.lutSizeForPreview(
            highFidelity = true,
            previewFps = HighEndQualityPolicy.maxPreviewFps.toFloat(),
            recording = false
        )

    private fun sampleLuma(values: FloatArray, size: Int, normalizedPoint: Float): Float {
        val index = (normalizedPoint * (size - 1)).toInt().coerceIn(0, size - 1)
        val base = (index + index * size + index * size * size) * 3
        val r = values[base]
        val g = values[base + 1]
        val b = values[base + 2]
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun sampleRgb(values: FloatArray, size: Int, normalizedPoint: Float): Triple<Float, Float, Float> {
        val index = (normalizedPoint * (size - 1)).toInt().coerceIn(0, size - 1)
        val base = (index + index * size + index * size * size) * 3
        return Triple(values[base], values[base + 1], values[base + 2])
    }

    private fun sampleRgb(
        values: FloatArray,
        size: Int,
        red: Float,
        green: Float,
        blue: Float
    ): Triple<Float, Float, Float> {
        val ri = (red * (size - 1)).toInt().coerceIn(0, size - 1)
        val gi = (green * (size - 1)).toInt().coerceIn(0, size - 1)
        val bi = (blue * (size - 1)).toInt().coerceIn(0, size - 1)
        val base = (ri + gi * size + bi * size * size) * 3
        return Triple(values[base], values[base + 1], values[base + 2])
    }

    @Test
    fun neutralLookDoesNotBuildPreviewLut() {
        val colorScience = ColorScience()

        assertNull(colorScience.getPreviewLutFloatArray())
    }

    @Test
    fun logPipelineBuildsBoundedPreviewLut() {
        val colorScience = ColorScience().apply {
            logEnabled = true
        }

        val lut = colorScience.getPreviewLutFloatArray()

        assertNotNull(lut)
        assertEquals(expectedHighFidelityPreviewSize(), lut!!.second)
        assertTrue(lut.first.isNotEmpty())
        assertTrue(lut.first.all { it in 0f..1f })
    }

    @Test
    fun creativeLutBuildsPreviewCubeWithRgbTriplets() {
        val colorScience = ColorScience().apply {
            setBuiltInLut("Warm Cinema")
            setLutToggleEnabled(true)
        }

        val (values, size) = colorScience.getPreviewLutFloatArray()
            ?: error("Preview LUT should be generated when creative LUT is enabled")

        assertEquals(expectedHighFidelityPreviewSize(), size)
        assertEquals(size * size * size * 3, values.size)
        assertTrue(values.all { it in 0f..1f })
    }

    @Test
    fun manualColorAdjustmentsAlsoForcePreviewLutGeneration() {
        val colorScience = ColorScience().apply {
            contrast = 1.08f
            brightness = 0.03f
            saturation = 0.94f
            highlights = 0.12f
            shadows = 0.08f
        }

        val lut = colorScience.getPreviewLutFloatArray()

        assertNotNull(lut)
        assertEquals(expectedHighFidelityPreviewSize(), lut!!.second)
        assertTrue(lut.first.all { it in 0f..1f })
    }

    @Test
    fun unchangedColorConfigurationReusesPreviewLutStorageWithoutChangingContent() {
        withStandardQualityPolicy {
            val colorScience = ColorScience().apply {
                brightness = 0.03f
            }

            val first = colorScience.getPreviewLutFloatArray()
                ?: error("Manual brightness must produce a preview LUT")
            val second = colorScience.getPreviewLutFloatArray()
                ?: error("Unchanged manual brightness must retain the preview LUT")

            assertSame("Unchanged configuration regenerated LUT storage", first.first, second.first)
            assertEquals(first.second, second.second)
            assertTrue(first.first.contentEquals(second.first))
        }
    }

    @Test
    fun manualSelectionChangeInvalidatesPreviewLutCacheAndPreservesPriorContent() {
        withStandardQualityPolicy {
            val colorScience = ColorScience().apply {
                brightness = 0.02f
            }
            val first = colorScience.getPreviewLutFloatArray()
                ?: error("Initial manual selection must produce a preview LUT")
            val firstContent = first.first.copyOf()

            colorScience.brightness = 0.08f
            val changed = colorScience.getPreviewLutFloatArray()
                ?: error("Changed manual selection must produce a preview LUT")

            assertNotSame("Changed configuration reused stale LUT storage", first.first, changed.first)
            assertTrue("Changed manual selection did not change LUT content", !first.first.contentEquals(changed.first))
            assertTrue("Cached prior LUT content was mutated", firstContent.contentEquals(first.first))
        }
    }

    @Test
    fun concurrentColdPreviewLutRequestsPublishOneCompleteImmutableResult() {
        withStandardQualityPolicy {
            val colorScience = ColorScience().apply {
                brightness = 0.04f
            }
            val workerCount = 8
            val start = CountDownLatch(1)
            val done = CountDownLatch(workerCount)
            val executor = Executors.newFixedThreadPool(workerCount)
            val results = Collections.synchronizedList(mutableListOf<Pair<FloatArray, Int>>())

            repeat(workerCount) {
                executor.execute {
                    try {
                        start.await()
                        colorScience.getPreviewLutFloatArray()?.let(results::add)
                    } finally {
                        done.countDown()
                    }
                }
            }

            start.countDown()
            assertTrue("Concurrent LUT requests did not finish", done.await(30, TimeUnit.SECONDS))
            executor.shutdownNow()

            assertEquals(workerCount, results.size)
            val first = results.first()
            results.forEach { result ->
                assertSame("Concurrent request regenerated LUT storage", first.first, result.first)
                assertEquals(first.second, result.second)
                assertTrue("Concurrent request observed partial LUT content", first.first.contentEquals(result.first))
                assertTrue("Concurrent request observed non-finite LUT content", result.first.all(Float::isFinite))
            }
        }
    }

    @Test
    fun hdr10ProfileBuildsPreviewLutWithPreservedWarmHighlightOrdering() {
        val colorScience = ColorScience().apply {
            loadBuiltInProfile("HDR10 Native")
        }

        val (values, size) = colorScience.getPreviewLutFloatArray()
            ?: error("HDR10 profile should build a preview LUT")

        assertEquals(expectedHighFidelityPreviewSize(), size)
        assertTrue(values.all { it in 0f..1f })

        val ri = (size - 2).coerceAtLeast(0)
        val gi = (size * 3 / 5).coerceIn(0, size - 1)
        val bi = (size / 4).coerceIn(0, size - 1)
        val base = (ri + gi * size + bi * size * size) * 3

        val red = values[base]
        val green = values[base + 1]
        val blue = values[base + 2]
        assertTrue(red >= green)
        assertTrue(green >= blue)
    }

    @Test
    fun logAndWideGamutPreviewLut_preserveMidtoneVisibility() {
        val logColorScience = ColorScience().apply {
            logEnabled = true
        }
        val rec2020ColorScience = ColorScience().apply {
            loadBuiltInProfile("Rec.2020")
        }

        val logLut = logColorScience.getPreviewLutFloatArray()
            ?: error("LOG preview LUT should exist")
        val rec2020Lut = rec2020ColorScience.getPreviewLutFloatArray()
            ?: error("Rec.2020 preview LUT should exist")

        val logMidtoneLuma = sampleLuma(logLut.first, logLut.second, normalizedPoint = 0.5f)
        val rec2020MidtoneLuma = sampleLuma(rec2020Lut.first, rec2020Lut.second, normalizedPoint = 0.5f)

        assertTrue(logMidtoneLuma > 0.42f)
        assertTrue(rec2020MidtoneLuma > 0.45f)
    }

    @Test
    fun genericLogOnNaturalProfile_matchesExplicitNLogPreviewCurve() {
        val genericLog = ColorScience().apply {
            logEnabled = true
        }
        val explicitNLog = ColorScience().apply {
            loadBuiltInProfile("N-Log")
        }

        val genericLut = genericLog.getPreviewLutFloatArray()
            ?: error("Generic LOG preview LUT should exist")
        val explicitLut = explicitNLog.getPreviewLutFloatArray()
            ?: error("Explicit N-Log preview LUT should exist")

        assertEquals(explicitLut.second, genericLut.second)
        val size = genericLut.second
        val sampleIndices = listOf(
            ((size / 4 + (size / 4) * size + (size / 4) * size * size) * 3),
            ((size / 2 + (size / 2) * size + (size / 2) * size * size) * 3),
            (((size * 3 / 4) + (size * 5 / 8) * size + (size * 9 / 16) * size * size) * 3)
        )
        sampleIndices.forEach { base ->
            assertEquals(explicitLut.first[base], genericLut.first[base], 0.012f)
            assertEquals(explicitLut.first[base + 1], genericLut.first[base + 1], 0.012f)
            assertEquals(explicitLut.first[base + 2], genericLut.first[base + 2], 0.012f)
        }
    }

    @Test
    fun wideGamutAndHdrPreviewLut_keepNeutralMidtonesBalanced() {
        val rec2020 = ColorScience().apply {
            loadBuiltInProfile("Rec.2020")
        }
        val hdr10 = ColorScience().apply {
            loadBuiltInProfile("HDR10 Native")
        }

        val recLut = rec2020.getPreviewLutFloatArray()
            ?: error("Rec.2020 preview LUT should exist")
        val hdrLut = hdr10.getPreviewLutFloatArray()
            ?: error("HDR10 preview LUT should exist")

        val recMid = sampleRgb(recLut.first, recLut.second, normalizedPoint = 0.5f)
        val hdrMid = sampleRgb(hdrLut.first, hdrLut.second, normalizedPoint = 0.5f)

        val recSpread = maxOf(recMid.first, recMid.second, recMid.third) -
            minOf(recMid.first, recMid.second, recMid.third)
        val hdrSpread = maxOf(hdrMid.first, hdrMid.second, hdrMid.third) -
            minOf(hdrMid.first, hdrMid.second, hdrMid.third)

        assertTrue(recSpread < 0.09f)
        assertTrue(hdrSpread < 0.10f)
    }

    @Test
    fun standardsColorSpaceProfilesExposePrimariesTransferAndLumaCoefficients() {
        val rec709 = ColorScience.resolveColorSpaceProfile("Rec.709")
            ?: error("Rec.709 color space profile must be available")
        val rec2020 = ColorScience.resolveColorSpaceProfile("Rec.2020")
            ?: error("Rec.2020 color space profile must be available")
        val neoWideGamut = ColorScience.resolveColorSpaceProfile("Neo Wide Gamut")
            ?: error("Neo Wide Gamut must resolve to the proprietary D55 wide-gamut space")

        assertEquals("Rec.709", rec709.name)
        assertEquals("BT.1886/sRGB", rec709.transfer)
        assertEquals(0.2126f, rec709.lumaCoefficients[0], 0.0001f)
        assertEquals(0.7152f, rec709.lumaCoefficients[1], 0.0001f)
        assertEquals(0.0722f, rec709.lumaCoefficients[2], 0.0001f)

        assertEquals("Rec.2020", rec2020.name)
        assertEquals("BT.2020 non-constant luminance", rec2020.matrix)
        assertEquals(0.2627f, rec2020.lumaCoefficients[0], 0.0001f)
        assertEquals(0.6780f, rec2020.lumaCoefficients[1], 0.0001f)
        assertEquals(0.0593f, rec2020.lumaCoefficients[2], 0.0001f)

        assertEquals("Neo Wide Gamut", neoWideGamut.name)
        assertEquals("D55", neoWideGamut.whitePoint)
        assertEquals(0.7347f, neoWideGamut.primaries.getValue("red").first, 0.0001f)
        assertEquals(0.2653f, neoWideGamut.primaries.getValue("red").second, 0.0001f)
        assertEquals(0.1152f, neoWideGamut.primaries.getValue("green").first, 0.0001f)
        assertEquals(0.8260f, neoWideGamut.primaries.getValue("green").second, 0.0001f)
        assertEquals(0.0860f, neoWideGamut.primaries.getValue("blue").first, 0.0001f)
        assertEquals(0.0110f, neoWideGamut.primaries.getValue("blue").second, 0.0001f)
        assertEquals(0.279812198f, neoWideGamut.lumaCoefficients[0], 0.000001f)
        assertEquals(0.709575275f, neoWideGamut.lumaCoefficients[1], 0.000001f)
        assertEquals(0.010612527f, neoWideGamut.lumaCoefficients[2], 0.000001f)
        assertEquals(
            listOf("Rec.709", "Rec.2020", "Neo ACES AP1", "Neo ACES AP0", "Neo Wide Gamut"),
            ColorScience.COLOR_SPACE_PROFILES.map { it.name }
        )
        listOf("Display P3", "ARRI", "Sony", "Canon", "Panasonic", "RED", "Blackmagic", "DaVinci").forEach { token ->
            assertFalse(ColorScience.COLOR_SPACE_PROFILES.any { it.name.contains(token, ignoreCase = true) })
        }
    }

    @Test
    fun builtInProfilesDoNotExposeVendorBrandedColorScience() {
        val profiles = ColorScience.BUILT_IN_PROFILES
        val joined = profiles.joinToString(separator = " ")

        assertTrue(profiles.contains("Rec.709"))
        assertTrue(profiles.contains("Rec.2020"))
        assertTrue(profiles.contains("Neo Wide Gamut"))
        assertTrue(profiles.contains("Neo ACES High End"))
        assertTrue(profiles.contains("N-Log Cinema"))
        listOf("Display P3", "ARRI", "Sony", "Canon", "Panasonic", "RED", "Blackmagic", "DaVinci", "Dolby").forEach { token ->
            assertFalse(joined.contains(token, ignoreCase = true))
        }
    }

    @Test
    fun neoWideGamutPreviewUsesOriginalWideGamutMathWithoutVendorProfileClaims() {
        val neoWideGamut = ColorScience().apply {
            loadBuiltInProfile("Neo Wide Gamut")
        }

        val neoWideLut = neoWideGamut.getPreviewLutFloatArray()
            ?: error("Neo Wide Gamut preview LUT should exist")

        val neoWideSample = sampleRgb(neoWideLut.first, neoWideLut.second, red = 0.78f, green = 0.40f, blue = 0.16f)
        val neoWideSpread = maxOf(neoWideSample.first, neoWideSample.second, neoWideSample.third) -
            minOf(neoWideSample.first, neoWideSample.second, neoWideSample.third)

        assertTrue(
            "Neo Wide Gamut should preserve a non-neutral wide-gamut color transform",
            neoWideSpread > 0.015f
        )
        listOf(neoWideSample).forEach { sample ->
            assertTrue(sample.first in 0f..1f)
            assertTrue(sample.second in 0f..1f)
            assertTrue(sample.third in 0f..1f)
        }
    }

    @Test
    fun neoGamutApexIsRegisteredAndUsesTheProjectWideGamutTransform() {
        assertTrue(ColorScience.BUILT_IN_PROFILES.contains("NeoGamut APEX"))
        assertEquals(
            "Neo Wide Gamut",
            ColorScience.resolveColorSpaceProfile("NeoGamut APEX")?.name
        )

        val apex = ColorScience().apply { loadBuiltInProfile("NeoGamut APEX") }
        val wide = ColorScience().apply { loadBuiltInProfile("Neo Wide Gamut") }
        val apexLut = apex.getPreviewLutFloatArray()
            ?: error("NeoGamut APEX must produce a project-owned preview transform")
        val wideLut = wide.getPreviewLutFloatArray()
            ?: error("Neo Wide Gamut must produce a project-owned preview transform")

        assertEquals(wideLut.second, apexLut.second)
        assertTrue(
            "NeoGamut APEX diverged from its authoritative Neo Wide Gamut base",
            wideLut.first.contentEquals(apexLut.first)
        )
    }

    @Test
    fun externalVendorColorSpaceAliasesSafelyResolveToNeoWideGamut() {
        val externalWideGamut = "AR" + "RI Wide Gamut 4"
        val externalP3 = "Display " + "P3"

        assertEquals("Neo Wide Gamut", ColorScience.resolveColorSpaceProfile(externalWideGamut)?.name)
        assertEquals("Neo Wide Gamut", ColorScience.resolveColorSpaceProfile(externalP3)?.name)
    }

    @Test
    fun hueLockedManualGradePreservesOklabHueUnderExtremeAdjustments() {
        val before = ColorScience.oklabHueDegrees(0.82f, 0.31f, 0.11f)
        val graded = ColorScience.applyHueLockedOklabGrade(
            r = 0.82f,
            g = 0.31f,
            b = 0.11f,
            saturationScale = 1.42f,
            contrastScale = 1.35f,
            brightnessOffset = 0.02f,
            hueShiftDegrees = 0f
        )
        val after = ColorScience.oklabHueDegrees(graded.first, graded.second, graded.third)
        val delta = kotlin.math.abs(before - after).let { minOf(it, 360f - it) }

        assertTrue("Oklab hue drifted by $delta degrees", delta < 0.75f)
        assertTrue(graded.first in 0f..1f)
        assertTrue(graded.second in 0f..1f)
        assertTrue(graded.third in 0f..1f)
    }

    @Test
    fun activeColorScienceSourcesDoNotCompileVendorProfiles() {
        val forbidden = listOf(
            Regex("\\bAR" + "RI\\b", RegexOption.IGNORE_CASE),
            Regex("\\bSo" + "ny\\b", RegexOption.IGNORE_CASE),
            Regex("\\bCa" + "non\\b", RegexOption.IGNORE_CASE),
            Regex("\\bPa" + "nasonic\\b", RegexOption.IGNORE_CASE),
            Regex("\\bR" + "ED\\b"),
            Regex("\\bBlack" + "magic\\b", RegexOption.IGNORE_CASE),
            Regex("\\bDa" + "Vinci\\b", RegexOption.IGNORE_CASE),
            Regex("\\bDol" + "by\\b", RegexOption.IGNORE_CASE),
            Regex("\\bFilm" + "Light\\b", RegexOption.IGNORE_CASE)
        )
        val activeSources = listOf(
            "app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt",
            "app/src/main/java/com/neoproc/neoproccamx/color/AcesColorEngine.kt",
            "app/src/main/java/com/neoproc/neoproccamx/color/VideoLogProfilePolicy.kt",
            "app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanToneDeliveryMode.kt",
            "app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag",
            "app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp"
        )

        activeSources.forEach { path ->
            val text = repoFile(path).readText()
            forbidden.forEach { token ->
                assertFalse("$token leaked in $path", token.containsMatchIn(text))
            }
        }
    }

    @Test
    fun previewAndExportShadersExposeHueLockedNwgToneMath() {
        val preview = repoFile("app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()
        val export = repoFile("app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()

        assertTrue(preview.contains("hueLockedOklabGrade"))
        assertTrue(preview.contains("c1ContinuousToneMap"))
        assertTrue(preview.contains("NEO_WIDE_GAMUT_TO_XYZ"))
        assertTrue(export.contains("neoWideGamutToAcesCg"))
    }

    private inline fun withStandardQualityPolicy(block: () -> Unit) {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = false
            block()
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
