package com.neoproc.neoproccamx.ui

import android.hardware.camera2.TotalCaptureResult
import android.util.Log
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.niv99.Niv99DetailProfile
import com.neoproc.neoproccamx.niv99.Niv99ExecutionBoundaryPolicy
import com.neoproc.neoproccamx.niv99.Niv99MetadataStatus
import com.neoproc.neoproccamx.niv99.Niv99OperatingMode
import com.neoproc.neoproccamx.niv99.Niv99PreviewTuningContinuity
import com.neoproc.neoproccamx.niv99.Niv99SignalDomain
import com.neoproc.neoproccamx.pipeline.FramePipelineManager
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile

/** Mutable, publisher-owned route state reused for every Camera2 result. */
internal class Niv99Camera2RouteState {
    var rawVideoActive: Boolean = false
    var proModeActive: Boolean = false
    var sensorLinearNdrActive: Boolean = false
    var hdrMode: String = "OFF"
    var detailProfile: VideoDetailProfile = VideoDetailProfile.NATURAL_DETAIL
    var width: Int = 1
    var height: Int = 1
    var targetFps: Int = 1
    var colorProfile: String = ""
    var videoColorSpace: String = ""
    var logEnabled: Boolean = false
    var surfaceGeneration: Long = 0L
    var manualKelvin: Int = 0
    var manualTint: Int = 0
}

/**
 * Owns the allocation-free UI-to-NIV99 Camera2 boundary. The route writer is created once and the
 * mutable state is reused under the same monitor as publication, so a frame observes one coherent
 * mode/profile/control snapshot and cannot combine WB data from different capture results.
 */
internal class Niv99Camera2MetadataPublisher(
    private val pipeline: FramePipelineManager,
    private val camera: CameraController,
    private val writeRoute: Niv99Camera2RouteState.() -> Unit
) {
    private val route = Niv99Camera2RouteState()

    /**
     * Captures the same route authority used by Camera2 result publication for the frame currently
     * entering the preview compositor. A caller-owned object avoids hot-path allocation.
     */
    @Synchronized
    fun copyCurrentPreviewContinuity(
        sessionGeneration: Long,
        output: Niv99PreviewTuningContinuity
    ): Boolean {
        if (!Niv99ExecutionBoundaryPolicy.LIVE_PREVIEW_PIXEL_PROCESSING_ENABLED) {
            output.clear()
            return false
        }
        val cameraId = camera.currentCameraId
        if (cameraId.isBlank() || sessionGeneration < 0L) {
            output.clear()
            return false
        }
        route.writeRoute()
        return output.set(
            cameraId = cameraId,
            sessionGeneration = sessionGeneration,
            mode = route.operatingMode(),
            domain = route.signalDomain(),
            detailProfile = route.niv99DetailProfile(),
            width = route.width.coerceAtLeast(1),
            height = route.height.coerceAtLeast(1),
            targetFps = route.targetFps.coerceAtLeast(1),
            colorProfileId = route.colorProfileId(),
            surfaceGeneration = route.surfaceGeneration,
            codecGeneration = sessionGeneration,
            manualKelvin = route.manualKelvin.takeIf { it > 0 } ?: 0,
            manualTint = if (route.manualKelvin > 0) route.manualTint else 0,
            awbLockRequested = camera.isAeAwbLocked()
        )
    }

    @Synchronized
    fun publish(result: TotalCaptureResult, sessionGeneration: Long) {
        if (!Niv99ExecutionBoundaryPolicy.LIVE_CAPTURE_METADATA_ENABLED) return
        val cameraId = camera.currentCameraId
        val characteristics = camera.getCameraCharacteristics()
        if (cameraId.isBlank() || characteristics == null) return
        route.writeRoute()
        val status = try {
            pipeline.onNiv99CaptureResult(
                result = result,
                cameraId = cameraId,
                characteristics = characteristics,
                captureSessionGeneration = sessionGeneration,
                mode = route.operatingMode(),
                signalDomain = route.signalDomain(),
                detailProfile = route.niv99DetailProfile(),
                width = route.width.coerceAtLeast(1),
                height = route.height.coerceAtLeast(1),
                targetFps = route.targetFps.coerceAtLeast(1),
                colorProfileId = route.colorProfileId(),
                surfaceGeneration = route.surfaceGeneration,
                codecGeneration = sessionGeneration,
                manualKelvin = route.manualKelvin.takeIf { it > 0 } ?: 0,
                manualTint = if (route.manualKelvin > 0) route.manualTint else 0,
                awbLockRequested = camera.isAeAwbLocked()
            )
        } catch (exception: Exception) {
            Log.e(TAG, "NIV99 rejected Camera2 metadata", exception)
            return
        }
        if (status != Niv99MetadataStatus.VALID &&
            (result.frameNumber == 0L || result.frameNumber % REJECTION_LOG_INTERVAL_FRAMES == 0L)
        ) {
            Log.w(
                TAG,
                "NIV99_METADATA_REJECTED status=$status camera=$cameraId " +
                    "generation=$sessionGeneration frame=${result.frameNumber}"
            )
        }
    }

    private fun Niv99Camera2RouteState.operatingMode(): Niv99OperatingMode = when {
        rawVideoActive -> Niv99OperatingMode.RAW_PREVIEW
        proModeActive -> Niv99OperatingMode.PRO
        else -> Niv99OperatingMode.VIDEO
    }

    private fun Niv99Camera2RouteState.signalDomain(): Niv99SignalDomain = when {
        rawVideoActive || sensorLinearNdrActive -> Niv99SignalDomain.SENSOR_LINEAR_DEMOSAICED
        !hdrMode.equals("OFF", ignoreCase = true) -> Niv99SignalDomain.P010_YUV
        else -> Niv99SignalDomain.DISPLAY_RGB
    }

    private fun Niv99Camera2RouteState.niv99DetailProfile(): Niv99DetailProfile = when (detailProfile) {
        VideoDetailProfile.FOCUSED_DETAIL -> Niv99DetailProfile.FOCUSED
        VideoDetailProfile.NATURAL_DETAIL -> Niv99DetailProfile.NATURAL
        VideoDetailProfile.EXTREME_DETAIL -> Niv99DetailProfile.EXTREME
    }

    private fun Niv99Camera2RouteState.colorProfileId(): Long {
        var hash = 17L
        hash = hash * 31L + colorProfile.hashCode().toLong()
        hash = hash * 31L + videoColorSpace.hashCode().toLong()
        hash = hash * 31L + hdrMode.hashCode().toLong()
        hash = hash * 31L + if (logEnabled) 1L else 0L
        hash = hash and Long.MAX_VALUE
        return if (hash == 0L) 1L else hash
    }

    private companion object {
        const val TAG = "Niv99Camera2Publisher"
        const val REJECTION_LOG_INTERVAL_FRAMES = 120L
    }
}
