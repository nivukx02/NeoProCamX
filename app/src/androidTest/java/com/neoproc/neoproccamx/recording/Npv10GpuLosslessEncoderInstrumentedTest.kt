package com.neoproc.neoproccamx.recording

import android.app.Activity
import android.content.Intent
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real GL compute and native codec tests. Pattern tests establish bits, never camera FPS/quality. */
@RunWith(AndroidJUnit4::class)
class Npv10GpuLosslessEncoderInstrumentedTest {
    private var foreground: ActivityScenario<Activity>? = null

    @Before fun acquireNormalForegroundHost() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        foreground = ActivityScenario.launch(Intent().setClassName(context.packageName,
            "com.neoproc.neoproccamx.testing.Lq031KeepAliveActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @After fun releaseNormalForegroundHost() {
        foreground?.close()
        foreground = null
    }

    private fun capacity(width: Int, height: Int) = Npv10PacketCodecEngine.storedBytes(
        width.toLong() * height * 3L, Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
    ).toInt()

    private fun planes(source: ByteBuffer, width: Int, height: Int): Pair<ByteBuffer, ByteBuffer> {
        val boundary = width * height * 2
        val y = source.duplicate().apply { position(0); limit(boundary) }.slice()
        val uv = source.duplicate().apply { position(boundary); limit(source.capacity()) }.slice()
        return y to uv
    }

    private fun bytes(buffer: ByteBuffer, count: Int) = ByteArray(count).also {
        buffer.duplicate().apply { position(0); limit(count) }.get(it)
    }

    private fun encode(source: ByteBuffer, width: Int, height: Int, output: ByteBuffer,
                       gpu: Boolean, parallel: Boolean = false): LongArray {
        val (y, uv) = planes(source, width, height)
        return requireNotNull(if (parallel) {
            Npv10GpuLosslessEncoder.encodeParallel(y, uv, width, height, width * 2, width * 2, output)
        } else Npv10GpuLosslessEncoder.encode(y, uv, width, height, width * 2, width * 2, output, gpu)) {
            "${if (gpu) "GPU" else "CPU"} rejected actual canonical P010"
        }
    }

    @Test fun everyResidualWidthAndOriginMatchesOriginalCpuWireBytes() {
        val width = 64
        val height = 2
        val source = ByteBuffer.allocateDirect(width * height * 3).order(ByteOrder.LITTLE_ENDIAN)
        val cpu = ByteBuffer.allocateDirect(capacity(width, height))
        val gpu = ByteBuffer.allocateDirect(cpu.capacity())
        val restored = ByteBuffer.allocateDirect(source.capacity())
        try {
            assertTrue("Real GLES 3.1 compute admission required", Npv10GpuLosslessEncoder.prepare(width, height))
            var cases = 0
            for (evenBits in 0..10) for (oddBits in 0..10) for (origin in 0..2) {
                var state = 0x34123781
                source.clear()
                repeat(source.capacity() / 2) { sample ->
                    state = state * 1_664_525 + 1_013_904_223
                    val bits = if (sample and 1 == 0) evenBits else oddBits
                    val mask = (1 shl bits) - 1
                    val minimum = (1023 - mask) * origin / 2
                    val residual = when (sample % 64) {
                        0, 1 -> 0
                        2, 3 -> mask
                        else -> (state ushr 12) and mask
                    }
                    source.putShort(((minimum + residual) shl 6).toShort())
                }
                source.clear()
                val expected = encode(source, width, height, cpu, false)
                val actual = encode(source, width, height, gpu, true)
                assertArrayEquals("CPU/GPU metadata $evenBits/$oddBits/$origin", expected.copyOf(5), actual.copyOf(5))
                val packet = bytes(gpu, actual[0].toInt())
                assertArrayEquals("Exact NLC2 bytes $evenBits/$oddBits/$origin", bytes(cpu, expected[0].toInt()), packet)
                assertEquals(CRC32().apply { update(packet) }.value, actual[1])
                val parallel = encode(source, width, height, gpu, false, parallel = true)
                assertArrayEquals("Parallel metadata $evenBits/$oddBits/$origin", expected.copyOf(5), parallel.copyOf(5))
                assertArrayEquals("Parallel exact NLC2 $evenBits/$oddBits/$origin", packet, bytes(gpu, parallel[0].toInt()))
                assertTrue(Npv10PacketCodecEngine.decodeChunkedLosslessNativeForTest(gpu, packet.size,
                    source.capacity(), restored))
                assertArrayEquals(bytes(source, source.capacity()), bytes(restored, restored.capacity()))
                cases++
            }
            Log.i(TAG, "GPU_INTEGER_CONTRACT cases=$cases actualGpu=true exactWire=true sourceCrc=CPU cameraFps=NOT_TESTED")
        } finally { Npv10GpuLosslessEncoder.close() }
    }

    @Test fun paddedRowsAndMultipleChunksPreserveEverySample() {
        val width = 4096
        val height = 46
        val rowStride = width * 2 + 128
        val y = ByteBuffer.allocateDirect(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        val uv = ByteBuffer.allocateDirect(rowStride * (height / 2)).order(ByteOrder.LITTLE_ENDIAN)
        listOf(y, uv).forEachIndexed { plane, buffer ->
            repeat(buffer.capacity() / rowStride) { row ->
                repeat(width) { column ->
                    val value = 128 + ((row * 11 + column * 3 + plane * 191) and 255)
                    buffer.putShort(row * rowStride + column * 2, (value shl 6).toShort())
                }
                repeat(128) { padding -> buffer.put(row * rowStride + width * 2 + padding, 0x7f) }
            }
        }
        val cpu = ByteBuffer.allocateDirect(capacity(width, height))
        val gpu = ByteBuffer.allocateDirect(cpu.capacity())
        try {
            assertTrue(Npv10GpuLosslessEncoder.prepare(width, height))
            val expected = requireNotNull(Npv10GpuLosslessEncoder.encode(y, uv, width, height, rowStride, rowStride, cpu, false))
            val actual = requireNotNull(Npv10GpuLosslessEncoder.encode(y, uv, width, height, rowStride, rowStride, gpu, true))
            assertArrayEquals(expected.copyOf(5), actual.copyOf(5))
            assertArrayEquals(bytes(cpu, expected[0].toInt()), bytes(gpu, actual[0].toInt()))
            val parallel = requireNotNull(Npv10GpuLosslessEncoder.encodeParallel(y, uv, width, height,
                rowStride, rowStride, gpu))
            assertArrayEquals(expected.copyOf(5), parallel.copyOf(5))
            assertArrayEquals(bytes(cpu, expected[0].toInt()), bytes(gpu, parallel[0].toInt()))
        } finally { Npv10GpuLosslessEncoder.close() }
    }

    @Test fun invalidShapesCapacityAndCanonicalPaddingFailClosed() {
        assertFalse(Npv10GpuLosslessEncoder.prepare(0, 3072))
        assertFalse(Npv10GpuLosslessEncoder.prepare(4096, 3071))
        assertFalse(Npv10GpuLosslessEncoder.prepare(8192, 8192))
        val source = ByteBuffer.allocateDirect(64 * 2 * 3)
        val (y, uv) = planes(source, 64, 2)
        val output = ByteBuffer.allocateDirect(capacity(64, 2))
        try {
            assertTrue(Npv10GpuLosslessEncoder.prepare(64, 2))
            assertNull(Npv10GpuLosslessEncoder.encode(y, uv, 64, 2, 127, 128, output, true))
            assertNull(Npv10GpuLosslessEncoder.encode(y, uv, 64, 2, 128, 128,
                ByteBuffer.allocateDirect(1), true))
            assertNull(Npv10GpuLosslessEncoder.encodeParallel(y, uv, 64, 2, 127, 128, output))
            assertNull(Npv10GpuLosslessEncoder.encodeParallel(y, uv, 64, 2, 128, 128,
                ByteBuffer.allocateDirect(1)))
            source.put(0, 1) // A low padding bit must never be silently discarded.
            assertNull(Npv10GpuLosslessEncoder.encode(y, uv, 64, 2, 128, 128, output, true))
            assertNull(Npv10GpuLosslessEncoder.encodeParallel(y, uv, 64, 2, 128, 128, output))
        } finally { Npv10GpuLosslessEncoder.close() }
        source.put(0, 0)
        try {
            assertTrue("Explicit new session recovers after retired failure", Npv10GpuLosslessEncoder.prepare(64, 2))
            assertTrue(encode(source, 64, 2, output, true)[0] > 0L)
        } finally { Npv10GpuLosslessEncoder.close() }
    }

    @Test fun realRecordedFramesHaveBitExactCpuGpuParityAndMeasuredTotalCost() {
        val arguments = InstrumentationRegistry.getArguments()
        val relativeSources = requireNotNull(arguments.getString("neoGpuP010Sources")) {
            "Physical GPU qualification requires retained real-camera P010 samples"
        }.split(',')
        require(relativeSources.size == 3) { "Supply exact first, middle and last recorded samples" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val allowedRoot = File(context.filesDir, "qa-recorded-frame-audits").canonicalFile
        val width = 4096
        val height = 3072
        val source = ByteBuffer.allocateDirect(width * height * 3)
        val cpu = ByteBuffer.allocateDirect(capacity(width, height))
        val gpu = ByteBuffer.allocateDirect(cpu.capacity())
        val restored = ByteBuffer.allocateDirect(source.capacity())
        try {
            assertTrue("Actual device GPU compute admission", Npv10GpuLosslessEncoder.prepare(width, height))
            relativeSources.forEachIndexed { index, relative ->
                val file = File(context.filesDir, relative).canonicalFile
                require(file.toPath().startsWith(allowedRoot.toPath()) && file.isFile && file.length() == source.capacity().toLong())
                source.clear()
                FileInputStream(file).channel.use { channel ->
                    while (source.hasRemaining()) check(channel.read(source) > 0) { "Truncated actual P010 frame" }
                }
                source.clear()
                val expected = encode(source, width, height, cpu, false)
                val actual = encode(source, width, height, gpu, true)
                assertArrayEquals(expected.copyOf(5), actual.copyOf(5))
                assertArrayEquals(bytes(cpu, expected[0].toInt()), bytes(gpu, actual[0].toInt()))
                // ABBA interleaving includes upload, independent CPU packing/CRC, fence and assembly.
                // No device-camera FPS assertion is derived from these replay timings.
                repeat(3) { cycle ->
                    listOf(false, true, true, false).forEach { useGpu ->
                        val result = encode(source, width, height, if (useGpu) gpu else cpu, useGpu)
                        assertArrayEquals(expected.copyOf(5), result.copyOf(5))
                        Log.i(TAG, "ACTUAL_FRAME_GPU_ABBA sample=$index cycle=$cycle gpu=$useGpu " +
                            "totalNs=${result[9]} uploadNs=${result[5]} cpuOriginalPackNs=${result[6]} " +
                            "gpuWaitNs=${result[7]} assembleNs=${result[8]} bytes=${result[0]} " +
                            "source=${file.name} cameraFps=NOT_TESTED")
                    }
                }
                val parallelFirst = encode(source, width, height, gpu, false, parallel = true)
                assertArrayEquals(expected.copyOf(5), parallelFirst.copyOf(5))
                assertArrayEquals(bytes(cpu, expected[0].toInt()), bytes(gpu, parallelFirst[0].toInt()))
                repeat(3) { cycle ->
                    listOf(false, true, true, false).forEach { useParallel ->
                        val result = encode(source, width, height, if (useParallel) gpu else cpu,
                            false, parallel = useParallel)
                        assertArrayEquals(expected.copyOf(5), result.copyOf(5))
                        Log.i(TAG, "ACTUAL_FRAME_PARALLEL_ABBA sample=$index cycle=$cycle parallel=$useParallel " +
                            "totalNs=${result[9]} bytes=${result[0]} source=${file.name} cameraFps=NOT_TESTED")
                    }
                }
                assertTrue(Npv10PacketCodecEngine.decodeChunkedLosslessNativeForTest(gpu, actual[0].toInt(),
                    source.capacity(), restored))
                assertArrayEquals(bytes(source, source.capacity()), bytes(restored, restored.capacity()))
                Log.i(TAG, "ACTUAL_FRAME_GPU_BIT_EXACT sample=$index bytes=${source.capacity()} sourceCrc=CPU decode=true")
            }
        } finally { Npv10GpuLosslessEncoder.close() }
    }

    @Test fun gpuAdmissionEncodeFailureAndClosePreserveCallerEglContext() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        assertTrue(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE), 0,
            configs, 0, 1, count, 0))
        assertEquals(1, count[0])
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        val texture = IntArray(1)
        try {
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context))
            GLES20.glGenTextures(1, texture, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
            assertTrue(GLES20.glIsTexture(texture[0]))
            assertTrue(Npv10GpuLosslessEncoder.prepare(64, 2))
            assertEquals(context, EGL14.eglGetCurrentContext())
            val source = ByteBuffer.allocateDirect(64 * 2 * 3)
            val output = ByteBuffer.allocateDirect(capacity(64, 2))
            assertTrue(encode(source, 64, 2, output, true)[0] > 0L)
            assertEquals(context, EGL14.eglGetCurrentContext())
            source.put(0, 1)
            val (y, uv) = planes(source, 64, 2)
            assertNull(Npv10GpuLosslessEncoder.encode(y, uv, 64, 2, 128, 128, output, true))
            assertEquals(context, EGL14.eglGetCurrentContext())
            Npv10GpuLosslessEncoder.close()
            assertEquals(context, EGL14.eglGetCurrentContext())
            assertEquals(surface, EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW))
            assertTrue("GPU cleanup must not destroy caller GL objects/display", GLES20.glIsTexture(texture[0]))
        } finally {
            Npv10GpuLosslessEncoder.close()
            GLES20.glDeleteTextures(1, texture, 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglDestroySurface(display, surface)
            // Android's foreground host/render thread shares this display; never terminate it.
        }
    }

    companion object { private const val TAG = "Npv10GpuQualification" }
}
