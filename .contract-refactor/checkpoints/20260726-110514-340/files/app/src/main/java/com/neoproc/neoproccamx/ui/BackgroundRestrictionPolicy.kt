package com.neoproc.neoproccamx.ui

import android.content.Context
import android.os.Build
import android.os.PowerManager

object BackgroundRestrictionPolicy {
    private const val MSG_BG_AND_BATTERY =
        "Background restricted • Battery optimization may interrupt long recording"
    private const val MSG_BG_ONLY =
        "Background restricted • Long recording reliability reduced"
    private const val MSG_BATTERY_ONLY =
        "Battery optimization active • Long recording may pause in background"

    fun resolveWarning(
        recordingActive: Boolean,
        backgroundRestricted: Boolean,
        batteryOptimizationActive: Boolean
    ): String? {
        if (!recordingActive) return null
        return when {
            backgroundRestricted && batteryOptimizationActive -> MSG_BG_AND_BATTERY
            backgroundRestricted -> MSG_BG_ONLY
            batteryOptimizationActive -> MSG_BATTERY_ONLY
            else -> null
        }
    }

    fun resolveRuntimeWarning(context: Context, recordingActive: Boolean): String? {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return null
        val backgroundRestricted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                powerManager.javaClass
                    .getMethod("isBackgroundRestricted")
                    .invoke(powerManager) as Boolean
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
        val batteryOptimizationActive =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !powerManager.isIgnoringBatteryOptimizations(context.packageName)
        return resolveWarning(
            recordingActive = recordingActive,
            backgroundRestricted = backgroundRestricted,
            batteryOptimizationActive = batteryOptimizationActive
        )
    }

    fun isBackgroundRestrictionWarning(message: String?): Boolean =
        message != null &&
            (message.startsWith("Background restricted") ||
                message.startsWith("Battery optimization active"))
}
