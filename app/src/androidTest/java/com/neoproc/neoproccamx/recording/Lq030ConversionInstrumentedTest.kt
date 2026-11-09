package com.neoproc.neoproccamx.recording

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoproc.neoproccamx.capture.DngTiffReader
import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.niv99.Niv99OfflineConversionPolicy
import com.neoproc.neoproccamx.niv99.Niv99RawConversionProcessor
import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.RawDevelopEliteDetailFlag
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorSpace
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorTransfer
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.VideoDevelopColorMetadata
import com.neoproc.neoproccamx.snce.SnceIlluminantEstimator
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

/**
 * Physical-device conversion oracle for LQ-030 / CP-171..176.
 *
 * The test retains every source, output master, companion certificate, LUT, and a machine-readable
 * report under the app's external-files directory so the exclusive ADB controller can pull and inspect
 * the exact candidate artifacts. It exercises production conversion code, never mutates a source master,
 * and rejects rather than substituting an 8-bit or lower-quality transport.
 */
@RunWith(AndroidJUnit4::class)
class Lq030ConversionInstrumentedTest {
    private companion object {
        const val WIDTH = 96 // divisible by the v210 six-pixel packing group
        const val HEIGHT = 64
        const val FPS = 25
        const val FRAME_COUNT = 4
        const val FRAME_DURATION_US = 40_000L
        const val SENSOR_TIMESTAMP_ORIGIN_NS = 7_000_000_000L
        const val TEST_WAKE_LOCK_TIMEOUT_MS = 30L * 60L * 1_000L
    }

    /**
     * Publishes the deterministic LQ-030 RAW master through the same scoped-storage contract used by
     * production recording. This is deliberately a fixture-only transaction: the subsequent test is
     * driven through the real Settings UI, so conversion actions, progress, publication, certificates,
     * and source retention are exercised by production code instead of being called by instrumentation.
     */
    @Test
    fun publishProductionUiRawFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val fixtureRoot = requireNotNull(context.getExternalFilesDir("neo-qa/lq030-ui-fixture")) {
            "App-scoped LQ-030 UI-fixture evidence directory is unavailable"
        }
        assertTrue("Unable to create LQ-030 UI-fixture evidence directory", fixtureRoot.isDirectory || fixtureRoot.mkdirs())

        val token = "${System.currentTimeMillis()}-${System.nanoTime()}"
        val clipName = "NEO_RADIANCE_RAW_LQ030_UI_$token"
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/CamX/$clipName/"
        val rawMeta = rawMetadata()
        val metadataBytes = RawDevelopMetadataSidecar.encode(rawMeta)
        val rawBytes = ByteArrayOutputStream().use { output ->
            repeat(FRAME_COUNT) { frameIndex ->
                output.write(
                    NprawFrameCodec.encodeFrame(
                        mode = RawCompressionMode.HIGH_COMPRESSION,
                        rawSamples = syntheticRawSamples(frameIndex),
                        width = WIDTH,
                        height = HEIGHT,
                        rowStride = WIDTH * Short.SIZE_BYTES,
                        pixelStride = Short.SIZE_BYTES,
                        timestamp = SENSOR_TIMESTAMP_ORIGIN_NS + frameIndex * FRAME_DURATION_US * 1_000L,
                        originalSize = WIDTH * HEIGHT * Short.SIZE_BYTES,
                        sensorWhiteLevel = rawMeta.sensorWhiteLevel
                    ).bytes
                )
            }
            output.toByteArray()
        }

        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val createdUris = ArrayList<android.net.Uri>(2)
        try {
            fun insertAndWrite(displayName: String, payload: ByteArray): android.net.Uri {
                val nowSeconds = System.currentTimeMillis() / 1_000L
                val uri = resolver.insert(
                    collection,
                    ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.DATE_ADDED, nowSeconds)
                        put(MediaStore.MediaColumns.DATE_MODIFIED, nowSeconds)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                ) ?: error("Unable to create LQ-030 UI fixture $displayName")
                createdUris += uri
                resolver.openOutputStream(uri, "w")?.use { it.write(payload) }
                    ?: error("Unable to write LQ-030 UI fixture $displayName")
                return uri
            }

            val streamUri = insertAndWrite(RawClipMediaStoreDiscovery.STREAM_NAME, rawBytes)
            val metadataUri = insertAndWrite(RawClipMediaStoreDiscovery.META_NAME, metadataBytes)
            createdUris.forEach { uri ->
                val updated = resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                assertTrue("MediaStore did not publish LQ-030 UI fixture $uri", updated > 0)
            }

            val discovered = RawClipMediaStoreDiscovery(context).listClips()
                .singleOrNull { it.name == clipName && it.relativePath.trimEnd('/') == relativePath.trimEnd('/') }
            requireNotNull(discovered) { "Production RAW converter did not discover the published UI fixture" }
            assertTrue("Published LQ-030 UI fixture remained pending", !discovered.streamPending)
            assertEquals(rawBytes.size.toLong(), discovered.streamBytes)

