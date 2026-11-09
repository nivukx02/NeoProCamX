package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10ExtentProbeIsolationContractTest {
    private val source = File("src/main/cpp/npv10_dmc_native.cpp").readText()
    private val begin = source.substringAfter("bool beginFrame(").substringBefore("bool acquirePayload(")

    @Test fun filesystemExtentProbeDoesNotHoldWriterStateLock() {
        val scope = begin.indexOf("ScopedUnlockedIo extentProbe(")
        val stat = begin.indexOf("extentResult = ::fstat(descriptor_, &fileStatus)")
        assertTrue(scope >= 0 && stat > scope)
        assertTrue(begin.contains("lock, activeFileExtentProbes_, processorStateChanged_"))
        val helper = File("src/main/cpp/npv10_inflight_io.h").readText()
        assertTrue(helper.indexOf("++activeOperations_") < helper.indexOf("stateLock_.unlock()"))
        assertTrue(helper.indexOf("stateLock_.lock()") < helper.indexOf("--activeOperations_"))
        assertTrue(helper.contains("stateChanged_.notify_all()"))
    }

    @Test fun shutdownCannotCloseDescriptorDuringExtentProbe() {
        val shutdown = source.substringAfter("bool shutdown()").substringBefore("private:")
        val guard = shutdown.indexOf("activeProcessors_ == 0u && activeFileExtentProbes_ == 0u")
        val close = shutdown.indexOf("::close(descriptor_)")
        assertTrue(guard >= 0 && close > guard)
    }

    @Test fun admissionRechecksConcurrentLifecycleAndSlotAfterUnlockedIo() {
        val stat = begin.indexOf("extentResult = ::fstat(descriptor_, &fileStatus)")
        val revalidation = begin.indexOf("slot.state != SlotState::Empty || frameIndex < nextCommitFrameIndex_", stat)
        val claim = begin.indexOf("slot.state = SlotState::Claimed")
        assertTrue(stat >= 0 && revalidation > stat && claim > revalidation)
        assertTrue(begin.substring(stat, claim).contains("closed_ || closing_ || !recordWriterFailure_.empty()"))
        // Concurrent reservation growth is not a stale frame. The individual frame must still
        // fit its own reservation snapshot and fstat proof; only the shared high-water mark merges.
        assertFalse(begin.contains("reservedBytes < reservedBytes_"))
        assertTrue(begin.contains("reservedBytes_ = std::max(reservedBytes_, reservedBytes)"))
    }

    @Test fun extentSafetyAndOriginalErrnoRemainMandatory() {
        assertTrue(begin.contains("if (extentResult != 0) extentErrno = errno"))
        assertTrue(begin.contains("errno = extentErrno"))
        assertTrue(begin.contains("!S_ISREG(fileStatus.st_mode) || conservativeEnd > fileStatus.st_size"))
        assertTrue(begin.contains("conservativeEnd > reservedBytes"))
        assertTrue(begin.contains("alignedRecordWriter_->alignedEnd(conservativeEnd, &conservativeEnd)"))
    }

    @Test fun kotlinWriterReleasesCommitMonitorBeforeNativeAdmissionWait() {
        val writer = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt").readText()
        val admission = writer.substringAfter("internal fun beginDirectMappedFrame(")
            .substringBefore("internal fun abortDirectMappedFrame(")
        assertTrue(admission.contains("directMappedAdmissionFence.withAdmission"))
        assertTrue(admission.contains("val (engine, reservedForAdmission) = synchronized(this)"))
        val snapshotEnd = admission.indexOf("activeEngine to physicalReservedBytes\n        }")
        val nativeBegin = admission.indexOf("engine.beginFrame(frameIndex, reservedForAdmission)")
        assertTrue(snapshotEnd >= 0 && nativeBegin > snapshotEnd)
        assertFalse(writer.contains("@Synchronized\n    internal fun beginDirectMappedFrame("))
    }

    @Test fun bothCloseRoutesAcquireLifecycleFenceBeforeCommitMonitor() {
        val writer = File("src/main/java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt").readText()
        val close = writer.substringAfter("override fun close()")
            .substringBefore("private fun closeInternal(")
        assertTrue(Regex("directMappedAdmissionFence\\.withClose").findAll(close).count() == 2)
        assertTrue(Regex("withClose \\{\\s+synchronized\\(this\\)").findAll(close).count() == 2)
        assertFalse(close.contains("@Synchronized"))
        assertTrue(writer.contains("engine.awaitCommittedFrames(frameCount)"))
        assertTrue(writer.contains("channel.force(true)"))
    }
}
