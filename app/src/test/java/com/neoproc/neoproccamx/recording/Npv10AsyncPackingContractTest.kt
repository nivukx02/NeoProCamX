package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Semaphore
import java.util.TreeMap

/**
 * Source and reference-model contracts for the Camera2-Image-owned asynchronous P010 packing lane.
 *
 * Android [android.media.Image] cannot be instantiated by the hermetic JVM suite. These contracts
 * therefore pin the production ownership boundaries and exercise the reorder algorithm with every
 * completion permutation. Device parity remains covered by Npv10FusedP010PackInstrumentedTest.
 */
class Npv10AsyncPackingContractTest {

    private val sourceRoot = listOf(
        File("src/main/java/com/neoproc/neoproccamx"),
        File("app/src/main/java/com/neoproc/neoproccamx")
    ).first { it.isDirectory }
    private val manager = File(sourceRoot, "recording/Npv10RecordingManager.kt").readText()
    private val writer = File(sourceRoot, "recording/Npv10FileWriter.kt").readText()
    private val fusedPackingInstrumentedTest = listOf(
        File(
            "src/androidTest/java/com/neoproc/neoproccamx/recording/" +
                "Npv10FusedP010PackInstrumentedTest.kt"
        ),
        File(
            "app/src/androidTest/java/com/neoproc/neoproccamx/recording/" +
                "Npv10FusedP010PackInstrumentedTest.kt"
        )
    ).first { it.isFile }.readText()

    @Test
    fun deviceHarnessUsesProductionReaderReserveAndNeverOverAcquiresWorkerLeases() {
        val harness = fusedPackingInstrumentedTest.section(
            "private fun measureFreshFusedFrames(",
            "private data class FreshPipelineTelemetry"
        )
        val ownership = fusedPackingInstrumentedTest.section(
            "private class TrackedImageOwnership(",
            "private inner class TimingStats"
        )
        val admission = harness.indexOf("!pendingFrameSlots.tryAcquire()")
        val acquire = harness.indexOf("reader.acquireNextImage()")
        val imageRetirement = harness
            .substringAfter("val ownership = TrackedImageOwnership(image) {")
            .substringBefore("}")

        assertTrue(fusedPackingInstrumentedTest.contains(
            "Npv10InFlightBufferPolicy.readerMaxImages(size.width, size.height)"
        ))
        assertFalse(fusedPackingInstrumentedTest.contains("P010_READER_MAX_IMAGES"))
        assertTrue(harness.contains("Semaphore(pendingFrameCapacity, true)"))
        assertFalse(fusedPackingInstrumentedTest.contains("ByteBuffer.allocateDirect"))
        assertTrue(harness.contains("allocatePrefaultedNativeBuffer(storedBytes)"))
        assertTrue(harness.contains("ConcurrentHashMap<String, Npv10NativeFrameBuffer>()"))
        assertTrue(harness.contains("workerPacketLeases.values.forEach(Npv10NativeFrameBuffer::close)"))
        assertTrue(admission >= 0)
        assertTrue(acquire > admission)
        assertFalse(harness.contains("pendingFrameSlots.acquire("))
        assertTrue(harness.contains("releasePendingFrameSlot()"))
        assertOrdered(
            imageRetirement,
            "targetImagesClosed.incrementAndGet()",
            "inFlight.decrementAndGet()",
            "releasePendingFrameSlot()"
        )
        assertEquals(1, harness.literalCount("inFlight.decrementAndGet()"))
        assertTrue(ownership.indexOf("image.close()") < ownership.indexOf("onClosed()"))
        assertTrue(fusedPackingInstrumentedTest.contains(
            "freshTelemetry.maxInFlight <= pendingFrameCapacity"
        ))
        assertTrue(fusedPackingInstrumentedTest.contains(
            "freshTelemetry.maxQueueDepth <= MAX_MEASURED_QUEUE_DEPTH"
        ))
    }

