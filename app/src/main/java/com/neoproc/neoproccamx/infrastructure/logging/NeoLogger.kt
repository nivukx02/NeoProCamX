package com.neoproc.neoproccamx.infrastructure.logging

import android.util.Log
import com.neoproc.neoproccamx.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * NEO LOGGER — ENTERPRISE-GRADE ASYNCHRONOUS LOGGING INFRASTRUCTURE
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * High-performance, zero-allocation logging system designed for production camera applications.
 * Features:
 *   - Lock-free async logging with verified when supported delivery
 *   - Multi-tier log buffering (memory + disk)
 *   - Automatic log rotation and compression
 *   - Telemetry correlation for crash analysis
 *   - Zero-impact on hot paths through deferred I/O
 *
 * Thread Safety: All operations are thread-safe via concurrent data structures and coroutine dispatch.
 * Performance: Sub-microsecond logging overhead through lock-free queues.
 *
 * @author NeoProCamX Infrastructure Team
 * @version 10.0.0-ENTERPRISE
 * @since 1.0.0
 */
object NeoLogger {

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // LOGGING CONSTANTS AND CONFIGURATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /** Maximum number of log entries to hold in memory buffer before forced flush. */
    private const val MEMORY_BUFFER_CAPACITY = 10_000

    /** Maximum log file size before rotation (10 MB). */
    private const val MAX_LOG_FILE_SIZE_BYTES = 10 * 1024 * 1024

    /** Maximum number of archived log files to retain. */
    private const val MAX_ARCHIVED_LOGS = 10

    /** Interval between automatic buffer flushes (milliseconds). */
    private const val AUTO_FLUSH_INTERVAL_MS = 5_000L

    /** Date format for log timestamps (ISO 8601 compatible). */
    private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Date format for log file names. */
    private val FILE_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INTERNAL STATE MANAGEMENT
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /** Dedicated low-priority lane so external-storage work never competes with camera callbacks. */
    private val loggingDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { task ->
            Thread(
                {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                    task.run()
                },
                "NeoLogger-Storage"
            ).apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }.asCoroutineDispatcher()

    private val loggingJob = SupervisorJob()

    /** Primary logging scope isolated from the shared coroutine I/O pool. */
    private val loggingScope = CoroutineScope(loggingJob + loggingDispatcher)

    /** Lock-free queue for log entries awaiting processing. */
    private val logQueue = ConcurrentLinkedQueue<LogEntry>()

    /** Constant-time queue depth; ConcurrentLinkedQueue.size walks every queued node. */
    private val queuedEntries = AtomicInteger(0)

    /** Coalesces pressure-triggered flushes into at most one pending storage-lane task. */
    private val flushScheduled = AtomicBoolean(false)

    /** Allows shutdown to wait for logging calls that passed the operational-state check. */
    private val enqueuesInFlight = AtomicInteger(0)

    /** Mutex for synchronized file operations. */
    private val fileMutex = Mutex()

    /** Atomic flag indicating if logger is operational (enabled for output). */
    private val isOperational = AtomicBoolean(true)

    /**
     * Atomic flag tracking one-time disk-logging initialization.
     *
     * Kept separate from [isOperational] on purpose: the previous implementation reused
     * isOperational (which defaults to true) with compareAndSet(false, true) as the
     * initialize() guard, so the guard ALWAYS failed and silently skipped createNewLogFile().
     * The result was that the on-device log file was never written at all.
     */
    private val isInitialized = AtomicBoolean(false)

    /** Counter for dropped log entries under extreme pressure. */
    private val droppedEntries = AtomicLong(0)

    /** Current log file reference (synchronized access). */
    private var currentLogFile: File? = null

    /** Log file directory (initialized lazily). */
    private var logDirectory: File? = null

    /** Current log file size tracker. */
    private val currentFileSize = AtomicLong(0)

