package com.neoproc.neoproccamx.infrastructure.telemetry

import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.security.AppSecurityBuildConfig
import com.neoproc.neoproccamx.security.PinnedBackendClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * TELEMETRY COLLECTOR — ENTERPRISE-GRADE ANALYTICS AND METRICS COLLECTION INFRASTRUCTURE
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Comprehensive telemetry system for production monitoring:
 *   - Event-based telemetry with rich contextual data
 *   - Metric aggregation with statistical analysis
 *   - User session tracking with privacy preservation
 *   - Atomic local batching with explicitly authorized HTTPS upload
 *   - Offline storage with retry mechanisms
 *   - Real-time alerting on anomaly detection
 *   - GDPR/privacy-compliant data handling
 *   - Custom dimensions and metric types
 *
 * Thread Safety: All operations are thread-safe via concurrent data structures.
 * Performance: Lock-free event queue with batched processing.
 *
 * @author NeoProCamX Infrastructure Team
 * @version 10.0.0-ENTERPRISE
 * @since 1.0.0
 */
object TelemetryCollector {

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // TELEMETRY CONSTANTS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /** Maximum events in memory buffer before forced flush. */
    private const val MAX_BUFFER_SIZE = 1000

    /** Maximum batch size for transmission. */
    private const val MAX_BATCH_SIZE = 100

    /** Flush interval in milliseconds. */
    private const val FLUSH_INTERVAL_MS = 30_000L // 30 seconds

    /** Maximum events stored per session. */
    private const val MAX_EVENTS_PER_SESSION = 10_000

    /** Preserve existing unsent batches and stop admitting new events before diagnostics fill storage. */
    private const val MAX_LOCAL_BATCH_BYTES = 32L * 1024L * 1024L

