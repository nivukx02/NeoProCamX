package com.neoproc.neoproccamx.ui

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build

internal object CameraPermissionPolicy {
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
}
