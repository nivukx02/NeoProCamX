package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.rendering.GpuSignalFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoQualityInstructionStreamTest {
    @Test
    fun `instruction owns the exact controls resolved from its measured evidence`() {
        val width = 16
        val height = 12
        val payload = p010(width, height) { x, y -> 180 + ((x * 17 + y * 11) % 620) }

        val instruction = NeoQualityInstructionStream.analyzeP010(
            frameIndex = 7L,
            payload = payload,
            previousP010Payload = null,
            width = width,
            height = height,
            signalFamily = GpuSignalFamily.SDR,
            detailProfile = VideoDetailProfile.FOCUSED_DETAIL
        )
        val direct = ConversionFrameQualityPolicy.resolve(
            family = GpuSignalFamily.SDR,
            detailProfile = VideoDetailProfile.FOCUSED_DETAIL,
            metrics = instruction.noiseMetrics,
            sceneDetail = instruction.sceneDetail
        )

        assertEquals(NeoQualityInstructionStream.VERSION, instruction.version)
        assertEquals(7L, instruction.frameIndex)
        assertNotNull(instruction.sceneDetail)
        assertEquals(direct.sharpen.toRawBits(), instruction.quality.sharpen.toRawBits())
        assertEquals(direct.denoise.toRawBits(), instruction.quality.denoise.toRawBits())
        assertTrue(
            instruction.signature.startsWith(
                "NQIS${NeoQualityInstructionStream.VERSION}:7:SDR:FOCUSED_DETAIL:"
            )
        )
    }

    @Test
    fun `scene analysis can be disabled without disabling measured noise controls`() {
        val payload = p010(8, 8) { x, y -> if ((x + y) and 1 == 0) 430 else 570 }
        val instruction = NeoQualityInstructionStream.analyzeP010(
            frameIndex = 0L,
            payload = payload,
            previousP010Payload = null,
            width = 8,
            height = 8,
            signalFamily = GpuSignalFamily.HDR,
            detailProfile = VideoDetailProfile.NATURAL_DETAIL,
            sourceSceneAnalysisEnabled = false
        )

        assertNull(instruction.sceneDetail)
        assertTrue(instruction.noiseMetrics.lumaNoise > 0f)
        assertTrue(instruction.quality.sharpen in 0.12f..0.72f)
        assertTrue(instruction.quality.denoise in 0.22f..0.76f)
    }

    private fun p010(width: Int, height: Int, y10: (Int, Int) -> Int): ByteArray {
        val lumaBytes = width * height * 2
        val chromaSamples = ((width + 1) / 2) * ((height + 1) / 2)
        val payload = ByteArray(lumaBytes + chromaSamples * 4)
        fun write(offset: Int, sample: Int) {
            val word = sample.coerceIn(0, 1023) shl 6
            payload[offset] = word.toByte()
            payload[offset + 1] = (word ushr 8).toByte()
        }
        for (y in 0 until height) {
            for (x in 0 until width) write((y * width + x) * 2, y10(x, y))
        }
        var offset = lumaBytes
        repeat(chromaSamples) {
            write(offset, 512)
            write(offset + 2, 512)
            offset += 4
        }
        return payload
    }
}
