package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.ColorSpaceTransform

/**
 * The sensor's DNG color calibration, read once from [CameraCharacteristics] and cached, plus the
 * bridge into the device-true white-balance solver [DngWhiteBalanceMath].
 *
 * The Camera2 keys map one-to-one onto the DNG tags MotionCam consumes:
 *  - [CameraCharacteristics.SENSOR_COLOR_TRANSFORM1]/`2`  → `ColorMatrix1/2` (XYZ → camera reference)
 *  - [CameraCharacteristics.SENSOR_FORWARD_MATRIX1]/`2`   → `ForwardMatrix1/2` (camera ref → XYZ D50)
 *  - [CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1]/`2` → per-device `CameraCalibration1/2`
 *  - [CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1]/`2`  → reference illuminant → CCT (Kelvin)
 *
 * [fromCharacteristics] returns `null` unless BOTH reference color matrices and BOTH reference
 * illuminants are present and usable, so a device that does not publish full DNG calibration cleanly
 * falls back to the legacy [WhiteBalanceConversion] approximation (strict no-downgrade).
 */
internal class DeviceColorCalibration private constructor(
    private val colorMatrix1: FloatArray,
    private val colorMatrix2: FloatArray,
    private val cct1: Double,
    private val cct2: Double,
    private val forwardMatrix1: FloatArray?,
    private val forwardMatrix2: FloatArray?,
    private val calibration1: FloatArray?,
    private val calibration2: FloatArray?
) {

    /**
     * Device-true green-normalised gains + optional paired CCM for a manual (Kelvin, tint), or
     * `null` when the solve is numerically unusable (⇒ caller falls back to the legacy path).
     */
    fun solve(kelvin: Int, tint: Int): DngWhiteBalanceMath.DeviceWhiteBalance? =
        DngWhiteBalanceMath.solveManualWhiteBalance(
            kelvin = kelvin.toDouble(),
            tint = tint.toDouble(),
            colorMatrix1 = colorMatrix1,
            colorMatrix2 = colorMatrix2,
            cct1 = cct1,
            cct2 = cct2,
            forwardMatrix1 = forwardMatrix1,
            forwardMatrix2 = forwardMatrix2,
            calibration1 = calibration1,
            calibration2 = calibration2
        )

    companion object {

        fun fromCharacteristics(characteristics: CameraCharacteristics?): DeviceColorCalibration? {
            val c = characteristics ?: return null
            val cm1 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.let(::toFloatArray) ?: return null
            val cm2 = c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.let(::toFloatArray) ?: return null
            val illum1 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)
                ?.let(::illuminantToCct) ?: return null
            val illum2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)
                ?.toInt()?.let(::illuminantToCct) ?: return null
            // Two distinct reference illuminants are required for the dual-illuminant interpolation.
            if (illum1 == illum2) return null
            return DeviceColorCalibration(
                colorMatrix1 = cm1,
                colorMatrix2 = cm2,
                cct1 = illum1,
                cct2 = illum2,
                forwardMatrix1 = c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1)?.let(::toFloatArray),
                forwardMatrix2 = c.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2)?.let(::toFloatArray),
                calibration1 = c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.let(::toFloatArray),
                calibration2 = c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.let(::toFloatArray)
            )
        }

        /** Row-major 3×3 float array (index = row*3 + col) from a Camera2 [ColorSpaceTransform]. */
        private fun toFloatArray(t: ColorSpaceTransform): FloatArray = FloatArray(9) { i ->
            // ColorSpaceTransform.getElement(column, row).
            t.getElement(i % 3, i / 3).toFloat()
        }

        /**
         * Reference-illuminant enum → correlated color temperature (Kelvin), per the DNG / EXIF
         * LightSource table. Covers the values phone sensors actually report; returns `null` for
         * unknown values so the whole calibration is rejected (fall back to legacy).
         */
        fun illuminantToCct(illuminant: Int): Double? = when (illuminant) {
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN -> 2856.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN -> 3200.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT -> 3450.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT -> 4230.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B -> 4874.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT -> 5000.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D50 -> 5003.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D55,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_FLASH -> 5503.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT -> 6430.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D65,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER -> 6504.0
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_D75,
            CameraMetadata.SENSOR_REFERENCE_ILLUMINANT1_SHADE -> 7504.0
            else -> null
        }
    }
}
