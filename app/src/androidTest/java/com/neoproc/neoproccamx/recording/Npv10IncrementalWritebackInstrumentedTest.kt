package com.neoproc.neoproccamx.recording

import android.os.Build
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.CRC32

/** Real native/file regression with synthetic data; not physical camera endurance evidence. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class Npv10IncrementalWritebackInstrumentedTest {
    @Test
    fun fullFrameLosslessReservationDoesNotSuppressWriteback() {
        withFile { file ->
            RandomAccessFile(file, "rw").use { Os.posix_fallocate(it.fd, 0, RESERVED_BYTES) }
            open(file).use { engine ->
                val stats = engine.snapshot()
                assertTrue(stats.incrementalWritebackEnabled)
                assertFalse(stats.incrementalWritebackSuppressedForRealtime)
                assertEquals(MAX_REQUEST_BYTES, stats.incrementalWritebackTargetBytes)
                assertEquals(0L, stats.incrementalWritebackAttempts)
            }
        }
    }

    @Test
    fun fullFrameLosslessWritebackIsBoundedAndPreservesEverySampleAndCrc() {
        val encoding = Npv10Niv99ColorEncoding(Npv10Niv99ColorMatrix.BT709, Npv10Niv99Transfer.BT709)
        val source = ByteBuffer.allocateDirect(DECODED_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val random = Random(0x4E50563130L)
        repeat(DECODED_BYTES / 2) { source.putShort((random.nextInt(1024) shl 6).toShort()) }
        source.rewind()
        val reference = ByteBuffer.allocateDirect(PACKED_BYTES).order(ByteOrder.nativeOrder())
        assertTrue(Npv10FramePacker.processNiv99CanonicalP010ToPackedTenBitBufferWithCrc(
            source, WIDTH, HEIGHT, reference, encoding, 1f, 1f, 1f, 0f, 0f, 0f, 1f
        ) >= 0L)
        val referencePacked = ByteArray(PACKED_BYTES).also(reference.duplicate()::get)
        val expected = Npv10PacketCodecEngine.decode(
            referencePacked, DECODED_BYTES, Npv10PacketCodec.PACKED_P010_10_LE_V1
        )
        withFile { file ->
            RandomAccessFile(file, "rw").use { Os.posix_fallocate(it.fd, 0, RESERVED_BYTES) }
            val offsets = LongArray(FRAMES)
            val sizes = IntArray(FRAMES)
            val checksums = IntArray(FRAMES)
            var tail = DATA_START
            open(file).use { engine ->
                repeat(FRAMES) { frame ->
                    engine.beginFrame(frame.toLong(), RESERVED_BYTES)
                    val prepared = requireNotNull(
                        Npv10FramePacker.processNiv99CanonicalP010ToDirectMappedTenBitWithCrc(
                            source, WIDTH, HEIGHT, engine, frame.toLong(), encoding,
                            1f, 1f, 1f, 0f, 0f, 0f, 1f
                        )
                    )
                    offsets[frame] = tail + FRAME_HEADER_BYTES
                    sizes[frame] = prepared.encodedBytes
                    checksums[frame] = prepared.crc32
                    engine.commitFrame(
                        frame.toLong(), prepared.encodedBytes, offsets[frame], RESERVED_BYTES,
                        prepared.crc32, timestampNs = timestamp(frame), width = WIDTH, height = HEIGHT
                    )
                    tail = offsets[frame] + prepared.encodedBytes
                    engine.awaitCommittedFrames(frame + 1L)
                }
                assertTrue("fixture must exercise multiple bounded requests", tail - DATA_START > 2 * MAX_REQUEST_BYTES)
                val deadline = SystemClock.elapsedRealtime() + 15_000L
                var stats = engine.snapshot()
                while (stats.incrementalWritebackCoveredThroughOffset < tail &&
                    stats.incrementalWritebackFailures == 0L && SystemClock.elapsedRealtime() < deadline
                ) {
                    SystemClock.sleep(10)
                    stats = engine.snapshot()
                }
                assertEquals("this filesystem must actually execute the hint", 0L, stats.incrementalWritebackFailures)
                assertEquals(tail, stats.incrementalWritebackCoveredThroughOffset)
                assertTrue(stats.incrementalWritebackSuccesses >= 3L)
                assertTrue(stats.maximumIncrementalWritebackRequestBytes in 1..MAX_REQUEST_BYTES)
                assertEquals(tail - DATA_START, stats.asynchronouslyPersistedRecordBytes)
                assertEquals(FRAMES.toLong(), stats.asynchronouslyPersistedFrames)
                assertEquals(0L, stats.asynchronousRecordWriteFailures)
                assertEquals(0L, stats.pendingRecordQueueDepth)
                assertEquals(0L, stats.commitFailures)
            }
            RandomAccessFile(file, "rw").use { input ->
                input.channel.force(true)
                repeat(FRAMES) { frame ->
                    input.seek(offsets[frame] - FRAME_HEADER_BYTES)
                    assertEquals(0x46524D30, input.readInt())
                    assertEquals(timestamp(frame), input.readLong())
                    assertEquals(WIDTH, input.readInt())
                    assertEquals(HEIGHT, input.readInt())
                    assertEquals(sizes[frame], input.readInt())
                    assertEquals(checksums[frame], input.readInt())
                    val packet = ByteArray(sizes[frame]).also(input::readFully)
                    assertEquals(checksums[frame].toLong() and 0xffff_ffffL, CRC32().apply { update(packet) }.value)
                    assertArrayEquals("frame=$frame", expected, Npv10PacketCodecEngine.decode(packet, DECODED_BYTES, CODEC))
                }
            }
        }
    }

    private fun open(file: File) = Npv10DirectMappedCommitEngine.open(
        file, DATA_START, FRAME_HEADER_BYTES, PACKED_BYTES,
        slotCount = 2, reservedBytes = RESERVED_BYTES, payloadCapacityBytes = CAPACITY,
        sampleCount = DECODED_BYTES / 2, chunkedLosslessCompression = true
    )

    private fun withFile(block: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "npv10-writeback-${System.nanoTime()}.bin")
        try { block(file) } finally { assertTrue(!file.exists() || file.delete()) }
    }

    private fun timestamp(frame: Int) = 1_000_000L + frame * 40_000_000L

    private companion object {
        const val WIDTH = 4096
        const val HEIGHT = 3072
        const val DECODED_BYTES = WIDTH * HEIGHT * 3
        const val FRAME_HEADER_BYTES = 28
        const val DATA_START = 4096L
        const val FRAMES = 2
        const val MAX_REQUEST_BYTES = 16L * 1024 * 1024
        val CODEC = Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        val PACKED_BYTES = Npv10PacketCodecEngine.packedP010Bytes(DECODED_BYTES.toLong()).toInt()
        val CAPACITY = Npv10PacketCodecEngine.storedBytes(DECODED_BYTES.toLong(), CODEC).toInt()
        val RESERVED_BYTES = DATA_START + FRAMES.toLong() * (FRAME_HEADER_BYTES + CAPACITY)
    }
}
