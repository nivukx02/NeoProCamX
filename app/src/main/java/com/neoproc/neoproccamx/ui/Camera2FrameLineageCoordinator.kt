package com.neoproc.neoproccamx.ui

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.camera.Camera2CaptureFrameEvidence
import com.neoproc.neoproccamx.camera.Camera2FrameLineageAccumulator
import com.neoproc.neoproccamx.camera.Camera2RequestTag
import com.neoproc.neoproccamx.pipeline.SensorLinearNdrSessionGenerationPolicy
import com.neoproc.neoproccamx.pipeline.SensorTimestampTimebaseReconciler
import com.neoproc.neoproccamx.rendering.VulkanFrameLineageSubmission
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the bounded, ordered Camera2 capture-result lineage used by recording certificates.
 *
 * Capture callbacks never perform certificate work on the Camera2 callback thread. Admission and
 * terminal sealing share one lock, while a single FIFO worker provides the proof that every accepted
 * result reached the accumulator before teardown freezes the certificate.
 */
internal class Camera2FrameLineageCoordinator(
    private val onCaptureDequeued: (sensorTimestampNs: Long, sessionGeneration: Long) -> Unit
) {
    private data class CaptureIngress(
        val sessionGeneration: Long,
        val frameNumber: Long,
        val sensorTimestampNs: Long,
        val recordingEvidenceEligible: Boolean,
        val sensorFrameDurationNs: Long?,
        val exposureTimeNs: Long?,
        val focusDistanceDiopters: Float?,
        val whiteBalanceMode: Int?
    )

    internal data class RecordingCaptureMetadata(
        val sensorFrameDurationNs: Long? = null,
        val exposureTimeNs: Long? = null,
        val focusDistanceDiopters: Float? = null,
        val whiteBalanceMode: Int? = null
    )

    private val lineageLock = Any()
    private var sessionGeneration = 0L
    private var initialSessionGeneration = 0L
    private var requestRevision = 0L
    private var targetRoles: Set<String> = emptySet()
    private var pipelineSignature = ""
    private var accumulator: Camera2FrameLineageAccumulator? = null
    private var recordingActive = false
    private var trackedCaptureResults = 0
    private var sessionRebaseCount = 0
    private var discardedPreRebaseCaptureResults = 0
    private var captureWindowLastTimestampNs = 0L
    private val captureAdmissionClosed = AtomicBoolean(false)
    private val timestampTimebase = SensorTimestampTimebaseReconciler()

    private val ingress = ConcurrentLinkedQueue<CaptureIngress>()
    private val ingressSize = AtomicInteger(0)
    private val drainScheduled = AtomicBoolean(false)
    private val ingressLifecycleLock = Any()
    @Volatile private var ingressOpen = true
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "Camera2-Lineage").apply { isDaemon = true }
    }
    @Volatile private var lastMetadata: Map<String, String> = emptyMap()

    fun begin(
        generation: Long,
        feedPath: RecordingFeedPath,
        recordingPipelineSignature: String
    ) {
        val roles = buildSet {
            add("capture")
            if (feedPath != RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE) add("preview")
            if (feedPath != RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE) add("encoder")
            add(feedPath.name)
        }
        synchronized(lineageLock) {
            sessionGeneration = generation
            initialSessionGeneration = generation
            sessionRebaseCount = 0
            discardedPreRebaseCaptureResults = 0
            requestRevision = 0L
            targetRoles = roles
            pipelineSignature = recordingPipelineSignature
            accumulator = Camera2FrameLineageAccumulator(expectedSessionGeneration = generation)
            recordingActive = true
            trackedCaptureResults = 0
            captureWindowLastTimestampNs = 0L
            captureAdmissionClosed.set(false)
            timestampTimebase.reset()
            lastMetadata = metadataLocked()
        }
    }

    /**
     * Rebase only the observational certificate when recording setup replaces the pre-armed Camera2
     * session. Recording, preview, and encoder ownership are deliberately left untouched.
     */
    fun rebaseIfActive(generation: Long) {
        if (generation <= 0L) return
        synchronized(lineageLock) {
            if (!recordingActive || accumulator == null) return
            if (generation == sessionGeneration) return

            val previousGeneration = sessionGeneration
            val discardedCaptureResults = trackedCaptureResults
            discardedPreRebaseCaptureResults += discardedCaptureResults
            sessionRebaseCount++
            sessionGeneration = generation
            requestRevision = 0L
            trackedCaptureResults = 0
            captureWindowLastTimestampNs = 0L
            captureAdmissionClosed.set(false)
            timestampTimebase.reset()
            accumulator = Camera2FrameLineageAccumulator(expectedSessionGeneration = generation)
            lastMetadata = metadataLocked()
            Log.i(
                LOG_TAG,
                "Camera2 recording lineage rebased from session=$previousGeneration to session=$generation " +
                    "discardedPreRebaseCaptureResults=$discardedCaptureResults"
            )
        }
    }

    fun freeze() {
        synchronized(lineageLock) {
            if (accumulator == null) return
            val metadata = metadataLocked()
            lastMetadata = metadata
            recordingActive = false
            captureAdmissionClosed.set(true)
            Log.i(
                LOG_TAG,
                "Camera2 frame lineage frozen " +
                    "certified=${metadata["camera2FrameLineageCertified"]} " +
                    "frames=${metadata["camera2FrameLineageFrameCount"]} " +
                    "durationMs=${metadata["camera2FrameLineageDurationMs"]} " +
                    "match=${metadata["camera2FrameLineageMatchRatio"]} " +
                    "preview=${metadata["camera2FrameLineage.previewSubmissionCount"]} " +
                    "encoder=${metadata["camera2FrameLineage.encoderSubmissionCount"]} " +
                    "omissions=${metadata["camera2FrameLineage.endpointOmissionCount"]} " +
                    "failures=${metadata["camera2FrameLineageFailureCodes"]}"
            )
        }
    }

    fun recordCaptureResult(result: TotalCaptureResult, captureSessionGeneration: Long) {
        val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        enqueueCaptureObservation(timestampNs, captureSessionGeneration, result.frameNumber) {
            RecordingCaptureMetadata(
                sensorFrameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
                exposureTimeNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                whiteBalanceMode = result.get(CaptureResult.CONTROL_AWB_MODE)
            )
        }
    }

    /**
     * Every live capture can reconcile a visible preview swap, including idle preview and the
     * period after a recording certificate has frozen. Only eligible recording observations
     * extract recording metadata or enter the certificate. Both routes retain the existing
     * bounded FIFO worker and teardown barrier; no camera callback performs display work.
     */
    internal fun enqueueCaptureObservation(
        timestampNs: Long,
        captureSessionGeneration: Long,
        frameNumber: Long,
        recordingMetadata: () -> RecordingCaptureMetadata = { RecordingCaptureMetadata() }
    ): Boolean {
        if (timestampNs <= 0L) return false
        val recordingEvidenceEligible = !captureAdmissionClosed.get() && synchronized(lineageLock) {
            recordingActive && captureSessionGeneration == sessionGeneration
        }
        val metadata = if (recordingEvidenceEligible) recordingMetadata() else null
        if (recordingEvidenceEligible) observeReferenceTimestamp(timestampNs)
        synchronized(ingressLifecycleLock) {
            if (!ingressOpen) return false
            while (true) {
                val queued = ingressSize.get()
                if (queued >= MAX_QUEUED_CAPTURE_RESULTS) return false
                if (ingressSize.compareAndSet(queued, queued + 1)) break
            }
            ingress.offer(
                CaptureIngress(
                    sessionGeneration = captureSessionGeneration,
                    frameNumber = frameNumber,
                    sensorTimestampNs = timestampNs,
                    recordingEvidenceEligible = recordingEvidenceEligible,
                    sensorFrameDurationNs = metadata?.sensorFrameDurationNs,
                    exposureTimeNs = metadata?.exposureTimeNs,
                    focusDistanceDiopters = metadata?.focusDistanceDiopters,
                    whiteBalanceMode = metadata?.whiteBalanceMode
                )
            )
            // Schedule while lifecycle admission is held. Teardown cannot insert its terminal FIFO
            // barrier between admission and publication of the corresponding executor task.
            scheduleDrain()
        }
        return true
    }

    fun recordSubmission(
        submission: VulkanFrameLineageSubmission,
        currentCameraSessionGeneration: Long
    ) {
        if (submission.sensorTimestampNs <= 0L) return
        if (submission.cameraSessionGeneration >= 0L &&
            !SensorLinearNdrSessionGenerationPolicy.isCurrent(
                frameSessionGeneration = submission.cameraSessionGeneration,
                currentSessionGeneration = currentCameraSessionGeneration
            )
        ) return
        val canonicalTimestampNs = normalizeTimestamp(
            submission.rawSensorTimestampNs.takeIf { it > 0L } ?: submission.sensorTimestampNs
        )
        synchronized(lineageLock) {
            val activeAccumulator = accumulator ?: return
            if (!recordingActive) return
            if (!isInsideCaptureWindowLocked(canonicalTimestampNs)) return
            val sourceSessionGeneration = submission.cameraSessionGeneration
                .takeIf { it >= 0L }
                ?: sessionGeneration
            activeAccumulator.recordImageTimestamp(
                sensorTimestampNs = canonicalTimestampNs,
                sessionGeneration = sourceSessionGeneration
            )
            if (submission.previewSubmitted) {
                activeAccumulator.recordPreviewSubmission(
                    sensorTimestampNs = canonicalTimestampNs,
                    presentationTimestampNs = submission.presentationTimeNs,
                    sessionGeneration = sourceSessionGeneration
                )
            }
            if (submission.encoderSubmitted) {
                activeAccumulator.recordEncoderSubmission(
                    sensorTimestampNs = canonicalTimestampNs,
                    presentationTimestampNs = submission.presentationTimeNs,
                    sessionGeneration = sourceSessionGeneration
                )
            }
            maybeFreezeIfReadyLocked()
        }
    }

    fun recordEncoderSubmission(sensorTimestampNs: Long) {
        if (sensorTimestampNs <= 0L) return
        val canonicalTimestampNs = normalizeTimestamp(sensorTimestampNs)
        synchronized(lineageLock) {
            val activeAccumulator = accumulator ?: return
            if (!recordingActive) return
            if (!isInsideCaptureWindowLocked(canonicalTimestampNs)) return
            activeAccumulator.recordImageTimestamp(canonicalTimestampNs)
            activeAccumulator.recordEncoderSubmission(
                sensorTimestampNs = canonicalTimestampNs,
                presentationTimestampNs = canonicalTimestampNs
            )
            maybeFreezeIfReadyLocked()
        }
    }

    fun recordPreviewPresentation(sensorTimestampNs: Long, presentationTimestampNs: Long) {
        val canonicalTimestampNs = normalizeTimestamp(sensorTimestampNs)
        synchronized(lineageLock) {
            val activeAccumulator = accumulator ?: return
            if (!recordingActive) return
            if (!isInsideCaptureWindowLocked(canonicalTimestampNs)) return
            activeAccumulator.recordImageTimestamp(canonicalTimestampNs)
            activeAccumulator.recordPreviewSubmission(
                sensorTimestampNs = canonicalTimestampNs,
                presentationTimestampNs = presentationTimestampNs
            )
            maybeFreezeIfReadyLocked()
        }
    }

    fun runtimeMetadata(): Map<String, String> = synchronized(lineageLock) { metadataLocked() }

    /**
     * Seal admission, drain every accepted FIFO item, and retire the worker gracefully. This must
     * complete before the final certificate freeze and native owner destruction.
     */
    fun drainAndShutdownForTeardown(timeoutMs: Long = DEFAULT_TEARDOWN_TIMEOUT_MS): Boolean {
        if (!sealAndDrainCaptureIngress(timeoutMs)) return false
        executor.shutdown()
        val terminated = try {
            executor.awaitTermination(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return terminated && ingress.isEmpty() && ingressSize.get() == 0 && !drainScheduled.get()
    }

    private fun drainIngressQueue() {
        while (true) {
            val accepted = ingress.poll() ?: break
            ingressSize.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            onCaptureDequeued(accepted.sensorTimestampNs, accepted.sessionGeneration)
            recordCaptureIngress(accepted)
        }
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        try {
            executor.execute {
                try {
                    drainIngressQueue()
                } finally {
                    drainScheduled.set(false)
                    if (ingress.isNotEmpty()) scheduleDrain()
                }
            }
        } catch (_: RejectedExecutionException) {
            drainScheduled.set(false)
            while (ingress.poll() != null) {
                ingressSize.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            }
        }
    }

    private fun sealAndDrainCaptureIngress(timeoutMs: Long): Boolean {
        synchronized(ingressLifecycleLock) {
            ingressOpen = false
        }
        // The barrier drains rather than merely waiting. A running worker may have observed an empty
        // queue immediately before admission closed and may enqueue a redundant no-op follow-up.
        val barrier = try {
            executor.submit { drainIngressQueue() }
        } catch (_: RejectedExecutionException) {
            return executor.isTerminated && ingress.isEmpty() && ingressSize.get() == 0
        }
        val barrierReached = try {
            barrier.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            true
        } catch (_: java.util.concurrent.TimeoutException) {
            false
        } catch (_: java.util.concurrent.ExecutionException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!barrierReached) return false
        return ingress.isEmpty() && ingressSize.get() == 0
    }

    private fun recordCaptureIngress(accepted: CaptureIngress) {
        // An idle-preview item queued before begin() cannot become recording evidence merely
        // because the worker dequeues it after the recording starts.
        if (!accepted.recordingEvidenceEligible) return
        synchronized(lineageLock) {
            val activeAccumulator = accumulator ?: return
            if (!recordingActive) return
            if (accepted.sessionGeneration != sessionGeneration) return
            if (trackedCaptureResults >= MAX_TRACKED_CAPTURE_RESULTS) {
                captureAdmissionClosed.set(true)
                lastMetadata = metadataLocked()
                return
            }
            requestRevision++
            trackedCaptureResults++
            captureWindowLastTimestampNs = accepted.sensorTimestampNs
            if (trackedCaptureResults >= MAX_TRACKED_CAPTURE_RESULTS) {
                captureAdmissionClosed.set(true)
            }
            activeAccumulator.recordCaptureResult(
                Camera2CaptureFrameEvidence(
                    sessionGeneration = sessionGeneration,
                    requestRevision = requestRevision,
                    frameNumber = accepted.frameNumber,
                    sensorTimestampNs = accepted.sensorTimestampNs,
                    sensorFrameDurationNs = accepted.sensorFrameDurationNs,
                    exposureTimeNs = accepted.exposureTimeNs,
                    focusDistanceDiopters = accepted.focusDistanceDiopters,
                    whiteBalanceMode = accepted.whiteBalanceMode?.let { mode -> "AWB_$mode" },
                    requestTag = Camera2RequestTag(
                        sessionGeneration = sessionGeneration,
                        requestRevision = requestRevision,
                        targetRoles = targetRoles,
                        pipelineSignature = pipelineSignature
                    )
                )
            )
            maybeFreezeIfReadyLocked()
        }
    }

    private fun maybeFreezeIfReadyLocked() {
        val metadata = metadataLocked()
        lastMetadata = metadata
        if (metadata["camera2FrameLineageCertified"] == "true") {
            recordingActive = false
            captureAdmissionClosed.set(true)
        }
    }

    private fun metadataLocked(): Map<String, String> {
        val activeAccumulator = accumulator
            ?: return lastMetadata.ifEmpty {
                mapOf(
                    "camera2FrameLineageCertificatePresent" to "false",
                    "camera2FrameLineageCertified" to "false"
                )
            }
        return activeAccumulator.validateStrictCertificate().toCompactMetadataMap() +
            timestampTimebase.snapshot().toMetadata("camera2FrameLineageTimestamp") + mapOf(
            "camera2FrameLineageSessionGeneration" to sessionGeneration.toString(),
            "camera2FrameLineageInitialSessionGeneration" to initialSessionGeneration.toString(),
            "camera2FrameLineageSessionRebaseCount" to sessionRebaseCount.toString(),
            "camera2FrameLineageDiscardedPreRebaseCaptureResults" to
                discardedPreRebaseCaptureResults.toString(),
            "camera2FrameLineageRecordingActive" to recordingActive.toString(),
            "camera2FrameLineageTargetRoles" to targetRoles.joinToString(separator = ","),
            "camera2FrameLineagePipelineSignature" to pipelineSignature,
            "camera2FrameLineageTrackedCaptureResults" to trackedCaptureResults.toString(),
            "camera2FrameLineageCaptureWindowClosed" to captureAdmissionClosed.get().toString(),
            "camera2FrameLineageCaptureWindowLastTimestampNs" to
                captureWindowLastTimestampNs.toString()
        )
    }

    private fun isInsideCaptureWindowLocked(sensorTimestampNs: Long): Boolean =
        !captureAdmissionClosed.get() ||
            captureWindowLastTimestampNs <= 0L ||
            sensorTimestampNs <= captureWindowLastTimestampNs

    private fun observeReferenceTimestamp(sensorTimestampNs: Long) {
        val monotonicBeforeNs = System.nanoTime()
        val boottimeNowNs = SystemClock.elapsedRealtimeNanos()
        val monotonicAfterNs = System.nanoTime()
        timestampTimebase.observeReference(
            timestampNs = sensorTimestampNs,
            monotonicNowNs = midpoint(monotonicBeforeNs, monotonicAfterNs),
            boottimeNowNs = boottimeNowNs
        )
    }

    private fun normalizeTimestamp(sensorTimestampNs: Long): Long {
        val monotonicBeforeNs = System.nanoTime()
        val boottimeNowNs = SystemClock.elapsedRealtimeNanos()
        val monotonicAfterNs = System.nanoTime()
        return timestampTimebase.normalizeToReference(
            timestampNs = sensorTimestampNs,
            monotonicNowNs = midpoint(monotonicBeforeNs, monotonicAfterNs),
            boottimeNowNs = boottimeNowNs
        )
    }

    private fun midpoint(first: Long, second: Long): Long =
        first + (second - first) / 2L

    private companion object {
        const val LOG_TAG = "CameraViewModel"
        const val MAX_TRACKED_CAPTURE_RESULTS = 240
        const val MAX_QUEUED_CAPTURE_RESULTS = 128
        const val DEFAULT_TEARDOWN_TIMEOUT_MS = 3_000L
    }
}
