package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Npv10AlignedWriterContractTest {
    private val mainRoot get() = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test
    fun `native source contracts invalidate the JVM test cache after a native change`() {
        val build = File(mainRoot, "../../build.gradle").canonicalFile.readText()
        assertTrue(build.contains("tasks.withType(org.gradle.api.tasks.testing.Test).configureEach"))
        assertTrue(build.contains("inputs.files(fileTree('src/main/cpp')"))
        assertTrue(build.contains("include '**/*.h', '**/*.cpp', '**/*.c', 'CMakeLists.txt'"))
        assertTrue(build.contains("withPropertyName('nativeRecordingSourceContracts')"))
        assertTrue(build.contains("withPropertyName('nativeRecordingContractBuildConfiguration')"))
        assertTrue(build.contains("withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)"))
    }

    @Test
    fun `writer selection precedes capture and runtime failure cannot fall back`() {
        val native = File(mainRoot, "cpp/npv10_dmc_native.cpp").readText()
        assertTrue(native.indexOf("engine->initializeAlignedRecordWriter(path)") <
            native.indexOf("engine->startRecordWriterWorker()"))
        val branch = native.substringAfter("if (alignedRecordWriterEnabled_) {\n            ScopedDmcTraceSection")
            .substringBefore("std::size_t headerWritten")
        assertTrue(branch.contains("alignedRecordWriter_->writeRecord("))
        assertTrue(branch.contains("return false; // No fallback after admission"))
        assertTrue(branch.contains("return true;"))
        assertFalse(branch.contains("pwritev"))
    }

    @Test
    fun `large file support and JNI telemetry widths stay synchronized`() {
        val native = File(mainRoot, "cpp/npv10_dmc_native.cpp").readText()
        val cmake = File(mainRoot, "cpp/CMakeLists.txt").readText()
        assertTrue(cmake.contains("npv10_dmc_native.cpp PROPERTIES COMPILE_DEFINITIONS \"_FILE_OFFSET_BITS=64\""))
        assertTrue(native.contains("static_assert(sizeof(off_t) >= 8u"))
        val nativeWidth = Regex("kWritebackTelemetryValues = (\\d+)u")
            .find(native)!!.groupValues[1].toInt()
        val jniWidth = Regex("kValueCount = (\\d+);").find(native)!!.groupValues[1].toInt()
        val highestField = Regex("output\\[(\\d+)] =").findAll(native)
            .maxOf { it.groupValues[1].toInt() }
        assertEquals(55, nativeWidth)
        assertEquals(nativeWidth, jniWidth)
        assertEquals(nativeWidth, highestField + 1)
        // The pressure extension appends fields; the existing aligned-writer ABI stays at 22..31.
        assertTrue(native.contains("output[31] = alignedRecordWriter_ && alignedRecordWriter_->probeVerified() ? 1 : 0;"))
        assertTrue(native.contains("output[32] = chunkedLosslessCompression_ ? 1 : 0;"))
        assertTrue(native.contains("output[48] = alignedRecordWriter_ && alignedRecordWriter_->kernelAioEnabled() ? 1 : 0;"))
        assertTrue(native.contains("output[51] = alignedRecordWriter_ ? telemetryCount(alignedRecordWriter_->kernelAioCompletions()) : 0;"))
        assertTrue(native.contains("output[52] = alignedRecordWriter_ ? telemetryCount(alignedRecordWriter_->ownedRecordWrites()) : 0;"))
        assertTrue(native.contains("output[53] = alignedRecordWriter_ ? telemetryCount(alignedRecordWriter_->ownedPayloadBytes()) : 0;"))
        assertTrue(native.contains("output[54] = alignedRecordWriter_ ? telemetryCount(alignedRecordWriter_->immutableStreamRecordWrites()) : 0;"))
        val wrapper = File(mainRoot, "java/com/neoproc/neoproccamx/recording/Npv10DirectMappedCommitEngine.kt").readText()
        assertTrue(wrapper.contains("alignedRecordWriterProbeVerified = writeback?.getOrNull(31) == 1L"))
    }

    @Test
    fun `selection never changes container quality and barriers remain mandatory`() {
        val header = File(mainRoot, "cpp/npv10_aligned_record_writer.h").readText()
        assertTrue(header.contains("O_RDWR | O_CLOEXEC | O_NOFOLLOW | O_DIRECT"))
        assertTrue(header.contains("original.st_ino != direct.st_ino"))
        assertTrue(header.contains("physicalEnd > reservationLimit"))
        assertTrue(header.contains("file.st_size < physicalEnd"))
        assertFalse(header.contains("F_SETFL"))
        val writer = File(mainRoot, "java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt").readText()
        assertTrue(writer.contains("directMappedCommitEngine?.awaitCommittedFrames(requestedFrame)"))
        assertTrue(writer.contains("durabilityChannel.force(false)"))
        assertTrue(writer.contains("engine.awaitCommittedFrames(frameCount)"))
        assertTrue(writer.contains("channel.force(true)"))
        assertTrue(writer.contains("channel.truncate(committedBytes)"))
    }

    @Test
    fun `certificate reports actual aligned writer counters and capability`() {
        val evidence = Npv10WriterStageTimings(
            alignedRecordWriterAttempted = true, alignedRecordWriterEnabled = true,
            alignedRecordWriterAlignmentBytes = 16_384L, alignedRecordWriterCalls = 20L,
            alignedRecordWriterPhysicalBytes = 50_000L, alignedRecordWriterPaddingBytes = 200L,
            alignedRecordWriterFailures = 0L, alignedRecordWriterStagingBytes = 2L * 1024L * 1024L,
            alignedRecordWriterProbeVerified = true
        ).toEvidenceMetadata()
        assertEquals("true", evidence["npv10DmcAlignedWriterEnabled"])
        assertEquals("true", evidence["npv10DmcAlignedWriterProbeVerified"])
        assertEquals("16384", evidence["npv10DmcAlignedWriterAlignmentBytes"])
        assertEquals("50000", evidence["npv10DmcAlignedWriterPhysicalBytes"])
        assertEquals("200", evidence["npv10DmcAlignedWriterPaddingBytes"])
        assertEquals("0", evidence["npv10DmcAlignedWriterFailures"])
        assertEquals("2097152", evidence["npv10DmcAlignedWriterStagingBytes"])
    }

    @Test
    fun `owned packet route preserves timing and kernel buffer retirement`() {
        val native = File(mainRoot, "cpp/npv10_dmc_native.cpp").readText()
        val shutdown = native.substringAfter("bool shutdown() noexcept {").substringBefore("private:")
        assertTrue(shutdown.indexOf("alignedRecordWriter_->close()") <
            shutdown.indexOf("::munmap(slot.reservationBase"))
        assertTrue(shutdown.contains("!alignedRecordWriter_->sourceBuffersRetired()"))
        val loop = native.substringAfter("void recordWriterWorkerLoop()").substringBefore("void startIncrementalWritebackWorker")
        assertTrue(loop.indexOf("const auto started = std::chrono::steady_clock::now()") <
            loop.indexOf("written = writeRecordAt("))
        assertTrue(native.contains("frameIndex == nextCommitFrameIndex_"))
        val writer = File(mainRoot, "cpp/npv10_aligned_record_writer.h").readText()
        val owned = writer.substringAfter("bool writeOwnedRecord(").substringBefore("// The engine joins")
        assertTrue(owned.contains("physicalEnd > reservationLimit"))
        assertTrue(owned.contains("file.st_size < physicalEnd"))
        val completeWrite = owned.indexOf("writeAlignedBytes(record, physical, baseOffset_, streamImmutableRecord)")
        assertTrue(completeWrite >= 0)
        assertTrue(completeWrite < owned.indexOf("ownedRecordWrites_.fetch_add"))
        assertFalse(owned.contains("std::memcpy(record + head, payload"))
    }

    @Test
    fun `immutable refill is limited to large owned packets and preserves all completion checks`() {
        val route = File(mainRoot, "cpp/npv10_realtime_aligned_writer.h").readText()
        assertTrue(route.contains("payloadBytes > Concurrent::kCapacity"))
        val aio = File(mainRoot, "cpp/npv10_kernel_aio.h").readText()
        assertTrue(aio.contains("bool writeImmutableStream("))
        assertTrue(aio.contains("ImmutableSource ? produced : slot * chunkCapacity"))
        assertTrue(aio.contains("event.data != state.requests[slot].aio_data"))
        assertTrue(aio.contains("event.res != static_cast<std::int64_t>(expected)"))
        assertTrue(aio.contains("if (active != 0 || persisted != count) return fail(EIO)"))
        assertTrue(aio.contains("std::size_t MaximumGroupBytes = 8u * 1024u * 1024u"))
        assertTrue(aio.contains("unsigned RequestDepth = 4u"))
    }

    @Test
    fun `streaming recovers queue pressure only after validated pending completion`() {
        val aio = File(mainRoot, "cpp/npv10_kernel_aio.h").readText()
        val stream = aio.substringAfter("bool writeStream(").substringBefore("bool writeImmutableStream(")
        assertTrue(stream.contains("submitted < 0 && errno == EAGAIN && state.pending != 0u"))
        val pressure = stream.substringAfter("errno == EAGAIN && state.pending != 0u")
            .substringBefore("if (submitted <= 0")
        assertTrue(pressure.indexOf("if (!reapStream(active, persisted)) return false;") >= 0)
        assertTrue(pressure.indexOf("reapStream(active, persisted)") < pressure.indexOf("continue;"))
        assertTrue(pressure.contains("submissionBackpressureEvents_.fetch_add"))
        assertTrue(stream.contains("partialSubmissions_.fetch_add"))
        assertTrue(stream.contains("if (active != 0 || persisted != count) return fail(EIO);"))
        val retire = aio.substringAfter("bool reapStream(").substringBefore("Admission reject(")
        assertTrue(retire.contains("state.pending == 0 || state.pending > kDepth"))
        assertTrue(retire.contains("event.obj != reinterpret_cast<std::uintptr_t>(&state.requests[slot])"))
        assertTrue(retire.contains("event.res != static_cast<std::int64_t>(expected) || event.res2 != 0"))
        assertTrue(retire.contains("return fail(completed < 0 ? errno : ETIMEDOUT)"))
        assertTrue(retire.indexOf("event.res !=") < retire.indexOf("persisted +="))
    }
}
