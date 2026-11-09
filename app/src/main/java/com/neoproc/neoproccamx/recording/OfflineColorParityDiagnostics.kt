package com.neoproc.neoproccamx.recording

import android.util.Log
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** A real, timestamped set of measured CIELAB patches from one presentation surface. */
internal data class ColorParityPatchSet(
    val producer: String,
    val frameId: Long,
    val sensorTimestampNs: Long,
    val patchesLab: List<FloatArray>
)

internal data class OfflineColorParityRequest(
    val recordingId: String,
    val outputDirectory: File,
    val preview: ColorParityPatchSet,
    val record: ColorParityPatchSet,
    val export: ColorParityPatchSet,
    /** Association is timestamp based; a caller must declare the maximum legitimate capture skew. */
    val maximumTimestampSkewNs: Long
)

internal data class OfflineColorParityReport(
    val recordingId: String,
    val measured: Boolean,
    val reason: String,
    val maximumTimestampSkewNs: Long,
    val observedTimestampSkewNs: Long,
    val measurement: TriSurfaceParityMeasurement
) {
    fun toJson(): String = buildString(768) {
        append('{')
        append("\"schema\":\"neoprocamx.offline-color-parity.v1\",")
        append("\"recordingId\":\"").append(recordingId.jsonEscaped()).append("\",")
        append("\"measured\":").append(measured).append(',')
        append("\"reason\":\"").append(reason.jsonEscaped()).append("\",")
        append("\"maximumTimestampSkewNs\":").append(maximumTimestampSkewNs).append(',')
        append("\"observedTimestampSkewNs\":").append(observedTimestampSkewNs).append(',')
        append("\"withinTolerance\":").append(measurement.withinTolerance).append(',')
        append("\"previewVsRecordMean\":").append(measurement.previewVsRecordMean.jsonNumber()).append(',')
        append("\"previewVsRecordMax\":").append(measurement.previewVsRecordMax.jsonNumber()).append(',')
        append("\"previewVsExportMean\":").append(measurement.previewVsExportMean.jsonNumber()).append(',')
        append("\"previewVsExportMax\":").append(measurement.previewVsExportMax.jsonNumber()).append(',')
        append("\"recordVsExportMean\":").append(measurement.recordVsExportMean.jsonNumber()).append(',')
        append("\"recordVsExportMax\":").append(measurement.recordVsExportMax.jsonNumber()).append(',')
        append("\"worstMean\":").append(measurement.worstMean.jsonNumber()).append(',')
        append("\"worstMax\":").append(measurement.worstMax.jsonNumber())
        append('}')
    }

    private fun String.jsonEscaped(): String = buildString(length + 8) {
        for (character in this@jsonEscaped) {
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }

    private fun Double.jsonNumber(): String =
        if (isFinite()) String.format(Locale.US, "%.6f", this) else "null"
}

internal enum class OfflineColorParityAdmission {
    ACCEPTED,
    DISABLED,
    OVERLOADED,
    INVALID
}

/**
 * Offline-only preview/record/export color parity. Admission is non-blocking and bounded; its worker
 * has no live camera, encoder, GPU or storage-queue dependency and times out when idle. Only real Lab
 * patch measurements are accepted. Missing, malformed or temporally unrelated inputs produce an
 * explicit NOT_PROVEN report rather than a synthetic pass.
 */
internal object OfflineColorParityDiagnostics {
    private const val MAX_QUEUED_REPORTS = 2
    private const val THREAD_KEEP_ALIVE_SECONDS = 15L
    private val executor = ThreadPoolExecutor(
        0,
        1,
        THREAD_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUED_REPORTS),
        { runnable ->
            Thread(runnable, "OfflineColorParity").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        },
        ThreadPoolExecutor.AbortPolicy()
    ).apply { allowCoreThreadTimeOut(true) }

    fun submit(request: OfflineColorParityRequest): OfflineColorParityAdmission {
        if (!TriSurfaceParityFlag.enabled) return OfflineColorParityAdmission.DISABLED
        if (!validOutput(request)) return OfflineColorParityAdmission.INVALID
        return try {
            executor.execute {
                val report = analyze(request)
                writeAtomically(request, report)
            }
            OfflineColorParityAdmission.ACCEPTED
        } catch (_: RejectedExecutionException) {
            OfflineColorParityAdmission.OVERLOADED
        }
    }

    /** Pure analysis seam used by the worker and JVM tests. */
    fun analyze(request: OfflineColorParityRequest): OfflineColorParityReport {
        val sets = listOf(request.preview, request.record, request.export)
        val timestamps = sets.map { it.sensorTimestampNs }
        val observedSkew = if (timestamps.all { it > 0L }) {
            (timestamps.maxOrNull()!! - timestamps.minOrNull()!!).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
        val inputValid = sets.all(::validPatchSet)
        val temporalValid = request.maximumTimestampSkewNs >= 0L &&
            observedSkew <= request.maximumTimestampSkewNs
        val measurement = if (inputValid && temporalValid) {
            TriSurfaceParityPolicy.measure(
                request.preview.patchesLab,
                request.record.patchesLab,
                request.export.patchesLab
            )
        } else {
            TriSurfaceParityPolicy.notProven()
        }
        val reason = when {
            !inputValid -> "NOT_PROVEN_INVALID_OR_INCOMPLETE_REAL_PATCHES"
            !temporalValid -> "NOT_PROVEN_TIMESTAMP_ASSOCIATION_OUT_OF_RANGE"
            !measurement.measured -> "NOT_PROVEN_PATCH_CARDINALITY_MISMATCH"
            measurement.withinTolerance -> "MEASURED_WITHIN_TOLERANCE"
            else -> "MEASURED_OUTSIDE_TOLERANCE"
        }
        return OfflineColorParityReport(
            recordingId = request.recordingId,
            measured = measurement.measured,
            reason = reason,
            maximumTimestampSkewNs = request.maximumTimestampSkewNs,
            observedTimestampSkewNs = observedSkew,
            measurement = measurement
        )
    }

    private fun validPatchSet(set: ColorParityPatchSet): Boolean =
        set.producer.isNotBlank() &&
            set.frameId >= 0L &&
            set.sensorTimestampNs > 0L &&
            set.patchesLab.isNotEmpty() &&
            set.patchesLab.all { patch ->
                patch.size >= 3 && patch[0].isFinite() && patch[1].isFinite() && patch[2].isFinite()
            }

    private fun validOutput(request: OfflineColorParityRequest): Boolean =
        request.recordingId.isNotBlank() && request.maximumTimestampSkewNs >= 0L

    private fun writeAtomically(request: OfflineColorParityRequest, report: OfflineColorParityReport) {
        val directory = request.outputDirectory
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            Log.e("OfflineColorParity", "Report directory unavailable: ${directory.absolutePath}")
            return
        }
        val safeId = request.recordingId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        if (safeId.isBlank()) {
            Log.e("OfflineColorParity", "Recording id cannot form a safe report filename")
            return
        }
        val output = File(directory, "$safeId.color-parity.json")
        val temporary = File(directory, ".$safeId.color-parity-${System.nanoTime()}.tmp")
        runCatching {
            FileOutputStream(temporary).use { stream ->
                stream.write(report.toJson().toByteArray(StandardCharsets.UTF_8))
                stream.fd.sync()
            }
            // Both paths are in the same directory, so POSIX rename atomically replaces any old
            // sidecar: readers observe either the complete previous report or the complete new one.
            Os.rename(temporary.absolutePath, output.absolutePath)
        }.onFailure { failure ->
            temporary.delete()
            Log.e("OfflineColorParity", "Unable to persist ${output.absolutePath}", failure)
        }
    }
}
