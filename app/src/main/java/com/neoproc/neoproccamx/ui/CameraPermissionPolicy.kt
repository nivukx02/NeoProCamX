package com.neoproc.neoproccamx.ui

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build

internal object CameraPermissionPolicy {
    enum class DenialAction {
        SHOW_RATIONALE,
        SHOW_APP_SETTINGS
    }

    @SuppressLint("InlinedApi")
    fun requiredPermissions(sdkInt: Int): List<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (sdkInt <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        // Android 13+ requires POST_NOTIFICATIONS to show the ongoing recording
        // foreground-service notification. Requesting it does not gate camera open
        // (isCameraGranted checks CAMERA only), so denial never blocks capture.
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun isCameraGranted(
        result: Map<String, Boolean>,
        hasCameraPermission: Boolean
    ): Boolean =
        result[Manifest.permission.CAMERA] == true || hasCameraPermission

    /**
     * Launch the platform prompt once per Activity permission cycle. The in-flight and dialog guards
     * prevent onResume (which also runs while a permission surface closes) from opening a second
     * launcher and producing an OEM-specific prompt loop.
     */
    fun shouldLaunchRequest(
        hasCameraPermission: Boolean,
        requestInFlight: Boolean,
        requestAttempted: Boolean,
        denialUiVisible: Boolean
    ): Boolean =
        !hasCameraPermission &&
            !requestInFlight &&
            !requestAttempted &&
            !denialUiVisible

    /** A denied permission that Android can no longer ask for must recover through App Info. */
    fun denialAction(shouldShowRationale: Boolean): DenialAction =
        if (shouldShowRationale) DenialAction.SHOW_RATIONALE else DenialAction.SHOW_APP_SETTINGS
}
