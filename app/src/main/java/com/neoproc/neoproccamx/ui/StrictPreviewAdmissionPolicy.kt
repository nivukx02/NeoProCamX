package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.rendering.PersistentPreviewOutputRouter
import com.neoproc.neoproccamx.rendering.PreviewRuntimeFailure

/** Typed proof state for the visible >=10-bit preview route used by video surfaces. */
internal enum class StrictPreviewAdmissionState {
    PENDING,
    ACTIVE_PERSISTENT,
    ACTIVE_NATIVE,
    FAILED;

    val active: Boolean
        get() = this == ACTIVE_PERSISTENT || this == ACTIVE_NATIVE
}

/** Fail-closed decision made before a staged render profile may reopen native frame ingress. */
internal enum class StrictPreviewProfileFinalizationGate {
    ALLOW_PROVEN_ROUTE,
    REQUIRE_NATIVE_PRECISION_PROOF,
    RETAIN_FRAME_INGRESS
}

internal object StrictPreviewAdmissionPolicy {
    data class Rejection(
        val result: PersistentPreviewOutputRouter.StartResult,
        val warning: String
    )

    fun required(appMode: AppMode): Boolean =
        VideoRenderProfileQualityHintPolicy.isVideoSurface(appMode)

    fun recordingAllowed(
        appMode: AppMode,
        state: StrictPreviewAdmissionState
    ): Boolean = !required(appMode) || state.active

    fun profileFinalizationGate(
        appMode: AppMode,
        state: StrictPreviewAdmissionState,
        persistentRouteHealthy: Boolean
    ): StrictPreviewProfileFinalizationGate {
        if (!required(appMode)) {
            return StrictPreviewProfileFinalizationGate.ALLOW_PROVEN_ROUTE
        }
        return when (state) {
            StrictPreviewAdmissionState.ACTIVE_PERSISTENT ->
                if (persistentRouteHealthy) {
                    StrictPreviewProfileFinalizationGate.ALLOW_PROVEN_ROUTE
                } else {
                    StrictPreviewProfileFinalizationGate.RETAIN_FRAME_INGRESS
                }
            StrictPreviewAdmissionState.ACTIVE_NATIVE ->
                StrictPreviewProfileFinalizationGate.REQUIRE_NATIVE_PRECISION_PROOF
            StrictPreviewAdmissionState.PENDING,
            StrictPreviewAdmissionState.FAILED ->
                StrictPreviewProfileFinalizationGate.RETAIN_FRAME_INGRESS
        }
    }

    fun rejection(result: PersistentPreviewOutputRouter.StartResult): Rejection {
        val normalized = if (result.started) {
            PersistentPreviewOutputRouter.StartResult.failure(
                PersistentPreviewOutputRouter.StartFailure.UNKNOWN,
                "A strict preview rejection was reported without a failure reason"
            )
        } else {
            result
        }
        val reason = normalized.failure?.name ?: "UNKNOWN"
        return Rejection(
            result = normalized,
            warning = "Strict 10-bit BT.2020-PQ preview is unavailable ($reason). " +
                "The requested mode was retained and the 8-bit fallback was blocked."
        )
    }

    fun runtimeFailure(failure: PreviewRuntimeFailure): PersistentPreviewOutputRouter.StartResult {
        val detail = "${failure.component}/${failure.operation} " +
            "eglError=0x${failure.eglError.toString(16)}; ${failure.detail}"
        return PersistentPreviewOutputRouter.StartResult.failure(
            PersistentPreviewOutputRouter.StartFailure.RUNTIME_EGL_FAILURE,
            detail
        )
    }
}
