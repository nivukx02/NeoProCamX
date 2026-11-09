package com.neoproc.neoproccamx.niv99

import java.nio.ByteBuffer

enum class Niv99PlaneCopyStatus {
    VALID,
    INVALID_DIMENSIONS,
    INVALID_STRIDE,
    INPUT_TOO_SMALL,
    OUTPUT_TOO_SMALL,
    UNSUPPORTED_PIXEL_LAYOUT
}

enum class Niv99PackedEndian {
    LITTLE_ENDIAN,
    BIG_ENDIAN
}

/**
 * Allocation-free RAW16 unpacking into caller-owned float storage. This is the explicit bounded-copy
 * fallback; the production zero-copy route passes the AHardwareBuffer directly to Vulkan.
 */
object Niv99Raw16PlaneAdapter {
    fun copyUnsignedRaw16(
        input: ByteBuffer,
        inputOffsetBytes: Int,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        pixelStrideBytes: Int,
        endian: Niv99PackedEndian,
        output: FloatArray,
        outputOffset: Int = 0,
        outputRowStride: Int = width
    ): Niv99PlaneCopyStatus {
        if (width <= 0 || height <= 0 || inputOffsetBytes < 0 || outputOffset < 0) {
            return Niv99PlaneCopyStatus.INVALID_DIMENSIONS
        }
        if (pixelStrideBytes < 2 || rowStrideBytes < (width - 1) * pixelStrideBytes + 2 ||
            outputRowStride < width
        ) {
            return Niv99PlaneCopyStatus.INVALID_STRIDE
        }
        val lastInputExclusive = inputOffsetBytes.toLong() +
            (height - 1L) * rowStrideBytes + (width - 1L) * pixelStrideBytes + 2L
        if (lastInputExclusive > input.limit().toLong()) return Niv99PlaneCopyStatus.INPUT_TOO_SMALL
        val lastOutputExclusive = outputOffset.toLong() + (height - 1L) * outputRowStride + width
        if (lastOutputExclusive > output.size.toLong()) return Niv99PlaneCopyStatus.OUTPUT_TOO_SMALL

        for (y in 0 until height) {
            val inputRow = inputOffsetBytes + y * rowStrideBytes
            val outputRow = outputOffset + y * outputRowStride
            for (x in 0 until width) {
                val byteIndex = inputRow + x * pixelStrideBytes
                val first = input.get(byteIndex).toInt() and 0xff
                val second = input.get(byteIndex + 1).toInt() and 0xff
                val code = if (endian == Niv99PackedEndian.LITTLE_ENDIAN) {
                    first or (second shl 8)
                } else {
                    (first shl 8) or second
                }
                output[outputRow + x] = code.toFloat()
            }
        }
        return Niv99PlaneCopyStatus.VALID
    }
}

/** Read-only P010 view; it cannot represent an eight-bit intermediate. */
class Niv99P010PlaneDescriptor {
    var luma: ByteBuffer? = null
    var chroma: ByteBuffer? = null
    var width: Int = 0
    var height: Int = 0
    var lumaRowStrideBytes: Int = 0
    var chromaRowStrideBytes: Int = 0
    var lumaPixelStrideBytes: Int = 2
    var chromaPixelStrideBytes: Int = 2
    var bitDepth: Int = 10

    fun set(
        luma: ByteBuffer,
        chroma: ByteBuffer,
        width: Int,
        height: Int,
        lumaRowStrideBytes: Int,
        chromaRowStrideBytes: Int,
        lumaPixelStrideBytes: Int,
        chromaPixelStrideBytes: Int,
        bitDepth: Int = 10
    ): Niv99P010PlaneDescriptor {
        this.luma = luma
        this.chroma = chroma
        this.width = width
        this.height = height
        this.lumaRowStrideBytes = lumaRowStrideBytes
        this.chromaRowStrideBytes = chromaRowStrideBytes
        this.lumaPixelStrideBytes = lumaPixelStrideBytes
        this.chromaPixelStrideBytes = chromaPixelStrideBytes
        this.bitDepth = bitDepth
        return this
    }

    fun validate(): Niv99PlaneCopyStatus {
        if (width <= 0 || height <= 0) return Niv99PlaneCopyStatus.INVALID_DIMENSIONS
        if (bitDepth < 10) return Niv99PlaneCopyStatus.UNSUPPORTED_PIXEL_LAYOUT
        if (lumaPixelStrideBytes < 2 || chromaPixelStrideBytes < 2 ||
            lumaRowStrideBytes < width * 2 || chromaRowStrideBytes < width * 2
        ) {
            return Niv99PlaneCopyStatus.INVALID_STRIDE
        }
        val lumaBuffer = luma ?: return Niv99PlaneCopyStatus.INPUT_TOO_SMALL
        val chromaBuffer = chroma ?: return Niv99PlaneCopyStatus.INPUT_TOO_SMALL
        val requiredLuma = (height - 1L) * lumaRowStrideBytes + width * 2L
        val chromaRows = (height + 1) / 2
        val requiredChroma = (chromaRows - 1L) * chromaRowStrideBytes + width * 2L
        if (requiredLuma > lumaBuffer.remaining().toLong() ||
            requiredChroma > chromaBuffer.remaining().toLong()
        ) {
            return Niv99PlaneCopyStatus.INPUT_TOO_SMALL
        }
        return Niv99PlaneCopyStatus.VALID
    }
}
