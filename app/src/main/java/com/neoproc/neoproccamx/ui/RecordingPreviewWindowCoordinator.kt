package com.neoproc.neoproccamx.ui

/**
 * Main-thread owner of display-only recording handoffs. The render thread acknowledges each
 * operation, but only the newest generation may publish a replacement window. Camera inputs,
 * recording profiles, and encoder surfaces are deliberately absent from this owner's interface.
 */
internal class RecordingPreviewWindowCoordinator<S : Any>(
    private val runOnMain: (() -> Unit) -> Unit,
    private val canRetain: () -> Boolean,
    private val recordingActive: () -> Boolean,
    private val detachWindow: ((Boolean, String) -> Unit) -> Boolean,
    private val attachWindow: (S, (Boolean, String) -> Unit) -> Boolean,
    private val onRetained: () -> Unit,
    private val onRestored: (S) -> Unit,
    private val onFailure: (String) -> Unit,
    private val onEvidence: (String) -> Unit
) {
    var retained = false
        private set
    private var generation = 0L
    private var pendingSurface: S? = null

    fun reset() {
        generation += 1L
        retained = false
        pendingSurface = null
    }

    fun retain(): Boolean {
        if (!recordingActive() || !canRetain()) return false
        // Android can report background and surface destruction for the same retired window.
        if (retained && pendingSurface == null) return true
        val ticket = ++generation
        retained = true
        pendingSurface = null
        val admitted = detachWindow { started, detail ->
            runOnMain {
                if (ticket != generation) return@runOnMain
                if (!started) fail(detail) else onEvidence(
                    "RECORDING_PREVIEW_WINDOW_DETACHED cameraInputsRetained=true " +
                        "renderProfileRetained=true sessionRebuild=false"
                )
            }
        }
        if (!admitted) {
            if (ticket == generation) reset()
            return false
        }
        // A synchronous failure callback must not be undone by publishing retained state again.
        if (ticket == generation) onRetained()
        return true
    }

    fun restore(surface: S) {
        if (!retained || !recordingActive() || pendingSurface === surface) return
        val ticket = ++generation
        pendingSurface = surface
        val admitted = attachWindow(surface) { started, detail ->
            runOnMain {
                if (ticket != generation) return@runOnMain
                if (!started) {
                    fail(detail)
                } else {
                    // Publish all display identities in this same main-thread transaction before
                    // ordinary rebind logic can decide whether the retained pipeline is reusable.
                    onRestored(surface)
                    retained = false
                    pendingSurface = null
                    onEvidence(
                        "RECORDING_PREVIEW_WINDOW_REATTACHED cameraInputsRetained=true " +
                            "renderProfileRetained=true sessionRebuild=false"
                    )
                }
            }
        }
        if (!admitted && ticket == generation) {
            fail("Strict same-size window replacement was not admitted")
        }
    }

    private fun fail(detail: String) {
        reset()
        onFailure(detail)
    }
}
