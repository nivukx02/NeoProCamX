package com.neoproc.neoproccamx.camera

internal object CameraSessionRecoveryPolicy {

    fun shouldResetConservativeFallback(
        conservativeSessionFallback: Boolean,
        previewSurfaceValid: Boolean,
        hardwareImportSurfaceAvailable: Boolean,
        yuvSurfaceAvailable: Boolean
    ): Boolean {
        if (!conservativeSessionFallback) return false
        if (previewSurfaceValid) return false
        return hardwareImportSurfaceAvailable || yuvSurfaceAvailable
    }
}
