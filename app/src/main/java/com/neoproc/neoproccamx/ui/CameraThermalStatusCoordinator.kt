package com.neoproc.neoproccamx.ui

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.neoproc.neoproccamx.pipeline.HighEndRuntimeTier
import com.neoproc.neoproccamx.pipeline.ThermalState

internal data class CameraThermalStatusEvent(
    val platformStatus: Int,
    val tier: HighEndRuntimeTier,
    val throttled: Boolean,
    val thermalState: ThermalState,
    /** Canonical Android severity label consumed by [ThermalRuntimePolicy]. */
    val runtimePolicyLabel: String
)

/** Lifecycle owner for the process thermal callback formerly retained by CameraViewModel. */
internal class CameraThermalStatusCoordinator(
    private val context: Context,
    private val onStatus: (CameraThermalStatusEvent) -> Unit
) {
    private var powerManager: PowerManager? = null
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || listener != null) return
        val manager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val registeredListener = PowerManager.OnThermalStatusChangedListener { status ->
            // A low-frequency dedicated tag survives suppression of large CameraViewModel parity
            // dumps. Record actual Android callbacks, not a cached admission value repeated per frame.
            Log.i(EVIDENCE_TAG, telemetryLine(status, SystemClock.elapsedRealtimeNanos(), "CALLBACK"))
            onStatus(CameraThermalStatusPolicy.resolve(status))
        }
        manager.addThermalStatusListener(registeredListener)
        powerManager = manager
        listener = registeredListener
        Log.i(EVIDENCE_TAG, telemetryLine(
            manager.currentThermalStatus, SystemClock.elapsedRealtimeNanos(), "INITIAL_SNAPSHOT"
        ))
        Log.i(TAG, "Thermal status listener registered")
    }

    fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listener?.let { registeredListener ->
                powerManager?.removeThermalStatusListener(registeredListener)
            }
        }
        listener = null
        powerManager = null
    }

    companion object {
        private const val TAG = "CameraViewModel"
        private const val EVIDENCE_TAG = "CameraThermalStatus"

        internal fun telemetryLine(status: Int, elapsedRealtimeNs: Long, source: String): String =
            "CAMERA_THERMAL_STATUS platformStatus=$status elapsedRealtimeNs=$elapsedRealtimeNs " +
                "source=$source valid=${status in 0..6 && elapsedRealtimeNs >= 0L} " +
                "temperatureC=NOT_EXPOSED_BY_ANDROID_STATUS_API"
    }
}

internal object CameraThermalStatusPolicy {
    fun resolve(status: Int): CameraThermalStatusEvent {
        val tier = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> HighEndRuntimeTier.ULTRA
            PowerManager.THERMAL_STATUS_LIGHT -> HighEndRuntimeTier.CINEMA
            PowerManager.THERMAL_STATUS_MODERATE -> HighEndRuntimeTier.PROFESSIONAL
            PowerManager.THERMAL_STATUS_SEVERE -> HighEndRuntimeTier.SUSTAINED
            PowerManager.THERMAL_STATUS_CRITICAL -> HighEndRuntimeTier.RECOVERY
            else -> HighEndRuntimeTier.RECOVERY
        }
        return CameraThermalStatusEvent(
            platformStatus = status,
            tier = tier,
            throttled = status >= PowerManager.THERMAL_STATUS_SEVERE,
            thermalState = ThermalState.fromAndroidStatus(status),
            runtimePolicyLabel = runtimePolicyLabel(status)
        )
    }

    internal fun runtimePolicyLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN"
    }
}
