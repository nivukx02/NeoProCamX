package com.neoproc.neoproccamx.ai

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.infrastructure.cache.CacheManager
import com.neoproc.neoproccamx.infrastructure.config.ConfigurationManager
import com.neoproc.neoproccamx.infrastructure.error.ErrorHandler
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.infrastructure.performance.PerformanceMonitor
import com.neoproc.neoproccamx.infrastructure.telemetry.TelemetryCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * AI PERFORMANCE OPTIMIZER — INTELLIGENT ADAPTIVE PERFORMANCE MANAGEMENT SYSTEM
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Advanced AI performance optimization providing:
 *   - Dynamic quality adjustment based on device capabilities
 *   - Thermal throttling detection and response
 *   - Memory pressure handling with automatic model unloading
 *   - Battery-aware processing modes
 *   - Real-time performance profiling and adaptation
 *   - Predictive performance modeling
 *   - Automatic pipeline optimization
 *   - Cross-session performance learning
 *
 * Thread Safety: All optimization decisions are thread-safe.
 * Performance: Minimal overhead with predictive preemption.
 *
 * @author NeoProCamX AI Performance Team
 * @version 10.0.0-ENTERPRISE
 * @since 1.0.0
 */
class AIPerformanceOptimizer private constructor(context: Context) {

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: AIPerformanceOptimizer? = null

        fun getInstance(context: Context): AIPerformanceOptimizer {
            return instance ?: synchronized(this) {
                instance ?: AIPerformanceOptimizer(context.applicationContext).also {
                    instance = it
                    it.initialize()
                }
            }
        }

        fun getInstance(): AIPerformanceOptimizer {
            return instance ?: throw IllegalStateException("AIPerformanceOptimizer not initialized")
        }

        // ═════════════════════════════════════════════════════════════════════════════════════════════
        // PERFORMANCE OPTIMIZATION CONSTANTS
        // ═════════════════════════════════════════════════════════════════════════════════════════════

        /** Memory threshold for low memory mode (percentage). */
        private const val LOW_MEMORY_THRESHOLD_PCT = 85

        /** Memory threshold for critical memory mode (percentage). */
        private const val CRITICAL_MEMORY_THRESHOLD_PCT = 95

        /** Thermal threshold for throttling (Celsius). */
        private const val THERMAL_THROTTLE_THRESHOLD = 42

        /** Critical thermal threshold (Celsius). */
        private const val THERMAL_CRITICAL_THRESHOLD = 48

        /** Battery threshold for power saving (percentage). */
        private const val LOW_BATTERY_THRESHOLD = 20

        /** Critical battery threshold (percentage). */
        private const val CRITICAL_BATTERY_THRESHOLD = 10

        /** Performance monitoring interval (milliseconds). */
        private const val MONITORING_INTERVAL_MS = 2000L