    @Test
    fun cameraCallbackPrestartsNativePackingAndBoundsLosslessOverlap() {
        val enqueue = manager.section(
            "internal fun enqueueP010ImageWithAdmission(image: Image): Npv10P010ImageAdmission",
            "private fun publishPreparedP010Commit"
        )
        val dispatch = enqueue.indexOf("p010PackingExecutor.execute")
        val prepare = enqueue.indexOf("prepareP010ImageForAsyncWrite(")
        val publish = enqueue.indexOf("publishPreparedP010Commit(requireNotNull(completion))")
        val accepted = enqueue.indexOf("captureLedger.recordAccepted(sensorTimestampNs)")

        assertTrue(manager.contains("ThreadPoolExecutor("))
        assertTrue(manager.contains("prestartAllCoreThreads()"))
        assertFalse(manager.contains("\"NPV10-DMC-Pack\""))
        assertFalse(manager.contains("dmcP010PackingExecutor"))
        assertTrue(manager.contains("const val P010_PACKING_DISPATCH_WORKERS = 1"))
        assertTrue(manager.contains("Npv10PackingOverlapPolicy.MAX_DISPATCH_WORKERS"))
        assertTrue(manager.contains(
            "p010PackingExecutor.corePoolSize = Npv10PackingOverlapPolicy.dispatchWorkers(config.packetCodec)"
        ))
        assertTrue(manager.contains("p010PackingExecutor.queue.isEmpty()"))
        assertTrue(manager.contains("p010PackingExecutor.prestartAllCoreThreads()"))
        assertTrue(manager.indexOf("Npv10PackingOverlapPolicy.dispatchWorkers(config.packetCodec)") <
            manager.indexOf("val reservation = takePreparedReservation()"))
        assertTrue(manager.contains("The native row engine already fans one frame"))
        assertTrue(Npv10InFlightBufferPolicy.MIN_PENDING_FRAMES >= 3)
        assertTrue(fusedPackingInstrumentedTest.contains("const val P010_PACKING_DISPATCH_WORKERS = 1"))
        assertTrue(fusedPackingInstrumentedTest.contains(
            "const val END_TO_END_P95_BUDGET_NS = 80_000_000L"
        ))
        assertFalse(fusedPackingInstrumentedTest.contains(
            "FRAME_PERIOD_NS * P010_PACKING_DISPATCH_WORKERS"
        ))
        assertTrue(manager.contains("Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)"))
        assertTrue(dispatch >= 0)
        assertTrue(prepare > dispatch)
        assertTrue(publish > prepare)
        assertTrue(accepted > dispatch)
        assertFalse(enqueue.substring(0, dispatch).contains("packP010ImageToPackedTenBitBuffer"))
        assertFalse(enqueue.contains("writerExecutor.execute"))
        assertEquals(1, enqueue.literalCount("captureLedger.recordAccepted(sensorTimestampNs)"))
    }

    @Test
    fun outOfOrderWorkerCompletionsAreSubmittedToWriterExactlyOnceInIngressOrder() {
        val publish = manager.section(
            "private fun publishPreparedP010Commit",
            "private fun commitPreparedP010InOrder"
        )
        val put = publish.indexOf("preparedP010Commits[completion.sequence] = completion")
        val remove = publish.indexOf("preparedP010Commits.remove(nextP010CommitSequence)")
        val advance = publish.indexOf("nextP010CommitSequence += 1L")
        val submit = publish.indexOf("writerExecutor.execute")
        val writerClaim = publish.indexOf("ready.handOffToWriter()")

        assertTrue(manager.contains("TreeMap<Long, Npv10PreparedP010Commit>()"))
        assertTrue(put >= 0)
        assertTrue(remove > put)
        assertTrue(advance > remove)
        assertTrue(submit > advance)
        assertTrue(writerClaim > submit)
        assertTrue(publish.contains("preparedP010Commits.containsKey(completion.sequence)"))
        assertTrue(publish.contains("Duplicate NPV10 prepared completion"))

        permutations(listOf(0L, 1L, 2L, 3L)).forEach { arrivals ->
            val reorder = TreeMap<Long, Long>()
            val committed = mutableListOf<Long>()
            var next = 0L
            arrivals.forEach { sequence ->
                assertEquals(null, reorder.put(sequence, sequence))
                while (true) {
                    val ready = reorder.remove(next) ?: break
                    committed += ready
                    next += 1L
                }
            }
            assertEquals(listOf(0L, 1L, 2L, 3L), committed)
            assertTrue(reorder.isEmpty())
        }
    }

