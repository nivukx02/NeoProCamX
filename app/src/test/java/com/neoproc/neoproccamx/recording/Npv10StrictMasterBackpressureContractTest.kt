package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * s6-f04 strict-master no-silent-drop contract (test-only). The live FAIL_STRICT_MASTER path does
 * NOT throw — it drops + errors + returns WITHOUT writing the frame; this proves an overflow frame
 * can never be written into the master, and that a master built from the in-budget frames has no
 * hole and a clean session hash.
 */
class Npv10StrictMasterBackpressureContractTest {

    private val managerSource: String = listOf(
        "src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt",
        "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt"
    ).map(::File).firstOrNull { it.exists() }!!.readText()

    @Test
    fun cameraP010UsesFusedPlaneToPackedTenBitPathWithoutQualityChange() {
        val writerSource = File(
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt"
        ).readText()
        val packerSource = File(
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10FramePacker.kt"
        ).readText()
        val nativeSource = File("src/main/cpp/npv10_raw_native.cpp").readText()
        val imageWrite = writerSource.substringAfter("fun writeP010Image(image: Image)")
            .substringBefore("fun writeCopiedP010Frame")

        val fusedIndex = imageWrite.indexOf("packP010ImageToPackedTenBitBufferWithResult")
        val compatibilityCopyIndex = imageWrite.indexOf("copyP010ImageIntoDirectBufferWithResult")
        assertTrue(fusedIndex >= 0)
        assertTrue(compatibilityCopyIndex > fusedIndex)
        assertTrue(packerSource.contains("nativePackP010ImagePlanesToPackedTenBit"))
        assertTrue(packerSource.contains("nativePackP010ImagePlanesToPackedTenBitWithCrc"))
        assertTrue(nativeSource.contains("packStandardP010PlanesToTenBit"))
        assertTrue(nativeSource.contains("crc32_combine"))
        assertTrue(nativeSource.contains("kFourWordPaddingMask"))
        assertTrue(nativeSource.contains("outputOffset == targetBytes"))
        assertTrue(nativeSource.contains("word & 0x003Fu"))
    }

    @Test
    fun writerHotPathFusesPacketCrcAndDoesNotBlockOnPeriodicFsync() {
        val writerSource = File(
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt"
        ).readText()
        val codecSource = File(
            "src/main/java/com/neoproc/neoproccamx/recording/Npv10PacketCodec.kt"
        ).readText()
        val nativeSource = File("src/main/cpp/npv10_raw_native.cpp").readText()
        val copiedWrite = writerSource
            .substringAfter("fun writeCopiedP010Frame(frame: Npv10CopiedP010Frame)")
            .substringBefore("private fun commitEncodedP010Frame")
        val commit = writerSource
            .substringAfter("private fun commitEncodedP010Frame")
            .substringBefore("override fun close()")
        val checkpoint = writerSource
            .substringAfter("private fun scheduleDurabilityCheckpointIfRequired()")
            .substringBefore("private fun awaitDurabilityCheckpoint()")
        val close = writerSource
            .substringAfter("private fun closeInternal(")
            .substringBefore("private fun writeHeader()")

        assertTrue(copiedWrite.contains("Npv10PacketCodecEngine.encodeWithCrc32("))
        assertTrue(
            copiedWrite.indexOf("throwIfDurabilityCheckpointFailed()") <
                copiedWrite.indexOf("Npv10PacketCodecEngine.encodeWithCrc32(")
        )
        assertTrue(commit.contains("precomputedCrc32 ?: crc32("))
        assertFalse(commit.contains("channel.force("))
        assertTrue(checkpoint.contains("durabilityExecutor.submit"))
        assertTrue(checkpoint.contains("durabilityChannel.force(false)"))
        assertTrue(checkpoint.contains("coalescedDurabilityCheckpointRequests.incrementAndGet()"))
        assertTrue(writerSource.contains("val openedDurabilityFile = try"))
        assertTrue(writerSource.contains("durabilityRandomAccessFile = openedDurabilityFile"))
        assertTrue(writerSource.contains("reservationExecutor.submit"))
        assertTrue(checkpoint.contains("firstDurableFrameProof::complete"))
        assertTrue(
            checkpoint.indexOf("durabilityChannel.force(false)") <
                checkpoint.indexOf("firstDurableFrameProof::complete")
        )
        assertTrue(writerSource.contains("if (firstDurableFrameProof.isDone)"))
        assertTrue(writerSource.contains("runCatching { firstDurableFrameProof.join() }"))
        assertTrue(
            checkpoint.indexOf("throwIfDurabilityCheckpointFailed()") <
                checkpoint.indexOf("frameCount != 1L")
        )
        assertTrue(close.indexOf("awaitDurabilityCheckpoint()") < close.indexOf("channel.truncate(committedBytes)"))
        assertTrue(close.indexOf("channel.truncate(committedBytes)") < close.indexOf("channel.force(true)"))
        assertTrue(codecSource.contains("nativePackP010TenBitPacketWithCrc"))
        assertTrue(nativeSource.contains("packContiguousP010TenBit"))
        assertTrue(nativeSource.contains("kCrcCacheChunkBytes"))
        assertTrue(nativeSource.contains("sampleIndex + 32 <= sampleCount"))
    }

