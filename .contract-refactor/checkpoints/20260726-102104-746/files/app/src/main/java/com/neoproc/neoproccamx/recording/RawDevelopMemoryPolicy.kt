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

    fun gpuDirectPackPeakBytes(width: Int, height: Int, format: RawTenBitPacker.Format): Long =
        rawSamplesBytes(width, height) +
            acescgBytes(width, height) +
            eliteDetailExtraBytes(width, height) +
            packedBytes(width, height, format) +
            SAFETY_BYTES

    fun gpuFullParityPeakBytes(width: Int, height: Int, format: RawTenBitPacker.Format): Long =
        gpuDirectPackPeakBytes(width, height, format) +
            cpuDirectPackPeakBytes(width, height, format) +
            developedRgbBytes(width, height)

    fun availableJavaHeapBytes(
        runtime: Runtime = Runtime.getRuntime()
    ): Long = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())).coerceAtLeast(0L)

    fun fitsNow(
        peakBytes: Long,
        runtime: Runtime = Runtime.getRuntime()
    ): Boolean = peakBytes <= availableJavaHeapBytes(runtime)
}
