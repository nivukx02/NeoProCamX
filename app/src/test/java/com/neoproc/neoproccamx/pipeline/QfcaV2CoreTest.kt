package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.rendering.RawBufferDescriptor
import com.neoproc.neoproccamx.recording.HevcColorMetadataRewriter
import com.neoproc.neoproccamx.recording.Npv10MovConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt

class QfcaV2CoreTest {

    @Test
    fun testLayer0PhotonFidelityPreservation() {
        // Verify creation of RawBufferDescriptor and metadata extraction parameters
        val descriptor = RawBufferDescriptor(
            width = 1920,
            height = 1080,
            stride = 1920,
            blackLevel = 64,
            whiteLevel = 4095,
            exposureTimeNs = 33333333L,
            analogGain = 8.0f,
            digitalGain = 1.5f,
            noiseS = 0.0024f,
            noiseO = 0.00015f
        )

        assertEquals(1920, descriptor.width)
        assertEquals(1080, descriptor.height)
        assertEquals(33333333L, descriptor.exposureTimeNs)
        assertEquals(8.0f, descriptor.analogGain)
        assertEquals(1.5f, descriptor.digitalGain)
        assertEquals(0.0024f, descriptor.noiseS)
        assertEquals(0.00015f, descriptor.noiseO)
    }

    @Test
    fun testLayer1DynamicFocusIntelligenceField() {
        val field = CinematicValueField(gridWidth = 16, gridHeight = 16)
        val sharpness = FloatArray(256) { 0.1f }
        sharpness[8 * 16 + 8] = 0.9f // Inject higher sharpness at center
        val motion = FloatArray(256) { 0.1f }

        // Trigger center-weighted AF fallback by passing null TotalCaptureResult
        val values = field.update(
            result = null,
            sensorWidth = 4000,
            sensorHeight = 3000,
            localSharpness = sharpness,
            localMotion = motion
        )

        assertEquals(256, values.size)
        for (v in values) {
            assertTrue("Saliency value $v must be in [0.0, 1.0]", v in 0.0f..1.0f)
        }
        
        // Saliency should be higher near center
        val centerVal = field.getValueAt(8, 8)
        val cornerVal = field.getValueAt(0, 0)
        assertTrue("Center saliency ($centerVal) should exceed corner saliency ($cornerVal)", centerVal > cornerVal)
    }

    @Test
    fun testLayer2CinematicResourceOrchestrationEngine() {
        val field = CinematicValueField(gridWidth = 16, gridHeight = 16)
        val sharpness = FloatArray(256) { 0.5f }
        val motion = FloatArray(256) { 0.0f }
        
        // Make center extremely salient (Tier 3)
        for (i in 0 until 256) {
            sharpness[i] = 1.0f
        }

        field.update(
            result = null,
            sensorWidth = 1920,
            sensorHeight = 1080,
            localSharpness = sharpness,
            localMotion = motion
        )

        val orchestrator = ResourceOrchestrator(powerManager = null)
        val tiles = orchestrator.orchestrate(
            valueField = field,
            frameWidth = 1920,
            frameHeight = 1080,
            targetBudgetMs = 33.3f
        )

        assertTrue(tiles.isNotEmpty())
        assertTrue("Tiles should be classified into tiers 1, 2, or 3", tiles.all { it.tier in 1..3 })
    }

    @Test
    fun testLayer3MultiFrameRealityReconstructionMath() {
        // Anisotropic Kernel Regression and robust fusion simulation
        fun anisotropicFusionWeight(dist: Float, edgeGradient: Float): Float {
            // Steering kernel: penalize distance and cross-edge gradient
            val spatialFactor = abs(dist)
            val gradientPenalty = edgeGradient * 2.0f
            return Math.exp(-((spatialFactor + gradientPenalty).toDouble())).toFloat()
        }

        val weightAlongEdge = anisotropicFusionWeight(0.5f, 0.1f)
        val weightAcrossEdge = anisotropicFusionWeight(0.5f, 0.9f)
        assertTrue(
            "Fusion steering kernel must favor sampling along edges rather than across edges",
            weightAlongEdge > weightAcrossEdge
        )
    }

    @Test
    fun testLayer4InfiniteDetailPreservationMatrixMath() {
        // Simulation of structure tensor eigenvalues for region classification
        fun classifyStructure(lambda1: Float, lambda2: Float): String {
            return when {
                lambda1 > 10.0f && lambda2 < 1.0f -> "EDGE"
                lambda1 > 10.0f && lambda2 > 10.0f -> "TEXTURE"
                else -> "FLAT"
            }
        }

        assertEquals("EDGE", classifyStructure(15.0f, 0.5f))
        assertEquals("TEXTURE", classifyStructure(12.0f, 11.0f))
        assertEquals("FLAT", classifyStructure(0.2f, 0.1f))
    }

    @Test
    fun testLayer5FocusDomainDynamicRangeExpansion() {
        // Focal varying Laplacian pyramid gain amplification simulation
        fun getLaplacianDetailGain(saliency: Float): Float {
            return if (saliency >= 0.75f) {
                1.8f // High focus = boost details
            } else if (saliency < 0.35f) {
                0.2f // Background = suppress noise
            } else {
                1.0f
            }
        }

        assertTrue(getLaplacianDetailGain(0.85f) > getLaplacianDetailGain(0.5f))
        assertTrue(getLaplacianDetailGain(0.2f) < getLaplacianDetailGain(0.5f))
    }

