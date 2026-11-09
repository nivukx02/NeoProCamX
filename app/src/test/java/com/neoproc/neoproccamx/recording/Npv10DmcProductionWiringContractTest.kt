package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Npv10DmcProductionWiringContractTest {
    @Test
    fun `storage priority is scoped to the real writer before frame io and not the caller`() {
        val mainRoot = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val native = File(mainRoot, "cpp/npv10_dmc_native.cpp").readText()
        val priority = File(mainRoot, "cpp/npv10_record_io_priority.h").readText()
        val worker = native.substringAfter("void recordWriterWorkerLoop() noexcept {")
            .substringBefore("bool reserveSlots() noexcept")
        assertTrue(worker.contains("ScopedRecordIoPriority<> storagePriority"))
        assertTrue(worker.indexOf("ScopedRecordIoPriority<>") < worker.indexOf("writeRecordAt("))
        assertTrue(worker.contains("storagePriority.applied()"))
        assertTrue(worker.contains("storagePriority.error()"))
        assertTrue(priority.contains("__NR_ioprio_set, 1, 0, priority"))
        assertTrue(priority.contains("kRequested = 2 << 13"))
        assertTrue(priority.contains("error_ = Io::capability()"))
        assertTrue(priority.contains("current != kRequested"))
        assertTrue(priority.contains("~ScopedRecordIoPriority()"))
        assertFalse(priority.contains("setpriority("))
    }

    @Test
    fun `production NPV10 requires DMC and bypasses packet allocation`() {
        val mainRoot = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val viewModel = File(mainRoot, "java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val manager = File(
            mainRoot,
            "java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt"
        ).readText()
        val writer = File(
            mainRoot,
            "java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt"
        ).readText()

        assertTrue(viewModel.contains("directMappedCommitRequired = true"))
        assertTrue(manager.contains("if (config.directMappedCommitRequired)"))
        assertTrue(manager.contains("processor.processImageAndPackDirectMappedIfTuningReady("))
        assertTrue(manager.contains("processor.processCanonicalAndPackDirectMapped("))
        assertTrue(manager.contains("activeWriter.beginDirectMappedFrame(sequence, encodedBytes)"))
        assertTrue(writer.contains("commitDirectMappedP010Frame(packet, writerServiceStartedNs)"))
        assertTrue(writer.contains("engine.commitFrame("))
        assertTrue(writer.contains("frameIndex = packet.directMappedFrameIndex"))
        assertTrue(writer.contains("encodedBytes = packet.encodedBytes"))
        assertTrue(writer.contains("payloadFileOffset = payloadFileOffset"))

        val dmcBranch = manager.substring(
            manager.indexOf("val packetLease = if (config.directMappedCommitRequired)"),
            manager.indexOf("var directMappedFrameBegun")
        )
        assertTrue(dmcBranch.contains("null"))
        assertFalse(dmcBranch.substringBefore("} else {").contains("acquireP010PacketBuffer"))
    }

    @Test
    fun `native DMC uses a prefaulted bounded staging ring and exact positional commits`() {
        val mainRoot = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val native = File(mainRoot, "cpp/npv10_dmc_native.cpp").readText()
        val processor = File(mainRoot, "cpp/npv10_raw_native.cpp").readText()

        assertTrue(native.contains("MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED"))
        assertTrue(native.contains("volatile std::uint8_t* const pageTouch"))
        assertTrue(native.contains("::pwritev("))
        assertTrue(native.contains("writeRecordAt("))
        assertTrue(native.contains("recordWriterWorkerLoop"))
        assertTrue(native.contains("recordWriteWorkAvailable_.notify_one()"))
        assertTrue(native.contains("SlotState::Queued"))
        assertTrue(native.contains("header[0] = 'F'"))
        assertTrue(native.contains("payloadFileOffset"))
        assertTrue(native.contains("activeCommits_"))
        assertTrue(native.contains("slotCount"))
        assertTrue(native.contains("bounded slot reuse attempted before retirement"))
        assertTrue(native.contains("NPV10 DMC async record write"))
        assertTrue(processor.contains("kNiv99HelperThreadNice = -8"))
        assertTrue(processor.contains("kHelperThreadCount = 7u"))
        assertTrue(processor.contains("kOfflineHelperThreadCount = 5u"))
        assertTrue(processor.contains("kCaptureSafeHelperThreadCount = 5u"))
        assertTrue(processor.contains("sched_getaffinity"))
        assertTrue(processor.contains("allowedCpuCount > 1u ? allowedCpuCount - 1u : 1u"))
        assertTrue(processor.contains("kUnknownAffinityHelperThreadCount = 4u"))
        assertTrue(processor.contains("APerformanceHint_createSession"))
        assertTrue(processor.contains("APerformanceHint_reportActualWorkDuration"))
        assertTrue(processor.contains("kNiv99TargetFrameDurationNanos = 30'000'000LL"))
        assertTrue(processor.contains("storeThreeEightSampleNiv99OutputsNeon"))
        assertTrue(processor.contains("kLumaRowsPerClaim = 4"))
        val ordinaryPacker = processor.substringAfter("struct StandardP010PackRowJob")
            .substringBefore("bool acceptNiv99TemporalHistory")
        assertTrue(ordinaryPacker.contains("kLumaRowsPerClaim = 32"))
        assertTrue(ordinaryPacker.contains("NPV10 P010 CRC combine"))
        assertTrue(processor.contains("job->emitP010 && !job->computeOutputCrc"))
        assertTrue(processor.contains("NIV99 P010 submission wait"))
        assertTrue(processor.contains("NIV99 P010 execute"))
        assertFalse(processor.contains("configuredSubmittingThreadId_"))
        assertFalse(processor.contains("threadIds[threadCount++] = submittingThreadId"))
        val losslessCodec = File(mainRoot, "cpp/npv10_lossless_chunk_codec.cpp").readText()
        assertTrue(losslessCodec.contains("kRealtimeSearchAcceleration = 1024u"))
        assertTrue(losslessCodec.contains("kUpstreamRealtimeAcceleration = 1024;"))
        assertTrue(losslessCodec.contains("hasWorthwhileSplitLz4Savings"))
        assertTrue(losslessCodec.contains("kCompressionAdmissionMatchProbeBytes"))
        assertTrue(losslessCodec.contains("kCompressionAdmissionEvidenceBytes"))
        assertTrue(losslessCodec.contains("attemptCompression && !splitPackedTenBit"))
        assertTrue(losslessCodec.contains("attemptCompression && directCapacity > 0u"))
        assertTrue(losslessCodec.contains("framePackedTenBitRealtimeInPlace"))
        val livePreparation = native.substring(
            native.indexOf("bool finishProcessing("),
            native.indexOf("bool commitFrame(")
        )
        assertTrue(livePreparation.contains("compressPackedTenBitRealtimeRangeLz4InPlace("))
        assertTrue(losslessCodec.contains("compressPackedTenBitRealtimeLz4InPlace("))
        assertTrue(losslessCodec.contains("rangePacking ? kRangeVersion : kVersion"))
        assertTrue(losslessCodec.contains("encodeRange10("))
        assertTrue(losslessCodec.contains("decodeRange10("))
        assertFalse(livePreparation.contains("compressPackedTenBitInPlace("))
        assertTrue(losslessCodec.contains("LZ4_compress_fast_extState("))
        assertTrue(losslessCodec.contains("LZ4_decompress_safe("))
        assertTrue(losslessCodec.contains("LZ4_stream_t upstreamState{}"))
        assertTrue(losslessCodec.contains("thread_local Workspace gWorkspace"))
        assertTrue(losslessCodec.contains("compressedBytes > 0u && compressedBytes < sourceBytes"))
        assertTrue(losslessCodec.contains("std::memmove(packet + bodyOffset"))
        assertTrue(losslessCodec.contains("result.compressedChunks = 0"))
        val writer = File(
            mainRoot,
            "java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt"
        ).readText()
        val directCommit = writer.substring(
            writer.indexOf("private fun commitDirectMappedP010Frame("),
            writer.indexOf("private fun commitEncodedP010Frame(")
        )
        assertFalse(directCommit.contains("writeFrameHeader("))
        assertTrue(directCommit.contains("timestampNs = packet.timestampNs"))
        assertFalse(native.contains("MAP_SHARED"))
        assertFalse(native.contains("primerThread_"))
        assertFalse(native.contains("mapSlotForFrame"))
    }

    @Test
    fun `DMC incrementally preflushes committed ranges without weakening durability checkpoints`() {
        val mainRoot = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val native = File(mainRoot, "cpp/npv10_dmc_native.cpp").readText()
        val engine = File(
            mainRoot,
            "java/com/neoproc/neoproccamx/recording/Npv10DirectMappedCommitEngine.kt"
        ).readText()
        val writer = File(
            mainRoot,
            "java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt"
        ).readText()
        val manager = File(
            mainRoot,
            "java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt"
        ).readText()

        assertTrue(native.contains("::sync_file_range("))
        assertTrue(native.contains("SYNC_FILE_RANGE_WRITE"))
        assertTrue(native.contains("kIncrementalWritebackTargetBytes"))
        assertTrue(native.contains("kIncrementalWritebackMaxFrames"))
        assertTrue(native.contains("IncrementalWritebackPolicy::suppressForRealtime("))
        val writebackPolicy = File(mainRoot, "cpp/npv10_writeback_policy.h").readText()
        assertTrue(writebackPolicy.contains("payloadCapacityBytes >= kLegacySuppressionPayloadBytes"))
        assertTrue(native.contains("incrementalWritebackSuppressedForRealtime_"))
        assertTrue(native.contains("incrementalWritebackCoveredThroughOffset_"))
        assertTrue(native.contains("incrementalWritebackWorkerLoop"))
        assertTrue(native.contains("incrementalWritebackWorkAvailable_.notify_one()"))
        val commitRangePublisher = native.substring(
            native.indexOf("void noteCommittedRangeForIncrementalWriteback"),
            native.indexOf("void incrementalWritebackWorkerLoop")
        )
        assertFalse(commitRangePublisher.contains("::sync_file_range("))
        assertTrue(engine.contains("nativeIncrementalWritebackTelemetry"))
        assertTrue(engine.contains("nativeAwaitCommittedFrames"))
        assertTrue(writer.contains("directMappedCommitEngine?.awaitCommittedFrames(requestedFrame)"))
        assertTrue(writer.contains("engine.awaitCommittedFrames(frameCount)"))
        assertTrue(writer.contains("fun toEvidenceMetadata(): Map<String, String>"))
        assertTrue(writer.contains("npv10DmcIncrementalWritebackAttempts"))
        assertTrue(writer.contains("npv10DmcIncrementalWritebackSuppressedForRealtime"))
        assertTrue(writer.contains("npv10DmcAsyncPersistedFrames"))
        assertTrue(writer.contains("npv10DmcAsyncRecordWriteFailures"))
        assertTrue(manager.contains("putAll(writerStageTimings.toEvidenceMetadata())"))
        assertTrue(writer.contains("durabilityChannel.force(false)"))
        assertTrue(writer.contains("durabilityRandomAccessFile = openedDurabilityFile"))
        assertTrue(writer.contains("channel.force(true)"))
        assertFalse(native.contains("SYNC_FILE_RANGE_WAIT_AFTER"))
    }

    @Test
    fun `lossless writeback uses committed byte bounds and reports actual native I O`() {
        val mainRoot = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val native = File(mainRoot, "cpp/npv10_dmc_native.cpp").readText()
        val policy = File(mainRoot, "cpp/npv10_writeback_policy.h").readText()
        assertTrue(native.contains("IncrementalWritebackPolicy::suppressForRealtime("))
        assertTrue(native.contains("IncrementalWritebackPolicy::boundedEnd("))
        assertTrue(native.contains("recordWriteCompletedBytes_ += frameHeaderBytes_ + storedBytes"))
        assertTrue(policy.contains("!chunkedLosslessCompression"))
        assertTrue(policy.contains("kMaximumRequestBytes = 16LL * 1024LL * 1024LL"))
        assertTrue(policy.contains("requestedThrough <= coveredThrough"))
        assertTrue(policy.contains("pending < kMaximumRequestBytes ? pending : kMaximumRequestBytes"))
    }

    @Test
    fun `ten bit vector split keeps scalar tails and never overreads final source block`() {
        val mainRoot = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val codec = File(mainRoot, "cpp/npv10_lossless_chunk_codec.cpp").readText()
        val simd = File(mainRoot, "cpp/npv10_split10_simd.h").readText()
        assertTrue(codec.contains("splitPackedTenBitSimdPrefix(source, sampleCount, target)"))
        assertTrue(codec.contains("for (; sample + 4u <= sampleCount; sample += 4u)"))
        assertTrue(codec.contains("for (; sample < sampleCount; ++sample)"))
        assertTrue(simd.contains("defined(__aarch64__)"))
        assertTrue(simd.contains("samples - sample >= 32"))
        assertTrue(simd.contains("vcombine_u8(vld1_u8(packed + 32), zero)"))
        assertFalse(simd.contains("vld1q_u8(packed + 32)"))
        assertTrue(simd.contains("return 0;"))
    }

    @Test
    fun `DMC scheduling keeps capture ingress above persistence and helper work`() {
        val mainRoot = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val manager = File(
            mainRoot,
            "java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt"
        ).readText()
        val writerFactory = manager.substring(
            manager.indexOf("private val writerExecutor"),
            manager.indexOf("private val packingThreadIndex")
        )
        val packingFactory = manager.substring(
            manager.indexOf("private val p010PackingExecutor"),
            manager.indexOf("private val reservationExecutor")
        )

        assertTrue(writerFactory.contains("Process.THREAD_PRIORITY_DISPLAY"))
        assertFalse(writerFactory.contains("Process.THREAD_PRIORITY_URGENT_DISPLAY"))
        assertTrue(packingFactory.contains("Process.THREAD_PRIORITY_URGENT_DISPLAY"))
        assertTrue(packingFactory.contains("ThreadPoolExecutor("))
        assertTrue(packingFactory.contains("P010_PACKING_DISPATCH_WORKERS"))
        assertTrue(packingFactory.contains("prestartAllCoreThreads()"))
        assertTrue(manager.contains("p010PackingExecutor.execute"))
        assertFalse(packingFactory.contains("NPV10-DMC-Pack"))
        assertFalse(manager.contains("dmcP010PackingExecutor"))
    }
}