    @Test
    fun publicationFailureRetiresEveryNonWriterOwnerAndPoisonsLaterWrites() {
        val completionOwner = manager.section(
            "private class Npv10PreparedP010Commit",
            "internal enum class Npv10FrameBackpressureAction"
        )
        val publish = manager.section(
            "private fun publishPreparedP010Commit",
            "private fun commitPreparedP010InOrder"
        )

        assertTrue(completionOwner.contains("AtomicInteger(OWNER_PACKING)"))
        assertTrue(completionOwner.contains("compareAndSet(OWNER_PACKING, OWNER_PUBLISHER)"))
        assertTrue(completionOwner.contains("compareAndSet(OWNER_PUBLISHER, OWNER_WRITER)"))
        assertTrue(completionOwner.contains("compareAndSet(OWNER_PACKING, OWNER_RETIRED)"))
        assertTrue(completionOwner.contains("compareAndSet(OWNER_PUBLISHER, OWNER_RETIRED)"))
        assertTrue(completionOwner.contains("compareAndSet(OWNER_WRITER, OWNER_RETIRED)"))
        assertTrue(publish.contains("try"))
        assertTrue(publish.contains("catch (failure: Throwable)"))
        assertTrue(publish.contains("poisonP010CommitPipeline(failure)"))
        assertTrue(publish.contains("readyInTransfer"))
        assertTrue(publish.contains("preparedP010Commits.clear()"))
        assertTrue(publish.contains("candidate.retireFromPublication()"))
        assertTrue(publish.contains("releasePreparedP010Resources(candidate)"))
    }

