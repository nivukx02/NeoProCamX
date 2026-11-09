package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

class NearBlackChromaCalibrationContractTest {
    private val preview =
        File("src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt").readText()
    private val hdrPreview =
        File("src/main/java/com/neoproc/neoproccamx/rendering/Hdr10PqPreviewCompositor.kt").readText()
    private val recorder = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
    private val vulkanPreview =
        File("src/main/cpp/shaders/vulkan/ycbcr_preview.frag").readText()

    @Test
    fun `calibration reaches every processed video monitor and recording path`() {
        assertTrue(preview.contains("vec3 calibrateNearBlackChroma"))
        assertTrue(preview.contains("if (uGrade == 1 || uGrade == 2)"))
        assertTrue(preview.contains("return calibrateNearBlackChroma(clamp(outc"))
        assertTrue(preview.contains("float neutralDither ="))
        assertTrue(preview.contains("vec3(neutralDither) * blackDitherGate"))
        assertTrue(preview.contains("vec3 neighbourConsensusChroma"))
        assertTrue(preview.contains("float surfaceOutlierBlend"))

        assertTrue(hdrPreview.contains("uniform vec2 uTexel;"))
        assertTrue(hdrPreview.contains("vec3 calibrateNearBlackChroma(vec2 uv, vec3 center)"))
        assertTrue(hdrPreview.contains("lin = calibrateNearBlackChroma(vTexCoord, lin)"))
        assertTrue(hdrPreview.contains("mix(0.96, 1.0, outlierConfidence)"))
        assertTrue(hdrPreview.contains("vec3 neighbourConsensusChroma"))
        assertTrue(hdrPreview.contains("float surfaceOutlierBlend"))
        assertTrue(!hdrPreview.contains("if (yc >= 0.10) return center;"))

        // The recorder keeps an identity value outside the shadow gate, then applies the exact
        // calibration mix only when the gate admits the pixel. Verify both pieces so the test
        // protects the optimized control flow without requiring the former eager declaration.
        assertTrue(recorder.contains("vec3 calibratedChroma = chroma;"))
        assertTrue(
            recorder.contains(
                "calibratedChroma = mix(chroma, consensusChroma, shadowCalibrationBlend);"
            )
        )
        assertTrue(recorder.contains("vec3 spatiallyDenoisedChroma = mix("))
        assertTrue(recorder.contains("vec3 finalColor = vec3(y) + spatiallyDenoisedChroma"))
        assertTrue(recorder.contains("mix(0.96, 1.0, outlierConfidence)"))
        assertTrue(recorder.contains("vec3 cardinalChroma[4]"))
        assertTrue(recorder.contains("float surfaceOutlierBlend"))
        assertTrue(recorder.contains("float calibrationSignalScale ="))
        assertTrue(recorder.contains("(uInputTransfer == 3 || uInputTransfer == 4)"))
        assertTrue(recorder.contains("1.0 / clamp(uNdrToneProfile.x, 0.02, 0.30)"))
        assertTrue(recorder.contains("float calibrationY = y * calibrationSignalScale"))
        assertTrue(recorder.contains("length(calibratedChroma) * calibrationSignalScale"))
        assertTrue(recorder.contains("float bilateralLumaDeltaMetric = dl * calibrationSignalScale"))
        assertTrue(recorder.contains("float detailLumaMetric = lcd * calibrationSignalScale"))
        assertTrue(recorder.contains("smoothstep(0.02, 0.10, detailLumaMetric)"))
        assertTrue(recorder.contains("float sourceMotionMetric ="))
        assertTrue(recorder.contains("float temporalMotionMetric ="))

        assertTrue(vulkanPreview.contains("vec3 calibrateNearBlackChroma(vec3 color)"))
        assertTrue(vulkanPreview.contains("color = calibrateNearBlackChroma(color)"))
        assertTrue(vulkanPreview.contains("belowColorConfidence * 0.96"))
    }

