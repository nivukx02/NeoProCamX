package com.neoproc.neoproccamx.capture

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult

/**
 * RawDngMetadataContract — completeness contract for the colour-defining DNG tags.
 *
 * A DNG only renders the *same neutral* on a third party's machine if it is
 * self-describing: black/white levels, both colour matrices and their calibration
 * illuminants, the forward matrices, and the noise profile (Adobe DNG 1.6 / SMPTE).
 *
 * Android's `DngCreator` writes those tags automatically — but only from whatever the
 * `CameraCharacteristics` + `TotalCaptureResult` it is handed actually contain. So the
 * real contract is upstream: did the capture metadata carry those keys at all? This
 * validator turns that into a checkable set, so the capture path can assert/telemeter
 * completeness instead of silently shipping a colour-ambiguous master.
 *
 * The [evaluate] core is pure (JVM-unit-testable); [fromCameraMetadata] is the thin,
 * device-side adapter that reads the real camera2 keys.
 */
object RawDngMetadataContract {

    enum class Tag(val dngField: String) {
        BLACK_LEVEL("BlackLevel"),
        WHITE_LEVEL("WhiteLevel"),
        COLOR_MATRIX("ColorMatrix1/2"),
        FORWARD_MATRIX("ForwardMatrix1/2"),
        CALIBRATION_ILLUMINANT("CalibrationIlluminant1/2"),
        NOISE_PROFILE("NoiseProfile"),
        NEUTRAL_WHITE("AsShotNeutral")
    }

    /** Tags required for a colour-exact, self-describing cinema master. */
    val REQUIRED: Set<Tag> = setOf(
        Tag.BLACK_LEVEL,
        Tag.WHITE_LEVEL,
        Tag.COLOR_MATRIX,
        Tag.FORWARD_MATRIX,
        Tag.CALIBRATION_ILLUMINANT,
        Tag.NOISE_PROFILE
    )

    data class Completeness(
        val present: Set<Tag>,
        val missing: Set<Tag>,
        val required: Set<Tag>
    ) {
        val isComplete: Boolean get() = missing.isEmpty()

        /** Fraction of required tags present, 0..1. */
        val ratio: Float
            get() = if (required.isEmpty()) 1f else present.intersect(required).size.toFloat() / required.size.toFloat()

        val summary: String
            get() = buildString {
                append("DNG metadata ")
                append(present.intersect(required).size)
                append('/')
                append(required.size)
                if (missing.isEmpty()) {
                    append(" complete")
                } else {
                    append(" missing=")
                    append(missing.joinToString(",") { it.name })
                }
            }
    }

    /**
     * Pure evaluation: given the set of tags actually present, compute completeness
     * against [required].
     */
    fun evaluate(present: Set<Tag>, required: Set<Tag> = REQUIRED): Completeness {
        val missing = required.filterNot { present.contains(it) }.toSet()
        return Completeness(present = present, missing = missing, required = required)
    }

    /**
     * Device-side adapter: derive the present-tag set from real camera2 metadata, then
     * [evaluate]. `DngCreator` will emit each present tag; a missing one means the HAL
     * did not characterise it (and the DNG falls back to defaults for that field).
     */
    fun fromCameraMetadata(
        characteristics: CameraCharacteristics,
        result: TotalCaptureResult,
        required: Set<Tag> = REQUIRED
    ): Completeness {
        val present = mutableSetOf<Tag>()

        if (characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN) != null ||
            result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL) != null
        ) present.add(Tag.BLACK_LEVEL)

        if (characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) != null ||
            result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) != null
        ) present.add(Tag.WHITE_LEVEL)

        if (characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1) != null ||
            characteristics.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2) != null
        ) present.add(Tag.COLOR_MATRIX)

        if (characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1) != null ||
            characteristics.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2) != null
        ) present.add(Tag.FORWARD_MATRIX)

        if (characteristics.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) != null) {
            present.add(Tag.CALIBRATION_ILLUMINANT)
        }

        if (result.get(CaptureResult.SENSOR_NOISE_PROFILE) != null) present.add(Tag.NOISE_PROFILE)

        if (result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT) != null ||
            result.get(CaptureResult.COLOR_CORRECTION_GAINS) != null
        ) present.add(Tag.NEUTRAL_WHITE)

        return evaluate(present, required)
    }
}
