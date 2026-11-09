package com.neoproc.neoproccamx.camera

enum class CameraSessionResetStep {
    CLOSE_FAILING_READERS,
    RELEASE_BOUND_SURFACES,
    FLUSH_NATIVE_BUFFERS,
    REQUERY_HAL_CAPABILITIES,
    REINITIALIZE_VULKAN_SWAPCHAIN,
    REBUILD_CAPTURE_SESSION,
    BLOCK_UI_THREAD
}

data class CameraSessionResetContract(
    val reason: String,
    val requestedAtElapsedMs: Long,
    val targetRecoveryWindowMs: Long,
    val nonBlocking: Boolean,
    val steps: List<CameraSessionResetStep>
) {
    val signature: String
        get() = listOf(
            "isolated-session-reset",
            "target=${targetRecoveryWindowMs}ms",
            "nonBlocking=$nonBlocking",
            "steps=${steps.joinToString(">")}",
            "reason=$reason"
        ).joinToString("|")

    companion object {
        fun resolve(reason: String, nowElapsedMs: Long): CameraSessionResetContract =
            CameraSessionResetContract(
                reason = reason,
                requestedAtElapsedMs = nowElapsedMs,
                targetRecoveryWindowMs = 200L,
                nonBlocking = true,
                steps = listOf(
                    CameraSessionResetStep.CLOSE_FAILING_READERS,
                    CameraSessionResetStep.RELEASE_BOUND_SURFACES,
                    CameraSessionResetStep.FLUSH_NATIVE_BUFFERS,
                    CameraSessionResetStep.REQUERY_HAL_CAPABILITIES,
                    CameraSessionResetStep.REINITIALIZE_VULKAN_SWAPCHAIN,
                    CameraSessionResetStep.REBUILD_CAPTURE_SESSION
                )
            )
    }
}
