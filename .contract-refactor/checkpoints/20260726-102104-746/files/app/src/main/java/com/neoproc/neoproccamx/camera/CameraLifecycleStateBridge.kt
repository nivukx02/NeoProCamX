package com.neoproc.neoproccamx.camera

import kotlinx.coroutines.flow.MutableStateFlow

internal object CameraLifecycleStateBridge {
    fun apply(
        stateMachine: CameraStateMachine,
        publicState: MutableStateFlow<CameraState>,
        target: CameraState,
        reason: String? = null
    ) {
        if (target == CameraState.Closing && stateMachine.current == CameraState.Closed) {
            publicState.value = CameraState.Closed
            return
        }
        if (stateMachine.current == target) {
            publicState.value = target
            return
        }

        val transitioned = when (target) {
            CameraState.Streaming -> transitionToStreaming(stateMachine, reason)
            else -> stateMachine.transition(target, reason)
        }

        if (!transitioned) {
            stateMachine.forceState(target, reason ?: "camera-lifecycle-sync")
        }
        publicState.value = target
    }

    private fun transitionToStreaming(
        stateMachine: CameraStateMachine,
        reason: String?
    ): Boolean {
        when {
            stateMachine.isClosed -> {
                if (!stateMachine.toOpening(reason ?: "prepare-streaming")) return false
                if (!stateMachine.toOpen(reason ?: "prepare-streaming")) return false
            }
            stateMachine.isOpening -> {
                if (!stateMachine.toOpen(reason ?: "prepare-streaming")) return false
            }
            stateMachine.isClosing -> return false
            stateMachine.isStreaming -> return true
        }
        return stateMachine.toStreaming(reason)
    }
}
