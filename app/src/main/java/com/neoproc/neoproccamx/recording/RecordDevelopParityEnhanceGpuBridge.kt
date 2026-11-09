package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopParityEnhanceGpuBridge — GPU (Vulkan compute) port of the LIVE-CINEMA parity luma pass
 * ([Npv10ExportEnhancementPolicy.enhanceLiveCinemaLumaP010]) for the SDR/NDR record-develop converter.
 *
 * It offloads the heavy edge-aware bilateral denoise + halo-guarded unsharp (the converter's CPU
 * bottleneck under the live-parity look) onto the OFFLINE headless Vulkan compute engine shared with
 * [Npv10ExportGpuAccelerator] / [NativeRecordDevelopGpuBridge] (own instance/device/compute-queue,
 * host-visible SSBOs — NO swapchain/AHB, so it is independent of the realtime GPU path).
 *
 * NO-DOWNGRADE: the native pipeline is OPTIONAL (a driver reject leaves the enhance/develop lanes
 * untouched and this reports not-ready), the dispatch uses a BOUNDED fence timeout (a hung queue
 * degrades to CPU instead of blocking), the pass is LUMA-ONLY (chroma byte-identical), and the
 * converter accepts a GPU frame only when it matches the CPU parity reference within a tight code
 * tolerance — otherwise it falls back to the byte-exact CPU pass. Worst case == today's CPU output.
 */
internal object RecordDevelopParityEnhanceGpuBridge {
    private const val NATIVE_LIBRARY = "neoproc_vulkan"

    private val nativeLibraryAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary(NATIVE_LIBRARY)
            true
        }.getOrDefault(false)
    }

    /**
     * True only when the native library loaded AND the optional parity-enhance compute pipeline built
     * (and has not been disabled by a prior dispatch timeout). When false, [enhanceLumaP010] is a
     * guaranteed null-returning no-op, so the converter uses the CPU parity pass.
     */
    fun isAvailable(): Boolean {
        if (!nativeLibraryAvailable) return false
        return runCatching { nativeStatus() == "ready" }.getOrDefault(false)
    }

    fun unavailableReason(): String {
        if (!nativeLibraryAvailable) return "NATIVE_LIBRARY_UNAVAILABLE"
        return runCatching { nativeStatus() }
            .getOrDefault("NATIVE_STATUS_UNAVAILABLE")
            .ifBlank { "NATIVE_STATUS_UNAVAILABLE" }
    }

    /**
     * Runs the live-parity bilateral+unsharp on the P010 luma plane on the GPU. Returns a NEW P010
     * payload (luma enhanced, chroma byte-identical to [payload]) or null on any failure/timeout —
     * the caller then runs the byte-exact CPU pass. [payload] is never mutated (valid temporal prior).
     */
    fun enhanceLumaP010(
        payload: ByteArray,
        width: Int,
        height: Int,
        uSharpness: Double,
        uDenoise: Double
    ): ByteArray? {
        if (!nativeLibraryAvailable || width <= 0 || height <= 0 || payload.isEmpty()) return null
        return runCatching {
            nativeEnhanceLumaP010(payload, width, height, uSharpness.toFloat(), uDenoise.toFloat())
        }.getOrNull()
    }

    @JvmStatic
    private external fun nativeEnhanceLumaP010(
        payload: ByteArray,
        width: Int,
        height: Int,
        uSharpness: Float,
        uDenoise: Float
    ): ByteArray?

    @JvmStatic
    private external fun nativeStatus(): String
}
