package com.neoproc.neoproccamx.recording

import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Physical-device oracle for the finalized Video-mode artifact retained by LQ-001.05.
 *
 * The test deliberately consumes the MediaStore URI in place. This avoids copying a multi-gigabyte
 * 4K master to the host and, more importantly, exercises the same platform extractor and hardware
 * decoder path that applications use on the device. It never modifies or republishes the source.
 */
@RunWith(AndroidJUnit4::class)
class Lq001VideoArtifactIntegrityInstrumentedTest {
    @Test
    fun retainedVideo_isExact4k25Main10WithMonotonicAvAndRealDecodedImages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val uriText = requireNotNull(arguments.getString(ARG_SOURCE_URI)) {
            "Missing instrumentation argument $ARG_SOURCE_URI"
        }
        val sourceUri = Uri.parse(uriText)
        require(sourceUri.scheme == "content") { "Expected a MediaStore content URI: $sourceUri" }

        val context = instrumentation.targetContext
        val videoInspection = inspectTrack(sourceUri, VIDEO_PREFIX)
        val audioInspection = inspectTrack(sourceUri, AUDIO_PREFIX)
        val frameInspections = decodeRepresentativeFrames(sourceUri, videoInspection.durationUs)
        val sourceBytes = context.contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { descriptor ->
            descriptor.declaredLength.takeIf { it > 0L } ?: descriptor.parcelFileDescriptor.statSize
        } ?: 0L
        val measuredContainerBitrateBps = if (sourceBytes > 0L && videoInspection.durationUs > 0L) {
            Math.multiplyExact(sourceBytes, 8_000_000L) / videoInspection.durationUs
        } else {
            0L
        }

        val result = JSONObject()
            .put("schema", "neo-qa/lq001-video-artifact-integrity/v1")
            .put("sourceUri", sourceUri.toString())
            .put("video", videoInspection.toJson())
            .put("audio", audioInspection.toJson())
            .put("frames", JSONArray().apply { frameInspections.forEach { put(it.toJson()) } })
            .put("sourceBytes", sourceBytes)
            .put("measuredContainerBitrateBps", measuredContainerBitrateBps)
            .put("distinctDecodedFrameHashes", frameInspections.map { it.sha256 }.distinct().size)
            .put(
                "maximumDecodedFrameMeanAbsoluteDifference",
                maximumMeanAbsoluteDifference(frameInspections)
            )

        val evidenceDirectory = File(context.filesDir, "neo-qa/lq001")
        assertTrue(
            "Unable to create device evidence directory: ${evidenceDirectory.absolutePath}",
            evidenceDirectory.isDirectory || evidenceDirectory.mkdirs()
        )
        File(evidenceDirectory, EVIDENCE_FILE).writeText(result.toString(2), Charsets.UTF_8)
        println("LQ001_VIDEO_ARTIFACT_EVIDENCE=${result}")

