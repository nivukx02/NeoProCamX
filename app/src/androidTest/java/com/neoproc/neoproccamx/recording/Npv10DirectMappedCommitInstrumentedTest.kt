package com.neoproc.neoproccamx.recording

import android.os.Build
import android.os.SystemClock
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class Npv10DirectMappedCommitInstrumentedTest {
    @Test
    fun olderAdmissionSnapshotRemainsValidAfterAProvenReservationExtension() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "npv10-dmc-reservation-race-${System.nanoTime()}.bin")
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            WIDTH, HEIGHT, Npv10PayloadFormat.P010_LE
        )
        val packedBytes = Npv10PacketCodecEngine.packedP010Bytes(decodedBytes).toInt()
        val recordBytes = FRAME_HEADER_BYTES.toLong() + packedBytes
        val originalReservation = DATA_START + recordBytes * SLOT_COUNT
        val grownReservation = originalReservation + recordBytes * SLOT_COUNT
        try {
            RandomAccessFile(file, "rw").use { output ->
                Os.posix_fallocate(output.fd, 0L, originalReservation)
            }
            Npv10DirectMappedCommitEngine.open(
                file = file,
                dataStartOffset = DATA_START,
                frameHeaderBytes = FRAME_HEADER_BYTES,
                payloadBytes = packedBytes,
                slotCount = SLOT_COUNT,
                reservedBytes = originalReservation
            ).use { engine ->
                engine.allocatePhysicalRange(originalReservation, grownReservation - originalReservation)
                // Two packing lanes may enter in this order after the maintenance lane extends
                // storage. Both frames fit their OWN snapshots and are independently fstat-proven.
                engine.beginFrame(frameIndex = 1L, reservedBytes = grownReservation)
                engine.beginFrame(frameIndex = 0L, reservedBytes = originalReservation)
                assertEquals(2L, engine.snapshot().framesMapped)
                assertEquals(0L, engine.snapshot().mappingFailures)
                engine.abortFrame(0L)
                engine.abortFrame(1L)
                engine.beginFrame(frameIndex = 0L, reservedBytes = originalReservation)
                engine.abortFrame(0L)
                assertEquals(3L, engine.snapshot().framesAborted)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun concurrentGrowthCannotLendUnprovenCapacityToAnAdmissionSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "npv10-dmc-reservation-proof-${System.nanoTime()}.bin")
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            WIDTH, HEIGHT, Npv10PayloadFormat.P010_LE
        )
        val packedBytes = Npv10PacketCodecEngine.packedP010Bytes(decodedBytes).toInt()
        val recordBytes = FRAME_HEADER_BYTES.toLong() + packedBytes
        val physicalReservation = DATA_START + recordBytes * SLOT_COUNT
        try {
            RandomAccessFile(file, "rw").use { output ->
                Os.posix_fallocate(output.fd, 0L, physicalReservation)
            }
            Npv10DirectMappedCommitEngine.open(
                file = file,
                dataStartOffset = DATA_START,
                frameHeaderBytes = FRAME_HEADER_BYTES,
                payloadBytes = packedBytes,
                slotCount = SLOT_COUNT,
                reservedBytes = physicalReservation
            ).use { engine ->
                val smallerThanThisFrame = DATA_START + recordBytes
                val insufficientSnapshot = runCatching {
                    engine.beginFrame(frameIndex = 1L, reservedBytes = smallerThanThisFrame)
                }.exceptionOrNull()
                assertTrue(insufficientSnapshot is java.io.IOException)
                val largerThanPhysicalFile = physicalReservation + recordBytes * SLOT_COUNT
                val unprovenExtension = runCatching {
                    engine.beginFrame(frameIndex = SLOT_COUNT.toLong(), reservedBytes = largerThanPhysicalFile)
                }.exceptionOrNull()
                assertTrue(unprovenExtension is java.io.IOException)
                assertEquals(0L, engine.snapshot().framesMapped)
                assertEquals(2L, engine.snapshot().mappingFailures)
                // Failed admission must not occupy or poison a valid slot.
                engine.beginFrame(frameIndex = 0L, reservedBytes = physicalReservation)
                engine.abortFrame(0L)
                assertEquals(1L, engine.snapshot().framesAborted)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun chunkedLosslessDirectMappedPackets_areBitExactAtSequentialVariableOffsets() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "npv10-dmc-nlc2-${System.nanoTime()}.bin")
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            WIDTH,
            HEIGHT,
            Npv10PayloadFormat.P010_LE
        ).toInt()
        val packedBytes = Npv10PacketCodecEngine.packedP010Bytes(decodedBytes.toLong()).toInt()
        val packetCapacity = Npv10PacketCodecEngine.storedBytes(
            decodedBytes.toLong(),
            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        ).toInt()
        val frameCount = 2
        val reservedBytes = DATA_START +
            (FRAME_HEADER_BYTES + packetCapacity).toLong() * frameCount
        val canonical = syntheticCanonicalP010(decodedBytes)
        val referencePacked = ByteBuffer.allocateDirect(packedBytes).order(ByteOrder.nativeOrder())
        val encoding = Npv10Niv99ColorEncoding(
            matrix = Npv10Niv99ColorMatrix.BT709,
            transfer = Npv10Niv99Transfer.BT709
        )

        try {
            RandomAccessFile(file, "rw").use { output ->
                Os.posix_fallocate(output.fd, 0L, reservedBytes)
            }
            val referenceCrc = Npv10FramePacker.processNiv99CanonicalP010ToPackedTenBitBufferWithCrc(
                canonicalP010 = canonical,
                width = WIDTH,
                height = HEIGHT,
                target = referencePacked,
                encoding = encoding,
                redCorrection = 1.0f,
                greenCorrection = 1.0f,
                blueCorrection = 1.0f,
                spatialDenoise = 0.0f,
                chromaDenoise = 0.0f,
                sharpeningCeiling = 0.0f,
                confidence = 1.0f
            )
            assertTrue(referenceCrc >= 0L)
            val referencePacket = ByteArray(packedBytes).also(referencePacked.duplicate()::get)
            val expectedP010 = Npv10PacketCodecEngine.decode(
                referencePacket,
                decodedBytes,
                Npv10PacketCodec.PACKED_P010_10_LE_V1
            )

            val offsets = ArrayList<Long>(frameCount)
            val sizes = ArrayList<Int>(frameCount)
            Npv10DirectMappedCommitEngine.open(
                file = file,
                dataStartOffset = DATA_START,
                frameHeaderBytes = FRAME_HEADER_BYTES,
                payloadBytes = packedBytes,
                payloadCapacityBytes = packetCapacity,
                sampleCount = decodedBytes / Short.SIZE_BYTES,
                chunkedLosslessCompression = true,
                slotCount = SLOT_COUNT,
                reservedBytes = reservedBytes
            ).use { engine ->
                var recordStart = DATA_START
                repeat(frameCount) { frame ->
                    val frameIndex = frame.toLong()
                    val timestampNs = 1_000_000L + frameIndex
                    engine.beginFrame(frameIndex, reservedBytes)
                    val prepared = requireNotNull(
                        Npv10FramePacker.processNiv99CanonicalP010ToDirectMappedTenBitWithCrc(
                            canonicalP010 = canonical,
                            width = WIDTH,
                            height = HEIGHT,
                            directMappedCommit = engine,
                            frameIndex = frameIndex,
                            encoding = encoding,
                            redCorrection = 1.0f,
                            greenCorrection = 1.0f,
                            blueCorrection = 1.0f,
                            spatialDenoise = 0.0f,
                            chromaDenoise = 0.0f,
                            sharpeningCeiling = 0.0f,
                            confidence = 1.0f
                        )
                    )
                    // Production now uses bounded upstream LZ4 with a bit-exact raw fallback.
                    // Packet sizes may vary, but may never exceed the unchanged raw envelope.
                    assertTrue(prepared.encodedBytes in 1..packetCapacity)
                    if (frame == 0) {
                        // A future packing admission can prove an extended prefix while this
                        // earlier commit still owns its valid pre-extension reservation snapshot.
                        val grownReservation = reservedBytes +
                            (FRAME_HEADER_BYTES + packetCapacity).toLong() * SLOT_COUNT
                        RandomAccessFile(file, "rw").use { output ->
                            Os.posix_fallocate(output.fd, reservedBytes, grownReservation - reservedBytes)
                        }
                        engine.beginFrame(SLOT_COUNT - 1L, grownReservation)
                        engine.abortFrame(SLOT_COUNT - 1L)
                        val uncoveredCommit = runCatching {
                            engine.commitFrame(
                                frameIndex = frameIndex, encodedBytes = prepared.encodedBytes,
                                payloadFileOffset = recordStart + FRAME_HEADER_BYTES,
                                reservedBytes = recordStart + FRAME_HEADER_BYTES + prepared.encodedBytes - 1L,
                                crc32 = prepared.crc32, timestampNs = timestampNs,
                                width = WIDTH, height = HEIGHT
                            )
                        }.exceptionOrNull()
                        assertTrue("A newer reservation cannot lend bytes to an invalid snapshot",
                            uncoveredCommit is java.io.IOException)
                    }
                    val payloadOffset = recordStart + FRAME_HEADER_BYTES
                    offsets += payloadOffset
                    sizes += prepared.encodedBytes
                    engine.commitFrame(
                        frameIndex = frameIndex,
                        encodedBytes = prepared.encodedBytes,
                        payloadFileOffset = payloadOffset,
                        reservedBytes = reservedBytes,
                        crc32 = prepared.crc32,
                        timestampNs = timestampNs,
                        width = WIDTH,
                        height = HEIGHT
                    )
                    recordStart = payloadOffset + prepared.encodedBytes
                }
                engine.awaitCommittedFrames(frameCount.toLong())
                val telemetry = engine.snapshot()
                assertEquals(frameCount.toLong(), telemetry.framesCommitted)
                assertEquals(frameCount.toLong(), telemetry.asynchronouslySubmittedFrames)
                assertEquals(frameCount.toLong(), telemetry.asynchronouslyPersistedFrames)
                assertEquals(0L, telemetry.asynchronousRecordWriteFailures)
                assertEquals(0L, telemetry.pendingRecordQueueDepth)
                assertEquals(sizes.sumOf(Int::toLong), telemetry.directMappedPayloadBytes)
            }

            RandomAccessFile(file, "r").use { input ->
                repeat(frameCount) { frame ->
                    val frameHeader = ByteArray(FRAME_HEADER_BYTES)
                    input.seek(offsets[frame] - FRAME_HEADER_BYTES)
                    input.readFully(frameHeader)
                    assertNpv10FrameHeader(
                        frameHeader,
                        timestampNs = 1_000_000L + frame,
                        payloadBytes = sizes[frame]
                    )
                    val packet = ByteArray(sizes[frame])
                    input.seek(offsets[frame])
                    input.readFully(packet)
                    assertEquals("Stored frame CRC differs from its actual packet",
                        ByteBuffer.wrap(frameHeader).order(ByteOrder.BIG_ENDIAN)
                            .getInt(FRAME_HEADER_BYTES - Int.SIZE_BYTES).toLong() and 0xffff_ffffL,
                        CRC32().apply { update(packet) }.value)
                    assertAllNlc2ChunksAreBounded(packet, decodedBytes / Short.SIZE_BYTES)
                    assertArrayEquals(
                        "frame=$frame",
                        expectedP010,
                        Npv10PacketCodecEngine.decode(
                            packet,
                            decodedBytes,
                            Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
                        )
                    )
                    assertArrayEquals("Independent wire decoder frame=$frame", expectedP010,
                        Npv10ChunkedLosslessCodec.decodeP010(packet, decodedBytes))
                }
            }
        } finally {
            assertTrue(!file.exists() || file.delete())
        }
    }

    private fun assertNpv10FrameHeader(
        bytes: ByteArray,
        timestampNs: Long,
        payloadBytes: Int
    ) {
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x46524D30, header.int)
        assertEquals(timestampNs, header.long)
        assertEquals(WIDTH, header.int)
        assertEquals(HEIGHT, header.int)
        assertEquals(payloadBytes, header.int)
        header.int // payload CRC is validated by the NLC2 decode below.
        assertEquals(FRAME_HEADER_BYTES, header.position())
    }

    @Test
    fun legacyAndRangeWireVersionsKeepExplicitBoundsAndIndependentDecoding() {
        val samples = 512
        val source = ByteBuffer.allocate(samples * Short.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .apply { repeat(samples) { putShort(((it % 17 + 256) shl 6).toShort()) } }.array()
        for (version in 1..2) {
            val packet = Npv10ChunkedLosslessCodec.encodeP010(source, rangePacking = version == 2)
            assertAllNlc2ChunksAreBounded(packet, samples, expectedVersion = version)
            assertArrayEquals(source, Npv10ChunkedLosslessCodec.decodeP010(packet, source.size))
            val unknownVersion = packet.copyOf().also {
                ByteBuffer.wrap(it).order(ByteOrder.BIG_ENDIAN).putInt(4, 3)
            }
            assertTrue("Unknown container version must be rejected",
                runCatching { Npv10ChunkedLosslessCodec.decodeP010(unknownVersion, source.size) }.isFailure)
        }
    }

    private fun assertAllNlc2ChunksAreBounded(
        packet: ByteArray,
        sampleCount: Int,
        expectedVersion: Int = 2
    ) {
        val header = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0x4E4C4332, header.int)
        // Current native range encoding is version 2. Legacy version-1 files are tested
        // explicitly above; accepting an arbitrary version would weaken this oracle.
        assertEquals(expectedVersion, header.int)
        assertEquals(((sampleCount.toLong() * 10L + 7L) / 8L).toInt(), header.int)
        assertEquals(sampleCount * Short.SIZE_BYTES, header.int)
        assertEquals(sampleCount, header.int)
        assertEquals(Npv10ChunkedLosslessCodec.CHUNK_SAMPLES, header.int)
        val chunkCount = header.int
        assertEquals((sampleCount + Npv10ChunkedLosslessCodec.CHUNK_SAMPLES - 1) /
            Npv10ChunkedLosslessCodec.CHUNK_SAMPLES, chunkCount)
        header.int // packed payload CRC
        var packetOffset = Npv10ChunkedLosslessCodec.FRAME_HEADER_BYTES
        var remainingSamples = sampleCount
        repeat(chunkCount) {
            assertTrue(packetOffset <= packet.size - Npv10ChunkedLosslessCodec.CHUNK_HEADER_BYTES)
            val flags = header.getInt(packetOffset)
            assertTrue("unknown NLC2 chunk encoding $flags for version $expectedVersion",
                flags == 0 || flags == 1 || (expectedVersion == 2 && flags == 2))
            val samples = header.getInt(packetOffset + Int.SIZE_BYTES)
            val storedBytes = header.getInt(packetOffset + Int.SIZE_BYTES * 2)
            assertEquals(minOf(Npv10ChunkedLosslessCodec.CHUNK_SAMPLES, remainingSamples), samples)
            val rawBytes = ((samples.toLong() * 10L + 7L) / 8L).toInt()
            if (flags == 0) assertEquals(rawBytes, storedBytes)
            else assertTrue("Lossless LZ4/range chunks must save bytes without changing samples",
                storedBytes in 1 until rawBytes)
            assertTrue(storedBytes <= packet.size - packetOffset - Npv10ChunkedLosslessCodec.CHUNK_HEADER_BYTES)
            packetOffset += Npv10ChunkedLosslessCodec.CHUNK_HEADER_BYTES + storedBytes
            remainingSamples -= samples
        }
        assertEquals(0, remainingSamples)
        assertEquals(packet.size, packetOffset)
    }

    @Test
    fun directMappedNiv99Packet_isBitExactWithNativeBufferPath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "npv10-dmc-parity-${System.nanoTime()}.bin")
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            WIDTH,
            HEIGHT,
            Npv10PayloadFormat.P010_LE
        ).toInt()
        val packedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes.toLong(),
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        ).toInt()
        val recordBytes = FRAME_HEADER_BYTES + packedBytes
        val reservedBytes = DATA_START + recordBytes.toLong() * SLOT_COUNT
        val canonical = syntheticCanonicalP010(decodedBytes)
        val reference = ByteBuffer.allocateDirect(packedBytes).order(ByteOrder.nativeOrder())
        val encoding = Npv10Niv99ColorEncoding(
            matrix = Npv10Niv99ColorMatrix.BT709,
            transfer = Npv10Niv99Transfer.BT709
        )

        try {
            RandomAccessFile(file, "rw").use { output ->
                Os.posix_fallocate(output.fd, 0L, reservedBytes)
            }
            val expectedCrc = Npv10FramePacker.processNiv99CanonicalP010ToPackedTenBitBufferWithCrc(
                canonicalP010 = canonical,
                width = WIDTH,
                height = HEIGHT,
                target = reference,
                encoding = encoding,
                redCorrection = 1.07f,
                greenCorrection = 1.0f,
                blueCorrection = 0.94f,
                spatialDenoise = 0.42f,
                chromaDenoise = 0.63f,
                sharpeningCeiling = 0.31f,
                confidence = 0.91f
            )
            assertTrue(expectedCrc >= 0L)

            Npv10DirectMappedCommitEngine.open(
                file = file,
                dataStartOffset = DATA_START,
                frameHeaderBytes = FRAME_HEADER_BYTES,
                payloadBytes = packedBytes,
                slotCount = SLOT_COUNT,
                reservedBytes = reservedBytes
            ).use { engine ->
                engine.beginFrame(frameIndex = 0L, reservedBytes = reservedBytes)
                val directResult = requireNotNull(
                    Npv10FramePacker.processNiv99CanonicalP010ToDirectMappedTenBitWithCrc(
                    canonicalP010 = canonical,
                    width = WIDTH,
                    height = HEIGHT,
                    directMappedCommit = engine,
                    frameIndex = 0L,
                    encoding = encoding,
                    redCorrection = 1.07f,
                    greenCorrection = 1.0f,
                    blueCorrection = 0.94f,
                    spatialDenoise = 0.42f,
                    chromaDenoise = 0.63f,
                    sharpeningCeiling = 0.31f,
                    confidence = 0.91f
                    )
                )
                assertEquals(expectedCrc, directResult.crc32.toLong() and 0xffff_ffffL)
                assertEquals(packedBytes, directResult.encodedBytes)
                engine.commitFrame(0L, directResult.crc32)
                engine.awaitCommittedFrames(1L)
                val telemetry = engine.snapshot()
                assertEquals(1L, telemetry.framesMapped)
                assertEquals(1L, telemetry.framesCommitted)
                assertEquals(packedBytes.toLong(), telemetry.directMappedPayloadBytes)
            }

            val expected = ByteArray(packedBytes).also { bytes ->
                reference.position(0)
                reference.get(bytes)
            }
            val actual = ByteArray(packedBytes)
            RandomAccessFile(file, "r").use { input ->
                input.seek(DATA_START + FRAME_HEADER_BYTES)
                input.readFully(actual)
            }
            assertArrayEquals(expected, actual)
        } finally {
            assertTrue(!file.exists() || file.delete())
        }
    }

    @Test
    fun abortedSlot_isRetiredAndWarmBufferIsReusableWithoutGrowingTheRing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "npv10-dmc-lifecycle-${System.nanoTime()}.bin")
        val payloadBytes = 4096
        val reservedBytes = DATA_START + (FRAME_HEADER_BYTES + payloadBytes).toLong() * 32L
        try {
            RandomAccessFile(file, "rw").use { output ->
                Os.posix_fallocate(output.fd, 0L, reservedBytes)
            }
            Npv10DirectMappedCommitEngine.open(
                file = file,
                dataStartOffset = DATA_START,
                frameHeaderBytes = FRAME_HEADER_BYTES,
                payloadBytes = payloadBytes,
                slotCount = SLOT_COUNT,
                reservedBytes = reservedBytes
            ).use { engine ->
                repeat(32) { cycle ->
                    val frameIndex = cycle.toLong()
                    engine.beginFrame(frameIndex, reservedBytes)
                    engine.abortFrame(frameIndex)
                    engine.abortFrame(frameIndex)
                }
                val telemetry = engine.snapshot()
                assertEquals(32L, telemetry.framesMapped)
                assertEquals(64L, telemetry.framesAborted)
                assertEquals(SLOT_COUNT, telemetry.slotCount)
                assertEquals(0L, telemetry.mappingFailures)
            }
        } finally {
            assertTrue(!file.exists() || file.delete())
        }
    }

    @Test
    fun highThroughputPayload_suppressesOptionalWritebackWithoutDisablingDmc() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "npv10-dmc-realtime-${System.nanoTime()}.bin")
        val payloadBytes = 12 * 1024 * 1024
        val reservedBytes = DATA_START + FRAME_HEADER_BYTES + payloadBytes.toLong()
        try {
            RandomAccessFile(file, "rw").use { output ->
                Os.posix_fallocate(output.fd, 0L, reservedBytes)
            }
            Npv10DirectMappedCommitEngine.open(
                file = file,
                dataStartOffset = DATA_START,
                frameHeaderBytes = FRAME_HEADER_BYTES,
                payloadBytes = payloadBytes,
                slotCount = 1,
                reservedBytes = reservedBytes
            ).use { engine ->
                val telemetry = engine.snapshot()
                assertTrue(telemetry.enabled)
                assertTrue(!telemetry.incrementalWritebackEnabled)
                assertTrue(telemetry.incrementalWritebackSuppressedForRealtime)
                assertEquals(0L, telemetry.incrementalWritebackAttempts)
            }
        } finally {
            assertTrue(!file.exists() || file.delete())
        }
    }

    @Test
    fun committedSlots_useWarmBoundedStagingAndRemainBitExactAcrossReuse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.cacheDir, "npv10-dmc-primer-${System.nanoTime()}.bin")
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            WIDTH,
            HEIGHT,
            Npv10PayloadFormat.P010_LE
        ).toInt()
        val packedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes.toLong(),
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        ).toInt()
        val frameCount = SLOT_COUNT * 3
        val recordBytes = FRAME_HEADER_BYTES + packedBytes
        val reservedBytes = DATA_START + recordBytes.toLong() * frameCount
        val canonical = syntheticCanonicalP010(decodedBytes)
        val reference = ByteBuffer.allocateDirect(packedBytes).order(ByteOrder.nativeOrder())
        val encoding = Npv10Niv99ColorEncoding(
            matrix = Npv10Niv99ColorMatrix.BT709,
            transfer = Npv10Niv99Transfer.BT709
        )

        try {
            RandomAccessFile(file, "rw").use { output ->
                Os.posix_fallocate(output.fd, 0L, reservedBytes)
            }
            val expectedCrc = Npv10FramePacker.processNiv99CanonicalP010ToPackedTenBitBufferWithCrc(
                canonicalP010 = canonical,
                width = WIDTH,
                height = HEIGHT,
                target = reference,
                encoding = encoding,
                redCorrection = 1.03f,
                greenCorrection = 1.0f,
                blueCorrection = 0.97f,
                spatialDenoise = 0.38f,
                chromaDenoise = 0.61f,
                sharpeningCeiling = 0.28f,
                confidence = 0.88f
            )
            assertTrue(expectedCrc >= 0L)

            Npv10DirectMappedCommitEngine.open(
                file = file,
                dataStartOffset = DATA_START,
                frameHeaderBytes = FRAME_HEADER_BYTES,
                payloadBytes = packedBytes,
                slotCount = SLOT_COUNT,
                reservedBytes = reservedBytes
            ).use { engine ->
                repeat(frameCount) { frame ->
                    val frameIndex = frame.toLong()
                    engine.beginFrame(frameIndex, reservedBytes)
                    val directResult = requireNotNull(
                        Npv10FramePacker.processNiv99CanonicalP010ToDirectMappedTenBitWithCrc(
                        canonicalP010 = canonical,
                        width = WIDTH,
                        height = HEIGHT,
                        directMappedCommit = engine,
                        frameIndex = frameIndex,
                        encoding = encoding,
                        redCorrection = 1.03f,
                        greenCorrection = 1.0f,
                        blueCorrection = 0.97f,
                        spatialDenoise = 0.38f,
                        chromaDenoise = 0.61f,
                        sharpeningCeiling = 0.28f,
                        confidence = 0.88f
                        )
                    )
                    assertEquals(expectedCrc, directResult.crc32.toLong() and 0xffff_ffffL)
                    assertEquals(packedBytes, directResult.encodedBytes)
                    engine.commitFrame(frameIndex, directResult.crc32)
                }
                engine.awaitCommittedFrames(frameCount.toLong())
                val writebackDeadlineMs = SystemClock.elapsedRealtime() + 5_000L
                var telemetry = engine.snapshot()
                while (telemetry.incrementalWritebackAttempts < 1L &&
                    SystemClock.elapsedRealtime() < writebackDeadlineMs
                ) {
                    SystemClock.sleep(10L)
                    telemetry = engine.snapshot()
                }
                assertEquals(frameCount.toLong(), telemetry.framesMapped)
                assertEquals(frameCount.toLong(), telemetry.framesCommitted)
                assertEquals(0L, telemetry.mappingFailures)
                assertEquals(0L, telemetry.commitFailures)
                assertTrue(telemetry.asynchronousRecordWriterEnabled)
                assertEquals(frameCount.toLong(), telemetry.asynchronouslySubmittedFrames)
                assertEquals(frameCount.toLong(), telemetry.asynchronouslyPersistedFrames)
                assertEquals(0L, telemetry.asynchronousRecordWriteFailures)
                assertEquals(0L, telemetry.pendingRecordQueueDepth)
                assertTrue(telemetry.incrementalWritebackEnabled)
                assertTrue(telemetry.incrementalWritebackAttempts >= 1L)
                assertEquals(
                    telemetry.incrementalWritebackAttempts,
                    telemetry.incrementalWritebackSuccesses
                )
                assertEquals(0L, telemetry.incrementalWritebackFailures)
                assertTrue(telemetry.incrementalWritebackBytes > 0L)
                assertTrue(telemetry.incrementalWritebackCoveredThroughOffset > DATA_START)
                assertEquals(0, telemetry.incrementalWritebackLastErrno)
            }

            val expected = ByteArray(packedBytes).also { bytes ->
                reference.position(0)
                reference.get(bytes)
            }
            RandomAccessFile(file, "r").use { input ->
                repeat(frameCount) { frame ->
                    val actual = ByteArray(packedBytes)
                    input.seek(DATA_START + frame.toLong() * recordBytes + FRAME_HEADER_BYTES)
                    input.readFully(actual)
                    assertArrayEquals("frame=$frame", expected, actual)
                }
            }
        } finally {
            assertTrue(!file.exists() || file.delete())
        }
    }

    private fun syntheticCanonicalP010(decodedBytes: Int): ByteBuffer {
        val output = ByteBuffer.allocateDirect(decodedBytes).order(ByteOrder.LITTLE_ENDIAN)
        val ySamples = WIDTH * HEIGHT
        repeat(ySamples) { index ->
            val code = 64 + (index * 13 + index / WIDTH * 7) % 877
            output.putShort((code shl 6).toShort())
        }
        repeat((decodedBytes - ySamples * Short.SIZE_BYTES) / Short.SIZE_BYTES) { index ->
            val code = 64 + (index * 17 + 191) % 877
            output.putShort((code shl 6).toShort())
        }
        output.flip()
        return output
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 32
        const val FRAME_HEADER_BYTES = 28
        const val SLOT_COUNT = 4
        const val DATA_START = 4096L
    }
}
