package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the lifecycle semantics of the record-then-develop [RecordingOutputStorageKind.PRIVATE_DEVELOP_MASTER]:
 * on failure the private intermediate File is deleted (so no partial/0-byte clip is surfaced to the develop
 * card), finalize performs no MediaStore publish / MediaScanner scan, and the two public kinds are unaffected
 * by the new decision field.
 */
class RecordDevelopStorageKindCleanupTest {

    @Test
    fun privateDevelopMasterRequestsFileDeletionOnFailure() {
        val decision = RecordingMediaStoreLifecyclePolicy.resolveFailureCleanup(
            storageKind = RecordingOutputStorageKind.PRIVATE_DEVELOP_MASTER,
            hasUri = false,
            hasFile = true
        )
        assertTrue(decision.deletePrivateDevelopFile)
        assertFalse(decision.deleteMediaStoreUri)
        assertFalse(decision.deleteLegacyFile)
    }

    @Test
    fun privateDevelopMasterNoDeletionWhenNoFile() {
        val decision = RecordingMediaStoreLifecyclePolicy.resolveFailureCleanup(
            storageKind = RecordingOutputStorageKind.PRIVATE_DEVELOP_MASTER,
            hasUri = false,
            hasFile = false
        )
        assertFalse(decision.deletePrivateDevelopFile)
    }

    @Test
    fun privateDevelopMasterFinalizeNeverPublishesOrScans() {
        val decision = RecordingMediaStoreLifecyclePolicy.resolveFinalize(
            storageKind = RecordingOutputStorageKind.PRIVATE_DEVELOP_MASTER,
            pending = false,
            finalDescription = ""
        )
        assertFalse(decision.publishMediaStore)
        assertFalse(decision.scanLegacyFile)
    }

    @Test
    fun publicKindsAreUnaffectedByTheNewPrivateField() {
        val media = RecordingMediaStoreLifecyclePolicy.resolveFailureCleanup(
            storageKind = RecordingOutputStorageKind.MEDIASTORE_PENDING_VIDEO,
            hasUri = true,
            hasFile = false
        )
        assertTrue(media.deleteMediaStoreUri)
        assertFalse(media.deletePrivateDevelopFile)

        val legacy = RecordingMediaStoreLifecyclePolicy.resolveFailureCleanup(
            storageKind = RecordingOutputStorageKind.LEGACY_PUBLIC_FILE,
            hasUri = false,
            hasFile = true
        )
        assertTrue(legacy.deleteLegacyFile)
        assertFalse(legacy.deletePrivateDevelopFile)
    }
}