    @Test
    fun `isolated dark colour speckle is strongly suppressed without changing luma`() {
        val center = Rgb(0.030, 0.010, 0.010)
        val result = calibrate(
            center,
            listOf(
                Rgb(0.014, 0.014, 0.014),
                Rgb(0.015, 0.015, 0.015),
                Rgb(0.013, 0.013, 0.013),
                Rgb(0.014, 0.014, 0.014)
            )
        )

        assertEquals(center.luma(), result.luma(), 1e-12)
        assertTrue(result.chromaMagnitude() < center.chromaMagnitude() * 0.25)
    }

    @Test
    fun `coherent sensor black cast is strongly neutralized`() {
        val center = Rgb(0.030, 0.010, 0.010)
        val result = calibrate(center, List(4) { center })

        assertEquals(center.luma(), result.luma(), 1e-12)
        assertTrue(result.chromaMagnitude() < center.chromaMagnitude() * 0.15)
    }

    @Test
    fun `recoverable coherent dark colour retains at least eighty five percent chroma`() {
        val center = Rgb(0.100, 0.045, 0.035)
        val result = calibrate(center, List(4) { center })

        assertEquals(center.luma(), result.luma(), 1e-12)
        assertTrue(result.chromaMagnitude() >= center.chromaMagnitude() * 0.85)
    }

    @Test
    fun `isolated bright neutral surface chroma speckle is suppressed without changing luma`() {
        val center = Rgb(0.76, 0.68, 0.69)
        val result = calibrate(center, List(4) { Rgb(0.70, 0.70, 0.70) })

        assertEquals(center.luma(), result.luma(), 1e-12)
        assertTrue(
            "bright isolated chroma must be removed: before=${center.chromaMagnitude()} " +
                "after=${result.chromaMagnitude()}",
            result.chromaMagnitude() < center.chromaMagnitude() * 0.16
        )
    }

    @Test
    fun `coherent bright colour and same luma chromatic edge are protected`() {
        val coherent = Rgb(0.70, 0.55, 0.42)
        assertRgbEquals(coherent, calibrate(coherent, List(4) { coherent }))

        val center = Rgb(0.63, 0.52, 0.44)
        val edgeA = Rgb(0.44, 0.58, 0.56)
        val edgeB = Rgb(0.66, 0.50, 0.42)
        val edgeResult = calibrate(center, listOf(edgeA, edgeB, edgeA, edgeB))
        assertEquals(center.luma(), edgeResult.luma(), 1e-12)
        assertTrue(edgeResult.chromaMagnitude() >= center.chromaMagnitude() * 0.98)
    }

    @Test
    fun `luma edge and normal brightness colour are unchanged`() {
        val dark = Rgb(0.030, 0.010, 0.010)
        val edge = calibrate(dark, List(4) { Rgb(0.20, 0.20, 0.20) })
        assertRgbEquals(dark, edge)

        val bright = Rgb(0.45, 0.20, 0.10)
        val brightResult = calibrate(
            bright,
            listOf(
                Rgb(0.44, 0.21, 0.11),
                Rgb(0.45, 0.20, 0.10),
                Rgb(0.46, 0.19, 0.09),
                Rgb(0.45, 0.20, 0.10)
            )
        )
        assertRgbEquals(bright, brightResult)
    }

    @Test
    fun `valid scene linear NDR colour survives diffuse-domain threshold normalization`() {
        // Luma ~= 0.013 and chroma ~= 0.013 reproduce the device-observed FP16 RAW range. Judging
        // these values directly against display-domain thresholds nearly neutralizes the colour;
        // normalizing comparison metrics by project diffuse scene (0.18) preserves the real chroma.
        val sceneLinearColour = Rgb(0.025000, 0.009736, 0.010000)
        val unnormalized = calibrate(sceneLinearColour, List(4) { sceneLinearColour })
        val normalized = calibrate(
            sceneLinearColour,
            List(4) { sceneLinearColour },
            signalScale = 1.0 / 0.18
        )

        assertEquals(sceneLinearColour.luma(), normalized.luma(), 1e-12)
        assertTrue(unnormalized.chromaMagnitude() < sceneLinearColour.chromaMagnitude() * 0.10)
        assertTrue(normalized.chromaMagnitude() >= sceneLinearColour.chromaMagnitude() * 0.99)
    }

