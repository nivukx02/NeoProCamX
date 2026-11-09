package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.rendering.BayerPattern
import com.neoproc.neoproccamx.rendering.RawBufferDescriptor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSensorExposureAnalyzerTest {
    @Test
    fun recordingSnapshotOwnsOnlyCopiedPrimitiveSamples() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawSensorExposureAnalyzer.kt"
        ).readText()
        val snapshot = source
            .substringAfter("internal data class RawSensorExposureSampleSnapshot(")
            .substringBefore(")\n\ninternal object RawSensorExposureAnalyzer")

        assertTrue(snapshot.contains("IntArray"))
        assertFalse(snapshot.contains("Image"))
        assertFalse(snapshot.contains("Plane"))
        assertFalse(snapshot.contains("HardwareBuffer"))
        assertFalse(snapshot.contains("ByteBuffer"))
    }

    @Test
    fun bggrMeterUsesSemanticBlackLevelsAndFindsRedChannelClip() {
        val width = 4
        val height = 4
        val rowStride = width * 2
        val black = floatArrayOf(40f, 30f, 20f, 10f)
        val white = 1023
        val buffer = ByteBuffer.allocate(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val position = (y and 1) * 2 + (x and 1)
                val channel = when (position) {
                    0 -> 3 // B
                    1 -> 2 // Gb
                    2 -> 1 // Gr
                    else -> 0 // R
                }
                val base = black[channel]
                val raw = if (x == 1 && y == 1) {
                    white
                } else {
                    (base + (white - base) * 0.20f).toInt()
                }
                buffer.putShort(y * rowStride + x * 2, raw.toShort())
            }
        }
        val descriptor = RawBufferDescriptor(
            width = width,
            height = height,
            stride = rowStride,
            blackLevel = 25,
            blackLevelRggb = black,
            whiteLevel = white,
            bayerPattern = BayerPattern.BGGR
        )

        val snapshot = RawSensorExposureAnalyzer.snapshot(buffer, rowStride, 2, descriptor)
        assertNotNull(snapshot)
        snapshot!!
        val metrics = RawSensorExposureAnalyzer.analyze(snapshot)

        assertNotNull(metrics)
        metrics!!
        assertEquals(16, metrics.sampleCount)
        // One isolated red sample is retained in the red-channel evidence but is not a broad clipped
        // highlight. This prevents a tiny red LED/specular from darkening the complete scene.
        assertEquals(0f, metrics.highlightClipFraction, 1e-6f)
        assertEquals(1f / 4f, metrics.redHighlightClipFraction, 1e-6f)
        assertTrue(metrics.medianLuma in 0.19f..0.21f)
        assertTrue(metrics.p99Luma > metrics.medianLuma)
        assertTrue(metrics.p99Red > metrics.p99Green)
        assertTrue(metrics.p99Red > metrics.p99Blue)

        // The copied snapshot remains stable after the camera-plane storage changes or is released.
        buffer.clear()
        while (buffer.remaining() >= 2) buffer.putShort(0)
        assertEquals(metrics, RawSensorExposureAnalyzer.analyze(snapshot))
    }

    @Test
    fun fullResolutionSamplingIsHardCappedAndCalibratesAfterPixelCopy() {
        val width = 4096
        val height = 3072
        val rowStride = width * 2
        val buffer = ByteBuffer.allocate(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        val codes = RawSensorExposureAnalyzer.snapshotCodes(
            plane = buffer,
            rowStrideBytes = rowStride,
            pixelStrideBytes = 2,
            width = width,
            height = height,
            bayerPattern = BayerPattern.RGGB
        )

        assertNotNull(codes)
        codes!!
        assertEquals(1_024, codes.quadCount)
        assertEquals(4_096, codes.samplesRggb.size)

        val descriptor = RawBufferDescriptor(
            width = width,
            height = height,
            stride = rowStride,
            blackLevel = 64,
            blackLevelRggb = floatArrayOf(64f, 64f, 64f, 64f),
            whiteLevel = 4095,
            bayerPattern = BayerPattern.RGGB
        )
        val calibrated = RawSensorExposureAnalyzer.calibrate(codes, descriptor)
        assertNotNull(calibrated)
        assertEquals(1_024, calibrated!!.quadCount)
        assertTrue(calibrated.samplesRggb === codes.samplesRggb)
    }
}
