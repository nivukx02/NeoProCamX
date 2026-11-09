package com.neoproc.neoproccamx.capture

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.ai.AIProcessor
import com.neoproc.neoproccamx.ai.EnhancementEngine
import com.neoproc.neoproccamx.ai.SceneType
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.camera.StillCaptureFailure
import com.neoproc.neoproccamx.camera.StillCaptureFrameIdentity
import com.neoproc.neoproccamx.camera.StillCaptureOverrides
import com.neoproc.neoproccamx.camera.StillCaptureRequestPhase
import com.neoproc.neoproccamx.camera.StillCaptureRequestToken
import com.neoproc.neoproccamx.color.ColorScience
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.CaptureRecommendation
import com.neoproc.neoproccamx.pipeline.SynchronousImageIngressGate
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The single owner of runtime provider lambdas supplied by the UI layer.
 *
 * Downstream still processors receive bound functions on this holder rather than the original
 * lambdas. Swapping the provider set therefore releases every UI capture at once. Provider reads
 * share the same re-entrant monitor as teardown, ensuring an old provider cannot begin executing
 * after [detachForTeardown] returns.
 */
internal class StillCaptureRuntimeProviderHolder(
    captureRecommendationProvider: () -> CaptureRecommendation?,
    aiEnhancementEnabledProvider: () -> Boolean,
    aiSceneTypeProvider: () -> SceneType,
    aiHdrSuggestedProvider: () -> Boolean,
    videoDetailProfileProvider: () -> VideoDetailProfile,
    debugValidationLabelProvider: () -> String?
) {
    private data class ProviderSet(
        val captureRecommendation: () -> CaptureRecommendation?,
        val aiEnhancementEnabled: () -> Boolean,
        val aiSceneType: () -> SceneType,
        val aiHdrSuggested: () -> Boolean,
        val videoDetailProfile: () -> VideoDetailProfile,
        val debugValidationLabel: () -> String?
    )

    private val providerLock = Any()
    private var providers = ProviderSet(
        captureRecommendation = captureRecommendationProvider,
        aiEnhancementEnabled = aiEnhancementEnabledProvider,
        aiSceneType = aiSceneTypeProvider,
        aiHdrSuggested = aiHdrSuggestedProvider,
        videoDetailProfile = videoDetailProfileProvider,
        debugValidationLabel = debugValidationLabelProvider
    )

    fun captureRecommendation(): CaptureRecommendation? = synchronized(providerLock) {
        providers.captureRecommendation()
    }

    fun aiEnhancementEnabled(): Boolean = synchronized(providerLock) {
        providers.aiEnhancementEnabled()
    }

    fun aiSceneType(): SceneType = synchronized(providerLock) {
        providers.aiSceneType()
    }

    fun aiHdrSuggested(): Boolean = synchronized(providerLock) {
        providers.aiHdrSuggested()
    }

    fun videoDetailProfile(): VideoDetailProfile = synchronized(providerLock) {
        providers.videoDetailProfile()
    }

    fun debugValidationLabel(): String? = synchronized(providerLock) {
        providers.debugValidationLabel()
    }

    fun detachForTeardown() {
        synchronized(providerLock) {
            providers = DETACHED_PROVIDERS
        }
    }

    private companion object {
        val DETACHED_PROVIDERS = ProviderSet(
            captureRecommendation = { null },
            aiEnhancementEnabled = { false },
            aiSceneType = { SceneType.GENERAL },
            aiHdrSuggested = { false },
            videoDetailProfile = { VideoDetailProfile.FOCUSED_DETAIL },
            debugValidationLabel = { null }
        )
    }
}

