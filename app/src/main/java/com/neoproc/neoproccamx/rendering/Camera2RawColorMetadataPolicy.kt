package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.ApexColorConfig
import com.neoproc.neoproccamx.color.NeoGamutCat16Math
import kotlin.math.abs

/** Source of the post-white-balance sensor -> XYZ(D50) matrix used by the RAW demosaic. */
internal enum class Camera2RawColorTransformSource {
    /** Per-frame Camera2 sensor -> linear-sRGB transform, converted to the project's XYZ(D50) PCS. */
    CAPTURE_RESULT_LINEAR_SRGB,

    /** Public DNG forward-matrix interpolation retained when the per-frame result is unavailable/invalid. */
    DNG_FORWARD_MATRIX_FALLBACK
}

internal data class Camera2RawColorTransformResolution(
    val sensorToXyzD50: FloatArray,
    val source: Camera2RawColorTransformSource
)

/**
 * Camera2 colour-metadata bridge for the project-owned RAW pipeline.
 *
 * Android defines the capture-result colour sequence as Bayer-domain RGGB gains followed by a 3x3
 * sensor-RGB -> linear-sRGB transform. The startup Camera2 image uses that sequence. Reusing the same
 * per-frame transform for RAW removes the deterministic colour discontinuity at the NDR handoff while
 * leaving the project's NDR/NIVUKX/NeoLog rendering equations untouched.
 *
 * All matrix products accumulate in [Double]. A result matrix is admitted only when the final
 * sensor -> XYZ(D50) transform passes the same neutral-axis proof as a visible project-owned frame;
 * otherwise the existing public DNG forward matrix remains the fail-closed fallback.
 */
internal object Camera2RawColorMetadataPolicy {
    // IEC 61966-2-1 linear sRGB (D65) -> CIE XYZ(D65), row-major.
    private val LINEAR_SRGB_TO_XYZ_D65 = doubleArrayOf(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041
    )

    // The rest of the RAW pipeline consumes XYZ(D50). Keep adaptation in the project's existing
    // high-precision CAT16 implementation instead of introducing a second colour-science path.
    private val XYZ_D65_TO_XYZ_D50: DoubleArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        NeoGamutCat16Math.calculateCat16Matrix(
            sourceX = ApexColorConfig.WhitePoint.D65.x,
            sourceY = ApexColorConfig.WhitePoint.D65.y,
            targetX = ApexColorConfig.WhitePoint.D50.x,
            targetY = ApexColorConfig.WhitePoint.D50.y
        ).map(Float::toDouble).toDoubleArray()
    }

    fun resolveSensorToXyzD50(
        captureResultSensorToLinearSrgb: FloatArray?,
        dngForwardFallback: FloatArray
    ): Camera2RawColorTransformResolution {
        val candidate = captureResultSensorToLinearSrgb
            ?.takeIf(::validMatrix)
            ?.let { sensorToLinearSrgb ->
                multiply3x3(
                    XYZ_D65_TO_XYZ_D50,
                    multiply3x3(LINEAR_SRGB_TO_XYZ_D65, sensorToLinearSrgb.toDoubleArray())
                ).toFloatArray()
            }
            ?.takeIf(::validMatrix)
            ?.takeIf { ProjectOwnedNdrColorProof.validateNeutralAxis(it).passed }

        return if (candidate != null) {
            Camera2RawColorTransformResolution(
                sensorToXyzD50 = candidate,
                source = Camera2RawColorTransformSource.CAPTURE_RESULT_LINEAR_SRGB
            )
        } else {
            Camera2RawColorTransformResolution(
                sensorToXyzD50 = dngForwardFallback.copyOf(),
                source = Camera2RawColorTransformSource.DNG_FORWARD_MATRIX_FALLBACK
            )
        }
    }

    /**
     * Convert Camera2 `[R, G_even, G_odd, B]` gains to semantic `[R, Gr, Gb, B]`.
     *
     * On GBRG/BGGR sensors the red row is odd, so Gr is `G_odd`; treating `G_even` as Gr is only
     * correct for RGGB/GRBG. The distinction is normally small but becomes visible in shadow colour
     * when a device exposes separate green gains.
     */
    fun semanticWhiteBalanceRggb(
        red: Float,
        greenEven: Float,
        greenOdd: Float,
        blue: Float,
        bayerPattern: BayerPattern
    ): FloatArray {
        val redRowIsEven = bayerPattern == BayerPattern.RGGB || bayerPattern == BayerPattern.GRBG
        return if (redRowIsEven) {
            floatArrayOf(red, greenEven, greenOdd, blue)
        } else {
            floatArrayOf(red, greenOdd, greenEven, blue)
        }
    }

    private fun validMatrix(matrix: FloatArray): Boolean {
        if (matrix.size != 9 || matrix.any { !it.isFinite() || abs(it) > MAX_ABS_COEFFICIENT }) {
            return false
        }
        val determinant =
            matrix[0].toDouble() * (matrix[4].toDouble() * matrix[8] - matrix[5].toDouble() * matrix[7]) -
                matrix[1].toDouble() * (matrix[3].toDouble() * matrix[8] - matrix[5].toDouble() * matrix[6]) +
                matrix[2].toDouble() * (matrix[3].toDouble() * matrix[7] - matrix[4].toDouble() * matrix[6])
        return determinant.isFinite() && abs(determinant) >= MIN_ABS_DETERMINANT
    }

    private fun multiply3x3(left: DoubleArray, right: DoubleArray): DoubleArray {
        require(left.size == 9 && right.size == 9)
        return DoubleArray(9) { index ->
            val row = index / 3
            val column = index % 3
            var sum = 0.0
            for (k in 0 until 3) {
                sum += left[row * 3 + k] * right[k * 3 + column]
            }
            sum
        }
    }

    private fun FloatArray.toDoubleArray(): DoubleArray = DoubleArray(size) { this[it].toDouble() }

    private fun DoubleArray.toFloatArray(): FloatArray = FloatArray(size) { this[it].toFloat() }

    private const val MAX_ABS_COEFFICIENT = 8f
    private const val MIN_ABS_DETERMINANT = 1.0e-8
}
