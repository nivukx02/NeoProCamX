package com.neoproc.neoproccamx.ai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.hardware.HardwareBuffer
import com.neoproc.neoproccamx.infrastructure.cache.CacheManager
import com.neoproc.neoproccamx.infrastructure.cache.MemoryCache
import com.neoproc.neoproccamx.infrastructure.config.ConfigurationManager
import com.neoproc.neoproccamx.infrastructure.error.ErrorHandler
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.infrastructure.performance.PerformanceMonitor
import com.neoproc.neoproccamx.infrastructure.telemetry.TelemetryCollector
import com.neoproc.neoproccamx.infrastructure.threading.ThreadPoolManager
import com.neoproc.neoproccamx.infrastructure.validation.InputValidator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * AI NEURAL ENGINE — ENTERPRISE-GRADE NEURAL INFERENCE ORCHESTRATION SYSTEM
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * High-performance neural network inference engine providing:
 *   - Multi-model concurrent execution with load balancing
 *   - Hardware acceleration (GPU, NNAPI, DSP) with automatic fallback
 *   - Intelligent batching for throughput optimization
 *   - Model versioning and hot-swapping support
 *   - Memory-mapped model loading for large networks
 *   - Comprehensive error recovery and retry mechanisms
 *   - Performance telemetry with automatic profiling
 *   - Thread-safe model inference with queue management
 *
 * Thread Safety: All inference operations are thread-safe via model-specific locks.
 * Performance: Lock-free model dispatch with batched inference.
 *
 * @author NeoProCamX AI Team
 * @version 10.0.0-ENTERPRISE
 * @since 1.0.0
 */
class AINeuralEngine private constructor(context: Context) {

    private val context: Context = context.applicationContext

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AINeuralEngine? = null

        fun getInstance(context: Context): AINeuralEngine {
            return instance ?: synchronized(this) {
                instance ?: AINeuralEngine(context.applicationContext).also {
                    instance = it
                    it.initialize()
                }
            }
        }

        fun getInstance(): AINeuralEngine {
            return instance ?: throw IllegalStateException("AINeuralEngine not initialized")
        }

        // ═════════════════════════════════════════════════════════════════════════════════════════════
        // NEURAL ENGINE CONSTANTS
        // ═════════════════════════════════════════════════════════════════════════════════════════════

        /** Default inference timeout in milliseconds. */
        private const val DEFAULT_INFERENCE_TIMEOUT_MS = 5000L

        /** Maximum concurrent model executions. */
        private const val MAX_CONCURRENT_EXECUTIONS = 4

        /** Maximum batch size for batched inference. */
        private const val MAX_BATCH_SIZE = 8

        /** Model cache size in MB. */
        private const val MODEL_CACHE_SIZE_MB = 256

        /** Input/output tensor cache size. */
        private const val TENSOR_CACHE_SIZE = 16

        /** Number of interpreter threads. */
        private const val INTERPRETER_THREAD_COUNT = 4

        // ═════════════════════════════════════════════════════════════════════════════════════════════
        // MODEL ALIASES AND PHYSICAL ASSET REGISTRY
        // ═════════════════════════════════════════════════════════════════════════════════════════════

        const val MODEL_DEPTH_ESTIMATION = AIConstants.MODEL_ALIAS_DEPTH_ESTIMATION
        const val MODEL_FACE_DETECTION = AIConstants.MODEL_ALIAS_FACE_DETECTION
        const val MODEL_SCENE_CLASSIFICATION = AIConstants.MODEL_ALIAS_SCENE_CLASSIFICATION
        const val MODEL_SUPER_RESOLUTION = AIConstants.MODEL_ALIAS_SUPER_RESOLUTION
        const val MODEL_DENOISING = AIConstants.MODEL_ALIAS_DENOISING
        const val MODEL_SEGMENTATION = AIConstants.MODEL_ALIAS_SEGMENTATION
        const val MODEL_POSE_ESTIMATION = AIConstants.MODEL_ALIAS_POSE_ESTIMATION
        const val MODEL_OBJECT_DETECTION = AIConstants.MODEL_ALIAS_OBJECT_DETECTION
        const val MODEL_ENHANCEMENT = AIConstants.MODEL_ALIAS_IMAGE_ENHANCEMENT
        const val MODEL_HDR_MERGE = AIConstants.MODEL_ALIAS_HDR_MERGE

        // REGISTRY HONESTY (CP98 / s7-f08): every alias below is classified so the registry can
        // never silently imply an unshipped capability. Keep in sync with
        // AINeuralEngineRegistryHonestyTest; never add an unclassified alias.
        //   live                  = loaded through THIS engine (runInference / runDenoiseTile).
        //   owned-by-<subsystem>  = packaged asset owned by a self-loading SDK/engine (this engine
        //                           refuses .task and never force-loads it).
        //   active-but-unconsumed = resolves to a packaged binary (reconciles ACTIVE) but NO
        //                           consumer loads it through this engine.
        //   dormant-no-binary     = no asset packaged and no consumer (reconciled UNAVAILABLE at init).
        internal val MODEL_ALIAS_REGISTRY = mapOf(
            "depth_estimation" to listOf("models/midas_v2_1_small.tflite", "models/depth_estimation.tflite", "depth_estimation.tflite"), // owned-by-DepthEstimator (self-loads MiDaS TFLite)
            "segmentation" to listOf("models/selfie_multiclass_256x256.tflite", "models/segmentation.tflite", "segmentation.tflite"), // owned-by-SegmentationEngine (MediaPipe ImageSegmenter)
            "denoising" to listOf("models/enhancement_denoise.tflite", "models/denoising.tflite", "denoising.tflite"), // live: NeuralDenoiseEngine via runDenoiseTile
            "object_detection" to listOf("models/efficientdet_lite0.tflite", "models/object_detection.tflite", "object_detection.tflite"), // live: ObjectTracker via runInference
            "face_detection" to listOf("models/face_landmarker.task", "models/face_detection.tflite", "face_detection.tflite"), // owned-by-FaceTracker (MediaPipe FaceLandmarker .task; engine refuses non-tflite)
            "pose_estimation" to listOf("models/pose_landmarker_full.task", "models/pose_estimation.tflite", "pose_estimation.tflite"), // owned-by-PoseTracker (MediaPipe PoseLandmarker .task; engine refuses non-tflite)
            "super_resolution" to listOf("models/super_resolution.tflite", "super_resolution.tflite"), // dormant-no-binary: no asset packaged, no consumer
            "image_enhancement" to listOf("models/enhancement_denoise.tflite", "models/image_enhancement.tflite", "image_enhancement.tflite"), // active-but-unconsumed: resolves to enhancement_denoise.tflite (shared with the denoising alias); NO consumer loads it through this engine; retained for config symmetry
            "scene_classification" to listOf("models/scene_classification.tflite", "scene_classification.tflite"), // dormant-no-binary: SceneDetector uses ML Kit ImageLabeling; no tflite packaged here
            "hdr_merge" to listOf("models/hdr_merge.tflite", "hdr_merge.tflite") // dormant-no-binary: no asset packaged, no consumer
        )

        @Volatile
        private var nativeTensorBridgeAvailable: Boolean = false

