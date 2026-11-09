package com.neoproc.neoproccamx.recording

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Real JNI/file ownership checks; synthetic input is not camera FPS or image-quality proof. */
@RunWith(AndroidJUnit4::class)
class Npv10FusedLosslessLeaseInstrumentedTest {
    @Test(timeout = 60_000L)
    fun releasedPlanesCanBeOverwrittenWithoutChangingCommittedOriginalPixels() = runReleasedPlaneParity()

    @Test(timeout = 60_000L)
    fun distinctPaddedPlaneStridesPreserveEveryRowAcrossNativeCommitAndBothDecoders() =
        runReleasedPlaneParity(yPadding = 32, uvPadding = 96)

    @Test(timeout = 90_000L)
    fun ownedFullResolutionPacketsPreserveVariableOffsetsAndReusedSlotBytes() = withOwner(reservedFrames = 5L) { owner ->
        var tail = DATA_START
        var storedTotal = 0L
        repeat(4) { frame ->
            if (frame != 0) owner.fill(if (frame and 1 == 0) 0x4080_4000_40c0_4040L else 0x7fc0_4000_0040_0000L)
            val expected = owner.originalBytes()
            owner.engine.beginFrame(frame.toLong(), owner.reserved)
            var released = 0
            val token = owner.prepare(Runnable { released++; owner.fill(0L) }, frame.toLong())
            assertTrue(token >= 0L)
            assertEquals(1, released)
            val stored = (token ushr 32).toInt()
            owner.engine.commitFrame(frame.toLong(), stored, tail + HEADER_BYTES, owner.reserved,
                token.toInt(), 1_000_000L + frame * 40_000_000L, WIDTH, HEIGHT)
            owner.engine.awaitCommittedFrames(frame + 1L)
            owner.engine.forceCommittedFrames(frame + 1L)
            val packet = ByteArray(stored)
            RandomAccessFile(owner.file, "r").use {
                it.seek(tail)
                assertEquals(0x46524d30, it.readInt())
                assertEquals(1_000_000L + frame * 40_000_000L, it.readLong())
                assertEquals(WIDTH, it.readInt()); assertEquals(HEIGHT, it.readInt())
                assertEquals(stored, it.readInt()); assertEquals(token.toInt(), it.readInt())
                it.readFully(packet)
            }
            assertEquals(token.toInt().toLong() and 0xffff_ffffL, CRC32().apply { update(packet) }.value)
            assertArrayEquals(expected, Npv10ChunkedLosslessCodec.decodeP010(packet, DECODED_BYTES))
            tail += HEADER_BYTES + stored
            storedTotal += stored
            val telemetry = owner.engine.snapshot()
            assertTrue("Qualification requires this real aligned storage backend", telemetry.alignedRecordWriterEnabled)
            assertEquals(frame + 1L, telemetry.kernelAio.ownedRecordWrites)
            assertEquals(storedTotal, telemetry.kernelAio.ownedPayloadBytes)
            assertEquals(0L, telemetry.asynchronousRecordWriteFailures)
            assertEquals(0L, telemetry.pendingRecordQueueDepth)
        }
    }