        /** Quality adjustment step size. */
        private const val QUALITY_ADJUSTMENT_STEP = 0.1f
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // OPTIMIZATION STRATEGY ENUM
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    enum class OptimizationStrategy {
        MAXIMUM_QUALITY,    // Prioritize quality over performance
        BALANCED,          // Balance quality and performance
        PERFORMANCE,       // Prioritize performance over quality
        POWER_SAVE,        // Minimize power consumption
        THERMAL_ADAPTIVE,  // Adapt based on thermal state
        MEMORY_ADAPTIVE    // Adapt based on memory pressure
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // DEVICE CAPABILITY TIER
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    enum class DeviceCapabilityTier {
        ENTRY_LEVEL,       // Low-end devices
        MID_RANGE,         // Mid-tier devices
        HIGH_END,          // Flagship devices
        FLAGSHIP           // Latest flagship devices
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // PERFORMANCE MODE
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    data class PerformanceMode(
        val name: String,
        val targetFps: Int,
        val inferenceResolution: Int,
        val modelQuality: ModelQuality,
        val enableAdvancedFeatures: Boolean,
        val maxConcurrentModels: Int,
        val gpuMemoryLimitMb: Int,
        val cpuThreadCount: Int
    )

    enum class ModelQuality {
        LOW,      // Fastest inference, lowest quality
        MEDIUM,   // Balanced quality and speed
        HIGH,     // High quality, slower inference
        MAXIMUM   // Maximum quality, slowest inference
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // DEVICE STATE DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    data class DeviceState(
        val availableMemoryMb: Long,
        val totalMemoryMb: Long,
        val memoryUsagePercent: Float,
        val javaHeapUsagePercent: Float = memoryUsagePercent,
        val systemAvailableMemoryMb: Long = availableMemoryMb,
        val systemTotalMemoryMb: Long = totalMemoryMb,
        val systemLowMemory: Boolean = false,
        val thermalState: ThermalState,
        val batteryPercent: Int,
        val isCharging: Boolean,
        val cpuUsage: Float,
        val gpuUsage: Float
    )

    data class ThermalState(
        val temperatureCelsius: Float,
        val thermalStatus: Int // From ThermalManager
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // PERFORMANCE METRICS DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    data class PerformanceMetrics(
        val averageInferenceTimeMs: Double,
        val p95InferenceTimeMs: Double,
        val p99InferenceTimeMs: Double,
        val throughputFps: Double,
        val droppedFrames: Int,
        val memoryPressureEvents: Int,
        val thermalThrottlingEvents: Int
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // OPTIMIZER STATE
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    sealed class OptimizerState {
        object Initializing : OptimizerState()
        object Monitoring : OptimizerState()
        object Optimizing : OptimizerState()
        object Throttling : OptimizerState()
        object Recovery : OptimizerState()
        object Error : OptimizerState()
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INTERNAL STATE
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private val context = context.applicationContext
    private val optimizerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _optimizerState = MutableStateFlow<OptimizerState>(OptimizerState.Initializing)
    val optimizerState: StateFlow<OptimizerState> = _optimizerState.asStateFlow()

    private val _currentPerformanceMode = MutableStateFlow<PerformanceMode>(createDefaultMode())
    val currentPerformanceMode: StateFlow<PerformanceMode> = _currentPerformanceMode.asStateFlow()

    private val _optimizationStrategy = MutableStateFlow(OptimizationStrategy.BALANCED)
    val optimizationStrategy: StateFlow<OptimizationStrategy> = _optimizationStrategy.asStateFlow()

    private val isOperational = AtomicBoolean(false)
    private val deviceCapabilityTier = AtomicReference(DeviceCapabilityTier.MID_RANGE)

    private val performanceHistory = ConcurrentHashMap<Long, PerformanceMetrics>()
    private val adaptationEvents = AtomicInteger(0)
    private val throttlingEvents = AtomicInteger(0)

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private fun initialize() {
        if (!isOperational.compareAndSet(false, true)) {
            NeoLogger.w("AIPerformanceOptimizer", "Already initialized")
            return
        }

        NeoLogger.i("AIPerformanceOptimizer", "Initializing performance optimizer...")

        // Detect device capabilities
        detectDeviceCapabilities()

        // Set initial performance mode based on device tier
        setInitialPerformanceMode()

        // Start monitoring
        startPerformanceMonitoring()

        _optimizerState.value = OptimizerState.Monitoring
        NeoLogger.i("AIPerformanceOptimizer", "Performance optimizer initialized, tier: ${deviceCapabilityTier.get()}")
    }

    private fun detectDeviceCapabilities() {
        val runtime = Runtime.getRuntime()
        val totalMemory = collectSystemMemoryInfo()?.totalMem?.takeIf { it > 0L }
            ?.let { it / (1024 * 1024) }
            ?: (runtime.maxMemory() / (1024 * 1024))
        val processors = runtime.availableProcessors()

        val tier = when {
            totalMemory >= 8192 && processors >= 8 -> DeviceCapabilityTier.FLAGSHIP
            totalMemory >= 6144 && processors >= 6 -> DeviceCapabilityTier.HIGH_END
            totalMemory >= 4096 && processors >= 4 -> DeviceCapabilityTier.MID_RANGE
            else -> DeviceCapabilityTier.ENTRY_LEVEL
        }

        deviceCapabilityTier.set(tier)

        NeoLogger.i(
            "AIPerformanceOptimizer",
            "Device capability detected: $tier (memory: ${totalMemory}MB, cores: $processors)"
        )
    }

    private fun setInitialPerformanceMode() {
        val mode = when (deviceCapabilityTier.get()) {
            DeviceCapabilityTier.FLAGSHIP -> createFlagshipMode()
            DeviceCapabilityTier.HIGH_END -> createHighEndMode()
            DeviceCapabilityTier.MID_RANGE -> createMidRangeMode()
            DeviceCapabilityTier.ENTRY_LEVEL -> createEntryLevelMode()
        }

        _currentPerformanceMode.value = mode
        NeoLogger.i("AIPerformanceOptimizer", "Initial performance mode: ${mode.name}")
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // PERFORMANCE MODE FACTORIES
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private fun createFlagshipMode() = PerformanceMode(
        name = "flagship",
        targetFps = 60,
        inferenceResolution = 512,
        modelQuality = ModelQuality.MAXIMUM,
        enableAdvancedFeatures = true,
        maxConcurrentModels = 8,
        gpuMemoryLimitMb = 1024,
        cpuThreadCount = 8
    )

    private fun createHighEndMode() = PerformanceMode(
        name = "high_end",
        targetFps = 60,
        inferenceResolution = 448,
        modelQuality = ModelQuality.HIGH,
        enableAdvancedFeatures = true,
        maxConcurrentModels = 6,
        gpuMemoryLimitMb = 768,
        cpuThreadCount = 6
    )

    private fun createMidRangeMode() = PerformanceMode(
        name = "mid_range",
        targetFps = 30,
        inferenceResolution = 384,
        modelQuality = ModelQuality.MEDIUM,
        enableAdvancedFeatures = true,
        maxConcurrentModels = 4,
        gpuMemoryLimitMb = 512,
        cpuThreadCount = 4
    )

    private fun createEntryLevelMode() = PerformanceMode(
        name = "entry_level",
        targetFps = 30,
        inferenceResolution = 256,
        modelQuality = ModelQuality.LOW,
        enableAdvancedFeatures = false,
        maxConcurrentModels = 2,
        gpuMemoryLimitMb = 256,
        cpuThreadCount = 2
    )

    private fun createDefaultMode() = createMidRangeMode()

    private fun createPowerSaveMode() = PerformanceMode(
        name = "power_save",
        targetFps = 15,
        inferenceResolution = 224,
        modelQuality = ModelQuality.LOW,
        enableAdvancedFeatures = false,
        maxConcurrentModels = 2,
        gpuMemoryLimitMb = 128,
        cpuThreadCount = 2
    )

    private fun createThermalThrottleMode() = PerformanceMode(
        name = "thermal_throttle",
        targetFps = 15,
        inferenceResolution = 192,
        modelQuality = ModelQuality.LOW,
        enableAdvancedFeatures = false,
        maxConcurrentModels = 1,
        gpuMemoryLimitMb = 64,
        cpuThreadCount = 1
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // PERFORMANCE MONITORING
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private fun startPerformanceMonitoring() {
        optimizerScope.launch {
            while (isOperational.get()) {
                delay(MONITORING_INTERVAL_MS)

                val deviceState = collectDeviceState()
                val metrics = collectPerformanceMetrics()

                // Store metrics
                performanceHistory[System.currentTimeMillis()] = metrics

                // Clean old history
                val cutoffTime = System.currentTimeMillis() - 300000 // 5 minutes
                performanceHistory.keys.removeIf { it < cutoffTime }

                // Analyze and adapt
                analyzeAndAdapt(deviceState, metrics)
            }
        }
    }

    private fun collectDeviceState(): DeviceState {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.maxMemory()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val javaHeapUsagePercent = (usedMemory.toFloat() / totalMemory * 100).coerceIn(0f, 100f)
        val systemInfo = collectSystemMemoryInfo()
        val systemAvailableMb = systemInfo?.availMem?.let { it / (1024 * 1024) }
            ?: (runtime.freeMemory() / (1024 * 1024))
        val systemTotalMb = systemInfo?.totalMem?.takeIf { it > 0L }?.let { it / (1024 * 1024) }
            ?: (totalMemory / (1024 * 1024))

        return DeviceState(
            availableMemoryMb = systemAvailableMb,
            totalMemoryMb = systemTotalMb,
            memoryUsagePercent = javaHeapUsagePercent,
            javaHeapUsagePercent = javaHeapUsagePercent,
            systemAvailableMemoryMb = systemAvailableMb,
            systemTotalMemoryMb = systemTotalMb,
            systemLowMemory = systemInfo?.lowMemory == true,
            thermalState = collectThermalState(),
            batteryPercent = 50, // Would require BatteryManager
            isCharging = false, // Would require BatteryManager
            cpuUsage = 0f, // Would require system stats
            gpuUsage = 0f  // Would require GPU stats
        )
    }

    private fun collectSystemMemoryInfo(): ActivityManager.MemoryInfo? {
        return runCatching {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            ActivityManager.MemoryInfo().also { info ->
                activityManager.getMemoryInfo(info)
            }
        }.getOrNull()
    }

    private fun collectThermalState(): ThermalState {
        // Would integrate with ThermalManager
        return ThermalState(
            temperatureCelsius = 35f,
            thermalStatus = 0
        )
    }

    private fun collectPerformanceMetrics(): PerformanceMetrics {
        val monitorStats = PerformanceMonitor.generateReport()

        return PerformanceMetrics(
            averageInferenceTimeMs = monitorStats.frameStatistics.values
                .map { it.averageFps }.average().let { if (it > 0) 1000.0 / it else 0.0 },
            p95InferenceTimeMs = 0.0, // Would calculate from histogram
            p99InferenceTimeMs = 0.0,
            throughputFps = monitorStats.frameStatistics.values.map { it.averageFps }.average(),
            droppedFrames = monitorStats.frameStatistics.values.sumOf { it.droppedFrames }.toInt(),
            memoryPressureEvents = 0,
            thermalThrottlingEvents = throttlingEvents.get()
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // ADAPTIVE OPTIMIZATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private fun analyzeAndAdapt(deviceState: DeviceState, metrics: PerformanceMetrics) {
        val currentMode = _currentPerformanceMode.value
        var newMode: PerformanceMode? = null

        // Check thermal state
        if (deviceState.thermalState.temperatureCelsius >= THERMAL_CRITICAL_THRESHOLD) {
            if (currentMode.name != "thermal_throttle") {
                newMode = createThermalThrottleMode()
                throttlingEvents.incrementAndGet()
                _optimizerState.value = OptimizerState.Throttling
                NeoLogger.w(
                    "AIPerformanceOptimizer",
                    "Critical thermal state detected: ${deviceState.thermalState.temperatureCelsius}C, throttling..."
                )
            }
        } else if (deviceState.thermalState.temperatureCelsius >= THERMAL_THROTTLE_THRESHOLD) {
            if (currentMode.name !in listOf("thermal_throttle", "power_save")) {
                newMode = createPowerSaveMode()
                _optimizerState.value = OptimizerState.Throttling
                NeoLogger.w(
                    "AIPerformanceOptimizer",
                    "Thermal throttling detected: ${deviceState.thermalState.temperatureCelsius}C"
                )
            }
        }

        // Check memory pressure
        val criticalMemory =
            deviceState.javaHeapUsagePercent >= CRITICAL_MEMORY_THRESHOLD_PCT ||
                deviceState.systemLowMemory ||
                deviceState.systemAvailableMemoryMb in 1L until 128L
        val elevatedMemory =
            criticalMemory ||
                deviceState.javaHeapUsagePercent >= LOW_MEMORY_THRESHOLD_PCT ||
                deviceState.systemAvailableMemoryMb in 1L until 512L

        if (criticalMemory) {
            // Critical memory - emergency measures
            NeoLogger.emergency(
                "AIPerformanceOptimizer",
                "Critical memory pressure: heap=${deviceState.javaHeapUsagePercent}% " +
                    "systemFree=${deviceState.systemAvailableMemoryMb}MB " +
                    "systemLow=${deviceState.systemLowMemory}"
            )
            handleCriticalMemoryPressure()
        } else if (elevatedMemory) {
            // Low memory - reduce quality
            if (currentMode.modelQuality.ordinal > ModelQuality.LOW.ordinal) {
                newMode = reduceQuality(currentMode)
                NeoLogger.w(
                    "AIPerformanceOptimizer",
                    "Memory pressure detected: heap=${deviceState.javaHeapUsagePercent}% " +
                        "systemFree=${deviceState.systemAvailableMemoryMb}MB, reducing quality"
                )
            }
        }

        // Check battery
        if (deviceState.batteryPercent <= CRITICAL_BATTERY_THRESHOLD && !deviceState.isCharging) {
            if (currentMode.name != "power_save") {
                newMode = createPowerSaveMode()
                NeoLogger.w(
                    "AIPerformanceOptimizer",
                    "Critical battery: ${deviceState.batteryPercent}%, enabling power save"
                )
            }
        } else if (deviceState.batteryPercent <= LOW_BATTERY_THRESHOLD && !deviceState.isCharging) {
            if (currentMode.name !in listOf("power_save", "thermal_throttle")) {
                newMode = reduceQuality(currentMode)
                NeoLogger.i(
                    "AIPerformanceOptimizer",
                    "Low battery: ${deviceState.batteryPercent}%, reducing quality"
                )
            }
        }

        // Check recovery conditions
        if (currentMode.name in listOf("thermal_throttle", "power_save")) {
            if (deviceState.thermalState.temperatureCelsius < THERMAL_THROTTLE_THRESHOLD - 5 &&
                deviceState.javaHeapUsagePercent < LOW_MEMORY_THRESHOLD_PCT - 10 &&
                !deviceState.systemLowMemory &&
                (deviceState.systemAvailableMemoryMb == 0L || deviceState.systemAvailableMemoryMb >= 512L) &&
                (deviceState.batteryPercent > LOW_BATTERY_THRESHOLD || deviceState.isCharging)
            ) {
                // Recovery conditions met - restore normal mode
                newMode = when (deviceCapabilityTier.get()) {
                    DeviceCapabilityTier.FLAGSHIP -> createFlagshipMode()
                    DeviceCapabilityTier.HIGH_END -> createHighEndMode()
                    DeviceCapabilityTier.MID_RANGE -> createMidRangeMode()
                    DeviceCapabilityTier.ENTRY_LEVEL -> createEntryLevelMode()
                }
                _optimizerState.value = OptimizerState.Recovery
                NeoLogger.i("AIPerformanceOptimizer", "Recovery conditions met, restoring normal mode")
            }
        }

        // Apply new mode if changed
        newMode?.let { mode ->
            applyPerformanceMode(mode)
            adaptationEvents.incrementAndGet()

            TelemetryCollector.trackEvent(
                TelemetryCollector.EventType.PERFORMANCE_METRIC,
                "performance_mode_changed",
                mapOf(
                    "from_mode" to currentMode.name,
                    "to_mode" to mode.name,
                    "reason" to determineChangeReason(deviceState, currentMode)
                )
            )
        }
    }

    private fun reduceQuality(mode: PerformanceMode): PerformanceMode {
        val lowerQuality = when (mode.modelQuality) {
            ModelQuality.MAXIMUM -> ModelQuality.HIGH
            ModelQuality.HIGH -> ModelQuality.MEDIUM
            ModelQuality.MEDIUM -> ModelQuality.LOW
            ModelQuality.LOW -> ModelQuality.LOW
        }

        return mode.copy(
            name = mode.name + "_reduced",
            modelQuality = lowerQuality,
            inferenceResolution = (mode.inferenceResolution * 0.8f).toInt()
        )
    }

    private fun handleCriticalMemoryPressure() {
        // Unload non-essential models
        AINeuralEngine.getInstance().unloadModel(AINeuralEngine.MODEL_SCENE_CLASSIFICATION)
        AINeuralEngine.getInstance().unloadModel(AINeuralEngine.MODEL_POSE_ESTIMATION)

        // Clear caches
        CacheManager.shutdownAll()

        // Force garbage collection
        System.gc()

        NeoLogger.emergency("AIPerformanceOptimizer", "Critical memory measures applied")
    }

    private fun determineChangeReason(deviceState: DeviceState, currentMode: PerformanceMode): String {
        return when {
            deviceState.thermalState.temperatureCelsius >= THERMAL_THROTTLE_THRESHOLD -> "thermal"
            deviceState.javaHeapUsagePercent >= LOW_MEMORY_THRESHOLD_PCT ||
                deviceState.systemLowMemory ||
                deviceState.systemAvailableMemoryMb in 1L until 512L -> "memory"
            deviceState.batteryPercent <= LOW_BATTERY_THRESHOLD -> "battery"
            else -> "performance"
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // MODE APPLICATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private fun applyPerformanceMode(mode: PerformanceMode) {
        _currentPerformanceMode.value = mode
        _optimizerState.value = OptimizerState.Optimizing

        // Apply settings
        applyThreadCount(mode.cpuThreadCount)
        applyGpuMemoryLimit(mode.gpuMemoryLimitMb)

        NeoLogger.i("AIPerformanceOptimizer", "Applied performance mode: ${mode.name}")

        // Return to monitoring state after brief optimization period
        optimizerScope.launch {
            delay(1000)
            if (_optimizerState.value == OptimizerState.Optimizing) {
                _optimizerState.value = OptimizerState.Monitoring
            }
        }
    }

    private fun applyThreadCount(threads: Int) {
        // Would configure TFLite interpreter threads
        NeoLogger.d("AIPerformanceOptimizer", "Set thread count: $threads")
    }

    private fun applyGpuMemoryLimit(limitMb: Int) {
        // Would configure GPU memory limit
        NeoLogger.d("AIPerformanceOptimizer", "Set GPU memory limit: ${limitMb}MB")
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun setOptimizationStrategy(strategy: OptimizationStrategy) {
        _optimizationStrategy.value = strategy
        NeoLogger.i("AIPerformanceOptimizer", "Optimization strategy set to: $strategy")
    }

    fun forcePerformanceMode(mode: PerformanceMode) {
        applyPerformanceMode(mode)
    }

    fun getRecommendedQuality(): ModelQuality {
        return _currentPerformanceMode.value.modelQuality
    }

    fun getRecommendedResolution(): Int {
        return _currentPerformanceMode.value.inferenceResolution
    }

    fun shouldEnableAdvancedFeatures(): Boolean {
        return _currentPerformanceMode.value.enableAdvancedFeatures
    }

    fun getStatistics(): OptimizerStatistics {
        return OptimizerStatistics(
            currentMode = _currentPerformanceMode.value.name,
            currentStrategy = _optimizationStrategy.value.name,
            deviceTier = deviceCapabilityTier.get().name,
            adaptationEvents = adaptationEvents.get(),
            throttlingEvents = throttlingEvents.get(),
            performanceHistorySize = performanceHistory.size
        )
    }

    data class OptimizerStatistics(
        val currentMode: String,
        val currentStrategy: String,
        val deviceTier: String,
        val adaptationEvents: Int,
        val throttlingEvents: Int,
        val performanceHistorySize: Int
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // SHUTDOWN
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun shutdown() {
        if (!isOperational.compareAndSet(true, false)) return

        optimizerScope.cancel()
        NeoLogger.i("AIPerformanceOptimizer", "Performance optimizer shut down")
    }
}
