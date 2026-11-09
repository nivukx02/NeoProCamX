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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Real JNI/filesystem integration. Synthetic pixels validate bytes, not camera quality or FPS. */
@RunWith(AndroidJUnit4::class)
class Npv10AlignedCommitInstrumentedTest {
    @Test
    fun largeLosslessRecords_preservePrefixFramesAndDurabilityAcrossSlotReuse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "npv10-aligned-integration-${System.nanoTime()}.bin")
        val width = 1920
        val height = 1080
        val frames = 12
        val prefix = ByteArray(13_654) { ((it * 31 + it / 97) and 255).toByte() }
        val decodedBytes = width * height * 3
        val packedBytes = Npv10PacketCodecEngine.packedP010Bytes(decodedBytes.toLong()).toInt()
        val packetCapacity = Npv10PacketCodecEngine.storedBytes(
            decodedBytes.toLong(), Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
        ).toInt()
        val reservedBytes = 128L * 1024L * 1024L
        val offsets = ArrayList<Long>()
        val sizes = ArrayList<Int>()
        val expectedHashes = ArrayList<ByteArray>()
        val concurrentForces = ArrayList<CompletableFuture<Void>>()
        val encoding = Npv10Niv99ColorEncoding(
            matrix = Npv10Niv99ColorMatrix.BT709,
            transfer = Npv10Niv99Transfer.BT709
        )
        var logicalEnd = prefix.size.toLong()
        try {
            RandomAccessFile(file, "rw").use { output ->
                Os.posix_fallocate(output.fd, 0L, reservedBytes)
                output.write(prefix)
                output.fd.sync()
                Npv10DirectMappedCommitEngine.open(
                    file = file,
                    dataStartOffset = prefix.size.toLong(),
                    frameHeaderBytes = 28,
                    payloadBytes = packedBytes,
                    payloadCapacityBytes = packetCapacity,
                    sampleCount = decodedBytes / 2,
                    chunkedLosslessCompression = true,
                    slotCount = 4,
                    reservedBytes = reservedBytes
                ).use { engine ->
                    val admission = engine.snapshot()
                    assertTrue("Aligned backend not attempted", admission.alignedRecordWriterAttempted)
                    assertTrue(
                        "Aligned device capability rejected: errno=${admission.alignedRecordWriterAdmissionErrno}",
                        admission.alignedRecordWriterEnabled
                    )
                    assertTrue(admission.alignedRecordWriterProbeVerified)
                    assertEquals(
                        (if (admission.kernelAio.enabled) 8L else 2L) * 1024L * 1024L,
                        admission.alignedRecordWriterStagingBytes
                    )
                    assertTrue(!admission.incrementalWritebackEnabled)
                    repeat(frames) { frame ->
                        val canonical = ByteBuffer.allocateDirect(decodedBytes).order(ByteOrder.LITTLE_ENDIAN)
                        var state = 0x4127531 + frame * 719
                        repeat(decodedBytes / 2) { sample ->
                            state = state xor (state shl 13)
                            state = state xor (state ushr 17)
                            state = state xor (state shl 5)
                            val code = if (frame % 2 == 0) 512 + (sample / 2048 + frame) % 32
                                else 64 + (state and Int.MAX_VALUE) % 877
                            canonical.putShort((code shl 6).toShort())
                        }
                        canonical.flip()
                        val reference = ByteBuffer.allocateDirect(packedBytes).order(ByteOrder.nativeOrder())
                        val referenceCrc = Npv10FramePacker.processNiv99CanonicalP010ToPackedTenBitBufferWithCrc(
                            canonicalP010 = canonical, width = width, height = height, target = reference,
                            encoding = encoding, redCorrection = 1.0f, greenCorrection = 1.0f,
                            blueCorrection = 1.0f, spatialDenoise = 0.0f, chromaDenoise = 0.0f,
                            sharpeningCeiling = 0.0f, confidence = 1.0f
                        )
                        assertTrue(referenceCrc >= 0L)
                        val referencePacket = ByteArray(packedBytes).also(reference.duplicate()::get)
                        val referenceP010 = Npv10PacketCodecEngine.decode(
                            referencePacket, decodedBytes, Npv10PacketCodec.PACKED_P010_10_LE_V1
                        )
                        expectedHashes += MessageDigest.getInstance("SHA-256").digest(referenceP010)
                        engine.beginFrame(frame.toLong(), reservedBytes)
                        val prepared = requireNotNull(
                            Npv10FramePacker.processNiv99CanonicalP010ToDirectMappedTenBitWithCrc(
                                canonicalP010 = canonical, width = width, height = height,
                                directMappedCommit = engine, frameIndex = frame.toLong(),
                                encoding = encoding, redCorrection = 1.0f, greenCorrection = 1.0f,
                                blueCorrection = 1.0f, spatialDenoise = 0.0f, chromaDenoise = 0.0f,
                                sharpeningCeiling = 0.0f, confidence = 1.0f
                            )
                        )
                        offsets += logicalEnd
                        sizes += prepared.encodedBytes
                        engine.commitFrame(
                            frameIndex = frame.toLong(), encodedBytes = prepared.encodedBytes,
                            payloadFileOffset = logicalEnd + 28L, reservedBytes = reservedBytes,
                            crc32 = prepared.crc32, timestampNs = 1_000_000_000L + frame * 40_000_000L,
                            width = width, height = height
                        )
                        logicalEnd += 28L + prepared.encodedBytes
                        if (frame == 1 || frame == 5 || frame == 9) {
                            val boundary = frame + 1L
                            concurrentForces += CompletableFuture.runAsync {
                                engine.forceCommittedFrames(boundary)
                            }
                        }
                        if (frame == 0 || frame == 7) {
                            engine.awaitCommittedFrames(frame + 1L)
                            engine.forceCommittedFrames(frame + 1L)
                            output.fd.sync()
                        }
                    }
                    concurrentForces.forEach { it.get(30L, TimeUnit.SECONDS) }
                    engine.awaitCommittedFrames(frames.toLong())
                    engine.forceCommittedFrames(frames.toLong())
                    output.fd.sync()
                    val telemetry = engine.snapshot()
                    assertEquals(frames.toLong(), telemetry.asynchronouslyPersistedFrames)
                    assertEquals(0L, telemetry.asynchronousRecordWriteFailures)
                    assertEquals(0L, telemetry.alignedRecordWriterFailures)
                    assertEquals(0L, telemetry.pendingRecordQueueDepth)
                    if (admission.kernelAio.enabled) {
                        assertTrue("Native AIO lost an accepted kernel completion",
                            telemetry.kernelAio.allSubmittedRequestsCompleted)
                        assertEquals(0, telemetry.kernelAio.admissionErrno)
                    }
                    assertTrue(telemetry.alignedRecordWriterCalls > 0L)
                    assertTrue(telemetry.alignedRecordWriterPhysicalBytes >= logicalEnd - prefix.size)
                    assertTrue(telemetry.alignedRecordWriterPaddingBytes > 0L)
                }
                output.setLength(logicalEnd)
                output.fd.sync()
            }
            assertTrue("Variable-size compression was not exercised", sizes.toSet().size > 1)
            assertEquals(logicalEnd, file.length())
            RandomAccessFile(file, "r").use { input ->
                val actualPrefix = ByteArray(prefix.size)
                input.readFully(actualPrefix)
                assertArrayEquals(prefix, actualPrefix)
                repeat(frames) { frame ->
                    input.seek(offsets[frame])
                    assertEquals(0x46524D30, input.readInt())
                    assertEquals(1_000_000_000L + frame * 40_000_000L, input.readLong())
                    assertEquals(width, input.readInt())
                    assertEquals(height, input.readInt())
                    assertEquals(sizes[frame], input.readInt())
                    val storedCrc = input.readInt().toLong() and 0xffff_ffffL
                    val packet = ByteArray(sizes[frame]).also(input::readFully)
                    assertEquals(storedCrc, java.util.zip.CRC32().apply { update(packet) }.value)
                    val decoded = Npv10PacketCodecEngine.decode(
                        packet, decodedBytes, Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
                    )
                    assertArrayEquals(
                        "frame=$frame", expectedHashes[frame], MessageDigest.getInstance("SHA-256").digest(decoded)
                    )
                }
            }
        } finally {
            assertTrue("Owned temporary fixture could not be removed", !file.exists() || file.delete())
        }
    }

    @Test
    fun durabilityRejectsUnsubmittedAndClosedBoundariesWithoutMutatingTheMaster() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("npv10-durability-bounds-", ".bin", context.cacheDir)
        val prefix = ByteArray(64) { (it * 37).toByte() }
        val reservation = 1024L * 1024L
        try {
            RandomAccessFile(file, "rw").use { output ->
                Os.posix_fallocate(output.fd, 0L, reservation)
                output.write(prefix)
                Npv10DirectMappedCommitEngine.open(
                    file = file, dataStartOffset = prefix.size.toLong(), frameHeaderBytes = 28,
                    payloadBytes = 5, sampleCount = 4, slotCount = 2, reservedBytes = reservation
                ).use { engine ->
                    val handle = engine.processingHandle()
                    engine.forceCommittedFrames(0L)
                    assertTrue(runCatching { engine.forceCommittedFrames(-1L) }
                        .exceptionOrNull() is IllegalArgumentException)
                    assertTrue(runCatching { engine.forceCommittedFrames(1L) }
                        .exceptionOrNull() is IllegalArgumentException)
                    assertTrue(!Npv10DirectMappedCommitNative.nativeForceCommittedFrames(handle, -1L))
                    assertTrue(!Npv10DirectMappedCommitNative.nativeForceCommittedFrames(handle, 1L))
                    assertEquals(0L, engine.snapshot().asynchronouslyPersistedFrames)
                    engine.close()
                    assertTrue(!Npv10DirectMappedCommitNative.nativeForceCommittedFrames(handle, 0L))
                    assertTrue(runCatching { engine.forceCommittedFrames(0L) }
                        .exceptionOrNull() is IllegalStateException)
                }
                assertEquals(reservation, output.length())
                output.seek(0L)
                val actual = ByteArray(prefix.size)
                output.readFully(actual)
                assertArrayEquals(prefix, actual)
            }
        } finally {
            assertTrue("Only the owned test file may be removed", file.delete())
        }
    }
}
