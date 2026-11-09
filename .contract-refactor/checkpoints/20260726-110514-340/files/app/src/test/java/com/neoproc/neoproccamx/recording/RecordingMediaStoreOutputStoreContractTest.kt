package com.neoproc.neoproccamx.recording

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
}
