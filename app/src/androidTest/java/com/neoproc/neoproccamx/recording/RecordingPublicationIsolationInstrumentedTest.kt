package com.neoproc.neoproccamx.recording

import android.content.ContentValues
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Actual MediaStore/file I/O; synthetic payloads are not camera-FPS or image-quality evidence. */
@RunWith(AndroidJUnit4::class)
class RecordingPublicationIsolationInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver
    private val collection get() = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Test
    fun capturePausesPublicCopyThenResumesWithExactSourceAndReadBackHashes() {
        runPublication(cancelWhilePaused = false)
    }

    @Test
    fun cancellationWhileCaptureOwnsStorageRollsBackPendingRowWithoutDamagingSource() {
        runPublication(cancelWhilePaused = true)
    }

    @Test
    fun backgroundDiscoveryNeverChangesAnUnverifiedActiveMaster() {
        val root = File(context.cacheDir, "lq001-readonly-${System.nanoTime()}")
        check(root.mkdirs())
        try {
            val masterDirectory = Npv10ContainerPolicy.masterDirectory(root).apply { check(mkdirs()) }
            val activeMaster = File(masterDirectory, "NPV10_active_4096x3072_25fps.npv10")
            val data = ByteArray(4_097) { (it * 19 + 3).toByte() }
            activeMaster.writeBytes(data)
            val modified = activeMaster.lastModified()
            assertTrue(Npv10MasterStore(root).listVerifiedMastersForPublication().isEmpty())
            assertArrayEquals(data, activeMaster.readBytes())
            assertEquals(modified, activeMaster.lastModified())
            assertFalse(Npv10VerifiedMasterPublication.markerFor(activeMaster).exists())
        } finally {
            check(root.deleteRecursively())
        }
    }

    private fun runPublication(cancelWhilePaused: Boolean) {
        val gate = RecordingPublicationIoGate()
        val capture = gate.acquireCapture()
        val cancellation = ConversionCancellationSignal()
        val worker = Executors.newSingleThreadExecutor()
        val name = "LQ001_IO_${System.currentTimeMillis()}_${System.nanoTime()}.bin"
        val source = File(context.cacheDir, name)
        val payload = ByteArray(2 * RecordingPublicationIoGate.MAX_BACKGROUND_CHUNK_BYTES + 31) {
            (it * 23 + 7).toByte()
        }
        source.writeBytes(payload)
        val original = source.inputStream().use { VerifiedArtifactCopy.measure(it) }
        try {
            val future = worker.submit<TransactionalMediaStoreArtifactSet.StagedArtifact> {
                TransactionalMediaStoreArtifactSet(resolver, backgroundIo = gate).use { transaction ->
                    val staged = transaction.stageFile(
                        collection = collection,
                        values = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/CamX/QA/")
                        },
                        source = source,
                        label = "LQ-001 capture-priority publication fixture",
                        cancellation = cancellation
                    )
                    assertEquals(1, transaction.commit(cancellation).size)
                    staged
                }
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (gate.snapshot().pausedBackgroundChunks == 0L && !future.isDone &&
                SystemClock.elapsedRealtime() < deadline
            ) SystemClock.sleep(10L)
            assertTrue("No paused publication observed: ${gate.snapshot()}", gate.snapshot().pausedBackgroundChunks > 0L)
            assertTrue(capture.awaitBackgroundIdle(1_000L))
            assertEquals(0, gate.snapshot().activeBackgroundChunks)
            assertFalse("Publication completed while capture owned storage", future.isDone)
            val pausedRows = rowsFor(name)
            assertEquals(1, pausedRows.size)
            assertEquals(1, pausedRows.single().pending)
            assertEquals(0L, pausedRows.single().bytes)

            if (cancelWhilePaused) {
                cancellation.cancel("LQ-001 injected cancellation while paused")
                val failure = runCatching { future.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
                assertTrue("Unexpected failure: $failure", failure is ExecutionException &&
                    failure.cause is CancellationException)
                assertTrue("Cancellation left a pending row", rowsFor(name).isEmpty())
                assertEquals(1, gate.snapshot().captureOwners)
            } else {
                capture.close()
                val staged = future.get(15, TimeUnit.SECONDS)
                VerifiedArtifactCopy.requireExact(original, staged.evidence, "real MediaStore publication")
                val actual = resolver.openInputStream(staged.uri)?.use { it.readBytes() }
                    ?: error("Published artifact is unreadable")
                assertArrayEquals(payload, actual)
                assertEquals(0, rowsFor(name).single().pending)
            }
            source.inputStream().use {
                VerifiedArtifactCopy.requireExact(original, VerifiedArtifactCopy.measure(it), "unchanged private source")
            }
            assertEquals(0, gate.snapshot().activeBackgroundChunks)
        } finally {
            cancellation.cancel("fixture cleanup")
            capture.close()
            worker.shutdownNow()
            check(worker.awaitTermination(10, TimeUnit.SECONDS)) { "Publication fixture worker did not retire" }
            rowsFor(name).forEach { check(resolver.delete(it.uri, null, null) == 1) }
            check(source.delete())
        }
    }

    private data class Row(val uri: Uri, val pending: Int, val bytes: Long)

    private fun rowsFor(name: String): List<Row> = buildList {
        OwnedPendingMediaStoreQuery.query(
            resolver, collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE),
            context.packageName,
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(name)
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                add(Row(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)),
                    cursor.getInt(1), cursor.getLong(2)))
            }
        }
    }
}
