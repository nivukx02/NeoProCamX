package com.neoproc.neoproccamx.camera

import kotlin.math.abs
import kotlin.math.max

/** Immutable public-Camera2 quality state captured at the still request/result boundary. */
internal data class StillCaptureQualitySnapshot(
    val edgeMode: Int?,
    val noiseReductionMode: Int?,
    val hotPixelMode: Int?,
    val shadingMode: Int?,
    val tonemapMode: Int?,
    val colorAberrationMode: Int?,
    val distortionCorrectionMode: Int?,
    val aeMode: Int?,
    val awbMode: Int?,
    val afMode: Int?,
    val colorCorrectionMode: Int?,
    val sensorSensitivityIso: Int?,
    val exposureTimeNs: Long?,
    val focusDistanceDiopters: Float?,
    /** Request-only transport evidence; CaptureResult does not report JPEG quality/orientation. */
    val jpegQuality: Int? = null,
    val jpegOrientationDegrees: Int? = null
)

internal enum class StillCaptureQualityVerdict {
    PROVEN,
    PARTIAL,
    NOT_PROVEN
}

internal data class StillCaptureQualityReadbackReport(
    val verdict: StillCaptureQualityVerdict,
    val matchedKeys: List<String>,
    val unreportedKeys: List<String>,
    val mismatchedKeys: List<String>,
    val requestedJpegQuality: Int?,
    val requestedJpegOrientationDegrees: Int?
) {
    val passed: Boolean get() = verdict != StillCaptureQualityVerdict.NOT_PROVEN

    fun toEvidenceLine(): String =
        "verdict=$verdict matched=${matchedKeys.joinToString(",")} " +
            "unreported=${unreportedKeys.joinToString(",")} " +
            "mismatched=${mismatchedKeys.joinToString(",")} " +
            "jpegQuality=${requestedJpegQuality ?: "unset"} " +
            "jpegOrientation=${requestedJpegOrientationDegrees ?: "unset"}"
}

/**
 * Compares the final built still request with the exact TotalCaptureResult from that same request.
 * Missing vendor readback is reported as PARTIAL; a contradictory value is NOT_PROVEN. The policy is
 * observe-only and never fabricates support for keys the HAL does not report.
 */
internal object StillCaptureQualityReadbackPolicy {
    private const val FLOAT_ABSOLUTE_TOLERANCE = 0.02f
    private const val FLOAT_RELATIVE_TOLERANCE = 0.02f
    private const val EXPOSURE_ABSOLUTE_TOLERANCE_NS = 100_000L
    private const val EXPOSURE_RELATIVE_TOLERANCE = 0.02

    fun verify(
        requested: StillCaptureQualitySnapshot,
        reported: StillCaptureQualitySnapshot
    ): StillCaptureQualityReadbackReport {
        val matched = mutableListOf<String>()
        val unreported = mutableListOf<String>()
        val mismatched = mutableListOf<String>()

        compareExact("edge", requested.edgeMode, reported.edgeMode, matched, unreported, mismatched)
        compareExact(
            "noiseReduction",
            requested.noiseReductionMode,
            reported.noiseReductionMode,
            matched,
            unreported,
            mismatched
        )
        compareExact("hotPixel", requested.hotPixelMode, reported.hotPixelMode, matched, unreported, mismatched)
        compareExact("shading", requested.shadingMode, reported.shadingMode, matched, unreported, mismatched)
        compareExact("tonemap", requested.tonemapMode, reported.tonemapMode, matched, unreported, mismatched)
        compareExact(
            "aberration",
            requested.colorAberrationMode,
            reported.colorAberrationMode,
            matched,
            unreported,
            mismatched
        )
        compareExact(
            "distortion",
            requested.distortionCorrectionMode,
            reported.distortionCorrectionMode,
            matched,
            unreported,
            mismatched
        )
        compareExact("ae", requested.aeMode, reported.aeMode, matched, unreported, mismatched)
        compareExact("awb", requested.awbMode, reported.awbMode, matched, unreported, mismatched)
        compareExact("af", requested.afMode, reported.afMode, matched, unreported, mismatched)
        compareExact(
            "colorCorrection",
            requested.colorCorrectionMode,
            reported.colorCorrectionMode,
            matched,
            unreported,
            mismatched
        )
        compareExact(
            "iso",
            requested.sensorSensitivityIso,
            reported.sensorSensitivityIso,
            matched,
            unreported,
            mismatched
        )
        compareLong(
            "exposureTime",
            requested.exposureTimeNs,
            reported.exposureTimeNs,
            matched,
            unreported,
            mismatched
        )
        compareFloat(
            "focusDistance",
            requested.focusDistanceDiopters,
            reported.focusDistanceDiopters,
            matched,
            unreported,
            mismatched
        )

        val verdict = when {
            mismatched.isNotEmpty() -> StillCaptureQualityVerdict.NOT_PROVEN
            unreported.isNotEmpty() -> StillCaptureQualityVerdict.PARTIAL
            else -> StillCaptureQualityVerdict.PROVEN
        }
        return StillCaptureQualityReadbackReport(
            verdict = verdict,
            matchedKeys = matched,
            unreportedKeys = unreported,
            mismatchedKeys = mismatched,
            requestedJpegQuality = requested.jpegQuality,
            requestedJpegOrientationDegrees = requested.jpegOrientationDegrees
        )
    }

    private fun <T> compareExact(
        name: String,
        requested: T?,
        reported: T?,
        matched: MutableList<String>,
        unreported: MutableList<String>,
        mismatched: MutableList<String>
    ) {
        if (requested == null) return
        when {
            reported == null -> unreported += name
            requested == reported -> matched += name
            else -> mismatched += "$name(requested=$requested,reported=$reported)"
        }
    }

    private fun compareLong(
        name: String,
        requested: Long?,
        reported: Long?,
        matched: MutableList<String>,
        unreported: MutableList<String>,
        mismatched: MutableList<String>
    ) {
        if (requested == null) return
        if (reported == null) {
            unreported += name
            return
        }
        val tolerance = max(
            EXPOSURE_ABSOLUTE_TOLERANCE_NS.toDouble(),
            abs(requested.toDouble()) * EXPOSURE_RELATIVE_TOLERANCE
        )
        if (abs(reported.toDouble() - requested.toDouble()) <= tolerance) {
            matched += name
        } else {
            mismatched += "$name(requested=$requested,reported=$reported)"
        }
    }

    private fun compareFloat(
        name: String,
        requested: Float?,
        reported: Float?,
        matched: MutableList<String>,
        unreported: MutableList<String>,
        mismatched: MutableList<String>
    ) {
        if (requested == null || !requested.isFinite()) return
        if (reported == null || !reported.isFinite()) {
            unreported += name
            return
        }
        val tolerance = max(FLOAT_ABSOLUTE_TOLERANCE, abs(requested) * FLOAT_RELATIVE_TOLERANCE)
        if (abs(reported - requested) <= tolerance) {
            matched += name
        } else {
            mismatched += "$name(requested=$requested,reported=$reported)"
        }
    }
}
