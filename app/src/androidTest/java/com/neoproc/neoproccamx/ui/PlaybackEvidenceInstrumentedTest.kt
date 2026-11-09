package com.neoproc.neoproccamx.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

/**
 * Asset-driven physical oracle for CP-188, CP-190, CP-197 and CP-198.
 *
 * The checkpoint runner supplies a real recorded or converted asset through instrumentation args.
 * No fixture is synthesized because a generated clip cannot prove the app's acquisition/conversion
 * playback path. A missing asset is a hard harness failure so a successful instrumentation exit can
 * never be mistaken for physical playback evidence.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackEvidenceInstrumentedTest {

    @Test
    fun configuredMasterMeasuresCadenceErrorsPtsAndExactRelease() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val uriText = arguments.getString(ARG_URI)?.takeIf(String::isNotBlank)
            ?: throw AssertionError(
                "Supply -e $ARG_URI <content-or-file-uri>; missing physical source is not PASS"
            )
        val sourceUri = Uri.parse(uriText)
        val sourceBytes = authenticatePlaybackSourceBytes(sourceUri, arguments)
        val sourceSha256 = sourceBytes.sha256
        val minimumFrames = arguments.intArg(ARG_MINIMUM_FRAMES, DEFAULT_MINIMUM_FRAMES)
            .coerceAtLeast(2)
        val minimumFpsMilli = arguments.getString(ARG_MINIMUM_FPS_MILLI)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: throw AssertionError(
                "Supply -e $ARG_MINIMUM_FPS_MILLI <positive-milli-fps>; " +
                    "playback cadence cannot PASS without an explicit floor"
            )
        val allowedDropped = arguments.longArg(ARG_ALLOWED_DROPPED, 0L).coerceAtLeast(0L)
        val allowedRepeated = arguments.longArg(ARG_ALLOWED_REPEATED, 0L).coerceAtLeast(0L)
        val timeoutMs = arguments.longArg(ARG_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val label = arguments.getString(ARG_LABEL).orEmpty().ifBlank { "playback-master" }

        PlaybackEvidenceRegistry.clearForTesting()
        val intent = Intent(instrumentation.targetContext, MediaPreviewActivity::class.java).apply {
            putExtra(MediaPreviewActivity.EXTRA_URI, sourceUri.toString())
            putExtra(MediaPreviewActivity.EXTRA_MIME_TYPE, arguments.getString(ARG_MIME_TYPE))
            putExtra(MediaPreviewActivity.EXTRA_SOURCE_CONTENT_SHA256, sourceSha256)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            arguments.getString(ARG_PRIVATE_TYPE)?.takeIf(String::isNotBlank)?.let { type ->
                putExtra(MediaPreviewActivity.EXTRA_PRIVATE_MASTER_TYPE, type)
            }
            arguments.getString(ARG_PRIVATE_PATH)?.takeIf(String::isNotBlank)?.let { path ->
                putExtra(MediaPreviewActivity.EXTRA_PRIVATE_MASTER_PATH, path)
            }
            arguments.getString(ARG_PRIVATE_META_URI)?.takeIf(String::isNotBlank)?.let { metadata ->
                putExtra(MediaPreviewActivity.EXTRA_PRIVATE_MASTER_META_URI, metadata)
            }
        }

        var sessionId = -1L
        var liveSnapshot: PlaybackEvidenceSnapshot? = null
        val reachedPresentationThreshold = ActivityScenario.launch<MediaPreviewActivity>(intent).use {
            waitUntil(timeoutMs) {
                PlaybackEvidenceRegistry.activeSnapshots()
                    .lastOrNull { it.sourceSha256 == sourceSha256 }
                    ?.also { snapshot ->
                        sessionId = snapshot.sessionId
                        liveSnapshot = snapshot
                    }
                    ?.presentationCallbacks
                    ?.let { it >= minimumFrames } == true
            }
        }

        val completed = waitForCompleted(sessionId, RELEASE_TIMEOUT_MS)
        val evidence = completed ?: PlaybackEvidenceRegistry.activeSnapshots()
            .firstOrNull { it.sessionId == sessionId }
            ?: liveSnapshot
        assertNotNull("Playback session disappeared without final evidence", evidence)
        val snapshot = requireNotNull(evidence)
        val evidenceFile = writeEvidence(
            label = label,
            snapshot = snapshot,
            minimumFrames = minimumFrames,
            minimumFpsMilli = minimumFpsMilli,
            allowedDropped = allowedDropped,
            allowedRepeated = allowedRepeated,
            sourceBytes = sourceBytes.byteCount,
            sourceAuthenticationMethod = sourceBytes.authenticationMethod
        )

        assertTrue("Evidence was not written: ${evidenceFile.absolutePath}", evidenceFile.isFile)
        assertEquals(
            "Playback evidence did not authenticate the supplied source bytes",
            PlaybackSourceDigestKind.CONTENT_BYTES_SHA256,
            snapshot.sourceDigestKind
        )
        assertEquals("Playback evidence source bytes changed", sourceSha256, snapshot.sourceSha256)
        assertTrue(
            "Playback did not reach $minimumFrames presentation callbacks within ${timeoutMs}ms",
            reachedPresentationThreshold
        )
        assertTrue("Playback evidence did not finalize after Activity destruction", snapshot.finalized)
        assertTrue("Playback resources did not release exactly once", snapshot.resourceReleaseComplete)
        assertEquals("Decoder errors", 0L, snapshot.decoderErrorCount)
        assertEquals("Player errors", 0L, snapshot.playerErrorCount)
        assertEquals("PTS regressions outside declared discontinuities", 0L, snapshot.ptsRegressionCount)
        assertTrue(
            "Only ${snapshot.presentationCallbacks}/$minimumFrames presentation callbacks",
            snapshot.presentationCallbacks >= minimumFrames
        )
        assertTrue("PTS did not advance", snapshot.ptsProgressUs > 0L)
        assertTrue(
            "Dropped/skipped/conflated frames ${snapshot.effectiveDroppedFrames} > $allowedDropped",
            snapshot.effectiveDroppedFrames <= allowedDropped
        )
        assertTrue(
            "Repeated PTS frames ${snapshot.repeatedPtsFrames} > $allowedRepeated",
            snapshot.repeatedPtsFrames <= allowedRepeated
        )
        if (minimumFpsMilli > 0) {
            assertTrue(
                "Measured cadence ${snapshot.measuredPresentationFpsMilli} < $minimumFpsMilli milli-fps",
                snapshot.measuredPresentationFpsMilli >= minimumFpsMilli
            )
        }
        if (snapshot.path == PlaybackEvidencePath.ENCODED_MASTER ||
            snapshot.path == PlaybackEvidencePath.NIVUKX_ENCODED_MASTER
        ) {
            assertTrue("Media3 reported no rendered decoder output", snapshot.decoderRenderedFrames > 0L)
            assertTrue("Media3 reported no decoder initialization", snapshot.decoderInitCount > 0L)
            assertEquals(
                "Decoder initialization/release imbalance",
                snapshot.decoderInitCount,
                snapshot.decoderReleaseCount
            )
        } else {
            assertTrue("Private master decoded no frames", snapshot.decodedFrames > 0L)
        }
    }

    private fun waitForCompleted(sessionId: Long, timeoutMs: Long): PlaybackEvidenceSnapshot? {
        if (sessionId < 0L) return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            PlaybackEvidenceRegistry.completedSnapshots()
                .firstOrNull { it.sessionId == sessionId }
                ?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return null
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (predicate()) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private fun writeEvidence(
        label: String,
        snapshot: PlaybackEvidenceSnapshot,
        minimumFrames: Int,
        minimumFpsMilli: Int,
        allowedDropped: Long,
        allowedRepeated: Long,
        sourceBytes: Long,
        sourceAuthenticationMethod: String
    ): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(context.getExternalFilesDir("neo-qa-playback"))
        check(root.isDirectory || root.mkdirs()) { "Unable to create ${root.absolutePath}" }
        val safeLabel = label.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
        val destination = File(root, "playback-$safeLabel-${snapshot.sessionId}.json")
        val temporary = File(root, ".${destination.name}.tmp-${System.nanoTime()}")
        val json = JSONObject()
            .put("schemaVersion", snapshot.schemaVersion)
            .put("checkpointScope", "CP-188,CP-190,CP-197,CP-198")
            .put(
                "measurementBoundary",
                "MEDIA3_RELEASE_CALLBACK_NOT_SURFACEFLINGER; pair with frame-timeline evidence"
            )
            .put("label", label)
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("buildFingerprint", Build.FINGERPRINT)
                .put("sdk", Build.VERSION.SDK_INT))
            .put("thresholds", JSONObject()
                .put("minimumPresentationCallbacks", minimumFrames)
                .put("minimumFpsMilli", minimumFpsMilli)
                .put("allowedDroppedSkippedConflated", allowedDropped)
                .put("allowedRepeatedPts", allowedRepeated)
                .put("allowedPtsRegressions", 0)
                .put("decoderErrors", 0)
                .put("playerErrors", 0)
                .put("exactResourceRelease", true))
            .put("measurement", JSONObject()
                .put("evidenceLine", snapshot.toEvidenceLine())
                .put("sessionId", snapshot.sessionId)
                .put("path", snapshot.path.name)
                .put("sourceSha256", snapshot.sourceSha256)
                .put("sourceDigestKind", snapshot.sourceDigestKind.name)
                .put("sourceBytes", sourceBytes)
                .put("sourceAuthenticationMethod", sourceAuthenticationMethod)
                .put("width", snapshot.width)
                .put("height", snapshot.height)
                .put("nominalFpsMilli", snapshot.nominalFpsMilli)
                .put("prerollPresentationCallbacks", snapshot.prerollPresentationCallbacks)
                .put("measuredPresentationFpsMilli", snapshot.measuredPresentationFpsMilli)
                .put("presentationCallbacks", snapshot.presentationCallbacks)
                .put("decodedFrames", snapshot.decodedFrames)
                .put("decoderRenderedFrames", snapshot.decoderRenderedFrames)
                .put("effectiveDroppedFrames", snapshot.effectiveDroppedFrames)
                .put("decoderDroppedFrames", snapshot.decoderDroppedFrames)
                .put("decoderSkippedFrames", snapshot.decoderSkippedFrames)
                .put("droppedFrameEvents", snapshot.droppedFrameEvents)
                .put("schedulerSkippedFrames", snapshot.schedulerSkippedFrames)
                .put("outputDroppedFrames", snapshot.outputDroppedFrames)
                .put("uiConflatedFrames", snapshot.uiConflatedFrames)
                .put("repeatedPtsFrames", snapshot.repeatedPtsFrames)
                .put("ptsRegressionCount", snapshot.ptsRegressionCount)
                .put("ptsProgressUs", snapshot.ptsProgressUs)
                .put("minPresentationIntervalNs", snapshot.minPresentationIntervalNs)
                .put("maxPresentationIntervalNs", snapshot.maxPresentationIntervalNs)
                .put("bufferingCount", snapshot.bufferingCount)
                .put("bufferingDurationMs", snapshot.bufferingDurationMs)
                .put("decoderName", snapshot.decoderName)
                .put("decoderInitCount", snapshot.decoderInitCount)
                .put("decoderReleaseCount", snapshot.decoderReleaseCount)
                .put("decoderErrorCount", snapshot.decoderErrorCount)
                .put("playerErrorCount", snapshot.playerErrorCount)
                .put("resourcesAcquired", snapshot.resourcesAcquired)
                .put("resourcesReleased", snapshot.resourcesReleased)
                .put("activeResourceCount", snapshot.activeResourceCount)
                .put("activeResourceNames", snapshot.activeResourceNames)
                .put("unexpectedResourceReleaseCount", snapshot.unexpectedResourceReleaseCount)
                .put("resourceReleaseComplete", snapshot.resourceReleaseComplete)
                .put("finalized", snapshot.finalized))
        temporary.writeText(json.toString(2), Charsets.UTF_8)
        runCatching {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }.getOrElse {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        return destination
    }

    private fun authenticatePlaybackSourceBytes(
        sourceUri: Uri,
        arguments: android.os.Bundle
    ): SourceByteDigest {
        val preverifiedSha256 = arguments.getString(ARG_PREVERIFIED_SOURCE_SHA256)
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotBlank)
        val preverifiedBytesText = arguments.getString(ARG_PREVERIFIED_SOURCE_BYTES)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        if (preverifiedSha256 != null || preverifiedBytesText != null) {
            if (preverifiedSha256 == null || preverifiedBytesText == null) {
                throw AssertionError(
                    "Runner source authentication requires both $ARG_PREVERIFIED_SOURCE_SHA256 " +
                        "and $ARG_PREVERIFIED_SOURCE_BYTES"
                )
            }
            if (!SHA256_PATTERN.matches(preverifiedSha256)) {
                throw AssertionError("Invalid runner-provided SHA-256: $preverifiedSha256")
            }
            val preverifiedBytes = preverifiedBytesText.toLongOrNull()?.takeIf { it > 0L }
                ?: throw AssertionError("Invalid runner-provided source byte count: $preverifiedBytesText")
            if (sourceUri.scheme != "file") {
                throw AssertionError(
                    "Runner-preverified source authentication is restricted to file:// sources"
                )
            }
            val sourceFile = sourceUri.path?.let(::File)
                ?: throw AssertionError("Runner-preverified source has no filesystem path: $sourceUri")
            if (!sourceFile.isFile || !sourceFile.canRead()) {
                throw AssertionError(
                    "Runner-preverified playback source is not readable: ${sourceFile.absolutePath}"
                )
            }
            val observedBytes = sourceFile.length()
            if (observedBytes != preverifiedBytes) {
                throw AssertionError(
                    "Runner-preverified source size changed: expected=$preverifiedBytes " +
                        "observed=$observedBytes path=${sourceFile.absolutePath}"
                )
            }
            return SourceByteDigest(
                sha256 = preverifiedSha256,
                byteCount = observedBytes,
                authenticationMethod = AUTH_RUNNER_NATIVE_SHA256
            )
        }

        return hashPlaybackSourceBytes(sourceUri, arguments)
    }

    private fun hashPlaybackSourceBytes(
        sourceUri: Uri,
        arguments: android.os.Bundle
    ): SourceByteDigest {
        val source = if (arguments.getString(ARG_PRIVATE_TYPE) == MediaPreviewActivity.PRIVATE_MASTER_NPV10) {
            val path = arguments.getString(ARG_PRIVATE_PATH)?.takeIf(String::isNotBlank)
                ?: throw AssertionError("NPV10 playback requires -e $ARG_PRIVATE_PATH <readable-path>")
            val file = File(path)
            if (!file.isFile || !file.canRead()) {
                throw AssertionError("NPV10 playback source is not a readable file: ${file.absolutePath}")
            }
            file.inputStream()
        } else {
            InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
                .openInputStream(sourceUri)
                ?: throw AssertionError("Unable to open playback source bytes: $sourceUri")
        }
        return source.buffered(HASH_BUFFER_BYTES).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            var byteCount = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                byteCount = Math.addExact(byteCount, read.toLong())
            }
            if (byteCount <= 0L) throw AssertionError("Playback source is empty: $sourceUri")
            SourceByteDigest(
                sha256 = digest.digest().joinToString(separator = "") { byte ->
                    String.format(Locale.ROOT, "%02x", byte.toInt() and 0xFF)
                },
                byteCount = byteCount,
                authenticationMethod = AUTH_INSTRUMENTATION_STREAM_SHA256
            )
        }
    }

    private data class SourceByteDigest(
        val sha256: String,
        val byteCount: Long,
        val authenticationMethod: String
    )

    private fun android.os.Bundle.intArg(name: String, fallback: Int): Int =
        getString(name)?.toIntOrNull() ?: fallback

    private fun android.os.Bundle.longArg(name: String, fallback: Long): Long =
        getString(name)?.toLongOrNull() ?: fallback

    companion object {
        private const val ARG_URI = "neoPlaybackUri"
        private const val ARG_MIME_TYPE = "neoPlaybackMime"
        private const val ARG_PRIVATE_TYPE = "neoPlaybackPrivateType"
        private const val ARG_PRIVATE_PATH = "neoPlaybackPrivatePath"
        private const val ARG_PRIVATE_META_URI = "neoPlaybackPrivateMetaUri"
        private const val ARG_MINIMUM_FRAMES = "neoPlaybackMinimumFrames"
        private const val ARG_MINIMUM_FPS_MILLI = "neoPlaybackMinimumFpsMilli"
        private const val ARG_ALLOWED_DROPPED = "neoPlaybackAllowedDropped"
        private const val ARG_ALLOWED_REPEATED = "neoPlaybackAllowedRepeated"
        private const val ARG_TIMEOUT_MS = "neoPlaybackTimeoutMs"
        private const val ARG_LABEL = "neoPlaybackLabel"
        private const val ARG_PREVERIFIED_SOURCE_SHA256 = "neoPlaybackPreverifiedSourceSha256"
        private const val ARG_PREVERIFIED_SOURCE_BYTES = "neoPlaybackPreverifiedSourceBytes"
        private const val AUTH_RUNNER_NATIVE_SHA256 = "RUNNER_NATIVE_FULL_BYTES_SHA256"
        private const val AUTH_INSTRUMENTATION_STREAM_SHA256 = "INSTRUMENTATION_FULL_BYTES_SHA256"
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
        private const val DEFAULT_MINIMUM_FRAMES = 30
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MIN_TIMEOUT_MS = 5_000L
        // The canonical CP-190 playback cadence gate requires a continuous ten-minute run.
        // Keep the harness ceiling above that duration so the physical endurance test cannot be
        // silently shortened to the previous three-minute instrumentation limit.
        private const val MAX_TIMEOUT_MS = 15L * 60L * 1_000L
        private const val RELEASE_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 50L
        private const val HASH_BUFFER_BYTES = 1024 * 1024
    }
}
