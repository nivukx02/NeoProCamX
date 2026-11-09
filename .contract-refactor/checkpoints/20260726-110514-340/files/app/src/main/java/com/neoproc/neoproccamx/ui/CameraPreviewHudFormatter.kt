package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.pipeline.HudIreScale
import com.neoproc.neoproccamx.recording.AudioDspPolicy
import com.neoproc.neoproccamx.recording.AudioLevel
import kotlin.math.abs
import kotlin.math.roundToInt

internal object CameraPreviewHudFormatter {
    fun format(
        sensorSize: Size?,
        resolution: String?,
        aspect: String?,
        lensLabel: String? = null
    ): String = format(
        sensorWidth = sensorSize?.width,
        sensorHeight = sensorSize?.height,
        resolution = resolution,
        aspect = aspect,
        lensLabel = lensLabel
    )

    fun format(
        sensorWidth: Int?,
        sensorHeight: Int?,
        resolution: String?,
        aspect: String?,
        lensLabel: String? = null
    ): String {
        val sensorLabel = if (sensorWidth != null && sensorHeight != null &&
            sensorWidth > 0 && sensorHeight > 0
        ) {
            "${sensorWidth}x${sensorHeight}"
        } else {
            UNKNOWN
        }
        val resolutionLabel = resolution?.trim().takeUnless { it.isNullOrEmpty() } ?: UNKNOWN
        val aspectLabel = aspect?.trim().takeUnless { it.isNullOrEmpty() } ?: UNKNOWN
        val base = "$sensorLabel | $resolutionLabel | $aspectLabel"
        // CP-QV23 — surface the pre-computed 35mm-equivalent lens badge on the existing HUD line.
        // A blank/null label is a strict no-op so today's HUD string stays byte-identical.
        val lens = lensLabel?.trim().takeUnless { it.isNullOrEmpty() } ?: return base
        return "$base | $lens"
    }

    fun aspectLabel(size: Size?): String {
        size ?: return UNKNOWN
        return aspectLabel(size.width, size.height)
    }

    fun aspectLabel(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return UNKNOWN
        val longEdge = maxOf(width, height).coerceAtLeast(1)
        val shortEdge = minOf(width, height).coerceAtLeast(1)
        val ratio = longEdge.toFloat() / shortEdge
        return when {
            isNear(ratio, 4f / 3f) -> "4:3"
            isNear(ratio, 16f / 9f) -> "16:9"
            isNear(ratio, 1f) -> "1:1"
            isNear(ratio, 3f / 2f) -> "3:2"
            isNear(ratio, 2.39f) -> "2.39:1"
            else -> String.format(java.util.Locale.US, "%.2f:1", ratio)
        }
    }

    fun formatStage6Status(
        provisional: Boolean,
        qualityClass: String,
        transportClassification: String,
        deviceLabStatus: String,
        nativePathValidated: Boolean
    ): String {
        val classLabel = qualityClass.stage6ShortClass()
        val transportLabel = transportClassification.stage6ShortTransport()
        val labLabel = deviceLabStatus.stage6ShortStatus()
        val nativeLabel = if (nativePathValidated) "OK" else "NT"
        val prefix = if (provisional) "PROV" else "FINAL"
        return "$prefix $classLabel | $transportLabel | Lab $labLabel | Native $nativeLabel"
    }

    // ── s9-f11 / CP-QV11 — pro-formatter extensions ─────────────────────────────
    //
    // Pure additive HUD formatters for the pro info panel: SMPTE timecode, an audio peak
    // classifier + segment meter, and a false-colour band label. None of these are called by the
    // existing [format]/[formatStage6Status] paths, so today's HUD output stays byte-identical.

    /**
     * SMPTE HH:MM:SS:FF timecode for a recording elapsed time. Ports the frame derivation used by
     * [com.neoproc.neoproccamx.ui.overlays.ProInfoOverlay]: seconds from the millisecond clock and
     * frames from the sub-second remainder scaled by the frame rate. [fps] is coerced to at least 1
     * (a zero/negative rate never divides or throws) and the frame field is clamped to `fps - 1`.
     *
     * When [dropFrame] is true the frame separator is the SMPTE drop-frame semicolon `;` (the numeric
     * derivation is unchanged — no frame renumbering is performed).
     */
    fun formatTimecode(elapsedMs: Long, fps: Int, dropFrame: Boolean = false): String {
        val ms = elapsedMs.coerceAtLeast(0L)
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val safeFps = fps.coerceAtLeast(1)
        val frames = ((ms % 1000) * safeFps / 1000).toInt().coerceIn(0, safeFps - 1)
        val frameSep = if (dropFrame) ";" else ":"
        return String.format(java.util.Locale.US, "%02d:%02d:%02d$frameSep%02d", h, m, s, frames)
    }

