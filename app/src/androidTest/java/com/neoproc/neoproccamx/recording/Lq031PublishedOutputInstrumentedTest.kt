package com.neoproc.neoproccamx.recording

import android.content.ContentUris
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoproc.neoproccamx.capture.DngTiffReader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale

/** Read-only, physical-device oracle over the exact files produced through the LQ-031 UI matrix. */
@RunWith(AndroidJUnit4::class)
class Lq031PublishedOutputInstrumentedTest {
    private data class MovSpec(
        val suffix: String,
        val sampleEntry: String,
        val transfer: Int,
        val matrix: Int,
        val fullRange: Boolean
    )

    private data class SignalEvidence(
        val minimumCode: Int,
        val maximumCode: Int,
        val uniqueCodeCount: Int,
        val distinctFrameCount: Int
    )

    @Test
    fun publishedUiMatrix_containsTimedTenBitImagesAndSensorDngFrames() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureRoot = requireNotNull(context.getExternalFilesDir("neo-qa/lq031-ui-fixture"))
        val fixtureName = File(fixtureRoot, "LATEST").readText(Charsets.US_ASCII).trim()
        val fixture = JSONObject(File(fixtureRoot, fixtureName).readText(Charsets.UTF_8))
        val clipName = fixture.getString("clipName")
        val width = fixture.getInt("width")
        val height = fixture.getInt("height")
        val fps = fixture.getInt("fps")
        val frameCount = fixture.getInt("frameCount")
        val expectedDurationUs = fixture.getLong("expectedDurationUs")
        val expectedSourceHash = fixture.getString("streamSha256")
        val expectedMetadataHash = fixture.getString("metadataSha256")

        val evidenceRoot = requireNotNull(context.getExternalFilesDir("neo-qa/lq031-output-audit"))
        assertTrue(evidenceRoot.isDirectory || evidenceRoot.mkdirs())
        val runDirectory = File(evidenceRoot, "run-${System.currentTimeMillis()}-${System.nanoTime()}")
        assertTrue(runDirectory.mkdirs())
        val staging = File(context.cacheDir, "lq031-output-audit-${System.nanoTime()}")
        assertTrue(staging.mkdirs())
        val outputEvidence = JSONArray()
        val dngEvidence = JSONArray()
        val report = JSONObject()
            .put("schema", "neo-qa-lq031-published-output-audit-v1")
            .put("checkpoint", "LQ-031")
            .put("status", "FAIL")
            .put("deviceManufacturer", Build.MANUFACTURER)
            .put("deviceModel", Build.MODEL)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("clipName", clipName)
            .put("width", width)
            .put("height", height)
            .put("fps", fps)
            .put("frameCount", frameCount)
            .put("outputs", outputEvidence)
            .put("dngFrames", dngEvidence)