    @Test
    fun managerOverflowBranchDropsAndErrorsWithoutWritingFrame() {
        val overflowBranch = managerSource
            .substringAfter("Npv10FrameBackpressureAction.FAIL_STRICT_MASTER")
            .substringBefore("writerExecutor.execute")
        val failureHelper = managerSource
            .substringAfter("private fun failStrictMasterBacklog()")
            .substringBefore("@Synchronized\n    fun pause()")
        assertTrue(overflowBranch.contains("pendingFrames.decrementAndGet()"))
        assertTrue(overflowBranch.contains("droppedFrames.incrementAndGet()"))
        assertTrue(overflowBranch.contains("failStrictMasterBacklog()"))
        assertTrue(failureHelper.contains("GpuRecordingState.Error("))
        assertTrue(overflowBranch.contains("return"))
        // The overflow frame is never written: writeFrame is not reachable in the strict branch.
        assertFalse(overflowBranch.contains("activeWriter.writeFrame"))
    }

    @Test
    fun writerThreadUsesUrgentDisplayPriorityForSustainedPrivateMasterWrites() {
        assertTrue(managerSource.contains("Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)"))
    }

    @Test
    fun cameraImageCallbackUsesBoundedFrameAwareWaitBeforeStrictFailure() {
        assertTrue(managerSource.contains("Npv10InFlightBufferPolicy.pendingFrames("))
        assertTrue(managerSource.contains("config.packetCodec"))
        assertTrue(managerSource.contains("Semaphore(activeMaxPendingFrames, true)"))
        assertTrue(managerSource.contains("frameSlots.tryAcquire()"))
        assertTrue(managerSource.contains("Npv10InFlightBufferPolicy.admissionWaitNanos(fps)"))
        assertTrue(managerSource.contains("frameSlots.tryAcquire(waitBudgetNanos, TimeUnit.NANOSECONDS)"))
        assertFalse(managerSource.contains("frameSlots.acquire()"))
        assertTrue(managerSource.contains("frameSlots.release()"))
        assertTrue(managerSource.contains("throughput exhausted its bounded frame leases"))
        assertTrue(managerSource.contains("ThreadPoolExecutor("))
        assertTrue(managerSource.contains("prestartAllCoreThreads()"))
        assertTrue(managerSource.contains("p010PackingExecutor.execute"))
        assertFalse(managerSource.contains("dmcP010PackingExecutor"))
        assertTrue(managerSource.contains("prepareP010ImageForAsyncWrite("))
        assertTrue(managerSource.contains("image = image"))
        assertTrue(managerSource.contains("config = sessionConfig"))
        assertTrue(managerSource.contains("writePreparedP010Packet"))
        assertTrue(managerSource.contains("releaseP010PacketBuffer"))
        assertTrue(managerSource.contains("packP010ImageToPackedTenBitBufferWithCrcResult"))
        assertTrue(managerSource.contains("closeImageSafely(image)"))
        assertTrue(managerSource.contains("writer !== activeWriter"))
    }

    @Test
    fun durableStopReleasesIdleP010CopiesBeforePostRecordAudit() {
        val stopBody = managerSource
            .substringAfter("fun stop(): File?")
            .substringBefore("fun resetCancelledStartError()")
        val closeIndex = stopBody.indexOf("awaitFutureUninterruptibly(closeTask)")
        val releaseIndex = stopBody.indexOf("releaseIdleP010PacketBuffers()")
        val auditIndex = stopBody.indexOf("activeWriter.buildDeviceEvidence(")

        assertTrue(closeIndex >= 0)
        assertTrue(releaseIndex > closeIndex)
        assertTrue(auditIndex > releaseIndex)
        assertTrue(managerSource.contains("lease.close()"))
    }

