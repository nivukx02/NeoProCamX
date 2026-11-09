package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureResult

sealed class CameraState {
    object Closed    : CameraState()
    object Opening   : CameraState()
    object Configuring : CameraState()
    object Open      : CameraState()
    object Streaming : CameraState()
    object Recording : CameraState()
    object PhotoCapture : CameraState()
    object Reconfiguring : CameraState()
    object Closing   : CameraState()
    data class Error(val msg: String) : CameraState()
}

data class CameraParams(
    val iso: Int = -1, val exposureNs: Long = -1L, val wbKelvin: Int = -1,
    val focusDist: Float = -1f, val ev: Int = 0, val flash: FlashMode = FlashMode.OFF,
    val zoom: Float = 1f, val fps: Int = 60
)

data class LiveResult(
    val iso: Int, val exposureNs: Long,
    val afState: Int, val aeState: Int, val awbState: Int, val focusDist: Float,
    val timestampNs: Long = 0L
) {
    val shutterLabel: String get() {
        if (exposureNs <= 0) return "Auto"
        val s = exposureNs / 1_000_000_000.0
        return if (s >= 1.0) "%.1fs".format(s) else "1/${(1.0/s).toInt()}"
    }
    val isoLabel: String  get() = if (iso > 0) "ISO $iso" else "ISO Auto"
    val afFocused: Boolean get() =
        afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
        afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
}

data class LensInfo(val cameraId: String, val lensFacing: Int, val focalLength: Float)

enum class FlashMode { OFF, ON, TORCH, AUTO }

data class StillCaptureOverrides(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val evCompensation: Int? = null,
    val aeLock: Boolean = false,
    val awbLock: Boolean = false
)

enum class StillCaptureRequestPhase {
    SINGLE_PRIMARY,
    HDR_BRACKET,
    HDR_RAW_COMPANION
}

/** Immutable identity carried by Camera2 from request submission to result/failure delivery. */
data class StillCaptureRequestToken(
    val captureId: Int,
    val phase: StillCaptureRequestPhase,
    val frameIndex: Int,
    val frameCount: Int,
    val expectsJpeg: Boolean,
    val expectsRaw: Boolean,
    /** Controller-owned generation; zero is reserved for an unsubmitted capture plan. */
    val operationEpoch: Long = 0L
) {
    init {
        require(captureId > 0) { "captureId must be positive" }
        require(frameCount > 0) { "frameCount must be positive" }
        require(frameIndex in 0 until frameCount) { "frameIndex must be inside frameCount" }
        require(expectsJpeg || expectsRaw) { "still request must target JPEG or RAW" }
        require(operationEpoch >= 0L) { "operationEpoch must not be negative" }
    }
}

data class StillCaptureFrameIdentity(
    val token: StillCaptureRequestToken,
    val sensorTimestampNs: Long,
    val frameNumber: Long
)

data class StillCaptureFailure(
    val token: StillCaptureRequestToken,
    val message: String,
    val frameNumber: Long? = null
)
