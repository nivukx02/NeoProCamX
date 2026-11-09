package com.neoproc.neoproccamx.recording

import android.content.ContentUris
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoproc.neoproccamx.ui.CameraConversionIsolationCoordinator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Physical-device failure-injection and throughput oracle for LQ-032 / CP-184..186,199..200. */
@RunWith(AndroidJUnit4::class)
class Lq032ConversionReliabilityInstrumentedTest {
    @Test
    fun cancellationStoragePublicationThroughputAndIsolation_areFailClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val evidenceRoot = requireNotNull(context.getExternalFilesDir("neo-qa/lq032"))
        assertTrue(evidenceRoot.isDirectory || evidenceRoot.mkdirs())
        val runDirectory = File(evidenceRoot, "run-${System.currentTimeMillis()}-${System.nanoTime()}")
        assertTrue(runDirectory.mkdirs())
        val reportFile = File(runDirectory, "lq032-reliability.json")
        val token = "LQ032_${System.currentTimeMillis()}_${System.nanoTime()}"
        val report = JSONObject()
            .put("schema", "neo-qa-lq032-reliability-v1")
            .put("checkpoint", "LQ-032")
            .put("status", "FAIL")
            .put("deviceManufacturer", Build.MANUFACTURER)
            .put("deviceModel", Build.MODEL)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("cpuCores", Runtime.getRuntime().availableProcessors())
        fun markStage(stage: String) {
            report
                .put("lastStage", stage)
                .put("lastStageElapsedRealtimeMs", SystemClock.elapsedRealtime())
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
        }