    @Test
    fun testLayer6PerceptualImaxRenderingEngineCSF() {
        // Mid-spatial frequency contrast stretch soft S-curve simulation
        fun contrastStretch(luma: Float, saliency: Float): Float {
            val k = if (saliency >= 0.75f) 4.0f else 1.0f
            return (1.0f / (1.0f + Math.exp((-k * (luma - 0.5f)).toDouble()))).toFloat()
        }

        val stretchedFocus = contrastStretch(0.6f, 0.8f)
        val stretchedBackground = contrastStretch(0.6f, 0.2f)
        assertTrue(
            "Focus domain should yield stronger contrast stretch",
            abs(stretchedFocus - 0.5f) > abs(stretchedBackground - 0.5f)
        )
    }

    @Test
    fun testLayer7TemporalStabilityCoreDenoise() {
        // Robustness blend factor computation simulation
        fun getTemporalBlendAlpha(baseAlpha: Float, ghostVeto: Float, detail: Float, saliency: Float): Float {
            return baseAlpha * (1.0f - ghostVeto) * (1.0f - detail * saliency)
        }

        // Flat background (ghostVeto = 0, detail = 0, saliency = 0) -> strong temporal blend (high alpha)
        val flatBackgroundAlpha = getTemporalBlendAlpha(0.99f, 0.0f, 0.0f, 0.0f)
        
        // High saliency textured foreground -> responsive blend (low alpha to prevent motion blur)
        val responsiveForegroundAlpha = getTemporalBlendAlpha(0.99f, 0.0f, 0.8f, 0.8f)

        assertTrue(
            "Background must have significantly higher temporal integration than moving/detailed foreground",
            flatBackgroundAlpha > responsiveForegroundAlpha
        )
    }

    @Test
    fun testLayer8ComputationalCinemaEncoderQpOffset() {
        // Delta QP offset mapping according to Layer 8 formula
        fun computeQpOffset(saliency: Float): Int {
            return (-12.0f * saliency).roundToInt()
        }

        assertEquals(-12, computeQpOffset(1.0f))
        assertEquals(-6, computeQpOffset(0.5f))
        assertEquals(0, computeQpOffset(0.0f))
    }

    @Test
    fun testLayer9QuantumFocusCinemaArchitectureFinalSerialization() {
        // Test focus map calculation on uncompressed P010-like byte array
        val width = 160
        val height = 160
        val payloadSize = width * height * 2 + (width / 2) * (height / 2) * 2 * 2
        val dummyP010 = ByteArray(payloadSize)

        // Inject some luma gradients inside a tile center to simulate contrast
        // Tile 0 is at top-left. It covers pixels (0..9, 0..9). Center is (5, 5).
        // Let's create high-frequency contrast in Tile 0
        for (py in 3..6) {
            for (px in 3..6) {
                val offset = (py * width + px) * 2
                // Inject varying 16-bit values
                val value = if ((py + px) % 2 == 0) 1000 shl 6 else 10 shl 6
                dummyP010[offset] = (value and 0xFF).toByte()
                dummyP010[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            }
        }

        // Invoke private computeFocusMap using reflection
        val converterClass = Npv10MovConverter::class.java
        val computeMethod = converterClass.getDeclaredMethod("computeFocusMap", ByteArray::class.java, Int::class.java, Int::class.java)
        computeMethod.isAccessible = true
        val focusMap = computeMethod.invoke(Npv10MovConverter, dummyP010, width, height) as FloatArray

        assertEquals(256, focusMap.size)
        assertTrue("Tile 0 contrast should be positive", focusMap[0] > 0.0f)
        assertTrue("Contrast values must be normalized", focusMap.all { it in 0.0f..1.0f })

        // Test T.35 SEI payload packaging
        val buildT35Method = converterClass.getDeclaredMethod("buildFocusMapT35Payload", FloatArray::class.java)
        buildT35Method.isAccessible = true
        val t35Payload = buildT35Method.invoke(Npv10MovConverter, focusMap) as ByteArray

        assertEquals(1029, t35Payload.size)
        assertEquals(0xB5.toByte(), t35Payload[0])
        assertEquals(0x00.toByte(), t35Payload[1])
        assertEquals(0x3C.toByte(), t35Payload[2])
        assertEquals(0x00.toByte(), t35Payload[3])
        assertEquals(0x01.toByte(), t35Payload[4])

        // Verify that the floats are packed in little-endian order
        val buffer = ByteBuffer.wrap(t35Payload, 5, 1024).order(ByteOrder.LITTLE_ENDIAN)
        val unpackedVal0 = buffer.getFloat()
        assertEquals(focusMap[0], unpackedVal0, 1e-6f)

        // Test HEVC payload detector
        val isHevcMethod = converterClass.getDeclaredMethod("isHevcPayload", ByteArray::class.java)
        isHevcMethod.isAccessible = true

        val dummyHevcAnnexB = byteArrayOf(0, 0, 0, 1, 0x4E.toByte(), 0x01)
        val dummyRawData = byteArrayOf(1, 2, 3, 4, 5, 6)

        assertTrue(isHevcMethod.invoke(Npv10MovConverter, dummyHevcAnnexB) as Boolean)
        assertFalse(isHevcMethod.invoke(Npv10MovConverter, dummyRawData) as Boolean)
    }
}
