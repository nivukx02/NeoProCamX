package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10CoordinatedReservationContractTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    @Test
    fun liveDmcAllocationUsesItsOwnedDescriptorAndTheRecordDurabilityBarrier() {
        val source = File(root, "cpp/npv10_dmc_native.cpp").readText()
        val allocation = source.substringAfter("bool allocatePhysicalRange(")
            .substringBefore("bool forceCommittedFrames(")
        assertTrue(allocation.contains("recordIoBarrier_.acquire()"))
        assertTrue(allocation.contains("lock, activeReservationAllocations_, processorStateChanged_"))
        assertTrue(allocation.contains("offset != physicalAllocationEndOffset_"))
        assertTrue(allocation.contains("!checkedAdd(offset, byteCount, &end)"))
        assertTrue(allocation.contains("::posix_fallocate64(descriptor_"))
        assertTrue(allocation.indexOf("if (failure != 0)") <
            allocation.indexOf("physicalAllocationEndOffset_ = end"))
        val shutdown = source.substringAfter("bool shutdown()").substringBefore("private:")
        assertTrue(shutdown.contains("activeReservationAllocations_ == 0u"))
    }

    @Test
    fun writerPublishesOnlySuccessfulPrefixesAndKeepsTheNonDmcPath() {
        val writer = File(root, "java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt").readText()
        val allocation = writer.substringAfter("val allocationProof = Npv10RollingExtentAllocator.allocate(")
            .substringBefore("check(allocationProof.confirmedEndBytes")
        assertTrue(allocation.contains("engine.allocatePhysicalRange(offsetBytes, lengthBytes)"))
        assertTrue(allocation.contains("Os.posix_fallocate(randomAccessFile.fd, offsetBytes, lengthBytes)"))
        assertTrue(allocation.indexOf("engine.allocatePhysicalRange") <
            allocation.indexOf("physicalReservedBytes = confirmedEndBytes"))
        assertTrue(allocation.contains("sliceLimitBytes = Npv10RollingExtentAllocator.LIVE_SLICE_BYTES"))
    }
}
