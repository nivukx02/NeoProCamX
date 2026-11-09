package com.neoproc.neoproccamx.recording

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CinemaCamPublicationIsolationContractTest {
    private fun source(area: String, name: String): String =
        File("src/main/java/com/neoproc/neoproccamx/$area/$name.kt").readText()

    @Test
    fun publisherUsesReadOnlyDiscoveryAndBothCopyAndVerificationAreGated() {
        val publisher = source("recording", "CinemaCamMtpPublisher")
        assertTrue(publisher.contains("listVerifiedMastersForPublication()"))
        assertFalse(publisher.contains("Npv10MasterStore(appContext.filesDir).listMasters()"))
        assertTrue(publisher.contains("TransactionalMediaStoreArtifactSet(resolver, backgroundIo = backgroundIo)"))
        assertTrue(publisher.contains("backgroundIo.backgroundChunk { reader.readNextPacket() }"))
        val transaction = source("recording", "TransactionalMediaStoreArtifactSet")
        assertTrue(transaction.contains("input, output, cancellation::shouldContinue, backgroundIo"))
        assertTrue(transaction.contains("VerifiedArtifactCopy.measure(input, cancellation::shouldContinue, backgroundIo)"))
        assertTrue(transaction.contains("VerifiedArtifactCopy.requireExact(sourceEvidence, destinationEvidence, label)"))
    }

    @Test
    fun readOnlyDiscoveryCannotRecoverOrTruncateALiveMaster() {
        val directory = java.nio.file.Files.createTempDirectory("npv10-publication-discovery-").toFile()
        try {
            val root = Npv10ContainerPolicy.masterDirectory(directory).apply { assertTrue(mkdirs()) }
            val unverified = File(root, "NPV10_active_4096x3072_25fps.npv10")
            val bytes = ByteArray(4_096) { (it * 13).toByte() }
            unverified.writeBytes(bytes)
            val originalModified = unverified.lastModified()
            assertTrue(Npv10MasterStore(directory).listVerifiedMastersForPublication().isEmpty())
            assertArrayEquals(bytes, unverified.readBytes())
            assertEquals(originalModified, unverified.lastModified())
            assertFalse(Npv10VerifiedMasterPublication.markerFor(unverified).exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun allRecordingStartsWaitBeforeBackendPreparationAndRetirementReleasesOnlyAfterProof() {
        val lifecycle = source("ui", "RecordingStopRestartLifecycle")
        assertEquals(3, Regex("beginStartWithPublicationIsolation\\(").findAll(lifecycle).count())
        assertTrue(lifecycle.contains("RecordingPublicationIoGate.process.acquireCapture()"))
        val completion = lifecycle.substringAfter("fun completeStart(").substringBefore("fun requestStop()")
        val inactiveLeaseRetirement = completion.indexOf("if (!backendActive) releasePublicationCaptureLease()")
        val startupAdmissionReopened = completion.indexOf("startupHandoff.completeStart(")
        assertTrue(inactiveLeaseRetirement >= 0)
        assertTrue(startupAdmissionReopened > inactiveLeaseRetirement)
        assertFalse(completion.substringAfter("beforeOwnerTransfer()").contains("releasePublicationCaptureLease()"))
        val viewModel = source("ui", "CameraViewModel")
        val startup = viewModel.substringAfter("private fun startRecordingInternal()")
        assertTrue(startup.indexOf("awaitPublicationIdleBeforeStart()") < startup.indexOf("awaitPrearmCompletion()"))
        assertTrue(viewModel.contains("recordingStopRestartLifecycle::releasePublicationIsolationAfterRetirement"))
        val retirement = source("ui", "NativeResourceRetirementCoordinator")
        assertTrue(retirement.contains("if (cleanupSafe && demosaicReleased) releaseRecordingIoIsolation()"))
        assertTrue(retirement.indexOf("if (!cameraRetirement.cameraRetired)") <
            retirement.indexOf("if (cleanupSafe && demosaicReleased) releaseRecordingIoIsolation()"))
    }
}
