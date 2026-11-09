package com.neoproc.neoproccamx.recording

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoproc.neoproccamx.pipeline.NprawFrameCodec
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Publishes a codec-valid RAW fixture for the production-UI portion of LQ-031.
 *
 * LQ-030 deliberately uses a tiny 96x64 fixture because its uncompressed conversion matrix does not
 * need a hardware codec. That geometry is not an Android HEVC encoder contract and must remain useful
 * negative evidence. This independent 640x360 fixture is aligned to common Main10 hardware geometry,
 * retains a real multi-frame sensor timeline, and is large enough to exercise every HEVC conversion
 * route without changing production resolution, bit depth, colour processing, or transport selection.
 */
@RunWith(AndroidJUnit4::class)
class Lq031UiFixtureInstrumentedTest {
    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 360
        const val FPS = 25
        const val FRAME_COUNT = 6
        const val FRAME_DURATION_US = 40_000L
        const val SENSOR_TIMESTAMP_ORIGIN_NS = 11_000_000_000L
    }

    @Test
    fun publishCodecValidProductionUiRawFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val evidenceRoot = requireNotNull(context.getExternalFilesDir("neo-qa/lq031-ui-fixture")) {
            "App-scoped LQ-031 UI-fixture evidence directory is unavailable"
        }
        assertTrue(
            "Unable to create LQ-031 UI-fixture evidence directory",
            evidenceRoot.isDirectory || evidenceRoot.mkdirs()
        )

        val token = "${System.currentTimeMillis()}-${System.nanoTime()}"
        val clipName = "NEO_RADIANCE_RAW_LQ031_UI_$token"
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/CamX/$clipName/"
        val metadata = rawMetadata()
        val metadataBytes = RawDevelopMetadataSidecar.encode(metadata)
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
                        timestamp = SENSOR_TIMESTAMP_ORIGIN_NS +
                            frameIndex * FRAME_DURATION_US * 1_000L,
                        originalSize = WIDTH * HEIGHT * Short.SIZE_BYTES,
                        sensorWhiteLevel = metadata.sensorWhiteLevel
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
                ) ?: error("Unable to create LQ-031 UI fixture $displayName")
                createdUris += uri
                resolver.openOutputStream(uri, "w")?.use { it.write(payload) }
                    ?: error("Unable to write LQ-031 UI fixture $displayName")
                return uri
            }

            val streamUri = insertAndWrite(RawClipMediaStoreDiscovery.STREAM_NAME, streamBytes)
            val metadataUri = insertAndWrite(RawClipMediaStoreDiscovery.META_NAME, metadataBytes)
            createdUris.forEach { uri ->
                val updated = resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                assertTrue("MediaStore did not publish LQ-031 UI fixture $uri", updated > 0)
            }

            val discovered = RawClipMediaStoreDiscovery(context).listClips().singleOrNull { clip ->
                clip.name == clipName &&
                    clip.relativePath.trimEnd('/') == relativePath.trimEnd('/')
            }
            requireNotNull(discovered) {
                "Production RAW converter did not discover the LQ-031 UI fixture"
            }
            assertTrue("Published LQ-031 UI fixture remained pending", !discovered.streamPending)
            assertEquals(streamBytes.size.toLong(), discovered.streamBytes)

            val evidence = JSONObject()
                .put("schema", "neo-qa-lq031-production-ui-fixture-v1")
                .put("checkpoint", "LQ-031")
                .put("status", "PASS")
                .put("deviceManufacturer", Build.MANUFACTURER)
                .put("deviceModel", Build.MODEL)
                .put("androidRelease", Build.VERSION.RELEASE)
                .put("androidSdk", Build.VERSION.SDK_INT)
                .put("clipName", clipName)
                .put("relativePath", relativePath)
                .put("streamUri", streamUri.toString())
                .put("metadataUri", metadataUri.toString())
                .put("streamBytes", streamBytes.size)
                .put("streamSha256", sha256(streamBytes))
                .put("metadataBytes", metadataBytes.size)
                .put("metadataSha256", sha256(metadataBytes))
                .put("width", WIDTH)
                .put("height", HEIGHT)
                .put("fps", FPS)
                .put("frameCount", FRAME_COUNT)
                .put("frameDurationUs", FRAME_DURATION_US)
                .put("expectedDurationUs", FRAME_COUNT * FRAME_DURATION_US)
            val evidenceFile = File(evidenceRoot, "fixture-$token.json")
            evidenceFile.writeText(evidence.toString(2), Charsets.UTF_8)
            File(evidenceRoot, "LATEST").writeText("${evidenceFile.name}\n", Charsets.US_ASCII)
        } catch (failure: Throwable) {
            createdUris.asReversed().forEach { uri ->
                runCatching { resolver.delete(uri, null, null) }
            }
            throw failure
        }
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

    /** Structured edges, gradients, colour-site differences, and time variation resist trivial frames. */
    private fun syntheticRawSamples(frameIndex: Int): IntArray =
        IntArray(WIDTH * HEIGHT) { pixel ->
            val x = pixel % WIDTH
            val y = pixel / WIDTH
            val checker = if (((x / 32) + (y / 24) + frameIndex) and 1 == 0) 480 else -320
            val fineDetail = ((x * 37 + y * 53 + frameIndex * 97) xor (x * y)) and 0x1FF
            val movingEdge = if (x >= 64 + frameIndex * 23) 620 else -180
            (448 + x * 3 + y * 2 + checker + fineDetail + movingEdge).coerceIn(64, 4095)
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(Locale.US, byte) }
}
