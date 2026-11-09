package com.neoproc.neoproccamx.ui

import android.util.Log
import com.neoproc.neoproccamx.rendering.NdrRenderProfileIdentity
import com.neoproc.neoproccamx.rendering.NdrRenderProfileCommitResult
import com.neoproc.neoproccamx.rendering.NdrRenderProfilePacket
import com.neoproc.neoproccamx.rendering.VulkanRuntimeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Lifecycle-owned wiring between coherent NDR profile commits and the visible RAW route.
 *
 * Observation is a child of the supplied scope, so ViewModel cancellation retires it automatically.
 * StateFlow's sticky value preserves a generation committed before this observer is installed. The
 * owner deliberately installs it only after both preview compositors have been constructed.
 */
internal object NdrRenderProfileLifecycleCoordinator {
    private const val TAG = "CameraViewModel"
    private const val OWNER_PUBLICATION_MAX_ATTEMPTS = 3

    fun observeReadyGenerations(
        scope: CoroutineScope,
        readyGenerations: StateFlow<Long>,
        isGenerationCurrent: (Long) -> Boolean,
        isModeActive: () -> Boolean,
        onReady: (Long) -> Unit
    ): Job = scope.launch(Dispatchers.Default) {
        readyGenerations.collect { readyGeneration ->
            try {
                if (shouldDispatch(readyGeneration, isGenerationCurrent, isModeActive)) {
                    onReady(readyGeneration)
                }
            } catch (failure: Throwable) {
                rethrowCancellationOrFatal(failure)
                Log.e(TAG, "NDR ready-generation observer survived callback failure", failure)
            }
        }
    }

    fun observeStagedGenerations(
        scope: CoroutineScope,
        stagedGenerations: StateFlow<Long>,
        isGenerationCurrent: (Long) -> Boolean,
        onStaged: (Long) -> Unit
    ): Job = scope.launch(Dispatchers.Default) {
        stagedGenerations.collect { stagedGeneration ->
            try {
                if (stagedGeneration > 0L && isGenerationCurrent(stagedGeneration)) {
                    onStaged(stagedGeneration)
                }
            } catch (failure: Throwable) {
                rethrowCancellationOrFatal(failure)
                Log.e(TAG, "NDR staged-generation observer survived callback failure", failure)
            }
        }
    }

    fun observeMutationReadyIdentities(
        scope: CoroutineScope,
        readyIdentities: StateFlow<NdrRenderProfileIdentity?>,
        isGenerationCurrent: (Long) -> Boolean,
        onReady: (NdrRenderProfileIdentity) -> Unit
    ): Job = scope.launch(Dispatchers.Default) {
        readyIdentities.collect { identity ->
            try {
                if (identity != null && isGenerationCurrent(identity.profileGeneration)) {
                    onReady(identity)
                }
            } catch (failure: Throwable) {
                rethrowCancellationOrFatal(failure)
                Log.e(TAG, "NDR RAW-drain observer survived callback failure", failure)
            }
        }
    }

    private fun rethrowCancellationOrFatal(failure: Throwable) {
        if (
            failure is CancellationException ||
            failure is VirtualMachineError ||
            failure is ThreadDeath
        ) {
            throw failure
        }
    }

    internal fun shouldDispatch(
        readyGeneration: Long,
        isGenerationCurrent: (Long) -> Boolean,
        isModeActive: () -> Boolean
    ): Boolean = readyGeneration > 0L &&
        isGenerationCurrent(readyGeneration) &&
        isModeActive()

    /**
     * Commits one coherent packet and converts only temporary cold-start unavailability into a quiet
     * defer. Every other non-committed result remains a visible fail-closed rejection.
     */
    fun commitForFrameIngress(
        runtime: VulkanRuntimeManager,
        packet: NdrRenderProfilePacket,
        activeGeneration: () -> Long
    ): Boolean {
        val result = runtime.commitNdrRenderProfilePacket(packet)
        if (permitsOwnerPublication(result)) return true
        return when (result) {
            NdrRenderProfileCommitResult.DEFERRED_UNREADY,
            NdrRenderProfileCommitResult.DEFERRED_IN_FLIGHT -> {
                Log.i(
                    TAG,
                    "NDR_RENDER_CONFIG_DEFERRED result=$result identity=${packet.identity} " +
                        "active=${activeGeneration()}; frame ingress retained"
                )
                false
            }
            else -> {
                Log.w(
                    TAG,
                    "NDR_RENDER_CONFIG_REJECTED result=$result requested=${packet.profileGeneration} " +
                        "active=${activeGeneration()}; frame ingress retained"
                )
                false
            }
        }
    }

    internal fun permitsOwnerPublication(result: NdrRenderProfileCommitResult): Boolean =
        result == NdrRenderProfileCommitResult.COMMITTED

    fun finalizeFrameIngress(
        runtime: VulkanRuntimeManager,
        packet: NdrRenderProfilePacket,
        activeGeneration: () -> Long,
        beforeIngress: () -> Boolean
    ): Boolean {
        repeat(OWNER_PUBLICATION_MAX_ATTEMPTS) { attempt ->
            if (activeGeneration() != packet.profileGeneration) return false
            if (runtime.finalizeNdrRenderProfilePacket(packet, beforeIngress)) return true
            Log.w(
                TAG,
                "NDR_RENDER_CONFIG_FINALIZE_RETRY requested=${packet.profileGeneration} " +
                    "active=${activeGeneration()} attempt=${attempt + 1}/$OWNER_PUBLICATION_MAX_ATTEMPTS"
            )
        }
        Log.e(
            TAG,
            "NDR_RENDER_CONFIG_FINALIZE_REJECTED requested=${packet.profileGeneration} " +
                "active=${activeGeneration()}; frame ingress retained"
        )
        return false
    }
}
