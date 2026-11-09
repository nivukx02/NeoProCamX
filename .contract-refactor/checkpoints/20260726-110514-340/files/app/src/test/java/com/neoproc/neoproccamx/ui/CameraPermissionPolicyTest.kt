package com.neoproc.neoproccamx.ui

import android.Manifest
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPermissionPolicyTest {

    @Test
    fun requiredPermissions_includesWriteExternalStorageOnAndroidPAndBelow() {
        val result = CameraPermissionPolicy.requiredPermissions(Build.VERSION_CODES.P)

        assertEquals(
            listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ),
            result
        )
    }

    @Test
    fun requiredPermissions_excludesWriteExternalStorageOnAndroidQAndAbove() {
        val result = CameraPermissionPolicy.requiredPermissions(Build.VERSION_CODES.Q)

        assertEquals(
            listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ),
            result
        )
    }

    @Test
    fun requiredPermissions_includesPostNotificationsOnAndroidTiramisuAndAbove() {
        val result = CameraPermissionPolicy.requiredPermissions(Build.VERSION_CODES.TIRAMISU)

        assertEquals(
            listOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            ),
            result
        )
    }

    @Test
    fun isCameraGranted_acceptsResultMapOrExistingPermission() {
        assertTrue(
            CameraPermissionPolicy.isCameraGranted(
                result = mapOf(Manifest.permission.CAMERA to true),
                hasCameraPermission = false
            )
        )
        assertTrue(
            CameraPermissionPolicy.isCameraGranted(
                result = emptyMap(),
                hasCameraPermission = true
            )
        )
        assertFalse(
            CameraPermissionPolicy.isCameraGranted(
                result = emptyMap(),
                hasCameraPermission = false
            )
        )
        assertFalse(
            CameraPermissionPolicy.isCameraGranted(
                result = mapOf(Manifest.permission.CAMERA to false),
                hasCameraPermission = false
            )
        )
    }
}
