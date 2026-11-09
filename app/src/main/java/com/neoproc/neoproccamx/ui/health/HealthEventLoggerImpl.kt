package com.neoproc.neoproccamx.ui.health

import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.monitor.HealthEventLogger
import com.neoproc.neoproccamx.monitor.HealthState
import com.neoproc.neoproccamx.monitor.ModuleHealthStatus
import com.neoproc.neoproccamx.monitor.MonitoredModule
import com.neoproc.neoproccamx.monitor.RecoveryAction

/**
 * Routes health transitions and recovery events into the app's persistent [NeoLogger] (mirrors to
 * logcat + rotating on-device file), at a severity that matches the target state. Every transition,
 * recovery attempt and result is logged for debugging and post-hoc analysis.
 */
class HealthEventLoggerImpl : HealthEventLogger {

    override fun onStateTransition(module: MonitoredModule, previous: HealthState?, next: ModuleHealthStatus) {
        val msg = "${module.displayLabel}: ${previous ?: "—"} -> ${next.state} (${next.reason})"
        val meta: Map<String, Any> = mapOf(
            "module" to module.name,
            "from" to (previous?.name ?: "none"),
            "to" to next.state.name,
            "reason" to next.reason,
            "stage" to next.diagnostics.pipelineStage
        )
        when (next.state) {
            HealthState.ERROR -> NeoLogger.e(TAG, msg, null, meta)
            HealthState.WARNING -> NeoLogger.w(TAG, msg, null, meta)
            else -> NeoLogger.i(TAG, msg, null, meta)
        }
    }

    override fun onRecoveryAttempt(
        module: MonitoredModule,
        action: RecoveryAction,
        attempt: Int,
        success: Boolean,
        rationale: String
    ) {
        val msg = "${module.displayLabel} recovery #$attempt $action success=$success ($rationale)"
        val meta: Map<String, Any> = mapOf(
            "module" to module.name,
            "action" to action.name,
            "attempt" to attempt,
            "success" to success
        )
        if (success) NeoLogger.i(TAG, msg, null, meta) else NeoLogger.w(TAG, msg, null, meta)
    }

    private companion object {
        const val TAG = "HealthMonitor"
    }
}
