package com.neoproc.neoproccamx.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max

/**
 * Physical CP-198 endurance oracle.
 *
 * Fifty Activity/player lifetimes execute in one target process and rotate through encoded,
 * converted, NPV10 and NPRAW sources. This is intentionally separate from the short cadence oracle:
 * restarting instrumentation for every source would also restart the process and hide retained
 * Activity, decoder, reader, descriptor and native-heap growth.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackResourceReleaseInstrumentedTest {

    @Test
    fun configuredFiftyCycleMixedFormatAudit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val sources = configuredSources(arguments, instrumentation.targetContext)
        val requestedCycles = arguments.getString(ARG_CYCLES)?.toIntOrNull() ?: REQUIRED_CYCLES
        val cycles = requestedCycles.coerceIn(REQUIRED_CYCLES, MAX_CYCLES)
        val label = arguments.getString(ARG_LABEL).orEmpty().ifBlank { "playback-release-50-cycle" }
        val samples = ArrayList<MemorySample>(cycles)
        val cycleEvidence = JSONArray()
        val failures = ArrayList<String>()

        settleMemory()
        val processBaseline = sampleMemory(cycle = -1, source = "process-baseline")
        var warmBaseline: MemorySample? = null

        for (cycle in 0 until cycles) {
            val source = sources[cycle % sources.size]
            val result = runCatching { executeCycle(instrumentation, source) }
            val snapshot = result.getOrNull()
            if (snapshot == null) {
                failures += "cycle=$cycle source=${source.label} ${result.exceptionOrNull()?.message}"
            } else {
                validateSnapshot(cycle, source, snapshot, failures)
            }

            settleMemory()
            val memory = sampleMemory(cycle = cycle, source = source.label)
            samples += memory
            if (cycle + 1 == WARMUP_CYCLES) warmBaseline = memory
            cycleEvidence.put(
                JSONObject()
                    .put("cycle", cycle)
                    .put("source", source.label)
                    .put("sourceSha256", source.sha256)
                    .put("path", snapshot?.path?.name ?: "NO_SNAPSHOT")
                    .put("presentationCallbacks", snapshot?.presentationCallbacks ?: 0L)
                    .put("effectiveDroppedFrames", snapshot?.effectiveDroppedFrames ?: -1L)
                    .put("decoderErrors", snapshot?.decoderErrorCount ?: -1L)
                    .put("playerErrors", snapshot?.playerErrorCount ?: -1L)
                    .put("resourcesAcquired", snapshot?.resourcesAcquired ?: -1L)
                    .put("resourcesReleased", snapshot?.resourcesReleased ?: -1L)
                    .put("releaseComplete", snapshot?.resourceReleaseComplete ?: false)
                    .put("pssKb", memory.pssKb)
                    .put("nativeHeapBytes", memory.nativeHeapBytes)
                    .put("javaHeapBytes", memory.javaHeapBytes)
                    .put("openFdCount", memory.openFdCount)
            )
            if (result.isFailure) break
        }

        settleMemory()
        val finalMemory = sampleMemory(cycle = samples.size, source = "process-final")
        val stableBaseline = warmBaseline ?: processBaseline
        val pssGrowthKb = finalMemory.pssKb - stableBaseline.pssKb
        val fdGrowth = finalMemory.openFdCount - stableBaseline.openFdCount
        val tail = samples.takeLast(minOf(STABILITY_WINDOW_CYCLES, samples.size))
        val tailPssSpreadKb = if (tail.isEmpty()) 0L else {
            tail.maxOf(MemorySample::pssKb) - tail.minOf(MemorySample::pssKb)
        }

        if (samples.size != cycles) failures += "completed=${samples.size} required=$cycles"
        if (PlaybackEvidenceRegistry.activeSnapshots().isNotEmpty()) {
            failures += "active playback sessions remain after the final cycle"
        }
        if (pssGrowthKb > MAX_POST_WARMUP_PSS_GROWTH_KB) {
            failures += "post-warmup PSS growth ${pssGrowthKb}KB > ${MAX_POST_WARMUP_PSS_GROWTH_KB}KB"
        }
        if (tailPssSpreadKb > MAX_TAIL_PSS_SPREAD_KB) {
            failures += "tail PSS spread ${tailPssSpreadKb}KB > ${MAX_TAIL_PSS_SPREAD_KB}KB"
        }
        if (fdGrowth > MAX_POST_WARMUP_FD_GROWTH) {
            failures += "post-warmup FD growth $fdGrowth > $MAX_POST_WARMUP_FD_GROWTH"
        }

        val evidenceFile = writeEvidence(
            label = label,
            cyclesRequested = cycles,
            sources = sources,
            processBaseline = processBaseline,
            warmBaseline = stableBaseline,
            finalMemory = finalMemory,
            pssGrowthKb = pssGrowthKb,
            fdGrowth = fdGrowth,
            tailPssSpreadKb = tailPssSpreadKb,
            cycleEvidence = cycleEvidence,
            failures = failures
        )
        assertTrue("Resource evidence was not written: ${evidenceFile.absolutePath}", evidenceFile.isFile)
        assertTrue("CP-198 failures: ${failures.joinToString(" | ")}", failures.isEmpty())
    }

    private fun executeCycle(
        instrumentation: android.app.Instrumentation,
        source: CycleSource
    ): PlaybackEvidenceSnapshot {
        PlaybackEvidenceRegistry.clearForTesting()
        val intent = Intent(instrumentation.targetContext, MediaPreviewActivity::class.java).apply {
            putExtra(MediaPreviewActivity.EXTRA_URI, source.uri.toString())
            putExtra(MediaPreviewActivity.EXTRA_MIME_TYPE, source.mimeType)
            putExtra(MediaPreviewActivity.EXTRA_SOURCE_CONTENT_SHA256, source.sha256)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            source.privateType?.let {
                putExtra(MediaPreviewActivity.EXTRA_PRIVATE_MASTER_TYPE, it)
            }
            source.privatePath?.let {
                putExtra(MediaPreviewActivity.EXTRA_PRIVATE_MASTER_PATH, it)
            }
            source.privateMetaUri?.let {
                putExtra(MediaPreviewActivity.EXTRA_PRIVATE_MASTER_META_URI, it.toString())
            }
        }

        var sessionId = -1L
        var latest: PlaybackEvidenceSnapshot? = null
        val reached = ActivityScenario.launch<MediaPreviewActivity>(intent).use {
            waitUntil(CYCLE_TIMEOUT_MS) {
                PlaybackEvidenceRegistry.activeSnapshots()
                    .lastOrNull { it.sourceSha256 == source.sha256 }
                    ?.also { snapshot ->
                        sessionId = snapshot.sessionId
                        latest = snapshot
                    }
                    ?.presentationCallbacks
                    ?.let { it >= source.minimumCallbacks } == true
            }
        }
        check(reached) {
            "cycle did not reach ${source.minimumCallbacks} callbacks; latest=${latest?.toEvidenceLine()}"
        }
        return waitUntilValue(RELEASE_TIMEOUT_MS) {
            PlaybackEvidenceRegistry.completedSnapshots().firstOrNull { it.sessionId == sessionId }
        } ?: error("session=$sessionId did not finalize after Activity destruction")
    }

    private fun validateSnapshot(
        cycle: Int,
        source: CycleSource,
        snapshot: PlaybackEvidenceSnapshot,
        failures: MutableList<String>
    ) {
        fun requireCycle(condition: Boolean, message: String) {
            if (!condition) failures += "cycle=$cycle source=${source.label} $message"
        }
        requireCycle(snapshot.sourceDigestKind == PlaybackSourceDigestKind.CONTENT_BYTES_SHA256, "digest kind")
        requireCycle(snapshot.sourceSha256 == source.sha256, "source hash mismatch")
        requireCycle(snapshot.path == source.expectedPath, "path=${snapshot.path}")
        requireCycle(snapshot.presentationCallbacks >= source.minimumCallbacks, "callback count")
        requireCycle(snapshot.ptsProgressUs > 0L, "PTS did not advance")
        requireCycle(snapshot.effectiveDroppedFrames == 0L, "drops=${snapshot.effectiveDroppedFrames}")
        requireCycle(snapshot.repeatedPtsFrames == 0L, "repeats=${snapshot.repeatedPtsFrames}")
        requireCycle(snapshot.ptsRegressionCount == 0L, "PTS regressions=${snapshot.ptsRegressionCount}")
        requireCycle(snapshot.decoderErrorCount == 0L, "decoder errors=${snapshot.decoderErrorCount}")
        requireCycle(snapshot.playerErrorCount == 0L, "player errors=${snapshot.playerErrorCount}")
        requireCycle(snapshot.resourceReleaseComplete, "resource release incomplete")
        if (source.expectedPath == PlaybackEvidencePath.ENCODED_MASTER) {
            requireCycle(snapshot.decoderInitCount > 0L, "decoder was not initialized")
            requireCycle(snapshot.decoderInitCount == snapshot.decoderReleaseCount, "decoder release imbalance")
        } else {
            requireCycle(snapshot.decodedFrames > 0L, "private master decoded no frames")
        }
    }

    private fun configuredSources(
        arguments: android.os.Bundle,
        context: Context
    ): List<CycleSource> {
        fun required(name: String): String = arguments.getString(name)?.takeIf(String::isNotBlank)
            ?: throw AssertionError("Supply -e $name <value>; CP-198 requires all four formats")
        fun source(
            label: String,
            uriArg: String,
            mime: String,
            expectedPath: PlaybackEvidencePath,
            minimumCallbacks: Long,
            privateType: String? = null,
            privatePathArg: String? = null,
            privateMetaArg: String? = null
        ): CycleSource {
            val uri = Uri.parse(required(uriArg))
            val privatePath = privatePathArg?.let(::required)
            val privateMetaUri = privateMetaArg?.let { Uri.parse(required(it)) }
            val sha256 = if (privatePath != null) {
                val file = File(privatePath)
                check(file.isFile && file.canRead()) { "Unreadable $label source: $privatePath" }
                file.inputStream().buffered(HASH_BUFFER_BYTES).use(::sha256)
            } else {
                context.contentResolver.openInputStream(uri)
                    ?.buffered(HASH_BUFFER_BYTES)
                    ?.use(::sha256)
                    ?: error("Unable to read $label source: $uri")
            }
            return CycleSource(
                label = label,
                uri = uri,
                mimeType = mime,
                expectedPath = expectedPath,
                minimumCallbacks = minimumCallbacks,
                privateType = privateType,
                privatePath = privatePath,
                privateMetaUri = privateMetaUri,
                sha256 = sha256
            )
        }

        return listOf(
            source(
                label = "original-encoded",
                uriArg = ARG_ORIGINAL_URI,
                mime = "video/mp4",
                expectedPath = PlaybackEvidencePath.ENCODED_MASTER,
                minimumCallbacks = ENCODED_MINIMUM_CALLBACKS
            ),
            source(
                label = "converted-main10",
                uriArg = ARG_CONVERTED_URI,
                mime = "video/mp4",
                expectedPath = PlaybackEvidencePath.ENCODED_MASTER,
                minimumCallbacks = CONVERTED_MINIMUM_CALLBACKS
            ),
            source(
                label = "private-npv10",
                uriArg = ARG_NPV10_URI,
                mime = "application/x-npv10",
                expectedPath = PlaybackEvidencePath.PRIVATE_NPV10_MASTER,
                minimumCallbacks = PRIVATE_MINIMUM_CALLBACKS,
                privateType = MediaPreviewActivity.PRIVATE_MASTER_NPV10,
                privatePathArg = ARG_NPV10_PATH
            ),
            source(
                label = "private-npraw",
                uriArg = ARG_NPRAW_URI,
                mime = "application/x-npraw",
                expectedPath = PlaybackEvidencePath.PRIVATE_NPRAW_MASTER,
                minimumCallbacks = PRIVATE_MINIMUM_CALLBACKS,
                privateType = MediaPreviewActivity.PRIVATE_MASTER_NPRAW,
                privateMetaArg = ARG_NPRAW_META_URI
            )
        )
    }

    private fun settleMemory() {
        repeat(2) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            SystemClock.sleep(GC_SETTLE_MS)
        }
    }

    private fun sampleMemory(cycle: Int, source: String): MemorySample {
        val runtime = Runtime.getRuntime()
        return MemorySample(
            cycle = cycle,
            source = source,
            pssKb = Debug.getPss(),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            javaHeapBytes = max(0L, runtime.totalMemory() - runtime.freeMemory()),
            openFdCount = File("/proc/self/fd").list()?.size ?: -1
        )
    }

    private fun writeEvidence(
        label: String,
        cyclesRequested: Int,
        sources: List<CycleSource>,
        processBaseline: MemorySample,
        warmBaseline: MemorySample,
        finalMemory: MemorySample,
        pssGrowthKb: Long,
        fdGrowth: Int,
        tailPssSpreadKb: Long,
        cycleEvidence: JSONArray,
        failures: List<String>
    ): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = requireNotNull(context.getExternalFilesDir("neo-qa-playback"))
        check(root.isDirectory || root.mkdirs()) { "Unable to create ${root.absolutePath}" }
        val safeLabel = label.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
        val destination = File(root, "playback-resource-$safeLabel.json")
        val temporary = File(root, ".${destination.name}.tmp-${System.nanoTime()}")
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("checkpoint", "CP-198")
            .put("status", if (failures.isEmpty()) "PASS" else "FAIL")
            .put("label", label)
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("buildFingerprint", Build.FINGERPRINT)
                .put("sdk", Build.VERSION.SDK_INT))
            .put("cyclesRequired", REQUIRED_CYCLES)
            .put("cyclesRequested", cyclesRequested)
            .put("cyclesCompleted", cycleEvidence.length())
            .put("sourceHashes", JSONArray().apply {
                sources.forEach { put(JSONObject().put("label", it.label).put("sha256", it.sha256)) }
            })
            .put("thresholds", JSONObject()
                .put("maximumEffectiveDropsPerCycle", 0)
                .put("maximumDecoderErrorsPerCycle", 0)
                .put("maximumPlayerErrorsPerCycle", 0)
                .put("exactResourceReleasePerCycle", true)
                .put("maximumPostWarmupPssGrowthKb", MAX_POST_WARMUP_PSS_GROWTH_KB)
                .put("maximumTailPssSpreadKb", MAX_TAIL_PSS_SPREAD_KB)
                .put("maximumPostWarmupFdGrowth", MAX_POST_WARMUP_FD_GROWTH))
            .put("processBaseline", processBaseline.toJson())
            .put("warmBaseline", warmBaseline.toJson())
            .put("finalMemory", finalMemory.toJson())
            .put("measuredPostWarmupPssGrowthKb", pssGrowthKb)
            .put("measuredTailPssSpreadKb", tailPssSpreadKb)
            .put("measuredPostWarmupFdGrowth", fdGrowth)
            .put("cycles", cycleEvidence)
            .put("failures", JSONArray(failures))
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

    private fun MemorySample.toJson(): JSONObject = JSONObject()
        .put("cycle", cycle)
        .put("source", source)
        .put("pssKb", pssKb)
        .put("nativeHeapBytes", nativeHeapBytes)
        .put("javaHeapBytes", javaHeapBytes)
        .put("openFdCount", openFdCount)

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            if (predicate()) return true
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return false
    }

    private fun <T> waitUntilValue(timeoutMs: Long, value: () -> T?): T? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        do {
            value()?.let { return it }
            SystemClock.sleep(POLL_INTERVAL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return null
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString(separator = "") { byte ->
            String.format(Locale.ROOT, "%02x", byte.toInt() and 0xFF)
        }
    }

    private data class CycleSource(
        val label: String,
        val uri: Uri,
        val mimeType: String,
        val expectedPath: PlaybackEvidencePath,
        val minimumCallbacks: Long,
        val privateType: String? = null,
        val privatePath: String? = null,
        val privateMetaUri: Uri? = null,
        val sha256: String
    )

    private data class MemorySample(
        val cycle: Int,
        val source: String,
        val pssKb: Long,
        val nativeHeapBytes: Long,
        val javaHeapBytes: Long,
        val openFdCount: Int
    )

    companion object {
        private const val ARG_ORIGINAL_URI = "neoPlaybackOriginalUri"
        private const val ARG_CONVERTED_URI = "neoPlaybackConvertedUri"
        private const val ARG_NPV10_URI = "neoPlaybackNpv10Uri"
        private const val ARG_NPV10_PATH = "neoPlaybackNpv10Path"
        private const val ARG_NPRAW_URI = "neoPlaybackNprawUri"
        private const val ARG_NPRAW_META_URI = "neoPlaybackNprawMetaUri"
        private const val ARG_CYCLES = "neoPlaybackCycles"
        private const val ARG_LABEL = "neoPlaybackLabel"
        private const val REQUIRED_CYCLES = 50
        private const val MAX_CYCLES = 100
        private const val WARMUP_CYCLES = 8
        private const val STABILITY_WINDOW_CYCLES = 10
        private const val ENCODED_MINIMUM_CALLBACKS = 12L
        private const val CONVERTED_MINIMUM_CALLBACKS = 4L
        private const val PRIVATE_MINIMUM_CALLBACKS = 8L
        private const val CYCLE_TIMEOUT_MS = 30_000L
        private const val RELEASE_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 25L
        private const val GC_SETTLE_MS = 75L
        private const val HASH_BUFFER_BYTES = 1024 * 1024
        private const val MAX_POST_WARMUP_PSS_GROWTH_KB = 64L * 1024L
        private const val MAX_TAIL_PSS_SPREAD_KB = 64L * 1024L
        private const val MAX_POST_WARMUP_FD_GROWTH = 8
    }
}