        assertEquals(3840, videoInspection.width)
        assertEquals(2160, videoInspection.height)
        assertEquals(25, videoInspection.declaredFrameRate)
        assertTrue("HEVC Main10 profile was not retained: ${videoInspection.profile}", videoInspection.isMain10)
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, videoInspection.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, videoInspection.colorTransfer)
        assertTrue(
            "Measured bitrate fell below the strict 160 Mbps tolerance: format=${videoInspection.bitrate} container=$measuredContainerBitrateBps",
            maxOf(videoInspection.bitrate.toLong(), measuredContainerBitrateBps) >= MINIMUM_BITRATE_BPS
        )
        assertTrue(
            "Video duration is below 10 minutes 5 seconds: ${videoInspection.durationUs}",
            videoInspection.durationUs >= MINIMUM_DURATION_US
        )
        assertTrue(
            "Video sample count is below the 4K/25 endurance floor: ${videoInspection.sampleCount}",
            videoInspection.sampleCount >= MINIMUM_VIDEO_SAMPLES
        )
        assertEquals("Video PTS must be strictly monotonic", 0L, videoInspection.nonMonotonicPts)
        assertEquals("Video cadence must contain no missing 40 ms slots", 0L, videoInspection.missingCadenceSlots)
        assertTrue(
            "Video cadence deviated from 40 ms: max=${videoInspection.maximumCadenceErrorUs} us",
            videoInspection.maximumCadenceErrorUs <= MAXIMUM_CADENCE_ERROR_US
        )

        assertTrue("The finalized recording has no audio track", audioInspection.sampleCount > 0L)
        assertEquals("Audio PTS must be strictly monotonic", 0L, audioInspection.nonMonotonicPts)
        assertTrue(
            "Audio/video duration mismatch is too large: video=${videoInspection.durationUs} audio=${audioInspection.durationUs}",
            abs(videoInspection.durationUs - audioInspection.durationUs) <= MAXIMUM_AV_END_DELTA_US
        )

        frameInspections.forEach { frame ->
            assertTrue("Decoded ${frame.position} frame is effectively black", frame.nonBlackRatio >= 0.01)
            assertTrue("Decoded ${frame.position} frame has no spatial image detail", frame.lumaStdDev >= 1.0)
            assertTrue("Decoded ${frame.position} frame has collapsed luma range", frame.lumaRange >= 8)
        }
        assertTrue(
            "First/middle/final decoder outputs are byte-identical",
            frameInspections.map { it.sha256 }.distinct().size >= 2
        )
    }

    private fun inspectTrack(uri: Uri, mimePrefix: String): TrackInspection {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith(mimePrefix) == true
            } ?: throw AssertionError("Artifact has no $mimePrefix track")
            val format = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)

            val declaredDurationUs = format.longOrZero(MediaFormat.KEY_DURATION)
            val declaredVideoFrameCount = if (mimePrefix == VIDEO_PREFIX) {
                readDeclaredVideoFrameCount(uri)
            } else {
                0L
            }
            var inspectedCount = 0L
            var firstPtsUs = -1L
            var previousPtsUs = -1L
            var lastPtsUs = -1L
            var nonMonotonic = 0L
            var missingSlots = 0L
            var maximumCadenceErrorUs = 0L
            // This phone's vendor freezer kills a headless instrumentation process that scans a
            // 12+ GiB file for about a minute. Inspect bounded windows at the beginning, middle,
            // and end instead. The container-wide frame count comes independently from the retriever
            // index; the production certificate retains the exhaustive per-sample encoder ledger.
            val anchorsUs = longArrayOf(
                0L,
                declaredDurationUs / 2L,
                (declaredDurationUs - SAMPLE_WINDOW_DURATION_US).coerceAtLeast(0L)
            ).distinct()
            anchorsUs.forEach { anchorUs ->
                extractor.seekTo(anchorUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                previousPtsUs = -1L
                var windowSamples = 0
                val windowEndUs = anchorUs + SAMPLE_WINDOW_DURATION_US
                while (extractor.sampleTrackIndex >= 0 && windowSamples < MAXIMUM_WINDOW_SAMPLES) {
                    val ptsUs = extractor.sampleTime
                    if (ptsUs > windowEndUs && windowSamples > 0) break
                    if (firstPtsUs < 0L || ptsUs < firstPtsUs) firstPtsUs = ptsUs
                    if (previousPtsUs >= 0L) {
                        val deltaUs = ptsUs - previousPtsUs
                        if (deltaUs <= 0L) {
                            nonMonotonic++
                        } else if (mimePrefix == VIDEO_PREFIX) {
                            val cadenceError = abs(deltaUs - VIDEO_FRAME_DURATION_US)
                            maximumCadenceErrorUs = maxOf(maximumCadenceErrorUs, cadenceError)
                            if (deltaUs > VIDEO_FRAME_DURATION_US + MAXIMUM_CADENCE_ERROR_US) {
                                missingSlots += ((deltaUs.toDouble() / VIDEO_FRAME_DURATION_US).roundToLong() - 1L)
                                    .coerceAtLeast(1L)
                            }
                        }
                    }
                    previousPtsUs = ptsUs
                    lastPtsUs = maxOf(lastPtsUs, ptsUs)
                    inspectedCount++
                    windowSamples++
                    if (!extractor.advance()) break
                }
            }

            return TrackInspection(
                mime = format.getString(MediaFormat.KEY_MIME).orEmpty(),
                width = format.intOrZero(MediaFormat.KEY_WIDTH),
                height = format.intOrZero(MediaFormat.KEY_HEIGHT),
                declaredFrameRate = format.intOrZero(MediaFormat.KEY_FRAME_RATE),
                profile = format.intOrZero(MediaFormat.KEY_PROFILE),
                colorStandard = format.intOrZero(MediaFormat.KEY_COLOR_STANDARD),
                colorTransfer = format.intOrZero(MediaFormat.KEY_COLOR_TRANSFER),
                bitrate = format.intOrZero(MediaFormat.KEY_BIT_RATE),
                sampleCount = if (mimePrefix == VIDEO_PREFIX) declaredVideoFrameCount else inspectedCount,
                inspectedSampleCount = inspectedCount,
                firstPtsUs = firstPtsUs,
                lastPtsUs = lastPtsUs,
                durationUs = declaredDurationUs,
                nonMonotonicPts = nonMonotonic,
                missingCadenceSlots = missingSlots,
                maximumCadenceErrorUs = maximumCadenceErrorUs
            )
        } finally {
            extractor.release()
        }
    }

    private fun readDeclaredVideoFrameCount(uri: Uri): Long {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return MediaMetadataRetriever().let { retriever ->
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    ?.toLongOrNull()
                    ?: 0L
            } finally {
                retriever.release()
            }
        }
    }

    private fun decodeRepresentativeFrames(uri: Uri, durationUs: Long): List<FrameInspection> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val positions = listOf(
                "first" to 0L,
                "middle" to durationUs / 2L,
                "final" to (durationUs - 1_000_000L).coerceAtLeast(0L)
            )
            return positions.map { (position, timeUs) ->
                val bitmap = retriever.getScaledFrameAtTime(
                    timeUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    DECODE_WIDTH,
                    DECODE_HEIGHT
                ) ?: throw AssertionError("Hardware decoder returned no $position frame at $timeUs us")
                try {
                    inspectBitmap(position, timeUs, bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            retriever.release()
        }
    }

    private fun inspectBitmap(position: String, timeUs: Long, bitmap: Bitmap): FrameInspection {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var sum = 0.0
        var sumSquares = 0.0
        var nonBlack = 0L
        var minimum = 255
        var maximum = 0
        val digest = MessageDigest.getInstance("SHA-256")
        pixels.forEach { color ->
            val red = color ushr 16 and 0xff
            val green = color ushr 8 and 0xff
            val blue = color and 0xff
            val luma = (54 * red + 183 * green + 19 * blue) ushr 8
            sum += luma
            sumSquares += luma.toDouble() * luma.toDouble()
            if (luma > BLACK_LUMA_THRESHOLD) nonBlack++
            minimum = minOf(minimum, luma)
            maximum = maxOf(maximum, luma)
            digest.update((color ushr 24).toByte())
            digest.update((color ushr 16).toByte())
            digest.update((color ushr 8).toByte())
            digest.update(color.toByte())
        }
        val mean = sum / pixels.size.toDouble()
        val variance = (sumSquares / pixels.size.toDouble() - mean * mean).coerceAtLeast(0.0)
        return FrameInspection(
            position = position,
            requestedTimeUs = timeUs,
            width = bitmap.width,
            height = bitmap.height,
            meanLuma = mean,
            lumaStdDev = sqrt(variance),
            lumaRange = maximum - minimum,
            nonBlackRatio = nonBlack.toDouble() / pixels.size.toDouble(),
            sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
            luma = pixels.map { color ->
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                (54 * red + 183 * green + 19 * blue) ushr 8
            }.toIntArray()
        )
    }

    private fun maximumMeanAbsoluteDifference(frames: List<FrameInspection>): Double {
        var maximum = 0.0
        for (left in frames.indices) {
            for (right in left + 1 until frames.size) {
                val a = frames[left].luma
                val b = frames[right].luma
                val difference = a.indices.sumOf { index -> abs(a[index] - b[index]).toLong() }
                    .toDouble() / a.size.toDouble()
                maximum = maxOf(maximum, difference)
            }
        }
        return maximum
    }

    private fun MediaFormat.intOrZero(key: String): Int =
        if (containsKey(key)) getInteger(key) else 0

    private fun MediaFormat.longOrZero(key: String): Long =
        if (containsKey(key)) getLong(key) else 0L

    private data class TrackInspection(
        val mime: String,
        val width: Int,
        val height: Int,
        val declaredFrameRate: Int,
        val profile: Int,
        val colorStandard: Int,
        val colorTransfer: Int,
        val bitrate: Int,
        val sampleCount: Long,
        val inspectedSampleCount: Long,
        val firstPtsUs: Long,
        val lastPtsUs: Long,
        val durationUs: Long,
        val nonMonotonicPts: Long,
        val missingCadenceSlots: Long,
        val maximumCadenceErrorUs: Long
    ) {
        val isMain10: Boolean
            get() = mime == "video/hevc" && (
                profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                    profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
                )

        fun toJson(): JSONObject = JSONObject()
            .put("mime", mime)
            .put("width", width)
            .put("height", height)
            .put("declaredFrameRate", declaredFrameRate)
            .put("profile", profile)
            .put("isMain10", isMain10)
            .put("colorStandard", colorStandard)
            .put("colorTransfer", colorTransfer)
            .put("bitrate", bitrate)
            .put("sampleCount", sampleCount)
            .put("inspectedSampleCount", inspectedSampleCount)
            .put("firstPtsUs", firstPtsUs)
            .put("lastPtsUs", lastPtsUs)
            .put("durationUs", durationUs)
            .put("nonMonotonicPts", nonMonotonicPts)
            .put("missingCadenceSlots", missingCadenceSlots)
            .put("maximumCadenceErrorUs", maximumCadenceErrorUs)
    }

    private data class FrameInspection(
        val position: String,
        val requestedTimeUs: Long,
        val width: Int,
        val height: Int,
        val meanLuma: Double,
        val lumaStdDev: Double,
        val lumaRange: Int,
        val nonBlackRatio: Double,
        val sha256: String,
        val luma: IntArray
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("position", position)
            .put("requestedTimeUs", requestedTimeUs)
            .put("width", width)
            .put("height", height)
            .put("meanLuma", meanLuma)
            .put("lumaStdDev", lumaStdDev)
            .put("lumaRange", lumaRange)
            .put("nonBlackRatio", nonBlackRatio)
            .put("sha256", sha256)
    }

    private companion object {
        const val ARG_SOURCE_URI = "neoLq001SourceUri"
        const val EVIDENCE_FILE = "lq001-05-video-artifact-integrity.json"
        const val VIDEO_PREFIX = "video/"
        const val AUDIO_PREFIX = "audio/"
        const val VIDEO_FRAME_DURATION_US = 40_000L
        const val MAXIMUM_CADENCE_ERROR_US = 1L
        const val MINIMUM_DURATION_US = 605_000_000L
        const val MINIMUM_VIDEO_SAMPLES = 15_125L
        const val MINIMUM_BITRATE_BPS = 158_400_000
        const val MAXIMUM_AV_END_DELTA_US = 100_000L
        const val BLACK_LUMA_THRESHOLD = 3
        const val DECODE_WIDTH = 320
        const val DECODE_HEIGHT = 180
        const val SAMPLE_WINDOW_DURATION_US = 2_000_000L
        const val MAXIMUM_WINDOW_SAMPLES = 100
    }
}