        init {
            try {
                System.loadLibrary("neoproc_vulkan")
                nativeTensorBridgeAvailable = true
            } catch (_: UnsatisfiedLinkError) {
                nativeTensorBridgeAvailable = false
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // NEURAL ENGINE CONFIGURATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    data class ModelConfiguration(
        val modelName: String,
        val modelFile: String,
        val inputShape: IntArray,
        val outputShape: IntArray,
        val useGpuDelegate: Boolean = true,
        val useNnApiDelegate: Boolean = false,
        val useQuantized: Boolean = false,
        val numThreads: Int = INTERPRETER_THREAD_COUNT,
        val allowFp16: Boolean = true,
        val inferenceTimeoutMs: Long = DEFAULT_INFERENCE_TIMEOUT_MS
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ModelConfiguration
            return modelName == other.modelName
        }

        override fun hashCode(): Int = modelName.hashCode()
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // HARDWARE ACCELERATION TYPE
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    enum class AccelerationType {
        CPU,           // CPU-only execution
        GPU,           // GPU delegate
        NNAPI,         // Neural Network API
        DSP,           // Hexagon DSP (via NNAPI)
        GPU_FLEX       // GPU with flexible delegate
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // MODEL WRAPPER CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private data class ModelWrapper(
        val configuration: ModelConfiguration,
        var interpreter: Interpreter? = null,
        var gpuDelegate: GpuDelegate? = null,
        var nnApiDelegate: NnApiDelegate? = null,
        val loadedAt: Long = System.currentTimeMillis(),
        val inferenceCount: AtomicLong = AtomicLong(0),
        val errorCount: AtomicLong = AtomicLong(0),
        val averageInferenceTime: AtomicLong = AtomicLong(0),
        val lastUsed: AtomicLong = AtomicLong(System.currentTimeMillis()),
        val lock: Any = Any()
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INFERENCE REQUEST CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    data class InferenceRequest(
        val modelName: String,
        val inputData: Any,
        val priority: Int = 5, // 1-10, lower is higher priority
        val timeoutMs: Long = DEFAULT_INFERENCE_TIMEOUT_MS,
        val correlationId: String = java.util.UUID.randomUUID().toString(),
        val metadata: Map<String, Any> = emptyMap()
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INFERENCE RESULT CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    sealed class InferenceResult<out T> {
        data class Success<T>(
            val data: T,
            val inferenceTimeMs: Long,
            val modelName: String,
            val correlationId: String
        ) : InferenceResult<T>()

        data class Failure(
            val error: Throwable,
            val modelName: String,
            val correlationId: String,
            val recoveryAttempted: Boolean = false
        ) : InferenceResult<Nothing>()

        data class Timeout(
            val modelName: String,
            val correlationId: String,
            val timeoutMs: Long
        ) : InferenceResult<Nothing>()

        fun isSuccess(): Boolean = this is Success
        fun isFailure(): Boolean = this is Failure
        fun isTimeout(): Boolean = this is Timeout
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // ENGINE STATE CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    sealed class EngineState {
        object Uninitialized : EngineState()
        object Initializing : EngineState()
        object Ready : EngineState()
        object Processing : EngineState()
        object Degraded : EngineState() // Some models unavailable
        object Error : EngineState()
        object ShuttingDown : EngineState()
        object Shutdown : EngineState()
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // ENGINE STATISTICS CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    data class EngineStatistics(
        val totalInferences: Long,
        val successfulInferences: Long,
        val failedInferences: Long,
        val timeoutInferences: Long,
        val averageInferenceTimeMs: Double,
        val activeModels: Int,
        val totalModels: Int,
        val memoryUsageBytes: Long,
        val currentState: EngineState,
        val modelStatistics: Map<String, ModelStatistics>
    )

    data class ModelStatistics(
        val modelName: String,
        val inferenceCount: Long,
        val errorCount: Long,
        val averageInferenceTimeMs: Double,
        val lastUsed: Long,
        val isLoaded: Boolean
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INTERNAL STATE
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private val engineScope = CoroutineScope(
        SupervisorJob() + ThreadPoolManager.getDispatcher("neural_engine")
    )

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Uninitialized)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val loadedModels = ConcurrentHashMap<String, ModelWrapper>()
    private val modelConfigurations = ConcurrentHashMap<String, ModelConfiguration>()
    private val modelStatusMap = ConcurrentHashMap<String, ModelStatus>()
    private val resolvedModelAssets = ConcurrentHashMap<String, String>()
    private val discoveredModelAssets = AtomicReference<Set<String>>(emptySet())
    private val bitmapTensorScratch = ConcurrentHashMap<String, ByteBuffer>()
    private val neuralDenoiseEngineRef = AtomicReference<NeuralDenoiseEngine?>()
    private val pendingRequests = CopyOnWriteArrayList<InferenceRequest>()

    private val totalInferences = AtomicLong(0)
    private val successfulInferences = AtomicLong(0)
    private val failedInferences = AtomicLong(0)
    private val timeoutInferences = AtomicLong(0)

    private val isOperational = AtomicBoolean(false)
    private val activeExecutions = AtomicInteger(0)

    private val tensorCache = MemoryCache<String, ByteBuffer>(
        MemoryCache.CacheConfiguration(
            maxSize = TENSOR_CACHE_SIZE,
            defaultTtlMs = 30000
        )
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private fun initialize() {
        if (!isOperational.compareAndSet(false, true)) {
            NeoLogger.w("AINeuralEngine", "Already initialized")
            return
        }

        NeoLogger.i("AINeuralEngine", "Initializing Neural Engine...")
        _engineState.value = EngineState.Initializing

        try {
            // Register default model configurations
            registerDefaultModelConfigurations()
            val availableAssets = discoverPhysicalModelAssets()
            val unavailableModelCount = reconcileModelAvailability(availableAssets)

            // Initialize thread pools
            ThreadPoolManager.getOrCreatePool(
                "neural_engine",
                ThreadPoolManager.PoolConfiguration(
                    name = "neural_engine",
                    corePoolSize = MAX_CONCURRENT_EXECUTIONS,
                    maximumPoolSize = MAX_CONCURRENT_EXECUTIONS * 2,
                    priority = Thread.MAX_PRIORITY
                )
            )

            _engineState.value = if (unavailableModelCount == 0) EngineState.Ready else EngineState.Degraded
            NeoLogger.i(
                "AINeuralEngine",
                "Neural Engine initialized in ${_engineState.value} with " +
                    "${availableAssets.size} physical assets and $unavailableModelCount unavailable aliases"
            )

            TelemetryCollector.trackEvent(
                TelemetryCollector.EventType.CUSTOM_EVENT,
                "neural_engine_initialized",
                mapOf(
                    "models_registered" to modelConfigurations.size,
                    "models_unavailable" to unavailableModelCount,
                    "assets_discovered" to availableAssets.size
                )
            )

        } catch (e: Exception) {
            _engineState.value = EngineState.Error
            isOperational.set(false)
            ErrorHandler.handle(
                e,
                ErrorHandler.ErrorContext(
                    severity = ErrorHandler.ErrorSeverity.CRITICAL,
                    category = ErrorHandler.ErrorCategory.AI_MODEL_LOADING,
                    operation = "initialize",
                    component = "AINeuralEngine"
                )
            )
            NeoLogger.emergency("AINeuralEngine", "Failed to initialize Neural Engine", e)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // MODEL CONFIGURATION REGISTRATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private fun registerDefaultModelConfigurations() {
        // Depth Estimation Model
        registerModelConfiguration(
            ModelConfiguration(
                modelName = "depth_estimation",
                modelFile = MODEL_DEPTH_ESTIMATION,
                inputShape = intArrayOf(1, 256, 256, 3),
                outputShape = intArrayOf(1, 256, 256, 1),
                useGpuDelegate = true,
                useQuantized = false
            )
        )

        // Face Detection Model
        registerModelConfiguration(
            ModelConfiguration(
                modelName = "face_detection",
                modelFile = MODEL_FACE_DETECTION,
                inputShape = intArrayOf(1, 192, 192, 3),
                outputShape = intArrayOf(1, 100, 17), // 100 faces, 17 values each
                useGpuDelegate = true,
                useQuantized = true
            )
        )

        // Scene Classification Model
        registerModelConfiguration(
            ModelConfiguration(
                modelName = "scene_classification",
                modelFile = MODEL_SCENE_CLASSIFICATION,
                inputShape = intArrayOf(1, 224, 224, 3),
                outputShape = intArrayOf(1, 1000), // 1000 scene classes
                useGpuDelegate = true,
                useQuantized = true
            )
        )

        // Super Resolution Model
        registerModelConfiguration(
            ModelConfiguration(
                modelName = "super_resolution",
                modelFile = MODEL_SUPER_RESOLUTION,
                inputShape = intArrayOf(1, 256, 256, 3),
                outputShape = intArrayOf(1, 512, 512, 3), // 2x upscale
                useGpuDelegate = true,
                useQuantized = false
            )
        )

        // Denoising Model
        registerModelConfiguration(
            ModelConfiguration(
                modelName = "denoising",
                modelFile = MODEL_DENOISING,
                inputShape = intArrayOf(1, 256, 256, 3),
                outputShape = intArrayOf(1, 256, 256, 3),
                useGpuDelegate = true,
                useQuantized = false
            )
        )

        // Segmentation Model
        registerModelConfiguration(
            ModelConfiguration(
                modelName = "segmentation",
                modelFile = MODEL_SEGMENTATION,
                inputShape = intArrayOf(1, 512, 512, 3),
                outputShape = intArrayOf(1, 512, 512, 21), // 21 classes
                useGpuDelegate = true,
                useQuantized = false
            )
        )

        // Pose Estimation Model
        registerModelConfiguration(
            ModelConfiguration(
                modelName = "pose_estimation",
                modelFile = MODEL_POSE_ESTIMATION,
                inputShape = intArrayOf(1, 256, 256, 3),
                outputShape = intArrayOf(1, 17, 3), // 17 keypoints, (x, y, confidence)
                useGpuDelegate = true,
                useQuantized = true
            )
        )

        // Object Detection Model
        registerModelConfiguration(
            ModelConfiguration(
                modelName = "object_detection",
                modelFile = MODEL_OBJECT_DETECTION,
                inputShape = intArrayOf(1, 300, 300, 3),
                outputShape = intArrayOf(1, 100, 6), // 100 objects, 6 values each
                useGpuDelegate = true,
                useQuantized = true
            )
        )

        // Image Enhancement Model
        registerModelConfiguration(
            ModelConfiguration(
                modelName = "image_enhancement",
                modelFile = MODEL_ENHANCEMENT,
                inputShape = intArrayOf(1, 512, 512, 3),
                outputShape = intArrayOf(1, 512, 512, 3),
                useGpuDelegate = true,
                useQuantized = false
            )
        )

        // HDR Merge Model
        registerModelConfiguration(
            ModelConfiguration(
                modelName = "hdr_merge",
                modelFile = MODEL_HDR_MERGE,
                inputShape = intArrayOf(3, 1024, 1024, 3), // 3 exposure brackets
                outputShape = intArrayOf(1, 1024, 1024, 3),
                useGpuDelegate = true,
                useQuantized = false
            )
        )

        NeoLogger.i("AINeuralEngine", "Registered ${modelConfigurations.size} default model configurations")
    }

    fun registerModelConfiguration(configuration: ModelConfiguration) {
        modelConfigurations[configuration.modelName] = configuration
        modelStatusMap[configuration.modelName] = ModelStatus.UNVERIFIED
        NeoLogger.d("AINeuralEngine", "Registered model configuration: ${configuration.modelName}")
    }

    private fun discoverPhysicalModelAssets(): Set<String> {
        val modelAssets = context.assets.list(AIConstants.MODEL_ASSET_DIRECTORY)
            ?.map { "${AIConstants.MODEL_ASSET_DIRECTORY}/$it" }
            .orEmpty()
        val rootAssets = context.assets.list("")
            ?.filter { it.endsWith(".tflite", ignoreCase = true) || it.endsWith(".task", ignoreCase = true) }
            .orEmpty()
        val available = (modelAssets + rootAssets)
            .filter { it.endsWith(".tflite", ignoreCase = true) || it.endsWith(".task", ignoreCase = true) }
            .toSet()
        discoveredModelAssets.set(available)
        NeoLogger.i("AINeuralEngine", "Discovered physical neural assets: $available")
        return available
    }

    private fun reconcileModelAvailability(availableAssets: Set<String>): Int {
        var unavailable = 0
        modelConfigurations.forEach { (modelName, configuration) ->
            val resolvedPath = resolveModelAssetPath(modelName, availableAssets)
                ?: resolveModelAssetPath(configuration.modelFile, availableAssets)
            if (resolvedPath != null) {
                resolvedModelAssets[modelName] = resolvedPath
                modelStatusMap[modelName] = ModelStatus.ACTIVE
                modelConfigurations[modelName] = configuration.copy(modelFile = resolvedPath)
                NeoLogger.i("AINeuralEngine", "Alias '$modelName' mapped to '$resolvedPath'")
            } else {
                resolvedModelAssets.remove(modelName)
                modelStatusMap[modelName] = ModelStatus.UNAVAILABLE
                unavailable++
                NeoLogger.w("AINeuralEngine", "Alias '$modelName' has no packaged model asset; fallback path enabled")
            }
        }
        return unavailable
    }

    fun resolveModelAssetPath(aliasOrPath: String): String? {
        return resolveModelAssetPath(aliasOrPath, discoveredModelAssets.get())
    }

    private fun resolveModelAssetPath(aliasOrPath: String, availableAssets: Set<String>): String? {
        val normalizedAlias = aliasOrPath
            .substringAfterLast('/')
            .removeSuffix(".tflite")
            .removeSuffix(".task")
        val candidates = MODEL_ALIAS_REGISTRY[aliasOrPath]
            ?: MODEL_ALIAS_REGISTRY[normalizedAlias]
            ?: buildList {
                add(aliasOrPath)
                if (!aliasOrPath.startsWith("${AIConstants.MODEL_ASSET_DIRECTORY}/")) {
                    add("${AIConstants.MODEL_ASSET_DIRECTORY}/$aliasOrPath")
                }
            }
        return candidates.firstOrNull { candidate ->
            candidate in availableAssets || assetExistsWithoutLogging(candidate)
        }
    }

    private fun assetExistsWithoutLogging(path: String): Boolean {
        return try {
            context.assets.open(path).use { true }
        } catch (_: Throwable) {
            false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // MODEL LOADING AND UNLOADING
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun loadModel(modelName: String): Boolean {
        val configuration = modelConfigurations[modelName]
            ?: run {
                NeoLogger.e("AINeuralEngine", "Model configuration not found: $modelName")
                return false
            }

        return PerformanceMonitor.measure("load_model_$modelName") {
            try {
                if (modelStatusMap[modelName] != ModelStatus.ACTIVE) {
                    NeoLogger.i("AINeuralEngine", "Skipping unavailable model: $modelName")
                    return@measure false
                }

                // Check if already loaded
                if (loadedModels.containsKey(modelName)) {
                    NeoLogger.d("AINeuralEngine", "Model already loaded: $modelName")
                    return@measure true
                }

                val resolvedModelFile = resolvedModelAssets[modelName]
                    ?: resolveModelAssetPath(modelName)
                    ?: resolveModelAssetPath(configuration.modelFile)

                if (resolvedModelFile == null) {
                    modelStatusMap[modelName] = ModelStatus.UNAVAILABLE
                    NeoLogger.i("AINeuralEngine", "Skipping unavailable model: $modelName")
                    return@measure false
                }

                if (!resolvedModelFile.endsWith(".tflite", ignoreCase = true)) {
                    NeoLogger.i(
                        "AINeuralEngine",
                        "Model '$modelName' resolves to external task asset '$resolvedModelFile'; subsystem loader owns it"
                    )
                    return@measure false
                }

                val modelBuffer = loadModelBuffer(resolvedModelFile)

                // Hoisted so the delegates can be stored on the wrapper and closed in unloadModel.
                // Previously they were local vals inside apply{}, so wrapper.gpuDelegate/nnApiDelegate
                // stayed null -> native GPU/NNAPI delegate leak on reload + getAccelerationType()
                // always reporting CPU.
                var gpuDelegate: GpuDelegate? = null
                var nnApiDelegate: NnApiDelegate? = null
                val interpreterOptions = Interpreter.Options().apply {
                    setNumThreads(configuration.numThreads)
                    setUseXNNPACK(true)

                    if (configuration.allowFp16) {
                        try {
                            @Suppress("DEPRECATION")
                            setAllowFp16PrecisionForFp32(true)
                        } catch (_: Exception) {
                            // FP16 acceleration not available on this TFLite version
                        }
                    }

                    // Try GPU delegate first
                    if (configuration.useGpuDelegate) {
                        try {
                            @Suppress("DEPRECATION")
                            val gpuDelegateOptions = GpuDelegate.Options().apply {
                                setPrecisionLossAllowed(configuration.useQuantized)
                                @Suppress("DEPRECATION")
                                setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                            }
                            val delegate = GpuDelegate(gpuDelegateOptions)
                            addDelegate(delegate)
                            gpuDelegate = delegate
                        } catch (e: Exception) {
                            NeoLogger.w("AINeuralEngine", "GPU delegate not available for $modelName, falling back to CPU", e)
                        }
                    }

                    // Try NNAPI delegate
                    if (configuration.useNnApiDelegate) {
                        try {
                            val delegate = NnApiDelegate()
                            addDelegate(delegate)
                            nnApiDelegate = delegate
                        } catch (e: Exception) {
                            NeoLogger.w("AINeuralEngine", "NNAPI delegate not available for $modelName", e)
                        }
                    }
                }

                val interpreter = Interpreter(modelBuffer, interpreterOptions)

                val wrapper = ModelWrapper(
                    configuration = configuration.copy(modelFile = resolvedModelFile),
                    interpreter = interpreter,
                    gpuDelegate = gpuDelegate,
                    nnApiDelegate = nnApiDelegate
                )

                loadedModels[modelName] = wrapper

                NeoLogger.i("AINeuralEngine", "Model loaded successfully: $modelName")
                TelemetryCollector.trackEvent(
                    TelemetryCollector.EventType.CUSTOM_EVENT,
                    "model_loaded",
                    mapOf("model_name" to modelName, "acceleration" to getAccelerationType(modelName).name)
                )

                true

            } catch (e: Exception) {
                ErrorHandler.handle(
                    e,
                    ErrorHandler.ErrorContext(
                        severity = ErrorHandler.ErrorSeverity.RECOVERABLE,
                        category = ErrorHandler.ErrorCategory.AI_MODEL_LOADING,
                        operation = "load_model",
                        component = "AINeuralEngine",
                        metadata = mapOf("model_name" to modelName)
                    )
                )
                modelStatusMap[modelName] = ModelStatus.UNAVAILABLE
                NeoLogger.e("AINeuralEngine", "Failed to load model: $modelName", e)
                false
            }
        }
    }

    fun unloadModel(modelName: String) {
        loadedModels.remove(modelName)?.let { wrapper ->
            try {
                wrapper.interpreter?.close()
                wrapper.gpuDelegate?.close()
                wrapper.nnApiDelegate?.close()
                NeoLogger.i("AINeuralEngine", "Model unloaded: $modelName")
            } catch (e: Exception) {
                NeoLogger.w("AINeuralEngine", "Error unloading model: $modelName", e)
            }
        }
    }

    fun preloadAllModels(): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()
        modelConfigurations.keys.forEach { modelName ->
            if (modelStatusMap[modelName] != ModelStatus.ACTIVE) {
                NeoLogger.d("AINeuralEngine", "Skipping unavailable model alias during preload: $modelName")
                results[modelName] = false
            } else {
                val loaded = loadModel(modelName)
                results[modelName] = loaded
                if (modelName == MODEL_DENOISING && loaded) {
                    getNeuralDenoiseEngine().markModelPreloaded()
                }
            }
        }
        return results
    }

    fun unloadAllModels() {
        loadedModels.keys.toList().forEach { unloadModel(it) }
    }

    private fun loadModelFile(fileDescriptor: android.content.res.AssetFileDescriptor): MappedByteBuffer {
        FileInputStream(fileDescriptor.fileDescriptor).use { inputStream ->
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }

    private fun loadModelBuffer(assetPath: String): ByteBuffer {
        return try {
            context.assets.openFd(assetPath).use { fileDescriptor ->
                loadModelFile(fileDescriptor)
            }
        } catch (_: Throwable) {
            context.assets.open(assetPath).use { inputStream ->
                val bytes = inputStream.readBytes()
                ByteBuffer.allocateDirect(bytes.size)
                    .order(ByteOrder.nativeOrder())
                    .apply {
                        put(bytes)
                        rewind()
                    }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INFERENCE EXECUTION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    suspend fun <T> runInference(
        modelName: String,
        inputData: Any,
        outputMapper: (Array<*>) -> T,
        timeoutMs: Long = DEFAULT_INFERENCE_TIMEOUT_MS
    ): InferenceResult<T> {
        val correlationId = java.util.UUID.randomUUID().toString()

        return PerformanceMonitor.measureSuspend("inference_$modelName") {
            // Validate engine state
            if (_engineState.value !is EngineState.Ready &&
                _engineState.value !is EngineState.Processing &&
                _engineState.value !is EngineState.Degraded
            ) {
                return@measureSuspend InferenceResult.Failure(
                    error = IllegalStateException("Engine not ready: ${_engineState.value}"),
                    modelName = modelName,
                    correlationId = correlationId
                )
            }

            if (modelStatusMap[modelName] == ModelStatus.UNAVAILABLE) {
                return@measureSuspend InferenceResult.Failure(
                    error = IllegalStateException("Model unavailable: $modelName"),
                    modelName = modelName,
                    correlationId = correlationId
                )
            }

            // Load model if not loaded
            if (!loadedModels.containsKey(modelName)) {
                if (!loadModel(modelName)) {
                    return@measureSuspend InferenceResult.Failure(
                        error = IllegalStateException("Failed to load model: $modelName"),
                        modelName = modelName,
                        correlationId = correlationId
                    )
                }
            }

            val wrapper = loadedModels[modelName]
                ?: return@measureSuspend InferenceResult.Failure(
                    error = IllegalStateException("Model not available: $modelName"),
                    modelName = modelName,
                    correlationId = correlationId
                )

            // Check concurrent execution limit
            if (activeExecutions.incrementAndGet() > MAX_CONCURRENT_EXECUTIONS) {
                activeExecutions.decrementAndGet()
                return@measureSuspend InferenceResult.Failure(
                    error = IllegalStateException("Max concurrent executions reached"),
                    modelName = modelName,
                    correlationId = correlationId
                )
            }

            try {
                withTimeout(timeoutMs) {
                    executeInference(wrapper, inputData, outputMapper, correlationId)
                }
            } catch (e: TimeoutCancellationException) {
                timeoutInferences.incrementAndGet()
                InferenceResult.Timeout(modelName, correlationId, timeoutMs)
            } finally {
                activeExecutions.decrementAndGet()
            }
        }
    }

    private suspend fun <T> executeInference(
        wrapper: ModelWrapper,
        inputData: Any,
        outputMapper: (Array<*>) -> T,
        correlationId: String
    ): InferenceResult<T> {
        return withContext(engineScope.coroutineContext) {
            try {
                synchronized(wrapper.lock) {
                    val interpreter = wrapper.interpreter
                        ?: return@withContext InferenceResult.Failure(
                            error = IllegalStateException("Interpreter not available"),
                            modelName = wrapper.configuration.modelName,
                            correlationId = correlationId
                        )

                    val startTime = System.currentTimeMillis()

                    // Prepare input tensor from the actual loaded model contract, not stale defaults.
                    val inputSpec = resolveInputTensorSpec(wrapper.configuration, interpreter)
                    val inputBuffer = prepareInputBuffer(wrapper.configuration, inputData, inputSpec)

                    val outputShape = resolveOutputShape(wrapper, interpreter)
                    val outputArray = allocateFloatOutputTensor(outputShape)

                    // Run inference
                    interpreter.run(inputBuffer, outputArray)

                    val inferenceTime = System.currentTimeMillis() - startTime

                    // Update statistics
                    wrapper.inferenceCount.incrementAndGet()
                    wrapper.lastUsed.set(System.currentTimeMillis())
                    updateAverageInferenceTime(wrapper, inferenceTime)

                    // Update global statistics
                    totalInferences.incrementAndGet()
                    successfulInferences.incrementAndGet()

                    // Map output
                    val result = outputMapper(outputMapperPayload(outputArray))

                    // Recycle input buffer
                    recycleInputBuffer(wrapper.configuration.modelName, inputBuffer)

                    InferenceResult.Success(
                        data = result,
                        inferenceTimeMs = inferenceTime,
                        modelName = wrapper.configuration.modelName,
                        correlationId = correlationId
                    )
                }

            } catch (e: Exception) {
                wrapper.errorCount.incrementAndGet()
                failedInferences.incrementAndGet()

                ErrorHandler.handle(
                    e,
                    ErrorHandler.ErrorContext(
                        severity = ErrorHandler.ErrorSeverity.RECOVERABLE,
                        category = ErrorHandler.ErrorCategory.AI_INFERENCE,
                        operation = "inference",
                        component = "AINeuralEngine",
                        metadata = mapOf("model_name" to wrapper.configuration.modelName)
                    )
                )

                InferenceResult.Failure(
                    error = e,
                    modelName = wrapper.configuration.modelName,
                    correlationId = correlationId
                )
            }
        }
    }

    private fun resolveOutputShape(wrapper: ModelWrapper, interpreter: Interpreter): IntArray {
        val configuredShape = wrapper.configuration.outputShape
        val runtimeShape = runCatching { interpreter.getOutputTensor(0).shape() }
            .getOrDefault(configuredShape)
            .mapIndexed { index, dimension ->
                if (dimension > 0) {
                    dimension
                } else {
                    configuredShape.getOrNull(index)?.takeIf { it > 0 } ?: 1
                }
            }
            .toIntArray()
        return if (runtimeShape.isNotEmpty()) runtimeShape else configuredShape
    }

    private data class RuntimeInputTensorSpec(
        val shape: IntArray,
        val dataType: DataType,
        val byteSize: Int,
        val width: Int,
        val height: Int,
        val channels: Int
    ) {
        val isFloat32: Boolean = dataType == DataType.FLOAT32
        val isQuantizedBytes: Boolean = dataType == DataType.UINT8 || dataType == DataType.INT8
    }

    private fun resolveInputTensorSpec(
        configuration: ModelConfiguration,
        interpreter: Interpreter
    ): RuntimeInputTensorSpec {
        val configuredShape = configuration.inputShape
        val inputTensor = runCatching { interpreter.getInputTensor(0) }.getOrNull()
        val runtimeShape = inputTensor?.shape()
            ?.mapIndexed { index, dimension ->
                if (dimension > 0) {
                    dimension
                } else {
                    configuredShape.getOrNull(index)?.takeIf { it > 0 } ?: 1
                }
            }
            ?.toIntArray()
            ?: configuredShape
        val dataType = inputTensor?.dataType()
            ?: if (configuration.useQuantized) DataType.UINT8 else DataType.FLOAT32
        val byteSize = inputTensor?.numBytes()
            ?: (runtimeShape.fold(1) { acc, value -> acc * value.coerceAtLeast(1) } * bytesPerTensorComponent(dataType))
        val height = when {
            runtimeShape.size >= 4 -> runtimeShape[1]
            runtimeShape.size >= 3 -> runtimeShape[0]
            else -> configuredShape.getOrNull(1) ?: 1
        }.coerceAtLeast(1)
        val width = when {
            runtimeShape.size >= 4 -> runtimeShape[2]
            runtimeShape.size >= 3 -> runtimeShape[1]
            else -> configuredShape.getOrNull(2) ?: 1
        }.coerceAtLeast(1)
        val channels = when {
            runtimeShape.size >= 4 -> runtimeShape[3]
            runtimeShape.size >= 3 -> runtimeShape[2]
            else -> configuredShape.lastOrNull() ?: 3
        }.coerceAtLeast(1)
        return RuntimeInputTensorSpec(
            shape = runtimeShape,
            dataType = dataType,
            byteSize = byteSize.coerceAtLeast(1),
            width = width,
            height = height,
            channels = channels
        )
    }

    private fun bytesPerTensorComponent(dataType: DataType): Int =
        when (dataType) {
            DataType.FLOAT32 -> java.lang.Float.BYTES
            DataType.INT32 -> Int.SIZE_BYTES
            DataType.INT64 -> Long.SIZE_BYTES
            DataType.UINT8, DataType.INT8, DataType.BOOL -> Byte.SIZE_BYTES
            else -> java.lang.Float.BYTES
        }

    private fun allocateFloatOutputTensor(shape: IntArray): Any {
        require(shape.isNotEmpty()) { "Output shape must not be empty" }
        return when (shape.size) {
            1 -> FloatArray(shape[0])
            2 -> Array(shape[0]) { FloatArray(shape[1]) }
            3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
            4 -> Array(shape[0]) { Array(shape[1]) { Array(shape[2]) { FloatArray(shape[3]) } } }
            else -> throw IllegalArgumentException("Unsupported output rank: ${shape.size}")
        }
    }

    private fun outputMapperPayload(outputArray: Any): Array<*> {
        return when (outputArray) {
            is Array<*> -> outputArray
            else -> arrayOf(outputArray)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // BATCHED INFERENCE
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    suspend fun <T> runBatchedInference(
        modelName: String,
        inputs: List<Any>,
        outputMapper: (Array<*>) -> T,
        timeoutMs: Long = DEFAULT_INFERENCE_TIMEOUT_MS
    ): List<InferenceResult<T>> {
        if (inputs.isEmpty()) return emptyList()

        return PerformanceMonitor.measureSuspend("batched_inference_$modelName") {
            val results = mutableListOf<InferenceResult<T>>()

            // Process in batches
            inputs.chunked(MAX_BATCH_SIZE).forEach { batch ->
                batch.forEach { input ->
                    results.add(runInference(modelName, input, outputMapper, timeoutMs))
                }
            }

            results
        }
    }

    fun getNeuralDenoiseEngine(): NeuralDenoiseEngine {
        return neuralDenoiseEngineRef.get() ?: NeuralDenoiseEngine(context).also { engine ->
            if (!neuralDenoiseEngineRef.compareAndSet(null, engine)) {
                return neuralDenoiseEngineRef.get() ?: engine
            }
        }
    }

    suspend fun <T> runDenoiseTile(
        inputData: Any,
        outputMapper: (Array<*>) -> T,
        timeoutMs: Long = AIConstants.DENOISING_BUDGET_MS
    ): InferenceResult<T> {
        return runInference(
            modelName = MODEL_DENOISING,
            inputData = inputData,
            outputMapper = outputMapper,
            timeoutMs = timeoutMs
        )
    }

    enum class ModelStatus {
        UNVERIFIED,
        ACTIVE,
        UNAVAILABLE
    }

    data class DirectTensorInput(
        val data: ByteBuffer,
        val isFloat32: Boolean = true
    )

    data class DirectYuvTensorInput(
        val yPlane: ByteBuffer,
        val uPlane: ByteBuffer,
        val vPlane: ByteBuffer,
        val yRowStride: Int,
        val uRowStride: Int,
        val vRowStride: Int,
        val uvPixelStride: Int,
        val width: Int,
        val height: Int,
        val normScale: Float = 1f / 255f,
        val normBias: Float = 0f
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INPUT/OUTPUT BUFFER MANAGEMENT
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private fun prepareInputBuffer(
        configuration: ModelConfiguration,
        inputData: Any,
        inputSpec: RuntimeInputTensorSpec
    ): ByteBuffer {
        val inputSize = inputSpec.byteSize
        // Stable key (model + input shape) so the direct buffer is REUSED across inferences.
        // The previous key embedded System.currentTimeMillis(), so the cache ALWAYS missed and a
        // fresh (often multi-MB) direct ByteBuffer was allocated every inference -> GC/native churn.
        // Reuse is safe: prepareInputBuffer runs inside synchronized(wrapper.lock) (executeInference),
        // serializing access per model; the key is per-model so distinct models never share a buffer.
        val cacheKey = "${configuration.modelName}_${inputSpec.shape.joinToString("x")}_${inputSpec.dataType}_$inputSize"
        val cached = tensorCache.get(cacheKey)
        val buffer = if (cached != null && cached.capacity() == inputSize) {
            cached.apply { clear() }
        } else {
            ByteBuffer.allocateDirect(inputSize).apply {
                order(ByteOrder.nativeOrder())
                tensorCache.put(cacheKey, this)
            }
        }
        populateInputBuffer(buffer, inputData, inputSpec)
        return buffer
    }

    fun processYuvToTensorDirect(
        input: DirectYuvTensorInput,
        outputBuffer: ByteBuffer
    ): Boolean {
        if (!outputBuffer.isDirect) return false
        if (!input.yPlane.isDirect || !input.uPlane.isDirect || !input.vPlane.isDirect) return false
        if (input.width <= 0 || input.height <= 0) return false
        if (outputBuffer.capacity() < input.width * input.height * 3 * java.lang.Float.BYTES) return false
        if (!nativeTensorBridgeAvailable) return false

        outputBuffer.clear()
        return try {
            val processed = nativeProcessYuvToBuffer(
                input.yPlane,
                input.uPlane,
                input.vPlane,
                input.yRowStride,
                input.uRowStride,
                input.vRowStride,
                input.uvPixelStride,
                input.width,
                input.height,
                outputBuffer,
                input.normScale,
                input.normBias
            )
            outputBuffer.rewind()
            processed
        } catch (_: UnsatisfiedLinkError) {
            nativeTensorBridgeAvailable = false
            false
        } catch (e: Throwable) {
            NeoLogger.w("AINeuralEngine", "Native YUV tensor bridge failed; scalar fallback will be used", e)
            false
        }
    }

    fun processHardwareBufferToTensorDirect(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        outputBuffer: ByteBuffer,
        normScale: Float = 1f / 255f,
        normBias: Float = 0f
    ): Boolean {
        if (!outputBuffer.isDirect) return false
        if (width <= 0 || height <= 0) return false
        if (outputBuffer.capacity() < width * height * 3 * java.lang.Float.BYTES) return false
        if (!nativeTensorBridgeAvailable) return false

        outputBuffer.clear()
        return try {
            val processed = nativeProcessHardwareBufferToBuffer(
                hardwareBuffer,
                width,
                height,
                outputBuffer,
                normScale,
                normBias
            )
            outputBuffer.rewind()
            processed
        } catch (_: UnsatisfiedLinkError) {
            nativeTensorBridgeAvailable = false
            false
        } catch (e: Throwable) {
            NeoLogger.w("AINeuralEngine", "Native HardwareBuffer tensor bridge failed", e)
            false
        }
    }

    private fun populateInputBuffer(
        buffer: ByteBuffer,
        inputData: Any,
        inputSpec: RuntimeInputTensorSpec
    ) {
        buffer.clear()
        var alreadyRewound = false
        when (inputData) {
            is Bitmap -> {
                populateBitmapInputBuffer(buffer, inputData, inputSpec)
            }
            is FloatArray -> populateFloatArrayInputBuffer(buffer, inputData, inputSpec)
            is DirectTensorInput -> {
                populateDirectTensorInputBuffer(buffer, inputData, inputSpec)
            }
            is DirectYuvTensorInput -> {
                if (!inputSpec.isFloat32 ||
                    inputData.width != inputSpec.width ||
                    inputData.height != inputSpec.height ||
                    !processYuvToTensorDirect(inputData, buffer)
                ) {
                    populateYuvInputBufferScalar(buffer, inputData, inputSpec)
                }
                // Both YUV paths write the full tensor and rewind() (position 0); zero-padding
                // here would overwrite the entire populated buffer with zeros.
                alreadyRewound = true
            }
            is ByteBuffer -> {
                val source = inputData.duplicate()
                source.rewind()
                copyBytesInto(buffer, source)
            }
            else -> throw IllegalArgumentException("Unsupported input type: ${inputData.javaClass}")
        }
        if (!alreadyRewound) {
            while (buffer.hasRemaining()) {
                buffer.put(0)
            }
            buffer.rewind()
        }
    }

    private external fun nativeProcessYuvToBuffer(
        yPlane: ByteBuffer,
        uPlane: ByteBuffer,
        vPlane: ByteBuffer,
        yStride: Int,
        uStride: Int,
        vStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        directOutputBuffer: ByteBuffer,
        normScale: Float,
        normBias: Float
    ): Boolean

    private external fun nativeProcessHardwareBufferToBuffer(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        directOutputBuffer: ByteBuffer,
        normScale: Float,
        normBias: Float
    ): Boolean

    private fun populateFloatArrayInputBuffer(
        buffer: ByteBuffer,
        inputData: FloatArray,
        inputSpec: RuntimeInputTensorSpec
    ) {
        inputData.forEach { value ->
            if (inputSpec.isFloat32 && buffer.remaining() >= java.lang.Float.BYTES) {
                buffer.putFloat(value)
            } else if (inputSpec.isQuantizedBytes && buffer.hasRemaining()) {
                buffer.put(quantizedByte(value))
            }
        }
    }

    private fun populateDirectTensorInputBuffer(
        buffer: ByteBuffer,
        inputData: DirectTensorInput,
        inputSpec: RuntimeInputTensorSpec
    ) {
        val source = inputData.data.duplicate()
        source.rewind()
        if (inputData.isFloat32 && inputSpec.isQuantizedBytes) {
            val floats = source.asFloatBuffer()
            while (floats.hasRemaining() && buffer.hasRemaining()) {
                buffer.put(quantizedByte(floats.get()))
            }
        } else {
            copyBytesInto(buffer, source)
        }
    }

    private fun populateBitmapInputBuffer(
        buffer: ByteBuffer,
        bitmap: Bitmap,
        inputSpec: RuntimeInputTensorSpec
    ) {
        val targetWidth = inputSpec.width
        val targetHeight = inputSpec.height
        val copySource = if (bitmap.config == Bitmap.Config.HARDWARE) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }
        val sourceWidth = copySource.width.coerceAtLeast(1)
        val sourceHeight = copySource.height.coerceAtLeast(1)
        val bytesPerPixel = bitmapBytesPerPixel(copySource)
        val pixelBytes = reusableBitmapPixelBuffer(copySource.byteCount.coerceAtLeast(sourceWidth * sourceHeight * bytesPerPixel))
        try {
            pixelBytes.clear()
            copySource.copyPixelsToBuffer(pixelBytes)
            pixelBytes.flip()
            for (y in 0 until targetHeight) {
                val sourceY = ((y.toLong() * sourceHeight) / targetHeight).toInt().coerceIn(0, sourceHeight - 1)
                for (x in 0 until targetWidth) {
                    val sourceX = ((x.toLong() * sourceWidth) / targetWidth).toInt().coerceIn(0, sourceWidth - 1)
                    putBitmapScratchPixel(
                        buffer = buffer,
                        inputSpec = inputSpec,
                        pixelBytes = pixelBytes,
                        byteOffset = (sourceY * sourceWidth + sourceX) * bytesPerPixel,
                        bytesPerPixel = bytesPerPixel
                    )
                    if (!buffer.hasRemaining()) return
                }
            }
        } finally {
            if (copySource !== bitmap && !copySource.isRecycled) {
                copySource.recycle()
            }
        }
    }

    private fun reusableBitmapPixelBuffer(requiredBytes: Int): ByteBuffer {
        val key = "bitmap_pixels_thread_${System.identityHashCode(Thread.currentThread())}"
        val existing = bitmapTensorScratch[key]
        if (existing != null && existing.capacity() >= requiredBytes) {
            existing.clear()
            existing.order(ByteOrder.nativeOrder())
            return existing
        }
        return ByteBuffer.allocateDirect(requiredBytes)
            .order(ByteOrder.nativeOrder())
            .also { bitmapTensorScratch[key] = it }
    }

    private fun bitmapBytesPerPixel(bitmap: Bitmap): Int {
        return when (bitmap.config) {
            Bitmap.Config.ALPHA_8 -> 1
            Bitmap.Config.RGB_565 -> 2
            else -> 4
        }
    }

    private fun putBitmapScratchPixel(
        buffer: ByteBuffer,
        inputSpec: RuntimeInputTensorSpec,
        pixelBytes: ByteBuffer,
        byteOffset: Int,
        bytesPerPixel: Int
    ) {
        if (byteOffset < 0 || byteOffset + bytesPerPixel > pixelBytes.limit()) {
            putRgbComponents(buffer, inputSpec, 0, 0, 0, 255)
            return
        }
        when (bytesPerPixel) {
            1 -> {
                val y = pixelBytes.get(byteOffset).toInt() and 0xFF
                putRgbComponents(buffer, inputSpec, y, y, y, 255)
            }
            2 -> {
                val rgb565 = pixelBytes.getShort(byteOffset).toInt() and 0xFFFF
                putRgbComponents(
                    buffer = buffer,
                    inputSpec = inputSpec,
                    r = ((rgb565 shr 11) and 0x1F) * 255 / 31,
                    g = ((rgb565 shr 5) and 0x3F) * 255 / 63,
                    b = (rgb565 and 0x1F) * 255 / 31,
                    a = 255
                )
            }
            else -> {
                putRgbComponents(
                    buffer = buffer,
                    inputSpec = inputSpec,
                    r = pixelBytes.get(byteOffset).toInt() and 0xFF,
                    g = pixelBytes.get(byteOffset + 1).toInt() and 0xFF,
                    b = pixelBytes.get(byteOffset + 2).toInt() and 0xFF,
                    a = pixelBytes.get(byteOffset + 3).toInt() and 0xFF
                )
            }
        }
    }

    private fun populateYuvInputBufferScalar(
        buffer: ByteBuffer,
        input: DirectYuvTensorInput,
        inputSpec: RuntimeInputTensorSpec
    ) {
        buffer.clear()
        val yPlane = input.yPlane.duplicate()
        val uPlane = input.uPlane.duplicate()
        val vPlane = input.vPlane.duplicate()
        val pixelStride = input.uvPixelStride.coerceAtLeast(1)
        val sourceWidth = input.width.coerceAtLeast(1)
        val sourceHeight = input.height.coerceAtLeast(1)
        for (y in 0 until inputSpec.height) {
            val sourceY = ((y.toLong() * sourceHeight) / inputSpec.height).toInt().coerceIn(0, sourceHeight - 1)
            val yRowOffset = sourceY * input.yRowStride
            val chromaY = sourceY / 2
            val uRowOffset = chromaY * input.uRowStride
            val vRowOffset = chromaY * input.vRowStride
            for (x in 0 until inputSpec.width) {
                val sourceX = ((x.toLong() * sourceWidth) / inputSpec.width).toInt().coerceIn(0, sourceWidth - 1)
                val chromaX = sourceX / 2
                val yIndex = yRowOffset + sourceX
                val uIndex = uRowOffset + chromaX * pixelStride
                val vIndex = vRowOffset + chromaX * pixelStride
                if (yIndex >= yPlane.capacity() || uIndex >= uPlane.capacity() || vIndex >= vPlane.capacity()) {
                    putRgbComponents(buffer, inputSpec, 0, 0, 0, 255)
                    continue
                }
                val luma = yPlane.get(yIndex).toInt() and 0xFF
                val cb = (uPlane.get(uIndex).toInt() and 0xFF) - 128
                val cr = (vPlane.get(vIndex).toInt() and 0xFF) - 128
                val r = (luma + 1.402f * cr).toInt().coerceIn(0, 255)
                val g = (luma - 0.344136f * cb - 0.714136f * cr).toInt().coerceIn(0, 255)
                val b = (luma + 1.772f * cb).toInt().coerceIn(0, 255)
                if (inputSpec.isFloat32) {
                    putFloatComponent(buffer, r * input.normScale + input.normBias)
                    putFloatComponent(buffer, g * input.normScale + input.normBias)
                    putFloatComponent(buffer, b * input.normScale + input.normBias)
                } else {
                    putRgbComponents(buffer, inputSpec, r, g, b, 255)
                }
                if (!buffer.hasRemaining()) break
            }
            if (!buffer.hasRemaining()) break
        }
        buffer.rewind()
    }

    private fun putRgbComponents(
        buffer: ByteBuffer,
        inputSpec: RuntimeInputTensorSpec,
        r: Int,
        g: Int,
        b: Int,
        a: Int
    ) {
        if (inputSpec.channels == 1) {
            val luma = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            putComponent(buffer, inputSpec, luma)
            return
        }
        putComponent(buffer, inputSpec, r)
        if (inputSpec.channels >= 2) putComponent(buffer, inputSpec, g)
        if (inputSpec.channels >= 3) putComponent(buffer, inputSpec, b)
        if (inputSpec.channels >= 4) putComponent(buffer, inputSpec, a)
    }

    private fun putComponent(buffer: ByteBuffer, inputSpec: RuntimeInputTensorSpec, value: Int) {
        if (inputSpec.isFloat32) {
            putFloatComponent(buffer, value.coerceIn(0, 255) / 255.0f)
        } else if (inputSpec.isQuantizedBytes && buffer.hasRemaining()) {
            buffer.put(value.coerceIn(0, 255).toByte())
        }
    }

    private fun putFloatComponent(buffer: ByteBuffer, value: Float) {
        if (buffer.remaining() >= java.lang.Float.BYTES) {
            buffer.putFloat(value)
        }
    }

    private fun quantizedByte(value: Float): Byte =
        (value.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()

    private fun copyBytesInto(target: ByteBuffer, source: ByteBuffer) {
        val length = minOf(target.remaining(), source.remaining())
        if (length <= 0) return
        val originalLimit = source.limit()
        source.limit(source.position() + length)
        target.put(source)
        source.limit(originalLimit)
    }

    private fun recycleInputBuffer(modelName: String, buffer: ByteBuffer) {
        buffer.clear()
        // Could cache the buffer here for reuse
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun isModelLoaded(modelName: String): Boolean {
        return loadedModels.containsKey(modelName)
    }

    fun isModelAvailable(modelName: String): Boolean {
        return modelStatusMap[modelName] == ModelStatus.ACTIVE
    }

    fun getModelStatus(modelName: String): ModelStatus {
        return modelStatusMap[modelName] ?: ModelStatus.UNVERIFIED
    }

    fun getLoadedModels(): List<String> {
        return loadedModels.keys.toList()
    }

    fun getAccelerationType(modelName: String): AccelerationType {
        val wrapper = loadedModels[modelName] ?: return AccelerationType.CPU

        return when {
            wrapper.gpuDelegate != null -> AccelerationType.GPU
            wrapper.nnApiDelegate != null -> AccelerationType.NNAPI
            else -> AccelerationType.CPU
        }
    }

    fun getModelConfiguration(modelName: String): ModelConfiguration? {
        return modelConfigurations[modelName]
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // STATISTICS AND TELEMETRY
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private fun updateAverageInferenceTime(wrapper: ModelWrapper, newTime: Long) {
        val current = wrapper.averageInferenceTime.get()
        val count = wrapper.inferenceCount.get()
        val newAverage = (current * (count - 1) + newTime) / count
        wrapper.averageInferenceTime.set(newAverage)
    }

    fun getStatistics(): EngineStatistics {
        val total = totalInferences.get()
        val success = successfulInferences.get()
        val avgTime = if (total > 0) {
            loadedModels.values.sumOf { it.averageInferenceTime.get() }.toDouble() / loadedModels.size
        } else 0.0

        val modelStats = loadedModels.mapValues { (_, wrapper) ->
            ModelStatistics(
                modelName = wrapper.configuration.modelName,
                inferenceCount = wrapper.inferenceCount.get(),
                errorCount = wrapper.errorCount.get(),
                averageInferenceTimeMs = wrapper.averageInferenceTime.get().toDouble(),
                lastUsed = wrapper.lastUsed.get(),
                isLoaded = wrapper.interpreter != null
            )
        }

        return EngineStatistics(
            totalInferences = total,
            successfulInferences = success,
            failedInferences = failedInferences.get(),
            timeoutInferences = timeoutInferences.get(),
            averageInferenceTimeMs = avgTime,
            activeModels = loadedModels.size,
            totalModels = modelConfigurations.size,
            memoryUsageBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            currentState = _engineState.value,
            modelStatistics = modelStats
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // SHUTDOWN
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun shutdown() {
        if (!isOperational.compareAndSet(true, false)) return

        _engineState.value = EngineState.ShuttingDown
        NeoLogger.i("AINeuralEngine", "Shutting down Neural Engine...")

        engineScope.cancel()
        unloadAllModels()
        bitmapTensorScratch.clear()
        tensorCache.shutdown()

        _engineState.value = EngineState.Shutdown
        NeoLogger.i("AINeuralEngine", "Neural Engine shut down")
    }
}
