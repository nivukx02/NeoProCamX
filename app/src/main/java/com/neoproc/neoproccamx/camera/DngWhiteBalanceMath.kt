package com.neoproc.neoproccamx.camera

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Device-true white-balance math, ported faithfully from the Adobe DNG SDK as used by MotionCam
 * (`Temperature.cpp` + `CameraProfile.cpp` + `Color.cpp`, all in `D:/motioncam-main`).
 *
 * ## Why this exists
 * The legacy [WhiteBalanceConversion.kelvinToRggbGains] path derives RGGB gains from the generic
 * Tanner-Helland blackbody approximation with only a weak (±15 %) green-row nudge from the sensor
 * forward matrix. That is device-*independent* and drifts a few percent per illuminant — fine for a
 * rough look, not for the "accurate data from Pro Control" this project targets.
 *
 * This object instead solves the white balance the way a raw developer (and the camera HAL itself)
 * does: from the sensor's own DNG calibration — the two reference color matrices, forward matrices,
 * and calibration transforms Camera2 exposes as `SENSOR_COLOR_TRANSFORM1/2`,
 * `SENSOR_FORWARD_MATRIX1/2`, `SENSOR_CALIBRATION_TRANSFORM1/2`, and `SENSOR_REFERENCE_ILLUMINANT1/2`.
 * The result is *device-true*: the gains are exactly what neutralises the chosen (Kelvin, tint) on
 * this specific sensor, and the optional color matrix is the neutral-preserving camera→linear-sRGB
 * transform for that white point.
 *
 * ## Neutral-preserving CCM factoring
 * Camera2 applies [android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_GAINS] first, then the
 * 3×3 [android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_TRANSFORM]:
 * `out = CCM · (gains ⊙ cameraRGB)`. We put the white balance in the gains and factor it *out* of
 * the full raw→sRGB render `FULL` so `CCM = FULL · diag(gains)⁻¹`. Then `CCM · diag(gains) = FULL`
 * exactly, and the chosen neutral maps to sRGB white — the render never picks up a residual cast.
 *
 * ## Fail-closed
 * Every entry point returns `null` when the calibration is missing or numerically unusable (a
 * singular matrix, a non-finite intermediate, …). Callers fall back to the legacy approximation, so
 * a device that does not publish DNG calibration keeps today's behaviour bit-for-bit — this is a
 * strict upgrade, never a downgrade.
 *
 * All math is done in [Double] for precision and is Android-free, so it is fully unit-testable off
 * device. Stateless; safe from any thread.
 */
internal object DngWhiteBalanceMath {

    /** Scale factor between uv-space distance and the user-facing "tint" parameter (dng_temperature). */
    private const val TINT_SCALE = -3000.0

    /** D50 profile-connection-space white in CIE xy (Color.cpp `D50XYCoord`). */
    private const val D50_X = 0.3457
    private const val D50_Y = 0.3585