    @Test
    fun everySuccessfulOrFailedOwnershipPathRetiresItsResourcesExactlyOnce() {
        val enqueue = manager.section(
            "internal fun enqueueP010ImageWithAdmission(image: Image): Npv10P010ImageAdmission",
            "private fun publishPreparedP010Commit"
        )
        val rejectedDispatchTail = enqueue.substringAfter("} catch (t: RejectedExecutionException) {")
        val packingRejection = rejectedDispatchTail
            .substringBefore("} catch (failure: Throwable) {")
        val packingDispatchFailure = rejectedDispatchTail.substringAfter("} catch (failure: Throwable) {")
        val unexpectedWorkerFailure = enqueue
            .substringAfter("} catch (unexpected: Throwable) {")
            .substringBefore("} finally {")
        val prepare = manager.section(
            "private fun prepareP010ImageForAsyncWrite",
            "private fun validateRuntimeP010Image"
        )
        val commit = manager.section(
            "private fun commitPreparedP010InOrder",
            "private fun retireRejectedPreparedP010Commit"
        )
        val writerRejection = manager.section(
            "private fun retireRejectedPreparedP010Commit",
            "private fun retireInvalidPreparedP010Commit"
        )
        val releaseResources = manager.section(
            "private fun releasePreparedP010Resources",
            "private fun releaseUnpublishedP010Packet"
        )
        val niv99CanonicalFallback = prepare.substringAfter("if (!processed) {")

        // Direct-map success, canonical-copy fallback, and the outer failure guard each own one
        // mutually exclusive close site. Validate copy-before-close only inside the fallback branch.
        assertEquals(3, prepare.literalCount("closeImageSafely(image)"))
        assertOrdered(
            niv99CanonicalFallback,
            "copyP010ImageIntoCanonicalDirectBuffer(",
            "closeImageSafely(image)",
            "processor.processCanonicalAndPack("
        )
        assertTrue(prepare.contains("if (!imageClosed) closeImageSafely(image)"))
        assertFalse(unexpectedWorkerFailure.contains("closeImageSafely(image)"))
        assertTrue(commit.contains("completion.retireFromWriter()"))
        assertEquals(1, commit.literalCount("releasePreparedP010Resources("))
        assertTrue(commit.contains("abortDirectMappedFrame = !persistenceCommitted"))
        assertTrue(writerRejection.contains("completion.retireFromPublication()"))
        assertEquals(1, writerRejection.literalCount("releasePreparedP010Resources(completion)"))
        assertEquals(1, releaseResources.literalCount("releaseP010PacketBuffer"))
        assertEquals(1, releaseResources.literalCount("pendingFrames.decrementAndGet()"))
        assertEquals(1, releaseResources.literalCount("completion.frameSlots.release()"))
        assertTrue(releaseResources.contains("runCatching"))
        assertTrue(
            releaseResources.indexOf("pendingFrames.decrementAndGet()") >
                releaseResources.indexOf("exceptionOrNull()")
        )
        assertEquals(1, packingRejection.literalCount("markP010PackingFinished(packingPhaser)"))
        assertEquals(1, packingRejection.literalCount("closeImageSafely(image)"))
        assertEquals(1, packingRejection.literalCount("publishPreparedP010Commit("))
        assertEquals(0, packingRejection.literalCount("pendingFrames.decrementAndGet()"))
        assertEquals(0, packingRejection.literalCount("frameSlots.release()"))
        assertEquals(1, packingDispatchFailure.literalCount("markP010PackingFinished(packingPhaser)"))
        assertEquals(1, packingDispatchFailure.literalCount("closeImageSafely(image)"))
        assertEquals(1, packingDispatchFailure.literalCount("retireUnmaterializedP010Admission"))
        assertTrue(commit.contains("preparationFailure"))
        assertTrue(commit.contains("droppedFrames.incrementAndGet()"))
        assertTrue(commit.contains("poisonP010CommitPipeline"))
    }

    @Test
    fun writerFailurePoisonsTheCommitLaneBeforeAnyLaterQueuedPacketCanWrite() {
        val commit = manager.section(
            "private fun commitPreparedP010InOrder",
            "private fun retireRejectedPreparedP010Commit"
        )
        val poisonCheck = commit.indexOf("producerBoundaryGateRef.get()?.poisonFailure != null")
        val write = commit.indexOf("writePreparedP010Packet")
        val catchPoison = commit.indexOf("poisonP010CommitPipeline(failure)")
        val start = manager.section("fun start(config: Npv10SessionConfig): File", "fun preparePhysicalReservationAsync")
        val packingDrain = manager.section(
            "private fun awaitP010PackingDrain",
            "private fun prepareP010ImageForAsyncWrite"
        )

        assertTrue(manager.contains(
            "private val producerBoundaryGateRef = AtomicReference<Npv10ProducerBoundaryGate?>(null)"
        ))
        assertTrue(poisonCheck >= 0)
        assertTrue(write > poisonCheck)
        assertTrue(catchPoison > write)
        assertTrue(start.contains("val sessionProducerBoundaryGate = Npv10ProducerBoundaryGate()"))
        assertTrue(packingDrain.contains("producerBoundaryGateRef.get()?.poisonFailure != null"))
        assertTrue(commit.contains("droppedFrames.incrementAndGet()"))
    }

