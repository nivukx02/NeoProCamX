package com.neoproc.neoproccamx.recording

import android.graphics.ImageFormat
import android.media.Image
import android.os.Build
import com.neoproc.neoproccamx.pipeline.RawYuvFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder

object Npv10FramePacker {
    private const val NEUTRAL_CHROMA_8_BIT = 128
    private val nativeLibraryAvailable: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        runCatching {
            System.loadLibrary("neoproc_vulkan")
            true
        }.getOrDefault(false)
    }

    enum class P010CopyPath {
        NATIVE_DIRECT,
        DIRECT_BUFFER,
        FAILED
    }

    data class P010CopyResult(
        val bytesCopied: Int,
        val path: P010CopyPath
    )

    fun pack(
        frame: RawYuvFrame,
        payloadFormat: Npv10PayloadFormat = Npv10PayloadFormat.YUV420P10_LE
    ): ByteArray =
        when (payloadFormat) {
            Npv10PayloadFormat.P010_LE -> packP010(frame)
            Npv10PayloadFormat.RAW12_NPV10_PACKED -> packRaw12Frame(frame)
            Npv10PayloadFormat.RAW14_NPV10_PACKED -> packRaw14Frame(frame)
            Npv10PayloadFormat.YUV420P10_LE,
            Npv10PayloadFormat.YUV420_8BIT_EXPANDED -> packPlanar(frame)
        }

    fun packRaw12ToNpv10P010Words(rawSamples: IntArray): ByteArray {
        val output = ByteArray(rawSamples.size * Short.SIZE_BYTES)
        rawSamples.forEachIndexed { index, sample ->
            val raw = sample.coerceIn(0, RAW12_MAX)
            val word = ((raw ushr 2) shl P010_VALID_BITS_SHIFT) or
                ((raw and 0x03) shl 4) or
                crc4Raw12(raw)
            writeLittleEndian16(output, index * Short.SIZE_BYTES, word)
        }
        return output
    }

    fun unpackRaw12FromNpv10P010Words(payload: ByteArray): IntArray {
        require(payload.size % Short.SIZE_BYTES == 0) { "RAW12 NPV10 payload must be 16-bit aligned" }
        return IntArray(payload.size / Short.SIZE_BYTES) { index ->
            val word = readLittleEndian16(payload, index * Short.SIZE_BYTES)
            ((word ushr P010_VALID_BITS_SHIFT) shl 2) or ((word ushr 4) and 0x03)
        }
    }

    fun packRaw14ToNpv10P010Words(rawSamples: IntArray, frameParitySeed: Int = 0): ByteArray {
        val output = ByteArray(rawSamples.size * Short.SIZE_BYTES)
        rawSamples.forEachIndexed { index, sample ->
            val raw = sample.coerceIn(0, RAW14_MAX)
            val parity = (frameParitySeed + index) and 0x03
            val word = ((raw ushr 4) shl P010_VALID_BITS_SHIFT) or
                ((raw and 0x0F) shl 2) or
                parity
            writeLittleEndian16(output, index * Short.SIZE_BYTES, word)
        }
        return output
    }

    fun unpackRaw14FromNpv10P010Words(payload: ByteArray): IntArray {
        require(payload.size % Short.SIZE_BYTES == 0) { "RAW14 NPV10 payload must be 16-bit aligned" }
        return IntArray(payload.size / Short.SIZE_BYTES) { index ->
            val word = readLittleEndian16(payload, index * Short.SIZE_BYTES)
            ((word ushr P010_VALID_BITS_SHIFT) shl 4) or ((word ushr 2) and 0x0F)
        }
    }

    fun crc4Raw12(sample: Int): Int {
        val raw = sample and RAW12_MAX
        return (raw xor (raw ushr 4) xor (raw ushr 8)) and 0x0F
    }

    fun packIntoDirectP010(
        frame: RawYuvFrame,
        target: ByteBuffer = ByteBuffer.allocateDirect(
            Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(frame.width, frame.height).toInt()
        ).order(ByteOrder.nativeOrder())
    ): ByteBuffer {
        val payloadBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(frame.width, frame.height)
            .toInt()
        target.clear()
        val packed =
            nativeLibraryAvailable &&
                runCatching {
                    nativePackP010Neon(
                        frame.yBytes,
                        frame.uBytes,
                        frame.vBytes,
                        frame.width,
                        frame.height,
                        frame.yRowStride,
                        frame.uRowStride,
                        frame.vRowStride,
                        frame.yPixelStride,
                        frame.uPixelStride,
                        frame.vPixelStride,
                        frame.bitDepth,
                        target
                    )
                }.getOrDefault(false)
        if (packed) {
            target.position(payloadBytes)
        } else {
            target.clear()
            target.put(pack(frame, Npv10PayloadFormat.P010_LE))
        }
        target.flip()
        return target
    }

    fun copyP010ImageIntoDirectBuffer(
        image: Image,
        target: ByteBuffer
    ): Int = copyP010ImageIntoDirectBufferWithResult(image, target).bytesCopied

    fun copyP010ImageIntoDirectBufferWithResult(
        image: Image,
        target: ByteBuffer
    ): P010CopyResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            image.format != ImageFormat.YCBCR_P010 ||
            image.planes.size < 2
        ) {
            return P010CopyResult(0, P010CopyPath.FAILED)
        }
        val payloadBytes = Npv10ContainerPolicy
            .estimatedPayloadBytesPerFrame(image.width, image.height)
            .toInt()
        if (target.capacity() < payloadBytes) {
            return P010CopyResult(0, P010CopyPath.FAILED)
        }
        val yPlane = image.planes[0]
        val uvPlane = image.planes[1]
        val vPlane = image.planes.getOrNull(2)
        val yBuffer = yPlane.buffer.duplicate().slice()
        val uvBuffer = uvPlane.buffer.duplicate().slice()
        val vBuffer = vPlane?.buffer?.duplicate()?.slice()
        target.clear()
        val yPixelStride = yPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES)
        val uvPixelStride = uvPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES * 2)
        val nativeCopied =
            nativeLibraryAvailable &&
                runCatching {
                    nativeCopyP010ImagePlanes(
                        yBuffer,
                        uvBuffer,
                        image.width,
                        image.height,
                        yPlane.rowStride,
                        uvPlane.rowStride,
                        yPixelStride,
                        uvPixelStride,
                        target
                    )
                }.getOrDefault(false)
        if (nativeCopied) {
            target.position(payloadBytes)
            target.flip()
            return P010CopyResult(payloadBytes, P010CopyPath.NATIVE_DIRECT)
        }

        if (vPlane != null && vBuffer != null) {
            target.clear()
            val threePlaneCopiedBytes = copyP010ThreePlaneBuffersDirect(
                yBuffer = yBuffer,
                uBuffer = uvBuffer,
                vBuffer = vBuffer,
                width = image.width,
                height = image.height,
                yRowStride = yPlane.rowStride,
                uRowStride = uvPlane.rowStride,
                vRowStride = vPlane.rowStride,
                yPixelStride = yPixelStride,
                uPixelStride = uvPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES),
                vPixelStride = vPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES),
                target = target
            )
            if (threePlaneCopiedBytes > 0) {
                target.flip()
                return P010CopyResult(threePlaneCopiedBytes, P010CopyPath.DIRECT_BUFFER)
            }
        }

        target.clear()
        val directCopiedBytes = copyP010PlaneBuffersDirect(
            yBuffer = yBuffer,
            uvBuffer = uvBuffer,
            width = image.width,
            height = image.height,
            yRowStride = yPlane.rowStride,
            uvRowStride = uvPlane.rowStride,
            yPixelStride = yPixelStride,
            uvPixelStride = uvPixelStride,
            target = target
        )
        if (directCopiedBytes <= 0) {
            target.clear()
            return P010CopyResult(0, P010CopyPath.FAILED)
        }
        target.flip()
        return P010CopyResult(directCopiedBytes, P010CopyPath.DIRECT_BUFFER)
    }

    internal fun copyP010PlaneBuffersDirect(
        yBuffer: ByteBuffer,
        uvBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        yPixelStride: Int,
        uvPixelStride: Int,
        target: ByteBuffer
    ): Int {
        if (width <= 0 || height <= 0 ||
            yRowStride <= 0 || uvRowStride <= 0 ||
            yPixelStride < Short.SIZE_BYTES ||
            uvPixelStride < Short.SIZE_BYTES * 2
        ) {
            return 0
        }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val yPayloadRowBytes = width * Short.SIZE_BYTES
        val uvPayloadRowBytes = chromaWidth * Short.SIZE_BYTES * 2
        val payloadBytes = yPayloadRowBytes * height + uvPayloadRowBytes * chromaHeight
        if (target.capacity() < payloadBytes) return 0

        target.clear()
        val yCopied = copyPlaneGroupsDirect(
            source = yBuffer,
            target = target,
            groupsPerRow = width,
            rows = height,
            sourceRowStride = yRowStride,
            sourceGroupStride = yPixelStride,
            bytesPerGroup = Short.SIZE_BYTES
        )
        val uvCopied = yCopied && copyPlaneGroupsDirect(
            source = uvBuffer,
            target = target,
            groupsPerRow = chromaWidth,
            rows = chromaHeight,
            sourceRowStride = uvRowStride,
            sourceGroupStride = uvPixelStride,
            bytesPerGroup = Short.SIZE_BYTES * 2
        )
        if (!uvCopied || target.position() != payloadBytes) {
            target.clear()
            return 0
        }
        return payloadBytes
    }

    internal fun copyP010ThreePlaneBuffersDirect(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        yPixelStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        target: ByteBuffer
    ): Int {
        if (width <= 0 || height <= 0 ||
            yRowStride <= 0 || uRowStride <= 0 || vRowStride <= 0 ||
            yPixelStride < Short.SIZE_BYTES ||
            uPixelStride < Short.SIZE_BYTES ||
            vPixelStride < Short.SIZE_BYTES
        ) {
            return 0
        }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val payloadBytes = width * height * Short.SIZE_BYTES +
            chromaWidth * chromaHeight * Short.SIZE_BYTES * 2
        if (target.capacity() < payloadBytes) return 0

        target.clear()
        val yCopied = copyPlaneGroupsDirect(
            source = yBuffer,
            target = target,
            groupsPerRow = width,
            rows = height,
            sourceRowStride = yRowStride,
            sourceGroupStride = yPixelStride,
            bytesPerGroup = Short.SIZE_BYTES
        )
        val chromaCopied = yCopied && copyP010ChromaViewsDirect(
            uSource = uBuffer,
            vSource = vBuffer,
            target = target,
            groupsPerRow = chromaWidth,
            rows = chromaHeight,
            uRowStride = uRowStride,
            vRowStride = vRowStride,
            uGroupStride = uPixelStride,
            vGroupStride = vPixelStride
        )
        if (!chromaCopied || target.position() != payloadBytes) {
            target.clear()
            return 0
        }
        return payloadBytes
    }

    private fun copyPlaneGroupsDirect(
        source: ByteBuffer,
        target: ByteBuffer,
        groupsPerRow: Int,
        rows: Int,
        sourceRowStride: Int,
        sourceGroupStride: Int,
        bytesPerGroup: Int
    ): Boolean {
        if (groupsPerRow <= 0 || rows <= 0) return false
        val requiredSourceBytes =
            (rows - 1L) * sourceRowStride +
                (groupsPerRow - 1L) * sourceGroupStride +
                bytesPerGroup
        if (requiredSourceBytes > source.limit().toLong()) return false

        val payloadRowBytes = groupsPerRow * bytesPerGroup
        if (sourceGroupStride == bytesPerGroup) {
            if (sourceRowStride == payloadRowBytes) {
                val contiguous = source.duplicate()
                contiguous.position(0)
                contiguous.limit(payloadRowBytes * rows)
                target.put(contiguous)
                return true
            }
            repeat(rows) { row ->
                val rowStart = row * sourceRowStride
                val rowSlice = source.duplicate()
                rowSlice.position(rowStart)
                rowSlice.limit(rowStart + payloadRowBytes)
                target.put(rowSlice)
            }
            return true
        }

        repeat(rows) { row ->
            repeat(groupsPerRow) { group ->
                val sourceOffset = row * sourceRowStride + group * sourceGroupStride
                repeat(bytesPerGroup) { byteOffset ->
                    target.put(source.get(sourceOffset + byteOffset))
                }
            }
        }
        return true
    }

    private fun copyP010ChromaViewsDirect(
        uSource: ByteBuffer,
        vSource: ByteBuffer,
        target: ByteBuffer,
        groupsPerRow: Int,
        rows: Int,
        uRowStride: Int,
        vRowStride: Int,
        uGroupStride: Int,
        vGroupStride: Int
    ): Boolean {
        if (groupsPerRow <= 0 || rows <= 0) return false
        val requiredUBytes =
            (rows - 1L) * uRowStride +
                (groupsPerRow - 1L) * uGroupStride +
                Short.SIZE_BYTES
        val requiredVBytes =
            (rows - 1L) * vRowStride +
                (groupsPerRow - 1L) * vGroupStride +
                Short.SIZE_BYTES
        if (requiredUBytes > uSource.limit().toLong() || requiredVBytes > vSource.limit().toLong()) {
            return false
        }

        repeat(rows) { row ->
            repeat(groupsPerRow) { group ->
                val uOffset = row * uRowStride + group * uGroupStride
                val vOffset = row * vRowStride + group * vGroupStride
                target.put(uSource.get(uOffset))
                target.put(uSource.get(uOffset + 1))
                target.put(vSource.get(vOffset))
                target.put(vSource.get(vOffset + 1))
            }
        }
        return true
    }

    private fun packPlanar(frame: RawYuvFrame): ByteArray {
        val width = frame.width.coerceAtLeast(1)
        val height = frame.height.coerceAtLeast(1)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val output = ByteArray(Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt())
        var offset = 0

        for (row in 0 until height) {
            for (column in 0 until width) {
                val sample = readPlaneSample(
                    bytes = frame.yBytes,
                    row = row,
                    column = column,
                    rowStride = frame.yRowStride,
                    pixelStride = frame.yPixelStride,
                    fallback = 0,
                    bitDepth = frame.bitDepth
                )
                writeTenBitSample(output, offset, sample, frame.bitDepth)
                offset += Short.SIZE_BYTES
            }
        }

        offset = packChromaPlane(
            output = output,
            offset = offset,
            bytes = frame.uBytes,
            width = chromaWidth,
            height = chromaHeight,
            rowStride = frame.uRowStride,
            pixelStride = frame.uPixelStride,
            bitDepth = frame.bitDepth
        )
        packChromaPlane(
            output = output,
            offset = offset,
            bytes = frame.vBytes,
            width = chromaWidth,
            height = chromaHeight,
            rowStride = frame.vRowStride,
            pixelStride = frame.vPixelStride,
            bitDepth = frame.bitDepth
        )
        return output
    }

    private fun packP010(frame: RawYuvFrame): ByteArray {
        val width = frame.width.coerceAtLeast(1)
        val height = frame.height.coerceAtLeast(1)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val output = ByteArray(Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt())
        var offset = 0

        for (row in 0 until height) {
            for (column in 0 until width) {
                val sample = readPlaneSample(
                    bytes = frame.yBytes,
                    row = row,
                    column = column,
                    rowStride = frame.yRowStride,
                    pixelStride = frame.yPixelStride,
                    fallback = 0,
                    bitDepth = frame.bitDepth
                )
                writeP010Sample(output, offset, sample, frame.bitDepth)
                offset += Short.SIZE_BYTES
            }
        }

        for (row in 0 until chromaHeight) {
            for (column in 0 until chromaWidth) {
                val u = readPlaneSample(
                    bytes = frame.uBytes,
                    row = row,
                    column = column,
                    rowStride = frame.uRowStride,
                    pixelStride = frame.uPixelStride,
                    fallback = NEUTRAL_CHROMA_8_BIT,
                    bitDepth = frame.bitDepth
                )
                val v = readPlaneSample(
                    bytes = frame.vBytes,
                    row = row,
                    column = column,
                    rowStride = frame.vRowStride,
                    pixelStride = frame.vPixelStride,
                    fallback = NEUTRAL_CHROMA_8_BIT,
                    bitDepth = frame.bitDepth
                )
                writeP010Sample(output, offset, u, frame.bitDepth)
                writeP010Sample(output, offset + Short.SIZE_BYTES, v, frame.bitDepth)
                offset += Short.SIZE_BYTES * 2
            }
        }
        return output
    }

    private fun packRaw12Frame(frame: RawYuvFrame): ByteArray =
        packRaw12ToNpv10P010Words(readRawPlane(frame, RAW12_MAX))

    private fun packRaw14Frame(frame: RawYuvFrame): ByteArray =
        packRaw14ToNpv10P010Words(
            rawSamples = readRawPlane(frame, RAW14_MAX),
            frameParitySeed = ((frame.timestamp ushr 1) and 0x03).toInt()
        )

    private fun readRawPlane(frame: RawYuvFrame, maxValue: Int): IntArray {
        val width = frame.width.coerceAtLeast(1)
        val height = frame.height.coerceAtLeast(1)
        val samples = IntArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                samples[row * width + column] = readPlaneSample(
                    bytes = frame.yBytes,
                    row = row,
                    column = column,
                    rowStride = frame.yRowStride,
                    pixelStride = frame.yPixelStride,
                    fallback = 0,
                    bitDepth = frame.bitDepth,
                    maxValueOverride = maxValue
                ).coerceIn(0, maxValue)
            }
        }
        return samples
    }

    private fun packChromaPlane(
        output: ByteArray,
        offset: Int,
        bytes: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        bitDepth: Int
    ): Int {
        var cursor = offset
        for (row in 0 until height) {
            for (column in 0 until width) {
                val sample = readPlaneSample(
                    bytes = bytes,
                    row = row,
                    column = column,
                    rowStride = rowStride,
                    pixelStride = pixelStride,
                    fallback = NEUTRAL_CHROMA_8_BIT,
                    bitDepth = bitDepth
                )
                writeTenBitSample(output, cursor, sample, bitDepth)
                cursor += Short.SIZE_BYTES
            }
        }
        return cursor
    }

    private fun readPlaneSample(
        bytes: ByteArray,
        row: Int,
        column: Int,
        rowStride: Int,
        pixelStride: Int,
        fallback: Int,
        bitDepth: Int,
        maxValueOverride: Int = 1023
    ): Int {
        if (bytes.isEmpty() || rowStride <= 0 || pixelStride <= 0) return fallback
        val index = row * rowStride + column * pixelStride
        if (bitDepth >= 10) {
            val lo = bytes.getOrNull(index)?.toInt()?.and(0xFF) ?: return fallback
            val hi = bytes.getOrNull(index + 1)?.toInt()?.and(0xFF) ?: 0
            val packed = (hi shl 8) or lo
            return if (packed > 1023) {
                if (maxValueOverride > 1023) packed else packed ushr 6
            } else {
                packed
            }.coerceIn(0, maxValueOverride)
        }
        return bytes.getOrNull(index)?.toInt()?.and(0xFF) ?: fallback
    }

    private fun writeTenBitSample(output: ByteArray, offset: Int, sample: Int, bitDepth: Int) {
        writeLittleEndian16(output, offset, normalizeTenBitSample(sample, bitDepth))
    }

    private fun writeP010Sample(output: ByteArray, offset: Int, sample: Int, bitDepth: Int) {
        writeLittleEndian16(output, offset, normalizeTenBitSample(sample, bitDepth) shl P010_VALID_BITS_SHIFT)
    }

    private fun normalizeTenBitSample(sample: Int, bitDepth: Int): Int {
        val tenBit = if (bitDepth >= 10) {
            sample.coerceIn(0, 1023)
        } else {
            (sample.coerceIn(0, 255) shl 2).coerceIn(0, 1023)
        }
        return tenBit
    }

    private fun writeLittleEndian16(output: ByteArray, offset: Int, value: Int) {
        output[offset] = (value and 0xFF).toByte()
        output[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun readLittleEndian16(input: ByteArray, offset: Int): Int =
        (input[offset].toInt() and 0xFF) or
            ((input[offset + 1].toInt() and 0xFF) shl 8)

    @JvmStatic
    private external fun nativePackP010Neon(
        yBytes: ByteArray,
        uBytes: ByteArray,
        vBytes: ByteArray,
        width: Int,
        height: Int,
        yRowStride: Int,
        uRowStride: Int,
        vRowStride: Int,
        yPixelStride: Int,
        uPixelStride: Int,
        vPixelStride: Int,
        bitDepth: Int,
        target: ByteBuffer
    ): Boolean

    @JvmStatic
    private external fun nativeCopyP010ImagePlanes(
        yBuffer: ByteBuffer,
        uvBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yRowStride: Int,
        uvRowStride: Int,
        yPixelStride: Int,
        uvPixelStride: Int,
        target: ByteBuffer
    ): Boolean

    private const val P010_VALID_BITS_SHIFT = 6
    private const val RAW12_MAX = 0x0FFF
    private const val RAW14_MAX = 0x3FFF
}