    @Test
    fun acceptedImageLeasesDrainBeforeCameraReaderMayBeRebuilt() {
        val drainBody = managerSource
            .substringAfter("fun awaitAcceptedFrameDrain(): Boolean")
            .substringBefore("fun stop(): File?")

        assertTrue(drainBody.contains("beginFinalization()"))
        val packingDrainIndex = drainBody.indexOf("awaitP010PackingDrain()")
        val writerMarkerIndex = drainBody.indexOf("writerExecutor.submit<Boolean>")
        assertTrue(packingDrainIndex >= 0)
        assertTrue(writerMarkerIndex > packingDrainIndex)
        assertTrue(drainBody.contains("writerExecutor.submit<Boolean>"))
        assertTrue(drainBody.contains("awaitFutureUninterruptibly(drainTask)"))
        assertTrue(drainBody.contains("pendingFrames.get() == 0"))
    }

    @Test
    fun intentionalPauseStartsNewSensorCadenceSegmentBeforeResumeAdmission() {
        val resumeBody = managerSource
            .substringAfter("fun resume(): Boolean")
            .substringBefore("fun beginFinalization(): Boolean")

        val packingDrainIndex = resumeBody.indexOf("awaitP010PackingDrain()")
        val markerIndex = resumeBody.indexOf("activeWriter.markTimestampCadenceDiscontinuity()")
        val resumeAdmissionIndex = resumeBody.indexOf("paused = false")
        assertTrue(packingDrainIndex >= 0)
        assertTrue(markerIndex > packingDrainIndex)
        assertTrue(markerIndex >= 0)
        assertTrue(resumeAdmissionIndex > markerIndex)
    }

    @Test
    fun startRejectsSubstitutedResolutionBeforeCreatingOrAdoptingAMaster() {
        val startBody = managerSource
            .substringAfter("fun start(config: Npv10SessionConfig): File")
            .substringBefore("fun preparePhysicalReservationAsync()")
        val exactRequirement = startBody.indexOf("require(config.exactSelectedResolution)")
        val masterAdoption = startBody.indexOf("takePreparedReservation()")

        assertTrue(exactRequirement >= 0)
        assertTrue(masterAdoption > exactRequirement)
        assertTrue(startBody.contains("refusing a substituted"))
    }

    @Test
    fun failedEliteAuditRetainsPrivateMasterButCannotPassPublicationGate() {
        val stopBody = managerSource
            .substringAfter("fun stop(): File?")
            .substringBefore("fun resetCancelledStartError()")
        val gateBody = stopBody
            .substringAfter("val prePublicationFailures = buildList")
            .substringBefore("recordStopStageTimings(")

        assertTrue(
            gateBody.contains(
                "evidence?.eliteVideoQualityAuditStatus != EliteQualityAuditStatus.PASS"
            )
        )
        assertTrue(gateBody.contains("eliteVideoQualityAudit="))
        assertTrue(gateBody.contains("lastStopDurabilityAuditPassed = durabilityFailures.isEmpty()"))
        assertTrue(gateBody.contains("master preserved privately but failed durability publication gate"))
        assertFalse(gateBody.contains("activeOutput?.delete()"))
        assertTrue(stopBody.lastIndexOf("return activeOutput") > stopBody.indexOf("val durabilityFailures"))
    }

