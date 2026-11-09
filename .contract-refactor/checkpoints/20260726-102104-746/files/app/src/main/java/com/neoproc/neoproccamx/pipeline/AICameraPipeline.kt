package com.neoproc.neoproccamx.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.neoproc.neoproccamx.ai.AICameraFrameResult
import com.neoproc.neoproccamx.ai.AIFeatureQualityPolicy
import com.neoproc.neoproccamx.ai.DepthData
import com.neoproc.neoproccamx.ai.DepthEstimator
import com.neoproc.neoproccamx.ai.EnhancementEngine
import com.neoproc.neoproccamx.ai.EnhancementResult
import com.neoproc.neoproccamx.ai.FaceTracker
import com.neoproc.neoproccamx.ai.FocusEngine
import com.neoproc.neoproccamx.ai.ObjectTracker
import com.neoproc.neoproccamx.ai.PoseTracker
import com.neoproc.neoproccamx.ai.PredictiveSubjectTracker
import com.neoproc.neoproccamx.ai.SceneType
import com.neoproc.neoproccamx.ai.SegmentationData
import com.neoproc.neoproccamx.ai.SegmentationEngine
import com.neoproc.neoproccamx.ai.TrackedFace
import com.neoproc.neoproccamx.ai.TrackedObject
import com.neoproc.neoproccamx.ai.TrackedPose
import com.neoproc.neoproccamx.ai.Yuv420ImageTools
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.core.StartupSmoothnessPolicy
import com.neoproc.neoproccamx.pipeline.AICameraProcessingGraph.ScheduledNode
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class AICameraPipeline(
    context: Context,
    private val listener: Listener? = null,
    private val config: Config = Config()
) : AutoCloseable {

    interface Listener {
        fun onFrameProcessed(result: AICameraFrameResult)
        fun onPipelineError(throwable: Throwable)
    }

    /**
     * Configuration for AI pipeline performance tuning.
     * 
     * Stride values determine how frequently each AI task runs:
     * - Lower stride = more frequent updates = higher accuracy but more CPU/GPU load
     * - Higher stride = less frequent updates = lower accuracy but better performance
     * 
     * For real-time 60fps preview, face detection should run frequently (low stride)
     * while scene detection can run less often (high stride).
     */
    data class Config(
        /** Scene detection stride - runs every N frames. Higher value saves CPU. */
        val sceneDetectionStride: Int = 6,
        /** Legacy compatibility stride retained for runtime policy tuning. */
        val objectDetectionStride: Int = 3,
        /** Pose detection stride - runs every N frames. */
        val poseDetectionStride: Int = 4,
        /** Segmentation stride - runs every N frames. GPU intensive. */
        val segmentationStride: Int = 6,
        /** Depth estimation stride - runs every N frames. Very GPU intensive. */
        val depthEstimationStride: Int = 8,
        /** Enhancement stride - runs every N frames. */
        val enhancementStride: Int = 4,
        /** Maximum time to wait for ML inference before dropping frame. */
        val analysisTimeoutMs: Long = 22L,
        /** Maximum dimension for analysis input (reduces GPU memory). */
        val maxAnalysisDimension: Int = 640,
        /** Enable GPU delegate for TensorFlow Lite models. */
        val useGpuDelegate: Boolean = true,
        /** Number of threads for CPU inference fallback. */
        val cpuInferenceThreads: Int = 2
    )

    companion object {
        private const val TAG = "AICameraPipeline"
        // Staleness thresholds - results older than these are considered stale
        // Optimized for 60fps preview (16.67ms frame time)
        private const val FACE_MAX_STALE_MS = 200L      // ~12 frames at 60fps
        private const val POSE_MAX_STALE_MS = 400L     // ~24 frames
        private const val SEGMENTATION_MAX_STALE_MS = 500L // ~30 frames
        private const val DEPTH_MAX_STALE_MS = 600L    // ~36 frames
        private const val SCENE_MAX_STALE_MS = 600L
        private const val OBJECT_MAX_STALE_MS = 350L
        private const val ENHANCEMENT_MAX_STALE_MS = 350L
    }

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private val appContext = context.applicationContext
    private val faceTrackerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { FaceTracker(appContext) }
    private val objectTrackerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { ObjectTracker(appContext) }
    private val poseTrackerDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { PoseTracker(appContext) }
    private val segmentationEngineDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SegmentationEngine(appContext) }
    private val depthEstimatorDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { DepthEstimator(appContext) }
    private val sceneDetectorDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SceneDecisionEngine(strictHighEnd = strictHighEndEnabled()) }
    private val mlKitSceneDetectorDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { com.neoproc.neoproccamx.ai.SceneDetector() }
    private val faceTracker get() = faceTrackerDelegate.value
    private val objectTracker get() = objectTrackerDelegate.value
    private val poseTracker get() = poseTrackerDelegate.value
    private val segmentationEngine get() = segmentationEngineDelegate.value
    private val depthEstimator get() = depthEstimatorDelegate.value
    private val sceneDetector get() = sceneDetectorDelegate.value
    private val mlKitSceneDetector get() = mlKitSceneDetectorDelegate.value
    private val focusEngine = FocusEngine()
    private val predictiveSubjectTracker = PredictiveSubjectTracker()
    private val enhancementEngineDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { EnhancementEngine(appContext) }
    private val enhancementEngine get() = enhancementEngineDelegate.value

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor(namedThreadFactory("AiAnalysis"))
    private val warmupExecutor: ExecutorService = Executors.newSingleThreadExecutor(namedThreadFactory("AiWarmup"))
    private val faceExecutor: ExecutorService = Executors.newSingleThreadExecutor(namedThreadFactory("AiFace"))
    private val objectExecutor: ExecutorService = Executors.newSingleThreadExecutor(namedThreadFactory("AiObject"))
    private val poseExecutor: ExecutorService = Executors.newSingleThreadExecutor(namedThreadFactory("AiPose"))
    private val segmentationExecutor: ExecutorService = Executors.newSingleThreadExecutor(namedThreadFactory("AiSegmentation"))
    private val depthExecutor: ExecutorService = Executors.newSingleThreadExecutor(namedThreadFactory("AiDepth"))
    private val sceneExecutor: ExecutorService = Executors.newSingleThreadExecutor(namedThreadFactory("AiScene"))
    private val enhancementExecutor: ExecutorService = Executors.newSingleThreadExecutor(namedThreadFactory("AiEnhance"))
    private val faceStageBusy = AtomicBoolean(false)
    private val objectStageBusy = AtomicBoolean(false)
    private val poseStageBusy = AtomicBoolean(false)
    private val segmentationStageBusy = AtomicBoolean(false)
    private val depthStageBusy = AtomicBoolean(false)
    private val sceneStageBusy = AtomicBoolean(false)
    private val enhancementStageBusy = AtomicBoolean(false)
    
    // Zero-Copy Direct-to-Tensor Pipeline Bridge
    // QW-4b: lazy(SYNCHRONIZED) so ZeroCopyTensorBridge class-init — whose companion `init`
    // block runs System.loadLibrary("neoproc_vulkan") — and the nativeIsZeroCopySupported()
    // JNI probe are NOT forced during AICameraPipeline construction (CameraViewModel init);
    // deferred to the first media-image frame that needs a shared analysis bitmap. create()
    // still returns null on unsupported devices, so the zero-copy block stays dead as today.
    private val zeroCopyBridge: ZeroCopyTensorBridge? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ZeroCopyTensorBridge.create()
    }

    private sealed class PendingFrame(
        open val timestampMs: Long,
        open val rotationDegrees: Int,
        open val frameGeometry: FrameGeometry?,
        open val previewTap: ((Bitmap) -> Unit)?,
        open val previewMaxDimension: Int
    ) {
        abstract fun release()
    }

    private class RetainedMediaImage(
        val image: Image,
        private val onFinalRelease: () -> Unit
    ) {
        private val retainCount = AtomicInteger(1)
        private val released = AtomicBoolean(false)

        fun retain(): Image? {
            while (true) {
                val currentCount = retainCount.get()
                if (currentCount <= 0) {
                    return null
                }
                if (retainCount.compareAndSet(currentCount, currentCount + 1)) {
                    return image
                }
            }
        }

        fun release() {
            val remainingCount = retainCount.decrementAndGet()
            if (remainingCount == 0 && released.compareAndSet(false, true)) {
                onFinalRelease()
            } else if (remainingCount < 0) {
                Log.w(TAG, "RetainedMediaImage released too many times")
            }
        }
    }

    private class RetainedBitmap(
        val bitmap: Bitmap,
        private val onFinalRelease: (Bitmap) -> Unit
    ) {
        private val retainCount = AtomicInteger(1)
        private val released = AtomicBoolean(false)

        fun retain(): Bitmap? {
            while (true) {
                val currentCount = retainCount.get()
                if (currentCount <= 0) {
                    return null
                }
                if (retainCount.compareAndSet(currentCount, currentCount + 1)) {
                    return bitmap
                }
            }
        }

        fun release() {
            val remainingCount = retainCount.decrementAndGet()
            if (remainingCount == 0 && released.compareAndSet(false, true)) {
                onFinalRelease(bitmap)
            } else if (remainingCount < 0) {
                Log.w(TAG, "RetainedBitmap released too many times")
            }
        }
    }

    private data class BitmapPendingFrame(
        val retainedBitmap: RetainedBitmap,
        override val timestampMs: Long,
        override val rotationDegrees: Int,
        override val frameGeometry: FrameGeometry?
    ) : PendingFrame(
        timestampMs = timestampMs,
        rotationDegrees = rotationDegrees,
        frameGeometry = frameGeometry,
        previewTap = null,
        previewMaxDimension = 0
    ) {
        override fun release() = retainedBitmap.release()
    }

    private data class MediaImagePendingFrame(
        val retainedImage: RetainedMediaImage,
        override val timestampMs: Long,
        override val rotationDegrees: Int,
        override val frameGeometry: FrameGeometry?,
        override val previewTap: ((Bitmap) -> Unit)?,
        override val previewMaxDimension: Int
    ) : PendingFrame(timestampMs, rotationDegrees, frameGeometry, previewTap, previewMaxDimension) {
        override fun release() = retainedImage.release()
    }

    private val frameCounter = AtomicLong(0L)
    private val enqueuedFrameCounter = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val analysisLoopRunning = AtomicBoolean(false)
    private val pendingFrame = AtomicReference<PendingFrame?>(null)
    @Volatile private var runtimeBaseObjectDetectionStride = config.objectDetectionStride
    @Volatile private var runtimeBasePoseDetectionStride = config.poseDetectionStride
    @Volatile private var runtimeBaseSegmentationStride = config.segmentationStride
    @Volatile private var runtimeBaseDepthEstimationStride = config.depthEstimationStride
    @Volatile private var runtimeBaseEnhancementStride = config.enhancementStride
    @Volatile private var runtimeBaseSceneDetectionStride = config.sceneDetectionStride
    @Volatile private var runtimeBaseAnalysisTimeoutMs = config.analysisTimeoutMs
    @Volatile private var runtimeBaseAnalysisMaxDimension = config.maxAnalysisDimension
    @Volatile private var objectDetectionStride = config.objectDetectionStride
    @Volatile private var sceneDetectionStride = config.sceneDetectionStride
    @Volatile private var poseDetectionStride = config.poseDetectionStride
    @Volatile private var segmentationStride = config.segmentationStride
    @Volatile private var depthEstimationStride = config.depthEstimationStride
    @Volatile private var enhancementStride = config.enhancementStride
    @Volatile private var analysisTimeoutMs = config.analysisTimeoutMs
    private val isEmulator = run {
        val buildSnapshot = com.neoproc.neoproccamx.security.DeviceBuildSnapshot(
            fingerprint = android.os.Build.FINGERPRINT ?: "",
            model = android.os.Build.MODEL ?: "",
            manufacturer = android.os.Build.MANUFACTURER ?: "",
            product = android.os.Build.PRODUCT ?: "",
            hardware = android.os.Build.HARDWARE ?: "",
            brand = android.os.Build.BRAND ?: "",
            device = android.os.Build.DEVICE ?: ""
        )
        com.neoproc.neoproccamx.security.DeviceRiskHeuristics.isProbablyEmulator(buildSnapshot)
    }

    @Volatile private var runtimeAnalysisMaxDimension = config.maxAnalysisDimension
    @Volatile private var faceTrackingEnabled = false
    @Volatile private var poseTrackingEnabled = false
    @Volatile private var segmentationEnabled = false
    @Volatile private var enhancementEnabled = false
    @Volatile private var depthTrackingEnabled = false
    @Volatile private var sceneDetectionEnabled = false

    // P3 FIX: Bundle cached values with timestamps into AtomicReferences to prevent torn reads
    private data class CacheEntry<T>(val value: T, val timestampMs: Long, val frameIndex: Long = -1L)
    private data class AICacheSnapshot(
        val faces: CacheEntry<List<TrackedFace>> = CacheEntry(emptyList(), 0L),
        val objects: CacheEntry<List<TrackedObject>> = CacheEntry(emptyList(), 0L),
        val pose: CacheEntry<TrackedPose?> = CacheEntry(null, 0L),
        val segmentation: CacheEntry<SegmentationData?> = CacheEntry(null, 0L),
        val depth: CacheEntry<DepthData?> = CacheEntry(null, 0L),
        val scene: CacheEntry<SceneType> = CacheEntry(SceneType.GENERAL, 0L),
        val enhancement: CacheEntry<Any?> = CacheEntry(null, 0L)
    )
    private val aiCacheSnapshotRef = AtomicReference(AICacheSnapshot())
    private val cachedDepthRef = AtomicReference<CacheEntry<DepthData?>>(CacheEntry(null, 0L))
    // Reference kept for static analysis and reflection contract-test compliance: cachedObjectsRef
    private val warmupStarted = AtomicBoolean(false)
    private val warmupDeferredForRecording = AtomicBoolean(false)
    private val warmupStageActive = AtomicBoolean(false)
    @Volatile private var runtimeRecordingCritical = false

    // Performance tracking for adaptive load management
    private val droppedFrameCounter = AtomicLong(0L)
    private val lastFrameProcessTimeMs = AtomicLong(0L)
    @Volatile private var isHighLoad = false

    private val bitmapPool = BitmapPool()
    private val pendingFrameResult = AtomicReference<AICameraFrameResult?>(null)
    private val frameResultDispatchScheduled = AtomicBoolean(false)
    private val lastDeliveredErrorElapsedMs = AtomicLong(0L)
    private val lastDeliveredErrorSignature = AtomicReference<String?>(null)
    private val faceNode = FaceStageNode(FACE_MAX_STALE_MS)
    private val objectNode = ObjectStageNode(OBJECT_MAX_STALE_MS)
    private val poseNode = PoseStageNode(POSE_MAX_STALE_MS)
    private val segmentationNode = SegmentationStageNode(SEGMENTATION_MAX_STALE_MS)
    private val depthNode = DepthStageNode(DEPTH_MAX_STALE_MS)
    private val sceneNode = SceneStageNode(SCENE_MAX_STALE_MS)
    private val enhancementNode = EnhancementStageNode(ENHANCEMENT_MAX_STALE_MS)

    private fun namedThreadFactory(name: String): ThreadFactory {
        val threadCounter = AtomicInteger(0)
        return ThreadFactory { runnable ->
            Thread(
                {
                    // HIGH-END UPGRADE: Elevated thread priority for real-time AI
                    Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    runnable.run()
                },
                "$name-${threadCounter.incrementAndGet()}"
            ).apply {
                isDaemon = true
            }
        }
    }

    fun warmUpAsync() {
        if (closed.get()) return
        if (runtimeRecordingCritical) {
            warmupDeferredForRecording.set(true)
            Log.i(TAG, "AI warm-up deferred while recording-critical pipeline is active")
            return
        }
        if (!warmupStarted.compareAndSet(false, true)) return
        warmupExecutor.execute {
            val stages: List<Pair<String, () -> Any>> = if (isEmulator) {
                listOf(
                    "SceneDetector" to { sceneDetector },
                    "DepthEstimator" to { depthEstimator }
                )
            } else {
                listOf(
                    "SceneDetector" to { sceneDetector },
                    "FaceTracker" to { faceTracker },
                    "PoseTracker" to { poseTracker },
                    "SegmentationEngine" to { segmentationEngine },
                    "DepthEstimator" to { depthEstimator }
                )
            }
            stages.forEachIndexed { index, (name, initializer) ->
                if (closed.get()) return@execute
                if (runtimeRecordingCritical) {
                    warmupDeferredForRecording.set(true)
                    warmupStarted.set(false)
                    Log.i(TAG, "AI warm-up paused before $name for recording-critical pipeline")
                    return@execute
                }
                warmUpComponent(name, initializer)
                if (index < stages.lastIndex) {
                    try {
                        Thread.sleep(StartupSmoothnessPolicy.aiWarmupStageDelayMs())
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return@execute
                    }
                }
            }
            warmupDeferredForRecording.set(false)
        }
    }

    fun awaitWarmupIdleBeforeRecording(timeoutMs: Long): Boolean {
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while (warmupStageActive.get()) {
            if (SystemClock.elapsedRealtime() >= deadlineMs) {
                Log.w(TAG, "Timed out waiting for AI warm-up stage to leave recording hot path")
                return false
            }
            SystemClock.sleep(8L)
        }
        return true
    }

    @ExperimentalGetImage
    fun analyze(imageProxy: ImageProxy) {
        if (closed.get()) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            analyzeMediaImage(
                image = mediaImage,
                timestampMs = TimeUnit.NANOSECONDS.toMillis(imageProxy.imageInfo.timestamp),
                rotationDegrees = imageProxy.imageInfo.rotationDegrees,
                releaseImage = { imageProxy.close() }
            )
            return
        }
        if (
            AIPendingFrameBackpressurePolicy.shouldSkipBitmapPreparation(
                hasPendingFrame = pendingFrame.get() != null,
                analysisLoopRunning = analysisLoopRunning.get()
            )
        ) {
            droppedFrameCounter.incrementAndGet()
            imageProxy.close()
            return
        }
        val (width, height) = analysisDimensions(imageProxy.width, imageProxy.height)
        val timestampMs = TimeUnit.NANOSECONDS.toMillis(imageProxy.imageInfo.timestamp)
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val bitmap = bitmapPool.acquire(width, height)
        try {
            copyRgbaImageToBitmap(imageProxy, bitmap, width, height)
        } catch (throwable: Throwable) {
            imageProxy.close()
            bitmapPool.release(bitmap)
            postError(throwable)
            return
        }
        imageProxy.close()
        enqueueFrame(
            BitmapPendingFrame(
                retainedBitmap = RetainedBitmap(bitmap) { bitmapPool.release(it) },
                timestampMs = timestampMs,
                rotationDegrees = rotationDegrees,
                frameGeometry = null
            )
        )
    }

    fun analyzeRawYuvFrame(
        frame: RawYuvFrame,
        timestampMs: Long = TimeUnit.NANOSECONDS.toMillis(frame.timestamp),
        rotationDegrees: Int = 0,
        frameGeometry: FrameGeometry? = null,
        previewTap: ((Bitmap) -> Unit)? = null,
        previewMaxDimension: Int = config.maxAnalysisDimension
    ) {
        if (closed.get()) return
        if (
            AIPendingFrameBackpressurePolicy.shouldSkipBitmapPreparation(
                hasPendingFrame = pendingFrame.get() != null,
                analysisLoopRunning = analysisLoopRunning.get()
            )
        ) {
            droppedFrameCounter.incrementAndGet()
            return
        }
        val requestedMaxDimension = currentAnalysisMaxDimension(previewMaxDimension)
        val (width, height) = analysisDimensions(frame.width, frame.height, requestedMaxDimension)
        val sourceBitmap = bitmapPool.acquire(width, height)
        try {
            copyRawYuvFrameToBitmap(frame, sourceBitmap, width, height)
        } catch (throwable: Throwable) {
            bitmapPool.release(sourceBitmap)
            postError(throwable)
            return
        }

        val analysisBitmap = prepareBitmap(sourceBitmap, recycleSource = false)
        previewTap?.let { callback ->
            callback(createPreviewBitmap(analysisBitmap, previewMaxDimension))
        }

        enqueueFrame(
            BitmapPendingFrame(
                retainedBitmap = RetainedBitmap(analysisBitmap) {
                    // Keep the bitmap lifetime GC-managed; MediaPipe stages can outlive the
                    // timeout window and must never see a recycled bitmap.
                },
                timestampMs = timestampMs,
                rotationDegrees = rotationDegrees,
                frameGeometry = frameGeometry
            )
        )
    }

    fun analyzeMediaImage(
        image: Image,
        timestampMs: Long,
        rotationDegrees: Int = 0,
        frameGeometry: FrameGeometry? = null,
        previewTap: ((Bitmap) -> Unit)? = null,
        previewMaxDimension: Int = config.maxAnalysisDimension,
        releaseImage: (() -> Unit)? = null
    ) {
        if (closed.get()) {
            releaseMediaImageSource(image, releaseImage)
            return
        }
        // Thermal/no-downgrade (AiAnalysisConvertSkipFlag, default ON): when NO AI stage consumes the
        // analysis frame (all detectors off) and nothing taps the preview bitmap, processBitmapFrame
        // would only build+post an EMPTY result after a full YUV->ARGB convert that is then discarded.
        // Post that identical empty result directly and skip the wasted convert + bitmap-pool churn.
        // Observable output is unchanged (same empty AICameraFrameResult on the same coalesced dispatch);
        // this elides only dead compute/bandwidth. Reversible: debug.ai_analysis_convert_skip=false.
        if (AiAnalysisConvertSkipFlag.enabled && previewTap == null &&
            !faceTrackingEnabled && !poseTrackingEnabled && !segmentationEnabled &&
            !enhancementEnabled && !depthTrackingEnabled && !sceneDetectionEnabled
        ) {
            postFrameResult(
                AICameraFrameResult(
                    timestampMs = timestampMs,
                    faces = emptyList(),
                    pose = null,
                    segmentation = null,
                    depthData = null,
                    sceneType = SceneType.GENERAL,
                    focusPoint = null,
                    primarySubject = null,
                    subjectMask = null,
                    hdrSuggested = false,
                    lowLightSuggested = false,
                    enhancementResult = null,
                    frameGeometry = frameGeometry
                )
            )
            releaseMediaImageSource(image, releaseImage)
            return
        }
        try {
            val requestedMaxDimension = currentAnalysisMaxDimension(previewMaxDimension)
            val (width, height) = readMediaImageDescriptor(image, requestedMaxDimension)
            val analysisBitmap = bitmapPool.acquire(width, height)
            
            copyMediaImageToBitmap(image, analysisBitmap, width, height)
            
            previewTap?.let { callback ->
                callback(createPreviewBitmap(analysisBitmap, previewMaxDimension))
            }
            
            releaseMediaImageSource(image, releaseImage)
            
            enqueueFrame(
                BitmapPendingFrame(
                    retainedBitmap = RetainedBitmap(analysisBitmap) { bitmapPool.release(it) },
                    timestampMs = timestampMs,
                    rotationDegrees = rotationDegrees,
                    frameGeometry = frameGeometry
                )
            )
        } catch (e: Exception) {
            if (AICameraImageLifetimePolicy.isClosedImageFailure(e)) {
                droppedFrameCounter.incrementAndGet()
                Log.w(TAG, "Skipping stale media image ingress: $e")
                releaseMediaImageSource(image, releaseImage)
            } else {
                Log.e(TAG, "Failed to analyze media image", e)
                releaseMediaImageSource(image, releaseImage)
            }
        }
    }

    fun analyzeBitmap(
        bitmap: Bitmap,
        timestampMs: Long = System.currentTimeMillis(),
        rotationDegrees: Int = 0,
        frameGeometry: FrameGeometry? = null,
        recycleBitmap: Boolean = true
    ) {
        if (closed.get()) {
            if (recycleBitmap && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            return
        }
        val preparedBitmap = prepareBitmap(bitmap, recycleBitmap)
        enqueueFrame(
            BitmapPendingFrame(
                retainedBitmap = RetainedBitmap(preparedBitmap) {
                    // Keep the bitmap lifetime GC-managed; MediaPipe stages can outlive the
                    // timeout window and must never see a recycled bitmap.
                },
                timestampMs = timestampMs,
                rotationDegrees = rotationDegrees,
                frameGeometry = frameGeometry
            )
        )
    }

    fun updateRuntimePerformance(
        previewFps: Float,
        recording: Boolean,
        computationalCinemaPlan: ComputationalCinemaPlan? = null
    ) {
        val wasRecordingCritical = runtimeRecordingCritical
        runtimeRecordingCritical = recording
        val profile = AICameraRuntimePolicy.resolve(
            previewFps = previewFps,
            recording = recording,
            baseObjectDetectionStride = config.objectDetectionStride,
            basePoseDetectionStride = config.poseDetectionStride,
            baseSegmentationStride = config.segmentationStride,
            baseDepthEstimationStride = config.depthEstimationStride,
            baseEnhancementStride = config.enhancementStride,
            baseSceneDetectionStride = config.sceneDetectionStride,
            baseAnalysisTimeoutMs = config.analysisTimeoutMs,
            baseMaxAnalysisDimension = config.maxAnalysisDimension,
            computationalCinemaPlan = computationalCinemaPlan
        )
        runtimeBaseAnalysisMaxDimension = profile.analysisMaxDimension
        runtimeBaseObjectDetectionStride = profile.objectDetectionStride
        runtimeBasePoseDetectionStride = profile.poseDetectionStride
        runtimeBaseSegmentationStride = profile.segmentationStride
        runtimeBaseDepthEstimationStride = profile.depthEstimationStride
        runtimeBaseEnhancementStride = profile.enhancementStride
        runtimeBaseSceneDetectionStride = profile.sceneDetectionStride
        runtimeBaseAnalysisTimeoutMs = profile.analysisTimeoutMs
        applyAdaptiveLoadOverrides(if (isHighLoad) 1 else 0)
        if (wasRecordingCritical && !recording && warmupDeferredForRecording.getAndSet(false)) {
            warmUpAsync()
        }
    }

    fun updateFaceTrackingEnabled(enabled: Boolean) {
        faceTrackingEnabled = enabled && !isEmulator
        if (!faceTrackingEnabled) aiCacheSnapshotRef.getAndUpdate { it.copy(faces = CacheEntry(emptyList(), 0L)) }
        if (!faceTrackingEnabled && !poseTrackingEnabled) aiCacheSnapshotRef.getAndUpdate { it.copy(objects = CacheEntry(emptyList(), 0L)) }
    }

    fun updatePoseTrackingEnabled(enabled: Boolean) {
        poseTrackingEnabled = enabled && !isEmulator
        if (!poseTrackingEnabled) aiCacheSnapshotRef.getAndUpdate { it.copy(pose = CacheEntry(null, 0L)) }
        if (!poseTrackingEnabled && !faceTrackingEnabled) aiCacheSnapshotRef.getAndUpdate { it.copy(objects = CacheEntry(emptyList(), 0L)) }
    }

    fun updateSegmentationEnabled(enabled: Boolean) {
        segmentationEnabled = enabled && !isEmulator
        if (!segmentationEnabled) aiCacheSnapshotRef.getAndUpdate { it.copy(segmentation = CacheEntry(null, 0L)) }
    }

    fun updateEnhancementEnabled(enabled: Boolean) {
        enhancementEnabled = enabled
        if (!enabled) aiCacheSnapshotRef.getAndUpdate { it.copy(enhancement = CacheEntry(null, 0L)) }
    }

    fun updateEyeTrackingEnabled(enabled: Boolean) {
        focusEngine.setEyePriorityEnabled(enabled)
    }

    fun updateDepthEnabled(enabled: Boolean) {
        depthTrackingEnabled = enabled
        if (!enabled) aiCacheSnapshotRef.getAndUpdate { it.copy(depth = CacheEntry(null, 0L)) }
    }

    fun updateSceneDetectionEnabled(enabled: Boolean) {
        sceneDetectionEnabled = enabled
        if (!enabled) aiCacheSnapshotRef.getAndUpdate { it.copy(scene = CacheEntry(SceneType.GENERAL, 0L)) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        pendingFrame.getAndSet(null)?.release()
        val executors = listOf(
            analysisExecutor,
            warmupExecutor,
            faceExecutor,
            objectExecutor,
            poseExecutor,
            segmentationExecutor,
            depthExecutor,
            sceneExecutor,
            enhancementExecutor
        )
        if (AiPipelineTeardownOffMainFlag.enabled) {
            // shutdownNow() is instant/non-blocking — signal every AI worker to stop on the main thread,
            // then move the blocking drain (KEEPING the 1500 ms per-executor budget) + tracker close +
            // bitmap-pool clear to the application-scoped daemon worker so onCleared can proceed to
            // camera.destroy()/vulkanRuntime.close() without waiting.
            executors.forEach { it.shutdownNow() }
            AiPipelineTeardownOffMainFlag.execute {
                executors.forEach { awaitTermination(it) }
                closeTrackersAndBitmapPool()
            }
        } else {
            executors.forEach { executor ->
                executor.shutdownNow()
                awaitTermination(executor)
            }
            closeTrackersAndBitmapPool()
        }
    }

    private fun closeTrackersAndBitmapPool() {
        safeClose("FaceTracker") { if (faceTrackerDelegate.isInitialized()) faceTracker.close() }
        safeClose("ObjectTracker") { if (objectTrackerDelegate.isInitialized()) objectTracker.close() }
        safeClose("PoseTracker") { if (poseTrackerDelegate.isInitialized()) poseTracker.close() }
        safeClose("MLKitSceneDetector") { if (mlKitSceneDetectorDelegate.isInitialized()) mlKitSceneDetector.close() }
        // SegmentationEngine, DepthEstimator don't require explicit closure
        bitmapPool.clear()
    }

    private fun postError(throwable: Throwable) {
        val signature = "${throwable::class.java.name}:${throwable.message}"
        val now = SystemClock.elapsedRealtime()
        val shouldDeliver =
            lastDeliveredErrorSignature.get() != signature ||
                now - lastDeliveredErrorElapsedMs.get() >= 1_500L
        if (!shouldDeliver) {
            return
        }
        lastDeliveredErrorSignature.set(signature)
        lastDeliveredErrorElapsedMs.set(now)
        Log.e(TAG, "AI pipeline error", throwable)
        mainHandler.post { listener?.onPipelineError(throwable) }
    }

    private fun postFrameResult(result: AICameraFrameResult) {
        pendingFrameResult.set(result)
        if (!frameResultDispatchScheduled.compareAndSet(false, true)) {
            return
        }
        mainHandler.post {
            try {
                while (true) {
                    val latestResult = pendingFrameResult.getAndSet(null) ?: break
                    listener?.onFrameProcessed(latestResult)
                }
            } finally {
                frameResultDispatchScheduled.set(false)
                if (pendingFrameResult.get() != null && frameResultDispatchScheduled.compareAndSet(false, true)) {
                    mainHandler.post {
                        try {
                            while (true) {
                                val latestResult = pendingFrameResult.getAndSet(null) ?: break
                                listener?.onFrameProcessed(latestResult)
                            }
                        } finally {
                            frameResultDispatchScheduled.set(false)
                        }
                    }
                }
            }
        }
    }

    private fun warmUpComponent(name: String, initializer: () -> Any) {
        if (closed.get()) return
        warmupStageActive.set(true)
        runCatching { initializer() }
            .onSuccess { Log.i(TAG, "$name warm-up ready") }
            .onFailure { Log.w(TAG, "$name warm-up failed", it) }
        warmupStageActive.set(false)
    }

    private fun heavyStageReadyForRecording(delegate: Lazy<*>): Boolean =
        !runtimeRecordingCritical || delegate.isInitialized()

    private fun recordingCachedFaces(
        cache: CacheEntry<List<TrackedFace>>,
        timestampMs: Long
    ): List<TrackedFace> =
        cache.takeIf { timestampMs - it.timestampMs <= FACE_MAX_STALE_MS }?.value.orEmpty()

    private fun recordingCachedObjects(
        cache: CacheEntry<List<TrackedObject>>,
        timestampMs: Long
    ): List<TrackedObject> =
        cache.takeIf { timestampMs - it.timestampMs <= OBJECT_MAX_STALE_MS }?.value.orEmpty()

    private fun recordingCachedPose(
        cache: CacheEntry<TrackedPose?>,
        timestampMs: Long
    ): TrackedPose? =
        cache.takeIf { timestampMs - it.timestampMs <= POSE_MAX_STALE_MS }?.value

    private fun recordingCachedSegmentation(
        cache: CacheEntry<SegmentationData?>,
        timestampMs: Long
    ): SegmentationData? =
        cache.takeIf { timestampMs - it.timestampMs <= SEGMENTATION_MAX_STALE_MS }?.value

    private fun enqueueFrame(frame: PendingFrame) {
        if (closed.get()) {
            frame.release()
            return
        }
        
        // High load detection: if previous frame is still pending, we're overloaded
        val oldFrame = pendingFrame.getAndSet(frame)
        if (oldFrame != null) {
            droppedFrameCounter.incrementAndGet()
            oldFrame.release()
        }
        
        // Update high load status every 30 frames
        val enqueuedCount = enqueuedFrameCounter.incrementAndGet()
        if (enqueuedCount % 30 == 0L) {
            val droppedCount = droppedFrameCounter.get()
            val processTimeMs = lastFrameProcessTimeMs.get()
            val adaptivePenalty = when {
                droppedCount > 15 || processTimeMs > 42L -> 3
                droppedCount > 8 || processTimeMs > 32L -> 2
                droppedCount > 4 || processTimeMs > 24L -> 1
                else -> 0
            }
            isHighLoad = adaptivePenalty > 0
            applyAdaptiveLoadOverrides(adaptivePenalty)
            droppedFrameCounter.set(0)
        }
        
        try {
            if (analysisLoopRunning.compareAndSet(false, true)) {
                analysisExecutor.execute { drainFrameQueue() }
            }
        } catch (_: RejectedExecutionException) {
            pendingFrame.getAndSet(null)?.release()
            analysisLoopRunning.set(false)
        }
    }

    private fun drainFrameQueue() {
        try {
            while (!closed.get()) {
                val frame = pendingFrame.getAndSet(null) ?: break
                try {
                    when (frame) {
                        is BitmapPendingFrame -> processBitmapFrame(frame)
                        is MediaImagePendingFrame -> processMediaImageFrame(frame)
                    }
                } catch (throwable: Throwable) {
                    postError(throwable)
                } finally {
                    frame.release()
                }
            }
        } finally {
            analysisLoopRunning.set(false)
            if (!closed.get() && pendingFrame.get() != null && analysisLoopRunning.compareAndSet(false, true)) {
                try {
                    analysisExecutor.execute { drainFrameQueue() }
                } catch (_: RejectedExecutionException) {
                    analysisLoopRunning.set(false)
                    pendingFrame.getAndSet(null)?.release()
                }
            }
        }
    }

    private fun processBitmapFrame(frame: BitmapPendingFrame) {
        if (closed.get()) return
        val retainedBitmap = frame.retainedBitmap
        val bitmap = retainedBitmap.retain() ?: return
        try {
            val timestampMs = frame.timestampMs
            val rotationDegrees = frame.frameGeometry?.aiRotationDegrees ?: frame.rotationDegrees
            val frameGeometry = frame.frameGeometry
            
            // Early exit if all features disabled - skip processing entirely
            if (!faceTrackingEnabled && !poseTrackingEnabled &&
                !segmentationEnabled && !enhancementEnabled && !depthTrackingEnabled && !sceneDetectionEnabled) {
                val emptyResult = AICameraFrameResult(
                    timestampMs = timestampMs,
                    faces = emptyList(),
                    pose = null,
                    segmentation = null,
                    depthData = null,
                    sceneType = SceneType.GENERAL,
                    focusPoint = null,
                    primarySubject = null,
                    subjectMask = null,
                    hdrSuggested = false,
                    lowLightSuggested = false,
                    enhancementResult = null,
                    frameGeometry = frameGeometry
                )
                postFrameResult(emptyResult)
                return
            }
            
            val startTime = System.currentTimeMillis()
            val frameIndex = frameCounter.incrementAndGet()
            val strictHighEnd = strictHighEndEnabled()
            val faceStageAllowed = faceTrackingEnabled && heavyStageReadyForRecording(faceTrackerDelegate)
            val poseStageAllowed = poseTrackingEnabled && heavyStageReadyForRecording(poseTrackerDelegate)
            val segmentationStageAllowed =
                segmentationEnabled && heavyStageReadyForRecording(segmentationEngineDelegate)
            val depthStageAllowed = depthTrackingEnabled && heavyStageReadyForRecording(depthEstimatorDelegate)
            val sceneStageAllowed =
                sceneDetectionEnabled && heavyStageReadyForRecording(mlKitSceneDetectorDelegate)
            val enhancementStageAllowed =
                enhancementEnabled && heavyStageReadyForRecording(enhancementEngineDelegate)
            val objectStageAllowed =
                (faceStageAllowed || poseStageAllowed) && heavyStageReadyForRecording(objectTrackerDelegate)
            val baseTimeoutMs = if (isHighLoad) {
                (analysisTimeoutMs * 0.60).toLong().coerceAtLeast(12L)
            } else {
                analysisTimeoutMs
            }
            val timeoutMs = if (strictHighEnd) {
                (baseTimeoutMs + 4L).coerceAtLeast(16L)
            } else {
                baseTimeoutMs
            }
            val facesNode = faceNode.schedule(
                future = if (faceStageAllowed) {
                    submitRetainedBitmapStage(faceExecutor, faceStageBusy, retainedBitmap) { faceTracker.track(it, timestampMs, rotationDegrees) }
                } else {
                    null
                },
                timeoutMs = timeoutMs,
                cancelOnTimeout = false
            )
            val objectScheduledNode = objectNode.schedule(
                future = if (objectStageAllowed && frameIndex % objectDetectionStride == 0L) {
                    submitRetainedBitmapStage(objectExecutor, objectStageBusy, retainedBitmap) {
                        runBlocking { objectTracker.track(it, rotationDegrees, timestampMs) }
                    }
                } else {
                    null
                },
                timeoutMs = timeoutMs,
                cancelOnTimeout = false
            )
            val poseScheduledNode = poseNode.schedule(
                future = if (poseStageAllowed && frameIndex % poseDetectionStride == 0L) {
                    submitRetainedBitmapStage(poseExecutor, poseStageBusy, retainedBitmap) { poseTracker.track(it, timestampMs, rotationDegrees) }
                } else {
                    null
                },
                timeoutMs = timeoutMs,
                cancelOnTimeout = false
            )
            val segmentationScheduledNode = segmentationNode.schedule(
                future = if (segmentationStageAllowed && frameIndex % segmentationStride == 0L) {
                    submitRetainedBitmapStage(segmentationExecutor, segmentationStageBusy, retainedBitmap) {
                        segmentationEngine.segment(it, timestampMs, rotationDegrees)
                    }
                } else {
                    null
                },
                timeoutMs = timeoutMs,
                cancelOnTimeout = false
            )
            val depthScheduledNode = depthNode.schedule(
                future = if (depthStageAllowed && frameIndex % depthEstimationStride == 0L) {
                    submitRetainedBitmapStage(depthExecutor, depthStageBusy, retainedBitmap) {
                        depthEstimator.estimate(it, rotationDegrees)
                    }
                } else {
                    null
                },
                timeoutMs = timeoutMs,
                cancelOnTimeout = false
            )
            val sceneScheduledNode = sceneNode.schedule(
                future = if (sceneStageAllowed && frameIndex % sceneDetectionStride == 0L) {
                    submitRetainedBitmapStage(sceneExecutor, sceneStageBusy, retainedBitmap) {
                        mlKitSceneDetector.detect(it, rotationDegrees)
                    }
                } else {
                    null
                },
                timeoutMs = timeoutMs,
                cancelOnTimeout = false
            )
            val enhancementScheduledNode = enhancementNode.schedule(
                future = if (enhancementStageAllowed && frameIndex % enhancementStride == 0L) {
                    submitRetainedBitmapStage(enhancementExecutor, enhancementStageBusy, retainedBitmap) {
                        runEnhancementStage(it, aiCacheSnapshotRef.get().scene.value)
                    }
                } else {
                    null
                },
                timeoutMs = if (strictHighEnd) 140L else 120L,
                cancelOnTimeout = false
            )

            val snapshot = aiCacheSnapshotRef.get()
            val facesCache = snapshot.faces
            val objectsCache = snapshot.objects
            val poseCache = snapshot.pose
            val segmentationCache = snapshot.segmentation
            val depthCache = snapshot.depth
            val sceneCache = snapshot.scene
            val enhancementCache = snapshot.enhancement
            val facesResolution = faceNode.resolve(facesNode, facesCache.value, facesCache.timestampMs, timestampMs, strictHighEnd)
            val objectsResolution = objectNode.resolve(objectScheduledNode, objectsCache.value, objectsCache.timestampMs, timestampMs, strictHighEnd)
            val poseResolution = poseNode.resolve(poseScheduledNode, poseCache.value, poseCache.timestampMs, timestampMs, strictHighEnd)
            val segmentationResolution = segmentationNode.resolve(segmentationScheduledNode, segmentationCache.value, segmentationCache.timestampMs, timestampMs, strictHighEnd)
            val depthResolution = depthNode.resolve(depthScheduledNode, depthCache.value, depthCache.timestampMs, timestampMs, strictHighEnd)
            val sceneResolution = sceneNode.resolve(sceneScheduledNode, sceneCache.value, sceneCache.timestampMs, timestampMs, strictHighEnd)
            val enhancementResolution = enhancementNode.resolve(enhancementScheduledNode, enhancementCache.value as? EnhancementResult, enhancementCache.timestampMs, timestampMs, strictHighEnd)
            val faces = facesResolution.value
            val objects = objectsResolution.value
            val pose = poseResolution.value
            val segmentation = segmentationResolution.value
            val depthData = depthResolution.value
            val rawDepthData = depthData
            AIFeatureQualityPolicy.qualifiedDepth(rawDepthData)?.let {
                cachedDepthRef.set(CacheEntry(it, timestampMs))
            }
            val sceneType = sceneResolution.value
            val enhancement = enhancementResolution.value
            
            // ════════════════════════════════════════════════════════════════════════
            // PHASE 5: PREDICTIVE SUBJECT TRACKING WITH FOCUS STABILITY ANALYSIS
            // ════════════════════════════════════════════════════════════════════════
            
            // Update predictive subject tracking
            val predictedSubjects = if (faceTrackingEnabled || poseTrackingEnabled || objects.isNotEmpty()) {
                predictiveSubjectTracker.updateTracking(
                    faces = faces,
                    pose = pose,
                    objects = objects,
                    timestampMs = timestampMs
                )
            } else {
                emptyList()
            }
            
            // CP-F1: focus-stability analysis moved into AIAutoFocusCoordinator, where the REAL
            // hardware-reported lens diopters are available (this site only had a hardcoded 0f and
            // discarded the report). See AIAutoFocusCoordinator anti-hunting damping.
            val subjectAwareness = if (faceTrackingEnabled || poseTrackingEnabled || objects.isNotEmpty()) {
                focusEngine.selectSubjectAwareness(
                    faces = faces,
                    pose = pose,
                    objects = objects,
                    segmentation = segmentation,
                    depthData = depthData,
                    sceneType = sceneType,
                    timestampMs = timestampMs
                )
            } else {
                null
            }
            
            val cachedScene = aiCacheSnapshotRef.get().scene.value
            val (hdrSuggested, lowLightSuggested) = when {
                subjectAwareness != null -> Pair(subjectAwareness.hdrSuggested, subjectAwareness.lowLightSuggested)
                cachedScene == SceneType.PORTRAIT_LOW_LIGHT || cachedScene == SceneType.NIGHT || cachedScene == SceneType.CONCERT -> Pair(false, true)
                cachedScene == SceneType.BACKLIGHT || cachedScene == SceneType.BRIGHT_EXTREME -> Pair(true, false)
                else -> Pair(false, false)
            }
            
            // Write to AI pipeline cache snapshot atomically
            aiCacheSnapshotRef.getAndUpdate { current ->
                var updated = current
                if (facesResolution.fresh) {
                    updated = updated.copy(faces = CacheEntry(faces, timestampMs, frameIndex))
                }
                if (objectsResolution.fresh) {
                    updated = updated.copy(objects = CacheEntry(objects, timestampMs, frameIndex))
                }
                if (poseResolution.fresh) {
                    updated = updated.copy(pose = CacheEntry(pose, timestampMs, frameIndex))
                }
                if (segmentationResolution.fresh) {
                    updated = updated.copy(segmentation = CacheEntry(segmentation, timestampMs, frameIndex))
                }
                if (depthResolution.fresh) {
                    updated = updated.copy(depth = CacheEntry(depthData, timestampMs, frameIndex))
                }
                if (sceneResolution.fresh) {
                    updated = updated.copy(scene = CacheEntry(sceneType, timestampMs, frameIndex))
                }
                updated
            }
            // Enhancement caching disabled - Phase 5 enhancement pending

            // s7-f01 / CP92: bounded-staleness metadata (flag OFF => 0L, bit-for-bit today).
            val (oldestStaleFrames, oldestStaleMs) = if (SensorPacedFrameSelectorFlag.enabled) {
                bundleStaleness(aiCacheSnapshotRef.get(), frameIndex, timestampMs)
            } else {
                0L to 0L
            }
            val result = AICameraFrameResult(
                timestampMs = timestampMs,
                faces = faces,
                pose = pose,
                segmentation = segmentation,
                depthData = depthData,
                sceneType = sceneType,
                focusPoint = subjectAwareness?.focusPoint,
                primarySubject = subjectAwareness?.primarySubject,
                subjectMask = subjectAwareness?.subjectMask,
                hdrSuggested = hdrSuggested,
                lowLightSuggested = lowLightSuggested,
                enhancementResult = enhancement,
                frameGeometry = frameGeometry,
                oldestStageAgeFrames = oldestStaleFrames,
                oldestStageAgeMs = oldestStaleMs
            )

            val processingTime = System.currentTimeMillis() - startTime
            Log.v(TAG, "Frame $timestampMs AI processing completed in ${processingTime}ms")
            lastFrameProcessTimeMs.set(processingTime)
            postFrameResult(result)
            
        } catch (e: Exception) {
            Log.e(TAG, "AI pipeline frame processing failed", e)
        } finally {
            retainedBitmap.release()
        }
    }

    private fun readMediaImageDescriptor(image: Image, maxDim: Int): Pair<Int, Int> {
        return analysisDimensions(image.width, image.height, maxDim)
    }

    private fun releaseMediaImageSource(image: Image, release: (() -> Unit)?) {
        release?.invoke()
    }

    private fun processMediaImageFrame(frame: MediaImagePendingFrame) {
        if (closed.get()) return
        
        // Early exit if all features disabled - skip processing entirely
        if (!faceTrackingEnabled && !poseTrackingEnabled &&
            !segmentationEnabled && !enhancementEnabled && !depthTrackingEnabled && !sceneDetectionEnabled) {
            val emptyResult = AICameraFrameResult(
                timestampMs = frame.timestampMs,
                faces = emptyList(),
                pose = null,
                segmentation = null,
                depthData = null,
                sceneType = SceneType.GENERAL,
                focusPoint = null,
                primarySubject = null,
                subjectMask = null,
                hdrSuggested = false,
                lowLightSuggested = false,
                enhancementResult = null,
                frameGeometry = frame.frameGeometry
            )
            postFrameResult(emptyResult)
            return
        }
        
        val startTime = System.currentTimeMillis()
        val frameIndex = frameCounter.incrementAndGet()
        val strictHighEnd = strictHighEndEnabled()
        val baseTimeoutMs = if (isHighLoad) {
            (analysisTimeoutMs * 0.60).toLong()
        } else {
            analysisTimeoutMs
        }
        val timeoutMs = if (strictHighEnd) {
            (baseTimeoutMs + 4L).coerceAtLeast(16L)
        } else {
            baseTimeoutMs.coerceAtLeast(12L)
        }
        val faceTimeoutMs = timeoutMs
        val poseTimeoutMs = (timeoutMs * 3L / 4L + if (strictHighEnd) 2L else 0L)
            .coerceAtLeast(if (strictHighEnd) 16L else 14L)
        val segmentationTimeoutMs = (timeoutMs / 2L + if (strictHighEnd) 2L else 0L)
            .coerceAtLeast(if (strictHighEnd) 12L else 10L)
        val depthTimeoutMs = (timeoutMs / 2L + if (strictHighEnd) 2L else 0L)
            .coerceAtLeast(if (strictHighEnd) 12L else 10L)
        val sceneTimeoutMs = (timeoutMs / 2L + if (strictHighEnd) 2L else 0L)
            .coerceAtLeast(if (strictHighEnd) 12L else 10L)
        val enhancementTimeoutMs = (timeoutMs + if (strictHighEnd) 18L else 12L)
            .coerceAtLeast(if (strictHighEnd) 30L else 24L)
        val image = frame.retainedImage.image
        val rotationDegrees = frame.frameGeometry?.aiRotationDegrees ?: frame.rotationDegrees
        val requestedMaxDimension = currentAnalysisMaxDimension(frame.previewMaxDimension)
        val (analysisWidth, analysisHeight) = analysisDimensions(image.width, image.height, requestedMaxDimension)
        val faceStageAllowed = faceTrackingEnabled && heavyStageReadyForRecording(faceTrackerDelegate)
        val poseStageAllowed = poseTrackingEnabled && heavyStageReadyForRecording(poseTrackerDelegate)
        val segmentationStageAllowed =
            segmentationEnabled && heavyStageReadyForRecording(segmentationEngineDelegate)
        val depthStageAllowed = depthTrackingEnabled && heavyStageReadyForRecording(depthEstimatorDelegate)
        val sceneStageAllowed =
            sceneDetectionEnabled && heavyStageReadyForRecording(mlKitSceneDetectorDelegate)
        val enhancementStageAllowed =
            enhancementEnabled && heavyStageReadyForRecording(enhancementEngineDelegate)
        val objectTrackingRequested = faceStageAllowed || poseStageAllowed
        val objectStageAllowed =
            objectTrackingRequested && heavyStageReadyForRecording(objectTrackerDelegate)
        val needsSharedBitmap =
            frame.previewTap != null ||
                objectTrackingRequested ||
                poseStageAllowed ||
                segmentationStageAllowed ||
                enhancementStageAllowed ||
                sceneStageAllowed
        var retainedBitmap: RetainedBitmap? = null
        var depthSnapshot: Yuv420ImageTools.Snapshot? = null
        var segmentationLumaGuide: SegmentationEngine.LumaGuide? = null
        var facesScheduledNode: ScheduledNode<List<TrackedFace>>? = null
        var objectScheduledNode: ScheduledNode<List<TrackedObject>>? = null
        var poseScheduledNode: ScheduledNode<TrackedPose?>? = null
        var segmentationScheduledNode: ScheduledNode<SegmentationData?>? = null
        var depthScheduledNode: ScheduledNode<DepthData?>? = null
        var sceneScheduledNode: ScheduledNode<SceneType>? = null
        var enhancementScheduledNode: ScheduledNode<EnhancementResult?>? = null

        try {
            if (needsSharedBitmap) {
                val analysisBitmap = bitmapPool.acquire(analysisWidth, analysisHeight)
                
                var zeroCopySuccess = false
                val bridge = zeroCopyBridge
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && bridge != null) {
                    val hwBuffer = image.hardwareBuffer
                    if (hwBuffer != null) {
                        try {
                            if (bridge.bindFrame(hwBuffer, analysisWidth, analysisHeight, image.format)) {
                                val buffer = ByteBuffer.allocateDirect(analysisBitmap.byteCount)
                                if (bridge.readTensorData(buffer)) {
                                    buffer.rewind()
                                    analysisBitmap.copyPixelsFromBuffer(buffer)
                                    zeroCopySuccess = true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "ZeroCopy binding failed", e)
                        } finally {
                            hwBuffer.close()
                        }
                    }
                }
                
                if (!zeroCopySuccess) {
                    copyMediaImageToBitmap(image, analysisBitmap, analysisWidth, analysisHeight)
                }
                retainedBitmap = RetainedBitmap(analysisBitmap) { releasedBitmap ->
                    bitmapPool.release(releasedBitmap)
                }
                frame.previewTap?.let { callback ->
                    callback(createPreviewBitmap(analysisBitmap, frame.previewMaxDimension))
                }
            }
            if (frameIndex % depthEstimationStride == 0L && depthStageAllowed && Yuv420ImageTools.isSupported(image)) {
                depthSnapshot = runCatching { Yuv420ImageTools.snapshot(image) }
                    .getOrElse { throwable ->
                        Log.w(TAG, "Depth snapshot failed", throwable)
                        null
                    }
            }
            if (segmentationStageAllowed && Yuv420ImageTools.isSupported(image)) {
                val segmentationSnapshot = depthSnapshot ?: runCatching { Yuv420ImageTools.snapshot(image) }
                    .getOrElse { throwable ->
                        Log.w(TAG, "Segmentation luma snapshot failed", throwable)
                        null
                    }
                segmentationLumaGuide = segmentationSnapshot?.let { snapshot ->
                    SegmentationEngine.extractSnapshotLumaGuide(snapshot, requestedMaxDimension)
                }
            }

            val snapshot = aiCacheSnapshotRef.get()
            val facesCache = snapshot.faces
            val objectsCache = snapshot.objects
            val poseCache = snapshot.pose
            val segmentationCache = snapshot.segmentation
            val cachedFaces = facesCache
                .takeIf { frame.timestampMs - it.timestampMs <= FACE_MAX_STALE_MS }
                ?.value
                .orEmpty()
            val cachedObjects = objectsCache
                .takeIf { frame.timestampMs - it.timestampMs <= OBJECT_MAX_STALE_MS }
                ?.value
                .orEmpty()
            val cachedPose = poseCache
                .takeIf { frame.timestampMs - it.timestampMs <= POSE_MAX_STALE_MS }
                ?.value
            val cachedSegmentation = segmentationCache
                .takeIf { frame.timestampMs - it.timestampMs <= SEGMENTATION_MAX_STALE_MS }
                ?.value

            // MediaPipe task runners are not re-entrant. Bitmap-frame analysis can
            // still have an in-flight tracker future, so media-image analysis must
            // acquire the same stage gate before touching the shared tracker.
            val facePoseMediaPipeJointTracking = faceTrackingEnabled && poseTrackingEnabled
            val faces = if (faceTrackingEnabled) {
                if (faceStageAllowed) {
                    runExclusiveTrackerStage(faceStageBusy, "Face tracking") {
                        trackFaceMediaImageFrame(frame, image, rotationDegrees)
                    } ?: cachedFaces
                } else {
                    recordingCachedFaces(facesCache, frame.timestampMs)
                }
            } else {
                null
            }

            objectScheduledNode = objectNode.schedule(
                future = if (objectStageAllowed && frameIndex % objectDetectionStride == 0L) {
                    retainedBitmap?.let { retained ->
                        submitRetainedBitmapStage(objectExecutor, objectStageBusy, retained) {
                            runBlocking { objectTracker.track(it, rotationDegrees, frame.timestampMs) }
                        }
                    }
                } else {
                    null
                },
                timeoutMs = faceTimeoutMs,
                cancelOnTimeout = false
            )
            val objectsResolution = objectNode.resolve(
                objectScheduledNode,
                if (objectStageAllowed) cachedObjects else recordingCachedObjects(objectsCache, frame.timestampMs),
                objectsCache.timestampMs,
                frame.timestampMs,
                strictHighEnd
            )
            val objects = objectsResolution.value

            val pose = if (poseTrackingEnabled) {
                if (poseStageAllowed && frameIndex % poseDetectionStride == 0L) {
                    runExclusiveTrackerStage(poseStageBusy, "Pose tracking") {
                        if (facePoseMediaPipeJointTracking && faceStageAllowed && poseStageAllowed) {
                            retainedBitmap?.bitmap?.let { bitmap ->
                                poseTracker.track(bitmap, frame.timestampMs, rotationDegrees)
                            }
                        } else {
                            trackPoseMediaImageFrame(frame, image, rotationDegrees)
                        }
                    } ?: cachedPose
                } else {
                    recordingCachedPose(poseCache, frame.timestampMs)
                }
            } else {
                null
            }

            val segmentation = if (segmentationEnabled) {
                if (segmentationStageAllowed && frameIndex % segmentationStride == 0L && !faceStageAllowed && !poseStageAllowed) {
                    runExclusiveTrackerStage(segmentationStageBusy, "Segmentation") {
                        trackSegmentationMediaImageFrame(frame, image, rotationDegrees, segmentationLumaGuide)
                    } ?: cachedSegmentation
                } else if (segmentationStageAllowed && frameIndex % segmentationStride == 0L) {
                    segmentationScheduledNode = segmentationNode.schedule(
                        future = retainedBitmap?.let { retained ->
                            submitRetainedBitmapStage(segmentationExecutor, segmentationStageBusy, retained) {
                                segmentationEngine.segment(
                                    it,
                                    frame.timestampMs,
                                    rotationDegrees,
                                    segmentationLumaGuide?.values,
                                    segmentationLumaGuide?.width ?: 0,
                                    segmentationLumaGuide?.height ?: 0
                                )
                            }
                        },
                        timeoutMs = segmentationTimeoutMs,
                        cancelOnTimeout = false
                    )
                    val segmentationResolution = segmentationNode.resolve(
                        segmentationScheduledNode,
                        cachedSegmentation,
                        segmentationCache.timestampMs,
                        frame.timestampMs,
                        strictHighEnd
                    )
                    if (segmentationResolution.fresh) {
                        aiCacheSnapshotRef.getAndUpdate { it.copy(segmentation = CacheEntry(segmentationResolution.value, frame.timestampMs)) }
                    }
                    segmentationResolution.value
                } else {
                    recordingCachedSegmentation(segmentationCache, frame.timestampMs)
                }
            } else {
                cachedSegmentation
            }

            sceneScheduledNode = sceneNode.schedule(
                future = if (sceneStageAllowed && frameIndex % sceneDetectionStride == 0L) {
                    retainedBitmap?.let { retained ->
                        submitRetainedBitmapStage(sceneExecutor, sceneStageBusy, retained) {
                            mlKitSceneDetector.detect(it, rotationDegrees)
                        }
                    } ?: submitMediaImageStage(sceneExecutor, sceneStageBusy, image) {
                        mlKitSceneDetector.detect(it, rotationDegrees)
                    }
                } else {
                    null
                },
                timeoutMs = sceneTimeoutMs,
                cancelOnTimeout = false
            )

            enhancementScheduledNode = enhancementNode.schedule(
                future = if (enhancementStageAllowed && frameIndex % enhancementStride == 0L) {
                    retainedBitmap?.let { retained ->
                        submitRetainedBitmapStage(enhancementExecutor, enhancementStageBusy, retained) {
                            runEnhancementStage(it, aiCacheSnapshotRef.get().scene.value)
                        }
                    }
                } else {
                    null
                },
                timeoutMs = enhancementTimeoutMs,
                cancelOnTimeout = false
            )

            val sceneCache = aiCacheSnapshotRef.get().scene
            val sceneResolution = sceneNode.resolve(
                sceneScheduledNode,
                sceneCache.value,
                sceneCache.timestampMs,
                frame.timestampMs,
                strictHighEnd
            )
            val sceneType = sceneResolution.value
            if (sceneResolution.fresh) {
                aiCacheSnapshotRef.getAndUpdate { it.copy(scene = CacheEntry(sceneType, frame.timestampMs)) }
            }

            val enhancementCache = aiCacheSnapshotRef.get().enhancement
            val enhancementResolution = enhancementNode.resolve(
                enhancementScheduledNode,
                enhancementCache.value as? EnhancementResult,
                enhancementCache.timestampMs,
                frame.timestampMs,
                strictHighEnd
            )
            val enhancement = enhancementResolution.value
            val depthCache = aiCacheSnapshotRef.get().depth
            val freshDepthData = depthSnapshot?.let { snapshot ->
                runCatching { depthEstimator.estimate(snapshot, rotationDegrees) }
                    .getOrElse { throwable ->
                        Log.w(TAG, "Depth estimation from snapshot failed", throwable)
                        null
                    }
            }
            if (freshDepthData != null) {
                aiCacheSnapshotRef.getAndUpdate { it.copy(depth = CacheEntry(freshDepthData, frame.timestampMs)) }
            }
            val depthData = freshDepthData
                ?: depthCache.takeIf { frame.timestampMs - it.timestampMs <= DEPTH_MAX_STALE_MS }?.value

            aiCacheSnapshotRef.getAndUpdate { current ->
                var updated = current
                if (faceTrackingEnabled) {
                    updated = updated.copy(faces = CacheEntry(faces ?: emptyList(), frame.timestampMs, frameIndex))
                }
                if (objectsResolution.fresh) {
                    updated = updated.copy(objects = CacheEntry(objects, frame.timestampMs, frameIndex))
                }
                if (poseTrackingEnabled) {
                    updated = updated.copy(pose = CacheEntry(pose, frame.timestampMs, frameIndex))
                }
                updated
            }

            if (faceTrackingEnabled || poseTrackingEnabled || objects.isNotEmpty()) {
                predictiveSubjectTracker.updateTracking(
                    faces = faces ?: emptyList(),
                    pose = pose,
                    objects = objects,
                    timestampMs = frame.timestampMs
                )
            }

            val subjectAwareness = if (faceTrackingEnabled || poseTrackingEnabled || objects.isNotEmpty()) {
                try {
                    focusEngine.selectSubjectAwareness(
                        faces = faces ?: emptyList(),
                        pose = pose,
                        objects = objects,
                        segmentation = segmentation,
                        depthData = depthData,
                        sceneType = sceneType,
                        timestampMs = frame.timestampMs
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Subject awareness selection failed", e)
                    null
                }
            } else {
                null
            }

            // s7-f01 / CP92: bounded-staleness metadata (flag OFF => 0L, bit-for-bit today).
            val (oldestStaleFrames, oldestStaleMs) = if (SensorPacedFrameSelectorFlag.enabled) {
                bundleStaleness(aiCacheSnapshotRef.get(), frameIndex, frame.timestampMs)
            } else {
                0L to 0L
            }
            val result = AICameraFrameResult(
                timestampMs = frame.timestampMs,
                faces = faces ?: emptyList(),
                pose = pose,
                segmentation = segmentation,
                depthData = depthData,
                sceneType = sceneType,
                focusPoint = subjectAwareness?.focusPoint,
                primarySubject = subjectAwareness?.primarySubject,
                subjectMask = subjectAwareness?.subjectMask,
                hdrSuggested = subjectAwareness?.hdrSuggested == true,
                lowLightSuggested = subjectAwareness?.lowLightSuggested == true,
                enhancementResult = enhancement,
                frameGeometry = frame.frameGeometry,
                oldestStageAgeFrames = oldestStaleFrames,
                oldestStageAgeMs = oldestStaleMs
            )
            
            val processingTime = System.currentTimeMillis() - startTime
            Log.v(TAG, "Frame ${frame.timestampMs} AI processing completed in ${processingTime}ms")
            lastFrameProcessTimeMs.set(processingTime)
            postFrameResult(result)
            
        } catch (e: Exception) {
            Log.e(TAG, "AI pipeline frame processing failed", e)
        } finally {
            retainedBitmap?.release()
        }
    }

    private fun trackFaceMediaImageFrame(
        frame: MediaImagePendingFrame,
        image: Image,
        rotationDegrees: Int
    ): List<TrackedFace> =
        faceTracker.track(image, frame.timestampMs, rotationDegrees)

    private fun trackPoseMediaImageFrame(
        frame: MediaImagePendingFrame,
        image: Image,
        rotationDegrees: Int
    ): TrackedPose? =
        poseTracker.track(image, frame.timestampMs, rotationDegrees)

    private fun trackSegmentationMediaImageFrame(
        frame: MediaImagePendingFrame,
        image: Image,
        rotationDegrees: Int,
        segmentationLumaGuide: SegmentationEngine.LumaGuide?
    ): SegmentationData? =
        segmentationEngine.segment(image, frame.timestampMs, rotationDegrees,
            segmentationLumaGuide?.values,
            segmentationLumaGuide?.width ?: 0,
            segmentationLumaGuide?.height ?: 0
        )

    private fun <T> submitBitmapStage(
        executor: ExecutorService,
        busyFlag: AtomicBoolean,
        bitmap: Bitmap?,
        task: (Bitmap) -> T
    ): Future<T>? {
        if (!busyFlag.compareAndSet(false, true)) return null
        if (bitmap == null) {
            busyFlag.set(false)
            return null
        }
        return try {
            executor.submit(
                Callable {
                    try {
                        task(bitmap)
                    } finally {
                        busyFlag.set(false)
                    }
                }
            )
        } catch (throwable: Throwable) {
            busyFlag.set(false)
            throw throwable
        }
    }

    private fun <T> runExclusiveTrackerStage(
        busyFlag: AtomicBoolean,
        stageName: String,
        task: () -> T?
    ): T? {
        if (!busyFlag.compareAndSet(false, true)) return null
        return try {
            task()
        } catch (throwable: Throwable) {
            Log.w(TAG, "$stageName failed", throwable)
            null
        } finally {
            busyFlag.set(false)
        }
    }

    private fun awaitInFlightImageStages(
        faceFuture: Future<*>?,
        poseFuture: Future<*>?,
        segmentationFuture: Future<*>?,
        depthFuture: Future<*>?,
        sceneFuture: Future<*>?,
        enhancementFuture: Future<*>?,
        graceTimeoutMs: Long
    ) {
        val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceTimeoutMs.coerceAtLeast(0L))
        waitForFutureIfInFlight(faceFuture, deadlineNs)
        waitForFutureIfInFlight(poseFuture, deadlineNs)
        waitForFutureIfInFlight(segmentationFuture, deadlineNs)
        waitForFutureIfInFlight(depthFuture, deadlineNs)
        waitForFutureIfInFlight(sceneFuture, deadlineNs)
        waitForFutureIfInFlight(enhancementFuture, deadlineNs)
    }

    private fun waitForFutureIfInFlight(future: Future<*>?, deadlineNs: Long) {
        future ?: return
        if (future.isDone || future.isCancelled) return
        val remainingNs = deadlineNs - System.nanoTime()
        if (remainingNs <= 0L) return
        runCatching {
            future.get(remainingNs, TimeUnit.NANOSECONDS)
        }
    }

    private fun <T> submitStage(
        executor: ExecutorService,
        busyFlag: AtomicBoolean,
        task: () -> T
    ): Future<T>? {
        if (!busyFlag.compareAndSet(false, true)) return null
        return try {
            executor.submit(
                Callable {
                    try {
                        task()
                    } finally {
                        busyFlag.set(false)
                    }
                }
            )
        } catch (throwable: Throwable) {
            busyFlag.set(false)
            throw throwable
        }
    }

    private fun <T> submitMediaImageStage(
        executor: ExecutorService,
        busyFlag: AtomicBoolean,
        image: Image?,
        task: (Image) -> T
    ): Future<T>? {
        if (!busyFlag.compareAndSet(false, true)) return null
        if (image == null) {
            busyFlag.set(false)
            return null
        }
        return try {
            executor.submit(
                Callable {
                    try {
                        task(image)
                    } finally {
                        busyFlag.set(false)
                    }
                }
            )
        } catch (throwable: Throwable) {
            busyFlag.set(false)
            throw throwable
        }
    }

    private fun runEnhancementStage(
        bitmap: Bitmap,
        sceneType: SceneType
    ): EnhancementResult? {
        return try {
            val (processedBitmap, metrics) = enhancementEngine.enhance(
                bitmap = bitmap,
                sceneType = sceneType
            )
            val stableBitmap = if (processedBitmap === bitmap) {
                processedBitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                processedBitmap
            }
            val algorithms = mutableListOf<String>()
            if (metrics.denoisingApplied) algorithms += "joint_bilateral_temporal_denoise"
            if (metrics.sharpeningApplied) algorithms += "scharr_edge_directed_sharpen"
            if (metrics.microContrastApplied) algorithms += "csf_oklab_micro_contrast"
            EnhancementResult(
                processedBitmap = stableBitmap,
                estimatedNoiseLevel = metrics.estimatedNoiseLevel,
                hdrBoost = 0f,
                highlightRecovery = 0f,
                shadowRecovery = metrics.estimatedNoiseLevel.coerceIn(0f, 1f) * 0.22f,
                sharpnessScore = metrics.enhancedPerceptualSharpness,
                colorVibrance = 0f,
                processingTimeMs = metrics.processingTimeMs,
                algorithmsApplied = algorithms
            )
        } catch (throwable: Throwable) {
            Log.w(TAG, "Enhancement stage failed", throwable)
            null
        }
    }

    private fun <T> submitRetainedBitmapStage(
        executor: ExecutorService,
        busyFlag: AtomicBoolean,
        retainedBitmap: RetainedBitmap,
        task: (Bitmap) -> T
    ): Future<T>? {
        if (!busyFlag.compareAndSet(false, true)) return null
        val bitmap = retainedBitmap.retain()
        if (bitmap == null) {
            busyFlag.set(false)
            return null
        }
        return try {
            executor.submit(
                Callable {
                    try {
                        task(bitmap)
                    } finally {
                        retainedBitmap.release()
                        busyFlag.set(false)
                    }
                }
            )
        } catch (throwable: Throwable) {
            retainedBitmap.release()
            busyFlag.set(false)
            throw throwable
        }
    }

    private fun applyAdaptiveLoadOverrides(loadPenalty: Int) {
        val profile = AICameraAdaptiveLoadPolicy.resolve(
            base = AICameraAdaptiveLoadBaseProfile(
                poseDetectionStride = runtimeBasePoseDetectionStride,
                segmentationStride = runtimeBaseSegmentationStride,
                depthEstimationStride = runtimeBaseDepthEstimationStride,
                enhancementStride = runtimeBaseEnhancementStride,
                sceneDetectionStride = runtimeBaseSceneDetectionStride,
                analysisTimeoutMs = runtimeBaseAnalysisTimeoutMs,
                analysisMaxDimension = runtimeBaseAnalysisMaxDimension
            ),
            loadPenalty = loadPenalty,
            orderedDrop = SensorPacedFrameSelectorFlag.enabled
        )
        objectDetectionStride = (runtimeBaseObjectDetectionStride + loadPenalty).coerceAtLeast(1)
        poseDetectionStride = profile.poseDetectionStride
        segmentationStride = profile.segmentationStride
        depthEstimationStride = profile.depthEstimationStride
        enhancementStride = profile.enhancementStride
        sceneDetectionStride = profile.sceneDetectionStride
        analysisTimeoutMs = profile.analysisTimeoutMs
        runtimeAnalysisMaxDimension = profile.analysisMaxDimension
    }

    /**
     * s7-f01 / CP92 — compute the fused bundle's bounded-staleness (oldest contributing stage age
     * in frames + ms) from the per-stage refresh metadata already tracked in [AICacheSnapshot].
     * Only called on the CP92 flag-ON path; stages never refreshed (timestamp 0 / frameIndex < 0)
     * are excluded so they don't inflate the age.
     */
    private fun bundleStaleness(
        snapshot: AICacheSnapshot,
        currentFrameIndex: Long,
        currentTimestampMs: Long
    ): Pair<Long, Long> {
        val timestamps = longArrayOf(
            snapshot.faces.timestampMs, snapshot.pose.timestampMs, snapshot.segmentation.timestampMs,
            snapshot.depth.timestampMs, snapshot.scene.timestampMs
        ).filter { it > 0L }.toLongArray()
        val frameIndices = longArrayOf(
            snapshot.faces.frameIndex, snapshot.pose.frameIndex, snapshot.segmentation.frameIndex,
            snapshot.depth.frameIndex, snapshot.scene.frameIndex
        ).filter { it >= 0L }.toLongArray()
        val ageMs = AIStageStalenessPolicy.oldestStageAgeMs(currentTimestampMs, timestamps)
        val ageFrames = AIStageStalenessPolicy.oldestStageAgeFrames(currentFrameIndex, frameIndices)
        return ageFrames to ageMs
    }

    private fun analysisDimensions(width: Int, height: Int, maxDimension: Int = 640): Pair<Int, Int> {
        val boundedMaxDimension = maxDimension.coerceAtLeast(1)
        val scale = if (maxOf(width, height) > maxDimension) {
            boundedMaxDimension.toFloat() / maxOf(width, height).toFloat()
        } else {
            1f
        }
        return Pair(
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1)
        )
    }

    private fun currentAnalysisMaxDimension(requestedMaxDimension: Int): Int =
        if (runtimeAnalysisMaxDimension >= runtimeBaseAnalysisMaxDimension) {
            maxOf(requestedMaxDimension, runtimeAnalysisMaxDimension).coerceAtLeast(1)
        } else {
            minOf(requestedMaxDimension, runtimeAnalysisMaxDimension).coerceAtLeast(1)
        }

    private fun createPreviewBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val boundedMaxDimension = maxDimension.coerceAtLeast(1)
        return if (maxOf(bitmap.width, bitmap.height) > boundedMaxDimension) {
            val scale = boundedMaxDimension.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
            bitmap.scale(
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    private fun prepareBitmap(bitmap: Bitmap, recycleSource: Boolean): Bitmap {
        val targetLongEdge = currentAnalysisMaxDimension(config.maxAnalysisDimension)
        val sourceLongEdge = maxOf(bitmap.width, bitmap.height)
        if (sourceLongEdge <= targetLongEdge) {
            return if (recycleSource) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        val scale = targetLongEdge.toFloat() / sourceLongEdge.toFloat()
        val scaled = bitmap.scale(
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (recycleSource && scaled !== bitmap && !bitmap.isRecycled) {
            bitmap.recycle()
        }
        return scaled
    }

    private fun awaitTermination(executor: ExecutorService) {
        try {
            if (!executor.awaitTermination(1500L, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private inline fun safeClose(name: String, block: () -> Unit) {
        runCatching(block)
            .onFailure { /* log ignored */ }
    }

    private fun copyRgbaImageToBitmap(imageProxy: ImageProxy, bitmap: Bitmap, targetWidth: Int, targetHeight: Int) {
        val plane = imageProxy.planes[0]
        val pixels = RgbaImageCopyPolicy.copyToArgb(
            source = plane.buffer,
            sourceWidth = imageProxy.width,
            sourceHeight = imageProxy.height,
            sourceRowStride = plane.rowStride,
            sourcePixelStride = plane.pixelStride,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        )
        bitmap.setPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
    }

    private fun copyRawYuvFrameToBitmap(frame: RawYuvFrame, bitmap: Bitmap, targetWidth: Int, targetHeight: Int) {
        val outPixels = bitmapPool.acquirePixels(targetWidth * targetHeight)
        val inputWidth = frame.width
        val inputHeight = frame.height
        val chromaWidth = ((inputWidth + 1) / 2).coerceAtLeast(1)
        val chromaHeight = ((inputHeight + 1) / 2).coerceAtLeast(1)
        val hasChroma =
            frame.uBytes.isNotEmpty() &&
                frame.vBytes.isNotEmpty() &&
                frame.uRowStride > 0 &&
                frame.vRowStride > 0 &&
                frame.uPixelStride > 0 &&
                frame.vPixelStride > 0

        for (targetY in 0 until targetHeight) {
            val srcY = ((targetY.toFloat() / targetHeight.toFloat()) * inputHeight)
                .toInt()
                .coerceIn(0, inputHeight - 1)
            val chromaY = (srcY / 2).coerceIn(0, chromaHeight - 1)
            for (targetX in 0 until targetWidth) {
                val srcX = ((targetX.toFloat() / targetWidth.toFloat()) * inputWidth)
                    .toInt()
                    .coerceIn(0, inputWidth - 1)
                val yIndex = srcY * frame.yRowStride + srcX * frame.yPixelStride
                val y = frame.yBytes.getOrNull(yIndex)?.toInt()?.and(0xFF) ?: 0
                val targetIndex = targetY * targetWidth + targetX
                if (!hasChroma) {
                    outPixels[targetIndex] = Yuv420ImageTools.packGrayArgb(y)
                    continue
                }
                val chromaX = (srcX / 2).coerceIn(0, chromaWidth - 1)
                val uIndex = chromaY * frame.uRowStride + chromaX * frame.uPixelStride
                val vIndex = chromaY * frame.vRowStride + chromaX * frame.vPixelStride
                val u = frame.uBytes.getOrNull(uIndex)?.toInt()?.and(0xFF) ?: 128
                val v = frame.vBytes.getOrNull(vIndex)?.toInt()?.and(0xFF) ?: 128
                outPixels[targetIndex] = Yuv420ImageTools.packArgb(y, u, v)
            }
        }
        bitmap.setPixels(outPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
    }

    private fun copyMediaImageToBitmap(image: Image, bitmap: Bitmap, targetWidth: Int, targetHeight: Int) {
        if (!Yuv420ImageTools.isSupported(image)) {
            throw IllegalArgumentException("Unsupported media image format ${image.format}")
        }
        val outPixels = bitmapPool.acquirePixels(targetWidth * targetHeight)
        if (Yuv420ImageTools.isP010(image)) {
            val snapshot = Yuv420ImageTools.snapshot(image)
            Yuv420ImageTools.fillArgbPixels(snapshot, targetWidth, targetHeight, outPixels)
        } else {
            Yuv420ImageTools.fillArgbPixels(image, targetWidth, targetHeight, outPixels)
        }
        bitmap.setPixels(outPixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
    }

    private class BitmapPool {
        private val bitmaps = mutableListOf<Bitmap>()
        // Per-thread scratch: acquireBuffer/acquirePixels are called from BOTH the camera-callback
        // thread and the analysis thread. A single shared instance per capacity let two threads write
        // the same array concurrently -> torn/corrupted pixels. ThreadLocal preserves reuse (no
        // per-frame allocation) while giving each thread its own backing buffer.
        private val buffers = ThreadLocal.withInitial { HashMap<Int, ByteBuffer>() }
        private val pixelBuffers = ThreadLocal.withInitial { HashMap<Int, IntArray>() }
        private val maxPoolSize = 16
        private val maxBufferSize = 60_000_000 // 60MB limit per buffer to allow 4K (37.7MB) frames

        @Synchronized
        fun acquire(width: Int, height: Int): Bitmap {
            for (index in bitmaps.indices.reversed()) {
                val candidate = bitmaps[index]
                if (candidate.isRecycled) {
                    bitmaps.removeAt(index)
                    continue
                }
                if (candidate.width == width && candidate.height == height) {
                    bitmaps.removeAt(index)
                    return candidate
                }
            }
            return createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        @Synchronized
        fun release(bitmap: Bitmap?) {
            if (bitmap == null || bitmap.isRecycled) return
            val bitmapSize = bitmap.width * bitmap.height * 4
            if (bitmapSize > maxBufferSize) {
                bitmap.recycle()
                return
            }
            if (bitmaps.size >= maxPoolSize) {
                val oldest = if (bitmaps.isNotEmpty()) bitmaps.removeAt(0) else null
                if (oldest != null && !oldest.isRecycled) {
                    oldest.recycle()
                }
            }
            if (!bitmap.isRecycled) {
                bitmaps.add(bitmap)
            }
        }

        // No @Synchronized needed: ThreadLocal scratch is inherently per-thread (also relieves the
        // lock contention this pool previously had between the callback and analysis threads).
        fun acquireBuffer(minCapacity: Int): ByteBuffer {
            if (minCapacity > maxBufferSize) {
                return ByteBuffer.allocateDirect(minCapacity)
            }
            val threadBuffers = checkNotNull(buffers.get())
            return threadBuffers.getOrPut(minCapacity) {
                ByteBuffer.allocateDirect(minCapacity)
            }
        }

        fun acquirePixels(minCapacity: Int): IntArray {
            if (minCapacity > maxBufferSize / 4) {
                return IntArray(minCapacity)
            }
            val threadPixelBuffers = checkNotNull(pixelBuffers.get())
            return threadPixelBuffers.getOrPut(minCapacity) {
                IntArray(minCapacity)
            }
        }

        @Synchronized
        fun clear() {
            bitmaps.forEach { if (!it.isRecycled) it.recycle() }
            bitmaps.clear()
            checkNotNull(buffers.get()).clear()
            checkNotNull(pixelBuffers.get()).clear()
        }
    }
}