        try {
            val source = copyPublishedFile(
                displayName = "stream.npraw",
                relativePath = "Documents/CamX/$clipName/",
                destination = File(staging, "source-stream.npraw")
            )
            val metadata = copyPublishedFile(
                displayName = "develop.meta",
                relativePath = "Documents/CamX/$clipName/",
                destination = File(staging, "source-develop.meta")
            )
            assertEquals(expectedSourceHash, sha256(source))
            assertEquals(expectedMetadataHash, sha256(metadata))
            report
                .put("sourceSha256", sha256(source))
                .put("metadataSha256", sha256(metadata))
                .put("sourceHashUnchanged", true)

            val specs = listOf(
                MovSpec("_neolog_444.mov", "r210", 1, 1, true),
                MovSpec("_neolog_v210.mov", "v210", 1, 9, false),
                MovSpec("_neolog_hevc_main10_420.mov", "hvc1", 1, 9, false),
                MovSpec("_ndr10_plus_444.mov", "r210", 16, 0, true),
                MovSpec("_ndr10_plus_v210.mov", "v210", 16, 9, false),
                MovSpec("_ndr10_plus_hevc_main10_420.mov", "hvc1", 16, 9, false),
                MovSpec("_nivukx_vision_444.mov", "r210", 16, 0, true),
                MovSpec("_nivukx_vision_v210.mov", "v210", 16, 9, false),
                MovSpec("_nivukx_vision_hevc_main10_420.mov", "hvc1", 16, 9, false)
            )
            specs.forEach { spec ->
                val displayName = clipName + spec.suffix
                val local = copyPublishedFile(
                    displayName = displayName,
                    relativePath = "Movies/CamX/",
                    destination = File(staging, displayName)
                )
                val inspection = QuickTimeMovArtifactInspector.inspect(
                    local,
                    QuickTimeMovArtifactRequirements(
                        allowedVideoSampleEntries = setOf(spec.sampleEntry),
                        expectedVideoSampleEntry = spec.sampleEntry,
                        expectedWidth = width,
                        expectedHeight = height,
                        expectedRotationDegrees = 0,
                        expectedColor = QuickTimeMovNclxExpectation(
                            colorPrimaries = 9,
                            transferCharacteristics = spec.transfer,
                            matrixCoefficients = spec.matrix,
                            fullRange = spec.fullRange
                        ),
                        minimumSampleCount = frameCount.toLong()
                    )
                ).requirePass()
                val video = requireNotNull(inspection.videoTrack)
                assertEquals(frameCount.toLong(), video.sampleCount)
                assertEquals(expectedDurationUs, video.mediaDurationUnits)
                assertEquals(1_000_000L, video.mediaTimescale)
                assertTrue(video.timingCoversEverySample)

                val expectedPayloadBytes = when (spec.sampleEntry) {
                    "r210" -> RawTenBitPacker.payloadSize(
                        RawTenBitPacker.Format.R210_444,
                        width,
                        height
                    ).toLong() * frameCount
                    "v210" -> RawTenBitPacker.payloadSize(
                        RawTenBitPacker.Format.V210_422,
                        width,
                        height
                    ).toLong() * frameCount
                    else -> video.samplePayloadBytes
                }
                val signal = if (spec.sampleEntry == "hvc1") {
                    val hevc = requireNotNull(video.hevcConfiguration)
                    assertEquals(2, hevc.profileIdc)
                    assertEquals(10, hevc.bitDepthLuma)
                    assertEquals(10, hevc.bitDepthChroma)
                    inspectDecodedMain10(local, width, height, frameCount)
                } else {
                    inspectUncompressedTenBit(
                        local,
                        spec.sampleEntry,
                        expectedPayloadBytes,
                        frameCount
                    )
                }
                assertTrue("${spec.sampleEntry} output is black or constant: $signal", signal.maximumCode > 96)
                assertTrue("${spec.sampleEntry} output has no image detail: $signal", signal.uniqueCodeCount >= 32)
                assertTrue("${spec.sampleEntry} output lost temporal image changes: $signal", signal.distinctFrameCount > 1)

                outputEvidence.put(
                    JSONObject()
                        .put("displayName", displayName)
                        .put("sha256", sha256(local))
                        .put("fileBytes", local.length())
                        .put("sampleEntry", video.sampleEntry)
                        .put("bitDepth", video.hevcConfiguration?.bitDepthLuma ?: 10)
                        .put("frameCount", video.sampleCount)
                        .put("durationUs", video.mediaDurationUnits)
                        .put("fps", video.sampleCount * video.mediaTimescale.toDouble() / video.mediaDurationUnits)
                        .put("primaries", requireNotNull(video.nclxColor).colorPrimaries)
                        .put("transfer", requireNotNull(video.nclxColor).transferCharacteristics)
                        .put("matrix", requireNotNull(video.nclxColor).matrixCoefficients)
                        .put("fullRange", requireNotNull(video.nclxColor).fullRange)
                        .put("minimumCode", signal.minimumCode)
                        .put("maximumCode", signal.maximumCode)
                        .put("uniqueCodeCount", signal.uniqueCodeCount)
                        .put("distinctFrameCount", signal.distinctFrameCount)
                        .put("nonBlackImageContent", true)
                )
            }

            val dngHashes = linkedSetOf<String>()
            repeat(frameCount) { frameIndex ->
                val name = "frame_${(frameIndex + 1).toString().padStart(6, '0')}.dng"
                val local = copyPublishedFile(
                    displayName = name,
                    relativePath = "Documents/CamX/${clipName}_CinemaDNG/",
                    destination = File(staging, name)
                )
                val bytes = local.readBytes()
                val tags = DngTiffReader.read(bytes)
                assertEquals(4095L, tags.whiteLevel)
                assertEquals(64.0, requireNotNull(tags.blackLevel).first(), 0.0)
                assertEquals(9, requireNotNull(tags.colorMatrix1).size)
                assertEquals(23, tags.calibrationIlluminant1)
                val signal = inspectDngSensorPlane(bytes, width, height)
                assertTrue("$name is black or constant: $signal", signal.maximumCode > 96)
                assertTrue("$name has no sensor detail: $signal", signal.uniqueCodeCount >= 64)
                val hash = sha256(local)
                dngHashes += hash
                dngEvidence.put(
                    JSONObject()
                        .put("displayName", name)
                        .put("sha256", hash)
                        .put("fileBytes", local.length())
                        .put("blackLevel", requireNotNull(tags.blackLevel).first())
                        .put("whiteLevel", tags.whiteLevel)
                        .put("minimumCode", signal.minimumCode)
                        .put("maximumCode", signal.maximumCode)
                        .put("uniqueCodeCount", signal.uniqueCodeCount)
                        .put("nonBlackSensorImageContent", true)
                )
            }
            assertEquals("CinemaDNG temporal frames collapsed to duplicates", frameCount, dngHashes.size)
            report.put("status", "PASS")
        } catch (failure: Throwable) {
            report
                .put("status", "FAIL")
                .put("failureType", failure.javaClass.name)
                .put("failure", failure.message ?: "unknown failure")
            throw failure
        } finally {
            val reportFile = File(runDirectory, "lq031-output-audit.json")
            reportFile.writeText(report.toString(2), Charsets.UTF_8)
            File(evidenceRoot, "LATEST").writeText("${runDirectory.name}\n", Charsets.US_ASCII)
            staging.deleteRecursively()
        }
    }

    private fun copyPublishedFile(displayName: String, relativePath: String, destination: File): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING)
        val cursor = requireNotNull(
            resolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(displayName, relativePath),
                "${MediaStore.MediaColumns._ID} DESC"
            )
        )
        val uri = cursor.use {
            check(it.moveToFirst()) { "Published output is missing: $relativePath$displayName" }
            assertEquals("Published output is still pending", 0, it.getInt(1))
            ContentUris.withAppendedId(collection, it.getLong(0))
        }
        destination.parentFile?.mkdirs()
        resolver.openInputStream(uri)?.use { input ->
            destination.outputStream().buffered().use(input::copyTo)
        } ?: error("Unable to read published output: $uri")
        assertTrue("Published output is empty: $displayName", destination.length() > 0L)
        return destination
    }

    private fun inspectDecodedMain10(
        file: File,
        width: Int,
        height: Int,
        expectedFrames: Int
    ): SignalEvidence {
        var minimum = 1023
        var maximum = 0
        val unique = BooleanArray(1024)
        val frameHashes = linkedSetOf<String>()
        val presentationTimes = ArrayList<Long>(expectedFrames)
        RecordDevelopMediaCodecP010Reader(file).use { reader ->
            assertEquals(10, reader.format.storageBitDepth)
            assertEquals(width, reader.format.width)
            assertEquals(height, reader.format.height)
            while (true) {
                val frame = reader.readNextFrame() ?: break
                presentationTimes += frame.presentationTimeUs
                frameHashes += sha256(frame.p010)
                var offset = 0
                val lumaBytes = width * height * Short.SIZE_BYTES
                while (offset + 1 < lumaBytes) {
                    val word = (frame.p010[offset].toInt() and 0xFF) or
                        ((frame.p010[offset + 1].toInt() and 0xFF) shl 8)
                    assertEquals("Decoded P010 contains non-zero padding bits", 0, word and 0x3F)
                    val code = word ushr 6
                    minimum = minOf(minimum, code)
                    maximum = maxOf(maximum, code)
                    unique[code] = true
                    offset += Short.SIZE_BYTES
                }
            }
        }
        assertEquals(expectedFrames, presentationTimes.size)
        assertTrue(
            "Decoded HEVC PTS are not strictly monotonic: $presentationTimes",
            presentationTimes.zipWithNext().all { (previous, current) -> current > previous }
        )
        return SignalEvidence(minimum, maximum, unique.count { it }, frameHashes.size)
    }

    private fun inspectUncompressedTenBit(
        file: File,
        sampleEntry: String,
        expectedBytes: Long,
        frameCount: Int
    ): SignalEvidence {
        val payload = readMdatPayload(file, expectedBytes)
        assertEquals(expectedBytes, payload.size.toLong())
        val frameBytes = payload.size / frameCount
        val frameHashes = (0 until frameCount).mapTo(linkedSetOf()) { frameIndex ->
            sha256(payload.copyOfRange(frameIndex * frameBytes, (frameIndex + 1) * frameBytes))
        }
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
            offset += Int.SIZE_BYTES
        }
        return SignalEvidence(minimum, maximum, unique.count { it }, frameHashes.size)
    }

    private fun inspectDngSensorPlane(bytes: ByteArray, width: Int, height: Int): SignalEvidence {
        val pixelBytes = width * height * Short.SIZE_BYTES
        require(bytes.size >= pixelBytes) { "DNG cannot contain the declared sensor plane" }
        val start = bytes.size - pixelBytes
        var minimum = 0xFFFF
        var maximum = 0
        val unique = BooleanArray(4096)
        var offset = start
        while (offset + 1 < bytes.size) {
            val code = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            assertTrue("DNG sample exceeds 12-bit sensor range: $code", code in 0..4095)
            minimum = minOf(minimum, code)
            maximum = maxOf(maximum, code)
            unique[code] = true
            offset += Short.SIZE_BYTES
        }
        return SignalEvidence(minimum, maximum, unique.count { it }, 1)
    }

    private fun readMdatPayload(file: File, expectedBytes: Long): ByteArray {
        require(expectedBytes in 1..Int.MAX_VALUE.toLong())
        RandomAccessFile(file, "r").use { input ->
            var cursor = 0L
            while (cursor + 8L <= input.length()) {
                input.seek(cursor)
                val size32 = input.readInt().toLong() and 0xFFFF_FFFFL
                val type = String(ByteArray(4).also(input::readFully), Charsets.US_ASCII)
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
                    "Malformed $type box while reading LQ-031 payload"
                }
                if (type == "mdat") {
                    require(boxBytes - headerBytes >= expectedBytes)
                    input.seek(cursor + headerBytes)
                    return ByteArray(expectedBytes.toInt()).also(input::readFully)
                }
                cursor += boxBytes
            }
        }
        error("No mdat payload found in ${file.absolutePath}")
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
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(Locale.US, byte) }
}
