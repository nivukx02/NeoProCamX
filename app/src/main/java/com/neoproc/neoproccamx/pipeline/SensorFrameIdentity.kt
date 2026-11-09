package com.neoproc.neoproccamx.pipeline

import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import java.util.concurrent.atomic.AtomicReference

enum class FrameIdentitySource {
    IMAGE_TIMESTAMP,
    CAMERA2_CAPTURE_RESULT
}

/**
 * Stable identity for a sensor exposure. [ingressSequence] is local bookkeeping only; hardware
 * [frameNumber] and [sensorTimestampNs] remain authoritative whenever Camera2 supplies them.
 */
data class FrameIdentity(
    val sessionGeneration: Long,
    val ingressSequence: Long,
    val frameNumber: Long?,
    val sensorTimestampNs: Long,
    val source: FrameIdentitySource
) {
    val persistentId: String
        get() = "session=${if (sessionGeneration == UNASSIGNED_SESSION_GENERATION) "unassigned" else sessionGeneration}" +
            "/frame=${frameNumber ?: "image-$ingressSequence"}/sensor=$sensorTimestampNs"

    val isHardwareAuthoritative: Boolean
        get() = sessionGeneration != UNASSIGNED_SESSION_GENERATION &&
            frameNumber != null && sensorTimestampNs > 0L &&
            source == FrameIdentitySource.CAMERA2_CAPTURE_RESULT

    companion object {
        /** Image callbacks do not identify their CameraCaptureSession. Never guess a generation. */
        const val UNASSIGNED_SESSION_GENERATION: Long = Long.MIN_VALUE
    }
}

data class CameraRationalValue(val numerator: Int, val denominator: Int)

data class FrameWhiteBalanceMetadata(
    val awbMode: Int?,
    val awbState: Int?,
    val awbLocked: Boolean?,
    val redGain: Float?,
    val greenEvenGain: Float?,
    val greenOddGain: Float?,
    val blueGain: Float?,
    /** Row-major 3x3 Camera2 color-correction transform. */
    val colorTransform: List<CameraRationalValue>?,
    val neutralColorPoint: List<CameraRationalValue>?
)

data class FrameFocusMetadata(
    val afMode: Int?,
    val afState: Int?,
    val focusDistanceDiopters: Float?,
    val lensState: Int?
)

data class FrameLensMetadata(
    val activePhysicalCameraId: String?,
    val focalLengthMm: Float?,
    val aperture: Float?,
    val filterDensity: Float?,
    val opticalStabilizationMode: Int?
)

/**
 * Static calibration is copied once by the camera owner, then every frame reuses this immutable handle.
 * This avoids retaining CameraCharacteristics or repeatedly cloning calibration arrays on the hot path.
 */
data class CameraCalibrationHandle(
    val cameraId: String,
    val revision: Long,
    val intrinsicCalibration: List<Float> = emptyList(),
    val lensDistortion: List<Float> = emptyList(),
    val poseRotation: List<Float> = emptyList(),
    val poseTranslation: List<Float> = emptyList()
)

data class FrameCameraMetadata(
    val exposureStartTimestampNs: Long,
    val exposureDurationNs: Long?,
    val frameDurationNs: Long?,
    val rollingShutterSkewNs: Long?,
    val iso: Int?,
    val aeState: Int?,
    val flashState: Int?,
    val whiteBalance: FrameWhiteBalanceMetadata,
    val focus: FrameFocusMetadata,
    val lens: FrameLensMetadata,
    val calibration: CameraCalibrationHandle?
)

internal data class CapturedFrameMetadata(
    val identity: FrameIdentity,
    val camera: FrameCameraMetadata
)

/** Stable handle whose canonical Camera2 identity can arrive after an Image callback. */
class FrameIdentityHandle internal constructor(initial: FrameIdentity) {
    private val current = AtomicReference(initial)

    fun snapshot(): FrameIdentity = current.get()

    internal fun mergeCamera2(authoritative: FrameIdentity): Boolean {
        require(authoritative.source == FrameIdentitySource.CAMERA2_CAPTURE_RESULT)
        while (true) {
            val previous = current.get()
            if (previous.sensorTimestampNs > 0L &&
                authoritative.sensorTimestampNs > 0L &&
                previous.sensorTimestampNs != authoritative.sensorTimestampNs
            ) {
                return false
            }
            if (previous.sessionGeneration != FrameIdentity.UNASSIGNED_SESSION_GENERATION &&
                previous.sessionGeneration != authoritative.sessionGeneration
            ) {
                return false
            }
            if (previous.isHardwareAuthoritative) {
                return previous.frameNumber == authoritative.frameNumber &&
                    previous.sessionGeneration == authoritative.sessionGeneration
            }
            val merged = authoritative.copy(ingressSequence = previous.ingressSequence)
            if (current.compareAndSet(previous, merged)) return true
        }
    }
}

internal object Camera2FrameMetadataExtractor {
    fun extract(
        result: TotalCaptureResult,
        sessionGeneration: Long,
        ingressSequence: Long,
        calibration: CameraCalibrationHandle?
    ): CapturedFrameMetadata? {
        val timestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return null
        if (timestampNs <= 0L) return null
        val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val neutralPoint = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        val identity = FrameIdentity(
            sessionGeneration = sessionGeneration,
            ingressSequence = ingressSequence,
            frameNumber = result.frameNumber.takeIf { it >= 0L },
            sensorTimestampNs = timestampNs,
            source = FrameIdentitySource.CAMERA2_CAPTURE_RESULT
        )
        return CapturedFrameMetadata(
            identity = identity,
            camera = FrameCameraMetadata(
                exposureStartTimestampNs = timestampNs,
                exposureDurationNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
                frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION),
                rollingShutterSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW),
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY),
                aeState = result.get(CaptureResult.CONTROL_AE_STATE),
                flashState = result.get(CaptureResult.FLASH_STATE),
                whiteBalance = FrameWhiteBalanceMetadata(
                    awbMode = result.get(CaptureResult.CONTROL_AWB_MODE),
                    awbState = result.get(CaptureResult.CONTROL_AWB_STATE),
                    awbLocked = result.get(CaptureResult.CONTROL_AWB_LOCK),
                    redGain = gains?.red,
                    greenEvenGain = gains?.greenEven,
                    greenOddGain = gains?.greenOdd,
                    blueGain = gains?.blue,
                    colorTransform = transform?.let { matrix ->
                        buildList(9) {
                            for (row in 0..2) {
                                for (column in 0..2) {
                                    val value = matrix.getElement(column, row)
                                    add(CameraRationalValue(value.numerator, value.denominator))
                                }
                            }
                        }
                    },
                    neutralColorPoint = neutralPoint?.map { value ->
                        CameraRationalValue(value.numerator, value.denominator)
                    }
                ),
                focus = FrameFocusMetadata(
                    afMode = result.get(CaptureResult.CONTROL_AF_MODE),
                    afState = result.get(CaptureResult.CONTROL_AF_STATE),
                    focusDistanceDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                    lensState = result.get(CaptureResult.LENS_STATE)
                ),
                lens = FrameLensMetadata(
                    activePhysicalCameraId = result.get(
                        CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID
                    ),
                    focalLengthMm = result.get(CaptureResult.LENS_FOCAL_LENGTH),
                    aperture = result.get(CaptureResult.LENS_APERTURE),
                    filterDensity = result.get(CaptureResult.LENS_FILTER_DENSITY),
                    opticalStabilizationMode = result.get(
                        CaptureResult.LENS_OPTICAL_STABILIZATION_MODE
                    )
                ),
                calibration = calibration
            )
        )
    }
}
