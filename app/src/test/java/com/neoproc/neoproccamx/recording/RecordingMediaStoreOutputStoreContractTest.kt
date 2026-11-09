package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecordingMediaStoreOutputStoreContractTest {
    private val source = File("src/main/java/com/neoproc/neoproccamx/recording/RecordingMediaStoreOutputStore.kt").readText()

    @Test
    fun mediaStorePublisherWritesColorColumnsOnAndroidRAndAbove() {
        assertTrue(source.contains("MediaStore.Video.VideoColumns.COLOR_STANDARD"))
        assertTrue(source.contains("MediaStore.Video.VideoColumns.COLOR_TRANSFER"))
        assertTrue(source.contains("MediaStore.Video.VideoColumns.COLOR_RANGE"))
        assertTrue(source.contains("MediaFormat.COLOR_STANDARD_BT2020"))
        assertTrue(source.contains("MediaFormat.COLOR_TRANSFER_ST2084"))
        assertTrue(source.contains("MediaFormat.COLOR_TRANSFER_LINEAR"))
    }

    @Test
    fun mediaStorePublisherCreatesPendingSidecarAndCommitsAtomically() {
        assertTrue(source.contains("writeProfessionalSidecarMetadata"))
        assertTrue(source.contains("MediaStore.Files.getContentUri"))
        assertTrue(source.contains("MediaStore.MediaColumns.IS_PENDING, 1"))
        assertTrue(source.contains("MediaStore.MediaColumns.IS_PENDING, 0"))
        assertTrue(source.contains("application/json"))
        assertTrue(source.contains("cleanupFailure"))
    }

    @Test
    fun startupRecoveryPublishesOnlyStructurallyValidOwnedMovRows() {
        val appSource = File("src/main/java/com/neoproc/neoproccamx/NeoProCamXApp.kt").readText()
        val recoveryBlock = source
            .substringAfter("fun recoverPendingMovOutputsCreatedBefore")
            .substringBefore("fun create(")
        assertTrue(recoveryBlock.contains("OWNER_PACKAGE_NAME} = ?"))
        assertTrue(recoveryBlock.contains("IS_PENDING} = 1"))
        assertTrue(recoveryBlock.contains("ownedPendingMovStoredPathValues"))
        assertTrue(recoveryBlock.contains("RELATIVE_PATH} IN ("))
        assertTrue(recoveryBlock.contains("MIME_TYPE} = ?"))
        assertTrue(recoveryBlock.contains("DATE_ADDED} <= ?"))
        assertFalse(recoveryBlock.contains("RELATIVE_PATH} LIKE"))
        assertTrue(recoveryBlock.contains("MovRecoveryInspector.inspectPending(input.channel, size)"))
        assertTrue(recoveryBlock.contains("inspection.recoverable"))
        assertTrue(recoveryBlock.contains("READ_ONLY_FILE_DESCRIPTOR"))
        assertFalse(recoveryBlock.contains("isRecoverable(input.channel, size)"))
        assertTrue(recoveryBlock.contains("updated == 1"))
        assertTrue(recoveryBlock.contains("removed == 1"))
        assertTrue(recoveryBlock.contains("retaining"))
        assertTrue(appSource.contains("recoverPendingMovOutputsCreatedBefore"))
        assertTrue(appSource.contains("Dispatchers.IO"))
    }

    @Test
    fun finalizeRecoveryDeletesRowsThatStillFailDurabilityValidation() {
        val finalizeBlock = source
            .substringAfter("fun finalize(")
            .substringBefore("fun cleanupFailure(")

        assertTrue(finalizeBlock.contains("val recoveredEvidence = resolvedOutputEvidence(target)"))
        assertTrue(finalizeBlock.contains("if (recoveredEvidence.mediaStoreValidated) return recoveredEvidence"))
        assertTrue(finalizeBlock.contains("cleanupFailure(target)"))
        assertFalse(finalizeBlock.contains("takeIf { it.mediaStoreValidated }"))
    }

    @Test
    fun unrecoverableMainPublicationAlsoRemovesCommittedProfessionalSidecar() {
        val finalizeBlock = source
            .substringAfter("fun finalize(")
            .substringBefore("fun cleanupFailure(")
        val sidecarCapture = finalizeBlock.indexOf("var professionalSidecarUri: Uri? = null")
        val sidecarCleanup = finalizeBlock.indexOf(
            "cleanupProfessionalSidecarAfterFinalizationFailure(professionalSidecarUri)"
        )
        val mainCleanup = finalizeBlock.indexOf("cleanupFailure(target)", sidecarCleanup)
        val cleanupHelper = source
            .substringAfter("private fun cleanupProfessionalSidecarAfterFinalizationFailure(")
            .substringBefore("private fun ContentValues.putColorMetadataIfSupported")

        assertTrue(sidecarCapture >= 0)
        assertTrue(sidecarCleanup > sidecarCapture)
        assertTrue(mainCleanup > sidecarCleanup)
        assertTrue(cleanupHelper.contains("contentResolver.delete(sidecarUri, null, null)"))
        assertTrue(cleanupHelper.contains("sidecarUri.path?.let(::File)?.delete()"))
    }

    @Test
    fun descriptorOpenFailureRollsBackTheInsertedPendingRow() {
        val createBlock = source
            .substringAfter("private fun createMediaStoreTarget(")
            .substringBefore("private fun createLegacyFileTarget(")
        val insertIndex = createBlock.indexOf("contentResolver.insert")
        val openIndex = createBlock.indexOf("contentResolver.openFileDescriptor")
        val deleteIndex = createBlock.indexOf("contentResolver.delete(uri, null, null)")

        assertTrue(insertIndex >= 0)
        assertTrue(openIndex > insertIndex)
        assertTrue(deleteIndex > openIndex)
        assertTrue(createBlock.contains("catch (failure: Throwable)"))
        assertTrue(createBlock.contains("throw failure"))
    }
}
