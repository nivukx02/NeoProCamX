package com.neoproc.neoproccamx.recording

import java.util.Locale
import kotlin.math.abs

internal data class NeoHdrFrontEndEvidence(
    val blackLevels: List<Double>,
    val whiteLevel: Double,
    val wbGains: List<Double>,
    val neutralAfterWb: List<Double>,
    val sensorToXyz: List<Double>,
    val cctKelvin: Int,
    val defectCorrectionApplied: Boolean,
    val lensShadingCorrectionApplied: Boolean,
    val linearizationGuardApplied: Boolean,
    val redRunawayClampApplied: Boolean
) {
    companion object {
        fun unverified(): NeoHdrFrontEndEvidence =
            NeoHdrFrontEndEvidence(
                blackLevels = emptyList(),
                whiteLevel = 0.0,
                wbGains = emptyList(),
                neutralAfterWb = emptyList(),
                sensorToXyz = emptyList(),
                cctKelvin = 0,
                defectCorrectionApplied = false,
                lensShadingCorrectionApplied = false,
                linearizationGuardApplied = false,
                redRunawayClampApplied = false
            )
    }
}

internal data class NeoHdrFrontEndFailure(
    val code: String,
    val message: String
)

internal data class NeoHdrFrontEndEvidenceResult(
    val passed: Boolean,
    val evidence: NeoHdrFrontEndEvidence,
    val failures: List<NeoHdrFrontEndFailure>
) {
    fun toMetadata(): Map<String, String> {
        val ccmNonIdentity = evidence.sensorToXyz.size == 9 && !NeoHdrFrontEndEvidencePolicy.isIdentity3x3(evidence.sensorToXyz)
        return mapOf(
            "frontEndStatus" to if (passed) "PASS" else "FAIL",
            "frontEndFailureCodes" to failures.joinToString(separator = ";") { it.code },
            "frontEndBlackLevels" to evidence.blackLevels.joinToString(separator = ",") { it.format6() },
            "frontEndWhiteLevel" to evidence.whiteLevel.format6(),
            "frontEndWbGains" to evidence.wbGains.joinToString(separator = ",") { it.format6() },
            "frontEndNeutralAfterWb" to evidence.neutralAfterWb.joinToString(separator = ",") { it.format6() },
            "frontEndSensorToXyzCcm" to evidence.sensorToXyz.joinToString(separator = ",") { it.format6() },
            "frontEndCctKelvin" to evidence.cctKelvin.toString(),
            "frontEndDefectCorrection" to evidence.defectCorrectionApplied.toString(),
            "frontEndLensShadingCorrection" to evidence.lensShadingCorrectionApplied.toString(),
            "frontEndLinearizationGuard" to evidence.linearizationGuardApplied.toString(),
            "frontEndRedRunawayClamp" to evidence.redRunawayClampApplied.toString(),
            "frontEndCcmNonIdentity" to ccmNonIdentity.toString()
        )
    }

    private fun Double.format6(): String =
        String.format(Locale.US, "%.6f", this)
}

internal object NeoHdrFrontEndEvidencePolicy {
    private const val NEUTRAL_TOLERANCE = 0.03
    private const val MATRIX_EPSILON = 1e-6

    fun validate(evidence: NeoHdrFrontEndEvidence): NeoHdrFrontEndEvidenceResult {
        val failures = mutableListOf<NeoHdrFrontEndFailure>()
        val maxBlack = evidence.blackLevels.maxOrNull()
        if (evidence.blackLevels.size != 4 || maxBlack == null || evidence.whiteLevel <= maxBlack) {
            failures += NeoHdrFrontEndFailure(
                code = "BLACK_WHITE_LEVEL_INVALID",
                message = "RAW black levels must contain four Bayer offsets and white level must be above black."
            )
        }
        if (!wbApplied(evidence)) {
            failures += NeoHdrFrontEndFailure(
                code = "WB_NOT_APPLIED",
                message = "Neutral RGB after white balance must be approximately 1.0 on all channels."
            )
        }
        if (evidence.sensorToXyz.size != 9 || isIdentity3x3(evidence.sensorToXyz)) {
            failures += NeoHdrFrontEndFailure(
                code = "SENSOR_TO_XYZ_CCM_IDENTITY",
                message = "Sensor-to-XYZ matrix must be a real CCT-interpolated CCM, never identity."
            )
        }
        if (!evidence.defectCorrectionApplied) {
            failures += NeoHdrFrontEndFailure("DEFECT_CORRECTION_MISSING", "Dead/hot pixel correction was not proven.")
        }
        if (!evidence.lensShadingCorrectionApplied) {
            failures += NeoHdrFrontEndFailure("LENS_SHADING_MISSING", "Lens shading correction was not proven.")
        }
        if (!evidence.linearizationGuardApplied) {
            failures += NeoHdrFrontEndFailure("LINEARIZATION_GUARD_MISSING", "RAW linearization guard was not proven.")
        }
        if (!evidence.redRunawayClampApplied) {
            failures += NeoHdrFrontEndFailure("RED_RUNAWAY_CLAMP_MISSING", "Highlight red-runaway clamp was not proven.")
        }
        return NeoHdrFrontEndEvidenceResult(
            passed = failures.isEmpty(),
            evidence = evidence,
            failures = failures
        )
    }

    fun isIdentity3x3(matrix: List<Double>): Boolean {
        if (matrix.size != 9) return false
        return matrix.indices.all { index ->
            val row = index / 3
            val col = index % 3
            val expected = if (row == col) 1.0 else 0.0
            abs(matrix[index] - expected) <= MATRIX_EPSILON
        }
    }

    private fun wbApplied(evidence: NeoHdrFrontEndEvidence): Boolean {
        if (evidence.wbGains.size != 3 || evidence.neutralAfterWb.size != 3) return false
        if (evidence.wbGains.any { it <= 0.0 }) return false
        return evidence.neutralAfterWb.all { abs(it - 1.0) <= NEUTRAL_TOLERANCE }
    }
}