class StillCapture(
    private val context: Context,
    private val cameraController: CameraController,
    private val colorScience: ColorScience,
    private val computationalProcessor: AIProcessor,
    captureRecommendationProvider: () -> CaptureRecommendation?,
    aiEnhancementEnabledProvider: () -> Boolean = { true },
    aiSceneTypeProvider: () -> SceneType = { SceneType.GENERAL },
    aiHdrSuggestedProvider: () -> Boolean = { false },
    videoDetailProfileProvider: () -> VideoDetailProfile = {
        VideoDetailProfile.FOCUSED_DETAIL
    },
    debugValidationLabelProvider: () -> String? = { null }
) {
    companion object {
        private const val TAG = "StillCapture"
        private const val BUSY_RETRY_DELAY_MS = 55L
        private const val BUSY_RETRY_MAX_ATTEMPTS = 48
        private const val DEBUG_BUSY_RETRY_MAX_ATTEMPTS = 240
        private const val SINGLE_TIMEOUT_MS = 24_000L
        private const val SINGLE_PROCESSING_TIMEOUT_MS = 52_000L
        private const val HIGH_THROUGHPUT_SINGLE_TIMEOUT_MS = 8_000L
        private const val HIGH_THROUGHPUT_SINGLE_PROCESSING_TIMEOUT_MS = 12_000L
        private const val HDR_TIMEOUT_MS = 72_000L
        private const val HDR_PROCESSING_TIMEOUT_MS = 105_000L
        private const val RAW_TIMEOUT_MS = 36_000L
        private const val RAW_RESULT_ASSOCIATION_TIMEOUT_MS = 3_000L
        private const val DESTROY_IMAGE_INGRESS_TIMEOUT_MS = 3_500L
        private const val RETAINED_CAPTURE_RESULT_COUNT = 192
    }

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun adjustedCaptureTimeout(baseTimeoutMs: Long): Long =
        if (strictHighEndEnabled()) (baseTimeoutMs + 10_000L) else baseTimeoutMs

    private fun retryDelayMs(): Long =
        if (strictHighEndEnabled()) BUSY_RETRY_DELAY_MS.coerceAtLeast(60L) else BUSY_RETRY_DELAY_MS

    private fun isHighThroughputDebugCapture(debugLabel: String?): Boolean {
        val normalized = debugLabel?.trim()?.lowercase() ?: return false
        return normalized.startsWith("qa_stress_") || normalized.startsWith("qa_longrun_")
    }

    private val runtimeProviders = StillCaptureRuntimeProviderHolder(
        captureRecommendationProvider = captureRecommendationProvider,
        aiEnhancementEnabledProvider = aiEnhancementEnabledProvider,
        aiSceneTypeProvider = aiSceneTypeProvider,
        aiHdrSuggestedProvider = aiHdrSuggestedProvider,
        videoDetailProfileProvider = videoDetailProfileProvider,
        debugValidationLabelProvider = debugValidationLabelProvider
    )
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val captureControlDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "StillCaptureControl").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val captureControlScope = CoroutineScope(captureControlDispatcher + SupervisorJob())
    private val nextCaptureId = AtomicInteger(1)
    private val sessionCoordinator = StillCaptureSessionCoordinator()
    private val outputStore = StillCaptureOutputStore(
        context = context,
        debugValidationLabelProvider = runtimeProviders::debugValidationLabel,
        jpegQualityProvider = { cameraController.jpegQuality }
    )
    private val rawCaptureProcessor = StillRawCaptureProcessor(
        cameraController = cameraController,
        outputStore = outputStore,
        awaitCaptureResult = ::awaitCaptureResultForTimestamp,
        aiEnhancementEnabledProvider = runtimeProviders::aiEnhancementEnabled,
        aiSceneTypeProvider = runtimeProviders::aiSceneType,
        aiHdrSuggestedProvider = runtimeProviders::aiHdrSuggested,
        colorProfileProvider = { colorScience.activeProfileName },
        logEnabledProvider = { colorScience.logEnabled },
        lutEnabledProvider = { colorScience.lutEnabled },
        lutLabelProvider = { colorScience.activeLutLabel() }
    )
    private val imagePipeline = StillCaptureImagePipeline(
        cameraController = cameraController,
        colorScience = colorScience,
        computationalProcessor = computationalProcessor,
        outputStore = outputStore,
        rawCaptureProcessor = rawCaptureProcessor,
        enhancementEngine = EnhancementEngine(context),
        aiEnhancementEnabledProvider = runtimeProviders::aiEnhancementEnabled,
        aiSceneTypeProvider = runtimeProviders::aiSceneType,
        videoDetailProfileProvider = runtimeProviders::videoDetailProfile
    )

    private val captureTimeoutJobLock = Any()
    private val captureTimeoutGate = StillCaptureTimeoutGate()
    private var captureTimeoutJob: Job? = null

    @Volatile
    var lastResult: TotalCaptureResult? = null
    private val captureResultSynchronizer = ExactTimestampSynchronizer<TotalCaptureResult>(
        retainedValueCapacity = RETAINED_CAPTURE_RESULT_COUNT
    )
    private val jpegIdentitySynchronizer = ExactTimestampSynchronizer<StillCaptureFrameIdentity>(
        retainedValueCapacity = RETAINED_CAPTURE_RESULT_COUNT
    )
    private val rawIdentitySynchronizer = ExactTimestampSynchronizer<StillCaptureFrameIdentity>(
        retainedValueCapacity = RETAINED_CAPTURE_RESULT_COUNT
    )
    private val imageReaderIngress = SynchronousImageIngressGate {}
    private val cameraBufferTracker = StillCaptureCameraBufferTracker()
    private val rawDrainActive = AtomicBoolean(false)
    private val rawDrainRequested = AtomicBoolean(false)
    private val destroyFinalized = AtomicBoolean(false)
    private val destroyIngressSeal = AtomicReference<AutoCloseable?>(null)
    private val callbackRegistrationLock = Any()
    private val destroyLock = Any()
    private var callbackRegistrationSealed = false

    private val jpegReadyCallback: (ImageReader) -> Unit = { reader -> handleJpeg(reader) }
    private val rawReadyCallback: (ImageReader) -> Unit = { reader -> handleRaw(reader) }
    private val captureResultCallback: (TotalCaptureResult) -> Unit = { result -> onCaptureResult(result) }
    private val stillCaptureFailureCallback: (StillCaptureFailure) -> Unit = { failure ->
        handleControllerCaptureFailure(failure)
    }

    private val _events = MutableSharedFlow<CaptureEvent>(replay = 0, extraBufferCapacity = 6)
    val events: SharedFlow<CaptureEvent> = _events

    fun postCaptureError(message: String) {
        captureControlScope.launch {
            _events.emit(CaptureEvent.Error(message))
        }
    }

    fun isCaptureInFlight(): Boolean = sessionCoordinator.activeCaptureId() != null

    /**
     * True only while a still request owns, or is waiting for, Camera2 output buffers.
     *
     * Still processing may remain active after this becomes false so another shutter press cannot
     * overwrite its coordinator state. Recording admission uses this narrower resource-safety gate
     * and therefore does not wait for independent JPEG enhancement or MediaStore publication.
     */
    fun blocksRecordingAdmission(): Boolean = sessionCoordinator.blocksRecordingAdmission()

    /** True only while the still owner is waiting for a DNG frame from the shared RAW reader. */
    fun expectsRawImage(): Boolean = sessionCoordinator.expectsRawFrame()

    fun sealImageReaderIngress(): AutoCloseable = imageReaderIngress.seal()

    fun awaitImageReaderIngressIdle(timeoutMs: Long): Boolean = imageReaderIngress.awaitIdle(timeoutMs)

    /**
     * Bounded proof that no admitted reader callback and no successfully acquired Image remains.
     * The caller must keep image-reader ingress sealed for the duration of reader retirement.
     */
    fun awaitCameraBuffersReleased(timeoutMs: Long): Boolean {
        val boundedTimeoutMs = timeoutMs.coerceAtLeast(0L)
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(boundedTimeoutMs)
        val startedNanos = System.nanoTime()
        if (!imageReaderIngress.awaitIdle(boundedTimeoutMs)) return false
        val elapsedNanos = (System.nanoTime() - startedNanos).coerceAtLeast(0L)
        val remainingNanos = (timeoutNanos - elapsedNanos).coerceAtLeast(0L)
        return cameraBufferTracker.awaitReleasedNanos(remainingNanos)
    }

    /** Shared-reader entry point used by CameraViewModel's still/video RAW ownership multiplexer. */
    fun onRawImageAvailable(reader: ImageReader) = handleRaw(reader)

    fun setupCallbacks(
        registerRawCallback: Boolean = true,
        registerCaptureResultCallback: Boolean = true
    ) {
        synchronized(callbackRegistrationLock) {
            if (callbackRegistrationSealed) {
                Log.w(TAG, "Ignoring callback registration after still-capture teardown began")
                return
            }
            cameraController.onJpegReady = jpegReadyCallback
            if (registerRawCallback) cameraController.onRawReady = rawReadyCallback
            if (registerCaptureResultCallback) cameraController.onCaptureResult = captureResultCallback
            cameraController.onStillCaptureFailed = stillCaptureFailureCallback
        }
    }

    private fun detachCameraCallbacksForTeardown() {
        synchronized(callbackRegistrationLock) {
            callbackRegistrationSealed = true
            if (cameraController.onJpegReady === jpegReadyCallback) cameraController.onJpegReady = null
            if (cameraController.onRawReady === rawReadyCallback) cameraController.onRawReady = null
            if (cameraController.onCaptureResult === captureResultCallback) cameraController.onCaptureResult = null
            if (cameraController.onStillCaptureFailed === stillCaptureFailureCallback) {
                cameraController.onStillCaptureFailed = null
            }
        }
    }

    /** Accepts Camera2 results through an owning coordinator without replacing its existing callback. */
    fun onCaptureResult(result: TotalCaptureResult) {
        lastResult = result
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
        val token = result.request.tag as? StillCaptureRequestToken ?: return
        // This cache exists only for DNG metadata. Repeating preview results can evict the exact RAW
        // still result within the association timeout at high FPS, so retain tagged RAW results only.
        if (token.expectsRaw) captureResultSynchronizer.offer(timestamp, result)
        val identity = StillCaptureFrameIdentity(
            token = token,
            sensorTimestampNs = timestamp,
            frameNumber = result.frameNumber
        )
        if (token.expectsJpeg) jpegIdentitySynchronizer.offer(timestamp, identity)
        if (token.expectsRaw) rawIdentitySynchronizer.offer(timestamp, identity)
    }

    fun captureSingle(raw: Boolean = false) {
        val rawMode = StillCaptureCapturePolicy.resolveSingleCaptureRawMode(
            rawRequested = raw,
            supportsRaw = cameraController.supportsRaw
        )
        val presentation = StillRawWorkflowPolicy.resolveSinglePresentation(rawMode)
        val wantsNativeRaw = rawMode == StillCaptureCapturePolicy.SingleCaptureRawMode.NATIVE_RAW_COMPANION
        val capture = ActiveStillCapture(
            id = nextCaptureId.getAndIncrement(),
            mode = StillCaptureMode.SINGLE,
            primaryTag = presentation.primaryTag,
            rawCompanionTag = presentation.rawCompanionTag,
            debugLabel = runtimeProviders.debugValidationLabel(),
            failureLabel = presentation.failureLabel,
            hdrMode = "OFF",
            expectedJpegs = 1,
            hdrBracketEvs = listOf(0),
            rawRequested = wantsNativeRaw,
            singleCaptureRawMode = rawMode,
            processSingleBitmap = imagePipeline.shouldPostProcessSingleCapture(),
            rawCompanionAfterPrimary = false
        )
        val highThroughputDebugCapture = isHighThroughputDebugCapture(capture.debugLabel)
        val timeoutMs = if (isHighThroughputDebugCapture(capture.debugLabel)) {
            adjustedCaptureTimeout(HIGH_THROUGHPUT_SINGLE_TIMEOUT_MS)
        } else {
            adjustedCaptureTimeout(SINGLE_TIMEOUT_MS)
        }
        beginCapture(capture, timeoutMs) {
            val token = StillCaptureRequestToken(
                captureId = capture.id,
                phase = StillCaptureRequestPhase.SINGLE_PRIMARY,
                frameIndex = 0,
                frameCount = 1,
                expectsJpeg = true,
                expectsRaw = wantsNativeRaw
            )
            cameraController.captureStill(
                token = token,
                captureRaw = wantsNativeRaw,
                captureJpeg = true,
                overrides =
                    if (highThroughputDebugCapture) {
                        StillCaptureOverrides(
                            aeLock = true,
                            awbLock = true
                        )
                    } else {
                        null
                    }
            )
        }
    }

    fun captureHDR(rawCompanion: Boolean = false, hdrMode: String = "SMART_HDR") {
        val evs = StillCaptureCapturePolicy.resolveHdrBracketEvs(
            recommendedBracket = runtimeProviders.captureRecommendation()?.hdrBracketEvs,
            hdrMode = hdrMode
        )
        val presentation = StillRawWorkflowPolicy.resolveHdrPresentation(
            rawCompanionAfterPrimary = rawCompanion && cameraController.supportsRaw
        )
        val ndrDelivery = hdrMode.contains("NDR", ignoreCase = true) ||
            hdrMode.contains("NIVUKX", ignoreCase = true)
        val capture = ActiveStillCapture(
            id = nextCaptureId.getAndIncrement(),
            mode = StillCaptureMode.HDR,
            primaryTag = if (ndrDelivery) "NDR" else presentation.primaryTag,
            rawCompanionTag = presentation.rawCompanionTag,
            debugLabel = runtimeProviders.debugValidationLabel(),
            failureLabel = presentation.failureLabel,
            hdrMode = hdrMode,
            expectedJpegs = evs.size,
            hdrBracketEvs = evs,
            rawRequested = false,
            singleCaptureRawMode = StillCaptureCapturePolicy.SingleCaptureRawMode.JPEG_ONLY,
            processSingleBitmap = true,
            rawCompanionAfterPrimary = rawCompanion && cameraController.supportsRaw
        )
        beginCapture(capture, adjustedCaptureTimeout(HDR_TIMEOUT_MS)) {
            val wbLock = runtimeProviders.captureRecommendation()?.wbLockSuggested == true
            val tokens = evs.indices.map { index ->
                StillCaptureRequestToken(
                    captureId = capture.id,
                    phase = StillCaptureRequestPhase.HDR_BRACKET,
                    frameIndex = index,
                    frameCount = evs.size,
                    expectsJpeg = true,
                    expectsRaw = false
                )
            }
            cameraController.captureStillBurst(
                tokens = tokens,
                captureRaw = false,
                overridesList = evs.map { ev ->
                    StillCaptureOverrides(
                        evCompensation = ev,
                        awbLock = wbLock
                    )
                }
            )
        }
        scope.launch {
            _events.emit(CaptureEvent.Progress(0, evs.size))
        }
    }

    private fun beginCapture(
        capture: ActiveStillCapture,
        timeoutMs: Long,
        attempt: Int = 0,
        trigger: () -> Unit
    ) {
        val retryBudget = if (capture.debugLabel.isNullOrBlank()) {
            if (strictHighEndEnabled()) BUSY_RETRY_MAX_ATTEMPTS + 10 else BUSY_RETRY_MAX_ATTEMPTS
        } else {
            if (strictHighEndEnabled()) DEBUG_BUSY_RETRY_MAX_ATTEMPTS + 40 else DEBUG_BUSY_RETRY_MAX_ATTEMPTS
        }
        if (!sessionCoordinator.begin(capture)) {
            if (attempt < retryBudget) {
                captureControlScope.launch {
                    delay(retryDelayMs())
                    beginCapture(capture, timeoutMs, attempt = attempt + 1, trigger = trigger)
                }
            } else {
                captureControlScope.launch {
                    _events.emit(CaptureEvent.Error("Capture is still processing. Please retry in a moment."))
                }
            }
            return
        }
        scheduleTimeout(capture.id, capture.mode, timeoutMs)

        // CameraController.captureStill() only posts to CameraBackground. Queue that request before
        // publishing a UI event so a saturated/slow event collector can never delay the shutter.
        try {
            trigger()
        } catch (t: Throwable) {
            Log.e(TAG, "beginCapture trigger failed", t)
            failCapture(capture.id, "Capture start failed: ${t.message ?: "unknown error"}")
            return
        }
        captureControlScope.launch {
            _events.emit(CaptureEvent.Started)
        }
    }

    private fun scheduleTimeout(captureId: Int, mode: StillCaptureMode, timeoutMs: Long) {
        synchronized(captureTimeoutJobLock) {
            captureTimeoutJob?.cancel()
            val timeoutLease = captureTimeoutGate.replace()
            captureTimeoutJob = captureControlScope.launch {
                delay(timeoutMs)
                captureTimeoutGate.runIfCurrent(timeoutLease) {
                    if (sessionCoordinator.activeCaptureId() != captureId ||
                        sessionCoordinator.activeCaptureMode() != mode
                    ) {
                        return@runIfCurrent
                    }
                    // A slow secondary JPEG/HDR processor must never tear down an active recording after
                    // all Camera2 buffers have already been copied/closed. Recovery remains mandatory while
                    // the still request owns (or is waiting for) a JPEG/RAW buffer.
                    if (sessionCoordinator.blocksRecordingAdmission() &&
                        !sessionCoordinator.hasAcquiredCameraBuffers()
                    ) {
                        recoverCaptureSessionAfterFailure(
                            captureId = captureId,
                            phase = phaseForMode(mode),
                            reason = "${mode.timeoutLabel()} timeout"
                        )
                    }
                    failCapture(captureId, "${mode.timeoutLabel()} capture timed out")
                }
            }
        }
    }

    private fun handleJpeg(reader: ImageReader) {
        val ingress = imageReaderIngress.tryEnter() ?: return
        try {
            while (true) {
                val image = try {
                    if (sessionCoordinator.activeCaptureMode() == StillCaptureMode.HDR) {
                        reader.acquireNextImage()
                    } else {
                        reader.acquireLatestImage()
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "JPEG reader saturated", e)
                    break
                } catch (t: Throwable) {
                    Log.e(TAG, "handleJpeg failed", t)
                    break
                } ?: break

                val cameraBufferLease = cameraBufferTracker.acquire()
                var copyStartedNs = 0L
                var sensorTimestampNs = Long.MIN_VALUE
                var sessionBufferMarked = false
                var imageClosed = false
                val bytes = try {
                    copyStartedNs = SystemClock.elapsedRealtimeNanos()
                    sensorTimestampNs = image.timestamp
                    sessionCoordinator.markCameraBufferAcquired()
                    sessionBufferMarked = true
                    image.planes.firstOrNull()?.buffer?.duplicate()?.let { buffer ->
                        ByteArray(buffer.remaining()).also { buffer.get(it) }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "JPEG buffer copy failed timestampNs=$sensorTimestampNs", t)
                    null
                } finally {
                    try {
                        image.close()
                        imageClosed = true
                    } catch (t: Throwable) {
                        Log.e(TAG, "JPEG buffer close failed timestampNs=$sensorTimestampNs", t)
                    }
                    if (imageClosed) {
                        try {
                            if (sessionBufferMarked) sessionCoordinator.markCameraBufferReleased()
                        } finally {
                            cameraBufferLease.close()
                        }
                    }
                }
                if (!imageClosed) continue

                scope.launch {
                    var ownedToken: StillCaptureRequestToken? = null
                    try {
                        val identity = jpegIdentitySynchronizer.awaitExact(
                            timestampNs = sensorTimestampNs,
                            timeoutMillis = RAW_RESULT_ASSOCIATION_TIMEOUT_MS
                        )
                        if (identity == null) {
                            Log.e(TAG, "Discarding JPEG without exact Camera2 identity timestampNs=$sensorTimestampNs")
                            return@launch
                        }
                        val token = identity.token
                        ownedToken = token
                        cameraController.acknowledgeStillJpegBufferReleased(token)
                        if (!sessionCoordinator.markJpegBufferReleased(token)) {
                            Log.w(
                                TAG,
                                "Discarding stale/duplicate JPEG captureId=${token.captureId} " +
                                    "index=${token.frameIndex} timestampNs=$sensorTimestampNs"
                            )
                            return@launch
                        }
                        val jpegBytes = bytes
                        if (jpegBytes == null || jpegBytes.isEmpty()) {
                            failOwnedCapture(token, "JPEG buffer could not be copied")
                            return@launch
                        }
                        Log.i(
                            TAG,
                            "JPEG buffer released captureId=${token.captureId} index=${token.frameIndex} " +
                                "frame=${identity.frameNumber} timestampNs=$sensorTimestampNs bytes=${jpegBytes.size} " +
                                "copyMs=${(SystemClock.elapsedRealtimeNanos() - copyStartedNs) / 1_000_000.0}"
                        )
                        sessionCoordinator.routeJpegFrame(token, jpegBytes)?.let { processJpegBatch(it) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.e(TAG, "JPEG processing failed timestampNs=$sensorTimestampNs", error)
                        ownedToken?.let { token ->
                            failOwnedCapture(token, "JPEG processing failed")
                        }
                    }
                }
            }
        } finally {
            ingress.close()
        }
    }

    private suspend fun processJpegBatch(batch: ReadyStillCaptureBatch) {
        val capture = batch.capture
        val framesToProcess = batch.frames
        scheduleTimeout(capture.id, capture.mode, processingTimeoutFor(capture))

        if (capture.mode == StillCaptureMode.HDR) {
            batch.progress?.let { _events.emit(CaptureEvent.Progress(it.first, it.second)) }
        }

        val singleCaptureResult =
            if (capture.mode == StillCaptureMode.SINGLE) {
                processSingleCapture(capture, framesToProcess.firstOrNull())
            } else {
                null
            }

        val savedPath = when (capture.mode) {
            StillCaptureMode.SINGLE -> singleCaptureResult?.primarySavedPath
            StillCaptureMode.HDR -> processHdrCapture(capture, framesToProcess)
            StillCaptureMode.HDR_RAW_COMPANION -> null
        }

        if (savedPath.isNullOrEmpty()) {
            failCapture(capture.id, "${capture.failureLabel} capture failed")
            return
        }

        _events.emit(CaptureEvent.Saved(savedPath))
        if (capture.mode == StillCaptureMode.SINGLE) {
            when {
                !singleCaptureResult?.rawCompanionPath.isNullOrEmpty() ->
                    singleCaptureResult.rawCompanionPath.let { _events.emit(CaptureEvent.RawSaved(it)) }

                capture.singleCaptureRawMode == StillCaptureCapturePolicy.SingleCaptureRawMode.PROCESSED_SOURCE_FALLBACK ->
                    _events.emit(CaptureEvent.Error("Processed source package save failed"))
            }
        }
        onPrimarySaved(capture)
    }

    private suspend fun onPrimarySaved(capture: ActiveStillCapture) {
        val launchRawCompanion = sessionCoordinator.markPrimarySaved(capture.id) ==
            PrimaryStillCaptureTransition.LAUNCH_RAW_COMPANION

        if (launchRawCompanion) {
            scheduleTimeout(capture.id, StillCaptureMode.HDR_RAW_COMPANION, adjustedCaptureTimeout(RAW_TIMEOUT_MS))
            try {
                val token = StillCaptureRequestToken(
                    captureId = capture.id,
                    phase = StillCaptureRequestPhase.HDR_RAW_COMPANION,
                    frameIndex = 0,
                    frameCount = 1,
                    expectsJpeg = false,
                    expectsRaw = true
                )
                cameraController.captureStill(
                    token = token,
                    captureRaw = true,
                    captureJpeg = false,
                    overrides = StillCaptureOverrides(
                        aeLock = true,
                        awbLock = true
                    )
                )
            } catch (t: Throwable) {
                Log.e(TAG, "HDR RAW companion trigger failed", t)
                failCapture(capture.id, "RAW companion capture failed")
            }
        }
    }

    private fun handleRaw(reader: ImageReader) {
        val ingress = imageReaderIngress.tryEnter() ?: return
        rawDrainRequested.set(true)
        if (!rawDrainActive.compareAndSet(false, true)) {
            ingress.close()
            return
        }
        try {
            // Job completion is invoked even when teardown cancels the scope before the coroutine body
            // starts. It therefore owns the callback lease and closes the admission-vs-dispatch race
            // without relying on CoroutineStart.ATOMIC's delicate API.
            val drainJob = scope.launch {
                do {
                    rawDrainRequested.set(false)
                    drainRawReader(reader)
                } while (rawDrainRequested.get())
            }
            drainJob.invokeOnCompletion {
                rawDrainActive.set(false)
                val drainAgain = rawDrainRequested.getAndSet(false)
                ingress.close()
                // Close the race where a callback arrived after the last null acquire but before the
                // drainer released ownership. A close seal rejects this retry before acquisition.
                if (drainAgain && scope.isActive) handleRaw(reader)
            }
        } catch (failure: Throwable) {
            rawDrainActive.set(false)
            ingress.close()
            throw failure
        }
    }

    private suspend fun drainRawReader(reader: ImageReader) {
        while (true) {
            val image = try {
                reader.acquireNextImage()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "RAW reader saturated", e)
                break
            } catch (t: Throwable) {
                Log.e(TAG, "handleRaw failed", t)
                break
            } ?: break
            val cameraBufferLease = cameraBufferTracker.acquire()
            processRawImage(image, cameraBufferLease)
        }
    }

    private suspend fun processRawImage(
        image: Image,
        cameraBufferLease: StillCaptureCameraBufferLease
    ) {
        var sensorTimestampNs = Long.MIN_VALUE
        var identity: StillCaptureFrameIdentity? = null
        var capture: ActiveStillCapture? = null
        var savedPath: String? = null
        var processingFailed = false
        var associationFailureCaptureId: Int? = null
        var associationFailureMode: StillCaptureMode? = null
        var sessionBufferMarked = false
        var imageClosed = false
        try {
            sensorTimestampNs = image.timestamp
            sessionCoordinator.markCameraBufferAcquired()
            sessionBufferMarked = true
            val expectedCaptureId = sessionCoordinator.activeCaptureId()
            val expectedCaptureMode = sessionCoordinator.activeCaptureMode()
            if (expectedCaptureId == null ||
                expectedCaptureMode == null ||
                !sessionCoordinator.expectsRawFrame()
            ) {
                Log.d(TAG, "Discarding idle RAW image timestampNs=$sensorTimestampNs")
            } else {
                when (val association = rawIdentitySynchronizer.awaitExactOrNewer(
                    timestampNs = sensorTimestampNs,
                    timeoutMillis = RAW_RESULT_ASSOCIATION_TIMEOUT_MS
                )) {
                    is ExactOrNewerTimestampResult.Exact -> identity = association.value
                    is ExactOrNewerTimestampResult.Superseded -> Log.d(
                        TAG,
                        "Discarding stale RAW image timestampNs=$sensorTimestampNs " +
                            "newerStillTimestampNs=${association.newerTimestampNs}"
                    )
                    ExactOrNewerTimestampResult.TimedOut -> {
                        associationFailureCaptureId = expectedCaptureId
                        associationFailureMode = expectedCaptureMode
                        Log.e(
                            TAG,
                            "RAW Camera2 identity timed out captureId=$expectedCaptureId " +
                                "timestampNs=$sensorTimestampNs"
                        )
                    }
                }
            }
            capture = identity?.let { sessionCoordinator.beginRawProcessing(it.token) }
            val ownedCapture = capture
            if (ownedCapture == null && identity != null) {
                Log.w(TAG, "Discarding unowned RAW image timestampNs=$sensorTimestampNs")
            } else if (ownedCapture != null) {
                savedPath = rawCaptureProcessor.saveNativeDng(
                    image = image,
                    tag = ownedCapture.rawCompanionTag ?: ownedCapture.primaryTag,
                    debugLabel = ownedCapture.debugLabel
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            processingFailed = true
            Log.e(TAG, "RAW processing failed timestampNs=$sensorTimestampNs", error)
        } finally {
            try {
                image.close()
                imageClosed = true
            } catch (closeError: Throwable) {
                Log.e(TAG, "RAW buffer close failed timestampNs=$sensorTimestampNs", closeError)
            }
            if (imageClosed) {
                try {
                    if (sessionBufferMarked) sessionCoordinator.markCameraBufferReleased()
                } finally {
                    cameraBufferLease.close()
                }
            }
            val token = identity?.token
            val ownedCapture = capture
            if (imageClosed && token != null) {
                // Resource restoration and admission release are non-suspending and cannot be skipped
                // by a cancelled event collector or a failed DNG writer.
                cameraController.acknowledgeStillRawBufferReleased(token)
            }
            if (imageClosed && ownedCapture != null) {
                sessionCoordinator.finishRawProcessing(ownedCapture.id)
            }
        }

        val failedCaptureId = associationFailureCaptureId
        val failedCaptureMode = associationFailureMode
        if (failedCaptureId != null &&
            failedCaptureMode != null &&
            sessionCoordinator.activeCaptureId() == failedCaptureId
        ) {
            if (sessionCoordinator.blocksRecordingAdmission() &&
                !sessionCoordinator.hasAcquiredCameraBuffers()
            ) {
                recoverCaptureSessionAfterFailure(
                    captureId = failedCaptureId,
                    phase = phaseForMode(failedCaptureMode),
                    reason = "RAW Camera2 identity association timeout"
                )
            }
            failCapture(failedCaptureId, "RAW frame metadata association timed out")
            return
        }
        if (capture == null) return
        if (!processingFailed && !savedPath.isNullOrEmpty()) {
            _events.emit(CaptureEvent.RawSaved(savedPath))
        } else {
            _events.emit(CaptureEvent.Error("RAW save failed"))
        }
    }

    private suspend fun processSingleCapture(
        capture: ActiveStillCapture,
        jpegBytes: ByteArray?
    ): StillSingleCaptureResult = imagePipeline.processSingleCapture(capture, jpegBytes)

    private suspend fun processHdrCapture(
        capture: ActiveStillCapture,
        jpegFrames: List<ByteArray>
    ): String? = imagePipeline.processHdrCapture(capture, jpegFrames)

    private fun handleControllerCaptureFailure(failure: StillCaptureFailure) {
        if (sessionCoordinator.activeCaptureId() != failure.token.captureId) return
        if (sessionCoordinator.blocksRecordingAdmission() &&
            !sessionCoordinator.hasAcquiredCameraBuffers()
        ) {
            recoverCaptureSessionAfterFailure(
                captureId = failure.token.captureId,
                phase = failure.token.phase,
                reason = "controller failure: ${failure.message}"
            )
        }
        failCapture(failure.token.captureId, failure.message)
    }

    private fun failOwnedCapture(token: StillCaptureRequestToken, message: String) {
        if (sessionCoordinator.activeCaptureId() != token.captureId) return
        if (sessionCoordinator.blocksRecordingAdmission() &&
            !sessionCoordinator.hasAcquiredCameraBuffers()
        ) {
            recoverCaptureSessionAfterFailure(
                captureId = token.captureId,
                phase = token.phase,
                reason = message
            )
        }
        failCapture(token.captureId, message)
    }

    private fun recoverCaptureSessionAfterFailure(
        captureId: Int,
        phase: StillCaptureRequestPhase,
        reason: String
    ) {
        runCatching {
            cameraController.recoverStillCaptureSessionAfterFailure(captureId, phase)
        }.onFailure { error ->
            Log.e(TAG, "CRITICAL: Capture session recovery failed after $reason", error)
        }
    }

    private fun phaseForMode(mode: StillCaptureMode): StillCaptureRequestPhase = when (mode) {
        StillCaptureMode.SINGLE -> StillCaptureRequestPhase.SINGLE_PRIMARY
        StillCaptureMode.HDR -> StillCaptureRequestPhase.HDR_BRACKET
        StillCaptureMode.HDR_RAW_COMPANION -> StillCaptureRequestPhase.HDR_RAW_COMPANION
    }

    private suspend fun awaitCaptureResultForTimestamp(sensorTimestampNs: Long): TotalCaptureResult? {
        val exact = captureResultSynchronizer.awaitExact(
            timestampNs = sensorTimestampNs,
            timeoutMillis = RAW_RESULT_ASSOCIATION_TIMEOUT_MS
        )
        if (exact == null) {
            Log.e(
                TAG,
                "No exact Camera2 result for RAW sensor timestamp=$sensorTimestampNs; " +
                    "refusing to write a DNG with adjacent-frame metadata"
            )
        }
        return exact
    }

    private fun failCapture(captureId: Int, message: String) {
        if (!sessionCoordinator.clearIfActive(captureId)) return
        captureControlScope.launch {
            _events.emit(CaptureEvent.Error(message))
        }
    }

    private fun processingTimeoutFor(capture: ActiveStillCapture): Long =
        when (capture.mode) {
            StillCaptureMode.SINGLE -> if (isHighThroughputDebugCapture(capture.debugLabel)) {
                HIGH_THROUGHPUT_SINGLE_PROCESSING_TIMEOUT_MS
            } else {
                SINGLE_PROCESSING_TIMEOUT_MS
            }
            StillCaptureMode.HDR -> HDR_PROCESSING_TIMEOUT_MS
            StillCaptureMode.HDR_RAW_COMPANION -> adjustedCaptureTimeout(RAW_TIMEOUT_MS)
        }

    /** Releases every runtime lambda supplied by the owning UI layer and installs neutral defaults. */
    fun detachRuntimeProvidersForTeardown() {
        runtimeProviders.detachForTeardown()
    }

    fun destroy(): Boolean = synchronized(destroyLock) {
        if (destroyFinalized.get()) return@synchronized true
        if (destroyIngressSeal.get() == null) {
            val newSeal = imageReaderIngress.seal()
            if (!destroyIngressSeal.compareAndSet(null, newSeal)) newSeal.close()
        }
        detachCameraCallbacksForTeardown()
        detachRuntimeProvidersForTeardown()
        synchronized(captureTimeoutJobLock) {
            captureTimeoutGate.invalidate()
            captureTimeoutJob?.cancel()
            captureTimeoutJob = null
        }
        captureControlScope.cancel()
        scope.cancel()
        rawDrainRequested.set(false)
        sessionCoordinator.clear()
        if (!awaitCameraBuffersReleased(DESTROY_IMAGE_INGRESS_TIMEOUT_MS)) {
            Log.e(
                TAG,
                "StillCapture destroy retained ${cameraBufferTracker.outstandingCount()} " +
                    "acquired camera buffer(s); finalization deferred for guarded retry"
            )
            return@synchronized false
        }
        captureResultSynchronizer.close()
        jpegIdentitySynchronizer.close()
        rawIdentitySynchronizer.close()
        captureControlDispatcher.close()
        destroyFinalized.set(true)
        true
    }
}

sealed class CaptureEvent {
    object Started : CaptureEvent()
    data class Progress(val done: Int, val total: Int) : CaptureEvent()
    data class Saved(val uri: String) : CaptureEvent()
    data class RawSaved(val uri: String) : CaptureEvent()
    data class Error(val msg: String) : CaptureEvent()
}
