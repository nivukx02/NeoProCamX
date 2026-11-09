package com.neoproc.neoproccamx.recording

import android.os.Build
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/** Actual JNI/file tests. Synthetic pixels test ownership and fidelity, not physical camera FPS. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class Npv10LosslessOverlapInstrumentedTest {
    @Test
    fun parallelLosslessPreparationPreservesAllSamplesAndReusesCompressedReservation() =
        runOverlapCase(reverseCompletion = false)

    @Test
    fun reverseCompletionStillCommitsInTimestampOrderAndRetiresEverySlot() =
        runOverlapCase(reverseCompletion = true)

    @Test
    fun mixedRangeWidthsAcrossChunksPreserveSamplesAndIndependentWireDecoder() =
        runOverlapCase(reverseCompletion = false, width = 1024, height = 256, variedSamples = true)

    @Test
    fun mixedRangeDirectChunkWritesRemainOrderedAfterReverseWorkerCompletion() =
        runOverlapCase(reverseCompletion = true, width = 1024, height = 256, variedSamples = true)

    @Test
    fun everyTenBitCodeAtEveryWordPairPositionSurvivesConcurrentNativeCommit() =
        runOverlapCase(
            reverseCompletion = true, width = 1024, height = 256,
            variedSamples = true, wordPairCodes = true
        )

    @Test
    fun adjacentCompactFieldsSurviveReverseNativeCompletionAndIndependentDecode() =
        runOverlapCase(
            reverseCompletion = true, width = 1024, height = 256,
            variedSamples = true, compactFields = true
        )

    private fun runOverlapCase(
        reverseCompletion: Boolean,
        width: Int = WIDTH,
        height: Int = HEIGHT,
        variedSamples: Boolean = false,
        wordPairCodes: Boolean = false,
        compactFields: Boolean = false
    ) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "npv10-overlap-${System.nanoTime()}.bin")
        val decodedBytes = width * height * 3
        val packedBytes = Npv10PacketCodecEngine.packedP010Bytes(decodedBytes.toLong()).toInt()
        val packetCodec = Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        val capacity = Npv10PacketCodecEngine.storedBytes(decodedBytes.toLong(), packetCodec).toInt()
        val maximumRecordBytes = FRAME_HEADER_BYTES + capacity
        // Fits the two outstanding worst-case packets plus compressed history, but not 12 raw packets.
        val reservedBytes = DATA_START + (if (variedSamples) 9L else 3L) * maximumRecordBytes
        val encoding = Npv10Niv99ColorEncoding(Npv10Niv99ColorMatrix.BT709, Npv10Niv99Transfer.BT709)
        val expected = ArrayList<ByteArray>()
        val sources = (0 until FRAME_COUNT).map { frame ->
            ByteBuffer.allocateDirect(decodedBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(decodedBytes / 2) { sample ->
                    val code = if (compactFields) {
                        // Alternate independently valued neighbours through the new compact
                        // 3/5/6/7-bit fields, all SIMD merges, both chroma lanes and chunk tails.
                        // A plain shift/OR instead of low-field selection corrupts these values.
                        val bits = when ((sample / 64 + frame) and 3) {
                            0 -> 3
                            1 -> 5
                            2 -> 6
                            else -> 7
                        }
                        val mask = (1 shl bits) - 1
                        val pair = sample / 64 + frame * 977
                        val residual = if ((sample / 2) and 1 == 0) pair and mask
                            else (pair ushr bits) and mask
                        1023 - mask + residual
                    } else if (wordPairCodes) {
                        // Across each 16,384-code span, walk all 1,024 codes through one
                        // of the sixteen positions gathered by the native word-pair loader.
                        // Other positions alternate endpoints so incorrect byte/lane joins
                        // cannot hide behind equal neighbours. Frame offsets cover both
                        // chunk boundaries and reverse-completion ownership generations.
                        val position = (sample / (16 * 1024) + frame) % 16
                        if (sample % 16 == position) (sample / 16) % 1024
                        else if (sample and 1 == 0) 0 else 1023
                    } else if (variedSamples) {
                        // Every residual width and both chroma lanes, across a full chunk
                        // plus a tail chunk. Full-width noise exercises the raw/LZ4 escape.
                        val bits = (sample / 64 + (sample and 1) + frame) % 11
                        val mask = (1 shl bits) - 1
                        val origin = (1023 - mask) / 2
                        origin + ((sample * 37 + frame * 17) and mask)
                    } else if (sample < width * height) 128 + frame * 32 else 512
                    putShort((code shl 6).toShort())
                }
                rewind()
            }.also { source ->
                val reference = ByteBuffer.allocateDirect(packedBytes).order(ByteOrder.nativeOrder())
                assertTrue(Npv10FramePacker.processNiv99CanonicalP010ToPackedTenBitBufferWithCrc(
                    source, width, height, reference, encoding, 1f, 1f, 1f, 0f, 0f, 0f, 1f
                ) >= 0L)
                val packet = ByteArray(packedBytes).also(reference.duplicate()::get)
                expected += Npv10PacketCodecEngine.decode(
                    packet, decodedBytes, Npv10PacketCodec.PACKED_P010_10_LE_V1
                )
            }
        }
        val offsets = LongArray(FRAME_COUNT)
        val sizes = IntArray(FRAME_COUNT)
        val checksums = IntArray(FRAME_COUNT)
        try {
            RandomAccessFile(file, "rw").use { Os.posix_fallocate(it.fd, 0, reservedBytes) }
            Npv10DirectMappedCommitEngine.open(
                file = file, dataStartOffset = DATA_START, frameHeaderBytes = FRAME_HEADER_BYTES,
                payloadBytes = packedBytes, payloadCapacityBytes = capacity,
                sampleCount = decodedBytes / 2, chunkedLosslessCompression = true,
                slotCount = 4, reservedBytes = reservedBytes
            ).use { engine ->
                val workers = Executors.newFixedThreadPool(Npv10PackingOverlapPolicy.dispatchWorkers(packetCodec))
                var tail = DATA_START
                try {
                    for (first in 0 until FRAME_COUNT step 2) {
                        engine.beginFrame(first.toLong(), reservedBytes)
                        engine.beginFrame(first + 1L, reservedBytes)
                        val ready = CountDownLatch(2)
                        val secondComplete = CountDownLatch(1)
                        val futures = (first..first + 1).map { frame ->
                            workers.submit<Npv10DirectMappedPreparedPayload> {
                                ready.countDown()
                                check(ready.await(10, TimeUnit.SECONDS))
                                if (reverseCompletion && frame == first) {
                                    check(secondComplete.await(10, TimeUnit.SECONDS))
                                }
                                requireNotNull(Npv10FramePacker.processNiv99CanonicalP010ToDirectMappedTenBitWithCrc(
                                    canonicalP010 = sources[frame], width = width, height = height,
                                    directMappedCommit = engine, frameIndex = frame.toLong(), encoding = encoding,
                                    redCorrection = 1f, greenCorrection = 1f, blueCorrection = 1f,
                                    spatialDenoise = 0f, chromaDenoise = 0f, sharpeningCeiling = 0f, confidence = 1f
                                )).also { if (frame != first) secondComplete.countDown() }
                            }
                        }
                        futures.forEachIndexed { index, future ->
                            val frame = first + index
                            val prepared = future.get(15, TimeUnit.SECONDS)
                            assertTrue(prepared.encodedBytes in 1 until capacity)
                            offsets[frame] = tail + FRAME_HEADER_BYTES
                            sizes[frame] = prepared.encodedBytes
                            checksums[frame] = prepared.crc32
                            engine.commitFrame(
                                frameIndex = frame.toLong(), encodedBytes = prepared.encodedBytes,
                                payloadFileOffset = offsets[frame], reservedBytes = reservedBytes,
                                crc32 = prepared.crc32, timestampNs = timestamp(frame), width = width, height = height
                            )
                            tail = offsets[frame] + prepared.encodedBytes
                        }
                        engine.awaitCommittedFrames(first + 2L)
                    }
                    assertTrue(tail <= reservedBytes)
                    assertTrue(DATA_START + FRAME_COUNT.toLong() * maximumRecordBytes > reservedBytes)
                    val telemetry = engine.snapshot()
                    assertEquals(FRAME_COUNT.toLong(), telemetry.asynchronouslyPersistedFrames)
                    assertEquals(0L, telemetry.asynchronousRecordWriteFailures)
                    assertEquals(0L, telemetry.pendingRecordQueueDepth)
                    assertEquals(0L, telemetry.mappingFailures)
                    assertEquals(0L, telemetry.commitFailures)
                } finally {
                    workers.shutdownNow()
                    assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS))
                }
            }
            RandomAccessFile(file, "r").use { input ->
                repeat(FRAME_COUNT) { frame ->
                    input.seek(offsets[frame] - FRAME_HEADER_BYTES)
                    assertEquals(0x46524D30, input.readInt())
                    assertEquals(timestamp(frame), input.readLong())
                    assertEquals(width, input.readInt())
                    assertEquals(height, input.readInt())
                    assertEquals(sizes[frame], input.readInt())
                    assertEquals(checksums[frame], input.readInt())
                    val packet = ByteArray(sizes[frame]).also(input::readFully)
                    assertEquals("Native DMC must publish the versioned exact-range envelope", 2,
                        ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN).getInt(4))
                    assertEquals(checksums[frame].toLong() and 0xffff_ffffL, CRC32().apply { update(packet) }.value)
                    assertArrayEquals("frame=$frame", expected[frame], Npv10PacketCodecEngine.decode(packet, decodedBytes, packetCodec))
                    assertArrayEquals("independent Kotlin range decoder frame=$frame", expected[frame],
                        Npv10ChunkedLosslessCodec.decodeP010(packet, decodedBytes))
                }
            }
        } finally {
            assertTrue(!file.exists() || file.delete())
        }
    }

    private fun timestamp(frame: Int): Long = 1_000_000L + frame * 40_000_000L

    private companion object {
        const val WIDTH = 256
        const val HEIGHT = 128
        const val FRAME_COUNT = 12
        const val DATA_START = 4096L
        const val FRAME_HEADER_BYTES = 28
    }
}
