package com.neoproc.neoproccamx.ui

import android.util.Size
import java.util.Locale

/**
 * RecordingOperationOutcome — cinema-oriented recording operation result model.
 *
 * Encapsulates the result of a recording start/stop/capture operation.
 * The sealed class has exactly two variants: [Success] and [Failure],
 * matching the exhaustive when-expressions in CameraViewModel.
 *
 * For advanced pipeline telemetry, use [RecordingSessionTelemetry] alongside
 * this outcome.
 */
sealed class RecordingOperationOutcome {

    /**
     * Operation completed successfully.
     */
    object Success : RecordingOperationOutcome()

    /**
     * Operation failed with detailed diagnostics.
     *
     * @param reason     Human-readable failure description.
     * @param operation  The pipeline operation that failed.
     * @param cause      Optional underlying exception.
     * @param errorCode  Machine-readable error code for telemetry.
     * @param isRecoverable Whether a retry is safe.
     * @param retryAfterMs Suggested retry delay in milliseconds.
     */
    data class Failure(
        val reason: String,
        val operation: String = "unknown",
        val cause: Throwable? = null,
        val errorCode: RecordingErrorCode = RecordingErrorCode.UNKNOWN,
        val isRecoverable: Boolean = true,
        val retryAfterMs: Long = 500L
    ) : RecordingOperationOutcome() {
        /** Short diagnostic for logging. */
        val diagnostic: String
            get() = "[$operation] $reason" + (cause?.let { " (${it.javaClass.simpleName})" } ?: "")
    }

    /** Whether this outcome represents a terminal failure. */
    val isFatalError: Boolean
        get() = this is Failure && !isRecoverable

    /** Whether this outcome is a success. */
    val isSuccess: Boolean
        get() = this is Success
}

// ── Extended Telemetry Models (not sealed subtypes) ───────────────────────────

/**
 * cinema-oriented recording session telemetry — captured alongside
 * [RecordingOperationOutcome] for detailed pipeline diagnostics.
 *
 * These are NOT subtypes of the sealed class to avoid breaking exhaustive
 * when-expressions in CameraViewModel.
 */
data class RecordingSessionTelemetry(
    /** Output file path. */
    val outputPath: String = "",
    /** Recording duration in milliseconds. */
    val durationMs: Long = 0L,
    /** Total frames recorded. */
    val framesRecorded: Long = 0L,
    /** Frames dropped during the session. */
    val droppedFrames: Int = 0,
    /** Final file size in bytes. */
    val fileSizeBytes: Long = 0L,
    /** Measured average bitrate in kbps. */
    val averageBitrateKbps: Long = 0L,
    /** Resolved encoder output resolution. */
    val resolvedSize: Size = Size(1920, 1080),
    /** Resolved frame rate. */
    val resolvedFps: Int = 25,
    /** Active codec name. */
    val codecName: String = "HEVC",
    /** Active HDR mode. */
    val hdrMode: String = "OFF",
    /** Active color space. */
    val colorSpace: String = "Rec.709",
    /** Whether 10-bit encoding was active. */
    val is10Bit: Boolean = false,
    /** Unique session identifier. */
    val sessionId: String = ""
) {
    val durationSeconds: Double get() = durationMs / 1000.0
    val fileSizeMb: Float get() = fileSizeBytes / (1024f * 1024f)
    val dropRate: Float
        get() = if (framesRecorded > 0) droppedFrames.toFloat() / framesRecorded else 0f
    val isClean: Boolean get() = droppedFrames == 0
    val megapixels: Float
        get() = (resolvedSize.width.toLong() * resolvedSize.height.toLong()) / 1_000_000f

    val shortSummary: String
        get() = "$codecName ${resolvedSize.width}×${resolvedSize.height}@${resolvedFps}fps " +
            "${averageBitrateKbps / 1000}Mbps" +
            (if (is10Bit) " 10-bit" else "") +
            (if (hdrMode != "OFF") " $hdrMode" else "")

    val diagnosticSummary: String
        get() = buildString {
            append("Session: $shortSummary")
            append(" | duration=${String.format(Locale.US, "%.1f", durationSeconds)}s")
            append(" | frames=$framesRecorded")
            if (droppedFrames > 0) append(" | dropped=$droppedFrames")
            append(" | size=${String.format(Locale.US, "%.1f", fileSizeMb)}MB")
        }
}

// ── Error Code Catalog ────────────────────────────────────────────────────────

/**
 * Machine-readable error codes for recording failures.
 * Used for telemetry, analytics, and recovery logic.
 */
enum class RecordingErrorCode(
    val code: Int,
    val description: String,
    val isRecoverable: Boolean
) {
    // Success
    OK(0, "Success", true),

    // Configuration errors
    NO_OUTPUT_PATH(1001, "No output path configured", true),
    UNSUPPORTED_CODEC(1002, "Requested codec is not supported", true),
    UNSUPPORTED_RESOLUTION(1003, "Requested resolution is not supported", true),
    INCOMPATIBLE_HDR_CODEC(1004, "HDR mode requires HEVC or AV1 codec", true),

    // Camera session errors
    SESSION_NOT_READY(2001, "Camera session is not in a recordable state", true),
    SURFACE_INVALID(2002, "Encoder surface is null or invalid", true),
    PIPELINE_BUSY(2003, "Pipeline is already recording", false),

    // Encoder errors
    ENCODER_INIT_FAILED(3001, "MediaCodec encoder initialization failed", true),
    ENCODER_CONFIGURE_FAILED(3002, "MediaCodec encoder configuration failed", true),
    ENCODER_START_FAILED(3003, "MediaCodec encoder failed to start", true),
    ENCODER_DROPPED_FRAMES(3004, "Excessive frame drops detected", true),

    // Storage errors
    STORAGE_FULL(4001, "Insufficient storage space", false),
    STORAGE_UNAVAILABLE(4002, "Storage medium is unavailable", false),
    IO_ERROR(4003, "File I/O error during recording", false),

    // Thermal errors
    THERMAL_EMERGENCY(5001, "Recording stopped due to device overheating", true),
    THERMAL_CRITICAL(5002, "Thermal throttling degraded recording quality", true),

    // Unknown
    UNKNOWN(9999, "Unknown recording error", true);

    companion object {
        fun fromCode(code: Int): RecordingErrorCode =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