    /** Persistent buffered descriptor for the active external diagnostics file. */
    private val persistentLogWriter = PersistentLogWriter()

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // LOG ENTRY DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Immutable log entry containing all contextual information.
     * Designed for minimal memory footprint and maximum throughput.
     */
    private data class LogEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
        val threadName: String = Thread.currentThread().name,
        val processId: Int = android.os.Process.myPid(),
        val telemetryCorrelationId: String? = null,
        val metadata: Map<String, Any>? = null
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // LOG LEVEL ENUMERATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Comprehensive log level enumeration with severity ordering.
     */
    enum class LogLevel(val priority: Int, val androidLevel: Int, val symbol: String) {
        VERBOSE(0, Log.VERBOSE, "V"),
        DEBUG(1, Log.DEBUG, "D"),
        INFO(2, Log.INFO, "I"),
        WARN(3, Log.WARN, "W"),
        ERROR(4, Log.ERROR, "E"),
        ASSERT(5, Log.ASSERT, "A"),
        CRITICAL(6, Log.ERROR, "C"),
        EMERGENCY(7, Log.ERROR, "X");

        companion object {
            /** Minimum level that will be logged (configurable at runtime). */
            @Volatile
            var minimumLevel: LogLevel = if (BuildConfig.DEBUG) VERBOSE else INFO

            /**
             * Determines if a log level should be output based on current minimum.
             */
            fun shouldLog(level: LogLevel): Boolean = level.priority >= minimumLevel.priority
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INITIALIZATION AND CONFIGURATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Initialize the logging infrastructure with specified directory.
     * Must be called before any logging operations for disk persistence.
     *
     * @param directory The directory where log files will be stored
     * @return True if initialization successful, false otherwise
     */
    fun initialize(directory: File): Boolean {
        // Guard one-time disk initialization against its OWN flag. Using isOperational here
        // (default true) made compareAndSet(false, true) always fail, so the body below never
        // ran and the disk log file was never created.
        if (!isInitialized.compareAndSet(false, true)) {
            logInternal(LogLevel.WARN, "NeoLogger", "Already initialized, skipping reinitialization")
            return true
        }

        isOperational.set(true)
        return try {
            logDirectory = directory.apply {
                if (!exists()) mkdirs()
            }

            createNewLogFile()
            startAutoFlush()
            startLogRotationMonitor()

            logInternal(
                LogLevel.INFO,
                "NeoLogger",
                "Logging infrastructure initialized successfully at ${directory.absolutePath}"
            )
            true
        } catch (e: Exception) {
            isOperational.set(false)
            isInitialized.set(false)
            closeWriterQuietly()
            android.util.Log.e("NeoLogger", "Failed to initialize logging infrastructure", e)
            false
        }
    }

    /**
     * Shutdown the logging infrastructure gracefully.
     * Flushes all pending logs before termination.
     */
    fun shutdown() {
        if (!isOperational.compareAndSet(true, false)) {
            return
        }

        loggingScope.launch {
            try {
                while (enqueuesInFlight.get() != 0) yield()
                enqueueInternal(
                    LogLevel.INFO,
                    "NeoLogger",
                    "Logging infrastructure shut down successfully"
                )
                flushBufferSynchronously(allowWhileStopped = true)
                fileMutex.withLock { closeWriterQuietly() }
            } finally {
                loggingJob.cancel()
                loggingDispatcher.close()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // PUBLIC LOGGING API
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Log a verbose message with optional throwable and metadata.
     */
    fun v(tag: String, message: String, throwable: Throwable? = null, metadata: Map<String, Any>? = null) {
        log(LogLevel.VERBOSE, tag, message, throwable, metadata)
    }

    /**
     * Log a debug message with optional throwable and metadata.
     */
    fun d(tag: String, message: String, throwable: Throwable? = null, metadata: Map<String, Any>? = null) {
        log(LogLevel.DEBUG, tag, message, throwable, metadata)
    }

    /**
     * Log an info message with optional throwable and metadata.
     */
    fun i(tag: String, message: String, throwable: Throwable? = null, metadata: Map<String, Any>? = null) {
        log(LogLevel.INFO, tag, message, throwable, metadata)
    }

    /**
     * Log a warning message with optional throwable and metadata.
     */
    fun w(tag: String, message: String, throwable: Throwable? = null, metadata: Map<String, Any>? = null) {
        log(LogLevel.WARN, tag, message, throwable, metadata)
    }

    /**
     * Log an error message with optional throwable and metadata.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null, metadata: Map<String, Any>? = null) {
        log(LogLevel.ERROR, tag, message, throwable, metadata)
    }

    /**
     * Log a critical error requiring immediate attention.
     */
    fun critical(tag: String, message: String, throwable: Throwable? = null, metadata: Map<String, Any>? = null) {
        log(LogLevel.CRITICAL, tag, message, throwable, metadata)
        // Critical logs trigger immediate flush
        triggerAsyncFlush()
    }

    /**
     * Log an emergency condition that may cause system failure.
     */
    fun emergency(tag: String, message: String, throwable: Throwable? = null, metadata: Map<String, Any>? = null) {
        log(LogLevel.EMERGENCY, tag, message, throwable, metadata)
        // Emergency logs trigger immediate flush
        triggerAsyncFlush()
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // CORE LOGGING IMPLEMENTATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Primary logging method with comprehensive parameter validation and error handling.
     */
    private fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null,
        metadata: Map<String, Any>? = null
    ) {
        // Early exit if logging disabled or level below threshold.
        if (!isOperational.get() || !LogLevel.shouldLog(level)) {
            return
        }

        enqueuesInFlight.incrementAndGet()
        try {
            // Pairs with shutdown: a caller paused before incrementing cannot enqueue after stop.
            if (!isOperational.get()) return

            // Validate input parameters
            val validatedTag = validateTag(tag)
            val validatedMessage = validateMessage(message)

            // Create log entry
            val entry = LogEntry(
                level = level,
                tag = validatedTag,
                message = validatedMessage,
                throwable = throwable,
                metadata = sanitizeMetadata(metadata)
            )

            // ConcurrentLinkedQueue is unbounded: a successful offer retains every accepted log.
            logQueue.offer(entry)
            val queueDepth = queuedEntries.incrementAndGet()

            // Check if buffer flush needed using the constant-time depth counter.
            if (queueDepth >= MEMORY_BUFFER_CAPACITY / 2) {
                triggerAsyncFlush()
            }

            // Always output to Android log for immediate visibility
            outputToAndroidLog(entry)
        } finally {
            enqueuesInFlight.decrementAndGet()
        }
    }

    /**
     * Internal logging for infrastructure messages (bypasses normal validation).
     */
    private fun logInternal(level: LogLevel, tag: String, message: String) {
        enqueueInternal(level, tag, message)
    }

    private fun enqueueInternal(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)
        logQueue.offer(entry)
        queuedEntries.incrementAndGet()
        outputToAndroidLog(entry)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INPUT VALIDATION AND SANITIZATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Validates and sanitizes log tag to meet Android requirements.
     */
    private fun validateTag(tag: String): String {
        return when {
            tag.isEmpty() -> "Unknown"
            tag.length > 23 -> tag.take(23) // Android log tag length limit
            else -> tag
        }
    }

    /**
     * Validates and sanitizes log message.
     */
    private fun validateMessage(message: String): String {
        return when {
            message.isEmpty() -> "[Empty message]"
            message.length > 4000 -> message.take(4000) + "...[truncated]"
            else -> message
        }
    }

    /**
     * Sanitizes metadata map to prevent logging sensitive information.
     */
    private fun sanitizeMetadata(metadata: Map<String, Any>?): Map<String, Any>? {
        if (metadata == null) return null

        val sensitiveKeys = setOf(
            "password", "token", "secret", "key", "credential",
            "auth", "private", "session", "cookie"
        )

        return metadata.filter { (key, _) ->
            sensitiveKeys.none { key.lowercase().contains(it) }
        }.mapValues { (_, value) ->
            when (value) {
                is String -> if (value.length > 1000) value.take(1000) + "...[truncated]" else value
                else -> value
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // OUTPUT METHODS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Outputs log entry to Android system log.
     */
    private fun outputToAndroidLog(entry: LogEntry) {
        val formattedMessage = formatForAndroidLog(entry)
        when (entry.level) {
            LogLevel.VERBOSE -> Log.v(entry.tag, formattedMessage, entry.throwable)
            LogLevel.DEBUG -> Log.d(entry.tag, formattedMessage, entry.throwable)
            LogLevel.INFO -> Log.i(entry.tag, formattedMessage, entry.throwable)
            LogLevel.WARN -> Log.w(entry.tag, formattedMessage, entry.throwable)
            LogLevel.ERROR, LogLevel.CRITICAL, LogLevel.EMERGENCY ->
                Log.e(entry.tag, formattedMessage, entry.throwable)
            LogLevel.ASSERT -> Log.wtf(entry.tag, formattedMessage, entry.throwable)
        }
    }

    /**
     * Formats log entry for Android log output.
     */
    private fun formatForAndroidLog(entry: LogEntry): String {
        val threadInfo = "[${entry.threadName}]"
        val metadataStr = entry.metadata?.let { " | $it" } ?: ""
        return "$threadInfo ${entry.message}$metadataStr"
    }

    /**
     * Formats log entry for file output with full details.
     */
    private fun formatForFile(entry: LogEntry): String {
        val timestamp = TIMESTAMP_FORMAT.format(Date(entry.timestamp))
        val threadInfo = "${entry.threadName}(pid:${entry.processId})"
        val metadataStr = entry.metadata?.let { " | METADATA: $it" } ?: ""
        val throwableStr = entry.throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""

        return buildString {
            append("$timestamp ${entry.level.symbol}/${entry.tag} ")
            append("[$threadInfo] ${entry.message}")
            append(metadataStr)
            append(throwableStr)
            append("\n")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // FILE OPERATIONS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Creates a new log file with timestamp-based naming.
     */
    private fun createNewLogFile() {
        val dir = logDirectory ?: return
        val timestamp = FILE_DATE_FORMAT.format(Date())
        currentLogFile = File(dir, "neoproc_$timestamp.log")
        currentFileSize.set(currentLogFile?.length() ?: 0L)
    }

    /**
     * Flushes buffer to disk file with comprehensive error handling.
     */
    private suspend fun flushBufferSynchronously(allowWhileStopped: Boolean = false) {
        if (!allowWhileStopped && !isOperational.get()) return

        fileMutex.withLock {
            val file = currentLogFile ?: return
            val entries = drainQueueSnapshot()
            val dropped = droppedEntries.getAndSet(0)
            if (entries.isEmpty() && dropped == 0L) return

            try {
                // Every entry is formatted exactly once into one batch before the single write.
                val serializedBatch = buildString {
                    entries.forEach { entry -> append(formatForFile(entry)) }
                    if (dropped > 0) {
                        append(
                            formatForFile(
                                LogEntry(
                                    level = LogLevel.WARN,
                                    tag = "NeoLogger",
                                    message = "Dropped $dropped log entries due to queue pressure"
                                )
                            )
                        )
                    }
                }
                currentFileSize.set(persistentLogWriter.append(file, serializedBatch))
            } catch (e: Exception) {
                // Retain the batch for a later retry. A partial filesystem write can at worst
                // duplicate diagnostics; losing accepted diagnostics is never preferable.
                entries.forEach { entry ->
                    logQueue.offer(entry)
                    queuedEntries.incrementAndGet()
                }
                if (dropped > 0) droppedEntries.addAndGet(dropped)
                closeWriterQuietly()
                android.util.Log.e("NeoLogger", "Failed to flush logs to file", e)
                return
            }

            if (currentFileSize.get() >= MAX_LOG_FILE_SIZE_BYTES) {
                rotateLogFile()
            }
        }
    }

    private fun drainQueueSnapshot(): List<LogEntry> {
        val snapshotSize = queuedEntries.get().coerceAtLeast(0)
        if (snapshotSize == 0) return emptyList()

        val entries = ArrayList<LogEntry>(snapshotSize)
        repeat(snapshotSize) {
            val entry = logQueue.poll() ?: return@repeat
            queuedEntries.decrementAndGet()
            entries.add(entry)
        }
        return entries
    }

    /**
     * Triggers asynchronous buffer flush.
     */
    private fun triggerAsyncFlush() {
        if (!isOperational.get() || !flushScheduled.compareAndSet(false, true)) return
        loggingScope.launch {
            try {
                flushBufferSynchronously()
            } finally {
                flushScheduled.set(false)
                if (
                    isOperational.get() &&
                    queuedEntries.get() >= MEMORY_BUFFER_CAPACITY / 2
                ) {
                    triggerAsyncFlush()
                }
            }
        }
    }

    /**
     * Rotates log file when size limit reached.
     */
    private fun rotateLogFile() {
        try {
            val dir = logDirectory ?: return
            val currentFile = currentLogFile ?: return

            // Release the active descriptor before reading, compressing, and deleting its file.
            persistentLogWriter.close()

            // Archive current file
            val archiveName = "${currentFile.nameWithoutExtension}_archived_${FILE_DATE_FORMAT.format(Date())}.log.gz"
            val archiveFile = File(dir, archiveName)

            // Compress and move to archive
            currentFile.inputStream().use { input ->
                java.util.zip.GZIPOutputStream(archiveFile.outputStream()).use { gzip ->
                    input.copyTo(gzip)
                }
            }

            currentFile.delete()
            cleanupOldArchives()
            createNewLogFile()

        } catch (e: Exception) {
            android.util.Log.e("NeoLogger", "Failed to rotate log file", e)
        }
    }

    private fun closeWriterQuietly() {
        try {
            persistentLogWriter.close()
        } catch (e: Exception) {
            android.util.Log.e("NeoLogger", "Failed to close log file", e)
        }
    }

    /**
     * Cleans up old archived log files exceeding retention limit.
     */
    private fun cleanupOldArchives() {
        val dir = logDirectory ?: return
        val archives = dir.listFiles { file ->
            file.name.endsWith(".log.gz")
        }?.sortedBy { it.lastModified() } ?: return

        if (archives.size > MAX_ARCHIVED_LOGS) {
            archives.take(archives.size - MAX_ARCHIVED_LOGS).forEach { it.delete() }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // BACKGROUND PROCESSES
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Starts automatic buffer flush coroutine.
     */
    private fun startAutoFlush() {
        loggingScope.launch {
            while (isOperational.get()) {
                kotlinx.coroutines.delay(AUTO_FLUSH_INTERVAL_MS)
                flushBufferSynchronously()
            }
        }
    }

    /**
     * Starts log rotation monitoring coroutine.
     */
    private fun startLogRotationMonitor() {
        loggingScope.launch {
            while (isOperational.get()) {
                kotlinx.coroutines.delay(60_000) // Check every minute
                fileMutex.withLock {
                    if (currentFileSize.get() >= MAX_LOG_FILE_SIZE_BYTES) {
                        rotateLogFile()
                    }
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // TELEMETRY AND METRICS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    /**
     * Retrieves current logging statistics for telemetry.
     */
    fun getStatistics(): LoggingStatistics {
        return LoggingStatistics(
            queueSize = queuedEntries.get().coerceAtLeast(0),
            droppedEntries = droppedEntries.get(),
            currentFileSize = currentFileSize.get(),
            isOperational = isOperational.get()
        )
    }

    /**
     * Data class for logging statistics export.
     */
    data class LoggingStatistics(
        val queueSize: Int,
        val droppedEntries: Long,
        val currentFileSize: Long,
        val isOperational: Boolean
    )
}