    private fun runReleasedPlaneParity(yPadding: Int = 0, uvPadding: Int = 0) =
        withOwner(yPadding, uvPadding) { owner ->
        val expected = owner.originalBytes()
        owner.engine.beginFrame(0L, owner.reserved)
        var callbacks = 0
        val token = owner.prepare(Runnable {
            callbacks++
            owner.fill(0L)
        })
        assertEquals(1, callbacks)
        assertTrue("Fused packet preparation failed", token >= 0L)
        val stored = (token ushr 32).toInt()
        owner.engine.commitFrame(0L, stored, DATA_START + HEADER_BYTES, owner.reserved,
            token.toInt(), 1_000_000L, WIDTH, HEIGHT)
        owner.engine.awaitCommittedFrames(1L)
        owner.engine.forceCommittedFrames(1L)
        assertEquals(1L, owner.engine.snapshot().asynchronouslyPersistedFrames)
        val packet = ByteArray(stored)
        RandomAccessFile(owner.file, "r").use {
            it.seek(DATA_START + HEADER_BYTES)
            it.readFully(packet)
        }
        assertEquals(token.toInt().toLong() and 0xffff_ffffL, CRC32().apply { update(packet) }.value)
        assertArrayEquals("Independent Kotlin decoder must recover pixels from before source release",
            expected, Npv10ChunkedLosslessCodec.decodeP010(packet, DECODED_BYTES))
        // The ByteArray overload is the Kotlin fallback, not the native decoder.
        // Exercise the strict native hook and compare every byte, without another
        // full-frame heap copy or a GC-owned DirectByteBuffer retained across tests.
        Npv10NativeFrameBuffer.allocate(packet.size).use { input ->
            Npv10NativeFrameBuffer.allocate(DECODED_BYTES).use { output ->
                input.buffer.put(packet).flip()
                assertTrue("Native decoder failed (scalar fallback is not admitted)",
                    Npv10PacketCodecEngine.decodeChunkedLosslessNativeForTest(input.buffer,
                        packet.size, DECODED_BYTES, output.buffer))
                val reference = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN)
                val actual = output.buffer.order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(reference.remaining(), actual.remaining())
                while (reference.hasRemaining()) {
                    assertEquals("Native decoder changed original sample bits", reference.long, actual.long)
                }
            }
        }
    }

    @Test(timeout = 30_000L)
    fun throwingReleaseCallbackRetiresProcessingAndAllowsSameSlotRecovery() = withOwner { owner ->
        owner.engine.beginFrame(0L, owner.reserved)
        var callbacks = 0
        val failure = runCatching {
            owner.prepare(Runnable { callbacks++; throw IllegalStateException("test source release failure") })
        }.exceptionOrNull()
        assertTrue(failure is InvocationTargetException && failure.cause is IllegalStateException)
        assertEquals(1, callbacks)
        assertEquals(0L, owner.engine.snapshot().framesCommitted)
        owner.engine.abortFrame(0L) // terminal-owner cleanup is idempotent after native retirement
        owner.engine.beginFrame(0L, owner.reserved)
        assertTrue(owner.prepare(Runnable { callbacks++ }) >= 0L)
        assertEquals(2, callbacks)
        owner.engine.abortFrame(0L)
    }

    @Test(timeout = 30_000L)
    fun invalidLastChromaPaddingNeverPublishesAndDoesNotLeakProcessingOwnership() = withOwner { owner ->
        val offset = owner.uv.capacity() - 2
        val saved = owner.uv.get(offset)
        owner.uv.put(offset, (saved.toInt() or 1).toByte())
        owner.engine.beginFrame(0L, owner.reserved)
        var callbacks = 0
        assertEquals(-1L, owner.prepare(Runnable { callbacks++ }))
        assertEquals(0, callbacks)
        assertEquals(0L, owner.engine.snapshot().framesCommitted)
        owner.engine.abortFrame(0L)
        owner.uv.put(offset, saved)
        owner.engine.beginFrame(0L, owner.reserved)
        assertTrue(owner.prepare(Runnable { callbacks++ }) >= 0L)
        assertEquals(1, callbacks)
        owner.engine.abortFrame(0L)
    }

    private class Owner(val file: File, val engine: Npv10DirectMappedCommitEngine, val reserved: Long,
                        val y: ByteBuffer, val uv: ByteBuffer,
                        val yStride: Int, val uvStride: Int) {
        init {
            fill(0x4080_4000_40c0_4040L)
            for ((plane, stride, rows) in listOf(Triple(y, yStride, HEIGHT), Triple(uv, uvStride, HEIGHT / 2))) {
                repeat(rows) { row ->
                    // Distinguish rows and put noncanonical bits ONLY in padding. Reading
                    // padding or applying Y's pitch to UV must fail the unchanged decoder.
                    plane.putShort(row * stride, (((row * 17 + 83) and 1023) shl 6).toShort())
                    plane.putShort(row * stride + WIDTH * 2 - 2,
                        (((row * 29 + 173) and 1023) shl 6).toShort())
                    for (offset in row * stride + WIDTH * 2 until minOf((row + 1) * stride, plane.capacity())) {
                        plane.put(offset, 0x7f.toByte())
                    }
                }
            }
        }
        fun fill(word: Long) {
            for (plane in listOf(y, uv)) {
                plane.clear()
                while (plane.remaining() >= Long.SIZE_BYTES) plane.putLong(word)
                plane.clear()
            }
        }
        fun originalBytes(): ByteArray = ByteArray(DECODED_BYTES).also {
            var target = 0
            for ((plane, stride, rows) in listOf(Triple(y, yStride, HEIGHT), Triple(uv, uvStride, HEIGHT / 2))) {
                val source = plane.duplicate()
                repeat(rows) { row ->
                    source.position(row * stride)
                    source.get(it, target, WIDTH * 2)
                    target += WIDTH * 2
                }
            }
        }
        fun prepare(callback: Runnable, frameIndex: Long = 0L): Long {
            val method = Npv10FramePacker::class.java.declaredMethods.single {
                it.name == "nativePackP010ImagePlanesToDirectMappedTenBitWithCrc"
            }.apply { isAccessible = true }
            return method.invoke(null, y, uv, 0, 0, WIDTH, HEIGHT, yStride, uvStride,
                2, 4, engine.processingHandle(), frameIndex, callback) as Long
        }
    }

    private fun withOwner(yPadding: Int = 0, uvPadding: Int = 0, reservedFrames: Long = 2L, test: (Owner) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "lq001-fused-lease-${System.nanoTime()}.bin")
        val packed = Npv10PacketCodecEngine.packedP010Bytes(DECODED_BYTES.toLong()).toInt()
        val capacity = Npv10PacketCodecEngine.storedBytes(DECODED_BYTES.toLong(),
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2).toInt()
        // Preserve the two-slot memory bound. A four-frame test must separately reserve enough
        // physical FILE space for its incompressible packets plus the final alignment sector.
        val reserved = DATA_START + (HEADER_BYTES + capacity).toLong() * reservedFrames
        val yStride = WIDTH * 2 + yPadding
        val uvStride = WIDTH * 2 + uvPadding
        try {
            RandomAccessFile(file, "rw").use { Os.posix_fallocate(it.fd, 0L, reserved) }
            Npv10NativeFrameBuffer.allocate((HEIGHT - 1) * yStride + WIDTH * 2).use { y ->
                Npv10NativeFrameBuffer.allocate((HEIGHT / 2 - 1) * uvStride + WIDTH * 2).use { uv ->
                    Npv10DirectMappedCommitEngine.open(file = file, dataStartOffset = DATA_START,
                        frameHeaderBytes = HEADER_BYTES, payloadBytes = packed, payloadCapacityBytes = capacity,
                        sampleCount = DECODED_BYTES / 2, chunkedLosslessCompression = true, slotCount = 2,
                        reservedBytes = reserved).use {
                        test(Owner(file, it, reserved, y.buffer, uv.buffer, yStride, uvStride))
                    }
                }
            }
        } finally {
            assertTrue("Test-owned scratch could not be removed", !file.exists() || file.delete())
        }
    }

    private companion object {
        const val WIDTH = 4096
        const val HEIGHT = 3072
        const val DECODED_BYTES = WIDTH * HEIGHT * 3
        const val DATA_START = 4096L
        const val HEADER_BYTES = 28
    }
}