    @Test
    fun asyncWriteFailureCannotBeCountedAsCommittedOrPublished() {
        val enqueueBody = managerSource
            .substringAfter("internal fun enqueueP010ImageWithAdmission(image: Image): Npv10P010ImageAdmission")
            .substringBefore("private fun publishPreparedP010Commit")
        val commitBody = managerSource
            .substringAfter("private fun commitPreparedP010InOrder")
            .substringBefore("private fun retireRejectedPreparedP010Commit")
        val prepareBody = managerSource
            .substringAfter("private fun prepareP010ImageForAsyncWrite")
            .substringBefore("private fun validateRuntimeP010Image")
        val releaseBody = managerSource
            .substringAfter("private fun releasePreparedP010Resources")
            .substringBefore("private fun retireUnmaterializedP010Admission")
        val writeIndex = commitBody.indexOf("writePreparedP010Packet")
        val commitIndex = commitBody.indexOf("captureLedger.recordCommitted(")
        val errorIndex = commitBody.indexOf("poisonP010CommitPipeline(failure)")
        val stopBody = managerSource
            .substringAfter("fun stop(): File?")
            .substringBefore("fun resetCancelledStartError()")

        assertTrue(enqueueBody.contains("preparationFailure = failure"))
        assertTrue(prepareBody.contains("finally"))
        assertTrue(prepareBody.contains("closeImageSafely(image)"))
        assertTrue(writeIndex >= 0)
        assertTrue(commitIndex > writeIndex)
        assertTrue(errorIndex > commitIndex)
        assertTrue(commitBody.contains("asynchronousWriterFailures.incrementAndGet()"))
        assertTrue(commitBody.contains("droppedFrames.incrementAndGet()"))
        assertTrue(commitBody.contains("recordP010Copy(Npv10FramePacker.P010CopyPath.FAILED)"))
        assertTrue(commitBody.contains("completion.retireFromWriter()"))
        assertTrue(commitBody.contains("var persistenceCommitted = false"))
        assertTrue(commitBody.contains("persistenceCommitted = true"))
        assertTrue(commitBody.contains("releasePreparedP010Resources("))
        assertTrue(commitBody.contains("abortDirectMappedFrame = !persistenceCommitted"))
        assertTrue(releaseBody.contains("releaseP010PacketBuffer"))
        assertTrue(releaseBody.contains("pendingFrames.decrementAndGet()"))
        assertTrue(releaseBody.contains("completion.frameSlots.release()"))
        assertTrue(stopBody.contains("addAll(captureLedgerFailures)"))
        assertTrue(stopBody.contains("asynchronousWriterFailures="))
        assertTrue(stopBody.contains("val terminalError = _state.value as? GpuRecordingState.Error"))
        assertTrue(stopBody.contains("Npv10VerifiedMasterPublication.publish("))
    }

    @Test
    fun masterBuiltWithInBudgetFramesHasNoHoleAndCleanHash() {
        // Mirror the manager withholding the overflow frame: build a master from only the frames
        // that were accepted; the result must be a complete, hash-verifiable master (no partial/hole).
        val root = Files.createTempDirectory("npv10-strict-nohole").toFile()
        val config = Npv10SessionConfig(
            width = 2,
            height = 2,
            fps = 24,
            mode = "PRO",
            colorProfile = "Rec.709",
            startedAtEpochMs = 1L,
            sourceFormat = "YCBCR_P010",
            sourceBitDepth = 10,
            payloadFormat = Npv10PayloadFormat.P010_LE,
            exactSelectedResolution = true
        )
        val master = Npv10MasterStore(root).createMasterFile(config)
        val writer = Npv10FileWriter(master, config)
        writer.use {
            it.writeFrame(frame(1_000_000L))
            it.writeFrame(frame(42_666_666L))
            it.writeFrame(frame(84_333_333L))
        }
        val audit = Npv10QualityGate.auditMasterFile(
            masterFile = master,
            expectedFrameCount = writer.frameCount,
            expectedPayloadBytes = writer.payloadBytesWritten
        )
        assertEquals(Npv10AuditStatus.PASS, audit.auditStatus)
        assertEquals(3L, audit.frameCount)
        assertTrue(audit.sessionHashCheckPass)
        assertTrue(audit.byteCountValid)
        root.deleteRecursively()
    }

    private fun frame(timestamp: Long) =
        com.neoproc.neoproccamx.pipeline.RawYuvFrame(
            width = 2,
            height = 2,
            yBytes = tenBitPlane(16, 32, 64, 128),
            uBytes = tenBitPlane(512),
            vBytes = tenBitPlane(512),
            yRowStride = 4,
            yPixelStride = 2,
            uRowStride = 2,
            vRowStride = 2,
            uPixelStride = 2,
            vPixelStride = 2,
            timestamp = timestamp,
            bitDepth = 10
        )

    private fun tenBitPlane(vararg samples: Int): ByteArray {
        val output = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val value = sample.coerceIn(0, 1023)
            output[index * 2] = (value and 0xFF).toByte()
            output[index * 2 + 1] = ((value ushr 8) and 0x03).toByte()
        }
        return output
    }
}
