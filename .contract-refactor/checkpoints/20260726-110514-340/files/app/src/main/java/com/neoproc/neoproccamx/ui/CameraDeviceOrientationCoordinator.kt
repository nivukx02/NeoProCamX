package com.neoproc.neoproccamx.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import com.neoproc.neoproccamx.utils.OrientationManager

internal data class CameraDeviceOrientationUpdate(
    val uiRotationDegrees: Int,
    val cameraRotationDegrees: Int
)

/**
 * Owns physical-orientation sensing and display-rotation fallback for the portrait-locked camera UI.
 * Capture and UI rotations are dispatched together so photos, videos, overlays, and controls cannot
 * observe different orientation samples during a configuration or foreground transition.
 */
internal class CameraDeviceOrientationCoordinator(
    activity: Activity,
    private val onOrientationUpdate: (CameraDeviceOrientationUpdate) -> Unit
) {
    private val displayRotationDegrees = AndroidDisplayRotationDegrees(activity)
    private var lastPhysicalOrientationDegrees: Int? = null
    private var lastDispatchedUpdate: CameraDeviceOrientationUpdate? = null
    private val listener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            lastPhysicalOrientationDegrees = orientation
            refresh()
        }
    }

    fun start() {
        listener.enable()
        refresh(force = true)
    }

    fun stop() {
        listener.disable()
    }

    fun refresh(force: Boolean = false) {
        val update = CameraDeviceOrientationPolicy.resolve(
            physicalOrientationDegrees = lastPhysicalOrientationDegrees,
            displayRotationDegrees = displayRotationDegrees.read()
        )
        if (!force && update == lastDispatchedUpdate) return
        lastDispatchedUpdate = update
        onOrientationUpdate(update)
    }
}

internal object CameraDeviceOrientationPolicy {
    fun resolve(
        physicalOrientationDegrees: Int?,
        displayRotationDegrees: Int
    ): CameraDeviceOrientationUpdate {
        val uiRotationDegrees = if (physicalOrientationDegrees == null) {
            OrientationManager.quantizePhysicalDeviceRotation(
                orientationDegrees = displayRotationDegrees,
                fallbackRotationDegrees = displayRotationDegrees
            )
        } else {
            OrientationManager.quantizePhysicalDeviceRotation(
                orientationDegrees = physicalOrientationDegrees,
                fallbackRotationDegrees = displayRotationDegrees
            )
        }
        return CameraDeviceOrientationUpdate(
            uiRotationDegrees = uiRotationDegrees,
            cameraRotationDegrees = CameraCaptureRotationPolicy.resolveCameraDeviceRotationDegrees(
                physicalDeviceRotationDegrees = uiRotationDegrees,
                displayRotationDegrees = displayRotationDegrees
            )
        )
    }
}

private class AndroidDisplayRotationDegrees(activity: Activity) {
    private val activity = activity
    private val windowManager =
        activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun read(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}
