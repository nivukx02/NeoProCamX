package com.neoproc.neoproccamx.ui

import android.util.Size
import java.util.Locale

/**
 * RecordingSourceResolutionPlan — cinema-oriented resolution plan for the recording pipeline.
 *
 * Encapsulates the resolved sensor readout and encoding resolution decision,
 * including full-sensor readout metadata, cine source crop dimensions,
 * delivery format, resolution tier classification, and EIS headroom.
 *
 * This plan is produced by [RecordingSourceResolutionPolicy] and consumed by:
 *  - CameraViewModel (session configuration)
 *  - RecordingManager (muxer format setup)
 *  - Preview pipeline (viewport synchronization)
 */
data class RecordingSourceResolutionPlan(
    /** Megapixels available from the full sensor pixel array. */
    val fullSensorMegapixels: Float = 12.2f,

    /** Megapixels of the selected cine source area. */
    val cineSourceMegapixels: Float = 8.3f,

    /** Recommended readout megapixels for the encoder. */
    val preferredReadoutMegapixels: Float = 10.0f,

    /** Full sensor physical pixel array dimensions. */
    val fullSensorSize: Size = Size(4032, 3024),

    /** Selected cine source crop dimensions (== recording output). */
    val cineSourceSize: Size = Size(3840, 2160),

    /** Whether a full-sensor readout was forced. */
    val fullSensorReadoutForced: Boolean = false,

    /** Whether the cine-source crop policy was applied. */
    val appliesCineSourcePolicy: Boolean = false,

    /** Delivery format identifier (e.g. "YUV_420_888_4K"). */
    val deliveryFormat: String = "YUV_420_888",

    /** Human-readable resolution tier label (e.g. "4K_UHD", "DCI_4K", "8K_UHD"). */
    val resolutionTier: String = "",

    /** Whether EIS crop headroom is reserved in the readout. */
    val stabilizationHeadroomReserved: Boolean = false
) {
    // ── Derived Properties ────────────────────────────────────────────────────

    /** Effective megapixels — full sensor if forced, else cine source area. */
    val effectiveMegapixels: Float
        get() = if (fullSensorReadoutForced) fullSensorMegapixels else cineSourceMegapixels

    /** Whether this plan captures the full physical sensor. */
    val isFullSensorCapture: Boolean
        get() = fullSensorReadoutForced

    /** Aspect ratio of the cine source crop area. */
    val cineSourceAspect: Float
        get() = cineSourceSize.width.toFloat() / cineSourceSize.height.coerceAtLeast(1)

    /** Whether this is a 4K or higher resolution plan. */
    val is4KOrHigher: Boolean
        get() = maxOf(cineSourceSize.width, cineSourceSize.height) >= 3840

    /** Whether this is a cinema-oriented (DCI/UHD) resolution. */
    val isCinemaGrade: Boolean
        get() = resolutionTier in listOf("4K_UHD", "DCI_4K", "5K", "6K_CINEMA", "8K_UHD")

    /** Long edge of the cine source area. */
    val longEdge: Int
        get() = maxOf(cineSourceSize.width, cineSourceSize.height)

    /** Short edge of the cine source area. */
    val shortEdge: Int
        get() = minOf(cineSourceSize.width, cineSourceSize.height)

    /** Width of the cine source area. */
    val width: Int get() = cineSourceSize.width

    /** Height of the cine source area. */
    val height: Int get() = cineSourceSize.height

    /** Human-readable summary for telemetry and logging. */
    val shortSummary: String
        get() = buildString {
            if (resolutionTier.isNotEmpty()) append("[$resolutionTier] ")
            append("${cineSourceSize.width}×${cineSourceSize.height}")
            append(" (${String.format(Locale.US, "%.1f", effectiveMegapixels)}MP)")
            append(if (fullSensorReadoutForced) " FULL_SENSOR" else " CROP")
            if (stabilizationHeadroomReserved) append(" +EIS")
        }

    /** Full diagnostic description for debug logging. */
    val diagnosticSummary: String
        get() = buildString {
            append("Plan: $shortSummary")
            append(" | sensor=${fullSensorSize.width}×${fullSensorSize.height}")
            append(" (${String.format(Locale.US, "%.1f", fullSensorMegapixels)}MP)")
            append(" | format=$deliveryFormat")
            if (appliesCineSourcePolicy) append(" | CINE_POLICY")
        }
}