    /**
     * Audio peak classifier: CLIP at/above the limiter ceiling (-1 dBFS), WARN at/above the limiter
     * threshold (-6 dBFS), NOM below. Non-finite peaks are treated as NOM (silence/undefined).
     */
    fun formatAudioMeter(level: AudioLevel): String {
        val peak = level.peakDbfs
        if (!peak.isFinite()) return "NOM"
        return when {
            peak >= AudioDspPolicy.DEFAULT_LIMITER_CEILING_DBFS -> "CLIP"
            peak >= AudioDspPolicy.DEFAULT_LIMITER_THRESHOLD_DBFS -> "WARN"
            else -> "NOM"
        }
    }

    /** Meter floor (dBFS); peaks at/below this fill zero segments. */
    private const val METER_FLOOR_DBFS = -60f

    /**
     * Number of lit meter segments for a peak level. The count is monotone non-decreasing in
     * [peakDbfs] and clamped to `[0, segments]`: the ceiling (-1 dBFS) fills all segments, the meter
     * floor fills none. A non-positive [segments] or a non-finite peak yields 0.
     */
    fun audioMeterSegments(peakDbfs: Float, segments: Int): Int {
        if (segments <= 0 || !peakDbfs.isFinite()) return 0
        val ceiling = AudioDspPolicy.DEFAULT_LIMITER_CEILING_DBFS
        val span = (ceiling - METER_FLOOR_DBFS).coerceAtLeast(1e-6f)
        val fraction = ((peakDbfs - METER_FLOOR_DBFS) / span).coerceIn(0f, 1f)
        return (fraction * segments).roundToInt().coerceIn(0, segments)
    }

    /**
     * SmallHD-style false-colour exposure band name for a scene [ire] (0..100+), delegating to
     * [HudIreScale.bandForIre]. Non-finite or negative IRE is coerced to 0 so the lookup never throws.
     */
    fun falseColorBandLabel(ire: Float): String {
        val safeIre = if (ire.isFinite()) ire.coerceAtLeast(0f) else 0f
        return HudIreScale.bandForIre(safeIre).name
    }

    private fun isNear(value: Float, target: Float): Boolean =
        abs(value - target) <= 0.03f

    private const val UNKNOWN = "Unknown"
}

private fun String.stage6ShortClass(): String =
    when (trim().uppercase()) {
        "SINGULARITY-100", "SINGULARITY_100" -> "S100"
        "SINGULARITY-75", "SINGULARITY_75" -> "S75"
        "SINGULARITY-50", "SINGULARITY_50" -> "S50"
        "SINGULARITY-25", "SINGULARITY_25" -> "S25"
        "CORE-HONEST", "CORE_HONEST" -> "CORE"
        "NO-RELEASE", "NO_RELEASE" -> "NO"
        else -> trim().ifBlank { "CORE" }
    }

private fun String.stage6ShortTransport(): String =
    when (trim().uppercase()) {
        "AHB_VULKAN_ZERO_COPY" -> "AHB0"
        "AHB_GPU_COMPATIBILITY" -> "AHB_GPU"
        "ENCODER_SURFACE_DIRECT" -> "ENC_DIR"
        "PRIVATE_MASTER_GPU_EXPORT" -> "PM_GPU"
        "RAW_SENSOR_PRIVATE_MASTER" -> "RAW_PM"
        "CPU_COMPATIBILITY_NON_SINGULARITY" -> "CPU"
        "UNPROVEN" -> "UNPROVEN"
        else -> trim().ifBlank { "UNPROVEN" }
    }

private fun String.stage6ShortStatus(): String =
    when (trim().uppercase()) {
        "PASS" -> "OK"
        "WARN" -> "WARN"
        "FAIL" -> "FAIL"
        "NOT_APPLICABLE" -> "NA"
        "NOT_TESTED" -> "NT"
        "DRY_RUN" -> "NT"
        else -> trim().ifBlank { "NT" }
    }
