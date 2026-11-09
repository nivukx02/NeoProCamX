package com.neoproc.neoproccamx.ui

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.StatFs
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/** Builds a continuous, packet-exact source for the ten-minute CP-190 hardware decoder gate. */
@RunWith(AndroidJUnit4::class)
class PlaybackEnduranceFixtureInstrumentedTest {

    @Test
    fun buildContinuousPacketExactFixture() {
        buildFixture(
            includeAudio = true,
            normalizeSingleTrackTimeline = false,
            defaultOutputName = DEFAULT_OUTPUT_NAME,
            purpose = "CP-190 continuous packet-exact 4K A/V playback fixture"
        )
    }

    /**
     * Decoder-cadence control for CP-190.
     *
     * The acquisition sample used by this checkpoint has a shorter AAC track than video. Repeating
     * both tracks on the video duration inserts an artificial audio gap at every join, so Media3's
     * audio clock can legitimately make the video renderer catch up. This fixture keeps every video
     * access unit byte-for-byte intact while normalizing only the join timestamps to the measured
     * source-frame cadence. Audio behavior remains covered by the original A/V asset and LQ-028;
     * this control isolates sustained hardware video decode and presentation.
     */
    @Test
    fun buildContinuousVideoOnlyPacketExactFixture() {
        buildFixture(
            includeAudio = false,
            normalizeSingleTrackTimeline = true,
            defaultOutputName = DEFAULT_VIDEO_ONLY_OUTPUT_NAME,
            purpose = "CP-190 continuous packet-exact 4K video decoder cadence fixture"
        )
    }

