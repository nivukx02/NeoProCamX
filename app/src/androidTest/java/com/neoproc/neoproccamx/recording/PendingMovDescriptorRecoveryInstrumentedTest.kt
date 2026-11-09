package com.neoproc.neoproccamx.recording

import android.content.ContentValues
import android.os.ParcelFileDescriptor.AutoCloseInputStream
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Real MediaStore descriptors, synthetic MOV atom fixtures; not camera/decoder quality evidence. */
@RunWith(AndroidJUnit4::class)
class PendingMovDescriptorRecoveryInstrumentedTest {
    @Test fun zeroMetadataSizeDoesNotInvalidateReadableMediaStoreFile() {
        inspectFixture(reportedBytes = 0L, truncate = false)
    }

    @Test fun staleTenGiBReservationDoesNotInvalidateReadableMediaStoreFile() {
        inspectFixture(reportedBytes = 10L * 1024 * 1024 * 1024, truncate = false)
    }

    @Test fun actuallyTruncatedFileIsNotPromotedByNonzeroMetadata() {
        inspectFixture(reportedBytes = 4096L, truncate = true)
    }

    private fun inspectFixture(reportedBytes: Long, truncate: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val complete = atom("ftyp", ByteArray(4)) + atom("mdat", ByteArray(12) { (it * 3 + 1).toByte() }) +
            atom("moov", ByteArray(8))
        val payload = if (truncate) complete.copyOf(complete.size - 2) else complete
        val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "LQ001_PENDING_STAT_${System.nanoTime()}.bin")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/CamX/QA/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }))
        try {
            requireNotNull(resolver.openOutputStream(uri, "w")).use { it.write(payload) }
            val inspection = AutoCloseInputStream(requireNotNull(resolver.openFileDescriptor(uri, "r"))).use {
                MovRecoveryInspector.inspectPending(it.channel, reportedBytes)
            }
            assertEquals(!truncate, inspection.recoverable)
            assertEquals(payload.size.toLong(), inspection.descriptorSizeBytes)
            assertEquals(reportedBytes, inspection.reportedSizeBytes)
            assertTrue(inspection.sizeMetadataStale)
            resolver.openInputStream(uri).use { input ->
                assertArrayEquals(payload, requireNotNull(input).readBytes())
            }
            println("LQ001_PENDING_DESCRIPTOR uri=$uri reportedBytes=$reportedBytes " +
                "actualBytes=${inspection.descriptorSizeBytes} recoverable=${inspection.recoverable} " +
                "sourceUnchanged=true fixtureOnly=true")
        } finally {
            // Only the exact row created by this test is removed, never another recording.
            assertEquals(1, resolver.delete(uri, null, null))
        }
    }

    private fun atom(type: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(8 + payload.size)
                output.write(type.toByteArray(Charsets.US_ASCII))
                output.write(payload)
            }
        }.toByteArray()
}
