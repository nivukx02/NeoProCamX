package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.rendering.GpuSignalFamily
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DirectP010QualityAnalyzerTest {
    @Test
    fun directNoiseAndSceneAnalysisMatchHeapPathExactly() {
        val width = 18
        val height = 12
        val current = frame(width, height, seed = 11)
        val previous = frame(width, height, seed = 7)
        val currentDirect = direct(current)
        val previousDirect = direct(previous)

        val heapNoise = Npv10ExportEnhancementPolicy.measureP010NoiseMetrics(
            current,
            previous,
            width,
            height
        )
        val directNoise = DirectP010QualityAnalyzer.measureNoise(
            currentDirect,
            previousDirect,
            width,
            height
        )
        val heapScene = requireNotNull(P010SceneDetailAnalyzer.analyze(current, width, height))
        val directScene = requireNotNull(P010SceneDetailAnalyzer.analyze(currentDirect, width, height))

        assertEquals(heapNoise.chromaNoise.toRawBits(), directNoise.chromaNoise.toRawBits())
        assertEquals(heapNoise.lumaNoise.toRawBits(), directNoise.lumaNoise.toRawBits())
        assertEquals(heapNoise.temporalInstability.toRawBits(), directNoise.temporalInstability.toRawBits())
        assertEquals(heapNoise.frameToFrameRgbVariance.toRawBits(), directNoise.frameToFrameRgbVariance.toRawBits())
        assertEquals(heapScene, directScene)
    }

    @Test
    fun directInstructionMatchesHeapInstruction() {
        val width = 18
        val height = 12
        val current = frame(width, height, seed = 23)
        val previous = frame(width, height, seed = 19)

        val heap = NeoQualityInstructionStream.analyzeP010(
            frameIndex = 4L,
            payload = current,
            previousP010Payload = previous,
            width = width,
            height = height,
            signalFamily = GpuSignalFamily.SDR,
            detailProfile = VideoDetailProfile.FOCUSED_DETAIL
        )
        val direct = NeoQualityInstructionStream.analyzeP010(
            frameIndex = 4L,
            payload = direct(current),
            previousP010Payload = direct(previous),
            width = width,
            height = height,
            signalFamily = GpuSignalFamily.SDR,
            detailProfile = VideoDetailProfile.FOCUSED_DETAIL
        )

        assertEquals(heap, direct)
    }

    private fun direct(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(bytes)
            flip()
        }

    private fun frame(width: Int, height: Int, seed: Int): ByteArray {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val samples = width * height + chromaWidth * chromaHeight * 2
        return ByteBuffer.allocate(samples * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                repeat(samples) { index ->
                    val code = (index * 37 + seed * 29 + (index / width) * 11) and 0x3FF
                    putShort((code shl 6).toShort())
                }
            }
            .array()
    }
}
