package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Npv10ActiveMasterProtectionContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt").readText()
    }

    @Test
    fun masterEnumerationAndStartShareOneLifecycleMonitor() {
        val listPrefix = source.substringBefore("fun listMasters()").takeLast(160)
        val startPrefix = source.substringBefore("fun start(config: Npv10SessionConfig)").takeLast(160)

        assertTrue(listPrefix.contains("@Synchronized"))
        assertTrue(startPrefix.contains("@Synchronized"))
        assertTrue(source.contains("store.listMasters(outputFile)"))
    }

    @Test
    fun adoptedMasterIsProtectedBeforeStorageOrWriterWorkBegins() {
        val start = source
            .substringAfter("fun start(config: Npv10SessionConfig): File {")
            .substringBefore("fun preparePhysicalReservationAsync()")
        val createIndex = start.indexOf("?: store.createMasterFile(config)")
        val protectIndex = start.indexOf("outputFile = file", startIndex = createIndex)
        val storageIndex = start.indexOf("reservePrivateStorageForMaster()")
        val writerIndex = start.indexOf("Npv10FileWriter(")

        assertTrue(createIndex >= 0)
        assertTrue(protectIndex > createIndex)
        assertTrue(storageIndex > protectIndex)
        assertTrue(writerIndex > protectIndex)
    }

    @Test
    fun everyPostAdoptionStartFailureRunsOneCompleteRollbackTransaction() {
        val start = source
            .substringAfter("fun start(config: Npv10SessionConfig): File {")
            .substringBefore("fun preparePhysicalReservationAsync()")
        val rollback = start
            .substringAfter("fun rollbackFailedStart() {")
            .substringBefore("\n        }")
        val catchIndex = start.lastIndexOf("catch (failure: Throwable)")
        val rollbackCallIndex = start.indexOf("rollbackFailedStart()", startIndex = catchIndex)

        assertTrue(catchIndex >= 0)
        assertTrue(rollbackCallIndex > catchIndex)
        assertTrue(rollback.contains("producerBoundaryGateRef.compareAndSet(gate, null)"))
        assertTrue(rollback.contains("gate.close()"))
        assertTrue(rollback.contains("writer = null"))
        assertTrue(rollback.contains("activeConfig = null"))
        assertTrue(rollback.contains("releaseIdleP010PacketBuffers()"))
        assertTrue(rollback.contains("startupWriter?.close()"))
        assertTrue(rollback.contains("outputFile = null"))
        assertTrue(rollback.contains("Npv10VerifiedMasterPublication.invalidate(file)"))
        assertTrue(rollback.contains("file.delete()"))
    }
}
