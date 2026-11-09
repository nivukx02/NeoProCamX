package com.neoproc.neoproccamx.ui

import android.Manifest
import android.graphics.ImageFormat
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.camera.CameraState
import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import com.neoproc.neoproccamx.recording.Npv10ContainerPolicy
import com.neoproc.neoproccamx.recording.Npv10FileReader
import com.neoproc.neoproccamx.recording.Npv10PacketCodec
import com.neoproc.neoproccamx.recording.Npv10PacketCodecEngine
import com.neoproc.neoproccamx.recording.Npv10PayloadFormat
import com.neoproc.neoproccamx.recording.Npv10VerifiedMasterPublication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Canonical CP-169 production-path endurance gate.
 *
 * Unlike the standalone ingress/packing sub-gate, this drives the real CameraViewModel -> Camera2
 * P010 -> NPV10 writer path for at least 65 seconds. The resulting ~38 GiB private master is never
 * deleted by this test. PASS requires v4 durable publication, exact three-stage timestamp lineage,
 * zero loss/rejection evidence, a full stored-packet reopen scan, and the canonical packed-payload
 * throughput floor.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class Npv10ProductionSustainedWriterInstrumentedTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun exact4096x3072P010_writerPersistsAtLeast1500FramesAcross65Seconds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val masterDirectory = Npv10ContainerPolicy.masterDirectory(context.filesDir)
        val existingMasters = masterDirectory.listFiles().orEmpty().map(File::getName).toSet()
        val artifactsBefore = snapshotPrivateArtifacts(context.filesDir)
        val availableBefore = StatFs(masterDirectory.absolutePath).availableBytes
        assertTrue(
            "CP-169 requires at least $MINIMUM_FREE_BYTES bytes free; available=$availableBefore",
            availableBefore >= MINIMUM_FREE_BYTES
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var vm: CameraViewModel? = null
            scenario.onActivity { activity -> vm = activity.vm }
            val cameraVm = requireNotNull(vm) { "CP-169 CameraViewModel was not attached" }
            var recordingMayBeActive = false
            var primaryFailure: Throwable? = null
            try {
                awaitCameraStreaming(cameraVm)
                scenario.onActivity {
                    val exactSize = cameraVm.camera.videoSizes.firstOrNull { size ->
                        size.width == EXPECTED_WIDTH && size.height == EXPECTED_HEIGHT
                    } ?: throw AssertionError(
                        "Device does not expose strict ${EXPECTED_WIDTH}x${EXPECTED_HEIGHT}: " +
                            cameraVm.camera.videoSizes.joinToString()
                    )
                    configureProcessed(cameraVm)
                    cameraVm.setPreviewAspectRatio(PreviewAspectRatio.FOUR_THREE)
                    cameraVm.setVideoResolution(exactSize)
                    cameraVm.setFPS(TARGET_FPS)
                    cameraVm.setDebugValidationLabel(VALIDATION_LABEL)
                }
                awaitCondition(SELECTION_TIMEOUT_MS, "Strict CP-169 selection did not settle") {
                    cameraVm.gpuRecording.videoWidth == EXPECTED_WIDTH &&
                        cameraVm.gpuRecording.videoHeight == EXPECTED_HEIGHT &&
                        cameraVm.uiMode.value == AppMode.VIDEO &&
                        cameraVm.cinemaVideoMode.value == CinemaVideoMode.PRO &&
                        !cameraVm.rawOnFlow.value &&
                        !cameraVm.logEnabledFlow.value &&
                        cameraVm.videoHdrMode.value == "OFF" &&
                        cameraVm.videoColorSpace.value == "Rec.709" &&
                        cameraVm.uncompressedCinemaPipelineEnabled.value
                }
                awaitFreshCameraSession(scenario, cameraVm)

                scenario.onActivity { cameraVm.startRecording() }
                recordingMayBeActive = true
                awaitRecording(cameraVm)
                holdRecordingForSustainedWindow(cameraVm)

                scenario.onActivity { cameraVm.stopRecording() }
                awaitNotRecording(cameraVm)
                recordingMayBeActive = false

                val master = awaitNewVerifiedMaster(masterDirectory, existingMasters)
                val result = reopenAndVerifyEveryPacket(master)
                val marker = readMarker(Npv10VerifiedMasterPublication.markerFor(master))

                assertEquals("4", marker["version"])
                assertEquals(master.name, marker["masterName"])
                assertEquals(master.length(), marker.requireLong("masterBytes"))
                assertEquals(result.totalFrames.toLong(), marker.requireLong("frameCount"))
                assertEquals(result.totalFrames.toLong(), marker.requireLong("captureIngressFrames"))
                assertEquals(result.totalFrames.toLong(), marker.requireLong("captureAcceptedFrames"))
                assertEquals(result.totalFrames.toLong(), marker.requireLong("captureCommittedFrames"))
                assertEquals(
                    result.totalFrames.toLong() * result.decodedPayloadBytesPerFrame,
                    marker.requireLong("captureCommittedPayloadBytes")
                )
                assertEquals(
                    result.totalFrames.toLong() * result.decodedPayloadBytesPerFrame,
                    marker.requireLong("payloadBytes")
                )
                assertEquals(0L, marker.requireLong("captureInvalidTimestamps"))
                assertEquals(0L, marker.requireLong("captureNonMonotonicIngress"))
                assertEquals(0L, marker.requireLong("captureMissingCadenceFrames"))
                assertEquals(0L, marker.requireLong("droppedFrames"))
                assertEquals("true", marker["crcCheckPass"])
                assertEquals("true", marker["byteCountCheckPass"])
                assertEquals("true", marker["sessionHashCheckPass"])
                assertEquals("true", marker["timestampCheckPass"])
                assertEquals("PASS", marker["auditStatus"])
                assertEquals("PASS", marker["eliteVideoQualityAuditStatus"])
                assertEquals("PASS", marker["masterCertificateStatus"])
                assertEquals(
                    result.terminalTimestampNs,
                    marker.requireLong("terminalCadenceBoundaryTimestampNs")
                )
                assertEquals(result.terminalTimestampNs, marker.requireLong("lastTimestampNs"))
                assertTrue(
                    "Terminal missing-frame exclusion is negative",
                    marker.requireLong("terminalCadenceExcludedMissingFrames") >= 0L
                )
                assertTrue("Verified publication read-back failed", Npv10VerifiedMasterPublication.isVerified(master))

                Log.i(
                    TAG,
                    "CP169_PRODUCTION_WRITER_PASS path=${master.absolutePath} bytes=${master.length()} " +
                        "totalFrames=${result.totalFrames} contentFrames=${result.contentFrames} " +
                        "contentDurationSec=${format(result.contentDurationSeconds)} " +
                        "measuredFps=${format(result.measuredFps)} packedBytesPerFrame=" +
                        "${result.storedPayloadBytesPerFrame} sustainedBytesPerSec=" +
                        "${format(result.sustainedPackedBytesPerSecond)} requiredBytesPerSec=" +
                        "$REQUIRED_PACKED_BYTES_PER_SECOND maxGapMs=${format(result.maxGapMs)} " +
                        "marker=${Npv10VerifiedMasterPublication.markerFor(master).absolutePath} " +
                        "preserved=true"
                )
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                val cleanupFailures = mutableListOf<Throwable>()
                val stateAtCleanup = cameraVm.gpuRecordState.value
                if (recordingMayBeActive || stateAtCleanup.isActiveRecordingState()) {
                    if (stateAtCleanup !is GpuRecordingState.Finalizing) {
                        runCatching { scenario.onActivity { cameraVm.stopRecording() } }
                            .onFailure { failure ->
                                Log.e(TAG, "Emergency CP-169 stop request failed", failure)
                                cleanupFailures += failure
                            }
                    }
                    runCatching { awaitTerminalForCleanup(cameraVm) }
                        .onFailure { failure ->
                            Log.e(TAG, "Emergency CP-169 stop did not settle", failure)
                            cleanupFailures += failure
                        }
                }
                runCatching {
                    logPrivateArtifactDelta(
                        root = context.filesDir,
                        before = artifactsBefore,
                        after = snapshotPrivateArtifacts(context.filesDir)
                    )
                }.onFailure { failure ->
                    Log.e(TAG, "CP-169 private artifact inventory failed", failure)
                    cleanupFailures += failure
                }
                if (cleanupFailures.isNotEmpty()) {
                    primaryFailure?.let { original ->
                        cleanupFailures.forEach(original::addSuppressed)
                    } ?: throw AssertionError(
                        "CP-169 emergency cleanup/inventory failed; see suppressed causes"
                    ).apply {
                        cleanupFailures.forEach(::addSuppressed)
                    }
                }
            }
        }
    }

    private fun configureProcessed(vm: CameraViewModel) {
        vm.setMode(AppMode.VIDEO)
        vm.setRawEnabled(false)
        vm.setLogEnabled(false)
        vm.setCinemaVideoMode(CinemaVideoMode.PRO)
        vm.setSdrQualityIntent(SdrQualityIntent.NORMAL)
        vm.setVideoColorSpace("Rec.709")
        vm.setColorProfile("Natural")
        vm.setVideoHdrMode("OFF")
        vm.setTenBitRecordingEnabled(true)
        vm.setLutEnabled(false)
    }

    private fun awaitCameraStreaming(vm: CameraViewModel) =
        awaitCondition(CAMERA_TIMEOUT_MS, "Camera did not become streaming: ${vm.cameraState.value}") {
            vm.cameraState.value == CameraState.Streaming
        }

    private fun awaitFreshCameraSession(
        scenario: ActivityScenario<MainActivity>,
        vm: CameraViewModel
    ) {
        awaitCameraStreaming(vm)
        awaitCondition(CAMERA_TIMEOUT_MS, "Previous camera refresh did not settle") {
            !vm.camera.sessionRefreshPending.get()
        }
        val refreshed = CountDownLatch(1)
        scenario.onActivity {
            vm.camera.resetPreviewActivityTracking()
            vm.camera.refreshOutputsAndSession(
                rebuildReaders = true,
                forceRebuild = true
            ) { refreshed.countDown() }
        }
        assertTrue(
            "Strict CP-169 camera refresh did not complete",
            refreshed.await(CAMERA_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        )
        awaitCondition(CAMERA_TIMEOUT_MS, "Fresh CP-169 session produced no preview data") {
            vm.cameraState.value == CameraState.Streaming && vm.camera.hasRecentPreviewActivity()
        }
    }

    private fun awaitRecording(vm: CameraViewModel) =
        awaitCondition(START_TIMEOUT_MS, "Strict CP-169 recording did not start") {
            when (val state = vm.gpuRecordState.value) {
                is GpuRecordingState.Error -> throw AssertionError(state.message)
                is GpuRecordingState.Recording -> true
                else -> false
            }
        }

    private fun holdRecordingForSustainedWindow(vm: CameraViewModel) {
        val startedMs = SystemClock.elapsedRealtime()
        val deadlineMs = startedMs + MINIMUM_RECORDING_WALL_MS
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            when (val state = vm.gpuRecordState.value) {
                is GpuRecordingState.Error -> throw AssertionError(state.message)
                is GpuRecordingState.Recording -> Unit
                else -> throw AssertionError(
                    "CP-169 recording left Recording before 65 s: state=$state " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - startedMs}"
                )
            }
            SystemClock.sleep(STATE_POLL_MS)
        }
    }

    private fun awaitNotRecording(vm: CameraViewModel) =
        awaitCondition(STOP_TIMEOUT_MS, "Strict CP-169 stop/finalization did not settle") {
            when (val state = vm.gpuRecordState.value) {
                is GpuRecordingState.Starting,
                is GpuRecordingState.Recording,
                is GpuRecordingState.Paused,
                is GpuRecordingState.Stopping,
                is GpuRecordingState.Finalizing -> false
                is GpuRecordingState.Error -> throw AssertionError(state.message)
                else -> vm.activeRecordingTransportHudLabel() == null &&
                    !vm.isRecordingReadyForDebugClipTiming()
            }
        }

    private fun awaitTerminalForCleanup(vm: CameraViewModel) =
        awaitCondition(STOP_TIMEOUT_MS, "Emergency CP-169 cleanup did not reach a terminal state") {
            when (vm.gpuRecordState.value) {
                is GpuRecordingState.Idle,
                is GpuRecordingState.Ready -> vm.activeRecordingTransportHudLabel() == null &&
                    !vm.isRecordingReadyForDebugClipTiming()
                else -> false
            }
        }

    private fun awaitNewVerifiedMaster(directory: File, existing: Set<String>): File {
        var found: File? = null
        awaitCondition(PUBLICATION_TIMEOUT_MS, "CP-169 master did not publish durably") {
            found = directory.listFiles().orEmpty().firstOrNull { candidate ->
                candidate.name !in existing &&
                    candidate.name.contains("_${VALIDATION_LABEL}_") &&
                    candidate.extension.equals("npv10", ignoreCase = true) &&
                    Npv10VerifiedMasterPublication.isVerified(candidate)
            }
            found != null
        }
        return requireNotNull(found).also { master ->
            assertTrue(
                "CP-169 master is not bound to validation label $VALIDATION_LABEL: ${master.name}",
                master.name.contains("_${VALIDATION_LABEL}_")
            )
            Npv10FileReader(master).use { reader -> assertCanonicalHeader(master, reader) }
        }
    }

    private fun reopenAndVerifyEveryPacket(master: File): SustainedMasterResult {
        assertTrue("CP-169 private master is missing", master.isFile && master.length() > 0L)
        assertTrue("CP-169 durable marker is missing", Npv10VerifiedMasterPublication.isVerified(master))
        Npv10FileReader(master).use { reader ->
            assertCanonicalHeader(master, reader)

            val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                reader.header.width,
                reader.header.height,
                reader.header.payloadFormat
            )
            val storedBytes = Npv10PacketCodecEngine.storedBytes(
                decodedBytes,
                reader.header.packetCodec
            )
            assertEquals(EXPECTED_STORED_BYTES_PER_FRAME, storedBytes)
            val packetBuffer = ByteBuffer.allocateDirect(storedBytes.toInt())
            var totalFrames = 0
            var firstTimestampNs = 0L
            var previousTimestampNs = 0L
            var penultimateTimestampNs = 0L
            var terminalTimestampNs = 0L
            val interFrameGapsNs = ArrayList<Long>()
            while (true) {
                val frame = reader.readNextStoredFrameInto(packetBuffer) ?: break
                val timestampNs = frame.timestampNs
                assertTrue("CP-169 packet has non-positive timestamp", timestampNs > 0L)
                if (totalFrames == 0) {
                    firstTimestampNs = timestampNs
                } else {
                    assertTrue(
                        "CP-169 packet timestamps are not strictly increasing",
                        timestampNs > previousTimestampNs
                    )
                    interFrameGapsNs += timestampNs - previousTimestampNs
                }
                penultimateTimestampNs = terminalTimestampNs
                terminalTimestampNs = timestampNs
                previousTimestampNs = timestampNs
                totalFrames += 1
            }
            val terminalBoundary = requireNotNull(
                Npv10VerifiedMasterPublication.terminalCadenceBoundary(master)
            ) { "CP-169 v4 terminal cadence boundary is missing" }
            assertEquals(terminalTimestampNs, terminalBoundary.timestampNs)
            assertTrue("CP-169 has too few packets for a terminal marker", totalFrames >= 3)

            val contentFrames = totalFrames - 1
            val maxContentGapNs = interFrameGapsNs.dropLast(1).maxOrNull() ?: 0L
            assertTrue(
                "CP-169 contains only $contentFrames content frames; required=$MINIMUM_CONTENT_FRAMES",
                contentFrames >= MINIMUM_CONTENT_FRAMES
            )
            val contentDurationNs = penultimateTimestampNs - firstTimestampNs
            assertTrue(
                "CP-169 content duration is ${contentDurationNs / 1_000_000_000.0}s; required>=65s wall/60s media",
                contentDurationNs >= MINIMUM_CONTENT_DURATION_NS
            )
            val measuredFps = (contentFrames - 1).toDouble() * NANOS_PER_SECOND /
                contentDurationNs.toDouble()
            val sustainedBytesPerSecond = storedBytes.toDouble() * (contentFrames - 1).toDouble() *
                NANOS_PER_SECOND / contentDurationNs.toDouble()
            assertTrue(
                "CP-169 sustained payload ${format(sustainedBytesPerSecond)} B/s is below " +
                    "$REQUIRED_PACKED_BYTES_PER_SECOND B/s",
                sustainedBytesPerSecond >= REQUIRED_PACKED_BYTES_PER_SECOND.toDouble()
            )
            assertTrue(
                "CP-169 cadence ${format(measuredFps)} fps is below $TARGET_FPS fps",
                measuredFps >= TARGET_FPS.toDouble()
            )
            assertTrue(
                "CP-169 maximum content sensor gap ${maxContentGapNs / 1_000_000.0}ms indicates loss",
                maxContentGapNs <= MAXIMUM_FRAME_GAP_NS
            )
            assertTrue(
                "CP-169 file is shorter than its packed payload floor",
                master.length() >= storedBytes * totalFrames.toLong()
            )
            return SustainedMasterResult(
                totalFrames = totalFrames,
                contentFrames = contentFrames,
                contentDurationSeconds = contentDurationNs / NANOS_PER_SECOND,
                measuredFps = measuredFps,
                storedPayloadBytesPerFrame = storedBytes,
                decodedPayloadBytesPerFrame = decodedBytes,
                sustainedPackedBytesPerSecond = sustainedBytesPerSecond,
                maxGapMs = maxContentGapNs / 1_000_000.0,
                terminalTimestampNs = terminalTimestampNs
            )
        }
    }

    private fun assertCanonicalHeader(master: File, reader: Npv10FileReader) {
        val header = reader.header
        val metadata = header.metadataValues
        assertEquals(Npv10ContainerPolicy.VERSION, header.version)
        assertEquals(EXPECTED_WIDTH, header.width)
        assertEquals(EXPECTED_HEIGHT, header.height)
        assertEquals(TARGET_FPS, header.fps)
        assertEquals(EXPECTED_BIT_DEPTH, header.bitDepth)
        assertEquals(EXPECTED_SOURCE_FORMAT, header.sourceFormat.uppercase(Locale.US))
        assertEquals(EXPECTED_BIT_DEPTH, header.sourceBitDepth)
        assertEquals(EXPECTED_BIT_DEPTH, header.storageBitDepth)
        assertTrue("CP-169 did not preserve exact selected resolution", header.exactSelectedResolution)
        assertEquals(Npv10PayloadFormat.P010_LE, header.payloadFormat)
        assertEquals(Npv10PacketCodec.PACKED_P010_10_LE_V1, header.packetCodec)
        assertTrue(
            "CP-169 master filename lost validation label $VALIDATION_LABEL: ${master.name}",
            master.name.contains("_${VALIDATION_LABEL}_")
        )

        metadata.assertValue("container", "npv10")
        metadata.assertValue("format", "p010_le")
        metadata.assertValue("payloadFormat", "p010_le")
        metadata.assertValue("packetCodec", "packed_p010_10_le_v1")
        metadata.assertValue("packetCodecLossless", "true")
        metadata.assertValue("storedPacketsOnly", "true")
        metadata.assertValue("individualFrameFiles", "false")
        metadata.assertValue("sourceFormat", EXPECTED_SOURCE_FORMAT.lowercase(Locale.US))
        metadata.assertValue("sourceBitDepth", EXPECTED_BIT_DEPTH.toString())
        metadata.assertValue("storageBitDepth", EXPECTED_BIT_DEPTH.toString())
        metadata.assertValue("exactSelectedResolution", "true")
        metadata.assertValue("selectedResolution", EXPECTED_RESOLUTION)
        metadata.assertValue("captureResolution", EXPECTED_RESOLUTION)
        metadata.assertValue("sourceImageFormat", ImageFormat.YCBCR_P010.toString())
        metadata.assertValue("nativeTenBitCapture", "true")
        metadata.assertValue("requestedFps", TARGET_FPS.toString())
        metadata.assertValue("appliedCameraFps", TARGET_FPS.toString())
        metadata.assertValue("pipeline", "custom-packed10-npv10")
        metadata.assertValue("mediaCodecUsed", "false")
    }

    private fun readMarker(marker: File): Map<String, String> {
        assertTrue("CP-169 verified marker is unreadable", marker.isFile && marker.length() > 0L)
        return marker.useLines { lines ->
            lines.mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }.toMap()
        }
    }

    private fun Map<String, String>.requireLong(key: String): Long =
        requireNotNull(this[key]?.toLongOrNull()) { "CP-169 marker field $key is missing or invalid" }

    private fun Map<String, String>.assertValue(key: String, expected: String) {
        val normalizedKey = key.lowercase(Locale.US)
        assertEquals(
            "CP-169 header metadata $key mismatch",
            expected,
            this[normalizedKey]
        )
    }

    private fun GpuRecordingState.isActiveRecordingState(): Boolean =
        this is GpuRecordingState.Starting ||
            this is GpuRecordingState.Recording ||
            this is GpuRecordingState.Paused ||
            this is GpuRecordingState.Stopping ||
            this is GpuRecordingState.Finalizing

    private fun snapshotPrivateArtifacts(root: File): Map<String, PrivateArtifactSnapshot> {
        if (!root.exists()) return emptyMap()
        return root.walkTopDown()
            .filter(File::isFile)
            .associate { file ->
                file.relativeTo(root).invariantSeparatorsPath to PrivateArtifactSnapshot(
                    bytes = file.length(),
                    lastModifiedMs = file.lastModified()
                )
            }
    }

    private fun logPrivateArtifactDelta(
        root: File,
        before: Map<String, PrivateArtifactSnapshot>,
        after: Map<String, PrivateArtifactSnapshot>
    ) {
        val changed = after.entries
            .filter { (path, snapshot) -> before[path] != snapshot }
            .sortedBy(Map.Entry<String, PrivateArtifactSnapshot>::key)
        val removed = (before.keys - after.keys).sorted()
        Log.i(
            TAG,
            "CP169_PRIVATE_ARTIFACT_INVENTORY root=${root.absolutePath} " +
                "before=${before.size} after=${after.size} changed=${changed.size} removed=${removed.size}"
        )
        changed.forEach { (path, snapshot) ->
            val status = if (path in before) "MODIFIED" else "NEW"
            Log.i(
                TAG,
                "CP169_PRIVATE_ARTIFACT status=$status path=$path bytes=${snapshot.bytes} " +
                    "lastModifiedMs=${snapshot.lastModifiedMs}"
            )
        }
        removed.forEach { path ->
            val snapshot = before.getValue(path)
            Log.i(
                TAG,
                "CP169_PRIVATE_ARTIFACT status=REMOVED path=$path bytes=${snapshot.bytes} " +
                    "lastModifiedMs=${snapshot.lastModifiedMs}"
            )
        }
    }

    private fun awaitCondition(timeoutMs: Long, message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var satisfied = condition()
        while (!satisfied && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(STATE_POLL_MS)
            satisfied = condition()
        }
        assertTrue(message, satisfied)
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.3f", value)

    private data class SustainedMasterResult(
        val totalFrames: Int,
        val contentFrames: Int,
        val contentDurationSeconds: Double,
        val measuredFps: Double,
        val storedPayloadBytesPerFrame: Long,
        val decodedPayloadBytesPerFrame: Long,
        val sustainedPackedBytesPerSecond: Double,
        val maxGapMs: Double,
        val terminalTimestampNs: Long
    )

    private data class PrivateArtifactSnapshot(
        val bytes: Long,
        val lastModifiedMs: Long
    )

    private companion object {
        const val TAG = "CP169ProductionWriter"
        const val VALIDATION_LABEL = "qa_cp169_durable_4096x3072_65s"
        const val EXPECTED_WIDTH = 4096
        const val EXPECTED_HEIGHT = 3072
        const val EXPECTED_RESOLUTION = "4096x3072"
        const val EXPECTED_SOURCE_FORMAT = "YCBCR_P010"
        const val EXPECTED_BIT_DEPTH = 10
        const val TARGET_FPS = 25
        const val MINIMUM_CONTENT_FRAMES = 1_500
        const val MINIMUM_RECORDING_WALL_MS = 65_000L
        const val MINIMUM_CONTENT_DURATION_NS = 60_000_000_000L
        const val EXPECTED_STORED_BYTES_PER_FRAME = 23_592_960L
        const val REQUIRED_PACKED_BYTES_PER_SECOND = 589_824_000L
        const val MAXIMUM_FRAME_GAP_NS = 60_000_000L
        const val MINIMUM_FREE_BYTES = 55L * 1024L * 1024L * 1024L
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val STATE_POLL_MS = 100L
        const val CAMERA_TIMEOUT_MS = 45_000L
        const val SELECTION_TIMEOUT_MS = 45_000L
        const val START_TIMEOUT_MS = 60_000L
        const val STOP_TIMEOUT_MS = 300_000L
        const val PUBLICATION_TIMEOUT_MS = 300_000L
    }
}
