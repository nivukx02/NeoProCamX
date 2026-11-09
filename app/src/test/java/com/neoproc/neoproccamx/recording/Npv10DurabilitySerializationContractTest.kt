package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10DurabilitySerializationContractTest {
    private val mainRoot get() = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private fun source(path: String) = File(mainRoot, path).readText()

    @Test fun `blocking durability dependency shares writer io priority with scoped restoration`() {
        val native = source("cpp/npv10_dmc_native.cpp")
        val force = native.substringAfter("bool forceCommittedFrames(")
            .substringBefore("void snapshotIncrementalWriteback(")
        val priorityIndex = force.indexOf("ScopedRecordIoPriority<> durabilityPriority")
        assertTrue(priorityIndex >= 0)
        assertTrue(force.indexOf("ScopedUnlockedIo retainedDescriptor") < priorityIndex)
        assertTrue(priorityIndex < force.indexOf("recordIoBarrier_.acquire()"))
        assertTrue(force.contains("durabilityPriority.applied()"))
        assertTrue(force.contains("durabilityPriority.error()"))
        assertTrue(force.contains("scope=owned_force_call durability=unchanged"))
        val priority = source("cpp/npv10_record_io_priority.h")
        assertTrue(priority.contains("~ScopedRecordIoPriority() { (void)restore(); }"))
        assertTrue(priority.contains("Io::setCurrent(prior_)"))
        assertTrue(force.contains("while (result < 0 && errno == EINTR)"))
        assertTrue(force.contains("if (result != 0)"))
    }

    @Test fun `flush and records share one barrier without excluding wait from the write timer`() {
        val native = source("cpp/npv10_dmc_native.cpp")
        val worker = native.substringAfter("void recordWriterWorkerLoop()")
            .substringBefore("void startIncrementalWritebackWorker()")
        assertTrue(worker.indexOf("const auto started = std::chrono::steady_clock::now()") <
            worker.indexOf("written = writeRecordAt("))
        val record = native.substringAfter("bool writeRecordAt(")
            .substringBefore("void noteCommittedRangeForIncrementalWriteback(")
        assertTrue(record.indexOf("recordIoBarrier_.acquire()") <
            record.indexOf("alignedRecordWriter_->writeRecord("))
        val force = native.substringAfter("bool forceCommittedFrames(")
            .substringBefore("void snapshotIncrementalWriteback(")
        assertTrue(force.contains("if (!awaitCommittedFrames(requiredFrames)) return false;"))
        assertTrue(force.indexOf("recordIoBarrier_.acquire()") < force.indexOf("::fdatasync(descriptor_)"))
        assertTrue(force.contains("while (result < 0 && errno == EINTR)"))
        assertTrue(force.contains("if (result != 0)"))
    }

    @Test fun `shutdown cannot close the file during a force or its lock wait`() {
        val native = source("cpp/npv10_dmc_native.cpp")
        val force = native.substringAfter("bool forceCommittedFrames(")
            .substringBefore("void snapshotIncrementalWriteback(")
        assertTrue(force.contains("closed_ || closing_ || descriptor_ < 0"))
        assertTrue(force.indexOf("ScopedUnlockedIo retainedDescriptor") <
            force.indexOf("recordIoBarrier_.acquire()"))
        assertTrue(force.contains("lock, activeDurabilityForces_, processorStateChanged_"))
        val close = native.substringAfter("bool shutdown() noexcept")
            .substringBefore("private:")
        assertTrue(close.indexOf("activeDurabilityForces_ == 0u") < close.indexOf("::close(descriptor_)"))
    }

    @Test fun `first periodic and final durability acknowledgements remain after successful IO`() {
        val writer = source("java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt")
        val force = writer.substringAfter("private fun drainDurabilityCheckpointRequests()")
            .substringBefore("internal fun firstDurableFrameResult()")
        assertTrue(force.contains("engine.forceCommittedFrames(requestedFrame)"))
        assertTrue(force.contains("else durabilityChannel.force(false)"))
        assertTrue(force.indexOf("engine.forceCommittedFrames(requestedFrame)") <
            force.indexOf("firstDurableFrameProof::complete"))
        assertTrue(force.contains("durabilityCheckpointFailure = failure"))
        assertTrue(writer.contains("frameCount - lastDurabilityCheckpointFrame < FLUSH_FRAME_INTERVAL"))
        assertTrue(writer.contains("channel.force(true)"))
    }

    @Test fun `JNI failure is propagated and future frame boundaries are rejected`() {
        val wrapper = source("java/com/neoproc/neoproccamx/recording/Npv10DirectMappedCommitEngine.kt")
        val force = wrapper.substringAfter("fun forceCommittedFrames(")
            .substringBefore("fun snapshot()")
        assertTrue(force.contains("requiredFrames >= 0L && requiredFrames <= framesCommitted.get()"))
        assertTrue(force.contains("requireOpenHandle()"))
        assertTrue(force.contains("nativeForceCommittedFrames(handle, requiredFrames)"))
        assertTrue(force.contains("throw nativeFailure("))
    }
}
