package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordDevelopDirectMovWorkflowContractTest {
    private val workflowSource = File(
        "src/main/java/com/neoproc/neoproccamx/recording/RecordDevelopDirectMovWorkflow.kt"
    ).readText()
    private val converterSource = File(
        "src/main/java/com/neoproc/neoproccamx/recording/RecordDevelopMovConverter.kt"
    ).readText()
    private val rawWorkflowSource = File(
        "src/main/java/com/neoproc/neoproccamx/recording/RawClipMovConversionWorkflow.kt"
    ).readText()
    private val mediaStoreOutputSource = File(
        "src/main/java/com/neoproc/neoproccamx/recording/SeekableMediaStoreMovOutput.kt"
    ).readText()

    @Test
    fun directMediaStoreDescriptorIsBorrowedAcrossConversionAndPublication() {
        val borrowedOwnership = "MovOutputOwnership.BORROWED_SEEKABLE_DESCRIPTOR"
        assertTrue(workflowSource.split(borrowedOwnership).size - 1 >= 2)
        assertTrue(converterSource.contains("if (outputOwnership.converterMayDeleteOutput)"))
        assertTrue(converterSource.contains("cleanupConversionArtifacts(output, outputOwnership, failure)"))
        assertFalse(
            "The converter must never derive a certificate beside /proc/self/fd",
            workflowSource.contains("certificateSidecarFile(target.outputFile)")
        )
        assertTrue("Certificate identity must be per MediaStore row", workflowSource.contains("target.transactionId"))
    }

    @Test
    fun finalizedArtifactGateRunsBeforePendingMediaStoreCommit() {
        val inspection = workflowSource.indexOf("RecordDevelopMovPublicationGate.publishFinalized(")
        val commit = workflowSource.indexOf("target.commit()")
        val callback = workflowSource.indexOf("onPublished(uri.toString())")
        assertTrue("Final MOV inspection must exist", inspection >= 0)
        assertTrue("Final MOV inspection must finish before MediaStore commit", inspection < commit)
        assertTrue("MediaStore must commit before publication callback", commit < callback)
        assertTrue(workflowSource.contains("record_develop_publication"))
    }

    @Test
    fun failedUncommittedTransactionRemovesNormalCertificateAndLeavesDescriptorRollbackToCaller() {
        assertTrue(workflowSource.contains("var committed = false"))
        assertTrue(workflowSource.contains("if (!committed)"))
        assertTrue(workflowSource.contains("Failed to remove unpublished conversion certificate"))
        assertFalse(workflowSource.contains("target.outputFile.delete()"))
    }

    @Test
    fun rawDirectConversionAlsoBorrowsDescriptorAndUsesPerTransactionDiagnostics() {
        assertTrue(
            rawWorkflowSource.contains(
                "outputOwnership = MovOutputOwnership.BORROWED_SEEKABLE_DESCRIPTOR"
            )
        )
        assertTrue(rawWorkflowSource.contains("target.transactionId"))
    }

    @Test
    fun mediaStoreRollbackIsStrictInsteadOfSilentlySwallowingDeleteFailure() {
        assertTrue(mediaStoreOutputSource.contains("deletePendingRowStrict()"))
        assertTrue(mediaStoreOutputSource.contains("check(deletedRows > 0)"))
        assertTrue(mediaStoreOutputSource.contains("Failed to roll back pending MediaStore MOV"))
    }
}