    @Test
    fun `true sensor black speckle remains suppressed in scene linear domain`() {
        val diffuseScene = 0.18
        val center = Rgb(0.030, 0.010, 0.010) * diffuseScene
        val neighbours = listOf(
            Rgb(0.014, 0.014, 0.014),
            Rgb(0.015, 0.015, 0.015),
            Rgb(0.013, 0.013, 0.013),
            Rgb(0.014, 0.014, 0.014)
        ).map { it * diffuseScene }
        val result = calibrate(center, neighbours, signalScale = 1.0 / diffuseScene)

        assertEquals(center.luma(), result.luma(), 1e-12)
        assertTrue(result.chromaMagnitude() < center.chromaMagnitude() * 0.25)
    }

    @Test
    fun `threshold decisions are invariant between canonical and scene linear domains`() {
        val diffuseScene = 0.18
        val canonicalCenter = Rgb(0.030, 0.010, 0.010)
        val canonicalNeighbours = listOf(
            Rgb(0.014, 0.014, 0.014),
            Rgb(0.015, 0.015, 0.015),
            Rgb(0.013, 0.013, 0.013),
            Rgb(0.014, 0.014, 0.014)
        )
        val canonical = calibrate(canonicalCenter, canonicalNeighbours)
        val sceneLinear = calibrate(
            canonicalCenter * diffuseScene,
            canonicalNeighbours.map { it * diffuseScene },
            signalScale = 1.0 / diffuseScene
        )

        assertRgbEquals(canonical * diffuseScene, sceneLinear)
    }

    @Test
    fun `temporal detail and chroma guards are invariant in scene linear domain`() {
        val diffuseScene = 0.18
        val signalScale = 1.0 / diffuseScene
        val canonicalMotion = 0.035
        val canonicalDetail = 0.055
        val sceneMotion = canonicalMotion * diffuseScene
        val sceneDetail = canonicalDetail * diffuseScene

        val canonicalMotionGuard = 1.0 - smoothstep(0.012, 0.060, canonicalMotion)
        val sceneMotionGuard =
            1.0 - smoothstep(0.012, 0.060, sceneMotion * signalScale)
        val canonicalChromaFlat = 1.0 - smoothstep(0.020, 0.100, canonicalDetail)
        val sceneChromaFlat =
            1.0 - smoothstep(0.020, 0.100, sceneDetail * signalScale)
        val canonicalHaloGuard = 1.0 - smoothstep(0.060, 0.200, canonicalDetail)
        val sceneHaloGuard =
            1.0 - smoothstep(0.060, 0.200, sceneDetail * signalScale)

        assertEquals(canonicalMotionGuard, sceneMotionGuard, 1e-12)
        assertEquals(canonicalChromaFlat, sceneChromaFlat, 1e-12)
        assertEquals(canonicalHaloGuard, sceneHaloGuard, 1e-12)
    }

