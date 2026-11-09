package com.neoproc.neoproccamx.recording

import android.os.Build
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

/** Physical JNI/file ownership tests, not synthetic proof of camera FPS or optical quality. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class Npv10EarlyImageReleaseInstrumentedTest {
    private class Fixture(val width: Int = 1024, val height: Int = 256) {
        val y = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        val uv = ByteBuffer.allocateDirect(width * height).order(ByteOrder.LITTLE_ENDIAN)
        val expected = ByteArray(width * height * 3)

        init {
            var target = 0
            for (plane in listOf(y, uv)) {
                repeat(plane.capacity() / 2) { index ->
                    val code = (113 + index * 17 + (index / 64) * 3) and 1023
                    val word = code shl 6
                    plane.putShort(index * 2, word.toShort())
                    expected[target++] = word.toByte()
                    expected[target++] = (word ushr 8).toByte()
                }
            }
        }

        fun poisonReturnedSource() {
            for (plane in listOf(y, uv)) {
                repeat(plane.capacity() / 2) { plane.putShort(it * 2, 0) }
            }
        }
    }

    @Test(timeout = 30_000L)
    fun sourceCanBeReusedImmediatelyAfterCopyWithoutChangingAnyStoredSample() =
        withEngine { engine, file, fixture, reservation ->
            val callbacks = AtomicInteger()
            val token = pack(engine, fixture, Runnable {
                assertEquals(1, callbacks.incrementAndGet())
                // Model the actual buffer-reuse boundary, not an FPS measurement. If native
                // compression still reads Camera2 memory after this callback, parity fails.
                fixture.poisonReturnedSource()
            })
            assertEquals(1, callbacks.get())
            persistAndVerify(engine, file, fixture, reservation, token)
        }

    @Test(timeout = 30_000L)
    fun legacyCallerWithoutEarlyReleaseRetainsTheSameExactCodecContract() =
        withEngine { engine, file, fixture, reservation ->
            persistAndVerify(engine, file, fixture, reservation, pack(engine, fixture, null))
        }

    @Test(timeout = 30_000L)
    fun releaseExceptionPropagatesAndRetiresNativeProcessingBeforeAbortAndClose() =
        withEngine { engine, _, fixture, _ ->
            val failure = IllegalStateException("injected source release failure")
            val callbacks = AtomicInteger()
            try {
                pack(engine, fixture, Runnable {
                    callbacks.incrementAndGet()
                    throw failure
                })
                throw AssertionError("Source release failure was hidden")
            } catch (invocation: InvocationTargetException) {
                assertSame(failure, invocation.cause)
            }
            assertEquals(1, callbacks.get())
            engine.abortFrame(0L)
            assertEquals(0L, engine.snapshot().framesCommitted)
            assertEquals(0L, engine.snapshot().pendingRecordQueueDepth)
        }

    @Test(timeout = 30_000L)
    fun malformedPlaneDoesNotReleaseSourceOrPublishAndStillRetiresItsClaim() =
        withEngine { engine, _, fixture, _ ->
            val callbacks = AtomicInteger()
            assertEquals(-1L, pack(engine, fixture, Runnable { callbacks.incrementAndGet() },
                yRowStride = 1))
            assertEquals(0, callbacks.get())
            engine.abortFrame(0L)
            assertEquals(0L, engine.snapshot().framesCommitted)
        }

    @Test(timeout = 30_000L)
    fun staleFrameCannotRunTheReleaseCallbackForAnotherOwnersPlanes() =
        withEngine { engine, _, fixture, _ ->
            val callbacks = AtomicInteger()
            assertEquals(-1L, pack(engine, fixture, Runnable { callbacks.incrementAndGet() },
                frameIndex = 1L))
            assertEquals(0, callbacks.get())
            engine.abortFrame(0L)
            assertEquals(0L, engine.snapshot().framesCommitted)
        }

    private fun pack(
        engine: Npv10DirectMappedCommitEngine,
        fixture: Fixture,
        release: Runnable?,
        yRowStride: Int = fixture.width * 2,
        frameIndex: Long = 0L
    ): Long {
        // Exercise the real private JNI seam without adding a test-only production entry point.
        val native = Npv10FramePacker::class.java.getDeclaredMethod(
            "nativePackP010ImagePlanesToDirectMappedTenBitWithCrc",
            ByteBuffer::class.java, ByteBuffer::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            Runnable::class.java
        ).apply { isAccessible = true }
        return native.invoke(null, fixture.y, fixture.uv, 0, 0, fixture.width, fixture.height,
            yRowStride, fixture.width * 2, 2, 4, engine.processingHandle(), frameIndex, release) as Long
    }

    private fun persistAndVerify(
        engine: Npv10DirectMappedCommitEngine,
        file: File,
        fixture: Fixture,
        reservation: Long,
        token: Long
    ) {
        assertTrue("Native preparation failed", token >= 0L)
        val storedBytes = (token ushr 32).toInt()
        val checksum = token.toInt()
        assertTrue(storedBytes in 1..engine.payloadCapacityBytes)
        engine.commitFrame(0L, storedBytes, DATA_START + FRAME_HEADER_BYTES, reservation,
            checksum, 40_000_000L, fixture.width, fixture.height)
        engine.awaitCommittedFrames(1L)
        val packet = ByteArray(storedBytes)
        RandomAccessFile(file, "rw").use { input ->
            input.fd.sync()
            input.seek(DATA_START)
            assertEquals(0x46524D30, input.readInt())
            assertEquals(40_000_000L, input.readLong())
            assertEquals(fixture.width, input.readInt())
            assertEquals(fixture.height, input.readInt())
            assertEquals(storedBytes, input.readInt())
            assertEquals(checksum, input.readInt())
            input.readFully(packet)
        }
        assertEquals(checksum.toLong() and 0xffff_ffffL, CRC32().apply { update(packet) }.value)
        assertArrayEquals(fixture.expected, Npv10PacketCodecEngine.decode(packet,
            fixture.expected.size, Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2))
        assertArrayEquals("Independent Kotlin wire decode differs", fixture.expected,
            Npv10ChunkedLosslessCodec.decodeP010(packet, fixture.expected.size))
        assertEquals(1L, engine.snapshot().asynchronouslyPersistedFrames)
        assertEquals(0L, engine.snapshot().asynchronousRecordWriteFailures)
    }

    private fun withEngine(
        body: (Npv10DirectMappedCommitEngine, File, Fixture, Long) -> Unit
    ) {
        assertTrue(Npv10FramePacker.prepareP010PackingExecutor())
        val fixture = Fixture()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "npv10-early-release-${System.nanoTime()}.bin")
        val packed = Npv10PacketCodecEngine.packedP010Bytes(fixture.expected.size.toLong()).toInt()
        val capacity = Npv10PacketCodecEngine.storedBytes(fixture.expected.size.toLong(),
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2).toInt()
        val reservation = DATA_START + 4L * (FRAME_HEADER_BYTES + capacity)
        try {
            RandomAccessFile(file, "rw").use { Os.posix_fallocate(it.fd, 0, reservation) }
            Npv10DirectMappedCommitEngine.open(file, DATA_START, FRAME_HEADER_BYTES, packed,
                capacity, fixture.expected.size / 2, true, 2, reservation).use { engine ->
                engine.beginFrame(0L, reservation)
                body(engine, file, fixture, reservation)
            }
        } finally {
            assertTrue(!file.exists() || file.delete())
        }
    }

    private companion object {
        const val DATA_START = 4096L
        const val FRAME_HEADER_BYTES = 28
    }
}
