package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.VulkanBridge
import com.neoproc.neoproccamx.rendering.XYZ_D50_TO_ACESCG
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RawDevelopGpuAccelerator — GPU (Vulkan compute) demosaic → sensor→ACEScg for the OFFLINE RAW→NeoLog
 * converter. It uploads a CPU sensor mosaic, runs the same `demosaic_vulkan.comp` used by the live
 * cinema-debayer (WB-before-demosaic + edge-directed green + CP11 sensor→XYZ→ACEScg), and reads the
 * FULL-RES ACEScg plane back to the CPU; the NeoLog encode + 10-bit pack finish on the CPU via
 * [com.neoproc.neoproccamx.pipeline.RawNeoLogDevelop.finishFromAcescg]. So the GPU path differs from the
 * full CPU develop ONLY in the demosaic — exactly what the converter's first-frame parity gate checks.
 *
 * GATING (no-downgrade): [developToAcescg] returns null — and the caller uses the full CPU develop —
 * unless ALL of these hold:
 *   1. [developGpuEnabled] is true (DEFAULT FALSE — device-gated until on-device parity-verified), and
 *   2. the `neoproc_vulkan` native library loaded, and
 *   3. a live [VulkanBridge] with the demosaic pipeline is [attach]ed, and
 *   4. the native dispatch + readback succeeds for this frame.
 * Any failure path returns null, so a device that cannot run it falls back to the byte-exact CPU develop.
 */
internal object RawDevelopGpuAccelerator {

    /**
     * Master gate, DEFAULT ON (opt-in hybrid). The GPU lane only ever REPLACES the demosaic; it is made
     * safe without prior device certification by two independent guards that keep it no-downgrade:
     *   • the converter's first-frame parity gate rejects the GPU develop for the whole clip unless it
     *     matches the CPU reference within tolerance (a broken transform / wrong colour space → CPU), and
     *   • [developToAcescg] returns null on any per-frame dispatch/readback failure → that frame develops
     *     on the byte-exact CPU path.
     * So on a device where the Vulkan demosaic is unavailable or wrong, RAW conversion transparently runs
     * the proven multi-core CPU develop. Flip OFF via [setDevelopGpuEnabled] to force pure-CPU develop.
     */
    @Volatile
    private var developGpuEnabled: Boolean = true

    @Volatile
    private var bridge: VulkanBridge? = null

    fun setDevelopGpuEnabled(enabled: Boolean) {
        developGpuEnabled = enabled
    }

    /** Attach the process-wide Vulkan bridge (whose demosaic pipeline is already built) for offline use. */
    fun attach(bridge: VulkanBridge?) {
        this.bridge = bridge
    }

    /** True only when the GPU develop path is enabled AND a supported bridge is attached. */
    fun isAvailable(): Boolean {
        if (!developGpuEnabled) return false
        val b = bridge ?: return false
        return runCatching { b.isSupported() }.getOrDefault(false)
    }

    /**
     * Demosaic [rawSamples] (row-major sensor mosaic, 0..whiteLevel) to a LINEAR ACEScg interleaved RGB
     * FloatArray of size width*height*3 (pre-exposure). Returns null when the GPU path is unavailable or
     * the native dispatch/readback fails — the caller then develops the frame on the CPU (no-downgrade).
     */
    fun developToAcescg(
        rawSamples: IntArray,
        meta: RawDevelopMetadata,
        width: Int,
        height: Int
    ): FloatArray? {
        if (!isAvailable() || width <= 0 || height <= 0) return null
        if (rawSamples.size < width * height) return null
        val activeBridge = bridge ?: return null
        return try {
            // Sensor mosaic → tightly-packed little-endian uint16 direct buffer (R16 upload source).
            val mosaic = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.nativeOrder())
            val mosaicShorts = mosaic.asShortBuffer()
            for (i in 0 until width * height) {
                mosaicShorts.put(i, rawSamples[i].coerceIn(0, 0xFFFF).toShort())
            }
            // Native writes width*height*3 float32 ACEScg components (RGB interleaved) into [out].
            val out = ByteBuffer
                .allocateDirect(width * height * 3 * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            val ok = activeBridge.developRawToAcescg(
                mosaic = mosaic,
                rowStrideBytes = width * 2,
                width = width,
                height = height,
                bayerPattern = meta.bayerPatternValue,
                whiteLevel = meta.whiteLevel,
                blackLevel = meta.blackLevel,
                whiteBalanceR = meta.whiteBalanceR,
                whiteBalanceGr = meta.whiteBalanceGr,
                whiteBalanceGb = meta.whiteBalanceGb,
                whiteBalanceB = meta.whiteBalanceB,
                sensorToXyz = meta.sensorToXyz,
                // Same XYZ(D50)→ACEScg matrix the CPU develop uses, so GPU vs CPU differ ONLY in demosaic.
                xyzToAcescg = XYZ_D50_TO_ACESCG,
                out = out
            )
            if (!ok) return null
            val floats = FloatArray(width * height * 3)
            out.order(ByteOrder.nativeOrder()).asFloatBuffer().get(floats)
            floats
        } catch (_: Exception) {
            null
        }
    }
}
