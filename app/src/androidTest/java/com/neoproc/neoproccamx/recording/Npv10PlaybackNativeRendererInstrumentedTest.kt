package com.neoproc.neoproccamx.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Physical ABI/JNI parity gate for the realtime private-master monitor renderer. */
@RunWith(AndroidJUnit4::class)
class Npv10PlaybackNativeRendererInstrumentedTest {

    @Test
    fun nativeP010Bt709RendererMatchesReferenceWithinOneDisplayCode() {
        val width = 16
        val height = 8
        val scale = 2
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val samples = width * height + chromaWidth * chromaHeight * 2
        val payload = ByteBuffer.allocateDirect(samples * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        val luma = IntArray(width * height) { index ->
            64 + ((index * 37) % 877)
        }
        val cb = IntArray(chromaWidth * chromaHeight) { index ->
            64 + ((index * 71) % 897)
        }
        val cr = IntArray(chromaWidth * chromaHeight) { index ->
            64 + ((index * 113) % 897)
        }
        luma.forEach { payload.putShort((it shl 6).toShort()) }
        for (index in cb.indices) {
            payload.putShort((cb[index] shl 6).toShort())
            payload.putShort((cr[index] shl 6).toShort())
        }
        payload.flip()

        val outputWidth = width / scale
        val outputHeight = height / scale
        val actual = IntArray(outputWidth * outputHeight)
        assertTrue(
            "Native P010 playback renderer unavailable on supported device ABI",
            Npv10PlaybackNativeRenderer.renderP010Bt709(
                payload = payload,
                width = width,
                height = height,
                scale = scale,
                output = actual
            )
        )

        for (y in 0 until outputHeight) {
            val sourceY = y * scale
            for (x in 0 until outputWidth) {
                val sourceX = x * scale
                val yCode = luma[sourceY * width + sourceX]
                val chromaIndex = (sourceY / 2) * chromaWidth + sourceX / 2
                val expected = Npv10ThumbnailRenderer.yuvToArgb(
                    yCode,
                    cb[chromaIndex],
                    cr[chromaIndex]
                )
                assertArgbNear(expected, actual[y * outputWidth + x])
            }
        }
    }

    private fun assertArgbNear(expected: Int, actual: Int) {
        val redDelta = abs(((expected ushr 16) and 0xFF) - ((actual ushr 16) and 0xFF))
        val greenDelta = abs(((expected ushr 8) and 0xFF) - ((actual ushr 8) and 0xFF))
        val blueDelta = abs((expected and 0xFF) - (actual and 0xFF))
        assertTrue(
            "Native/reference ARGB mismatch expected=${expected.toUInt().toString(16)} " +
                "actual=${actual.toUInt().toString(16)} deltas=$redDelta,$greenDelta,$blueDelta",
            redDelta <= 1 && greenDelta <= 1 && blueDelta <= 1
        )
    }
}