    /** Validates a previously generated fixture without rewriting its multi-gigabyte payload. */
    @Test
    fun validateContinuousVideoOnlyPacketExactFixture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val path = requiredArg(arguments, ARG_OUTPUT_PATH)
        val fixture = File(path)
        check(fixture.isFile && fixture.canRead() && fixture.length() > 0L) {
            "Fixture is not readable: ${fixture.absolutePath}"
        }
        val verification = inspect(fixture)
        val root = requireNotNull(instrumentation.targetContext.getExternalFilesDir("neo-qa-playback"))
        check(root.isDirectory || root.mkdirs()) { "Unable to create ${root.absolutePath}" }
        File(root, "${fixture.name}.validation.json").writeText(
            verificationJson(verification)
                .put("schemaVersion", 1)
                .put("purpose", "CP-190 existing continuous packet-exact video fixture validation")
                .put("outputPath", fixture.absolutePath)
                .put("outputSha256", sha256(Uri.fromFile(fixture)))
                .put("outputBytes", fixture.length())
                .toString(2),
            Charsets.UTF_8
        )
        assertContinuousVideoOnlyFixture(verification, TARGET_DURATION_SECONDS)
    }

    private fun buildFixture(
        includeAudio: Boolean,
        normalizeSingleTrackTimeline: Boolean,
        defaultOutputName: String,
        purpose: String
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val sourceUri = Uri.parse(requiredArg(arguments, ARG_SOURCE_URI))
        val durationSeconds = arguments.getString(ARG_DURATION_SECONDS)
            ?.toLongOrNull()
            ?.coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)
            ?: TARGET_DURATION_SECONDS
        val outputName = arguments.getString(ARG_OUTPUT_NAME)
            ?.replace(Regex("[^A-Za-z0-9_.-]"), "_")
            ?.takeIf(String::isNotBlank)
            ?: defaultOutputName
        val root = requireNotNull(instrumentation.targetContext.getExternalFilesDir("neo-qa-playback"))
        check(root.isDirectory || root.mkdirs()) { "Unable to create ${root.absolutePath}" }
        val output = File(root, outputName)
        val temporary = File(root, ".${output.name}.tmp-${System.nanoTime()}")

        val sourceDescriptor = instrumentation.targetContext.contentResolver
            .openAssetFileDescriptor(sourceUri, "r")
            ?: throw AssertionError("Unable to open source: $sourceUri")
        val extractor = MediaExtractor()
        val muxer: MediaMuxer
        val trackMap = LinkedHashMap<Int, Int>()
        val trackDescriptions = JSONArray()
        val trackTimelineDescriptions = JSONArray()
        val sourceDurationUs: Long
        val sourceSizeBytes = sourceDescriptor.length.takeIf { it > 0L } ?: 0L
        sourceDescriptor.use { descriptor ->
            extractor.setDataSource(
                descriptor.fileDescriptor,
                descriptor.startOffset,
                descriptor.length
            )
            check(extractor.trackCount > 0) { "Source has no media tracks" }
            var duration = 0L
            var maxInputBytes = MIN_SAMPLE_BUFFER_BYTES
            val formats = ArrayList<Pair<Int, MediaFormat>>(extractor.trackCount)
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val selected = mime.startsWith("video/") ||
                    (includeAudio && mime.startsWith("audio/"))
                if (!selected) continue
                formats += trackIndex to format
                duration = maxOf(duration, format.longOrZero(MediaFormat.KEY_DURATION))
                maxInputBytes = maxOf(maxInputBytes, format.intOrZero(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            check(formats.any { it.second.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }) {
                "Source has no video track"
            }
            check(duration > 0L) { "Source does not expose a positive duration" }
            sourceDurationUs = duration
            formats.forEach { (sourceTrack, _) -> extractor.selectTrack(sourceTrack) }
            val trackTimelines = inspectSelectedTrackTimelines(extractor, formats.map { it.first }.toSet())
            check(trackTimelines.keys.containsAll(formats.map { it.first })) {
                "Unable to inspect every selected source track"
            }
            trackTimelines.toSortedMap().forEach { (track, timeline) ->
                trackTimelineDescriptions.put(
                    JSONObject()
                        .put("sourceTrack", track)
                        .put("sampleCount", timeline.sampleCount)
                        .put("firstPtsUs", timeline.firstPtsUs)
                        .put("lastPtsUs", timeline.lastPtsUs)
                        .put("medianCadenceUs", timeline.cadenceUs)
                        .put("cycleDurationUs", timeline.cycleDurationUs)
                )
            }
            val fixtureCycleUs = if (normalizeSingleTrackTimeline) {
                check(formats.size == 1) {
                    "Normalized packet-exact fixture must contain exactly one selected track"
                }
                requireNotNull(trackTimelines[formats.single().first]).cycleDurationUs
            } else {
                sourceDurationUs
            }
            val repeats = ((durationSeconds * 1_000_000L + fixtureCycleUs - 1L) / fixtureCycleUs)
                .coerceAtLeast(1L)
            val expectedBytes = Math.multiplyExact(maxOf(1L, sourceSizeBytes), repeats)
            val availableBytes = StatFs(root.absolutePath).availableBytes
            check(availableBytes >= expectedBytes + STORAGE_RESERVE_BYTES) {
                "Insufficient storage: available=$availableBytes required=${expectedBytes + STORAGE_RESERVE_BYTES}"
            }

            muxer = MediaMuxer(temporary.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                formats.forEach { (sourceTrack, format) ->
                    trackMap[sourceTrack] = muxer.addTrack(format)
                    extractor.selectTrack(sourceTrack)
                    trackDescriptions.put(formatToJson(sourceTrack, format))
                }
                muxer.start()
                val sampleBuffer = ByteBuffer.allocateDirect(
                    maxOf(MIN_SAMPLE_BUFFER_BYTES, maxInputBytes.coerceAtMost(MAX_SAMPLE_BUFFER_BYTES))
                )
                val info = MediaCodec.BufferInfo()
                var samplesWritten = 0L
                var videoSamplesWritten = 0L
                var lastPtsUs = -1L
                var loopOffsetUs = 0L
                while (loopOffsetUs < durationSeconds * 1_000_000L) {
                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    while (true) {
                        val sourceTrack = extractor.sampleTrackIndex
                        if (sourceTrack < 0) break
                        val sourcePtsUs = extractor.sampleTime
                        val timeline = requireNotNull(trackTimelines[sourceTrack]) {
                            "Missing source timeline for selected track $sourceTrack"
                        }
                        val relativePtsUs = if (normalizeSingleTrackTimeline) {
                            Math.subtractExact(sourcePtsUs.coerceAtLeast(0L), timeline.firstPtsUs)
                        } else {
                            sourcePtsUs.coerceAtLeast(0L)
                        }
                        val outputPtsUs = Math.addExact(loopOffsetUs, relativePtsUs)
                        if (outputPtsUs >= durationSeconds * 1_000_000L) {
                            if (!extractor.advance()) break
                            continue
                        }
                        sampleBuffer.clear()
                        val size = extractor.readSampleData(sampleBuffer, 0)
                        check(size >= 0) { "Unexpected sample read failure at $outputPtsUs us" }
                        info.set(0, size, outputPtsUs, extractor.sampleFlags)
                        muxer.writeSampleData(
                            requireNotNull(trackMap[sourceTrack]) { "Unmapped track $sourceTrack" },
                            sampleBuffer,
                            info
                        )
                        samplesWritten++
                        if (extractor.getTrackFormat(sourceTrack)
                                .getString(MediaFormat.KEY_MIME)
                                ?.startsWith("video/") == true
                        ) {
                            videoSamplesWritten++
                        }
                        lastPtsUs = maxOf(lastPtsUs, outputPtsUs)
                        if (!extractor.advance()) break
                    }
                    loopOffsetUs = Math.addExact(loopOffsetUs, fixtureCycleUs)
                }
                check(videoSamplesWritten >= durationSeconds * MINIMUM_VIDEO_FPS) {
                    "Only $videoSamplesWritten video samples were copied"
                }
                check(lastPtsUs >= durationSeconds * 1_000_000L - sourceDurationUs) {
                    "Fixture ended too early: lastPtsUs=$lastPtsUs"
                }
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
                extractor.release()
            }
        }

        check(temporary.isFile && temporary.length() > sourceSizeBytes) {
            "Packet-exact endurance fixture was not written"
        }
        runCatching {
            Files.move(
                temporary.toPath(),
                output.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }.getOrElse {
            Files.move(
                temporary.toPath(),
                output.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }

        val verification = inspect(output)
        val report = JSONObject()
            .put("schemaVersion", 1)
            .put("purpose", purpose)
            .put("includeAudio", includeAudio)
            .put("normalizedSingleTrackTimeline", normalizeSingleTrackTimeline)
            .put("sourceUri", sourceUri.toString())
            .put("sourceSha256", sha256(sourceUri))
            .put("sourceBytes", sourceSizeBytes)
            .put("sourceDurationUs", sourceDurationUs)
            .put("targetDurationSeconds", durationSeconds)
            .put("outputPath", output.absolutePath)
            .put("outputSha256", sha256(Uri.fromFile(output)))
            .put("outputBytes", output.length())
            .put("outputDurationUs", verification.durationUs)
            .put("videoSamples", verification.videoSamples)
            .put("audioSamples", verification.audioSamples)
            .put("videoFirstPtsUs", verification.videoFirstPtsUs)
            .put("videoLastPtsUs", verification.videoLastPtsUs)
            .put("videoMinDeltaUs", verification.videoMinDeltaUs)
            .put("videoMedianDeltaUs", verification.videoMedianDeltaUs)
            .put("videoMaxDeltaUs", verification.videoMaxDeltaUs)
            .put("videoDecodeOrderPtsRegressions", verification.videoDecodeOrderPtsRegressions)
            .put("videoRepeatedPts", verification.videoRepeatedPts)
            .put("tracks", trackDescriptions)
            .put("sourceTrackTimelines", trackTimelineDescriptions)
        val reportFile = File(root, "$outputName.json")
        reportFile.writeText(report.toString(2), Charsets.UTF_8)
        assertTrue("Fixture report missing", reportFile.isFile)
        assertTrue("Output duration ${verification.durationUs} us", verification.durationUs >= durationSeconds * 1_000_000L - 50_000L)
        assertTrue("Output has no video samples", verification.videoSamples > 0L)
        if (normalizeSingleTrackTimeline) {
            assertContinuousVideoOnlyFixture(verification, durationSeconds)
        }
    }

    private fun inspectSelectedTrackTimelines(
        extractor: MediaExtractor,
        selectedTracks: Set<Int>
    ): Map<Int, TrackTimeline> {
        val ptsByTrack = selectedTracks.associateWith { ArrayList<Long>() }.toMutableMap()
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (true) {
            val track = extractor.sampleTrackIndex
            if (track < 0) break
            val ptsUs = extractor.sampleTime
            if (track in selectedTracks && ptsUs >= 0L) {
                requireNotNull(ptsByTrack[track]).add(ptsUs)
            }
            if (!extractor.advance()) break
        }
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        return ptsByTrack.mapValues { (track, sourcePts) ->
            check(sourcePts.isNotEmpty()) { "Selected track $track has no samples" }
            val sortedPts = sourcePts.distinct().sorted()
            val positiveDeltas = sortedPts.zipWithNext { left, right -> right - left }
                .filter { it > 0L }
                .sorted()
            check(positiveDeltas.isNotEmpty()) { "Selected track $track has no positive PTS cadence" }
            val cadenceUs = positiveDeltas[positiveDeltas.size / 2]
            val firstPtsUs = sortedPts.first()
            val lastPtsUs = sortedPts.last()
            TrackTimeline(
                firstPtsUs = firstPtsUs,
                lastPtsUs = lastPtsUs,
                cadenceUs = cadenceUs,
                cycleDurationUs = Math.addExact(
                    Math.subtractExact(lastPtsUs, firstPtsUs),
                    cadenceUs
                ),
                sampleCount = sourcePts.size
            )
        }
    }

    private fun inspect(file: File): FixtureVerification {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var durationUs = 0L
            var videoSamples = 0L
            var audioSamples = 0L
            var previousDecodeOrderVideoPtsUs = -1L
            var videoDecodeOrderPtsRegressions = 0L
            val videoPts = ArrayList<Long>()
            val mimeByTrack = HashMap<Int, String>()
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                mimeByTrack[track] = mime
                durationUs = maxOf(durationUs, format.longOrZero(MediaFormat.KEY_DURATION))
                if (mime.startsWith("video/") || mime.startsWith("audio/")) extractor.selectTrack(track)
            }
            while (extractor.sampleTrackIndex >= 0) {
                val mime = mimeByTrack[extractor.sampleTrackIndex].orEmpty()
                if (mime.startsWith("video/")) {
                    val ptsUs = extractor.sampleTime
                    if (previousDecodeOrderVideoPtsUs >= 0L && ptsUs < previousDecodeOrderVideoPtsUs) {
                        videoDecodeOrderPtsRegressions++
                    }
                    previousDecodeOrderVideoPtsUs = ptsUs
                    videoPts.add(ptsUs)
                    videoSamples++
                }
                if (mime.startsWith("audio/")) audioSamples++
                if (!extractor.advance()) break
            }
            val sortedVideoPts = videoPts.sorted()
            var videoMinDeltaUs = Long.MAX_VALUE
            var videoMedianDeltaUs = 0L
            var videoMaxDeltaUs = 0L
            var videoRepeatedPts = 0L
            val positiveVideoDeltas = ArrayList<Long>(maxOf(0, sortedVideoPts.size - 1))
            sortedVideoPts.zipWithNext { left, right -> right - left }.forEach { deltaUs ->
                if (deltaUs == 0L) {
                    videoRepeatedPts++
                } else if (deltaUs > 0L) {
                    positiveVideoDeltas.add(deltaUs)
                    videoMinDeltaUs = minOf(videoMinDeltaUs, deltaUs)
                    videoMaxDeltaUs = maxOf(videoMaxDeltaUs, deltaUs)
                }
            }
            if (positiveVideoDeltas.isNotEmpty()) {
                positiveVideoDeltas.sort()
                videoMedianDeltaUs = positiveVideoDeltas[positiveVideoDeltas.size / 2]
            }
            FixtureVerification(
                durationUs = durationUs,
                videoSamples = videoSamples,
                audioSamples = audioSamples,
                videoFirstPtsUs = sortedVideoPts.firstOrNull()?.coerceAtLeast(0L) ?: 0L,
                videoLastPtsUs = sortedVideoPts.lastOrNull()?.coerceAtLeast(0L) ?: 0L,
                videoMinDeltaUs = videoMinDeltaUs.takeUnless { it == Long.MAX_VALUE } ?: 0L,
                videoMedianDeltaUs = videoMedianDeltaUs,
                videoMaxDeltaUs = videoMaxDeltaUs,
                videoDecodeOrderPtsRegressions = videoDecodeOrderPtsRegressions,
                videoRepeatedPts = videoRepeatedPts
            )
        } finally {
            extractor.release()
        }
    }

    private fun formatToJson(track: Int, format: MediaFormat): JSONObject = JSONObject()
        .put("sourceTrack", track)
        .put("mime", format.getString(MediaFormat.KEY_MIME))
        .put("width", format.intOrZero(MediaFormat.KEY_WIDTH))
        .put("height", format.intOrZero(MediaFormat.KEY_HEIGHT))
        .put("frameRate", format.intOrZero(MediaFormat.KEY_FRAME_RATE))
        .put("durationUs", format.longOrZero(MediaFormat.KEY_DURATION))

    private fun MediaFormat.intOrZero(key: String): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

    private fun MediaFormat.longOrZero(key: String): Long =
        if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(0L) else 0L

    private fun sha256(uri: Uri): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = context.contentResolver.openInputStream(uri)
            ?: throw AssertionError("Unable to hash $uri")
        return input.buffered(HASH_BUFFER_BYTES).use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
            digest.digest().joinToString(separator = "") { byte ->
                String.format(Locale.ROOT, "%02x", byte.toInt() and 0xFF)
            }
        }
    }

    private fun requiredArg(arguments: android.os.Bundle, key: String): String =
        arguments.getString(key)?.takeIf(String::isNotBlank)
            ?: throw AssertionError("Supply -e $key <value>")

    private fun assertContinuousVideoOnlyFixture(
        verification: FixtureVerification,
        durationSeconds: Long
    ) {
        assertTrue(
            "Output duration ${verification.durationUs} us",
            verification.durationUs >= durationSeconds * 1_000_000L - 50_000L
        )
        assertTrue("Output has no video samples", verification.videoSamples > 0L)
        assertTrue("Video-only cadence fixture unexpectedly contains audio", verification.audioSamples == 0L)
        assertTrue("Video PTS repeated ${verification.videoRepeatedPts} times", verification.videoRepeatedPts == 0L)
        val cadenceUs = verification.videoMedianDeltaUs
        val minimumCadenceUs = cadenceUs * 9L / 10L
        val maximumCadenceUs = cadenceUs * 11L / 10L
        assertTrue(
            "Video presentation cadence is discontinuous: min=${verification.videoMinDeltaUs}us " +
                "median=${cadenceUs}us max=${verification.videoMaxDeltaUs}us",
            cadenceUs > 0L &&
                verification.videoMinDeltaUs >= minimumCadenceUs &&
                verification.videoMaxDeltaUs <= maximumCadenceUs
        )
    }

    private fun verificationJson(verification: FixtureVerification): JSONObject = JSONObject()
        .put("outputDurationUs", verification.durationUs)
        .put("videoSamples", verification.videoSamples)
        .put("audioSamples", verification.audioSamples)
        .put("videoFirstPtsUs", verification.videoFirstPtsUs)
        .put("videoLastPtsUs", verification.videoLastPtsUs)
        .put("videoMinDeltaUs", verification.videoMinDeltaUs)
        .put("videoMedianDeltaUs", verification.videoMedianDeltaUs)
        .put("videoMaxDeltaUs", verification.videoMaxDeltaUs)
        .put("videoDecodeOrderPtsRegressions", verification.videoDecodeOrderPtsRegressions)
        .put("videoRepeatedPts", verification.videoRepeatedPts)

    private data class FixtureVerification(
        val durationUs: Long,
        val videoSamples: Long,
        val audioSamples: Long,
        val videoFirstPtsUs: Long,
        val videoLastPtsUs: Long,
        val videoMinDeltaUs: Long,
        val videoMedianDeltaUs: Long,
        val videoMaxDeltaUs: Long,
        val videoDecodeOrderPtsRegressions: Long,
        val videoRepeatedPts: Long
    )

    private data class TrackTimeline(
        val firstPtsUs: Long,
        val lastPtsUs: Long,
        val cadenceUs: Long,
        val cycleDurationUs: Long,
        val sampleCount: Int
    )

    companion object {
        private const val ARG_SOURCE_URI = "neoPlaybackFixtureSourceUri"
        private const val ARG_DURATION_SECONDS = "neoPlaybackFixtureDurationSeconds"
        private const val ARG_OUTPUT_NAME = "neoPlaybackFixtureOutputName"
        private const val ARG_OUTPUT_PATH = "neoPlaybackFixtureOutputPath"
        private const val TARGET_DURATION_SECONDS = 600L
        private const val MIN_DURATION_SECONDS = 600L
        private const val MAX_DURATION_SECONDS = 900L
        private const val DEFAULT_OUTPUT_NAME = "original-4k-continuous-10min.mp4"
        private const val DEFAULT_VIDEO_ONLY_OUTPUT_NAME = "original-4k-video-only-continuous-10min.mp4"
        private const val MINIMUM_VIDEO_FPS = 24L
        private const val MIN_SAMPLE_BUFFER_BYTES = 16 * 1024 * 1024
        private const val MAX_SAMPLE_BUFFER_BYTES = 64 * 1024 * 1024
        private const val STORAGE_RESERVE_BYTES = 10L * 1024L * 1024L * 1024L
        private const val HASH_BUFFER_BYTES = 4 * 1024 * 1024
    }
}
