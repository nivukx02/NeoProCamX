package com.neoproc.neoproccamx.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * #15 fix: Play-COMPLIANT battery-optimization exemption entry point.
 *
 * This now opens [Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS] — the system LIST screen — which
 * needs NO restricted permission and is always allowed to be launched. The previous direct opt-out dialog
 * (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) required the Play-restricted
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission (removed from the manifest) and risked policy rejection.
 *
 * It is intentionally NOT auto-fired at launch anymore. Invoke it only from an explicit user action.
 */
internal object BatteryOptimizationExemptionRequester {
    private const val TAG = "BatteryOptimizationExemption"

    fun requestIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = activity.getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(activity.packageName)) return
        runCatching {
            // Compliant list screen (no restricted permission). Users toggle the app from the system list.
            activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to open battery optimization settings", throwable)
        }
    }
}
