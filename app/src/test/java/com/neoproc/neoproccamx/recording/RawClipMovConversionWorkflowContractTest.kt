package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawClipMovConversionWorkflowContractTest {
    private val source = File(
        "src/main/java/com/neoproc/neoproccamx/recording/RawClipMovConversionWorkflow.kt"
    ).readText()

    @Test
    fun fallbackPublicationReadsBackAndHashVerifiesEveryCopiedArtifact() {
        assertTrue(source.contains("VerifiedArtifactCopy.copyAndMeasure"))
        assertTrue(
            source.contains(
                "VerifiedArtifactCopy.measure(input, ConversionCancellationContext::shouldContinue)"
            )
        )
        assertTrue(source.contains("VerifiedArtifactCopy.requireExact"))
        assertTrue(source.contains("check(sourceEvidence.bytes == source.length())"))
    }

    @Test
    fun companionLutAndCertificateArePublishedBeforeMovVisibility() {
        assertTrue(source.contains("MediaStore.Files.getContentUri(volume)"))
        assertTrue(source.contains("RecordingMediaStoreLifecyclePolicy.resolveMetadataSidecarRelativePath()"))
        assertTrue(source.contains("CUBE_LUT_MIME_TYPE = \"application/octet-stream\""))
        assertTrue(source.contains("NeoLog_to_Rec709.cube\", CUBE_LUT_MIME_TYPE"))
        assertTrue(source.contains("resolveCompanionArtifacts"))
        val direct = source.substringAfter("if (direct != null)").substringBefore("val transactionId")
        assertTrue(direct.indexOf("stageCompanions") < direct.indexOf("target.commit()"))
        assertTrue(direct.indexOf("forEach(::publishPendingStrict)") < direct.indexOf("target.commit()"))

        val fallback = source.substringAfter("private fun publishVerifiedTransaction")
            .substringBefore("private fun plannedLocalArtifacts")
        assertTrue(fallback.indexOf("stageAndVerify(convertedMov") < fallback.indexOf("publishPendingStrict(movUri)"))
        assertTrue(fallback.indexOf("stageCompanions") < fallback.indexOf("publishPendingStrict(movUri)"))
    }

    @Test
    fun pendingUpdatesAndRollbackAreFailClosedAndStagingNamesAreUnique() {
        assertTrue(source.contains("check(updatedRows > 0)"))
        assertTrue(source.contains("check(deletedRows > 0)"))
        assertTrue(source.contains("UUID.randomUUID()"))
        assertFalse(source.contains("File(stagingRoot, outputName)"))
    }
}
