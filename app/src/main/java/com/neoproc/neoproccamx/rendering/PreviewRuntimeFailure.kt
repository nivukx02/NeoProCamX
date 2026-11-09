package com.neoproc.neoproccamx.rendering

import java.util.concurrent.atomic.AtomicBoolean

/** The strict-preview stage that lost its proven EGL route. */
internal enum class PreviewRuntimeComponent {
    OUTPUT_ROUTER,
    SDR_CHILD,
    HDR_CHILD
}

/** The runtime EGL operation whose failure invalidated the strict-preview route. */
internal enum class PreviewRuntimeOperation {
    EGL_MAKE_CURRENT,
    EGL_SWAP_BUFFERS
}

/** Immutable failure evidence shared by every stage in the strict-preview presentation chain. */
internal data class PreviewRuntimeFailure(
    val component: PreviewRuntimeComponent,
    val operation: PreviewRuntimeOperation,
    val eglError: Int,
    val detail: String
)

/**
 * A lifecycle-scoped one-shot latch. It is inert before a strict route has successfully started and
 * admits exactly one failure until the owner proves and arms a new route.
 */
internal class PreviewRuntimeFailureGate {
    private val armed = AtomicBoolean(false)

    fun armAfterSuccessfulStart() {
        armed.set(true)
    }

    fun disarm() {
        armed.set(false)
    }

    fun tryClaimActiveFailure(): Boolean = armed.compareAndSet(true, false)
}
