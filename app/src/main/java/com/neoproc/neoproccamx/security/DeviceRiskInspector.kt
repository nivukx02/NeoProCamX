package com.neoproc.neoproccamx.security

import android.os.Build
import android.os.Debug
import java.io.File

data class DeviceRiskSignals(
    val rooted: Boolean,
    val emulator: Boolean,
    val debuggerAttached: Boolean,
    val detectedRootPaths: Set<String>,
    val magiskDetected: Boolean,
    val buildSnapshot: DeviceBuildSnapshot
)

object DeviceRiskInspector {
    private val knownRootPaths = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/app/Superuser.apk",
        "/system/bin/.ext/su",
        "/system/usr/we-need-root/su-backup"
    )

    private val knownMagiskPaths = listOf(
        "/sbin/.magisk",
        "/data/adb/magisk",
        "/data/adb/modules"
    )

    fun inspect(): DeviceRiskSignals {
        val detectedRootPaths = knownRootPaths.filter { File(it).exists() }.toSet()
        val magiskDetected = knownMagiskPaths.any { File(it).exists() }
        val buildSnapshot = DeviceBuildSnapshot(
            fingerprint = Build.FINGERPRINT ?: "",
            model = Build.MODEL ?: "",
            manufacturer = Build.MANUFACTURER ?: "",
            product = Build.PRODUCT ?: "",
            hardware = Build.HARDWARE ?: "",
            brand = Build.BRAND ?: "",
            device = Build.DEVICE ?: ""
        )
        return DeviceRiskSignals(
            rooted = DeviceRiskHeuristics.isProbablyRooted(Build.TAGS, detectedRootPaths, magiskDetected),
            emulator = DeviceRiskHeuristics.isProbablyEmulator(buildSnapshot),
            debuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger(),
            detectedRootPaths = detectedRootPaths,
            magiskDetected = magiskDetected,
            buildSnapshot = buildSnapshot
        )
    }
}