            val evidence = JSONObject()
                .put("schema", "neo-qa-lq030-production-ui-fixture-v1")
                .put("checkpoint", "LQ-030")
                .put("status", "PASS")
                .put("deviceManufacturer", Build.MANUFACTURER)
                .put("deviceModel", Build.MODEL)
                .put("androidRelease", Build.VERSION.RELEASE)
                .put("androidSdk", Build.VERSION.SDK_INT)
                .put("clipName", clipName)
                .put("relativePath", relativePath)
                .put("streamUri", streamUri.toString())
                .put("metadataUri", metadataUri.toString())
                .put("streamBytes", rawBytes.size)
                .put("streamSha256", sha256(rawBytes))
                .put("metadataBytes", metadataBytes.size)
                .put("metadataSha256", sha256(metadataBytes))
                .put("width", WIDTH)
                .put("height", HEIGHT)
                .put("fps", FPS)
                .put("frameCount", FRAME_COUNT)
                .put("expectedNeoLogMov", "${clipName}_neolog_444.mov")
                .put("expectedXdr10PlusMov", "${clipName}_ndr10_plus_444.mov")
                .put("expectedNivukxVisionMov", "${clipName}_nivukx_vision_444.mov")
                .put("expectedCinemaDngPath", "${Environment.DIRECTORY_DOCUMENTS}/CamX/${clipName}_CinemaDNG/")
            val runEvidence = File(fixtureRoot, "fixture-$token.json")
            runEvidence.writeText(evidence.toString(2), Charsets.UTF_8)
            File(fixtureRoot, "LATEST").writeText("${runEvidence.name}\n", Charsets.US_ASCII)
        } catch (failure: Throwable) {
            createdUris.asReversed().forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
            throw failure
        }
    }

    @Test
    fun productionConversionMatrix_preservesTenBitColorTimingAndRawSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidenceRoot = requireNotNull(context.getExternalFilesDir("neo-qa/lq030")) {
            "App-scoped LQ-030 evidence directory is unavailable"
        }
        val runDirectory = File(evidenceRoot, "run-${System.currentTimeMillis()}-${System.nanoTime()}")
        assertTrue("Unable to create LQ-030 evidence directory", runDirectory.mkdirs())
        val stagingDirectory = File(context.cacheDir, "lq030-stage-${System.nanoTime()}")
        assertTrue("Unable to create LQ-030 staging directory", stagingDirectory.mkdirs())
        val resultFile = File(runDirectory, "lq030-result.json")
        val progressFile = File(runDirectory, "lq030-progress.json")
        val outputs = JSONArray()
        val result = JSONObject()
            .put("schema", "neo-qa-lq030-production-conversion-v1")
            .put("checkpoint", "LQ-030")
            .put("deviceManufacturer", Build.MANUFACTURER)
            .put("deviceModel", Build.MODEL)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("width", WIDTH)
            .put("height", HEIGHT)
            .put("fps", FPS)
            .put("sourceFrameCount", FRAME_COUNT)
            .put("outputs", outputs)

        val wakeLock = requireNotNull(context.getSystemService(PowerManager::class.java)) {
            "PowerManager unavailable for the LQ-030 device oracle"
        }.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NeoProCamX:Lq030ConversionOracle"
        ).apply {
            setReferenceCounted(false)
            acquire(TEST_WAKE_LOCK_TIMEOUT_MS)
        }

        fun markProgress(stage: String) {
            result.put("lastStage", stage)
            result.put("lastStageElapsedRealtimeMs", SystemClock.elapsedRealtime())
            progressFile.writeText(result.toString(2), Charsets.UTF_8)
        }

        try {
            markProgress("SOURCE_PREPARATION_STARTED")
            val rawMeta = rawMetadata()
            val rawFrames = (0 until FRAME_COUNT).map(::syntheticRawSamples)
            val rawMaster = File(runDirectory, "lq030-source.npraw")
            val rawMetadata = File(runDirectory, "lq030-develop.meta")
            rawMetadata.writeBytes(RawDevelopMetadataSidecar.encode(rawMeta))
            rawMaster.outputStream().buffered().use { output ->
                rawFrames.forEachIndexed { frameIndex, samples ->
                    output.write(
                        NprawFrameCodec.encodeFrame(
                            mode = RawCompressionMode.HIGH_COMPRESSION,
                            rawSamples = samples,
                            width = WIDTH,
                            height = HEIGHT,
                            rowStride = WIDTH * Short.SIZE_BYTES,
                            pixelStride = Short.SIZE_BYTES,
                            timestamp = SENSOR_TIMESTAMP_ORIGIN_NS + frameIndex * FRAME_DURATION_US * 1_000L,
                            originalSize = WIDTH * HEIGHT * Short.SIZE_BYTES,
                            sensorWhiteLevel = rawMeta.sensorWhiteLevel
                        ).bytes
                    )
                }
            }
            val rawHashBefore = sha256(rawMaster)
            val metadataHashBefore = sha256(rawMetadata)
            markProgress("RAW_SOURCE_READY")

            val coordinator = Raw12ClipConversionCoordinator()
            val rawProfiles = listOf(
                RawToNeoLogMovConverter.DeliveryProfile.NEOLOG,
                RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS,
                RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION
            )
            val rawFormats = listOf(
                RawToNeoLogMovConverter.OutputFormat.MASTER_444,
                RawToNeoLogMovConverter.OutputFormat.COMPAT_V210
            )
            rawProfiles.forEach { profile ->
                rawFormats.forEach { format ->
                    val stageName = "RAW_${profile.name}_${format.name}"
                    markProgress("${stageName}_STARTED")
                    outputs.put(
                        convertRawAndInspect(
                            runDirectory = runDirectory,
                            stagingDirectory = stagingDirectory,
                            source = rawMaster,
                            metadata = rawMeta,
                            coordinator = coordinator,
                            profile = profile,
                            format = format
                        )
                    )
                    markProgress("${stageName}_COMPLETED")
                }
            }

            markProgress("RAW_TO_CINEMADNG_STARTED")
            val dngDirectory = File(runDirectory, "cinema-dng")
            assertTrue("Unable to create CinemaDNG evidence directory", dngDirectory.mkdirs())
            val dngNiv99 = Niv99RawConversionProcessor(
                metadata = rawMeta,
                plan = Niv99OfflineConversionPolicy.rawToRawDeveloped()
            )
            var dngCount = 0
            rawMaster.inputStream().buffered().use { input ->
                while (true) {
                    val frame = NprawFrameCodec.readFrame(input) ?: break
                    val sourceBefore = frame.samples.copyOf()
                    val processed = dngNiv99.process(
                        source = frame.samples,
                        frameNumber = dngCount.toLong(),
                        sensorTimestampNs = frame.timestamp
                    )
                    assertArrayEquals("CinemaDNG conversion modified its NPRAW source", sourceBefore, frame.samples)
                    val dng = File(dngDirectory, RawCinemaDngWriter.frameFileName(dngCount))
                    dng.outputStream().buffered().use { output ->
                        RawCinemaDngWriter.writeFrame(
                            output,
                            processed.samples,
                            rawMeta,
                            processed.evidence
                        )
                    }
                    verifyDngSensorEquality(dng, processed.samples, rawMeta)
                    assertTrue(
                        "CinemaDNG did not embed NIV99 processing provenance",
                        dng.readBytes().toString(Charsets.ISO_8859_1).contains("NIV99;")
                    )
                    outputs.put(
                        JSONObject()
                            .put("conversion", "RAW_TO_CINEMADNG")
                            .put("frameIndex", dngCount)
                            .put("file", relative(runDirectory, dng))
                            .put("sha256", sha256(dng))
                            .put("bytes", dng.length())
                            .put("niv99DevelopedSamplesExact", true)
                            .put("sourceMasterUnchanged", true)
                            .put("niv99Route", processed.evidence.route.name)
                            .put("storageBitDepth", 16)
                    )
                    dngCount++
                }
            }
            assertEquals("CinemaDNG frame count", FRAME_COUNT, dngCount)
            markProgress("RAW_TO_CINEMADNG_COMPLETED")

            markProgress("SDR_SOURCE_PREPARATION_STARTED")
            val sdrSource = File(runDirectory, "lq030-sdr-p010-source.bin")
            val sdrFrames = (0 until FRAME_COUNT).map { frameIndex ->
                val p010 = syntheticP010(frameIndex)
                sdrSource.appendBytes(p010)
                RecordDevelopP010Frame(
                    index = frameIndex.toLong(),
                    presentationTimeUs = frameIndex * FRAME_DURATION_US,
                    width = WIDTH,
                    height = HEIGHT,
                    p010 = p010
                )
            }
            val sdrHashBefore = sha256(sdrSource)
            listOf(
                RecordDevelopDeliveryFormat.R210_444_UNCOMPRESSED_MOV,
                RecordDevelopDeliveryFormat.V210_422_UNCOMPRESSED_MOV
            ).forEach { delivery ->
                val stageName = "SDR_${delivery.name}"
                markProgress("${stageName}_STARTED")
                outputs.put(
                    convertSdrAndInspect(
                        runDirectory = runDirectory,
                        stagingDirectory = stagingDirectory,
                        source = sdrSource,
                        frames = sdrFrames,
                        delivery = delivery
                    )
                )
                markProgress("${stageName}_COMPLETED")
            }

            assertEquals("RAW source must remain byte-identical", rawHashBefore, sha256(rawMaster))
            assertEquals("RAW metadata must remain byte-identical", metadataHashBefore, sha256(rawMetadata))
            assertEquals("SDR source must remain byte-identical", sdrHashBefore, sha256(sdrSource))
            assertEquals("Expected six RAW MOVs, four DNGs, and two SDR MOVs", 12, outputs.length())

            result
                .put("status", "PASS")
                .put("rawSource", relative(runDirectory, rawMaster))
                .put("rawSourceSha256Before", rawHashBefore)
                .put("rawSourceSha256After", sha256(rawMaster))
                .put("rawMetadataSha256Before", metadataHashBefore)
                .put("rawMetadataSha256After", sha256(rawMetadata))
                .put("sdrSource", relative(runDirectory, sdrSource))
                .put("sdrSourceSha256Before", sdrHashBefore)
                .put("sdrSourceSha256After", sha256(sdrSource))
                .put("qualityFallbackUsed", false)
                .put("eightBitFallbackUsed", false)
            markProgress("COMPLETE")
            resultFile.writeText(result.toString(2), Charsets.UTF_8)
            File(runDirectory, "SHA256SUMS.txt").writeText(
                runDirectory.walkTopDown()
                    .filter { it.isFile && it != resultFile && it.name != "SHA256SUMS.txt" }
                    .sortedBy { it.relativeTo(runDirectory).invariantSeparatorsPath }
                    .joinToString(separator = "\n", postfix = "\n") { file ->
                        "${sha256(file)}  ${file.relativeTo(runDirectory).invariantSeparatorsPath}"
                    },
                Charsets.US_ASCII
            )
            File(evidenceRoot, "LATEST").writeText("${runDirectory.name}\n", Charsets.US_ASCII)
        } catch (failure: Throwable) {
            result
                .put("status", "FAIL")
                .put("failureType", failure.javaClass.name)
                .put("failure", failure.message ?: "unknown failure")
            resultFile.writeText(result.toString(2), Charsets.UTF_8)
            File(evidenceRoot, "LATEST").writeText("${runDirectory.name}\n", Charsets.US_ASCII)
            throw failure
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            stagingDirectory.deleteRecursively()
        }
    }

    /**
     * Device-side regression oracle for the production worker-count policy. Offline RAW development
     * promises that scheduling changes throughput only; the developed 10-bit samples must remain bit
     * exact. Keep the explicit one- and two-worker lanes because they cover the sequential boundary and
     * the historical test lane, then add the worker count selected by the real Settings workflow.
     */
    @Test
    fun rawConversionParallelismIsBitExactOnDevice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidenceRoot = requireNotNull(context.getExternalFilesDir("neo-qa/lq030-determinism")) {
            "App-scoped LQ-030 determinism evidence directory is unavailable"
        }
        val runDirectory = File(evidenceRoot, "run-${System.currentTimeMillis()}-${System.nanoTime()}")
        assertTrue("Unable to create LQ-030 determinism evidence directory", runDirectory.mkdirs())
        val metadata = rawMetadata()
        val streamBytes = ByteArrayOutputStream().use { output ->
            repeat(FRAME_COUNT) { frameIndex ->
                output.write(
                    NprawFrameCodec.encodeFrame(
                        mode = RawCompressionMode.HIGH_COMPRESSION,
                        rawSamples = syntheticRawSamples(frameIndex),
                        width = WIDTH,
                        height = HEIGHT,
                        rowStride = WIDTH * Short.SIZE_BYTES,
                        pixelStride = Short.SIZE_BYTES,
                        timestamp = SENSOR_TIMESTAMP_ORIGIN_NS + frameIndex * FRAME_DURATION_US * 1_000L,
                        originalSize = WIDTH * HEIGHT * Short.SIZE_BYTES,
                        sensorWhiteLevel = metadata.sensorWhiteLevel
                    ).bytes
                )
            }
            output.toByteArray()
        }
        val productionWorkers = ConversionParallelismPolicy.workerCount(
            RawDevelopMemoryPolicy.cpuDirectPackPeakBytes(
                width = WIDTH,
                height = HEIGHT,
                format = RawTenBitPacker.Format.R210_444
            )
        )
        val workerCounts = listOf(1, 2, productionWorkers).distinct()
        val originalDetailState = RawDevelopEliteDetailFlag.enabled
        val report = JSONObject()
            .put("schema", "neo-qa-lq030-worker-determinism-v1")
            .put("checkpoint", "LQ-030")
            .put("deviceManufacturer", Build.MANUFACTURER)
            .put("deviceModel", Build.MODEL)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("sourceSha256", sha256(streamBytes))
            .put("productionWorkers", productionWorkers)
        val results = JSONArray()
        try {
            RawDevelopEliteDetailFlag.set(true)
            listOf(
                RawToNeoLogMovConverter.DeliveryProfile.NEOLOG,
                RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS,
                RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION
            ).forEach { profile ->
                val options = RawToNeoLogMovConverter.Options(
                    variant = NeoLogCurve.Variant.CINEMA,
                    format = RawToNeoLogMovConverter.OutputFormat.MASTER_444,
                    deliveryProfile = profile
                )
                var reference: ByteArray? = null
                workerCounts.forEach { workers ->
                    val packed = ByteArrayOutputStream()
                    val conversion = RawToNeoLogMovConverter().convertStreamParallel(
                        input = streamBytes.inputStream().buffered(),
                        meta = metadata,
                        options = options,
                        parallelism = workers
                    ) { sample, _ -> packed.write(sample) }
                    assertEquals(FRAME_COUNT, conversion.framesConverted)
                    val payload = packed.toByteArray()
                    reference?.let { expected ->
                        assertArrayEquals(
                            "${profile.name} changed pixels at workerCount=$workers",
                            expected,
                            payload
                        )
                    } ?: run { reference = payload }
                    results.put(
                        JSONObject()
                            .put("profile", profile.name)
                            .put("workerCount", workers)
                            .put("payloadBytes", payload.size)
                            .put("payloadSha256", sha256(payload))
                    )
                }
            }
            report.put("status", "PASS").put("results", results)
            File(runDirectory, "lq030-worker-determinism.json")
                .writeText(report.toString(2), Charsets.UTF_8)
            File(evidenceRoot, "LATEST").writeText("${runDirectory.name}\n", Charsets.US_ASCII)
        } catch (failure: Throwable) {
            report
                .put("status", "FAIL")
                .put("failureType", failure.javaClass.name)
                .put("failure", failure.message ?: "unknown failure")
                .put("results", results)
            File(runDirectory, "lq030-worker-determinism.json")
                .writeText(report.toString(2), Charsets.UTF_8)
            File(evidenceRoot, "LATEST").writeText("${runDirectory.name}\n", Charsets.US_ASCII)
            throw failure
        } finally {
            RawDevelopEliteDetailFlag.set(originalDetailState)
        }
    }

    private fun convertRawAndInspect(
        runDirectory: File,
        stagingDirectory: File,
        source: File,
        metadata: RawDevelopMetadata,
        coordinator: Raw12ClipConversionCoordinator,
        profile: RawToNeoLogMovConverter.DeliveryProfile,
        format: RawToNeoLogMovConverter.OutputFormat
    ): JSONObject {
        val options = RawToNeoLogMovConverter.Options(
            variant = NeoLogCurve.Variant.CINEMA,
            format = format,
            deliveryProfile = profile
        )
        val outputName = "raw-${profile.fileSuffix}-${format.fileSuffix}.mov"
        val stagedOutput = File(stagingDirectory, outputName)
        val sourceHashBefore = sha256(source)
        val startedNs = System.nanoTime()
        val converted = source.inputStream().buffered().use { input ->
            coordinator.convert(
                streamInput = input,
                meta = metadata,
                options = options,
                outputPath = stagedOutput.absolutePath,
                parallelism = 2,
                expectedFrameCount = FRAME_COUNT.toLong()
            )
        }
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
        assertEquals("RAW conversion frame count", FRAME_COUNT, converted.framesConverted)
        assertTrue("RAW conversion emitted no payload", converted.bytesWritten > 0L)
        assertEquals("RAW conversion modified its source", sourceHashBefore, sha256(source))

        val stagedCertificate = File("${stagedOutput.absolutePath}.elite-conversion-certificate.txt")
        val stagedLut = if (profile == RawToNeoLogMovConverter.DeliveryProfile.NEOLOG) {
            NeoLogRec709LutWriter.sidecarFile(stagedOutput.absolutePath)
        } else {
            null
        }
        val output = File(runDirectory, outputName)
        stagedOutput.copyTo(output, overwrite = true)
        val certificate = File(runDirectory, stagedCertificate.name)
        stagedCertificate.copyTo(certificate, overwrite = true)
        val lut = stagedLut?.let { sourceLut ->
            File(runDirectory, sourceLut.name).also { sourceLut.copyTo(it, overwrite = true) }
        }

        val sampleEntry = if (format == RawToNeoLogMovConverter.OutputFormat.MASTER_444) "r210" else "v210"
        val color = coordinator.colorConfigFor(options)
        val inspection = QuickTimeMovArtifactInspector.inspect(
            output,
            QuickTimeMovArtifactRequirements(
                allowedVideoSampleEntries = setOf(sampleEntry),
                expectedVideoSampleEntry = sampleEntry,
                expectedWidth = WIDTH,
                expectedHeight = HEIGHT,
                expectedRotationDegrees = 0,
                expectedColor = QuickTimeMovNclxExpectation(
                    colorPrimaries = color.colorPrimaries,
                    transferCharacteristics = color.transferCharacteristics,
                    matrixCoefficients = color.matrixCoefficients,
                    fullRange = color.fullRange
                ),
                minimumSampleCount = FRAME_COUNT.toLong()
            )
        ).requirePass()
        val video = requireNotNull(inspection.videoTrack)
        assertEquals(FRAME_COUNT.toLong(), video.sampleCount)
        val expectedPayloadBytes = RawTenBitPacker.payloadSize(
            requireNotNull(format.packerFormat),
            WIDTH,
            HEIGHT
        ).toLong() * FRAME_COUNT
        assertEquals("Uncompressed RAW output payload size", expectedPayloadBytes, video.samplePayloadBytes)
        val signal = inspectTenBitPayload(output, sampleEntry, expectedPayloadBytes)
        assertTrue("Converted RAW output is flat", signal.maximum > signal.minimum && signal.uniqueCodes >= 16)

        assertTrue("RAW conversion quality certificate missing", certificate.isFile && certificate.length() > 0L)
        val certificateText = certificate.readText(Charsets.UTF_8)
        assertTrue("Certificate did not prove ten-bit output", certificateText.contains("declared.outputBitDepth=10"))
        assertTrue("Certificate permits an eight-bit fallback", certificateText.contains("declared.usedEightBitFallback=false"))
        assertTrue("Certificate permits a speed-quality reduction", certificateText.contains("declared.qualityReducedForSpeed=false"))
        assertTrue("Certificate permits a destructive fallback", certificateText.contains("declared.destructiveFallbackUsed=false"))
        assertTrue("Certificate did not prove NIV99 conversion", certificateText.contains("niv99OfflineEnabled=true"))
        assertTrue(
            "Certificate recorded the wrong NIV99 RAW route",
            certificateText.contains(
                "niv99OfflineRoute=" + when (profile) {
                    RawToNeoLogMovConverter.DeliveryProfile.NEOLOG -> "RAW_TO_LOG"
                    RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS -> "RAW_TO_NDR10_PLUS"
                    RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION -> "RAW_TO_NIVUKX_VISION"
                }
            )
        )
        assertTrue("Certificate did not preserve the RAW source", certificateText.contains("niv99OfflineSourceImmutable=true"))
        if (profile == RawToNeoLogMovConverter.DeliveryProfile.NEOLOG) {
            requireNotNull(lut).also {
                assertTrue("NeoLog viewing LUT missing", it.isFile && it.length() > 0L)
            }
        } else {
            assertTrue("PQ output lacks mdcv mastering metadata", containsFourCc(output, "mdcv"))
            assertTrue("PQ output lacks clli content-light metadata", containsFourCc(output, "clli"))
        }

        return JSONObject()
            .put("conversion", "RAW_TO_${profile.name}")
            .put("container", format.name)
            .put("sampleEntry", sampleEntry)
            .put("storageBitDepth", 10)
            .put("frameCount", video.sampleCount)
            .put("durationSeconds", video.durationSeconds)
            .put("width", video.sampleEntryWidth)
            .put("height", video.sampleEntryHeight)
            .put("payloadBytes", video.samplePayloadBytes)
            .put("fileBytes", output.length())
            .put("sha256", sha256(output))
            .put("file", relative(runDirectory, output))
            .put("certificate", relative(runDirectory, certificate))
            .put("certificateSha256", sha256(certificate))
            .put("viewingLut", lut?.let { relative(runDirectory, it) } ?: JSONObject.NULL)
            .put("viewingLutSha256", lut?.let(::sha256) ?: JSONObject.NULL)
            .put("colorPrimaries", video.nclxColor?.colorPrimaries)
            .put("transfer", video.nclxColor?.transferCharacteristics)
            .put("matrix", video.nclxColor?.matrixCoefficients)
            .put("fullRange", video.nclxColor?.fullRange)
            .put("minimumCode", signal.minimum)
            .put("maximumCode", signal.maximum)
            .put("uniqueCodes", signal.uniqueCodes)
            .put("elapsedMs", elapsedMs)
            .put("sourceHashUnchanged", true)
            .put("qualityFallbackUsed", false)
    }

    private fun convertSdrAndInspect(
        runDirectory: File,
        stagingDirectory: File,
        source: File,
        frames: List<RecordDevelopP010Frame>,
        delivery: RecordDevelopDeliveryFormat
    ): JSONObject {
        val outputFormat = requireNotNull(delivery.uncompressedFormat)
        val sampleEntry = if (outputFormat == RecordDevelopOutputFormat.R210_444) "r210" else "v210"
        val outputName = "sdr-${delivery.fileSuffix}.mov"
        val stagedOutput = File(stagingDirectory, outputName)
        val sourceHashBefore = sha256(source)
        val startedNs = System.nanoTime()
        val audit = RecordDevelopMovConverter.convert(
            master = source,
            output = stagedOutput,
            sidecar = sdrSidecar(),
            options = RecordDevelopConversionOptions(
                outputFormat = outputFormat,
                deliveryFormat = delivery
            ),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = {
                InMemoryP010FrameReader(
                    frames,
                    RecordDevelopDecodedFormat(WIDTH, HEIGHT, FPS)
                )
            },
            gpuAcceleratorFactory = { context -> NativeRecordDevelopGpuAccelerator(context) },
            expectedFrameCount = FRAME_COUNT.toLong()
        )
        val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L
        assertEquals(FRAME_COUNT.toLong(), audit.frameCount)
        assertTrue("SDR conversion did not preserve its source", audit.inputMasterHashUnchanged)
        assertEquals("SDR conversion changed source bytes", sourceHashBefore, sha256(source))
        assertEquals(10, audit.eliteQualityAudit.evidence.outputBitDepth)
        assertTrue(!audit.eliteQualityAudit.evidence.usedEightBitFallback)
        assertTrue(!audit.eliteQualityAudit.evidence.qualityReducedForSpeed)
        assertTrue(!audit.eliteQualityAudit.evidence.destructiveFallbackUsed)
        assertTrue("SDR derivative did not apply NIV99", audit.niv99Applied)
        assertEquals("RECORD_DEVELOP_SDR", audit.niv99Route)
        assertTrue("SDR NIV99 processing was not full-frame", audit.niv99FullFrame)

        val stagedCertificate = RecordDevelopMovConverter.certificateSidecarFile(stagedOutput)
        val output = File(runDirectory, outputName)
        stagedOutput.copyTo(output, overwrite = true)
        val certificate = File(runDirectory, stagedCertificate.name)
        stagedCertificate.copyTo(certificate, overwrite = true)

        val inspection = QuickTimeMovArtifactInspector.inspect(
            output,
            QuickTimeMovArtifactRequirements(
                allowedVideoSampleEntries = setOf(sampleEntry),
                expectedVideoSampleEntry = sampleEntry,
                expectedWidth = WIDTH,
                expectedHeight = HEIGHT,
                expectedRotationDegrees = 0,
                expectedColor = QuickTimeMovNclxExpectation(
                    colorPrimaries = audit.colorPrimaries,
                    transferCharacteristics = audit.transferCharacteristics,
                    matrixCoefficients = audit.matrixCoefficients,
                    fullRange = audit.fullRange
                ),
                minimumSampleCount = FRAME_COUNT.toLong()
            )
        ).requirePass()
        val video = requireNotNull(inspection.videoTrack)
        assertEquals(FRAME_COUNT.toLong(), video.sampleCount)
        val expectedPayloadBytes = RawTenBitPacker.payloadSize(
            outputFormat.packerFormat,
            WIDTH,
            HEIGHT
        ).toLong() * FRAME_COUNT
        assertEquals("Uncompressed SDR output payload size", expectedPayloadBytes, video.samplePayloadBytes)
        val signal = inspectTenBitPayload(output, sampleEntry, expectedPayloadBytes)
        assertTrue("Converted SDR output is flat", signal.maximum > signal.minimum && signal.uniqueCodes >= 16)

        assertTrue("SDR conversion quality certificate missing", certificate.isFile && certificate.length() > 0L)
        val certificateText = certificate.readText(Charsets.UTF_8)
        assertTrue("SDR certificate did not prove NIV99 conversion", certificateText.contains("niv99OfflineEnabled=true"))
        assertTrue("SDR certificate recorded the wrong NIV99 route", certificateText.contains("niv99OfflineRoute=RECORD_DEVELOP_SDR"))
        return JSONObject()
            .put("conversion", "SDR_RECORD_DEVELOP")
            .put("container", delivery.auditLabel)
            .put("sampleEntry", sampleEntry)
            .put("storageBitDepth", 10)
            .put("frameCount", video.sampleCount)
            .put("durationSeconds", video.durationSeconds)
            .put("width", video.sampleEntryWidth)
            .put("height", video.sampleEntryHeight)
            .put("payloadBytes", video.samplePayloadBytes)
            .put("fileBytes", output.length())
            .put("sha256", sha256(output))
            .put("file", relative(runDirectory, output))
            .put("certificate", relative(runDirectory, certificate))
            .put("certificateSha256", sha256(certificate))
            .put("colorPrimaries", video.nclxColor?.colorPrimaries)
            .put("transfer", video.nclxColor?.transferCharacteristics)
            .put("matrix", video.nclxColor?.matrixCoefficients)
            .put("fullRange", video.nclxColor?.fullRange)
            .put("minimumCode", signal.minimum)
            .put("maximumCode", signal.maximum)
            .put("uniqueCodes", signal.uniqueCodes)
            .put("gpuAccelerated", audit.gpuAccelerated)
            .put("workerCount", audit.workerCount)
            .put("elapsedMs", elapsedMs)
            .put("sourceHashUnchanged", true)
            .put("qualityFallbackUsed", false)
    }

    private fun rawMetadata(): RawDevelopMetadata = RawDevelopMetadata(
        width = WIDTH,
        height = HEIGHT,
        storageBitDepth = 12,
        sensorWhiteLevel = 4095,
        blackLevel = 64,
        whiteLevel = 4095,
        bayerPatternValue = 0,
        whiteBalanceR = 1.91f,
        whiteBalanceGr = 1f,
        whiteBalanceGb = 1.01f,
        whiteBalanceB = 1.57f,
        exposureTimeNs = 8_000_000L,
        analogGain = 1.25f,
        digitalGain = 1f,
        noiseS = 0.001f,
        noiseO = 0.0001f,
        sensorToXyz = DEFAULT_SENSOR_TO_XYZ.copyOf(),
        fps = FPS,
        colorProfileTag = "N-LOG-CINEMA"
    )

    private fun sdrSidecar(): VideoDevelopColorMetadata = VideoDevelopColorMetadata(
        width = WIDTH,
        height = HEIGHT,
        storageBitDepth = 10,
        fps = FPS,
        whiteBalanceR = 1.05f,
        whiteBalanceGr = 1f,
        whiteBalanceGb = 1f,
        whiteBalanceB = 1.03f,
        workingRgbToXyz = SnceIlluminantEstimator.REC709_TO_XYZ.copyOf(),
        adaptingLuminance = 20f,
        cctKelvin = 5600f,
        tint = 0f,
        colorSpace = DevelopColorSpace.REC709,
        colorTransfer = DevelopColorTransfer.SDR,
        colorProfileTag = "REC709_SDR_LQ030",
        detailProfileTag = "FOCUSED_DETAIL"
    )

    private fun syntheticRawSamples(frameIndex: Int): IntArray =
        IntArray(WIDTH * HEIGHT) { pixel ->
            val x = pixel % WIDTH
            val y = pixel / WIDTH
            val radial = ((x - WIDTH / 2) * (x - WIDTH / 2) + (y - HEIGHT / 2) * (y - HEIGHT / 2)) / 5
            (192 + x * 29 + y * 17 + frameIndex * 37 + radial).coerceIn(64, 4095)
        }

    private fun syntheticP010(frameIndex: Int): ByteArray {
        val chromaWidth = (WIDTH + 1) / 2
        val chromaHeight = (HEIGHT + 1) / 2
        val lumaBytes = WIDTH * HEIGHT * Short.SIZE_BYTES
        val output = ByteArray(lumaBytes + chromaWidth * chromaHeight * 4)
        fun put(offset: Int, code: Int) {
            val word = code.coerceIn(0, 1023) shl 6
            output[offset] = word.toByte()
            output[offset + 1] = (word ushr 8).toByte()
        }
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                put((y * WIDTH + x) * 2, 64 + ((x * 7 + y * 5 + frameIndex * 19) % 877))
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
        assertEquals(output.size, offset)
        return output
    }

    private fun verifyDngSensorEquality(dng: File, expectedSamples: IntArray, meta: RawDevelopMetadata) {
        val bytes = dng.readBytes()
        val tags = DngTiffReader.read(bytes)
        assertEquals(meta.whiteLevel.toLong(), tags.whiteLevel)
        assertEquals(meta.blackLevel.toDouble(), requireNotNull(tags.blackLevel).first(), 0.0)
        assertEquals(9, requireNotNull(tags.colorMatrix1).size)
        assertEquals(23, tags.calibrationIlluminant1)
        val pixelBytes = WIDTH * HEIGHT * Short.SIZE_BYTES
        val tail = bytes.copyOfRange(bytes.size - pixelBytes, bytes.size)
        val actual = IntArray(expectedSamples.size) { index ->
            (tail[index * 2].toInt() and 0xFF) or
                ((tail[index * 2 + 1].toInt() and 0xFF) shl 8)
        }
        assertArrayEquals("CinemaDNG sensor plane changed", expectedSamples, actual)
    }

    private fun inspectTenBitPayload(file: File, sampleEntry: String, expectedBytes: Long): SignalEvidence {
        val payload = readMdatPayload(file, expectedBytes)
        assertEquals("Unexpected uncompressed payload length", expectedBytes, payload.size.toLong())
        var minimum = 1023
        var maximum = 0
        val unique = BooleanArray(1024)
        var offset = 0
        while (offset + 3 < payload.size) {
            val word = if (sampleEntry == "r210") {
                ((payload[offset].toInt() and 0xFF) shl 24) or
                    ((payload[offset + 1].toInt() and 0xFF) shl 16) or
                    ((payload[offset + 2].toInt() and 0xFF) shl 8) or
                    (payload[offset + 3].toInt() and 0xFF)
            } else {
                (payload[offset].toInt() and 0xFF) or
                    ((payload[offset + 1].toInt() and 0xFF) shl 8) or
                    ((payload[offset + 2].toInt() and 0xFF) shl 16) or
                    ((payload[offset + 3].toInt() and 0xFF) shl 24)
            }
            assertEquals("$sampleEntry word uses bits outside three 10-bit lanes", 0, word ushr 30)
            repeat(3) { lane ->
                val code = (word ushr (lane * 10)) and 0x3FF
                minimum = minOf(minimum, code)
                maximum = maxOf(maximum, code)
                unique[code] = true
            }
            offset += 4
        }
        return SignalEvidence(minimum, maximum, unique.count { it })
    }

    private fun readMdatPayload(file: File, expectedBytes: Long): ByteArray {
        require(expectedBytes in 1..Int.MAX_VALUE.toLong())
        RandomAccessFile(file, "r").use { input ->
            var cursor = 0L
            while (cursor + 8L <= input.length()) {
                input.seek(cursor)
                val size32 = input.readInt().toLong() and 0xFFFF_FFFFL
                val typeBytes = ByteArray(4).also(input::readFully)
                val type = String(typeBytes, Charsets.US_ASCII)
                val headerBytes: Long
                val boxBytes: Long
                if (size32 == 1L) {
                    headerBytes = 16L
                    boxBytes = input.readLong()
                } else {
                    headerBytes = 8L
                    boxBytes = if (size32 == 0L) input.length() - cursor else size32
                }
                require(boxBytes >= headerBytes && cursor + boxBytes <= input.length()) {
                    "Malformed $type box while reading LQ-030 payload"
                }
                if (type == "mdat") {
                    require(boxBytes - headerBytes >= expectedBytes) {
                        "mdat has ${boxBytes - headerBytes} bytes, expected at least $expectedBytes"
                    }
                    input.seek(cursor + headerBytes)
                    return ByteArray(expectedBytes.toInt()).also(input::readFully)
                }
                cursor += boxBytes
            }
        }
        error("No mdat payload found in ${file.absolutePath}")
    }

    private fun containsFourCc(file: File, value: String): Boolean {
        val bytes = file.readBytes()
        val target = value.toByteArray(Charsets.US_ASCII)
        outer@ for (index in 0..bytes.size - target.size) {
            for (offset in target.indices) {
                if (bytes[index + offset] != target[offset]) continue@outer
            }
            return true
        }
        return false
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(Locale.US, byte) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(Locale.US, byte) }

    private fun relative(root: File, file: File): String =
        file.relativeTo(root).invariantSeparatorsPath

    private data class SignalEvidence(
        val minimum: Int,
        val maximum: Int,
        val uniqueCodes: Int
    )
}
