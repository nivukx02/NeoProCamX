package com.neoproc.neoproccamx.capture

import android.content.Context
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.util.Log
import com.neoproc.neoproccamx.ai.AIProcessor
import com.neoproc.neoproccamx.ai.SceneType
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.camera.StillCaptureOverrides
import com.neoproc.neoproccamx.color.ColorScience
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.CaptureRecommendation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import kotlin.math.abs

class StillCapture(
    private val context: Context,
    private val cameraController: CameraController,
    private val colorScience: ColorScience,
    private val computationalProcessor: AIProcessor,
    private val captureRecommendationProvider: () -> CaptureRecommendation?,
    private val aiEnhancementEnabledProvider: () -> Boolean = { true },
    private val aiSceneTypeProvider: () -> SceneType = { SceneType.GENERAL },
    private val aiHdrSuggestedProvider: () -> Boolean = { false },
    private val debugValidationLabelProvider: () -> String? = { null }
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

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val captureControlDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "StillCaptureControl").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val captureControlScope = CoroutineScope(captureControlDispatcher + SupervisorJob())
    private val nextCaptureId = AtomicInteger(1)
    private val sessionCoordinator = StillCaptureSessionCoordinator()
    private val outputStore = StillCaptureOutputStore(
        context = context,
        debugValidationLabelProvider = debugValidationLabelProvider,
        jpegQualityProvider = { cameraController.jpegQuality }
    )
    private val rawCaptureProcessor = StillRawCaptureProcessor(
        cameraController = cameraController,
        outputStore = outputStore,
        awaitCaptureResult = ::awaitCaptureResultForTimestamp,
        aiEnhancementEnabledProvider = aiEnhancementEnabledProvider,
        aiSceneTypeProvider = aiSceneTypeProvider,
        aiHdrSuggestedProvider = aiHdrSuggestedProvider,
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
        rawCaptureProcessor = rawCaptureProcessor
    )

    private var captureTimeoutJob: Job? = null
    private val jpegRoutingMutex = Mutex()

    @Volatile
    var lastResult: TotalCaptureResult? = null
    private val captureResultLock = Any()
    private val recentResultsByTimestamp =
        object : LinkedHashMap<Long, TotalCaptureResult>(16, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Long, TotalCaptureResult>?
            ): Boolean = size > 24
        }

    private val _events = MutableSharedFlow<CaptureEvent>(replay = 0, extraBufferCapacity = 6)
    val events: SharedFlow<CaptureEvent> = _events

    fun postCaptureError(message: String) {
        captureControlScope.launch {
            _events.emit(CaptureEvent.Error(message))
        }
    }

    fun isCaptureInFlight(): Boolean = sessionCoordinator.activeCaptureId() != null

    fun setupCallbacks(
        registerRawCallback: Boolean = true,
        registerCaptureResultCallback: Boolean = true
    ) {
        cameraController.onJpegReady = { reader -> handleJpeg(reader) }
        if (registerRawCallback) {
            cameraController.onRawReady = { reader -> handleRaw(reader) }
        }
        if (registerCaptureResultCallback) {
            cameraController.onCaptureResult = ::onCaptureResult
        }
        cameraController.onStillCaptureFailed = { message -> handleControllerCaptureFailure(message) }
    }

    /** Accepts Camera2 results through an owning coordinator without replacing its existing callback. */
    fun onCaptureResult(result: TotalCaptureResult) {
        lastResult = result
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
        if (timestamp != null) {
            synchronized(captureResultLock) {
                recentResultsByTimestamp[timestamp] = result
            }
        }
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
            debugLabel = debugValidationLabelProvider(),
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
            cameraController.captureStill(
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
            recommendedBracket = captureRecommendationProvider()?.hdrBracketEvs,
            hdrMode = hdrMode
        )
        val presentation = StillRawWorkflowPolicy.resolveHdrPresentation(
            rawCompanionAfterPrimary = rawCompanion && cameraController.supportsRaw
        )
        val capture = ActiveStillCapture(
            id = nextCaptureId.getAndIncrement(),
            mode = StillCaptureMode.HDR,
            primaryTag = presentation.primaryTag,
            rawCompanionTag = presentation.rawCompanionTag,
            debugLabel = debugValidationLabelProvider(),
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
            val wbLock = captureRecommendationProvider()?.wbLockSuggested == true
            cameraController.captureStillBurst(
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
        captureTimeoutJob?.cancel()
        captureTimeoutJob = captureControlScope.launch {
            delay(timeoutMs)
            val stillActive = sessionCoordinator.activeCaptureId() == captureId
            if (!stillActive) return@launch
            recoverCaptureSessionAfterFailure("${mode.timeoutLabel()} timeout")
            failCapture(captureId, "${mode.timeoutLabel()} capture timed out")
        }
    }

    private fun handleJpeg(reader: ImageReader) {
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

            val bytes = runCatching {
                image.planes.firstOrNull()?.buffer?.duplicate()?.let { buffer ->
                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                }
            }.getOrNull()
            image.close()
            if (bytes == null || bytes.isEmpty()) continue

            scope.launch {
                jpegRoutingMutex.withLock {
                    routeJpegFrame(bytes)
                }
            }
        }
    }

    private suspend fun routeJpegFrame(bytes: ByteArray) {
        val batch = sessionCoordinator.routeJpegFrame(bytes) ?: return
        val capture = batch.capture
        val framesToProcess = batch.frames
        val releaseCaptureBeforeProcessing =
            capture.mode == StillCaptureMode.SINGLE && !capture.rawRequested
        if (releaseCaptureBeforeProcessing) {
            sessionCoordinator.clearIfActive(capture.id)
        }
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
            if (releaseCaptureBeforeProcessing) {
                _events.emit(CaptureEvent.Error("${capture.failureLabel} capture failed"))
            } else {
                failCapture(capture.id, "${capture.failureLabel} capture failed")
            }
            return
        }

        _events.emit(CaptureEvent.Saved(savedPath))
        if (capture.mode == StillCaptureMode.SINGLE) {
            when {
                !singleCaptureResult?.rawCompanionPath.isNullOrEmpty() ->
                    singleCaptureResult.rawCompanionPath.let { _events.emit(CaptureEvent.RawSaved(it)) }

                capture.singleCaptureRawMode == StillCaptureCapturePolicy.SingleCaptureRawMode.SOFTWARE_RAW_FALLBACK ->
                    _events.emit(CaptureEvent.Error("RAW package save failed"))
            }
        }
        if (!releaseCaptureBeforeProcessing) {
            onPrimarySaved(capture)
        }
    }

    private suspend fun onPrimarySaved(capture: ActiveStillCapture) {
        val launchRawCompanion = sessionCoordinator.markPrimarySaved(capture.id) ==
            PrimaryStillCaptureTransition.LAUNCH_RAW_COMPANION

        if (launchRawCompanion) {
            scheduleTimeout(capture.id, StillCaptureMode.HDR_RAW_COMPANION, adjustedCaptureTimeout(RAW_TIMEOUT_MS))
            try {
                cameraController.captureStill(
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
        while (true) {
            val image = try {
                reader.acquireLatestImage()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "RAW reader saturated", e)
                break
            } catch (t: Throwable) {
                Log.e(TAG, "handleRaw failed", t)
                break
            } ?: break

            val capture = sessionCoordinator.beginRawProcessing()
            if (capture == null) {
                image.close()
                continue
            }

            scope.launch {
                processRawCapture(capture, image)
            }
        }
    }

    private suspend fun processRawCapture(capture: ActiveStillCapture, image: Image) {
        val savedPath = try {
            rawCaptureProcessor.saveNativeDng(
                image = image,
                tag = capture.rawCompanionTag ?: capture.primaryTag,
                debugLabel = capture.debugLabel
            )
        } finally {
            image.close()
        }

        if (!savedPath.isNullOrEmpty()) {
            _events.emit(CaptureEvent.RawSaved(savedPath))
        } else {
            _events.emit(CaptureEvent.Error("RAW save failed"))
        }

        sessionCoordinator.finishRawProcessing(capture.id)
    }

    private suspend fun processSingleCapture(
        capture: ActiveStillCapture,
        jpegBytes: ByteArray?
    ): StillSingleCaptureResult = imagePipeline.processSingleCapture(capture, jpegBytes)

    private suspend fun processHdrCapture(
        capture: ActiveStillCapture,
        jpegFrames: List<ByteArray>
    ): String? = imagePipeline.processHdrCapture(capture, jpegFrames)

    private fun handleControllerCaptureFailure(message: String) {
        val captureId = sessionCoordinator.activeCaptureId() ?: return
        recoverCaptureSessionAfterFailure("controller failure: $message")
        failCapture(captureId, message)
    }

    private fun recoverCaptureSessionAfterFailure(reason: String) {
        runCatching {
            cameraController.recoverStillCaptureSessionAfterFailure()
            cameraController.refreshOutputsAndSession(rebuildReaders = false)
        }.onFailure { error ->
            Log.e(TAG, "CRITICAL: Capture session recovery failed after $reason", error)
        }
    }

    private suspend fun awaitCaptureResultForTimestamp(sensorTimestampNs: Long): TotalCaptureResult? {
        repeat(100) {
            synchronized(captureResultLock) {
                recentResultsByTimestamp.remove(sensorTimestampNs)?.let { return it }
                val nearest = recentResultsByTimestamp.minByOrNull { (timestamp, _) ->
                    abs(timestamp - sensorTimestampNs)
                }
                if (nearest != null && abs(nearest.key - sensorTimestampNs) <= 80_000_000L) {
                    return nearest.value
                }
            }
            lastResult?.let { latest ->
                val latestTimestamp = latest.get(CaptureResult.SENSOR_TIMESTAMP)
                if (latestTimestamp != null && abs(latestTimestamp - sensorTimestampNs) <= 80_000_000L) {
                    return latest
                }
            }
            delay(25L)
        }
        return lastResult
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

    fun destroy() {
        captureTimeoutJob?.cancel()
        sessionCoordinator.clear()
        captureControlScope.cancel()
        captureControlDispatcher.close()
        scope.cancel()
    }
}

sealed class CaptureEvent {
    object Started : CaptureEvent()
    data class Progress(val done: Int, val total: Int) : CaptureEvent()
    data class Saved(val uri: String) : CaptureEvent()
    data class RawSaved(val uri: String) : CaptureEvent()
    data class Error(val msg: String) : CaptureEvent()
}