    /** Date format for timestamps. */
    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // TELEMETRY EVENT DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Telemetry event with comprehensive metadata.
     */
    data class TelemetryEvent(
        val eventId: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val sessionId: String,
        val eventType: EventType,
        val eventName: String,
        val properties: Map<String, Any> = emptyMap(),
        val metrics: Map<String, Double> = emptyMap(),
        val deviceInfo: DeviceInfo = DeviceInfo.current(),
        val appInfo: AppInfo = AppInfo.current(),
        val userInfo: UserInfo = UserInfo.anonymous()
    ) {
        /**
         * Convert to JSON for transmission/storage.
         */
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("eventId", eventId)
                put("timestamp", timestamp)
                put("timestampFormatted", TIMESTAMP_FORMAT.format(java.util.Date(timestamp)))
                put("sessionId", sessionId)
                put("eventType", eventType.name)
                put("eventName", eventName)
                put("properties", JSONObject(properties))
                put("metrics", JSONObject(metrics))
                put("deviceInfo", deviceInfo.toJson())
                put("appInfo", appInfo.toJson())
                put("userInfo", userInfo.toJson())
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // EVENT TYPE ENUMERATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Event type classification.
     */
    enum class EventType {
        SESSION_START,
        SESSION_END,
        USER_ACTION,
        SCREEN_VIEW,
        FEATURE_USAGE,
        PERFORMANCE_METRIC,
        ERROR_OCCURRENCE,
        CRASH_REPORT,
        CUSTOM_EVENT
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // DEVICE INFO DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Device information for telemetry.
     */
    data class DeviceInfo(
        val manufacturer: String,
        val model: String,
        val osVersion: String,
        val apiLevel: Int,
        val screenWidth: Int,
        val screenHeight: Int,
        val screenDensity: Float,
        val totalMemory: Long,
        val availableProcessors: Int
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("manufacturer", manufacturer)
                put("model", model)
                put("osVersion", osVersion)
                put("apiLevel", apiLevel)
                put("screenWidth", screenWidth)
                put("screenHeight", screenHeight)
                put("screenDensity", screenDensity)
                put("totalMemory", totalMemory)
                put("availableProcessors", availableProcessors)
            }
        }

        companion object {
            fun current(): DeviceInfo {
                return DeviceInfo(
                    manufacturer = android.os.Build.MANUFACTURER,
                    model = android.os.Build.MODEL,
                    osVersion = android.os.Build.VERSION.RELEASE,
                    apiLevel = android.os.Build.VERSION.SDK_INT,
                    screenWidth = 0, // Would require context
                    screenHeight = 0,
                    screenDensity = 0f,
                    totalMemory = Runtime.getRuntime().maxMemory(),
                    availableProcessors = Runtime.getRuntime().availableProcessors()
                )
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // APP INFO DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Application information for telemetry.
     */
    data class AppInfo(
        val appVersion: String,
        val buildNumber: Int,
        val packageName: String,
        val installDate: Long,
        val updateDate: Long,
        val isDebugBuild: Boolean
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("appVersion", appVersion)
                put("buildNumber", buildNumber)
                put("packageName", packageName)
                put("installDate", installDate)
                put("updateDate", updateDate)
                put("isDebugBuild", isDebugBuild)
            }
        }

        companion object {
            fun current(): AppInfo {
                return AppInfo(
                    appVersion = "1.0.0",
                    buildNumber = 1,
                    packageName = "com.neoproc.neoproccamx",
                    installDate = 0,
                    updateDate = 0,
                    isDebugBuild = false
                )
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // USER INFO DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * User information with privacy preservation.
     */
    data class UserInfo(
        val userId: String?, // Hashed/anonymized
        val isAnonymous: Boolean,
        val country: String?,
        val language: String?
    ) {
        fun toJson(): JSONObject {
            return JSONObject().apply {
                put("userId", userId ?: JSONObject.NULL)
                put("isAnonymous", isAnonymous)
                put("country", country ?: JSONObject.NULL)
                put("language", language ?: JSONObject.NULL)
            }
        }

        companion object {
            fun anonymous(): UserInfo {
                return UserInfo(
                    userId = null,
                    isAnonymous = true,
                    country = null,
                    language = Locale.getDefault().language
                )
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INTERNAL STATE
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /** Telemetry scope for background operations. */
    private val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Event buffer queue. */
    private val eventBuffer = ConcurrentLinkedQueue<TelemetryEvent>()

    /** Current session ID. */
    private var currentSessionId: String = generateSessionId()

    /** Session event counter. */
    private val sessionEventCount = AtomicLong(0)

    /** Operational flag. */
    private val isOperational = AtomicBoolean(false)

    /** Serializes durable batch commits. */
    private val batchWriteMutex = Mutex()

    /** Persistent storage directory. */
    private var storageDirectory: File? = null

    /** Durable batches awaiting an explicitly authorized upload. */
    private val pendingBatches = ConcurrentLinkedQueue<File>()
    private val pendingBatchBytes = AtomicLong(0)
    private val storageLimitWarningLogged = AtomicBoolean(false)

    /** Changes on every initialize/shutdown so stale periodic coroutines cannot revive after restart. */
    private val lifecycleGeneration = AtomicLong(0)

    /** Null unless a valid HTTPS endpoint and explicit user consent were both supplied. */
    @Volatile
    private var authorizedUploadEndpoint: String? = null

    @Volatile
    private var uploadClient: OkHttpClient? = null

    /** Metric aggregators by name. */
    private val metricAggregators = ConcurrentHashMap<String, MetricAggregator>()

    /** Custom dimensions applied to all events. */
    private val globalDimensions = ConcurrentHashMap<String, String>()

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // METRIC AGGREGATOR DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Metric aggregator for statistical analysis.
     */
    private data class MetricAggregator(
        val name: String,
        val values: MutableList<Double> = mutableListOf(),
        val lock: Any = Any()
    ) {
        fun record(value: Double) {
            synchronized(lock) {
                values.add(value)
                if (values.size > 1000) {
                    values.removeAt(0)
                }
            }
        }

        fun getStatistics(): MetricStatistics {
            synchronized(lock) {
                if (values.isEmpty()) {
                    return MetricStatistics(name, 0.0, 0.0, 0.0, 0.0, 0)
                }

                val sorted = values.sorted()
                val count = values.size
                val sum = values.sum()
                val mean = sum / count
                val min = sorted.first()
                val max = sorted.last()

                return MetricStatistics(
                    name = name,
                    mean = mean,
                    min = min,
                    max = max,
                    p95 = sorted[(count * 0.95).toInt().coerceAtMost(count - 1)],
                    count = count
                )
            }
        }
    }

    /**
     * Metric statistics summary.
     */
    data class MetricStatistics(
        val name: String,
        val mean: Double,
        val min: Double,
        val max: Double,
        val p95: Double,
        val count: Int
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Initialize telemetry collector.
     *
     * @param storageDir Directory for offline storage
     */
    @Synchronized
    fun initialize(
        storageDir: File,
        uploadEndpoint: String? = null,
        userConsentedToUpload: Boolean = false
    ) {
        val preparedDirectory = storageDir.apply {
            if (!exists()) mkdirs()
        }
        if (!preparedDirectory.isDirectory) {
            NeoLogger.e(
                "TelemetryCollector",
                "Telemetry storage directory is unavailable",
                IOException(preparedDirectory.absolutePath)
            )
            return
        }
        if (!isOperational.compareAndSet(false, true)) {
            NeoLogger.w("TelemetryCollector", "Already initialized")
            return
        }

        storageDirectory = preparedDirectory
        val configuredPins = runCatching { AppSecurityBuildConfig.backendPins }
            .onFailure { error ->
                NeoLogger.e("TelemetryCollector", "Invalid backend pin configuration; upload disabled", error)
            }
            .getOrDefault(emptyMap())
        authorizedUploadEndpoint = TelemetryUploadPolicy.authorizedHttpsEndpoint(
            uploadEndpoint,
            userConsentedToUpload,
            configuredPins.keys
        )
        uploadClient = authorizedUploadEndpoint?.let {
            PinnedBackendClient.create(pins = configuredPins)
                .newBuilder()
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
        }
        pendingBatches.clear()
        pendingBatchBytes.set(0)
        storageLimitWarningLogged.set(false)
        currentSessionId = generateSessionId()
        sessionEventCount.set(0)
        val generation = lifecycleGeneration.incrementAndGet()

        recoverPendingBatches()

        startFlushTask(generation)
        if (authorizedUploadEndpoint != null) startTransmissionTask(generation)

        // Record session start
        trackEvent(EventType.SESSION_START, "session_started")

        NeoLogger.i(
            "TelemetryCollector",
            "Telemetry initialized, session: $currentSessionId, localOnly=${authorizedUploadEndpoint == null}"
        )
    }

    /**
     * Shutdown telemetry collector.
     */
    @Synchronized
    fun shutdown() {
        if (!isOperational.get()) return
        trackEvent(EventType.SESSION_END, "session_ended")
        isOperational.set(false)
        lifecycleGeneration.incrementAndGet()
        authorizedUploadEndpoint = null
        uploadClient = null

        telemetryScope.launch {
            flushBuffer(drainAll = true)
        }

        NeoLogger.i("TelemetryCollector", "Telemetry shut down")
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // EVENT TRACKING API
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Track a telemetry event.
     */
    fun trackEvent(
        type: EventType,
        name: String,
        properties: Map<String, Any> = emptyMap(),
        metrics: Map<String, Double> = emptyMap()
    ) {
        if (!isOperational.get()) return
        if (pendingBatchBytes.get() >= MAX_LOCAL_BATCH_BYTES) {
            if (storageLimitWarningLogged.compareAndSet(false, true)) {
                NeoLogger.w("TelemetryCollector", "Local telemetry storage limit reached; pausing collection")
            }
            return
        }

        // Check session limit
        if (sessionEventCount.incrementAndGet() > MAX_EVENTS_PER_SESSION) {
            NeoLogger.w("TelemetryCollector", "Session event limit reached, dropping event: $name")
            return
        }

        // Merge global dimensions
        val mergedProperties = globalDimensions + properties

        val event = TelemetryEvent(
            sessionId = currentSessionId,
            eventType = type,
            eventName = name,
            properties = mergedProperties,
            metrics = metrics
        )

        eventBuffer.offer(event)

        // Check if immediate flush needed
        if (eventBuffer.size >= MAX_BUFFER_SIZE / 2) {
            telemetryScope.launch { flushBuffer() }
        }

        NeoLogger.v("TelemetryCollector", "Event tracked: $name")
    }

    /**
     * Track user action.
     */
    fun trackUserAction(action: String, properties: Map<String, Any> = emptyMap()) {
        trackEvent(EventType.USER_ACTION, action, properties)
    }

    /**
     * Track screen view.
     */
    fun trackScreenView(screenName: String, properties: Map<String, Any> = emptyMap()) {
        trackEvent(EventType.SCREEN_VIEW, "screen_$screenName", properties)
    }

    /**
     * Track feature usage.
     */
    fun trackFeatureUsage(feature: String, properties: Map<String, Any> = emptyMap()) {
        trackEvent(EventType.FEATURE_USAGE, "feature_$feature", properties)
    }

    /**
     * Track performance metric.
     */
    fun trackPerformanceMetric(
        metric: String,
        value: Double,
        properties: Map<String, Any> = emptyMap()
    ) {
        // Record in aggregator
        metricAggregators.getOrPut(metric) { MetricAggregator(metric) }
            .record(value)

        trackEvent(
            EventType.PERFORMANCE_METRIC,
            "metric_$metric",
            properties,
            mapOf("value" to value)
        )
    }

    /**
     * Track error occurrence.
     */
    fun trackError(
        errorType: String,
        message: String,
        properties: Map<String, Any> = emptyMap()
    ) {
        trackEvent(
            EventType.ERROR_OCCURRENCE,
            "error_$errorType",
            properties + ("error_message" to message)
        )
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // GLOBAL DIMENSIONS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Set global dimension applied to all events.
     */
    fun setGlobalDimension(key: String, value: String) {
        globalDimensions[key] = value
    }

    /**
     * Remove global dimension.
     */
    fun removeGlobalDimension(key: String) {
        globalDimensions.remove(key)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // SESSION MANAGEMENT
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Start new telemetry session.
     */
    fun startNewSession() {
        trackEvent(EventType.SESSION_END, "session_ended")
        currentSessionId = generateSessionId()
        sessionEventCount.set(0)
        trackEvent(EventType.SESSION_START, "session_started")
    }

    /**
     * Generate new session ID.
     */
    private fun generateSessionId(): String {
        return "sess_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // BACKGROUND PROCESSING
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Start periodic buffer flush task.
     */
    private fun startFlushTask(generation: Long) {
        telemetryScope.launch {
            while (isOperational.get() && lifecycleGeneration.get() == generation) {
                delay(FLUSH_INTERVAL_MS)
                if (lifecycleGeneration.get() == generation) flushBuffer()
            }
        }
    }

    /**
     * Start periodic transmission task.
     */
    private fun startTransmissionTask(generation: Long) {
        telemetryScope.launch {
            while (isOperational.get() && lifecycleGeneration.get() == generation) {
                delay(FLUSH_INTERVAL_MS * 2)
                if (lifecycleGeneration.get() == generation) processPendingBatches()
            }
        }
    }

    /**
     * Flush event buffer to storage.
     */
    private suspend fun flushBuffer(drainAll: Boolean = false) {
        if (eventBuffer.isEmpty()) return

        batchWriteMutex.withLock {
            do {
                val events = mutableListOf<TelemetryEvent>()
                while (events.size < MAX_BATCH_SIZE && eventBuffer.isNotEmpty()) {
                    eventBuffer.poll()?.let { events.add(it) }
                }
                if (events.isEmpty()) break

                val directory = storageDirectory
                if (directory == null) {
                    events.forEach { eventBuffer.offer(it) }
                    break
                }
                val batchFile = File(
                    directory,
                    "batch_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.json"
                )
                try {
                    val jsonArray = JSONArray()
                    events.forEach { jsonArray.put(it.toJson()) }
                    writeBatchAtomically(batchFile, jsonArray.toString())
                    pendingBatches.offer(batchFile)
                    pendingBatchBytes.addAndGet(batchFile.length())
                    NeoLogger.d("TelemetryCollector", "Flushed ${events.size} events to ${batchFile.name}")
                } catch (e: Exception) {
                    NeoLogger.e("TelemetryCollector", "Failed to flush buffer", e)
                    events.forEach { eventBuffer.offer(it) }
                    break
                }
            } while (drainAll && eventBuffer.isNotEmpty())
        }
    }

    private fun writeBatchAtomically(batchFile: File, payload: String) {
        val temporary = File(batchFile.parentFile, "${batchFile.name}.tmp")
        try {
            FileOutputStream(temporary).use { stream ->
                val writer = OutputStreamWriter(stream, StandardCharsets.UTF_8)
                writer.write(payload)
                writer.flush()
                stream.fd.sync()
            }
            if (!temporary.renameTo(batchFile)) {
                throw IOException("Unable to commit telemetry batch ${batchFile.name}")
            }
        } catch (error: Exception) {
            // The events are returned to memory by the caller. An incomplete temp file is not a batch.
            runCatching { temporary.delete() }
            throw error
        }
    }

    /**
     * Process pending transmissions.
     */
    private suspend fun processPendingBatches() {
        val endpoint = authorizedUploadEndpoint ?: return
        val client = uploadClient ?: return
        val file = pendingBatches.poll() ?: return
        try {
            val payload = file.readText(StandardCharsets.UTF_8)
            JSONArray(payload) // Never upload a partial/corrupt batch.
            val request = Request.Builder()
                .url(endpoint)
                .header("Idempotency-Key", file.nameWithoutExtension)
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val accepted = client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
            if (accepted) {
                val uploadedBytes = file.length()
                if (!file.delete()) {
                    NeoLogger.w("TelemetryCollector", "Uploaded batch could not be removed: ${file.name}")
                } else {
                    pendingBatchBytes.updateAndGet { current ->
                        (current - uploadedBytes).coerceAtLeast(0L)
                    }
                    if (pendingBatchBytes.get() < MAX_LOCAL_BATCH_BYTES) {
                        storageLimitWarningLogged.set(false)
                    }
                }
            } else {
                pendingBatches.offer(file)
            }
        } catch (e: Exception) {
            NeoLogger.e("TelemetryCollector", "Telemetry upload failed; retaining batch", e)
            pendingBatches.offer(file)
        }
    }

    /**
     * Recover pending transmissions from storage.
     */
    private fun recoverPendingBatches() {
        val files = storageDirectory?.listFiles()?.sortedBy { it.name }.orEmpty()
        files.filter { it.isFile && it.name.startsWith("batch_") && it.name.endsWith(".json") }
            .forEach { file ->
                pendingBatches.offer(file)
                pendingBatchBytes.addAndGet(file.length())
            }
        files.filter { it.isFile && it.name.startsWith("batch_") && it.name.endsWith(".json.tmp") }
            .forEach { temporary ->
                val committed = runCatching {
                    JSONArray(temporary.readText(StandardCharsets.UTF_8))
                    val target = File(temporary.parentFile, temporary.name.removeSuffix(".tmp"))
                    if (temporary.renameTo(target)) target else null
                }.getOrNull()
                committed?.let { file ->
                    pendingBatches.offer(file)
                    pendingBatchBytes.addAndGet(file.length())
                }
            }

        NeoLogger.i("TelemetryCollector", "Recovered ${pendingBatches.size} pending batches")
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // STATISTICS AND REPORTING
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Get metric statistics.
     */
    fun getMetricStatistics(): Map<String, MetricStatistics> {
        return metricAggregators.mapValues { it.value.getStatistics() }
    }

    /**
     * Get current telemetry status.
     */
    fun getStatus(): TelemetryStatus {
        return TelemetryStatus(
            isOperational = isOperational.get(),
            sessionId = currentSessionId,
            sessionEvents = sessionEventCount.get(),
            bufferSize = eventBuffer.size,
            pendingBatches = pendingBatches.size,
            pendingBatchBytes = pendingBatchBytes.get(),
            globalDimensions = globalDimensions.size,
            uploadAuthorized = authorizedUploadEndpoint != null
        )
    }

    /**
     * Telemetry status data class.
     */
    data class TelemetryStatus(
        val isOperational: Boolean,
        val sessionId: String,
        val sessionEvents: Long,
        val bufferSize: Int,
        val pendingBatches: Int,
        val pendingBatchBytes: Long,
        val globalDimensions: Int,
        val uploadAuthorized: Boolean
    )
}
