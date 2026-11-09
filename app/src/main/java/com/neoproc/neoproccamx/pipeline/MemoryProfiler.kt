package com.neoproc.neoproccamx.pipeline

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import kotlin.math.roundToInt

class MemoryProfiler(private val context: Context) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val maxMemory = Runtime.getRuntime().maxMemory()
    
    data class MemoryStats(
        val javaHeapUsageMb: Int,
        val totalUsageMb: Int,
        val utilizationPercent: Int,
        val pssKb: Long,
        val oomReserveFreeMb: Int,
        val javaHeapMaxMb: Int,
        val javaHeapUtilizationPercent: Int,
        val nativePssMb: Int,
        val systemTotalMb: Int,
        val systemLowMemory: Boolean,
        val warningLevel: WarningLevel
    ) {
        enum class WarningLevel {
            NORMAL, ELEVATED, CRITICAL
        }
    }

    fun captureSnapshot(): MemoryStats {
        val runtime = Runtime.getRuntime()
        val javaHeapUsage = ((runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)).roundToInt()
        val javaHeapMaxMb = (maxMemory / (1024.0 * 1024.0)).roundToInt().coerceAtLeast(1)
        val javaHeapUtilization =
            ((javaHeapUsage.toDouble() / javaHeapMaxMb.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
        
        val pssKb = try {
            Debug.getPss()
        } catch (e: Exception) {
            0L
        }
        
        val pssHeapMb = (pssKb / 1024).toInt()
        val totalUsage = maxOf(javaHeapUsage, pssHeapMb)
        
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val oomReserveFree = (memInfo.availMem / (1024.0 * 1024.0)).roundToInt()
        val systemTotalMb = (memInfo.totalMem / (1024.0 * 1024.0)).roundToInt()
        
        val warningLevel = MemoryPressurePolicy.classify(
            javaHeapUtilizationPercent = javaHeapUtilization,
            systemAvailableMb = oomReserveFree,
            systemLowMemory = memInfo.lowMemory,
            nativePssMb = pssHeapMb,
            systemTotalMb = systemTotalMb
        )
        
        return MemoryStats(
            javaHeapUsageMb = javaHeapUsage,
            totalUsageMb = totalUsage,
            utilizationPercent = javaHeapUtilization,
            pssKb = pssKb,
            oomReserveFreeMb = oomReserveFree,
            javaHeapMaxMb = javaHeapMaxMb,
            javaHeapUtilizationPercent = javaHeapUtilization,
            nativePssMb = pssHeapMb,
            systemTotalMb = systemTotalMb,
            systemLowMemory = memInfo.lowMemory,
            warningLevel = warningLevel
        )
    }

    fun estimateRecordingDurationMs(targetBitrateMbps: Int): Long {
        val snapshot = captureSnapshot()
        val freeMemory = maxMemory - (snapshot.javaHeapUsageMb * 1024 * 1024L)
        val bufferSize = freeMemory / 2L
        val bitrate = targetBitrateMbps * 1_000_000L / 8L
        return if (bitrate > 0) (bufferSize / bitrate) * 1000L else Long.MAX_VALUE
    }

    fun resolveMemoryWarning(
        recordingActive: Boolean,
        snapshot: MemoryStats? = null
    ): String? {
        // Debug.getPss() and ActivityManager.getMemoryInfo() can take multiple display frames on
        // OEM builds. Callers on latency-sensitive threads pass a snapshot captured on Dispatchers.IO.
        if (!recordingActive) return null
        val stats = snapshot ?: captureSnapshot()

        return when (stats.warningLevel) {
            MemoryStats.WarningLevel.CRITICAL ->
                "CRITICAL: Java heap ${stats.javaHeapUtilizationPercent}% (${stats.javaHeapUsageMb}/${stats.javaHeapMaxMb}MB) | " +
                    "system free ${stats.oomReserveFreeMb}MB | PSS ${stats.nativePssMb}MB | Recording may fail"
            MemoryStats.WarningLevel.ELEVATED ->
                "Memory pressure: Java heap ${stats.javaHeapUtilizationPercent}% | " +
                    "system free ${stats.oomReserveFreeMb}MB | PSS ${stats.nativePssMb}MB"
            MemoryStats.WarningLevel.NORMAL -> null
        }
    }

    fun isMemoryWarning(message: String?): Boolean =
        message != null && (message.startsWith("CRITICAL:") || message.startsWith("Memory pressure"))
}

internal object MemoryPressurePolicy {
    private const val JAVA_HEAP_ELEVATED_PERCENT = 85
    private const val JAVA_HEAP_CRITICAL_PERCENT = 95
    private const val SYSTEM_ELEVATED_FREE_MB = 512
    private const val SYSTEM_CRITICAL_FREE_MB = 128
    // Process PSS as a percent of total device RAM. Relative (not absolute MB) so
    // large recording buffers on high-RAM devices do not false-trigger; only fires
    // when this single process dominates RAM and is a prime low-memory-killer target.
    private const val PSS_ELEVATED_FRACTION_PERCENT = 55
    private const val PSS_CRITICAL_FRACTION_PERCENT = 75

    fun classify(
        javaHeapUtilizationPercent: Int,
        systemAvailableMb: Int,
        systemLowMemory: Boolean,
        nativePssMb: Int = 0,
        systemTotalMb: Int = 0
    ): MemoryProfiler.MemoryStats.WarningLevel {
        val heap = javaHeapUtilizationPercent.coerceIn(0, 100)
        val available = systemAvailableMb.coerceAtLeast(0)
        // Native/off-heap (graphics, ImageReader, MediaCodec, EGL/Vulkan, direct buffers)
        // pressure — the dominant real memory cost during PRO recording — expressed as a
        // fraction of total device RAM so the signal scales across devices.
        val pssFractionPercent =
            if (systemTotalMb > 0 && nativePssMb > 0)
                ((nativePssMb.toDouble() / systemTotalMb.toDouble()) * 100.0).roundToInt()
            else 0
        return when {
            heap >= JAVA_HEAP_CRITICAL_PERCENT ||
                systemLowMemory ||
                available in 1 until SYSTEM_CRITICAL_FREE_MB ||
                pssFractionPercent >= PSS_CRITICAL_FRACTION_PERCENT ->
                MemoryProfiler.MemoryStats.WarningLevel.CRITICAL
            heap >= JAVA_HEAP_ELEVATED_PERCENT ||
                available in 1 until SYSTEM_ELEVATED_FREE_MB ||
                pssFractionPercent >= PSS_ELEVATED_FRACTION_PERCENT ->
                MemoryProfiler.MemoryStats.WarningLevel.ELEVATED
            else -> MemoryProfiler.MemoryStats.WarningLevel.NORMAL
        }
    }
}

/** Maps measured process/system memory into the normalized adaptive-pipeline resource signal. */
internal object RuntimeMemoryPressureSignalPolicy {
    private const val BYTES_PER_MEBIBYTE = 1024L * 1024L

    fun pressure(stats: MemoryProfiler.MemoryStats): Float {
        val heapPressure = stats.javaHeapUtilizationPercent.coerceIn(0, 100) / 100f
        val systemPressure = if (stats.systemTotalMb > 0) {
            1f - stats.oomReserveFreeMb.coerceIn(0, stats.systemTotalMb).toFloat() /
                stats.systemTotalMb.toFloat()
        } else {
            0f
        }
        val processShare = if (stats.systemTotalMb > 0) {
            stats.nativePssMb.coerceAtLeast(0).toFloat() / stats.systemTotalMb.toFloat()
        } else {
            0f
        }
        val warningFloor = when (stats.warningLevel) {
            MemoryProfiler.MemoryStats.WarningLevel.NORMAL -> 0f
            MemoryProfiler.MemoryStats.WarningLevel.ELEVATED -> 0.80f
            MemoryProfiler.MemoryStats.WarningLevel.CRITICAL -> 0.93f
        }
        return maxOf(heapPressure, systemPressure, processShare, warningFloor).coerceIn(0f, 1f)
    }

    fun usedBytes(stats: MemoryProfiler.MemoryStats): Long =
        stats.totalUsageMb.coerceAtLeast(0).toLong().let { usageMb ->
            if (usageMb > Long.MAX_VALUE / BYTES_PER_MEBIBYTE) Long.MAX_VALUE
            else usageMb * BYTES_PER_MEBIBYTE
        }
}
