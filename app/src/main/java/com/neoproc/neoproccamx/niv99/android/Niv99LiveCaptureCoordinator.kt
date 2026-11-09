package com.neoproc.neoproccamx.niv99.android

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.niv99.Niv99DetailProfile
import com.neoproc.neoproccamx.niv99.Niv99CaptureMetadataRoutingPolicy
import com.neoproc.neoproccamx.niv99.Niv99DeferredMetadataJoinPolicy
import com.neoproc.neoproccamx.niv99.Niv99ExactFrameJoinPolicy
import com.neoproc.neoproccamx.niv99.Niv99FrameIdentity
import com.neoproc.neoproccamx.niv99.Niv99FrameMetadataRing
import com.neoproc.neoproccamx.niv99.Niv99LiveFrameConfiguration
import com.neoproc.neoproccamx.niv99.Niv99LiveFrameMetadata
import com.neoproc.neoproccamx.niv99.Niv99LiveFrameTuning
import com.neoproc.neoproccamx.niv99.Niv99MetadataLookupStatus
import com.neoproc.neoproccamx.niv99.Niv99MetadataStatus
import com.neoproc.neoproccamx.niv99.Niv99OperatingMode
import com.neoproc.neoproccamx.niv99.Niv99OrderedFrameIdentityQueue
import com.neoproc.neoproccamx.niv99.Niv99PreviewTuningContinuity
import com.neoproc.neoproccamx.niv99.Niv99PreviewTuningContinuityPolicy
import com.neoproc.neoproccamx.niv99.Niv99PreviewTuningFallbackStatus
import com.neoproc.neoproccamx.niv99.Niv99PreviewTuningSource
import com.neoproc.neoproccamx.niv99.Niv99ResetReason
import com.neoproc.neoproccamx.niv99.Niv99SequentialTuningCache
import com.neoproc.neoproccamx.niv99.Niv99SignalDomain
import com.neoproc.neoproccamx.niv99.Niv99TimelineTelemetry
import com.neoproc.neoproccamx.niv99.Niv99TimelineTelemetrySnapshot
import com.neoproc.neoproccamx.niv99.Niv99ValidationResult
import com.neoproc.neoproccamx.niv99.Niv99FullFrameYuvEstimator
import com.neoproc.neoproccamx.niv99.Niv99VideoPixelApplicationPolicy
import com.neoproc.neoproccamx.niv99.Niv99YuvColorEstimate
import com.neoproc.neoproccamx.niv99.Niv99YuvEstimateStatus
import com.neoproc.neoproccamx.niv99.Niv99YuvEstimateStore
import com.neoproc.neoproccamx.niv99.Niv99YuvFrameView
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class Niv99LiveCaptureSnapshot {
    var configuredCameraId: String = ""
    var configuredGeneration: Long = -1L
    var staticMetadataStatus: Niv99MetadataStatus = Niv99MetadataStatus.CAMERA_ID_MISMATCH
    var captureResultsSeen: Long = 0L
    var captureResultsPublished: Long = 0L
    var captureResultsRejected: Long = 0L
    var imageMatches: Long = 0L
    var imageMisses: Long = 0L
    var tuningAccepted: Long = 0L
    var tuningRejected: Long = 0L
    var tuningCacheHits: Long = 0L
    var tuningResolutions: Long = 0L
    var tuningWaits: Long = 0L
    var tuningWaitTimeouts: Long = 0L
    var tuningWaitTotalNs: Long = 0L
    var tuningWaitMaxNs: Long = 0L
    var timestampAlignedImageMatches: Long = 0L
    var timestampAlignmentMaxNs: Long = 0L
    var tuningWorkQueued: Long = 0L
    var tuningWorkCompleted: Long = 0L
    var tuningWorkRejected: Long = 0L
    var tuningWorkStale: Long = 0L
    var tuningWorkFailed: Long = 0L
    var tuningWorkRetired: Long = 0L
    var tuningWorkMaximumQueueDepth: Int = 0
    var tuningWorkTotalNs: Long = 0L
    var tuningWorkMaximumNs: Long = 0L
    var previewFallbackAttempts: Long = 0L
    var previewFallbackSelections: Long = 0L
    var previewFallbackPresentations: Long = 0L
    var previewFallbackRejections: Long = 0L
    var previewFallbackMaximumAgeNs: Long = 0L
    var lateMetadataArrivals: Long = 0L
    var supersededMetadataWaits: Long = 0L
    var lateMetadataMaximumNs: Long = 0L
    var deferredMetadataWaits: Long = 0L
    var deferredMetadataMatches: Long = 0L
    var deferredMetadataMisses: Long = 0L
    var deferredMetadataWaitTotalNs: Long = 0L
    var deferredMetadataWaitMaxNs: Long = 0L
    var pixelAppliedFrames: Long = 0L
    var previewPixelAppliedFrames: Long = 0L
    var recordingPixelAppliedFrames: Long = 0L
    var pixelRenderRejectedFrames: Long = 0L
    var previewPixelRenderRejectedFrames: Long = 0L
    var recordingPixelRenderRejectedFrames: Long = 0L
    var analysisFramesSeen: Long = 0L
    var analysisEstimatesPublished: Long = 0L
    var analysisEstimatesRejected: Long = 0L
    var colorEstimateTunings: Long = 0L
    var nonIdentityWhiteBalanceFrames: Long = 0L
    var denoiseEffectFrames: Long = 0L
    var reconstructionEffectFrames: Long = 0L
    var lastWhiteBalanceEffectMagnitude: Float = 0f
    var lastDenoiseEffectMagnitude: Float = 0f
    var lastReconstructionEffectMagnitude: Float = 0f
    var lastColorEstimateFrameNumber: Long = -1L
    var lastColorEstimateTimestampNs: Long = -1L
    val timeline = Niv99TimelineTelemetrySnapshot()
    var processingStatus: String = PROCESSING_STATUS_METADATA_ONLY
    var metadataOnly: Boolean = true

    companion object {
        const val PROCESSING_STATUS_METADATA_ONLY = "METADATA_ONLY"
        const val PROCESSING_STATUS_VIDEO_ARMED = "VIDEO_PIXEL_PATH_ARMED"
        const val PROCESSING_STATUS_ACTIVE = "ACTIVE"
    }
}

/**
 * Android boundary for NIV99. Camera2 objects never escape this class: each result is copied into a
 * fixed ring and image submission can retrieve only an exact camera/generation/timestamp match.
 */