    private fun calibrate(
        center: Rgb,
        neighbours: List<Rgb>,
        signalScale: Double = 1.0
    ): Rgb {
        require(neighbours.size == 4)
        require(signalScale.isFinite() && signalScale > 0.0)
        val yc = center.luma()
        val neighbourLuma = neighbours.map(Rgb::luma)
        val centerChroma = center - Rgb.neutral(yc)
        val neighbourChroma = neighbours.zip(neighbourLuma).map { (rgb, y) -> rgb - Rgb.neutral(y) }
        val neighbourConsensus = neighbourChroma.reduce(Rgb::plus) / 4.0
        val consensus = (centerChroma * 2.0 + neighbourChroma.reduce(Rgb::plus)) / 6.0
        val lumaStructure = neighbourLuma.maxOf { kotlin.math.abs(it - yc) }
        val chromaStructure = maxOf(
            (neighbourChroma[0] - neighbourChroma[1]).magnitude(),
            (neighbourChroma[2] - neighbourChroma[3]).magnitude()
        )
        val metricY = yc * signalScale
        val deepShadow = 1.0 - smoothstep(0.018, 0.090, metricY)
        val edgeProtect = maxOf(
            smoothstep(0.006, 0.045, lumaStructure * signalScale),
            smoothstep(0.018, 0.100, chromaStructure * signalScale)
        )
        val coherentColorProtect = smoothstep(
            0.018,
            0.085,
            consensus.magnitude() * signalScale
        )
        val disagreement = (centerChroma - consensus).magnitude() * signalScale
        val outlierConfidence = smoothstep(0.0008, 0.018, disagreement)
        val shadowConsensusBlend = (
            deepShadow * (1.0 - edgeProtect) * lerp(0.42, 0.96, outlierConfidence) *
                (1.0 - coherentColorProtect * 0.82)
            ).coerceIn(0.0, 0.96)
        var calibrated = centerChroma.lerp(consensus, shadowConsensusBlend)
        val surfaceNeutral = 1.0 - smoothstep(
            0.020,
            0.090,
            neighbourConsensus.magnitude() * signalScale
        )
        val visibleSurface = smoothstep(0.075, 0.240, metricY)
        val surfaceOutlierConfidence = smoothstep(
            0.0020,
            0.0280,
            (centerChroma - neighbourConsensus).magnitude() * signalScale
        )
        val surfaceOutlierBlend = (
            visibleSurface * (1.0 - edgeProtect) * surfaceNeutral *
                surfaceOutlierConfidence * 0.92
            ).coerceIn(0.0, 0.92)
        calibrated = calibrated.lerp(neighbourConsensus, surfaceOutlierBlend)
        val blackUncertainty = 1.0 - smoothstep(0.012, 0.065, metricY)
        val tinyChroma =
            1.0 - smoothstep(0.012, 0.070, calibrated.magnitude() * signalScale)
        val neutralize = (
            blackUncertainty * tinyChroma * (1.0 - edgeProtect) *
                (1.0 - coherentColorProtect) * lerp(0.96, 1.0, outlierConfidence)
            ).coerceIn(0.0, 0.995)
        calibrated *= 1.0 - neutralize
        return Rgb.neutral(yc) + calibrated
    }

    private fun assertRgbEquals(expected: Rgb, actual: Rgb) {
        assertEquals(expected.r, actual.r, 1e-12)
        assertEquals(expected.g, actual.g, 1e-12)
        assertEquals(expected.b, actual.b, 1e-12)
    }

    private fun smoothstep(edge0: Double, edge1: Double, value: Double): Double {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

    private data class Rgb(val r: Double, val g: Double, val b: Double) {
        fun luma(): Double = 0.2126 * r + 0.7152 * g + 0.0722 * b
        fun magnitude(): Double = sqrt(r * r + g * g + b * b)
        fun chromaMagnitude(): Double = (this - neutral(luma())).magnitude()
        fun lerp(other: Rgb, t: Double): Rgb = this * (1.0 - t) + other * t
        operator fun plus(other: Rgb): Rgb = Rgb(r + other.r, g + other.g, b + other.b)
        operator fun minus(other: Rgb): Rgb = Rgb(r - other.r, g - other.g, b - other.b)
        operator fun times(scale: Double): Rgb = Rgb(r * scale, g * scale, b * scale)
        operator fun div(scale: Double): Rgb = Rgb(r / scale, g / scale, b / scale)

        companion object {
            fun neutral(value: Double): Rgb = Rgb(value, value, value)
        }
    }
}
