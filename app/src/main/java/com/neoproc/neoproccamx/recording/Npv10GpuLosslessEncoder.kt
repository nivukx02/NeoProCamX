package com.neoproc.neoproccamx.recording

import java.nio.ByteBuffer

/**
 * Bit-exact GPU range encoder qualification boundary. The live recorder remains on its measured
 * CPU path until physical parity and total-time measurements qualify GPU upload/compute/readback.
 * This is not a display shader, a quality filter, a frame queue, or an implicit CPU retry.
 */
internal object Npv10GpuLosslessEncoder {
    private val available by lazy {
        runCatching { System.loadLibrary("neoproc_vulkan"); true }.getOrDefault(false)
    }

    fun prepare(width: Int, height: Int): Boolean = available && nativePrepare(width, height)

    fun encode(
        y: ByteBuffer, uv: ByteBuffer, width: Int, height: Int,
        yRowStride: Int, uvRowStride: Int, output: ByteBuffer, useGpu: Boolean
    ): LongArray? {
        require(y.isDirect && uv.isDirect && output.isDirect && !output.isReadOnly)
        require(y.position() == 0 && uv.position() == 0 && output.position() == 0)
        require(y.limit() == y.capacity() && uv.limit() == uv.capacity() && output.limit() == output.capacity())
        return if (available) nativeEncode(y, uv, width, height, yRowStride, uvRowStride, output, useGpu) else null
    }

    fun close() { if (available) nativeClose() }

    /** Same real samples/format oracle, using the existing capture-safe CPU helper pool. */
    fun encodeParallel(
        y: ByteBuffer, uv: ByteBuffer, width: Int, height: Int,
        yRowStride: Int, uvRowStride: Int, output: ByteBuffer
    ): LongArray? {
        require(y.isDirect && uv.isDirect && output.isDirect && !output.isReadOnly)
        require(y.position() == 0 && uv.position() == 0 && output.position() == 0)
        require(y.limit() == y.capacity() && uv.limit() == uv.capacity() && output.limit() == output.capacity())
        return if (available) nativeEncodeParallel(y, uv, width, height, yRowStride, uvRowStride, output) else null
    }

    @JvmStatic private external fun nativePrepare(width: Int, height: Int): Boolean
    @JvmStatic private external fun nativeEncode(
        y: ByteBuffer, uv: ByteBuffer, width: Int, height: Int,
        yRowStride: Int, uvRowStride: Int, output: ByteBuffer, useGpu: Boolean
    ): LongArray?
    @JvmStatic private external fun nativeClose()
    @JvmStatic private external fun nativeEncodeParallel(
        y: ByteBuffer, uv: ByteBuffer, width: Int, height: Int,
        yRowStride: Int, uvRowStride: Int, output: ByteBuffer
    ): LongArray?
}
