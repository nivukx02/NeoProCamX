package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopEnhanceLaneGpuBridge — GPU (Vulkan compute) port of the CPU-only CHROMA enhance lane of
 * [Npv10ExportEnhancementPolicy.enhanceP010] under the live-parity profile: temporal denoise, chroma
 * noise reduction, shadow chroma deadband, chroma-spike suppression, and blue-noise dither. Runs on the
 * same OFFLINE headless Vulkan compute engine as [RecordDevelopParityEnhanceGpuBridge] /
 * [NativeRecordDevelopGpuBridge] (own instance/device/compute-queue, host-visible SSBOs).
 *
 * The native side runs the five passes as bounded, ping-ponged sub-dispatches (record_develop_enhance_
 * lane_p010.comp). Identity colour-lane passes (calibration @ strength 0, SNCE nudge @ sat=1/warmth=0,
 * the gain-zeroed luma-detail/APEX lanes) are omitted because they are no-ops under the live-parity
 * profile — a profile where they are NOT no-ops simply diverges and the first-frame parity gate falls
 * back to the byte-exact CPU pass.
 *
 * NO-DOWNGRADE: the pipeline is OPTIONAL (a driver reject leaves the develop/enhance lanes untouched and
 * this reports not-ready), each pass uses a BOUNDED fence timeout (a hung queue degrades to CPU), and
 * the converter accepts the GPU lane only when it matches the CPU reference within the develop parity
 * tolerance. Worst case == today's byte-exact CPU output.
 */
internal object RecordDevelopEnhanceLaneGpuBridge {
    private const val NATIVE_LIBRARY = "neoproc_vulkan"

    /** Number of scalar uniforms the shader expects (must match kEnhanceLaneUniformFloatCount native). */
    const val UNIFORM_FLOAT_COUNT = 7

    private val nativeLibraryAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary(NATIVE_LIBRARY)
            true
        }.getOrDefault(false)
    }

    /**
     * True only when the native library loaded AND the optional enhance-lane compute pipeline built (and
     * was not disabled by a prior dispatch timeout). When false, [enhanceLaneP010] returns null and the
     * converter uses the CPU chroma enhance lane.
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
     * Runs the chroma enhance lane on the GPU. [uniforms] must be [UNIFORM_FLOAT_COUNT] long (see
     * [Npv10ExportEnhancementPolicy.enhanceLaneGpuUniforms]). Returns a NEW P010 payload or null on any
     * failure/timeout — the caller then runs the byte-exact CPU pass. [payload]/[previousP010Payload]
     * are never mutated.
     */
    fun enhanceLaneP010(
        payload: ByteArray,
        previousP010Payload: ByteArray?,
        width: Int,
        height: Int,
        uniforms: FloatArray
    ): ByteArray? {
        if (!nativeLibraryAvailable || width <= 0 || height <= 0 || payload.isEmpty()) return null
        if (uniforms.size != UNIFORM_FLOAT_COUNT) return null
        return runCatching {
            nativeEnhanceLaneP010(payload, previousP010Payload, width, height, uniforms)
        }.getOrNull()
    }

    @JvmStatic
    private external fun nativeEnhanceLaneP010(
        currentPayload: ByteArray,
        previousPayload: ByteArray?,
        width: Int,
        height: Int,
        uniforms: FloatArray
    ): ByteArray?

    @JvmStatic
    private external fun nativeStatus(): String
}
