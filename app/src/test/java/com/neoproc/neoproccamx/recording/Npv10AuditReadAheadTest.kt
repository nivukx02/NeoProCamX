package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class Npv10AuditReadAheadTest {
    @Test(timeout = 10_000L)
    fun serialAndReadAheadProduceIdenticalFullAuditsForEveryPacketCodec() {
        for (codec in Npv10PacketCodec.entries) withMaster(codec) { master ->
            val serial = audit(master, false)
            val parallel = audit(master, true)
            assertEquals(codec.name, Npv10AuditStatus.PASS, serial.auditStatus)
            assertEquals(codec.name, serial, parallel)
            assertEquals(64, parallel.sessionHash.length)
        }
    }

    @Test(timeout = 10_000L)
    fun reusePreservesEveryPacketAndTimestampInOrder() = withMaster { master ->
        val readAhead = newReadAhead(master)
        readAhead.use { pipeline ->
            var frames = 0
            pipeline.forEachFrame { frame ->
                assertEquals(1_000_000_000L + frames * 40_000_000L, frame.timestampNs)
                val decoded = Npv10PacketCodecEngine.decode(
                    ByteArray(frame.storedPayloadBytes).also { frame.payload.duplicate().get(it) },
                    frame.decodedPayloadBytes, Npv10PacketCodec.PACKED_P010_10_LE_V1
                )
                assertEquals((64 + frames) shl 6,
                    ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF)
                frames++
            }
            assertEquals(FRAMES, frames)
        }
        assertFalse(readAhead.isReadWorkerAlive)
    }

    @Test(timeout = 10_000L)
    fun corruptAndTruncatedPayloadsNeverPassEitherAudit() {
        for (codec in Npv10PacketCodec.entries) for (truncate in listOf(false, true)) {
            withMaster(codec) { master ->
                RandomAccessFile(master, "rw").use { file ->
                    if (truncate) file.setLength(file.length() - 1L) else {
                        file.seek(file.length() - 1L)
                        val byte = file.read()
                        file.seek(file.length() - 1L)
                        file.write(byte xor 0x80)
                    }
                }
                for (readAhead in listOf(false, true)) {
                    val result = audit(master, readAhead)
                    assertEquals(Npv10AuditStatus.FAIL, result.auditStatus)
                    assertFalse(result.crcValidated)
                    assertEquals("", result.sessionHash)
                }
            }
        }
    }

    @Test(timeout = 10_000L)
    fun consumerFailureIsPreservedAndProducerIsJoined() = withMaster { master ->
        val expected = IllegalStateException("consumer validation rejected frame")
        val readAhead = newReadAhead(master)
        val failure = runCatching {
            readAhead.use { it.forEachFrame { throw expected } }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertFalse(readAhead.isReadWorkerAlive)
        readAhead.close()
    }

    @Test(timeout = 10_000L)
    fun closeDuringValidationCancelsConsumerWithoutLeakingProducer() = withMaster { master ->
        val readAhead = newReadAhead(master)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val consumer = Thread {
            try {
                readAhead.use { pipeline -> pipeline.forEachFrame {
                    entered.countDown()
                    check(release.await(5L, TimeUnit.SECONDS))
                } }
            } catch (caught: Throwable) { failure.set(caught) }
        }
        consumer.start()
        try {
            assertTrue(entered.await(5L, TimeUnit.SECONDS))
            readAhead.close()
            assertFalse(readAhead.isReadWorkerAlive)
        } finally { release.countDown() }
        consumer.join(5_000L)
        assertFalse(consumer.isAlive)
        assertTrue(failure.get() is CancellationException)
    }

    @Test(timeout = 10_000L)
    fun interruptedConsumerRestoresInterruptAndJoinsProducer() = withMaster { master ->
        val readAhead = newReadAhead(master)
        val result = AtomicReference<Throwable?>()
        val interruptedAfterClose = AtomicReference(false)
        val consumer = Thread {
            try {
                readAhead.use { pipeline -> pipeline.forEachFrame {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("cancelled by caller")
                } }
            } catch (failure: Throwable) { result.set(failure) }
            interruptedAfterClose.set(Thread.currentThread().isInterrupted)
        }
        consumer.start()
        consumer.join(5_000L)
        assertFalse(consumer.isAlive)
        assertNotNull(result.get())
        assertTrue(interruptedAfterClose.get())
        assertFalse(readAhead.isReadWorkerAlive)
    }

    @Test(timeout = 10_000L)
    fun singleUseAndCloseBeforeStartAreEnforced() = withMaster { master ->
        newReadAhead(master).use { pipeline ->
            pipeline.forEachFrame { }
            assertTrue(runCatching { pipeline.forEachFrame { } }.exceptionOrNull() is IllegalStateException)
        }
        val closed = newReadAhead(master)
        closed.close()
        assertTrue(runCatching { closed.forEachFrame { } }.exceptionOrNull() is IllegalStateException)
        assertFalse(closed.isReadWorkerAlive)
    }

    @Test(timeout = 10_000L)
    fun independentDecodeOverlapsButConsumerKeepsOriginalPacketOrder() = withMaster { master ->
        val firstPreparing = CountDownLatch(1)
        val secondPrepared = CountDownLatch(1)
        val prepared = ConcurrentHashMap.newKeySet<Long>()
        val storageIdentities = ConcurrentHashMap.newKeySet<Int>()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val pipeline = newPreparingReadAhead(master) { frame, decoded ->
            val concurrent = active.incrementAndGet()
            maximumActive.accumulateAndGet(concurrent, ::maxOf)
            try {
                storageIdentities.add(System.identityHashCode(decoded))
                assertEquals(12, decoded.capacity())
                decoded.clear()
                decoded.putLong(frame.timestampNs)
                if (frame.timestampNs == 1_000_000_000L) {
                    firstPreparing.countDown()
                    check(secondPrepared.await(5L, TimeUnit.SECONDS))
                } else if (frame.timestampNs == 1_040_000_000L) {
                    // The second executor thread may run first. Keep it in preparation until
                    // the first has entered, so this test proves overlap in either scheduling
                    // order instead of occasionally measuring two already-finished tasks.
                    check(firstPreparing.await(5L, TimeUnit.SECONDS))
                    secondPrepared.countDown()
                }
                prepared.add(frame.timestampNs)
            } finally { active.decrementAndGet() }
        }
        var consumed = 0
        pipeline.use { it.forEachFrame { frame ->
            assertEquals(1_000_000_000L + consumed * 40_000_000L, frame.timestampNs)
            assertTrue("Unvalidated frame reached ordered hash consumer", prepared.remove(frame.timestampNs))
            consumed++
        } }
        assertEquals(FRAMES, consumed)
        assertEquals(2, maximumActive.get())
        assertEquals(2, storageIdentities.size)
        assertTrue(prepared.isEmpty())
        assertEquals(0, active.get())
        assertFalse(pipeline.isReadWorkerAlive)
        assertTrue(pipeline.areDecodeWorkersTerminated)
    }

    @Test(timeout = 10_000L)
    fun decodeFailureReachesCallerAndNeverReachesOrderedHashConsumer() = withMaster { master ->
        val expected = IllegalArgumentException("Original sample CRC rejected")
        val pipeline = newPreparingReadAhead(master) { frame, _ ->
            if (frame.timestampNs == 1_040_000_000L) throw expected
        }
        var consumed = 0
        val failure = runCatching { pipeline.use { it.forEachFrame { consumed++ } } }.exceptionOrNull()
        assertSame(expected, failure)
        assertEquals(1, consumed)
        assertFalse(pipeline.isReadWorkerAlive)
        assertTrue(pipeline.areDecodeWorkersTerminated)
    }

    @Test(timeout = 10_000L)
    fun cancellationJoinsEveryDecoderBeforeReleasingItsSlot() = withMaster { master ->
        val entered = CountDownLatch(1)
        val suspended = CountDownLatch(1)
        val active = AtomicInteger()
        val pipeline = newPreparingReadAhead(master) { _, _ ->
            active.incrementAndGet()
            entered.countDown()
            try { suspended.await() } finally { active.decrementAndGet() }
        }
        val failure = AtomicReference<Throwable?>()
        val consumer = Thread {
            try { pipeline.use { it.forEachFrame { error("Unfinished decode was consumed") } } }
            catch (caught: Throwable) { failure.set(caught) }
        }
        consumer.start()
        try {
            assertTrue(entered.await(5L, TimeUnit.SECONDS))
            pipeline.close()
        } finally { suspended.countDown() }
        consumer.join(5_000L)
        assertFalse(consumer.isAlive)
        assertTrue(failure.get() is CancellationException)
        assertEquals(0, active.get())
        assertFalse(pipeline.isReadWorkerAlive)
        assertTrue(pipeline.areDecodeWorkersTerminated)
    }

    @Test(timeout = 10_000L)
    fun unusedDecodePipelineClosesWithoutStartingOrLeakingWorkers() = withMaster { master ->
        val pipeline = newPreparingReadAhead(master) { _, _ -> error("Closed pipeline decoded a frame") }
        pipeline.close()
        pipeline.close()
        assertTrue(pipeline.areDecodeWorkersTerminated)
        assertFalse(pipeline.isReadWorkerAlive)
        assertTrue(runCatching { pipeline.forEachFrame { } }.exceptionOrNull() is IllegalStateException)
    }

    private fun newPreparingReadAhead(
        master: File,
        prepare: (Npv10StoredFrameRecord, ByteBuffer) -> Unit
    ): Npv10AuditReadAhead {
        val reader = Npv10FileReader(master)
        return Npv10AuditReadAhead(reader,
            Npv10PacketCodecEngine.storedBytes(12L, reader.header.packetCodec).toInt(),
            decodedCapacity = 12, preparePacket = prepare)
    }

    private fun audit(master: File, readAhead: Boolean) = Npv10QualityGate.auditMasterFile(
        master, FRAMES.toLong(), FRAMES * 12L, readAheadEnabled = readAhead
    )

    private fun newReadAhead(master: File): Npv10AuditReadAhead {
        val reader = Npv10FileReader(master)
        return Npv10AuditReadAhead(reader, Npv10PacketCodecEngine.storedBytes(12L, reader.header.packetCodec).toInt())
    }

    private fun withMaster(
        codec: Npv10PacketCodec = Npv10PacketCodec.PACKED_P010_10_LE_V1,
        test: (File) -> Unit
    ) {
        val directory = Files.createTempDirectory("npv10-read-ahead").toFile()
        val master = File(directory, "master.npv10")
        try {
            val config = Npv10SessionConfig(width = 2, height = 2, fps = 25, mode = "PRO",
                colorProfile = "Rec.709", startedAtEpochMs = 1L, sourceFormat = "YCBCR_P010",
                sourceBitDepth = 10, payloadFormat = Npv10PayloadFormat.P010_LE, packetCodec = codec)
            Npv10FileWriter(master, config).use { writer ->
                repeat(FRAMES) { index ->
                    val payload = ByteBuffer.allocateDirect(12).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(6) { payload.putShort(((64 + index + it) shl 6).toShort()) }
                    payload.flip()
                    writer.writeCopiedP010Frame(Npv10CopiedP010Frame(
                        timestampNs = 1_000_000_000L + index * 40_000_000L, width = 2, height = 2,
                        payload = payload, payloadBytes = 12, copyPath = Npv10FramePacker.P010CopyPath.DIRECT_BUFFER
                    ))
                }
            }
            test(master)
        } finally { directory.deleteRecursively() }
    }

    private companion object { const val FRAMES = 37 }
}