class Niv99LiveCaptureCoordinator(
    metadataCapacity: Int = Niv99FrameMetadataRing.DEFAULT_CAPACITY
) {
    private val adapter = Niv99Camera2Adapter()
    private val ring = Niv99FrameMetadataRing(metadataCapacity)
    private val configuration = Niv99LiveFrameConfiguration()
    private val matchedMetadata = Niv99LiveFrameMetadata()
    private val analysisMetadata = Niv99LiveFrameMetadata()
    private val resolvedTuningTelemetryScratch = Niv99LiveFrameTuning()
    private val sequentialTuningCache = Niv99SequentialTuningCache(metadataCapacity)
    private val fullFrameEstimator = Niv99FullFrameYuvEstimator()
    private val estimateStore = Niv99YuvEstimateStore()
    private val candidateEstimate = Niv99YuvColorEstimate()
    private val matchedEstimate = Niv99YuvColorEstimate()
    private val timelineTelemetry = Niv99TimelineTelemetry()
    /**
     * Stateful tuning is ordered exactly as Camera2 publishes results, but it must not consume the
     * Camera2 callback's 40 ms frame budget. The callback copies only a scalar identity into this
     * bounded FIFO. One prestarted display-priority worker resolves every identity in FIFO order.
     * Overflow is never conflated or replaced and is reported as a hard telemetry rejection.
     */
    private val tuningWorkQueue = Niv99OrderedFrameIdentityQueue(metadataCapacity)
    private val tuningWorkQueueLock = Any()
    private val tuningResolutionLock = Any()
    /**
     * Serializes the one Camera2 publisher with rare session transitions only. Preview and encoder
     * consumers deliberately never take this lock: a slow texture swap or recording lookup must not
     * consume the capture-result callback's 40 ms budget.
     */
    private val capturePublicationLock = Any()
    /** Isolates the fixed-size late-join anomaly ledger from capture and pixel accounting. */
    private val lateMetadataLock = Any()
    private val tuningWorkerIdentity = Niv99FrameIdentity()
    private var tuningDrainScheduled = false
    private val tuningWorkerClosed = AtomicBoolean(false)
    private val tuningWorkQueued = AtomicLong(0L)
    private val tuningWorkCompleted = AtomicLong(0L)
    private val tuningWorkRejected = AtomicLong(0L)
    private val tuningWorkStale = AtomicLong(0L)
    private val tuningWorkFailed = AtomicLong(0L)
    private val tuningWorkRetired = AtomicLong(0L)
    private val tuningWorkMaximumQueueDepth = AtomicInteger(0)
    private val tuningWorkTotalNs = AtomicLong(0L)
    private val tuningWorkMaximumNs = AtomicLong(0L)
    private val tuningDrainTask = Runnable(::drainOrderedTuningWork)
    private val tuningExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(1),
        { command ->
            Thread(
                {
                    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
                    command.run()
                },
                TUNING_WORKER_THREAD_NAME
            )
        },
        ThreadPoolExecutor.AbortPolicy()
    ).apply {
        prestartAllCoreThreads()
    }
    /**
     * Full-frame estimation is intentionally isolated from the capture-result monitor. Estimation is
     * bounded to one analysis worker, but can still take longer than a sensor interval on a large
     * frame. Holding `this` while it runs would block the Camera2 callback that must publish the exact
     * metadata/tuning packet before the matching P010 Image reaches its bounded join deadline.
     *
     * Timeline containers validate camera ID and generation internally. If reset races an in-flight
     * old-generation estimate, publication is rejected by [Niv99YuvEstimateStore] rather than leaking
     * the estimate into the replacement timeline.
     */
    private val analysisLaneLock = Any()
    @Volatile private var configuredCameraId = ""
    @Volatile private var configuredGeneration = -1L
    @Volatile private var configuredTargetFps = DEFAULT_ANALYSIS_TARGET_FPS
    @Volatile private var staticMetadataStatus = Niv99MetadataStatus.CAMERA_ID_MISMATCH
    private val captureResultsSeen = AtomicLong(0L)
    private val captureResultsPublished = AtomicLong(0L)
    private val captureResultsRejected = AtomicLong(0L)
    private val imageMatches = AtomicLong(0L)
    private val imageMisses = AtomicLong(0L)
    private val tuningAccepted = AtomicLong(0L)
    private val tuningRejected = AtomicLong(0L)
    private val tuningCacheHits = AtomicLong(0L)
    private val tuningResolutions = AtomicLong(0L)
    private val tuningWaits = AtomicLong(0L)
    private val tuningWaitTimeouts = AtomicLong(0L)
    private val tuningWaitTotalNs = AtomicLong(0L)
    private val tuningWaitMaxNs = AtomicLong(0L)
    private val timestampAlignedImageMatches = AtomicLong(0L)
    private val timestampAlignmentMaxNs = AtomicLong(0L)
    private val previewFallbackAttempts = AtomicLong(0L)
    private val previewFallbackSelections = AtomicLong(0L)
    private val previewFallbackPresentations = AtomicLong(0L)
    private val previewFallbackRejections = AtomicLong(0L)
    private val previewFallbackMaximumAgeNs = AtomicLong(0L)
    // Fixed-size anomaly ledger. Only a timed-out recording join occupies a slot; ordinary frames
    // touch no additional objects. Publication resolves the exact timestamp or proves it was skipped
    // once a newer monotonic result is accepted.
    private val lateWaitTimestampNs = LongArray(metadataCapacity.coerceAtLeast(2))
    private val lateWaitStartedNs = LongArray(lateWaitTimestampNs.size)
    private var lateWaitWriteIndex = 0
    private val lateMetadataArrivals = AtomicLong(0L)
    private val supersededMetadataWaits = AtomicLong(0L)
    private val lateMetadataMaximumNs = AtomicLong(0L)
    private val deferredMetadataWaits = AtomicLong(0L)
    private val deferredMetadataMatches = AtomicLong(0L)
    private val deferredMetadataMisses = AtomicLong(0L)
    private val deferredMetadataWaitTotalNs = AtomicLong(0L)
    private val deferredMetadataWaitMaxNs = AtomicLong(0L)
    private val pixelAppliedFrames = AtomicLong(0L)
    private val previewPixelAppliedFrames = AtomicLong(0L)
    private val recordingPixelAppliedFrames = AtomicLong(0L)
    private val pixelRenderRejectedFrames = AtomicLong(0L)
    private val previewPixelRenderRejectedFrames = AtomicLong(0L)
    private val recordingPixelRenderRejectedFrames = AtomicLong(0L)
    private val pixelEligibleTunings = AtomicLong(0L)
    private val analysisFramesSeen = AtomicLong(0L)
    private val analysisEstimatesPublished = AtomicLong(0L)
    private val analysisEstimatesRejected = AtomicLong(0L)
    private val colorEstimateTunings = AtomicLong(0L)
    private val nonIdentityWhiteBalanceFrames = AtomicLong(0L)
    private val denoiseEffectFrames = AtomicLong(0L)
    private val reconstructionEffectFrames = AtomicLong(0L)
    @Volatile private var lastWhiteBalanceEffectMagnitude = 0f
    @Volatile private var lastDenoiseEffectMagnitude = 0f
    @Volatile private var lastReconstructionEffectMagnitude = 0f
    @Volatile private var lastColorEstimateFrameNumber = -1L
    @Volatile private var lastColorEstimateTimestampNs = -1L

    fun onCaptureResult(
        result: TotalCaptureResult,
        cameraId: String,
        characteristics: CameraCharacteristics?,
        sessionGeneration: Long,
        mode: Niv99OperatingMode,
        signalDomain: Niv99SignalDomain,
        detailProfile: Niv99DetailProfile,
        width: Int,
        height: Int,
        targetFps: Int,
        colorProfileId: Long,
        surfaceGeneration: Long,
        codecGeneration: Long,
        manualKelvin: Int,
        manualTint: Int,
        awbLockRequested: Boolean
    ): Niv99MetadataStatus {
        return synchronized(capturePublicationLock) {
        captureResultsSeen.incrementAndGet()
        if (characteristics == null || cameraId.isBlank() || sessionGeneration < 0L) {
            captureResultsRejected.incrementAndGet()
            timelineTelemetry.recordCaptureRejected(cameraId, sessionGeneration)
            return Niv99MetadataStatus.CAMERA_ID_MISMATCH
        }
        // beginCameraSession() owns the current generation. A result from a retired Camera2 session
        // can arrive after replacement; it must not rewind any metadata ring or truth telemetry.
        if (configuredGeneration >= 0L && sessionGeneration != configuredGeneration) {
            captureResultsRejected.incrementAndGet()
            timelineTelemetry.recordCaptureRejected(cameraId, sessionGeneration)
            return Niv99MetadataStatus.STALE_CAPTURE_METADATA
        }
        if (cameraId != configuredCameraId || sessionGeneration != configuredGeneration) {
            val timelineReady = synchronized(tuningResolutionLock) {
                clearPendingTuningWork()
                clearLateMetadataWaits()
                staticMetadataStatus = adapter.readStatic(cameraId, characteristics)
                staticMetadataStatus == Niv99MetadataStatus.VALID &&
                    ring.beginTimeline(cameraId, sessionGeneration) &&
                    estimateStore.beginTimeline(cameraId, sessionGeneration) &&
                    sequentialTuningCache.beginTimeline(cameraId, sessionGeneration)
            }
            if (!timelineReady) {
                captureResultsRejected.incrementAndGet()
                timelineTelemetry.recordCaptureRejected(cameraId, sessionGeneration)
                return staticMetadataStatus
            }
            configuredCameraId = cameraId
            configuredGeneration = sessionGeneration
        }
        if (!timelineTelemetry.beginTimeline(cameraId, sessionGeneration)) {
            captureResultsRejected.incrementAndGet()
            return Niv99MetadataStatus.STALE_CAPTURE_METADATA
        }
        timelineTelemetry.recordCaptureSeen(cameraId, sessionGeneration)
        val metadataDepth = Niv99CaptureMetadataRoutingPolicy.resolve(signalDomain)
        val captureStatus = adapter.readCapture(result, sessionGeneration, metadataDepth)
        if (captureStatus != Niv99MetadataStatus.VALID) {
            captureResultsRejected.incrementAndGet()
            timelineTelemetry.recordCaptureRejected(cameraId, sessionGeneration)
            return captureStatus
        }
        updateConfiguration(
            mode = mode,
            signalDomain = signalDomain,
            detailProfile = detailProfile,
            width = width,
            height = height,
            targetFps = targetFps,
            colorProfileId = colorProfileId,
            surfaceGeneration = surfaceGeneration,
            codecGeneration = codecGeneration,
            manualKelvin = manualKelvin,
            manualTint = manualTint,
            awbLockRequested = awbLockRequested
        )
        configuredTargetFps = targetFps.coerceAtLeast(1)
        if (!ring.publish(
                capture = adapter.captureMetadata,
                calibration = adapter.calibrationFor(metadataDepth),
                measuredNoise = adapter.measuredNoiseFor(metadataDepth),
                configuration = configuration
            )
        ) {
            captureResultsRejected.incrementAndGet()
            timelineTelemetry.recordCaptureRejected(cameraId, sessionGeneration)
            return Niv99MetadataStatus.STALE_CAPTURE_METADATA
        }
        captureResultsPublished.incrementAndGet()
        val identity = adapter.captureMetadata.identity
        val timelinePublished = timelineTelemetry.recordCapturePublished(
            cameraId = identity.cameraId,
            sessionGeneration = identity.sessionGeneration,
            frameNumber = identity.frameNumber,
            sensorTimestampNs = identity.sensorTimestampNs
        )
        if (timelinePublished == 1L ||
            timelinePublished > 0L && timelinePublished % METADATA_LOG_INTERVAL_FRAMES == 0L
        ) {
            Log.i(
                METADATA_OBSERVE_TAG,
                "NIV99_METADATA_OBSERVE cameraId=${identity.cameraId} " +
                    "sessionGeneration=${identity.sessionGeneration} " +
                    "frameNumber=${identity.frameNumber} " +
                    "sensorTimestampNs=${identity.sensorTimestampNs} " +
                    "metadataDepth=$metadataDepth " +
                    "publishedInTimeline=$timelinePublished " +
                    "processingStatus=${Niv99LiveCaptureSnapshot.PROCESSING_STATUS_VIDEO_ARMED} " +
                    "pixelApplied=false"
                )
        }
        enqueueOrderedTuningWork(identity)
            Niv99MetadataStatus.VALID
        }
    }

    /**
     * Consumes the existing bounded full-frame analysis proxy. The exact Camera2 result is joined by
     * timestamp before any estimate is published, so a delayed or cross-session image cannot steer
     * later preview or recording pixels.
     */
    fun onAnalysisFrame(frame: Niv99YuvFrameView): Niv99YuvEstimateStatus =
        synchronized(analysisLaneLock) {
        analysisFramesSeen.incrementAndGet()
        if (configuredCameraId.isBlank() || configuredGeneration < 0L) {
            return@synchronized rejectAnalysisFrame(Niv99YuvEstimateStatus.NOT_CONFIGURED, frame)
        }
        val lookup = ring.awaitExact(
            expectedCameraId = configuredCameraId,
            expectedGeneration = configuredGeneration,
            sensorTimestampNs = frame.timestamp,
            timeoutNs = Niv99DeferredMetadataJoinPolicy.timeoutNs(configuredTargetFps.toFloat()),
            output = analysisMetadata
        )
        if (lookup != Niv99MetadataLookupStatus.MATCHED) {
            return@synchronized rejectAnalysisFrame(when (lookup) {
                Niv99MetadataLookupStatus.WRONG_TIMELINE -> Niv99YuvEstimateStatus.WRONG_TIMELINE
                Niv99MetadataLookupStatus.INVALID_IDENTITY -> Niv99YuvEstimateStatus.INVALID_IDENTITY
                Niv99MetadataLookupStatus.NOT_CONFIGURED -> Niv99YuvEstimateStatus.NOT_CONFIGURED
                else -> Niv99YuvEstimateStatus.METADATA_NOT_FOUND
            }, frame)
        }
        if (!Niv99VideoPixelApplicationPolicy.isEligible(analysisMetadata.configuration)) {
            return@synchronized rejectAnalysisFrame(Niv99YuvEstimateStatus.NOT_CONFIGURED, frame)
        }
        val estimateStatus = fullFrameEstimator.estimate(
            identity = analysisMetadata.capture.identity,
            frame = frame,
            output = candidateEstimate
        )
        if (estimateStatus != Niv99YuvEstimateStatus.VALID) {
            return@synchronized rejectAnalysisFrame(estimateStatus, frame)
        }
        val publishStatus = estimateStore.publish(candidateEstimate)
        if (publishStatus != Niv99YuvEstimateStatus.VALID) {
            return@synchronized rejectAnalysisFrame(publishStatus, frame)
        }
        val published = analysisEstimatesPublished.incrementAndGet()
        if (published == 1L || published % ANALYSIS_LOG_INTERVAL_FRAMES == 0L) {
            val source = candidateEstimate.sourceIdentity
            val message =
                "NIV99_FULL_FRAME_ESTIMATE total=$published cameraId=${source.cameraId} " +
                    "sessionGeneration=${source.sessionGeneration} frameNumber=${source.frameNumber} " +
                    "sensorTimestampNs=${source.sensorTimestampNs} " +
                    "wb=${candidateEstimate.redCorrection},${candidateEstimate.greenCorrection}," +
                    "${candidateEstimate.blueCorrection} confidence=${candidateEstimate.confidence} " +
                    "neutralTiles=${candidateEstimate.acceptedTileCount}/" +
                    "${candidateEstimate.totalTileCount} samples=${candidateEstimate.sampledPointCount} " +
                    "lumaNoise=${candidateEstimate.lumaNoiseSigma} " +
                    "chromaNoise=${candidateEstimate.chromaNoiseSigma}"
            Log.i(FULL_FRAME_ESTIMATE_TAG, message)
            NeoLogger.i(FULL_FRAME_ESTIMATE_TAG, message)
        }
        Niv99YuvEstimateStatus.VALID
    }

    private fun rejectAnalysisFrame(
        status: Niv99YuvEstimateStatus,
        frame: Niv99YuvFrameView
    ): Niv99YuvEstimateStatus {
        val rejected = analysisEstimatesRejected.incrementAndGet()
        if (rejected == 1L || rejected % ANALYSIS_REJECTION_LOG_INTERVAL_FRAMES == 0L) {
            val message =
                "NIV99_FULL_FRAME_REJECTED total=$rejected status=$status " +
                    "cameraId=$configuredCameraId sessionGeneration=$configuredGeneration " +
                    "sensorTimestampNs=${frame.timestamp} frame=${frame.width}x${frame.height} " +
                    "bitDepth=${frame.bitDepth}"
            Log.w(FULL_FRAME_ESTIMATE_TAG, message)
            NeoLogger.w(FULL_FRAME_ESTIMATE_TAG, message)
        }
        return status
    }

    fun copyTuningForImage(
        cameraId: String,
        sessionGeneration: Long,
        sensorTimestampNs: Long,
        output: Niv99LiveFrameTuning
    ): Niv99MetadataLookupStatus {
        val cachedLookup = probeTuningForImage(
            cameraId = cameraId,
            sessionGeneration = sessionGeneration,
            sensorTimestampNs = sensorTimestampNs,
            output = output
        )
        recordImageLookup(cameraId, sessionGeneration, cachedLookup, output)
        return cachedLookup
    }

    /**
     * Non-accounting exact lookup for the asynchronous preview rendezvous. The compositor may probe
     * several times while Camera2 metadata is in flight; only its terminal outcome is telemetry.
     */
    fun probeTuningForImage(
        cameraId: String,
        sessionGeneration: Long,
        sensorTimestampNs: Long,
        output: Niv99LiveFrameTuning
    ): Niv99MetadataLookupStatus = sequentialTuningCache.copyExact(
            expectedCameraId = cameraId,
            expectedGeneration = sessionGeneration,
            sensorTimestampNs = sensorTimestampNs,
            output = output
        )

    /**
     * Preview-only bounded continuity lookup. This never participates in recording and never claims
     * an exact timestamp match; the caller supplies the current route token captured at texture
     * latch time so a settings or mode transition cannot borrow the preceding packet.
     */
    fun probeRecentTuningForPreview(
        cameraId: String,
        sessionGeneration: Long,
        targetSensorTimestampNs: Long,
        maximumAgeNs: Long,
        expectedContinuity: Niv99PreviewTuningContinuity,
        output: Niv99LiveFrameTuning
    ): Niv99PreviewTuningFallbackStatus {
        previewFallbackAttempts.incrementAndGet()
        val status = sequentialTuningCache.copyNewestBefore(
            expectedCameraId = cameraId,
            expectedGeneration = sessionGeneration,
            targetSensorTimestampNs = targetSensorTimestampNs,
            maximumAgeNs = maximumAgeNs,
            expectedContinuity = expectedContinuity,
            output = output
        )
        if (status == Niv99PreviewTuningFallbackStatus.MATCHED_RECENT) {
            val ageNs = targetSensorTimestampNs - output.identity.sensorTimestampNs
            val selected = previewFallbackSelections.incrementAndGet()
            previewFallbackMaximumAgeNs.accumulateAndGet(ageNs, ::maxOf)
            if (selected == 1L || selected % PREVIEW_FALLBACK_LOG_INTERVAL_FRAMES == 0L) {
                Log.i(
                    PREVIEW_FALLBACK_TAG,
                    "NIV99_PREVIEW_TUNING_FALLBACK outcome=SELECTED total=$selected " +
                        "cameraId=$cameraId sessionGeneration=$sessionGeneration " +
                        "targetSensorTimestampNs=$targetSensorTimestampNs " +
                        "sourceSensorTimestampNs=${output.identity.sensorTimestampNs} " +
                        "ageMs=${ageNs / 1_000_000.0} exact=false"
                )
            }
        } else {
            previewFallbackRejections.incrementAndGet()
        }
        return status
    }

    /** Records one terminal preview lookup after its bounded retry window, never an interim probe. */
    fun recordPreviewImageLookup(
        cameraId: String,
        sessionGeneration: Long,
        sensorTimestampNs: Long,
        lookup: Niv99MetadataLookupStatus,
        output: Niv99LiveFrameTuning
    ): Boolean = recordProbedImageLookup(
        cameraId,
        sessionGeneration,
        sensorTimestampNs,
        lookup,
        output
    )

    /** Records the terminal result of a non-waiting recording probe exactly once. */
    fun recordRecordingImageLookup(
        cameraId: String,
        sessionGeneration: Long,
        sensorTimestampNs: Long,
        lookup: Niv99MetadataLookupStatus,
        output: Niv99LiveFrameTuning
    ): Boolean = recordProbedImageLookup(
        cameraId,
        sessionGeneration,
        sensorTimestampNs,
        lookup,
        output
    )

    private fun recordProbedImageLookup(
        cameraId: String,
        sessionGeneration: Long,
        sensorTimestampNs: Long,
        lookup: Niv99MetadataLookupStatus,
        output: Niv99LiveFrameTuning
    ): Boolean {
        if (cameraId != configuredCameraId || sessionGeneration != configuredGeneration ||
            sensorTimestampNs <= 0L
        ) {
            output.clear()
            return false
        }
        if (lookup == Niv99MetadataLookupStatus.MATCHED &&
            (!output.identity.isComplete() || output.identity.cameraId != cameraId ||
                output.identity.sessionGeneration != sessionGeneration ||
                output.identity.sensorTimestampNs != sensorTimestampNs)
        ) {
            output.clear()
            return false
        }
        recordImageLookup(cameraId, sessionGeneration, lookup, output)
        return true
    }

    /** Bounded exact-frame join for NPV10 when an Image callback leads its CaptureResult. */
    fun awaitTuningForImage(
        cameraId: String,
        sessionGeneration: Long,
        sensorTimestampNs: Long,
        timeoutNs: Long,
        output: Niv99LiveFrameTuning
    ): Niv99MetadataLookupStatus {
        val timestampAlignmentNs = Niv99ExactFrameJoinPolicy.timestampAlignmentNs(
            configuredTargetFps.toFloat()
        )
        val immediate = sequentialTuningCache.copyTimestampAligned(
            expectedCameraId = cameraId,
            expectedGeneration = sessionGeneration,
            sensorTimestampNs = sensorTimestampNs,
            maximumDeltaNs = timestampAlignmentNs,
            output = output
        )
        if (immediate != Niv99MetadataLookupStatus.NOT_FOUND || timeoutNs <= 0L) {
            recordTimestampAlignment(sensorTimestampNs, immediate, output)
            recordImageLookup(cameraId, sessionGeneration, immediate, output)
            return immediate
        }
        tuningWaits.incrementAndGet()
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val awaited = sequentialTuningCache.awaitTimestampAligned(
            expectedCameraId = cameraId,
            expectedGeneration = sessionGeneration,
            sensorTimestampNs = sensorTimestampNs,
            maximumDeltaNs = timestampAlignmentNs,
            timeoutNs = timeoutNs.coerceAtMost(Niv99ExactFrameJoinPolicy.MAXIMUM_WAIT_NS),
            output = output
        )
        val waitedNs = (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L)
        tuningWaitTotalNs.addAndGet(waitedNs)
        tuningWaitMaxNs.accumulateAndGet(waitedNs, ::maxOf)
        if (awaited == Niv99MetadataLookupStatus.NOT_FOUND) {
            tuningWaitTimeouts.incrementAndGet()
            recordTimedOutMetadataWait(sensorTimestampNs, startedNs)
        }
        recordTimestampAlignment(sensorTimestampNs, awaited, output)
        recordImageLookup(cameraId, sessionGeneration, awaited, output)
        return awaited
    }

    /**
     * Bounded exact-frame join for a caller-owned canonical P010 copy. Unlike
     * [awaitTuningForImage], this method must only be entered after the Camera2 Image has closed, so
     * a delayed result cannot occupy an ImageReader slot or stall the sensor producer.
     */
    fun awaitTuningForCopiedFrame(
        cameraId: String,
        sessionGeneration: Long,
        sensorTimestampNs: Long,
        timeoutNs: Long,
        output: Niv99LiveFrameTuning
    ): Niv99MetadataLookupStatus {
        val immediate = sequentialTuningCache.copyExact(
            expectedCameraId = cameraId,
            expectedGeneration = sessionGeneration,
            sensorTimestampNs = sensorTimestampNs,
            output = output
        )
        if (immediate != Niv99MetadataLookupStatus.NOT_FOUND || timeoutNs <= 0L) {
            recordImageLookup(cameraId, sessionGeneration, immediate, output)
            return immediate
        }

        deferredMetadataWaits.incrementAndGet()
        val startedNs = SystemClock.elapsedRealtimeNanos()
        val awaited = sequentialTuningCache.awaitExact(
            expectedCameraId = cameraId,
            expectedGeneration = sessionGeneration,
            sensorTimestampNs = sensorTimestampNs,
            timeoutNs = timeoutNs.coerceAtMost(Niv99DeferredMetadataJoinPolicy.MAXIMUM_WAIT_NS),
            output = output
        )
        val waitedNs = (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L)
        deferredMetadataWaitTotalNs.addAndGet(waitedNs)
        deferredMetadataWaitMaxNs.accumulateAndGet(waitedNs, ::maxOf)
        if (awaited == Niv99MetadataLookupStatus.MATCHED) {
            val matched = deferredMetadataMatches.incrementAndGet()
            if (waitedNs > Niv99ExactFrameJoinPolicy.MAXIMUM_WAIT_NS ||
                matched == 1L || matched % DEFERRED_METADATA_LOG_INTERVAL_FRAMES == 0L
            ) {
                val identity = output.identity
                Log.i(
                    DEFERRED_METADATA_TAG,
                    "NIV99_METADATA_DEFERRED outcome=MATCHED cameraId=${identity.cameraId} " +
                        "sessionGeneration=${identity.sessionGeneration} " +
                        "frameNumber=${identity.frameNumber} sensorTimestampNs=$sensorTimestampNs " +
                        "waitMs=${waitedNs / 1_000_000.0} imageOwned=false"
                )
            }
        } else {
            deferredMetadataMisses.incrementAndGet()
            recordTimedOutMetadataWait(sensorTimestampNs, startedNs)
        }
        recordImageLookup(cameraId, sessionGeneration, awaited, output)
        return awaited
    }

    /** Called only after the selected GPU target reports a successful frame submission. */
    fun onPixelRenderCompleted(
        tuning: Niv99LiveFrameTuning,
        recordingPath: Boolean
    ): Boolean {
        val identity = tuning.identity
        if (!tuning.pixelApplicationSucceeded ||
            !tuning.pixelApplicationApproved ||
            !identity.isComplete() ||
            identity.cameraId != configuredCameraId ||
            identity.sessionGeneration != configuredGeneration
        ) {
            recordPixelRenderRejection(tuning, recordingPath, requireApproval = false)
            return false
        }
        val timelineApplied = timelineTelemetry.recordPixelApplied(
            cameraId = identity.cameraId,
            sessionGeneration = identity.sessionGeneration,
            recordingPath = recordingPath
        )
        if (timelineApplied < 0L) {
            recordPixelRenderRejection(tuning, recordingPath, requireApproval = false)
            return false
        }
        val applied = pixelAppliedFrames.incrementAndGet()
        if (recordingPath) {
            recordingPixelAppliedFrames.incrementAndGet()
        } else {
            previewPixelAppliedFrames.incrementAndGet()
        }
        lastWhiteBalanceEffectMagnitude = tuning.whiteBalanceEffectMagnitude
        lastDenoiseEffectMagnitude = tuning.denoiseEffectMagnitude
        lastReconstructionEffectMagnitude = tuning.reconstructionEffectMagnitude
        if (tuning.colorEstimateApplied) {
            lastColorEstimateFrameNumber = tuning.colorEstimateSourceIdentity.frameNumber
            lastColorEstimateTimestampNs = tuning.colorEstimateSourceIdentity.sensorTimestampNs
        }
        if (tuning.whiteBalanceEffectMagnitude > EFFECT_EPSILON) {
            nonIdentityWhiteBalanceFrames.incrementAndGet()
        }
        if (tuning.denoiseEffectMagnitude > EFFECT_EPSILON) denoiseEffectFrames.incrementAndGet()
        if (tuning.reconstructionEffectMagnitude > EFFECT_EPSILON) {
            reconstructionEffectFrames.incrementAndGet()
        }
        if (timelineApplied == 1L || timelineApplied % PIXEL_LOG_INTERVAL_FRAMES == 0L) {
            val source = tuning.colorEstimateSourceIdentity
            val message =
                "NIV99_PIXEL_APPLIED total=$applied timelineTotal=$timelineApplied " +
                    "target=${if (recordingPath) "encoder" else "preview"} " +
                    "cameraId=${identity.cameraId} sessionGeneration=${identity.sessionGeneration} " +
                    "frameNumber=${identity.frameNumber} sensorTimestampNs=${identity.sensorTimestampNs} " +
                    "authority=${tuning.authority} confidence=${tuning.confidence} " +
                    "colorEstimateApplied=${tuning.colorEstimateApplied} " +
                    "colorSourceFrame=${if (source.isComplete()) source.frameNumber else -1L} " +
                    "colorSourceTimestampNs=${if (source.isComplete()) source.sensorTimestampNs else -1L} " +
                    "wbEffect=${tuning.whiteBalanceEffectMagnitude} " +
                    "denoiseEffect=${tuning.denoiseEffectMagnitude} " +
                    "reconstructionEffect=${tuning.reconstructionEffectMagnitude}"
            Log.i(PIXEL_ACTIVE_TAG, message)
            NeoLogger.i(PIXEL_ACTIVE_TAG, message)
        }
        return true
    }

    /** Validates and records the actual preview swap without ever relabelling reuse as exact. */
    fun onPreviewPixelRenderCompleted(
        tuning: Niv99LiveFrameTuning,
        presentedSensorTimestampNs: Long,
        source: Niv99PreviewTuningSource,
        expectedContinuity: Niv99PreviewTuningContinuity
    ): Boolean {
        val sourceValid = when (source) {
            Niv99PreviewTuningSource.EXACT_FRAME ->
                tuning.identity.sensorTimestampNs == presentedSensorTimestampNs
            Niv99PreviewTuningSource.RECENT_CONTINUITY ->
                Niv99PreviewTuningContinuityPolicy.canReuse(
                    expected = expectedContinuity,
                    candidate = tuning,
                    targetSensorTimestampNs = presentedSensorTimestampNs,
                    maximumAgeNs = Niv99PreviewTuningContinuityPolicy.maximumReuseAgeNs(
                        expectedContinuity.targetFps.toFloat()
                    )
                )
        }
        if (!sourceValid || !tuning.markPixelApplicationSucceeded()) {
            if (source == Niv99PreviewTuningSource.RECENT_CONTINUITY) {
                previewFallbackRejections.incrementAndGet()
            }
            recordPixelRenderRejection(tuning, recordingPath = false, requireApproval = false)
            return false
        }
        if (!onPixelRenderCompleted(tuning, recordingPath = false)) return false
        if (source == Niv99PreviewTuningSource.RECENT_CONTINUITY) {
            val presented = previewFallbackPresentations.incrementAndGet()
            if (presented == 1L || presented % PREVIEW_FALLBACK_LOG_INTERVAL_FRAMES == 0L) {
                val sourceTimestampNs = tuning.identity.sensorTimestampNs
                Log.i(
                    PREVIEW_FALLBACK_TAG,
                    "NIV99_PREVIEW_TUNING_FALLBACK outcome=PRESENTED total=$presented " +
                        "cameraId=${tuning.identity.cameraId} " +
                        "sessionGeneration=${tuning.identity.sessionGeneration} " +
                        "targetSensorTimestampNs=$presentedSensorTimestampNs " +
                        "sourceSensorTimestampNs=$sourceTimestampNs " +
                        "ageMs=${(presentedSensorTimestampNs - sourceTimestampNs) / 1_000_000.0} " +
                        "exact=false"
                )
            }
        }
        return true
    }

    fun onPixelRenderRejected(tuning: Niv99LiveFrameTuning, recordingPath: Boolean) {
        recordPixelRenderRejection(tuning, recordingPath, requireApproval = true)
    }

    fun onSessionGenerationAdvanced(generation: Long) {
        synchronized(capturePublicationLock) {
            if (generation < 0L || generation <= configuredGeneration) return@synchronized
            synchronized(tuningResolutionLock) {
                clearPendingTuningWork()
                clearLateMetadataWaits()
                timelineTelemetry.beginTimeline(configuredCameraId, generation)
                configuredGeneration = generation
                adapter.resetTimeline()
                if (configuredCameraId.isBlank() || !ring.beginTimeline(configuredCameraId, generation)) {
                    ring.clear()
                }
                if (configuredCameraId.isBlank() ||
                    !estimateStore.beginTimeline(configuredCameraId, generation)
                ) {
                    estimateStore.clear()
                }
                if (configuredCameraId.isBlank() ||
                    !sequentialTuningCache.beginTimeline(configuredCameraId, generation)
                ) {
                    sequentialTuningCache.clear(Niv99ResetReason.SESSION_CHANGED)
                }
            }
        }
    }

    fun close() {
        synchronized(capturePublicationLock) {
            synchronized(tuningResolutionLock) {
                clearPendingTuningWork()
                clearLateMetadataWaits()
                configuredCameraId = ""
                configuredGeneration = -1L
                configuredTargetFps = DEFAULT_ANALYSIS_TARGET_FPS
                ring.clear()
                estimateStore.clear()
                sequentialTuningCache.clear(Niv99ResetReason.EXPLICIT_RESET)
                timelineTelemetry.retireTimeline()
            }
        }
    }

    /** Permanently releases the ordered worker when the owning frame pipeline is destroyed. */
    fun destroy() {
        if (!tuningWorkerClosed.compareAndSet(false, true)) return
        close()
        tuningExecutor.shutdownNow()
    }

    fun snapshot(output: Niv99LiveCaptureSnapshot) {
        output.configuredCameraId = configuredCameraId
        output.configuredGeneration = configuredGeneration
        output.staticMetadataStatus = staticMetadataStatus
            output.captureResultsSeen = captureResultsSeen.get()
            output.captureResultsPublished = captureResultsPublished.get()
            output.captureResultsRejected = captureResultsRejected.get()
            output.imageMatches = imageMatches.get()
            output.imageMisses = imageMisses.get()
            output.tuningAccepted = tuningAccepted.get()
            output.tuningRejected = tuningRejected.get()
            output.tuningCacheHits = tuningCacheHits.get()
            output.tuningResolutions = tuningResolutions.get()
            output.tuningWaits = tuningWaits.get()
            output.tuningWaitTimeouts = tuningWaitTimeouts.get()
            output.tuningWaitTotalNs = tuningWaitTotalNs.get()
            output.tuningWaitMaxNs = tuningWaitMaxNs.get()
            output.timestampAlignedImageMatches = timestampAlignedImageMatches.get()
            output.timestampAlignmentMaxNs = timestampAlignmentMaxNs.get()
            output.tuningWorkQueued = tuningWorkQueued.get()
            output.tuningWorkCompleted = tuningWorkCompleted.get()
            output.tuningWorkRejected = tuningWorkRejected.get()
            output.tuningWorkStale = tuningWorkStale.get()
            output.tuningWorkFailed = tuningWorkFailed.get()
            output.tuningWorkRetired = tuningWorkRetired.get()
            output.tuningWorkMaximumQueueDepth = tuningWorkMaximumQueueDepth.get()
            output.tuningWorkTotalNs = tuningWorkTotalNs.get()
            output.tuningWorkMaximumNs = tuningWorkMaximumNs.get()
            output.previewFallbackAttempts = previewFallbackAttempts.get()
            output.previewFallbackSelections = previewFallbackSelections.get()
            output.previewFallbackPresentations = previewFallbackPresentations.get()
            output.previewFallbackRejections = previewFallbackRejections.get()
            output.previewFallbackMaximumAgeNs = previewFallbackMaximumAgeNs.get()
            output.lateMetadataArrivals = lateMetadataArrivals.get()
            output.supersededMetadataWaits = supersededMetadataWaits.get()
            output.lateMetadataMaximumNs = lateMetadataMaximumNs.get()
            output.deferredMetadataWaits = deferredMetadataWaits.get()
            output.deferredMetadataMatches = deferredMetadataMatches.get()
            output.deferredMetadataMisses = deferredMetadataMisses.get()
            output.deferredMetadataWaitTotalNs = deferredMetadataWaitTotalNs.get()
            output.deferredMetadataWaitMaxNs = deferredMetadataWaitMaxNs.get()
            output.pixelAppliedFrames = pixelAppliedFrames.get()
            output.previewPixelAppliedFrames = previewPixelAppliedFrames.get()
            output.recordingPixelAppliedFrames = recordingPixelAppliedFrames.get()
            output.pixelRenderRejectedFrames = pixelRenderRejectedFrames.get()
            output.previewPixelRenderRejectedFrames = previewPixelRenderRejectedFrames.get()
            output.recordingPixelRenderRejectedFrames = recordingPixelRenderRejectedFrames.get()
            output.analysisFramesSeen = analysisFramesSeen.get()
            output.analysisEstimatesPublished = analysisEstimatesPublished.get()
            output.analysisEstimatesRejected = analysisEstimatesRejected.get()
            output.colorEstimateTunings = colorEstimateTunings.get()
            output.nonIdentityWhiteBalanceFrames = nonIdentityWhiteBalanceFrames.get()
            output.denoiseEffectFrames = denoiseEffectFrames.get()
            output.reconstructionEffectFrames = reconstructionEffectFrames.get()
            output.lastWhiteBalanceEffectMagnitude = lastWhiteBalanceEffectMagnitude
            output.lastDenoiseEffectMagnitude = lastDenoiseEffectMagnitude
            output.lastReconstructionEffectMagnitude = lastReconstructionEffectMagnitude
            output.lastColorEstimateFrameNumber = lastColorEstimateFrameNumber
            output.lastColorEstimateTimestampNs = lastColorEstimateTimestampNs
            timelineTelemetry.snapshot(output.timeline)
            output.processingStatus = when {
                output.timeline.pixelAppliedFrames > 0L ->
                    Niv99LiveCaptureSnapshot.PROCESSING_STATUS_ACTIVE
                output.timeline.pixelEligibleTunings > 0L ->
                    Niv99LiveCaptureSnapshot.PROCESSING_STATUS_VIDEO_ARMED
                else -> Niv99LiveCaptureSnapshot.PROCESSING_STATUS_METADATA_ONLY
            }
        output.metadataOnly = output.timeline.pixelEligibleTunings == 0L &&
            output.timeline.pixelAppliedFrames == 0L
    }

    private fun updateConfiguration(
        mode: Niv99OperatingMode,
        signalDomain: Niv99SignalDomain,
        detailProfile: Niv99DetailProfile,
        width: Int,
        height: Int,
        targetFps: Int,
        colorProfileId: Long,
        surfaceGeneration: Long,
        codecGeneration: Long,
        manualKelvin: Int,
        manualTint: Int,
        awbLockRequested: Boolean
    ) {
        configuration.mode = mode
        configuration.domain = signalDomain
        configuration.detailProfile = detailProfile
        configuration.width = width
        configuration.height = height
        configuration.targetFps = targetFps
        configuration.colorProfileId = colorProfileId
        configuration.surfaceGeneration = surfaceGeneration
        configuration.codecGeneration = codecGeneration
        val controls = configuration.userControls
        val capture = adapter.captureMetadata
        controls.manualKelvin = manualKelvin.takeIf { it > 0 } ?: 0
        controls.manualTint = manualTint.coerceIn(-150, 150)
        controls.awbLockRequested = awbLockRequested
        if (controls.manualKelvin > 0 && capture.halGainsAvailable) {
            controls.manualRedGain = capture.halRedGain
            controls.manualGreenGain = (capture.halGreenEvenGain + capture.halGreenOddGain) * 0.5f
            controls.manualBlueGain = capture.halBlueGain
        } else if (controls.manualKelvin > 0) {
            controls.manualRedGain = 0f
            controls.manualGreenGain = 0f
            controls.manualBlueGain = 0f
        } else {
            controls.manualRedGain = 1f
            controls.manualGreenGain = 1f
            controls.manualBlueGain = 1f
        }
        controls.manualMatrixAvailable = controls.manualKelvin > 0 && capture.halMatrixAvailable
        if (controls.manualMatrixAvailable) {
            capture.halColorMatrix.copyInto(controls.manualColorMatrix)
        }
    }

    /** Runs only on the single ordered worker, after the scalar metadata packet is published. */
    private fun resolveAndCachePublishedTuning(identity: Niv99FrameIdentity): Boolean {
        val metadataLookup = ring.copyExact(
            expectedCameraId = identity.cameraId,
            expectedGeneration = identity.sessionGeneration,
            sensorTimestampNs = identity.sensorTimestampNs,
            output = matchedMetadata
        )
        if (metadataLookup != Niv99MetadataLookupStatus.MATCHED) {
            tuningRejected.incrementAndGet()
            timelineTelemetry.recordTuningRejected(identity.cameraId, identity.sessionGeneration)
            return false
        }
        val estimateStatus = estimateStore.copyLatestPreceding(
            target = matchedMetadata.capture.identity,
            output = matchedEstimate
        )
        val resolution = sequentialTuningCache.resolveAndPublish(
            metadata = matchedMetadata,
            colorEstimate = matchedEstimate.takeIf {
                estimateStatus == Niv99YuvEstimateStatus.VALID
            }
        )
        if (resolution != Niv99ValidationResult.VALID) {
            tuningRejected.incrementAndGet()
            timelineTelemetry.recordTuningRejected(identity.cameraId, identity.sessionGeneration)
            return false
        }
        val telemetryLookup = sequentialTuningCache.copyExact(
            expectedCameraId = identity.cameraId,
            expectedGeneration = identity.sessionGeneration,
            sensorTimestampNs = identity.sensorTimestampNs,
            output = resolvedTuningTelemetryScratch
        )
        if (telemetryLookup != Niv99MetadataLookupStatus.MATCHED) {
            tuningRejected.incrementAndGet()
            timelineTelemetry.recordTuningRejected(identity.cameraId, identity.sessionGeneration)
            return false
        }
        tuningResolutions.incrementAndGet()
        tuningAccepted.incrementAndGet()
        if (resolvedTuningTelemetryScratch.colorEstimateApplied) colorEstimateTunings.incrementAndGet()
        if (resolvedTuningTelemetryScratch.pixelApplicationApproved) {
            pixelEligibleTunings.incrementAndGet()
            timelineTelemetry.recordPixelEligibleTuning(identity.cameraId, identity.sessionGeneration)
        }
        timelineTelemetry.recordTuningAccepted(identity.cameraId, identity.sessionGeneration)
        return true
    }

    /**
     * Copies one identity in callback order and schedules at most one reusable drain task. No
     * Camera2 object, metadata payload, or per-frame Runnable crosses this boundary.
     */
    private fun enqueueOrderedTuningWork(identity: Niv99FrameIdentity) {
        var scheduleDrain = false
        var accepted = false
        var depth = 0
        synchronized(tuningWorkQueueLock) {
            if (!tuningWorkerClosed.get()) {
                accepted = tuningWorkQueue.offer(identity)
                if (accepted) {
                    depth = tuningWorkQueue.size()
                    if (!tuningDrainScheduled) {
                        tuningDrainScheduled = true
                        scheduleDrain = true
                    }
                }
            }
        }
        if (!accepted) {
            tuningWorkRejected.incrementAndGet()
            tuningRejected.incrementAndGet()
            timelineTelemetry.recordTuningRejected(identity.cameraId, identity.sessionGeneration)
            Log.e(
                TUNING_WORKER_TAG,
                "NIV99_TUNING_QUEUE_REJECTED cameraId=${identity.cameraId} " +
                    "sessionGeneration=${identity.sessionGeneration} " +
                    "frameNumber=${identity.frameNumber} sensorTimestampNs=${identity.sensorTimestampNs}"
            )
            return
        }
        tuningWorkQueued.incrementAndGet()
        tuningWorkMaximumQueueDepth.accumulateAndGet(depth) { current, observed ->
            maxOf(current, observed)
        }
        if (!scheduleDrain) return
        try {
            tuningExecutor.execute(tuningDrainTask)
        } catch (rejected: RuntimeException) {
            val discarded = synchronized(tuningWorkQueueLock) {
                val queued = tuningWorkQueue.size()
                tuningWorkQueue.clear()
                tuningDrainScheduled = false
                queued
            }
            tuningWorkRejected.addAndGet(discarded.toLong())
            tuningRejected.addAndGet(discarded.toLong())
            Log.e(
                TUNING_WORKER_TAG,
                "NIV99 ordered tuning worker rejected $discarded queued frame(s)",
                rejected
            )
        }
    }

    private fun drainOrderedTuningWork() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val hasWork = synchronized(tuningWorkQueueLock) {
                    if (tuningWorkQueue.poll(tuningWorkerIdentity)) {
                        true
                    } else {
                        tuningDrainScheduled = false
                        false
                    }
                }
                if (!hasWork) return
                val startedNs = SystemClock.elapsedRealtimeNanos()
                var stale = false
                val resolved = try {
                    synchronized(tuningResolutionLock) {
                        if (tuningWorkerIdentity.cameraId != configuredCameraId ||
                            tuningWorkerIdentity.sessionGeneration != configuredGeneration
                        ) {
                            stale = true
                            false
                        } else {
                            resolveAndCachePublishedTuning(tuningWorkerIdentity)
                        }
                    }
                } catch (throwable: Throwable) {
                    if (throwable is VirtualMachineError || throwable is ThreadDeath) throw throwable
                    tuningRejected.incrementAndGet()
                    timelineTelemetry.recordTuningRejected(
                        tuningWorkerIdentity.cameraId,
                        tuningWorkerIdentity.sessionGeneration
                    )
                    Log.e(
                        TUNING_WORKER_TAG,
                        "NIV99 ordered tuning failed independently " +
                            "cameraId=${tuningWorkerIdentity.cameraId} " +
                            "sessionGeneration=${tuningWorkerIdentity.sessionGeneration} " +
                            "frameNumber=${tuningWorkerIdentity.frameNumber} " +
                            "sensorTimestampNs=${tuningWorkerIdentity.sensorTimestampNs}",
                        throwable
                    )
                    false
                }
                val elapsedNs = (SystemClock.elapsedRealtimeNanos() - startedNs).coerceAtLeast(0L)
                tuningWorkTotalNs.addAndGet(elapsedNs)
                tuningWorkMaximumNs.accumulateAndGet(elapsedNs, ::maxOf)
                if (resolved) {
                    tuningWorkCompleted.incrementAndGet()
                    if (tuningWorkerIdentity.cameraId == configuredCameraId &&
                        tuningWorkerIdentity.sessionGeneration == configuredGeneration
                    ) {
                        correlateLateMetadataPublication(tuningWorkerIdentity)
                    }
                } else if (stale) {
                    tuningWorkStale.incrementAndGet()
                } else {
                    tuningWorkFailed.incrementAndGet()
                }
            }
        } finally {
            synchronized(tuningWorkQueueLock) {
                tuningDrainScheduled = false
            }
        }
    }

    private fun clearPendingTuningWork() {
        synchronized(tuningWorkQueueLock) {
            val retired = tuningWorkQueue.clear()
            if (retired > 0) tuningWorkRetired.addAndGet(retired.toLong())
        }
    }

    private fun recordPixelRenderRejection(
        tuning: Niv99LiveFrameTuning,
        recordingPath: Boolean,
        requireApproval: Boolean
    ) {
        if (requireApproval && !tuning.pixelApplicationApproved) return
        pixelRenderRejectedFrames.incrementAndGet()
        if (recordingPath) {
            recordingPixelRenderRejectedFrames.incrementAndGet()
        } else {
            previewPixelRenderRejectedFrames.incrementAndGet()
        }
        val identity = tuning.identity
        timelineTelemetry.recordPixelRejected(
            cameraId = identity.cameraId,
            sessionGeneration = identity.sessionGeneration,
            recordingPath = recordingPath
        )
    }

    private fun recordImageLookup(
        cameraId: String,
        sessionGeneration: Long,
        lookup: Niv99MetadataLookupStatus,
        output: Niv99LiveFrameTuning
    ) {
        if (cameraId != configuredCameraId || sessionGeneration != configuredGeneration) {
            output.clear()
            return
        }
        if (lookup == Niv99MetadataLookupStatus.MATCHED) {
            imageMatches.incrementAndGet()
            tuningAccepted.incrementAndGet()
            tuningCacheHits.incrementAndGet()
            timelineTelemetry.recordImageMatch(cameraId, sessionGeneration)
            timelineTelemetry.recordTuningAccepted(cameraId, sessionGeneration)
        } else {
            output.clear()
            imageMisses.incrementAndGet()
            timelineTelemetry.recordImageMiss(cameraId, sessionGeneration)
        }
    }

    private fun recordTimedOutMetadataWait(sensorTimestampNs: Long, startedNs: Long) {
        synchronized(lateMetadataLock) {
            if (sensorTimestampNs <= 0L || configuredGeneration < 0L) return@synchronized
            for (index in lateWaitTimestampNs.indices) {
                if (lateWaitTimestampNs[index] == sensorTimestampNs) return@synchronized
            }
            val slot = lateWaitWriteIndex
            lateWaitWriteIndex = (lateWaitWriteIndex + 1) % lateWaitTimestampNs.size
            lateWaitTimestampNs[slot] = sensorTimestampNs
            lateWaitStartedNs[slot] = startedNs
        }
    }

    private fun recordTimestampAlignment(
        imageTimestampNs: Long,
        lookup: Niv99MetadataLookupStatus,
        output: Niv99LiveFrameTuning
    ) {
        if (lookup != Niv99MetadataLookupStatus.MATCHED) return
        val metadataTimestampNs = output.identity.sensorTimestampNs
        if (metadataTimestampNs == imageTimestampNs) return
        val deltaNs = if (metadataTimestampNs >= imageTimestampNs) {
            metadataTimestampNs - imageTimestampNs
        } else {
            imageTimestampNs - metadataTimestampNs
        }
        val count = timestampAlignedImageMatches.incrementAndGet()
        timestampAlignmentMaxNs.accumulateAndGet(deltaNs, ::maxOf)
        if (count == 1L || count % TIMESTAMP_ALIGNMENT_LOG_INTERVAL_FRAMES == 0L) {
            Log.i(
                TIMESTAMP_ALIGNMENT_TAG,
                "NIV99_TIMESTAMP_ALIGNMENT outcome=MATCHED total=$count " +
                    "imageTimestampNs=$imageTimestampNs metadataTimestampNs=$metadataTimestampNs " +
                    "deltaNs=$deltaNs toleranceNs=" +
                    Niv99ExactFrameJoinPolicy.timestampAlignmentNs(configuredTargetFps.toFloat())
            )
        }
    }

    /** Called only after the exact tuning packet has been published for [identity]. */
    private fun correlateLateMetadataPublication(identity: Niv99FrameIdentity) {
        synchronized(lateMetadataLock) {
            val publishedTimestampNs = identity.sensorTimestampNs
            if (publishedTimestampNs <= 0L) return@synchronized
            val nowNs = SystemClock.elapsedRealtimeNanos()
            for (index in lateWaitTimestampNs.indices) {
                val waitedTimestampNs = lateWaitTimestampNs[index]
                if (waitedTimestampNs <= 0L || waitedTimestampNs > publishedTimestampNs) continue
                val elapsedNs = (nowNs - lateWaitStartedNs[index]).coerceAtLeast(0L)
                lateWaitTimestampNs[index] = 0L
                lateWaitStartedNs[index] = 0L
                if (waitedTimestampNs == publishedTimestampNs) {
                    lateMetadataArrivals.incrementAndGet()
                    lateMetadataMaximumNs.accumulateAndGet(elapsedNs, ::maxOf)
                    Log.w(
                        LATE_METADATA_TAG,
                        "NIV99_METADATA_LATE outcome=ARRIVED cameraId=${identity.cameraId} " +
                            "sessionGeneration=${identity.sessionGeneration} " +
                            "frameNumber=${identity.frameNumber} sensorTimestampNs=$publishedTimestampNs " +
                            "imageToPublicationMs=${elapsedNs / 1_000_000.0}"
                    )
                } else {
                    supersededMetadataWaits.incrementAndGet()
                    Log.e(
                        LATE_METADATA_TAG,
                        "NIV99_METADATA_LATE outcome=SUPERSEDED cameraId=${identity.cameraId} " +
                            "sessionGeneration=${identity.sessionGeneration} " +
                            "waitedTimestampNs=$waitedTimestampNs " +
                            "nextPublishedFrame=${identity.frameNumber} " +
                            "nextPublishedTimestampNs=$publishedTimestampNs " +
                            "waitAgeMs=${elapsedNs / 1_000_000.0}"
                    )
                }
            }
        }
    }

    private fun clearLateMetadataWaits() {
        synchronized(lateMetadataLock) {
            lateWaitTimestampNs.fill(0L)
            lateWaitStartedNs.fill(0L)
            lateWaitWriteIndex = 0
        }
    }

    private companion object {
        const val TIMESTAMP_ALIGNMENT_TAG = "NIV99_TIMESTAMP_ALIGN"
        const val TIMESTAMP_ALIGNMENT_LOG_INTERVAL_FRAMES = 300L
        const val DEFAULT_ANALYSIS_TARGET_FPS = 25
        const val METADATA_OBSERVE_TAG = "NIV99_METADATA_OBSERVE"
        const val METADATA_LOG_INTERVAL_FRAMES = 120L
        const val PIXEL_ACTIVE_TAG = "NIV99_PIXEL_ACTIVE"
        const val PIXEL_LOG_INTERVAL_FRAMES = 120L
        const val FULL_FRAME_ESTIMATE_TAG = "NIV99_FULL_FRAME_ESTIMATE"
        const val LATE_METADATA_TAG = "NIV99_METADATA_LATE"
        const val DEFERRED_METADATA_TAG = "NIV99_METADATA_DEFERRED"
        const val PREVIEW_FALLBACK_TAG = "NIV99_PREVIEW_FALLBACK"
        const val PREVIEW_FALLBACK_LOG_INTERVAL_FRAMES = 120L
        const val DEFERRED_METADATA_LOG_INTERVAL_FRAMES = 120L
        const val ANALYSIS_LOG_INTERVAL_FRAMES = 30L
        const val ANALYSIS_REJECTION_LOG_INTERVAL_FRAMES = 30L
        const val EFFECT_EPSILON = 1.0e-4f
        const val TUNING_WORKER_THREAD_NAME = "NIV99-OrderedTuning"
        const val TUNING_WORKER_TAG = "NIV99_TUNING_WORKER"
    }
}