    @Test
    fun pauseReaderRebuildStopAndReleaseCannotOvertakeImageOwnedPacking() {
        val resume = manager.section("fun resume(): Boolean", "fun beginFinalization(): Boolean")
        val acceptedDrain = manager.section("fun awaitAcceptedFrameDrain(): Boolean", "fun stop(): File?")
        val stop = manager.section("fun stop(): File?", "fun resetCancelledStartError()")
        val release = manager.section("fun release()", "private companion object")

        assertOrdered(
            resume,
            "awaitP010PackingDrain()",
            "activeWriter.markTimestampCadenceDiscontinuity()",
            "paused = false"
        )
        assertOrdered(
            acceptedDrain,
            "beginFinalization()",
            "awaitP010PackingDrain()",
            "writerExecutor.submit<Boolean>"
        )
        assertOrdered(
            stop,
            "beginFinalization()",
            "awaitP010PackingDrain()",
            "writerExecutor.submit<Boolean>"
        )
        assertTrue(manager.contains("p010PackingPhaser.arriveAndAwaitAdvance()"))
        assertTrue(manager.contains("check(p010PackingInFlight.get() == 0)"))
        val packingFinished = manager.section(
            "private fun markP010PackingFinished",
            "private fun awaitP010PackingDrain"
        )
        assertTrue(packingFinished.contains("finally"))
        assertTrue(packingFinished.contains("phaser.arriveAndDeregister()"))
        assertTrue(release.contains("p010PackingExecutor.shutdown()"))
        assertTrue(release.contains("p010PackingExecutor.awaitTermination"))
        assertTrue(release.contains("p010PackingExecutor.shutdownNow()"))
        assertTrue(release.contains("writerExecutor.shutdownNow()"))
        assertTrue(release.indexOf("p010PackingExecutor.shutdown()") < release.indexOf("writerExecutor.shutdownNow()"))
    }

    @Test
    fun compatibilityBuffersAreWorkerSafeAndCrcProvenanceReachesWriterTelemetry() {
        val prepare = manager.section(
            "private fun prepareP010ImageForAsyncWrite",
            "private fun validateRuntimeP010Image"
        )
        val writerPrepared = writer.section(
            "internal fun writePreparedP010Packet",
            "private fun commitEncodedP010Frame"
        )

        assertTrue(manager.contains("canonicalP010FallbackBufferPool"))
        assertTrue(manager.contains("acquireCanonicalP010FallbackBuffer"))
        assertTrue(manager.contains("releaseCanonicalP010FallbackBuffer"))
        assertTrue(prepare.contains("copyP010ImageIntoDirectBufferWithResult"))
        assertTrue(prepare.contains("Npv10PacketCodecEngine.encodeWithCrc32"))
        assertTrue(prepare.contains("releaseCanonicalP010FallbackBuffer(canonicalLease)"))
        assertTrue(prepare.contains("Npv10PreparedP010CrcPath.CAMERA_PLANE_FUSED_NATIVE"))
        assertTrue(prepare.contains("Npv10PreparedP010CrcPath.CANONICAL_PACKET_NATIVE"))
        assertTrue(prepare.contains("Npv10PreparedP010CrcPath.JAVA_PACKET_FALLBACK"))
        assertTrue(writerPrepared.contains("precomputedCrc32 = packet.crc32"))
        assertTrue(writerPrepared.contains("precomputedCrcNativeFused = packet.crcPath.nativeFused"))
        assertTrue(writerPrepared.contains("precomputedFallbackCrcNs = packet.fallbackCrcTimeNs"))

        assertTrue(Npv10PreparedP010CrcPath.CAMERA_PLANE_FUSED_NATIVE.nativeFused)
        assertTrue(Npv10PreparedP010CrcPath.CANONICAL_PACKET_NATIVE.nativeFused)
        assertFalse(Npv10PreparedP010CrcPath.JAVA_PACKET_FALLBACK.nativeFused)
    }

