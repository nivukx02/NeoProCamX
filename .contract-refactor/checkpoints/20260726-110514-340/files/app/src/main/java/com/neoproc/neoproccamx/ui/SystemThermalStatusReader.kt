package com.neoproc.neoproccamx.ui

import android.content.Context
import android.os.Build
import android.os.PowerManager

internal object SystemThermalStatusReader {
    fun status(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return context.getSystemService(PowerManager::class.java)?.currentThermalStatus
    }

    fun label(context: Context): String = when (status(context)) {
        PowerManager.THERMAL_STATUS_NONE -> "Nominal"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
        else -> "Unknown"
    }
}

