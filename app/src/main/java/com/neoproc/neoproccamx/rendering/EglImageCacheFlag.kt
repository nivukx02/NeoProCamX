package com.neoproc.neoproccamx.rendering

/**
 * EglImageCacheFlag — strict-record default reuse of EGLImageKHR per recycled AHardwareBuffer
 * slot on the live offscreen-EGL record submit path.
 *
 * Today every recorded frame does eglCreateImageKHR + eglDestroyImageKHR from the imported
 * AHardwareBuffer; on Adreno these driver imports are not free and add per-frame submit-latency
 * variance. The hardware-import reader is a small fixed pool (6-8), so the AHardwareBuffer* slots
 * recycle with stable identity — caching the EGLImage per slot removes the per-frame create/destroy
 * (the same technique GLConsumer/SurfaceTexture use internally). Sampling is bit-identical (same AHB,
 * same samplerExternalOES, same RGB10_A2 output).
 *
 * DEFAULT ON for strict high-end recording because the native bridge keys the cache by
 * AHardwareBuffer_getId when available. If a device cannot provide stable ids, native refuses the cache
 * and keeps the safe per-frame import path rather than risking stale-buffer reuse. The native side
 * caches only the EGLImageKHR and destroys it on eviction/surface teardown; it does not hold an extra
 * AHardwareBuffer ref because that pins the camera/reader pool and destabilizes 4K25 slot reuse.
 *
 * NOT a battery optimisation (removes work to lower latency/jitter, not to save power). Pushed to the
 * native bridge at setEncoderSurface, so a flip takes effect on the next recording. On-device A/B:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.egl_image_cache true
 */
object EglImageCacheFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