    @Test
    fun canonicalFallbackConcurrencyIsFairBoundedAndReleasedExactlyOnce() {
        val prepare = manager.section(
            "private fun prepareP010ImageForAsyncWrite",
            "private fun validateRuntimeP010Image"
        )

        assertTrue(manager.contains(
            "Npv10InFlightBufferPolicy.MAX_CONCURRENT_CANONICAL_FALLBACKS,\n        true"
        ))
        assertTrue(manager.contains(
            "canonicalP010FallbackBufferPool.size >=\n            " +
                "Npv10InFlightBufferPolicy.MAX_CONCURRENT_CANONICAL_FALLBACKS"
        ))
        assertOrdered(
            prepare,
            "canonicalP010FallbackSlots.acquireUninterruptibly()",
            "acquireCanonicalP010FallbackBuffer(decodedPayloadBytes)",
            "releaseCanonicalP010FallbackBuffer(canonicalLease)",
            "canonicalP010FallbackSlots.release()"
        )
        assertEquals(1, prepare.literalCount("canonicalP010FallbackSlots.acquireUninterruptibly()"))
        assertEquals(1, prepare.literalCount("canonicalP010FallbackSlots.release()"))

        val referenceGate = Semaphore(
            Npv10InFlightBufferPolicy.MAX_CONCURRENT_CANONICAL_FALLBACKS,
            true
        )
        repeat(3) { attempt ->
            runCatching {
                referenceGate.acquireUninterruptibly()
                try {
                    if (attempt == 1) error("modeled fallback failure")
                } finally {
                    referenceGate.release()
                }
            }
            assertEquals(
                Npv10InFlightBufferPolicy.MAX_CONCURRENT_CANONICAL_FALLBACKS,
                referenceGate.availablePermits()
            )
        }
        assertTrue(referenceGate.isFair)
    }

    @Test
    fun niv99CopiesAndClosesCameraImageBeforeBoundedMetadataRendezvous() {
        val start = manager.section(
            "fun start(config: Npv10SessionConfig): File",
            "fun preparePhysicalReservationAsync"
        )
        val prepare = manager.section(
            "private fun prepareP010ImageForAsyncWrite",
            "private fun validateRuntimeP010Image"
        )
        val acquire = manager.section(
            "private fun acquireNiv99CanonicalP010Buffer",
            "private fun releaseNiv99CanonicalP010Buffer"
        )
        val niv99CanonicalFallback = prepare.substringAfter("if (!processed) {")

        assertTrue(manager.contains("niv99CanonicalP010BufferPool"))
        assertTrue(start.contains("Npv10InFlightBufferPolicy.niv99PendingFrames("))
        assertTrue(start.contains("primeNiv99CanonicalP010BufferPool(config)"))
        assertFalse(acquire.contains("Npv10NativeFrameBuffer.allocate"))
        assertOrdered(
            niv99CanonicalFallback,
            "acquireNiv99CanonicalP010Buffer(decodedPayloadBytes)",
            "copyP010ImageIntoCanonicalDirectBuffer(",
            "closeImageSafely(image)",
            "processor.processCanonicalAndPack("
        )
        assertTrue(prepare.contains("releaseNiv99CanonicalP010Buffer(canonicalLease)"))
        assertTrue(manager.contains("releaseIdleNiv99CanonicalP010Buffers()"))
    }

    private fun assertOrdered(source: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val current = source.indexOf(token)
            assertTrue("Missing '$token'", current >= 0)
            assertTrue("'$token' is out of order", current > previous)
            previous = current
        }
    }

    private fun String.section(start: String, end: String): String =
        substringAfter(start).substringBefore(end)

    private fun String.literalCount(token: String): Int =
        windowed(token.length).count { it == token }

    private fun <T> permutations(values: List<T>): List<List<T>> {
        if (values.size <= 1) return listOf(values)
        return values.flatMapIndexed { index, value ->
            permutations(values.filterIndexed { candidateIndex, _ -> candidateIndex != index })
                .map { suffix -> listOf(value) + suffix }
        }
    }
}
