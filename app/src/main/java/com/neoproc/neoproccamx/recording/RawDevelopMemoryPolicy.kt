package com.neoproc.neoproccamx.recording

/**
 * Heap estimates for offline RAW->NeoLog conversion.
 *
 * These are scheduling limits only. They never change output pixels; they choose whether the converter
 * may run optional overlap/parity paths or should stay on the lower-peak reference path.
 */
internal object RawDevelopMemoryPolicy {
    private const val SAFETY_BYTES = 48L * 1024L * 1024L

    fun pixels(width: Int, height: Int): Long =
        width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0).toLong()

    fun rawSamplesBytes(width: Int, height: Int): Long =
        pixels(width, height) * Int.SIZE_BYTES

    fun monoBytes(width: Int, height: Int): Long =
        pixels(width, height) * Float.SIZE_BYTES

    fun acescgBytes(width: Int, height: Int): Long =
        pixels(width, height) * 3L * Float.SIZE_BYTES

    /** Persistent JNI direct buffers retained by [RawDevelopGpuWorkspace]. */
    fun gpuWorkspaceDirectBytes(width: Int, height: Int): Long =
        pixels(width, height) * (Short.SIZE_BYTES + 3L * Float.SIZE_BYTES)

    /** Persistent native RGBA16F transfer buffer used to read the Vulkan develop result. */
    fun gpuReadbackBytes(width: Int, height: Int): Long =
        pixels(width, height) * 4L * Short.SIZE_BYTES

    fun developedRgbBytes(width: Int, height: Int): Long =
        pixels(width, height) * 3L * Short.SIZE_BYTES

    fun packedBytes(width: Int, height: Int, format: RawTenBitPacker.Format): Long =
        RawTenBitPacker.payloadSize(format, width, height).toLong()

    fun eliteDetailExtraBytes(width: Int, height: Int): Long =
        // source/blurSmall luma + denoised luma; row scratch is negligible next to full-frame buffers.
        pixels(width, height) * 2L * Float.SIZE_BYTES

    fun cpuDirectPackPeakBytes(width: Int, height: Int, format: RawTenBitPacker.Format): Long =
        rawSamplesBytes(width, height) +
            monoBytes(width, height) +
            acescgBytes(width, height) +
            eliteDetailExtraBytes(width, height) +
            packedBytes(width, height, format) +
            SAFETY_BYTES

    fun gpuDevelopHostPeakBytes(width: Int, height: Int): Long =
        rawSamplesBytes(width, height) +
            acescgBytes(width, height) +
            gpuWorkspaceDirectBytes(width, height) +
            gpuReadbackBytes(width, height) +
            eliteDetailExtraBytes(width, height) +
            SAFETY_BYTES

    fun gpuDirectPackPeakBytes(width: Int, height: Int, format: RawTenBitPacker.Format): Long =
        gpuDevelopHostPeakBytes(width, height) + packedBytes(width, height, format)

    fun gpuFullParityPeakBytes(width: Int, height: Int, format: RawTenBitPacker.Format): Long =
        gpuDirectPackPeakBytes(width, height, format) +
            cpuDirectPackPeakBytes(width, height, format) +
            developedRgbBytes(width, height)

    /**
     * Java-heap reserve for the direct Vulkan callback path. Full-frame mosaic, ACEScg, and P010 storage
     * are direct/native buffers; only bounded row scratch and ordinary app allocations consume this heap.
     */
    fun gpuDirectCallbackJavaHeapBytes(width: Int): Long =
        width.coerceAtLeast(0).toLong() * 24L * java.lang.Float.BYTES + DIRECT_CALLBACK_SAFETY_BYTES

    /**
     * Java/direct-memory admission for the mapped Vulkan conversion route. The full RGB Float32 plane
     * lives in persistent Vulkan host memory and is therefore absent here; Java owns only the uint16 RAW
     * upload, reusable P010 encoder input, bounded row scratch, and a defensive runtime reserve.
     */
    fun gpuMappedCallbackJavaHeapBytes(width: Int, height: Int): Long =
        pixels(width, height) * (Short.SIZE_BYTES + 3L) +
            width.coerceAtLeast(0).toLong() * 24L * java.lang.Float.BYTES +
            DIRECT_CALLBACK_SAFETY_BYTES

    /** Java/direct-memory admission for GPU-native P010: only the reusable uint16 upload is added. */
    fun gpuP010MappedCallbackJavaHeapBytes(width: Int, height: Int): Long =
        pixels(width, height) * Short.SIZE_BYTES + DIRECT_CALLBACK_SAFETY_BYTES

    fun availableJavaHeapBytes(
        runtime: Runtime = Runtime.getRuntime()
    ): Long = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())).coerceAtLeast(0L)

    fun fitsNow(
        peakBytes: Long,
        runtime: Runtime = Runtime.getRuntime()
    ): Boolean = peakBytes <= availableJavaHeapBytes(runtime)

    private const val DIRECT_CALLBACK_SAFETY_BYTES = 16L * 1024L * 1024L
}
