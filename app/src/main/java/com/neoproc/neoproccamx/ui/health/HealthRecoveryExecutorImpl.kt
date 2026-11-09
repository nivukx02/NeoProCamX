package com.neoproc.neoproccamx.ui.health

import com.neoproc.neoproccamx.monitor.HealthRecoveryExecutor
import com.neoproc.neoproccamx.monitor.RecoveryAction
import com.neoproc.neoproccamx.ui.CameraViewModel

/**
 * Executes the monitor's non-destructive [RecoveryAction]s against the live pipeline via the
 * ViewModel's safe recovery hooks. Never restarts the session/encoder (see the product decision in
 * [com.neoproc.neoproccamx.monitor.HealthRecoveryPolicy]).
 */
class HealthRecoveryExecutorImpl(private val vm: CameraViewModel) : HealthRecoveryExecutor {
    override fun execute(action: RecoveryAction): Boolean = when (action) {
        RecoveryAction.REFRESH_GPU_CAPABILITIES -> vm.healthRecoveryRefreshGpuCapabilities()
        RecoveryAction.REARM_ISP_READBACK -> vm.healthRecoveryRearmIspReadback()
        RecoveryAction.RESET_IMPORT_HEALTH -> vm.healthRecoveryResetImportHealth()
        RecoveryAction.NONE -> false
    }
}