    /**
     * Wyszecki & Stiles blackbody isotemp table ("Color Science", 2nd ed., p.228) as used by the
     * DNG SDK. Columns: `r` (reciprocal mega-kelvin, i.e. mireds), `u`, `v` (CIE 1960 UCS of the
     * Planckian point), `t` (isotemp-line slope). 31 rows spanning ∞K (r=0) down to ~1667K (r=600).
     */
    private val TEMP_TABLE: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.0, 0.18006, 0.26352, -0.24341),
        doubleArrayOf(10.0, 0.18066, 0.26589, -0.25479),
        doubleArrayOf(20.0, 0.18133, 0.26846, -0.26876),
        doubleArrayOf(30.0, 0.18208, 0.27119, -0.28539),
        doubleArrayOf(40.0, 0.18293, 0.27407, -0.30470),
        doubleArrayOf(50.0, 0.18388, 0.27709, -0.32675),
        doubleArrayOf(60.0, 0.18494, 0.28021, -0.35156),
        doubleArrayOf(70.0, 0.18611, 0.28342, -0.37915),
        doubleArrayOf(80.0, 0.18740, 0.28668, -0.40955),
        doubleArrayOf(90.0, 0.18880, 0.28997, -0.44278),
        doubleArrayOf(100.0, 0.19032, 0.29326, -0.47888),
        doubleArrayOf(125.0, 0.19462, 0.30141, -0.58204),
        doubleArrayOf(150.0, 0.19962, 0.30921, -0.70471),
        doubleArrayOf(175.0, 0.20525, 0.31647, -0.84901),
        doubleArrayOf(200.0, 0.21142, 0.32312, -1.0182),
        doubleArrayOf(225.0, 0.21807, 0.32909, -1.2168),
        doubleArrayOf(250.0, 0.22511, 0.33439, -1.4512),
        doubleArrayOf(275.0, 0.23247, 0.33904, -1.7298),
        doubleArrayOf(300.0, 0.24010, 0.34308, -2.0637),
        doubleArrayOf(325.0, 0.24702, 0.34655, -2.4681),
        doubleArrayOf(350.0, 0.25591, 0.34951, -2.9641),
        doubleArrayOf(375.0, 0.26400, 0.35200, -3.5814),
        doubleArrayOf(400.0, 0.27218, 0.35407, -4.3633),
        doubleArrayOf(425.0, 0.28039, 0.35577, -5.3762),
        doubleArrayOf(450.0, 0.28863, 0.35714, -6.7262),
        doubleArrayOf(475.0, 0.29685, 0.35823, -8.5955),
        doubleArrayOf(500.0, 0.30505, 0.35907, -11.324),
        doubleArrayOf(525.0, 0.31320, 0.35968, -15.628),
        doubleArrayOf(550.0, 0.32129, 0.36011, -23.325),
        doubleArrayOf(575.0, 0.32931, 0.36038, -40.770),
        doubleArrayOf(600.0, 0.33724, 0.36051, -116.45)
    )

    // Linearized Bradford chromatic-adaptation matrix (dng_camera_profile `mapWhiteMatrix`).
    private val BRADFORD = doubleArrayOf(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296
    )

    // CIE XYZ (D65) -> linear sRGB (IEC 61966-2-1).
    private val XYZ_D65_TO_SRGB = doubleArrayOf(
        3.2404542, -1.5371385, -0.4985314,
        -0.9692660, 1.8760108, 0.0415560,
        0.0556434, -0.2040259, 1.0572252
    )

    // Bradford chromatic adaptation D50 -> D65 (DNG PCS is D50; sRGB is D65).
    private val CAT_D50_TO_D65 = doubleArrayOf(
        0.9555766, -0.0230393, 0.0631636,
        -0.0282895, 1.0099416, 0.0210077,
        0.0122982, -0.0204830, 1.3299098
    )

    /** A CIE xy chromaticity. */
    data class Chromaticity(val x: Double, val y: Double) {
        fun isUsable(): Boolean = x.isFinite() && y.isFinite() && y > 0.0
    }

    /**
     * Device-true white-balance solution.
     *
     * @property rGain green-normalised red gain (`gGreen == 1`), ready for `COLOR_CORRECTION_GAINS`.
     * @property bGain green-normalised blue gain.
     * @property colorCorrectionTransform row-major 3×3 to store in `COLOR_CORRECTION_TRANSFORM`
     *   (the WB-factored, neutral-preserving camera→linear-sRGB matrix), or `null` when it could not
     *   be derived (singular intermediate) — in which case the payload is incomplete and must not
     *   be used to disable Camera2 auto white balance.
     */
    data class DeviceWhiteBalance(
        val rGain: Float,
        val bGain: Float,
        val colorCorrectionTransform: FloatArray?
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DeviceWhiteBalance) return false
            return rGain == other.rGain && bGain == other.bGain &&
                colorCorrectionTransform?.toList() == other.colorCorrectionTransform?.toList()
        }

        override fun hashCode(): Int {
            var result = rGain.hashCode()
            result = 31 * result + bGain.hashCode()
            result = 31 * result + (colorCorrectionTransform?.toList()?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * DNG `dng_temperature::Get_xy_coord`: (correlated color temperature, tint) → CIE xy.
     * Physically exact along the Planckian locus; the tint slides perpendicular to it in uv space.
     */
    fun kelvinTintToXy(kelvin: Double, tint: Double): Chromaticity {
        if (!kelvin.isFinite() || kelvin <= 0.0 || !tint.isFinite()) {
            return Chromaticity(Double.NaN, Double.NaN)
        }
        val r = 1.0e6 / kelvin
        val offset = tint * (1.0 / TINT_SCALE)

        var index = 0
        while (index <= 29) {
            if (r < TEMP_TABLE[index + 1][R] || index == 29) {
                val f = (TEMP_TABLE[index + 1][R] - r) /
                    (TEMP_TABLE[index + 1][R] - TEMP_TABLE[index][R])

                var u = TEMP_TABLE[index][U] * f + TEMP_TABLE[index + 1][U] * (1.0 - f)
                var v = TEMP_TABLE[index][V] * f + TEMP_TABLE[index + 1][V] * (1.0 - f)

                // Slope vectors for each bounding isotemp line, length-normalized.
                var uu1 = 1.0
                var vv1 = TEMP_TABLE[index][T]
                var uu2 = 1.0
                var vv2 = TEMP_TABLE[index + 1][T]
                val len1 = sqrt(1.0 + vv1 * vv1)
                val len2 = sqrt(1.0 + vv2 * vv2)
                uu1 /= len1; vv1 /= len1
                uu2 /= len2; vv2 /= len2

                var uu3 = uu1 * f + uu2 * (1.0 - f)
                var vv3 = vv1 * f + vv2 * (1.0 - f)
                val len3 = sqrt(uu3 * uu3 + vv3 * vv3)
                uu3 /= len3; vv3 /= len3

                // Slide along the perpendicular by the tint offset.
                u += uu3 * offset
                v += vv3 * offset

                val denom = u - 4.0 * v + 2.0
                if (denom == 0.0 || !denom.isFinite()) return Chromaticity(Double.NaN, Double.NaN)
                return Chromaticity(1.5 * u / denom, v / denom)
            }
            index++
        }
        return Chromaticity(Double.NaN, Double.NaN)
    }

    /**
     * DNG `dng_temperature(const dng_xy_coord&)`: CIE xy → (CCT Kelvin, tint). Inverse of
     * [kelvinTintToXy]. Returns `(NaN, NaN)` if the coordinate cannot be resolved.
     */
    fun xyToKelvinTint(x: Double, y: Double): Pair<Double, Double> {
        val denom = 1.5 - x + 6.0 * y
        if (denom == 0.0 || !denom.isFinite()) return Double.NaN to Double.NaN
        val u = 2.0 * x / denom
        val v = 3.0 * y / denom

        var lastDt = 0.0
        var lastDu = 0.0
        var lastDv = 0.0

        var index = 1
        while (index <= 30) {
            var du = 1.0
            var dv = TEMP_TABLE[index][T]
            val len = sqrt(1.0 + dv * dv)
            du /= len; dv /= len

            var uu = u - TEMP_TABLE[index][U]
            var vv = v - TEMP_TABLE[index][V]
            var dt = -uu * dv + vv * du

            if (dt <= 0.0 || index == 30) {
                if (dt > 0.0) dt = 0.0
                dt = -dt
                val f = if (index == 1) 0.0 else dt / (lastDt + dt)

                val kelvin = 1.0e6 / (TEMP_TABLE[index - 1][R] * f + TEMP_TABLE[index][R] * (1.0 - f))

                uu = u - (TEMP_TABLE[index - 1][U] * f + TEMP_TABLE[index][U] * (1.0 - f))
                vv = v - (TEMP_TABLE[index - 1][V] * f + TEMP_TABLE[index][V] * (1.0 - f))

                du = du * (1.0 - f) + lastDu * f
                dv = dv * (1.0 - f) + lastDv * f
                val len2 = sqrt(du * du + dv * dv)
                du /= len2; dv /= len2

                val tint = (uu * du + vv * dv) * TINT_SCALE
                return kelvin to tint
            }
            lastDt = dt
            lastDu = du
            lastDv = dv
            index++
        }
        return Double.NaN to Double.NaN
    }

    /**
     * Solve the device-true white balance for a manual (Kelvin, tint) from the sensor's DNG
     * calibration. All matrices are row-major 3×3 float arrays of length 9.
     *
     * @param colorMatrix1 / [colorMatrix2] `SENSOR_COLOR_TRANSFORM1/2` (XYZ → camera reference).
     * @param cct1 / [cct2] correlated color temperature (Kelvin) of reference illuminant 1 / 2.
     * @param forwardMatrix1 / [forwardMatrix2] `SENSOR_FORWARD_MATRIX1/2` (camera reference → XYZ D50)
     *   or `null`; when both are absent the [DeviceWhiteBalance.colorCorrectionTransform] is derived
     *   from the color matrix via a Bradford-adapted inverse instead.
     * @param calibration1 / [calibration2] `SENSOR_CALIBRATION_TRANSFORM1/2` or `null` (⇒ identity).
     * @return the device-true solution, or `null` when the calibration is unusable.
     */
    fun solveManualWhiteBalance(
        kelvin: Double,
        tint: Double,
        colorMatrix1: FloatArray,
        colorMatrix2: FloatArray,
        cct1: Double,
        cct2: Double,
        forwardMatrix1: FloatArray? = null,
        forwardMatrix2: FloatArray? = null,
        calibration1: FloatArray? = null,
        calibration2: FloatArray? = null
    ): DeviceWhiteBalance? {
        if (colorMatrix1.size != 9 || colorMatrix2.size != 9) return null
        if (!cct1.isFinite() || !cct2.isFinite() || cct1 <= 0.0 || cct2 <= 0.0) return null

        val neutral = kelvinTintToXy(kelvin, tint)
        if (!neutral.isUsable()) return null

        // Order so illuminant 1 is the cooler reference (lower CCT), matching CameraProfile.cpp.
        val swap = cct1 > cct2
        val cm1 = toDouble(if (swap) colorMatrix2 else colorMatrix1)
        val cm2 = toDouble(if (swap) colorMatrix1 else colorMatrix2)
        val fm1 = (if (swap) forwardMatrix2 else forwardMatrix1)?.takeIf { it.size == 9 }?.let(::toDouble)
        val fm2 = (if (swap) forwardMatrix1 else forwardMatrix2)?.takeIf { it.size == 9 }?.let(::toDouble)
        val ca1 = (if (swap) calibration2 else calibration1)?.takeIf { it.size == 9 }?.let(::toDouble)
            ?: identity3()
        val ca2 = (if (swap) calibration1 else calibration2)?.takeIf { it.size == 9 }?.let(::toDouble)
            ?: identity3()
        val t1 = if (swap) cct2 else cct1
        val t2 = if (swap) cct1 else cct2

        // Interpolation weight in reciprocal-CCT (mired) space (findXyzToCamera).
        val cct = xyToKelvinTint(neutral.x, neutral.y).first
        if (!cct.isFinite()) return null
        val g = when {
            cct <= t1 -> 1.0
            cct >= t2 -> 0.0
            else -> (1.0 / cct - 1.0 / t2) / (1.0 / t1 - 1.0 / t2)
        }

        val colorMatrix = lerpMat(cm1, cm2, g)
        val forwardMatrix = when {
            fm1 != null && fm2 != null -> lerpMat(fm1, fm2, g)
            fm1 != null -> fm1
            fm2 != null -> fm2
            else -> null
        }
        val calibration = lerpMat(ca1, ca2, g)

        // Camera response to the chosen neutral (XYZ → camera), normalized & clamped like the SDK.
        val neutralXyz = xyToXyz(neutral.x, neutral.y)
        val cameraWhite = mulMatVec(colorMatrix, neutralXyz)
        val maxWhite = maxOf(cameraWhite[0], cameraWhite[1], cameraWhite[2])
        if (!(maxWhite > 0.0) || !maxWhite.isFinite()) return null
        val whiteScale = 1.0 / maxWhite
        val cw = DoubleArray(3) { clamp(0.001, cameraWhite[it] * whiteScale, 1.0) }
        if (cw[0] <= 0.0 || cw[1] <= 0.0 || cw[2] <= 0.0) return null

        // Green-normalized gains that neutralize the white (multiply channel c by camWhite_G / camWhite_c).
        val rGain = cw[1] / cw[0]
        val bGain = cw[1] / cw[2]
        if (!rGain.isFinite() || !bGain.isFinite() || rGain <= 0.0 || bGain <= 0.0) return null

        // Full raw-camera → linear-sRGB (WB included), then factor WB out into the gains so the
        // stored CCM is neutral-preserving: CCM · diag(gains) == FULL.
        val cameraToPcs = cameraToPcsMatrix(colorMatrix, forwardMatrix, calibration, cw, neutral)
        val ccm = cameraToPcs?.let { c2p ->
            val xyzD50ToSrgb = mulMat(XYZ_D65_TO_SRGB, CAT_D50_TO_D65)
            val full = mulMat(xyzD50ToSrgb, c2p) // camera RGB -> linear sRGB (WB baked)
            // CCM = FULL · diag(gains)^-1 with gains = [rGain, 1, bGain].
            val out = DoubleArray(9)
            val invGains = doubleArrayOf(1.0 / rGain, 1.0, 1.0 / bGain)
            for (row in 0..2) for (col in 0..2) {
                out[row * 3 + col] = full[row * 3 + col] * invGains[col]
            }
            if (out.all { it.isFinite() }) FloatArray(9) { out[it].toFloat() } else null
        }

        return DeviceWhiteBalance(
            rGain = clamp(0.1, rGain, 10.0).toFloat(),
            bGain = clamp(0.1, bGain, 10.0).toFloat(),
            colorCorrectionTransform = ccm
        )
    }

    /**
     * Raw-camera-RGB → XYZ(D50), white balance included. Uses the forward-matrix path when a
     * forward matrix is present (`forwardMatrix · diag(refCameraWhite)⁻¹ · calibration⁻¹`, the DNG
     * `cameraToPcs`), otherwise inverts the Bradford-adapted color matrix. Returns `null` if any
     * required inverse is singular.
     */
    private fun cameraToPcsMatrix(
        colorMatrix: DoubleArray,
        forwardMatrix: DoubleArray?,
        calibration: DoubleArray,
        cameraWhite: DoubleArray,
        neutral: Chromaticity
    ): DoubleArray? {
        if (forwardMatrix != null) {
            val invCalibration = invert3x3(calibration) ?: return null
            val refCameraWhite = mulMatVec(invCalibration, cameraWhite)
            if (refCameraWhite.any { it <= 0.0 || !it.isFinite() }) return null
            val diagInv = diagInverse(refCameraWhite) ?: return null
            return mulMat(mulMat(forwardMatrix, diagInv), invCalibration)
        }
        // No forward matrix: PCS→camera then invert (dng cameraToPcs fallback branch).
        val pcsToCamera = mulMat(colorMatrix, mapWhiteMatrix(Chromaticity(D50_X, D50_Y), neutral) ?: return null)
        return invert3x3(pcsToCamera)
    }

    /**
     * DNG `dng_camera_profile::mapWhiteMatrix` — linearized-Bradford chromatic adaptation from
     * [white1] to [white2] with the cone-gain ratios clamped to `[0.1, 10]`. Returns `null` if the
     * Bradford matrix cannot be inverted (it always can, but we stay defensive).
     */
    private fun mapWhiteMatrix(white1: Chromaticity, white2: Chromaticity): DoubleArray? {
        val w1 = mulMatVec(BRADFORD, xyToXyz(white1.x, white1.y))
        val w2 = mulMatVec(BRADFORD, xyToXyz(white2.x, white2.y))
        for (i in 0..2) { w1[i] = maxOf(w1[i], 0.0); w2[i] = maxOf(w2[i], 0.0) }
        val a = DoubleArray(9)
        a[0] = clamp(0.1, if (w1[0] > 0.0) w2[0] / w1[0] else 10.0, 10.0)
        a[4] = clamp(0.1, if (w1[1] > 0.0) w2[1] / w1[1] else 10.0, 10.0)
        a[8] = clamp(0.1, if (w1[2] > 0.0) w2[2] / w1[2] else 10.0, 10.0)
        val invBradford = invert3x3(BRADFORD) ?: return null
        return mulMat(mulMat(invBradford, a), BRADFORD)
    }

    // ─── small matrix / vector helpers (row-major 3×3, length-3 vectors) ────────────────────────

    private const val R = 0
    private const val U = 1
    private const val V = 2
    private const val T = 3

    private fun toDouble(m: FloatArray): DoubleArray = DoubleArray(9) { m[it].toDouble() }

    private fun identity3(): DoubleArray =
        doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

    /** CIE xy (Y=1) → XYZ, matching Color.cpp XYToXYZ (clamps degenerate coordinates). */
    private fun xyToXyz(x: Double, y: Double): DoubleArray {
        var px = clamp(0.000001, x, 0.999999)
        var py = clamp(0.000001, y, 0.999999)
        if (px + py > 0.999999) {
            val scale = 0.999999 / (px + py)
            px *= scale; py *= scale
        }
        return doubleArrayOf(px / py, 1.0, (1.0 - px - py) / py)
    }

    private fun lerpMat(a: DoubleArray, b: DoubleArray, g: Double): DoubleArray = when {
        g >= 1.0 -> a.copyOf()
        g <= 0.0 -> b.copyOf()
        else -> DoubleArray(9) { g * a[it] + (1.0 - g) * b[it] }
    }

    private fun mulMat(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (row in 0..2) for (col in 0..2) {
            var s = 0.0
            for (k in 0..2) s += a[row * 3 + k] * b[k * 3 + col]
            out[row * 3 + col] = s
        }
        return out
    }

    private fun mulMatVec(m: DoubleArray, v: DoubleArray): DoubleArray = doubleArrayOf(
        m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
        m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
        m[6] * v[0] + m[7] * v[1] + m[8] * v[2]
    )

    private fun diagInverse(v: DoubleArray): DoubleArray? {
        if (v.any { it == 0.0 || !it.isFinite() }) return null
        return doubleArrayOf(1.0 / v[0], 0.0, 0.0, 0.0, 1.0 / v[1], 0.0, 0.0, 0.0, 1.0 / v[2])
    }

    private fun invert3x3(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (det == 0.0 || !det.isFinite() || abs(det) < 1e-12) return null
        val invDet = 1.0 / det
        return doubleArrayOf(
            (e * i - f * h) * invDet, (c * h - b * i) * invDet, (b * f - c * e) * invDet,
            (f * g - d * i) * invDet, (a * i - c * g) * invDet, (c * d - a * f) * invDet,
            (d * h - e * g) * invDet, (b * g - a * h) * invDet, (a * e - b * d) * invDet
        )
    }

    private fun clamp(lo: Double, x: Double, hi: Double): Double = when {
        x < lo -> lo
        x > hi -> hi
        else -> x
    }
}
