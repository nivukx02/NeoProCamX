package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraStreamSize

/**
 * Keeps the idle P010 mode-switch output tied to the selected recording resolution.
 *
 * The persisted selection is available before Camera2 characteristics are loaded. Once the exact
 * native P010 plan is known, that supported size replaces the seed and remains authoritative until
 * the user selects a different recording resolution. Re-applying the same preference must not
 * demote the exact target back to an early/default analysis size.
 */
internal class ModeSwitchStandbyTargetState(initialRequestedSize: CameraStreamSize) {
    private var requestedSize = initialRequestedSize.normalized()
    private var exactP010Size: CameraStreamSize? = null

    @Synchronized
    fun updateRequestedSize(size: CameraStreamSize): CameraStreamSize {
        val normalized = size.normalized()
        if (normalized != requestedSize) {
            requestedSize = normalized
            exactP010Size = null
        }
        return exactP010Size ?: requestedSize
    }

    @Synchronized
    fun promoteExactP010Size(
        requestedSizeAtResolve: CameraStreamSize,
        exactSize: CameraStreamSize
    ): CameraStreamSize {
        if (requestedSizeAtResolve.normalized() == requestedSize) {
            exactP010Size = exactSize.normalized()
        }
        return exactP010Size ?: requestedSize
    }

    @Synchronized
    fun activeSize(): CameraStreamSize = exactP010Size ?: requestedSize

    private fun CameraStreamSize.normalized(): CameraStreamSize =
        if (width >= height) this else CameraStreamSize(height, width)
}