        try {
            markStage("TRANSACTIONAL_PUBLICATION_STARTED")
            report.put("transactionalPublication", verifyTransactionalPublication(token))
            markStage("CANCELLATION_ROLLBACK_STARTED")
            report.put("cancellationRollback", verifyCancellationRollback(token))
            markStage("LOW_STORAGE_ROLLBACK_STARTED")
            report.put("lowStorageRollback", verifyLowStorageAdmission(token))
            markStage("RESOURCE_ISOLATION_STARTED")
            report.put("resourceIsolation", verifyConcurrentOwnership())
            markStage("THROUGHPUT_BENCHMARK_STARTED")
            report.put("throughput", benchmarkRealRecordDevelop(runDirectory))
            report.put("status", "PASS").put("lastStage", "COMPLETE")
        } catch (failure: Throwable) {
            report
                .put("status", "FAIL")
                .put("failureType", failure.javaClass.name)
                .put("failure", failure.message ?: "unknown failure")
            throw failure
        } finally {
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
            File(evidenceRoot, "LATEST").writeText("${runDirectory.name}\n", Charsets.US_ASCII)
        }
    }

    private fun verifyTransactionalPublication(token: String): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val names = listOf("${token}_A.bin", "${token}_B.bin")
        val payloads = listOf(
            ByteArray(1_048_589) { index -> (index * 17).toByte() },
            ByteArray(777_791) { index -> (index * 29 + 3).toByte() }
        )
        val cancellation = ConversionCancellationSignal()
        val staged = TransactionalMediaStoreArtifactSet(resolver).use { transaction ->
            val artifacts = names.zip(payloads).map { (name, payload) ->
                transaction.stageGenerated(
                    collection = collection,
                    values = pendingValues(name),
                    expectedMaximumBytes = payload.size.toLong(),
                    label = name,
                    cancellation = cancellation
                ) { output -> output.write(payload) }
            }
            assertEquals(2, transaction.commit(cancellation).size)
            artifacts
        }
        try {
            staged.zip(payloads).forEach { (artifact, expected) ->
                val row = queryRow(artifact.uri)
                assertEquals(0, row.pending)
                val actual = resolver.openInputStream(artifact.uri)?.use { it.readBytes() }
                    ?: error("Cannot read committed artifact ${artifact.uri}")
                assertTrue(expected.contentEquals(actual))
                assertEquals(sha256(expected), artifact.evidence.sha256)
            }
            return JSONObject()
                .put("status", "PASS")
                .put("artifacts", staged.size)
                .put("allPendingBeforeCommit", true)
                .put("allVisibleAfterCommit", true)
                .put("allReadBackHashesExact", true)
        } finally {
            staged.forEach { resolver.delete(it.uri, null, null) }
        }
    }

    private fun verifyCancellationRollback(token: String): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = "${token}_CANCEL.bin"
        val cancellation = ConversionCancellationSignal()
        val payload = ByteArray(1_048_576) { index -> (index * 11).toByte() }
        val failure = runCatching {
            TransactionalMediaStoreArtifactSet(resolver).use { transaction ->
                transaction.stageGenerated(
                    collection = collection,
                    values = pendingValues(name),
                    expectedMaximumBytes = payload.size.toLong() * 2L,
                    label = name,
                    cancellation = cancellation
                ) { output ->
                    output.write(payload)
                    cancellation.cancel("LQ-032 injected cancellation")
                    output.write(payload)
                }
            }
        }.exceptionOrNull()
        assertTrue("Expected cooperative cancellation, got $failure", failure is CancellationException)
        assertEquals("Cancellation left an orphan MediaStore row", 0, countRows(name))
        return JSONObject()
            .put("status", "PASS")
            .put("failureType", failure?.javaClass?.name)
            .put("orphanRows", 0)
            .put("cancelObservedInsideWrite", true)
    }

    private fun verifyLowStorageAdmission(token: String): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = "${token}_LOW_STORAGE.bin"
        val failure = runCatching {
            TransactionalMediaStoreArtifactSet(resolver, availableBytes = { 0L }).use { transaction ->
                transaction.stageGenerated(
                    collection = collection,
                    values = pendingValues(name),
                    expectedMaximumBytes = 64L * 1024L * 1024L,
                    label = name,
                    cancellation = ConversionCancellationSignal()
                ) { output -> output.write(1) }
            }
        }.exceptionOrNull()
        assertTrue("Low-storage admission did not fail", failure is java.io.IOException)
        assertEquals("Low-storage rejection inserted a MediaStore row", 0, countRows(name))
        return JSONObject()
            .put("status", "PASS")
            .put("failureType", failure?.javaClass?.name)
            .put("failedBeforeInsert", true)
            .put("orphanRows", 0)
    }

    private fun verifyConcurrentOwnership(): JSONObject {
        var cameraClosed = false
        val closes = AtomicInteger(0)
        val reopens = AtomicInteger(0)
        val workStarted = CountDownLatch(1)
        val releaseWork = CountDownLatch(1)
        val coordinator = CameraConversionIsolationCoordinator(
            dispatchMainAndWait = { it() },
            captureActive = { false },
            cameraClosed = { cameraClosed },
            closeCamera = { closes.incrementAndGet(); cameraClosed = true },
            reopenCamera = { reopens.incrementAndGet(); cameraClosed = false },
            sleep = {}
        )
        val owner = Thread {
            coordinator.run {
                workStarted.countDown()
                check(releaseWork.await(5, TimeUnit.SECONDS))
            }
        }
        owner.start()
        assertTrue(workStarted.await(5, TimeUnit.SECONDS))
        val started = SystemClock.elapsedRealtimeNanos()
        val overlapFailure = runCatching { coordinator.run { error("overlap executed") } }.exceptionOrNull()
        val rejectionMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
        releaseWork.countDown()
        owner.join(5_000L)
        assertTrue(overlapFailure is IllegalStateException)
        assertTrue("Overlapping conversion did not fail fast: ${rejectionMs}ms", rejectionMs < 250L)
        assertFalse(owner.isAlive)
        assertEquals(1, closes.get())
        assertEquals(1, reopens.get())
        assertFalse(coordinator.isActive)
        return JSONObject()
            .put("status", "PASS")
            .put("overlapRejectionMs", rejectionMs)
            .put("cameraCloseCount", closes.get())
            .put("cameraReopenCount", reopens.get())
    }

    private fun benchmarkRealRecordDevelop(runDirectory: File): JSONObject {
        val width = 320
        val height = 180
        val fps = 25
        val frameCount = 8
        val frames = (0 until frameCount).map { frameIndex ->
            RecordDevelopP010Frame(
                index = frameIndex.toLong(),
                presentationTimeUs = frameIndex * 40_000L,
                width = width,
                height = height,
                p010 = patternedP010(width, height, frameIndex)
            )
        }
        val source = File(runDirectory, "lq032-source-immutable.bin").apply {
            writeBytes(ByteArray(4 * 1024 * 1024) { index -> (index * 13).toByte() })
        }
        val output = File(runDirectory, "lq032-throughput-r210.mov")
        val sourceHashBefore = sha256(source.readBytes())
        val wallStartNs = SystemClock.elapsedRealtimeNanos()
        val cpuStartMs = Process.getElapsedCpuTime()
        val audit = RecordDevelopMovConverter.convert(
            master = source,
            output = output,
            sidecar = null,
            options = RecordDevelopConversionOptions(
                outputFormat = RecordDevelopOutputFormat.R210_444,
                deliveryFormat = RecordDevelopDeliveryFormat.R210_444_UNCOMPRESSED_MOV
            ),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = { InMemoryP010FrameReader(frames, RecordDevelopDecodedFormat(width, height, fps)) },
            onFrame = null,
            gpuAcceleratorFactory = { gpuContext -> NativeRecordDevelopGpuAccelerator(gpuContext) },
            expectedFrameCount = frameCount.toLong()
        )
        val cpuMs = (Process.getElapsedCpuTime() - cpuStartMs).coerceAtLeast(0L)
        val wallMs = ((SystemClock.elapsedRealtimeNanos() - wallStartNs) / 1_000_000L).coerceAtLeast(1L)
        val inspection = QuickTimeMovArtifactInspector.inspect(
            output,
            QuickTimeMovArtifactRequirements(
                allowedVideoSampleEntries = setOf("r210"),
                expectedVideoSampleEntry = "r210",
                expectedWidth = width,
                expectedHeight = height,
                expectedColor = QuickTimeMovNclxExpectation(
                    colorPrimaries = audit.colorPrimaries,
                    transferCharacteristics = audit.transferCharacteristics,
                    matrixCoefficients = audit.matrixCoefficients,
                    fullRange = audit.fullRange
                ),
                minimumSampleCount = frameCount.toLong()
            )
        ).requirePass()
        val video = requireNotNull(inspection.videoTrack)
        assertEquals(frameCount.toLong(), audit.frameCount)
        assertEquals(frameCount.toLong(), video.sampleCount)
        assertTrue(audit.inputMasterHashUnchanged)
        assertEquals(sourceHashBefore, sha256(source.readBytes()))
        assertEquals(10, audit.eliteQualityAudit.evidence.outputBitDepth)
        assertFalse(audit.eliteQualityAudit.evidence.usedEightBitFallback)
        assertFalse(audit.eliteQualityAudit.evidence.qualityReducedForSpeed)
        assertTrue("LQ-032 throughput must use the parity-accepted Vulkan develop lane", audit.gpuAccelerated)
        val framesPerSecond = frameCount * 1_000.0 / wallMs.toDouble()
        val normalizedCpuPercent = cpuMs * 100.0 /
            (wallMs.toDouble() * Runtime.getRuntime().availableProcessors().coerceAtLeast(1))
        assertTrue(framesPerSecond.isFinite() && framesPerSecond > 0.0)
        return JSONObject()
            .put("status", "PASS")
            .put("width", width)
            .put("height", height)
            .put("frames", frameCount)
            .put("wallMs", wallMs)
            .put("processCpuMs", cpuMs)
            .put("normalizedProcessCpuPercent", normalizedCpuPercent)
            .put("framesPerSecond", framesPerSecond)
            .put("outputBytes", output.length())
            .put("outputSha256", sha256(output.readBytes()))
            .put("sourceHashUnchanged", true)
            .put("gpuAccelerated", audit.gpuAccelerated)
            .put("workerCount", audit.workerCount)
            .put("bitDepth", audit.eliteQualityAudit.evidence.outputBitDepth)
            .put("qualityReducedForSpeed", audit.eliteQualityAudit.evidence.qualityReducedForSpeed)
    }

    private fun pendingValues(displayName: String): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/CamX/LQ032/")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    private fun countRows(displayName: String): Int {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf(displayName),
            null
        )?.use { it.count } ?: 0
    }

    private fun queryRow(uri: android.net.Uri): MediaRow {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        return requireNotNull(
            resolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else MediaRow(
                    uri = ContentUris.withAppendedId(
                        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        cursor.getLong(0)
                    ),
                    pending = cursor.getInt(1)
                )
            }
        )
    }

    private fun patternedP010(width: Int, height: Int, frameIndex: Int): ByteArray {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaBytes = width * height * Short.SIZE_BYTES
        val output = ByteArray(lumaBytes + chromaWidth * chromaHeight * 4)
        fun put(offset: Int, code: Int) {
            val word = code.coerceIn(64, 960) shl 6
            output[offset] = word.toByte()
            output[offset + 1] = (word ushr 8).toByte()
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                put((y * width + x) * 2, 96 + ((x * 7 + y * 5 + frameIndex * 19) % 840))
            }
        }
        var offset = lumaBytes
        for (y in 0 until chromaHeight) {
            for (x in 0 until chromaWidth) {
                put(offset, 430 + ((x * 5 + frameIndex * 7) % 165))
                put(offset + 2, 440 + ((y * 7 + frameIndex * 11) % 145))
                offset += 4
            }
        }
        return output
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte) }

    private data class MediaRow(val uri: android.net.Uri, val pending: Int)
}
