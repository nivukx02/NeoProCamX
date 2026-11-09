package com.neoproc.neoproccamx.ai

import android.graphics.ImageFormat
import android.media.Image
import android.os.Build
import android.util.Log
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.NativeAnalysisIngress
import org.tensorflow.lite.DataType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.math.abs

/**
 * HIGH-END YUV420 conversion utilities with adaptive color interpretation and
 * no-scale fast paths for real-time camera pipelines.
 */
internal object Yuv420ImageTools {
    private const val TAG = "Yuv420ImageTools"
    private val loggedLayouts = Collections.synchronizedSet(mutableSetOf<String>())
    private val loggedInterpretations = Collections.synchronizedSet(mutableSetOf<String>())
    private val loggedSemiPlanarConversions = Collections.synchronizedSet(mutableSetOf<String>())
    private val cachedInterpretations =
        Collections.synchronizedMap(mutableMapOf<String, ColorInterpretation>())

    /**
     * Exact nearest-neighbour coordinates used by the bounded analysis proxy.
     *
     * Camera stream dimensions are stable for thousands of frames. Rebuilding these arrays with
     * floating-point division on every ImageReader callback was pure allocation/CPU churn. The cache
     * stores the result of the original expressions (rather than replacing them with different integer
     * arithmetic), so every selected source sample remains bit-for-bit identical.
     */
    private data class ChromaScaleMapKey(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val targetWidth: Int,
        val targetHeight: Int
    )

    private data class ChromaScaleMap(
        val sourceRows: IntArray,
        val sourceColumns: IntArray
    )

    private const val MAX_CHROMA_SCALE_MAPS = 8
    private val chromaScaleMapLock = Any()
    private val chromaScaleMaps = object : LinkedHashMap<ChromaScaleMapKey, ChromaScaleMap>(
        MAX_CHROMA_SCALE_MAPS,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ChromaScaleMapKey, ChromaScaleMap>?
        ): Boolean = size > MAX_CHROMA_SCALE_MAPS
    }

    internal enum class ChromaLayout { PLANAR, NV12, NV21 }
    internal enum class ColorRange { LIMITED, FULL }

    internal data class Snapshot(
        val width: Int,
        val height: Int,
        val yBytes: ByteArray,
        val uBytes: ByteArray,
        val vBytes: ByteArray,
        val yRowStride: Int,
        val uRowStride: Int,
        val vRowStride: Int,
        val yPixelStride: Int,
        val uPixelStride: Int,
        val vPixelStride: Int,
        val chromaLayout: ChromaLayout,
        val bitDepth: Int = 8
    )

    private data class ImageLayout(
        val width: Int,
        val height: Int,
        val yRowStride: Int,
        val uRowStride: Int,
        val vRowStride: Int,
        val yPixelStride: Int,
        val uPixelStride: Int,
        val vPixelStride: Int,
        val yBufferSize: Int,
        val uBufferSize: Int,
        val vBufferSize: Int,
        val uBufferPosition: Int,
        val vBufferPosition: Int,
        val chromaLayout: ChromaLayout
    )

    private data class ColorInterpretation(
        val colorRange: ColorRange,
        val swapUv: Boolean
    )

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    internal fun packArgb(
        y: Int,
        u: Int,
        v: Int,
        colorRange: ColorRange = ColorRange.FULL
    ): Int {
        return when (colorRange) {
            ColorRange.LIMITED -> packArgbLimitedRange(y, u, v)
            ColorRange.FULL -> packArgbFullRange(y, u, v)
        }
    }

    private fun packArgbLimitedRange(y: Int, u: Int, v: Int): Int {
        val c = (y - 16).coerceAtLeast(0)
        val d = u - 128
        val e = v - 128
        val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
        val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
        val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun packArgbFullRange(y: Int, u: Int, v: Int): Int {
        val yf = y.toFloat()
        val uf = (u - 128).toFloat()
        val vf = (v - 128).toFloat()
        val r = (yf + 1.402f * vf).toInt().coerceIn(0, 255)
        val g = (yf - 0.344136f * uf - 0.714136f * vf).toInt().coerceIn(0, 255)
        val b = (yf + 1.772f * uf).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    internal fun packGrayArgb(y: Int): Int {
        val luma = y.coerceIn(0, 255)
        return (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
    }

    fun isSupported(image: Image): Boolean =
        (image.format == ImageFormat.YUV_420_888 && image.planes.size >= 3) ||
            (isP010(image) && image.planes.size >= 2)

    fun snapshot(image: Image): Snapshot {
        require(isSupported(image)) { "Unsupported image format ${image.format}" }
        if (isP010(image)) return snapshotP010(image)
        val layout = inspectLayout(image)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        return Snapshot(
            width = image.width,
            height = image.height,
            yBytes = copyPlaneBytes(yPlane),
            uBytes = copyPlaneBytes(uPlane),
            vBytes = copyPlaneBytes(vPlane),
            yRowStride = yPlane.rowStride,
            uRowStride = uPlane.rowStride,
            vRowStride = vPlane.rowStride,
            yPixelStride = yPlane.pixelStride.coerceAtLeast(1),
            uPixelStride = uPlane.pixelStride.coerceAtLeast(1),
            vPixelStride = vPlane.pixelStride.coerceAtLeast(1),
            chromaLayout = layout.chromaLayout,
            bitDepth = 8
        )
    }

    /**
     * Chroma-only sibling of [snapshot]: copies ONLY the U/V planes and returns an EMPTY yBytes.
     * The shared [snapshot]/[snapshotP010] used by the NPV10 disk-writer and AI paths are NOT modified;
     * this exists purely so a scene-stats caller that discards the source Y plane can skip the
     * full-resolution Y copy. The chroma extraction is byte-for-byte identical to [snapshot].
     */
    fun snapshotChromaOnly(image: Image): Snapshot {
        require(isSupported(image)) { "Unsupported image format ${image.format}" }
        if (isP010(image)) return snapshotP010ChromaOnly(image)
        val layout = inspectLayout(image)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        return Snapshot(
            width = image.width,
            height = image.height,
            yBytes = ByteArray(0),
            uBytes = copyPlaneBytes(uPlane),
            vBytes = copyPlaneBytes(vPlane),
            yRowStride = yPlane.rowStride,
            uRowStride = uPlane.rowStride,
            vRowStride = vPlane.rowStride,
            yPixelStride = yPlane.pixelStride.coerceAtLeast(1),
            uPixelStride = uPlane.pixelStride.coerceAtLeast(1),
            vPixelStride = vPlane.pixelStride.coerceAtLeast(1),
            chromaLayout = layout.chromaLayout,
            bitDepth = 8
        )
    }

    /**
     * Builds the same chroma proxy that a full-resolution snapshot followed by a scaled analysis
     * read would produce, without first copying every source pixel. The returned arrays are heap
     * owned, so callers can close the Image immediately after this method returns.
     *
     * This is a bandwidth optimization only: source coordinates, P010 10-bit words, chroma order,
     * and the later 10-to-8 normalization remain identical to [snapshotChromaOnly].
     */
    fun snapshotScaledChromaOnly(
        image: Image,
        targetWidth: Int,
        targetHeight: Int,
        normalizeP010ToEightBit: Boolean = false,
        uDestination: ByteArray? = null,
        vDestination: ByteArray? = null
    ): Snapshot {
        require(isSupported(image)) { "Unsupported image format ${image.format}" }
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        val boundedWidth = targetWidth.coerceAtLeast(1)
        val boundedHeight = targetHeight.coerceAtLeast(1)
        val sourceChromaWidth = ((width + 1) / 2).coerceAtLeast(1)
        val sourceChromaHeight = ((height + 1) / 2).coerceAtLeast(1)
        val targetChromaWidth = ((boundedWidth + 1) / 2).coerceAtLeast(1)
        val targetChromaHeight = ((boundedHeight + 1) / 2).coerceAtLeast(1)
        val p010 = isP010(image)
        val bytesPerSample = if (p010 && !normalizeP010ToEightBit) Short.SIZE_BYTES else 1
        val targetChromaRowStride = targetChromaWidth * bytesPerSample
        val requiredBytes = targetChromaRowStride * targetChromaHeight
        val uBytes = uDestination?.takeIf { it.size == requiredBytes } ?: ByteArray(requiredBytes)
        val vBytes = vDestination?.takeIf { it.size == requiredBytes } ?: ByteArray(requiredBytes)
        val scaleMap = chromaScaleMap(
            sourceChromaWidth = sourceChromaWidth,
            sourceChromaHeight = sourceChromaHeight,
            targetChromaWidth = targetChromaWidth,
            targetChromaHeight = targetChromaHeight
        )
        val sourceRows = scaleMap.sourceRows
        val sourceColumns = scaleMap.sourceColumns

        if (p010) {
            val chromaPlane = image.planes[1]
            val chromaBuffer = openPlaneSlice(chromaPlane)
            val sourcePixelStride = chromaPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES * 2)
            copyScaledP010Chroma(
                chromaBuffer = chromaBuffer,
                sourceRowStride = chromaPlane.rowStride,
                sourcePixelStride = sourcePixelStride,
                sourceRows = sourceRows,
                sourceColumns = sourceColumns,
                targetChromaWidth = targetChromaWidth,
                targetChromaHeight = targetChromaHeight,
                targetChromaRowStride = targetChromaRowStride,
                bytesPerSample = bytesPerSample,
                normalizeP010ToEightBit = normalizeP010ToEightBit,
                uBytes = uBytes,
                vBytes = vBytes
            )
        } else {
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val uBuffer = openPlaneSlice(uPlane)
            val vBuffer = openPlaneSlice(vPlane)
            val uPixelStride = uPlane.pixelStride.coerceAtLeast(1)
            val vPixelStride = vPlane.pixelStride.coerceAtLeast(1)
            for (targetY in 0 until targetChromaHeight) {
                val sourceY = sourceRows[targetY]
                for (targetX in 0 until targetChromaWidth) {
                    val sourceX = sourceColumns[targetX]
                    val destinationIndex = targetY * targetChromaRowStride + targetX
                    uBytes[destinationIndex] = readByte(
                        uBuffer,
                        sourceY * uPlane.rowStride + sourceX * uPixelStride
                    ).toByte()
                    vBytes[destinationIndex] = readByte(
                        vBuffer,
                        sourceY * vPlane.rowStride + sourceX * vPixelStride
                    ).toByte()
                }
            }
        }

        return Snapshot(
            width = boundedWidth,
            height = boundedHeight,
            yBytes = ByteArray(0),
            uBytes = uBytes,
            vBytes = vBytes,
            yRowStride = boundedWidth * bytesPerSample,
            uRowStride = targetChromaRowStride,
            vRowStride = targetChromaRowStride,
            yPixelStride = bytesPerSample,
            uPixelStride = bytesPerSample,
            vPixelStride = bytesPerSample,
            chromaLayout = ChromaLayout.PLANAR,
            bitDepth = if (p010 && !normalizeP010ToEightBit) 10 else 8
        )
    }

    /** Shared verbatim traversal for the live P010 proxy and its startup prewarm. */
    private fun copyScaledP010Chroma(
        chromaBuffer: ByteBuffer,
        sourceRowStride: Int,
        sourcePixelStride: Int,
        sourceRows: IntArray,
        sourceColumns: IntArray,
        targetChromaWidth: Int,
        targetChromaHeight: Int,
        targetChromaRowStride: Int,
        bytesPerSample: Int,
        normalizeP010ToEightBit: Boolean,
        uBytes: ByteArray,
        vBytes: ByteArray,
        allowNative: Boolean = true
    ) {
        if (normalizeP010ToEightBit) {
            val sourceLimit = chromaBuffer.limit()
            if (
                allowNative &&
                NativeAnalysisIngress.copyScaledP010Chroma(
                    source = chromaBuffer,
                    sourceLimit = sourceLimit,
                    sourceRowStride = sourceRowStride,
                    sourcePixelStride = sourcePixelStride,
                    sourceRows = sourceRows,
                    sourceColumns = sourceColumns,
                    targetWidth = targetChromaWidth,
                    targetHeight = targetChromaHeight,
                    targetRowStride = targetChromaRowStride,
                    uDestination = uBytes,
                    vDestination = vBytes
                )
            ) {
                return
            }
            for (targetY in 0 until targetChromaHeight) {
                val sourceY = sourceRows[targetY]
                for (targetX in 0 until targetChromaWidth) {
                    val sourceX = sourceColumns[targetX]
                    val sourceIndex = sourceY * sourceRowStride + sourceX * sourcePixelStride
                    val destinationIndex = targetY * targetChromaRowStride + targetX
                    if (sourceIndex >= 0 && sourceIndex + 1 < sourceLimit) {
                        val word = chromaBuffer.getShort(sourceIndex).toInt() and 0xFFFF
                        val code10 = (word ushr 6).coerceIn(0, 1023)
                        uBytes[destinationIndex] =
                            ((code10 + 2) ushr 2).coerceIn(0, 255).toByte()
                    } else {
                        uBytes[destinationIndex] = 0
                    }
                    val vSourceIndex = sourceIndex + Short.SIZE_BYTES
                    if (vSourceIndex >= 0 && vSourceIndex + 1 < sourceLimit) {
                        val word = chromaBuffer.getShort(vSourceIndex).toInt() and 0xFFFF
                        val code10 = (word ushr 6).coerceIn(0, 1023)
                        vBytes[destinationIndex] =
                            ((code10 + 2) ushr 2).coerceIn(0, 255).toByte()
                    } else {
                        vBytes[destinationIndex] = 0
                    }
                }
            }
        } else {
            for (targetY in 0 until targetChromaHeight) {
                val sourceY = sourceRows[targetY]
                for (targetX in 0 until targetChromaWidth) {
                    val sourceX = sourceColumns[targetX]
                    val sourceIndex = sourceY * sourceRowStride + sourceX * sourcePixelStride
                    val destinationIndex =
                        targetY * targetChromaRowStride + targetX * bytesPerSample
                    copyTwoBytes(chromaBuffer, sourceIndex, uBytes, destinationIndex)
                    copyTwoBytes(
                        chromaBuffer,
                        sourceIndex + Short.SIZE_BYTES,
                        vBytes,
                        destinationIndex
                    )
                }
            }
        }
    }

    /**
     * Runs the exact live P010-to-analysis traversal on a synthetic direct buffer before the first
     * ImageReader frame. This moves ART compilation and direct-buffer accessor resolution away from
     * the camera callback without changing any live source coordinate or output sample.
     */
    fun prewarmScaledP010ChromaKernel(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        passes: Int = 3
    ): Int {
        val sourceChromaWidth = ((sourceWidth.coerceAtLeast(1) + 1) / 2).coerceAtLeast(1)
        val sourceChromaHeight = ((sourceHeight.coerceAtLeast(1) + 1) / 2).coerceAtLeast(1)
        val targetChromaWidth = ((targetWidth.coerceAtLeast(1) + 1) / 2).coerceAtLeast(1)
        val targetChromaHeight = ((targetHeight.coerceAtLeast(1) + 1) / 2).coerceAtLeast(1)
        val sourcePixelStride = Short.SIZE_BYTES * 2
        val sourceRowStride = sourceChromaWidth * sourcePixelStride
        val targetChromaRowStride = targetChromaWidth
        val chromaBuffer = ByteBuffer.allocateDirect(sourceRowStride * sourceChromaHeight)
            .order(ByteOrder.LITTLE_ENDIAN)
        // Non-zero sentinels make the writes observable while leaving buffer initialization bounded.
        chromaBuffer.putShort(0, (64 shl 6).toShort())
        chromaBuffer.putShort(Short.SIZE_BYTES, (940 shl 6).toShort())
        val uBytes = ByteArray(targetChromaRowStride * targetChromaHeight)
        val vBytes = ByteArray(targetChromaRowStride * targetChromaHeight)
        val scaleMap = chromaScaleMap(
            sourceChromaWidth = sourceChromaWidth,
            sourceChromaHeight = sourceChromaHeight,
            targetChromaWidth = targetChromaWidth,
            targetChromaHeight = targetChromaHeight
        )
        var sampleOrdinal = 0
        for (targetY in 0 until targetChromaHeight) {
            val sourceRow = scaleMap.sourceRows[targetY] * sourceRowStride
            for (targetX in 0 until targetChromaWidth) {
                val sourceIndex =
                    sourceRow + scaleMap.sourceColumns[targetX] * sourcePixelStride
                val uCode10 = (sampleOrdinal * 73 + targetY * 19) and 0x3FF
                val vCode10 = (sampleOrdinal * 47 + targetX * 31) and 0x3FF
                chromaBuffer.putShort(sourceIndex, (uCode10 shl 6).toShort())
                chromaBuffer.putShort(
                    sourceIndex + Short.SIZE_BYTES,
                    (vCode10 shl 6).toShort()
                )
                sampleOrdinal++
            }
        }
        var checksum = 0
        copyScaledP010Chroma(
            chromaBuffer = chromaBuffer,
            sourceRowStride = sourceRowStride,
            sourcePixelStride = sourcePixelStride,
            sourceRows = scaleMap.sourceRows,
            sourceColumns = scaleMap.sourceColumns,
            targetChromaWidth = targetChromaWidth,
            targetChromaHeight = targetChromaHeight,
            targetChromaRowStride = targetChromaRowStride,
            bytesPerSample = 1,
            normalizeP010ToEightBit = true,
            uBytes = uBytes,
            vBytes = vBytes
        )
        val referenceU = ByteArray(uBytes.size)
        val referenceV = ByteArray(vBytes.size)
        copyScaledP010Chroma(
            chromaBuffer = chromaBuffer,
            sourceRowStride = sourceRowStride,
            sourcePixelStride = sourcePixelStride,
            sourceRows = scaleMap.sourceRows,
            sourceColumns = scaleMap.sourceColumns,
            targetChromaWidth = targetChromaWidth,
            targetChromaHeight = targetChromaHeight,
            targetChromaRowStride = targetChromaRowStride,
            bytesPerSample = 1,
            normalizeP010ToEightBit = true,
            uBytes = referenceU,
            vBytes = referenceV,
            allowNative = false
        )
        if (!uBytes.contentEquals(referenceU) || !vBytes.contentEquals(referenceV)) {
            NativeAnalysisIngress.disable()
            System.arraycopy(referenceU, 0, uBytes, 0, uBytes.size)
            System.arraycopy(referenceV, 0, vBytes, 0, vBytes.size)
        }
        checksum = checksum xor (uBytes[0].toInt() and 0xFF)
        checksum = checksum xor ((vBytes[0].toInt() and 0xFF) shl 8)
        repeat((passes.coerceAtLeast(1) - 1).coerceAtLeast(0)) {
            copyScaledP010Chroma(
                chromaBuffer = chromaBuffer,
                sourceRowStride = sourceRowStride,
                sourcePixelStride = sourcePixelStride,
                sourceRows = scaleMap.sourceRows,
                sourceColumns = scaleMap.sourceColumns,
                targetChromaWidth = targetChromaWidth,
                targetChromaHeight = targetChromaHeight,
                targetChromaRowStride = targetChromaRowStride,
                bytesPerSample = 1,
                normalizeP010ToEightBit = true,
                uBytes = uBytes,
                vBytes = vBytes
            )
            checksum = checksum xor (uBytes[0].toInt() and 0xFF)
            checksum = checksum xor ((vBytes[0].toInt() and 0xFF) shl 8)
        }
        return checksum
    }

    private fun chromaScaleMap(
        sourceChromaWidth: Int,
        sourceChromaHeight: Int,
        targetChromaWidth: Int,
        targetChromaHeight: Int
    ): ChromaScaleMap {
        val key = ChromaScaleMapKey(
            sourceWidth = sourceChromaWidth,
            sourceHeight = sourceChromaHeight,
            targetWidth = targetChromaWidth,
            targetHeight = targetChromaHeight
        )
        synchronized(chromaScaleMapLock) {
            chromaScaleMaps[key]?.let { return it }
        }
        val created = ChromaScaleMap(
            sourceRows = IntArray(targetChromaHeight) { targetY ->
                ((targetY.toFloat() / targetChromaHeight) * sourceChromaHeight)
                    .toInt()
                    .coerceIn(0, sourceChromaHeight - 1)
            },
            sourceColumns = IntArray(targetChromaWidth) { targetX ->
                ((targetX.toFloat() / targetChromaWidth) * sourceChromaWidth)
                    .toInt()
                    .coerceIn(0, sourceChromaWidth - 1)
            }
        )
        return synchronized(chromaScaleMapLock) {
            chromaScaleMaps[key] ?: created.also { chromaScaleMaps[key] = it }
        }
    }

    private fun snapshotP010ChromaOnly(image: Image): Snapshot {
        val chromaPlane = image.planes[1]
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val uBytes = ByteArray(chromaWidth * chromaHeight * Short.SIZE_BYTES)
        val vBytes = ByteArray(chromaWidth * chromaHeight * Short.SIZE_BYTES)
        val chroma = planeSlice(chromaPlane)
        val chromaPixelStride = chromaPlane.pixelStride.coerceAtLeast(4)
        for (row in 0 until chromaHeight) {
            for (column in 0 until chromaWidth) {
                val src = row * chromaPlane.rowStride + column * chromaPixelStride
                val dst = (row * chromaWidth + column) * Short.SIZE_BYTES
                copyTwoBytes(chroma, src, uBytes, dst)
                copyTwoBytes(chroma, src + Short.SIZE_BYTES, vBytes, dst)
            }
        }
        return Snapshot(
            width = width,
            height = height,
            yBytes = ByteArray(0),
            uBytes = uBytes,
            vBytes = vBytes,
            yRowStride = width * Short.SIZE_BYTES,
            uRowStride = chromaWidth * Short.SIZE_BYTES,
            vRowStride = chromaWidth * Short.SIZE_BYTES,
            yPixelStride = Short.SIZE_BYTES,
            uPixelStride = Short.SIZE_BYTES,
            vPixelStride = Short.SIZE_BYTES,
            chromaLayout = ChromaLayout.PLANAR,
            bitDepth = 10
        )
    }

    fun isP010(image: Image): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            image.format == ImageFormat.YCBCR_P010

    private fun snapshotP010(image: Image): Snapshot {
        val yPlane = image.planes[0]
        val chromaPlane = image.planes[1]
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val yBytes = copyP010Plane(
            plane = yPlane,
            width = width,
            height = height,
            sourcePixelStride = yPlane.pixelStride.coerceAtLeast(2),
            outputRowStride = width * Short.SIZE_BYTES
        )
        val uBytes = ByteArray(chromaWidth * chromaHeight * Short.SIZE_BYTES)
        val vBytes = ByteArray(chromaWidth * chromaHeight * Short.SIZE_BYTES)
        val chroma = planeSlice(chromaPlane)
        val chromaPixelStride = chromaPlane.pixelStride.coerceAtLeast(4)
        for (row in 0 until chromaHeight) {
            for (column in 0 until chromaWidth) {
                val src = row * chromaPlane.rowStride + column * chromaPixelStride
                val dst = (row * chromaWidth + column) * Short.SIZE_BYTES
                copyTwoBytes(chroma, src, uBytes, dst)
                copyTwoBytes(chroma, src + Short.SIZE_BYTES, vBytes, dst)
            }
        }
        return Snapshot(
            width = width,
            height = height,
            yBytes = yBytes,
            uBytes = uBytes,
            vBytes = vBytes,
            yRowStride = width * Short.SIZE_BYTES,
            uRowStride = chromaWidth * Short.SIZE_BYTES,
            vRowStride = chromaWidth * Short.SIZE_BYTES,
            yPixelStride = Short.SIZE_BYTES,
            uPixelStride = Short.SIZE_BYTES,
            vPixelStride = Short.SIZE_BYTES,
            chromaLayout = ChromaLayout.PLANAR,
            bitDepth = 10
        )
    }

    private fun copyP010Plane(
        plane: Image.Plane,
        width: Int,
        height: Int,
        sourcePixelStride: Int,
        outputRowStride: Int
    ): ByteArray {
        val source = planeSlice(plane)
        val output = ByteArray(outputRowStride * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val src = row * plane.rowStride + column * sourcePixelStride
                val dst = row * outputRowStride + column * Short.SIZE_BYTES
                copyTwoBytes(source, src, output, dst)
            }
        }
        return output
    }

    private fun copyTwoBytes(source: java.nio.ByteBuffer, sourceIndex: Int, output: ByteArray, outputIndex: Int) {
        output[outputIndex] = if (sourceIndex in 0 until source.limit()) source.get(sourceIndex) else 0
        output[outputIndex + 1] =
            if (sourceIndex + 1 in 0 until source.limit()) source.get(sourceIndex + 1) else 0
    }

    fun fillArgbPixels(
        image: Image,
        targetWidth: Int,
        targetHeight: Int,
        outPixels: IntArray
    ) {
        require(isSupported(image)) { "Unsupported image format ${image.format}" }
        require(outPixels.size >= targetWidth * targetHeight) {
            "Output pixel buffer is too small for ${targetWidth}x${targetHeight}"
        }
        if (isP010(image)) {
            fillArgbPixelsP010(image, targetWidth, targetHeight, outPixels)
            return
        }

        val layout = inspectLayout(image)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = planeSlice(yPlane)
        val uBuffer = planeSlice(uPlane)
        val vBuffer = planeSlice(vPlane)
        val interpretation = resolveColorInterpretation(layout, yBuffer, uBuffer, vBuffer)
        if (targetWidth == layout.width && targetHeight == layout.height) {
            fillArgbPixelsNoScale(
                layout = layout,
                yBuffer = yBuffer,
                uBuffer = uBuffer,
                vBuffer = vBuffer,
                interpretation = interpretation,
                outPixels = outPixels
            )
            return
        }
        fillArgbPixels(
            srcWidth = layout.width,
            srcHeight = layout.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            outPixels = outPixels
        ) { srcX, srcY, chromaX, chromaY ->
            val y = readByte(
                yBuffer,
                srcY * layout.yRowStride + srcX * layout.yPixelStride
            )
            var u = readByte(
                uBuffer,
                chromaY * layout.uRowStride + chromaX * layout.uPixelStride
            )
            var v = readByte(
                vBuffer,
                chromaY * layout.vRowStride + chromaX * layout.vPixelStride
            )
            if (interpretation.swapUv) {
                val chroma = u
                u = v
                v = chroma
            }
            packArgb(y, u, v, interpretation.colorRange)
        }
    }

    /**
     * Produces a bounded, heap-owned ARGB result while the caller still owns an open Image. Unlike
     * [fillArgbPixels], this path does not make full-resolution heap copies of the source planes;
     * it reads only the exact source samples selected for the requested output dimensions.
     * No live plane view escapes this method.
     */
    fun fillArgbPixelsFromOpenImage(
        image: Image,
        targetWidth: Int,
        targetHeight: Int,
        outPixels: IntArray
    ) {
        require(isSupported(image)) { "Unsupported image format ${image.format}" }
        require(outPixels.size >= targetWidth * targetHeight) {
            "Output pixel buffer is too small for ${targetWidth}x${targetHeight}"
        }
        if (isP010(image)) {
            val width = image.width.coerceAtLeast(1)
            val height = image.height.coerceAtLeast(1)
            val yPlane = image.planes[0]
            val chromaPlane = image.planes[1]
            val yBuffer = openPlaneSlice(yPlane)
            val chromaBuffer = openPlaneSlice(chromaPlane)
            val yPixelStride = yPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES)
            val chromaPixelStride = chromaPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES * 2)
            fillArgbPixels(
                srcWidth = width,
                srcHeight = height,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                outPixels = outPixels
            ) { srcX, srcY, chromaX, chromaY ->
                val y = readP010SampleAs8(
                    yBuffer,
                    srcY * yPlane.rowStride + srcX * yPixelStride,
                    fallback = 0
                )
                val chromaIndex =
                    chromaY * chromaPlane.rowStride + chromaX * chromaPixelStride
                val u = readP010SampleAs8(chromaBuffer, chromaIndex, fallback = 128)
                val v = readP010SampleAs8(
                    chromaBuffer,
                    chromaIndex + Short.SIZE_BYTES,
                    fallback = 128
                )
                packArgb(y, u, v)
            }
            return
        }

        val layout = inspectLayout(image)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = openPlaneSlice(yPlane)
        val uBuffer = openPlaneSlice(uPlane)
        val vBuffer = openPlaneSlice(vPlane)
        val interpretation = resolveColorInterpretation(layout, yBuffer, uBuffer, vBuffer)
        fillArgbPixels(
            srcWidth = layout.width,
            srcHeight = layout.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            outPixels = outPixels
        ) { srcX, srcY, chromaX, chromaY ->
            val y = readByte(
                yBuffer,
                srcY * layout.yRowStride + srcX * layout.yPixelStride
            )
            var u = readByte(
                uBuffer,
                chromaY * layout.uRowStride + chromaX * layout.uPixelStride
            )
            var v = readByte(
                vBuffer,
                chromaY * layout.vRowStride + chromaX * layout.vPixelStride
            )
            if (interpretation.swapUv) {
                val chroma = u
                u = v
                v = chroma
            }
            packArgb(y, u, v, interpretation.colorRange)
        }
    }

    private fun fillArgbPixelsP010(
        image: Image,
        targetWidth: Int,
        targetHeight: Int,
        outPixels: IntArray
    ) {
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        val yPlane = image.planes[0]
        val chromaPlane = image.planes[1]
        val yBuffer = planeSlice(yPlane)
        val chromaBuffer = planeSlice(chromaPlane)
        val yPixelStride = yPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES)
        val chromaPixelStride = chromaPlane.pixelStride.coerceAtLeast(Short.SIZE_BYTES * 2)

        fillArgbPixels(
            srcWidth = width,
            srcHeight = height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            outPixels = outPixels
        ) { srcX, srcY, chromaX, chromaY ->
            val y = readP010SampleAs8(
                yBuffer,
                srcY * yPlane.rowStride + srcX * yPixelStride,
                fallback = 0
            )
            val chromaIndex =
                chromaY * chromaPlane.rowStride + chromaX * chromaPixelStride
            val u = readP010SampleAs8(chromaBuffer, chromaIndex, fallback = 128)
            val v = readP010SampleAs8(
                chromaBuffer,
                chromaIndex + Short.SIZE_BYTES,
                fallback = 128
            )
            packArgb(y, u, v)
        }
    }

    fun fillModelInput(
        image: Image,
        targetWidth: Int,
        targetHeight: Int,
        dataType: DataType,
        outBuffer: ByteBuffer
    ) {
        require(isSupported(image)) { "Unsupported image format ${image.format}" }
        if (isP010(image)) {
            fillModelInput(snapshotP010(image), targetWidth, targetHeight, dataType, outBuffer)
            return
        }
        outBuffer.clear()

        val layout = inspectLayout(image)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = planeSlice(yPlane)
        val uBuffer = planeSlice(uPlane)
        val vBuffer = planeSlice(vPlane)
        val interpretation = resolveColorInterpretation(layout, yBuffer, uBuffer, vBuffer)
        if (targetWidth == layout.width && targetHeight == layout.height) {
            fillModelInputNoScale(
                layout = layout,
                yBuffer = yBuffer,
                uBuffer = uBuffer,
                vBuffer = vBuffer,
                interpretation = interpretation,
                dataType = dataType,
                outBuffer = outBuffer
            )
            outBuffer.rewind()
            return
        }
        fillModelInput(
            srcWidth = layout.width,
            srcHeight = layout.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            dataType = dataType,
            outBuffer = outBuffer
        ) { srcX, srcY, chromaX, chromaY ->
            val y = readByte(
                yBuffer,
                srcY * layout.yRowStride + srcX * layout.yPixelStride
            )
            var u = readByte(
                uBuffer,
                chromaY * layout.uRowStride + chromaX * layout.uPixelStride
            )
            var v = readByte(
                vBuffer,
                chromaY * layout.vRowStride + chromaX * layout.vPixelStride
            )
            if (interpretation.swapUv) {
                val chroma = u
                u = v
                v = chroma
            }
            packArgb(y, u, v, interpretation.colorRange)
        }

        outBuffer.rewind()
    }

    private fun fillArgbPixelsNoScale(
        layout: ImageLayout,
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        interpretation: ColorInterpretation,
        outPixels: IntArray
    ) {
        val width = layout.width
        val height = layout.height
        var targetIndex = 0
        for (srcY in 0 until height) {
            val yRowOffset = srcY * layout.yRowStride
            val chromaY = srcY / 2
            val uRowOffset = chromaY * layout.uRowStride
            val vRowOffset = chromaY * layout.vRowStride
            for (srcX in 0 until width) {
                val chromaX = srcX / 2
                val y = readByte(yBuffer, yRowOffset + srcX * layout.yPixelStride)
                var u = readByte(uBuffer, uRowOffset + chromaX * layout.uPixelStride)
                var v = readByte(vBuffer, vRowOffset + chromaX * layout.vPixelStride)
                if (interpretation.swapUv) {
                    val chroma = u
                    u = v
                    v = chroma
                }
                outPixels[targetIndex++] = packArgb(y, u, v, interpretation.colorRange)
            }
        }
    }

    private fun fillModelInputNoScale(
        layout: ImageLayout,
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        interpretation: ColorInterpretation,
        dataType: DataType,
        outBuffer: ByteBuffer
    ) {
        val width = layout.width
        val height = layout.height
        for (srcY in 0 until height) {
            val yRowOffset = srcY * layout.yRowStride
            val chromaY = srcY / 2
            val uRowOffset = chromaY * layout.uRowStride
            val vRowOffset = chromaY * layout.vRowStride
            for (srcX in 0 until width) {
                val chromaX = srcX / 2
                val y = readByte(yBuffer, yRowOffset + srcX * layout.yPixelStride)
                var u = readByte(uBuffer, uRowOffset + chromaX * layout.uPixelStride)
                var v = readByte(vBuffer, vRowOffset + chromaX * layout.vPixelStride)
                if (interpretation.swapUv) {
                    val chroma = u
                    u = v
                    v = chroma
                }
                val argb = packArgb(y, u, v, interpretation.colorRange)
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                when (dataType) {
                    DataType.FLOAT32 -> {
                        outBuffer.putFloat(r / 255f)
                        outBuffer.putFloat(g / 255f)
                        outBuffer.putFloat(b / 255f)
                    }
                    DataType.UINT8 -> {
                        outBuffer.put(r.toByte())
                        outBuffer.put(g.toByte())
                        outBuffer.put(b.toByte())
                    }
                    else -> error("Unsupported model input tensor type $dataType")
                }
            }
        }
    }

    fun fillModelInput(
        snapshot: Snapshot,
        targetWidth: Int,
        targetHeight: Int,
        dataType: DataType,
        outBuffer: ByteBuffer
    ) {
        outBuffer.clear()

        fillModelInput(
            srcWidth = snapshot.width,
            srcHeight = snapshot.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            dataType = dataType,
            outBuffer = outBuffer
        ) { srcX, srcY, chromaX, chromaY ->
            val y = readSnapshotSampleAs8(
                snapshot.yBytes,
                srcY * snapshot.yRowStride + srcX * snapshot.yPixelStride,
                snapshot.bitDepth,
                fallback = 0
            )
            val u = readSnapshotSampleAs8(
                snapshot.uBytes,
                chromaY * snapshot.uRowStride + chromaX * snapshot.uPixelStride,
                snapshot.bitDepth,
                fallback = 128
            )
            val v = readSnapshotSampleAs8(
                snapshot.vBytes,
                chromaY * snapshot.vRowStride + chromaX * snapshot.vPixelStride,
                snapshot.bitDepth,
                fallback = 128
            )
            packArgb(y, u, v)
        }

        outBuffer.rewind()
    }

    fun copyToNv21(image: Image, swapUv: Boolean = false): ByteArray {
        val nv21 = ByteArray(requiredNv21Size(image.width, image.height))
        fillSemiPlanar(image, nv21, outputLayout = ChromaLayout.NV21, swapUv = swapUv)
        return nv21
    }

    fun fillNv21(image: Image, outBuffer: ByteArray, swapUv: Boolean = false) {
        fillSemiPlanar(image, outBuffer, outputLayout = ChromaLayout.NV21, swapUv = swapUv)
    }

    fun copyToNv12(image: Image, swapUv: Boolean = false): ByteArray {
        val nv12 = ByteArray(requiredNv21Size(image.width, image.height))
        fillSemiPlanar(image, nv12, outputLayout = ChromaLayout.NV12, swapUv = swapUv)
        return nv12
    }

    fun fillNv12(image: Image, outBuffer: ByteArray, swapUv: Boolean = false) {
        fillSemiPlanar(image, outBuffer, outputLayout = ChromaLayout.NV12, swapUv = swapUv)
    }

    private fun fillSemiPlanar(
        image: Image,
        outBuffer: ByteArray,
        outputLayout: ChromaLayout,
        swapUv: Boolean = false
    ) {
        require(isSupported(image)) { "Unsupported image format ${image.format}" }
        require(outputLayout == ChromaLayout.NV21 || outputLayout == ChromaLayout.NV12) {
            "Unsupported semi-planar output layout: $outputLayout"
        }
        if (isP010(image)) {
            fillSemiPlanar(snapshotP010(image), outBuffer, outputLayout, swapUv)
            return
        }
        val layout = inspectLayout(image)
        val requiredSize = requiredNv21Size(layout.width, layout.height)
        require(outBuffer.size >= requiredSize) {
            "Output $outputLayout buffer is too small for ${layout.width}x${layout.height}"
        }

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = planeSlice(yPlane)
        val uBuffer = planeSlice(uPlane)
        val vBuffer = planeSlice(vPlane)
        val interpretation = resolveColorInterpretation(layout, yBuffer, uBuffer, vBuffer)
        val effectiveSwapUv = interpretation.swapUv.xor(swapUv)
        logSemiPlanarConversionOnce(
            layout = layout,
            outputLayout = outputLayout,
            requestedSwapUv = swapUv,
            interpretedSwapUv = interpretation.swapUv,
            effectiveSwapUv = effectiveSwapUv
        )
        val width = layout.width
        val height = layout.height
        val chromaWidth = ((width + 1) / 2).coerceAtLeast(1)
        val chromaHeight = ((height + 1) / 2).coerceAtLeast(1)

        var dstIndex = 0
        for (srcY in 0 until height) {
            for (srcX in 0 until width) {
                val y = readByte(
                    yBuffer,
                    srcY * layout.yRowStride + srcX * layout.yPixelStride
                )
                outBuffer[dstIndex++] = y.toByte()
            }
        }

        for (chromaY in 0 until chromaHeight) {
            for (chromaX in 0 until chromaWidth) {
                val u = readByte(
                    uBuffer,
                    chromaY * layout.uRowStride + chromaX * layout.uPixelStride
                )
                val v = readByte(
                    vBuffer,
                    chromaY * layout.vRowStride + chromaX * layout.vPixelStride
                )
                val (first, second) = resolveChromaBytesForSemiPlanar(
                    u = u,
                    v = v,
                    outputLayout = outputLayout,
                    swapUv = effectiveSwapUv
                )
                outBuffer[dstIndex++] = first
                outBuffer[dstIndex++] = second
            }
        }
    }

    fun fillArgbPixels(
        snapshot: Snapshot,
        targetWidth: Int,
        targetHeight: Int,
        outPixels: IntArray
    ) {
        require(outPixels.size >= targetWidth * targetHeight) {
            "Output pixel buffer is too small for ${targetWidth}x${targetHeight}"
        }
        val chromaWidth = ((snapshot.width + 1) / 2).coerceAtLeast(1)
        val chromaHeight = ((snapshot.height + 1) / 2).coerceAtLeast(1)
        fillArgbPixels(
            srcWidth = snapshot.width,
            srcHeight = snapshot.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            outPixels = outPixels
        ) { srcX, srcY, chromaX, chromaY ->
            val safeChromaX = chromaX.coerceIn(0, chromaWidth - 1)
            val safeChromaY = chromaY.coerceIn(0, chromaHeight - 1)
            val y = readSnapshotSampleAs8(
                snapshot.yBytes,
                srcY * snapshot.yRowStride + srcX * snapshot.yPixelStride,
                snapshot.bitDepth,
                fallback = 0
            )
            val u = readSnapshotSampleAs8(
                snapshot.uBytes,
                safeChromaY * snapshot.uRowStride + safeChromaX * snapshot.uPixelStride,
                snapshot.bitDepth,
                fallback = 128
            )
            val v = readSnapshotSampleAs8(
                snapshot.vBytes,
                safeChromaY * snapshot.vRowStride + safeChromaX * snapshot.vPixelStride,
                snapshot.bitDepth,
                fallback = 128
            )
            packArgb(y, u, v)
        }
    }

    private fun fillSemiPlanar(
        snapshot: Snapshot,
        outBuffer: ByteArray,
        outputLayout: ChromaLayout,
        swapUv: Boolean
    ) {
        val requiredSize = requiredNv21Size(snapshot.width, snapshot.height)
        require(outBuffer.size >= requiredSize) {
            "Output $outputLayout buffer is too small for ${snapshot.width}x${snapshot.height}"
        }
        val chromaWidth = ((snapshot.width + 1) / 2).coerceAtLeast(1)
        val chromaHeight = ((snapshot.height + 1) / 2).coerceAtLeast(1)
        var dstIndex = 0
        for (srcY in 0 until snapshot.height) {
            for (srcX in 0 until snapshot.width) {
                outBuffer[dstIndex++] = readSnapshotSampleAs8(
                    snapshot.yBytes,
                    srcY * snapshot.yRowStride + srcX * snapshot.yPixelStride,
                    snapshot.bitDepth,
                    fallback = 0
                ).toByte()
            }
        }
        for (chromaY in 0 until chromaHeight) {
            for (chromaX in 0 until chromaWidth) {
                val u = readSnapshotSampleAs8(
                    snapshot.uBytes,
                    chromaY * snapshot.uRowStride + chromaX * snapshot.uPixelStride,
                    snapshot.bitDepth,
                    fallback = 128
                )
                val v = readSnapshotSampleAs8(
                    snapshot.vBytes,
                    chromaY * snapshot.vRowStride + chromaX * snapshot.vPixelStride,
                    snapshot.bitDepth,
                    fallback = 128
                )
                val (first, second) = resolveChromaBytesForSemiPlanar(
                    u = u,
                    v = v,
                    outputLayout = outputLayout,
                    swapUv = swapUv
                )
                outBuffer[dstIndex++] = first
                outBuffer[dstIndex++] = second
            }
        }
    }

    internal fun resolveChromaBytesForSemiPlanar(
        u: Int,
        v: Int,
        outputLayout: ChromaLayout,
        swapUv: Boolean
    ): Pair<Byte, Byte> {
        val resolvedU = if (swapUv) v else u
        val resolvedV = if (swapUv) u else v
        return when (outputLayout) {
            ChromaLayout.NV21 -> Pair(resolvedV.toByte(), resolvedU.toByte())
            ChromaLayout.NV12 -> Pair(resolvedU.toByte(), resolvedV.toByte())
            ChromaLayout.PLANAR -> Pair(resolvedU.toByte(), resolvedV.toByte())
        }
    }

    private fun copyPlaneBytes(plane: Image.Plane): ByteArray {
        return try {
            // Capture the buffer reference and its position/limit in one observation
            // so a racing Image.close() or buffer-reuse cannot change them mid-read.
            val src = plane.buffer
            val pos = src.position()
            val lim = src.limit()
            val count = (lim - pos).coerceAtLeast(0)
            if (count == 0) return ByteArray(0)
            val out = ByteArray(count)
            val dup = src.duplicate().apply {
                position(pos)
                limit(lim)
            }
            dup.get(out)
            out
        } catch (t: Throwable) {
            // Catches IllegalStateException (closed Image), BufferUnderflowException,
            // and any other recoverable error. SIGSEGVs cannot be caught here but
            // this narrows the window by minimising repeated buffer-field access.
            Log.w(TAG, "copyPlaneBytes failed; returning empty", t)
            ByteArray(0)
        }
    }

    private fun planeSlice(plane: Image.Plane): ByteBuffer {
        // UPGRADE (crash-fix, no behaviour change): return a HEAP-backed copy of the plane's
        // [position, limit) bytes instead of a live duplicate().slice() view over the Image's
        // gralloc/ashmem mapping. Downstream conversion/scoring reads samples LAZILY across a
        // scaled per-pixel loop; if the owning Image is closed/recycled by its ImageReader on
        // another thread mid-loop, that native mapping is unmapped and the next absolute
        // get(index) dereferences freed memory -> SIGSEGV (use-after-Image.close; device
        // tombstone: DirectByteBuffer.get <- readP010SampleAs8 <- fillArgbPixelsP010, fault at
        // an unmapped address). The old index+1<limit() guard cannot detect an unmapped page.
        // A heap ByteArray can never be unmapped, so the thousands of subsequent per-pixel reads
        // (the exact tombstone crash site) can no longer fault; the sole remaining touch of the
        // live mapping is copyPlaneBytes()' single guarded bulk read below.
        //
        // Indexing is preserved bit-for-bit: ByteBuffer.wrap() gives index 0 == the original
        // buffer position and limit() == (limit - position) -- exactly what the old slice()
        // exposed -- so readByte()/readP010SampleAs8()/copyTwoBytes()/resolveColorInterpretation()
        // produce identical pixels. copyPlaneBytes() performs the copy as a SINGLE bounded bulk
        // read while the Image is still alive (replacing thousands of lazy live reads) and, if the
        // Image was already torn down, degrades to an empty buffer -> neutral fallback samples
        // rather than a crash. This also hardens the snapshot builders (snapshotP010/copyP010Plane)
        // whose per-pixel copyTwoBytes loops previously read the live mapping directly.
        return ByteBuffer.wrap(copyPlaneBytes(plane))
    }

    /** Synchronous view used only while the caller guarantees that the owning Image stays open. */
    private fun openPlaneSlice(plane: Image.Plane): ByteBuffer =
        plane.buffer.duplicate().slice().order(ByteOrder.LITTLE_ENDIAN)

    private inline fun fillArgbPixels(
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        outPixels: IntArray,
        crossinline packPixel: (srcX: Int, srcY: Int, chromaX: Int, chromaY: Int) -> Int
    ) {
        forEachScaledPixel(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        ) { srcX, srcY, chromaX, chromaY, targetIndex ->
            outPixels[targetIndex] = packPixel(srcX, srcY, chromaX, chromaY)
        }
    }

    private inline fun fillModelInput(
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        dataType: DataType,
        outBuffer: ByteBuffer,
        crossinline packPixel: (srcX: Int, srcY: Int, chromaX: Int, chromaY: Int) -> Int
    ) {
        forEachScaledPixel(
            srcWidth = srcWidth,
            srcHeight = srcHeight,
            targetWidth = targetWidth,
            targetHeight = targetHeight
        ) { srcX, srcY, chromaX, chromaY, _ ->
            val argb = packPixel(srcX, srcY, chromaX, chromaY)
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            when (dataType) {
                DataType.FLOAT32 -> {
                    outBuffer.putFloat(r / 255f)
                    outBuffer.putFloat(g / 255f)
                    outBuffer.putFloat(b / 255f)
                }

                DataType.UINT8 -> {
                    outBuffer.put(r.toByte())
                    outBuffer.put(g.toByte())
                    outBuffer.put(b.toByte())
                }

                else -> error("Unsupported model input tensor type $dataType")
            }
        }
    }

    private inline fun forEachScaledPixel(
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        block: (srcX: Int, srcY: Int, chromaX: Int, chromaY: Int, targetIndex: Int) -> Unit
    ) {
        val safeSrcWidth = srcWidth.coerceAtLeast(1)
        val safeSrcHeight = srcHeight.coerceAtLeast(1)
        val chromaMaxX = maxOf(0, ((safeSrcWidth + 1) / 2) - 1)
        val chromaMaxY = maxOf(0, ((safeSrcHeight + 1) / 2) - 1)
        for (targetY in 0 until targetHeight) {
            val srcY = ((targetY.toFloat() / targetHeight.toFloat()) * safeSrcHeight)
                .toInt()
                .coerceIn(0, safeSrcHeight - 1)
            val chromaY = (srcY / 2).coerceIn(0, chromaMaxY)
            for (targetX in 0 until targetWidth) {
                val srcX = ((targetX.toFloat() / targetWidth.toFloat()) * safeSrcWidth)
                    .toInt()
                    .coerceIn(0, safeSrcWidth - 1)
                val chromaX = (srcX / 2).coerceIn(0, chromaMaxX)
                block(srcX, srcY, chromaX, chromaY, targetY * targetWidth + targetX)
            }
        }
    }

    private fun inspectLayout(image: Image): ImageLayout {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val layout = ImageLayout(
            width = image.width,
            height = image.height,
            yRowStride = yPlane.rowStride,
            uRowStride = uPlane.rowStride,
            vRowStride = vPlane.rowStride,
            yPixelStride = yPlane.pixelStride.coerceAtLeast(1),
            uPixelStride = uPlane.pixelStride.coerceAtLeast(1),
            vPixelStride = vPlane.pixelStride.coerceAtLeast(1),
            yBufferSize = yPlane.buffer.duplicate().remaining(),
            uBufferSize = uPlane.buffer.duplicate().remaining(),
            vBufferSize = vPlane.buffer.duplicate().remaining(),
            uBufferPosition = uPlane.buffer.position(),
            vBufferPosition = vPlane.buffer.position(),
            chromaLayout = detectChromaLayout(uPlane, vPlane)
        )
        logLayoutOnce(layout)
        logCoverageWarnings(layout)
        return layout
    }

    private fun resolveColorInterpretation(
        layout: ImageLayout,
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer
    ): ColorInterpretation {
        val key = layoutSignature(layout)
        cachedInterpretations[key]?.let { return it }

        val samples = buildHeuristicSamples(layout.width, layout.height)
        val candidates = listOf(
            ColorInterpretation(colorRange = ColorRange.FULL, swapUv = false),
            ColorInterpretation(colorRange = ColorRange.FULL, swapUv = true),
            ColorInterpretation(colorRange = ColorRange.LIMITED, swapUv = false),
            ColorInterpretation(colorRange = ColorRange.LIMITED, swapUv = true)
        )
        val best = candidates.minByOrNull { interpretation ->
            scoreInterpretation(
                layout = layout,
                yBuffer = yBuffer,
                uBuffer = uBuffer,
                vBuffer = vBuffer,
                samples = samples,
                interpretation = interpretation
            )
        } ?: ColorInterpretation(colorRange = ColorRange.FULL, swapUv = false)
        cachedInterpretations[key] = best
        logInterpretationOnce(key, best)
        return best
    }

    private fun detectChromaLayout(uPlane: Image.Plane, vPlane: Image.Plane): ChromaLayout {
        val uPixelStride = uPlane.pixelStride.coerceAtLeast(1)
        val vPixelStride = vPlane.pixelStride.coerceAtLeast(1)
        if (uPixelStride == 1 && vPixelStride == 1) {
            return ChromaLayout.PLANAR
        }
        if (uPixelStride == 2 && vPixelStride == 2 && uPlane.rowStride == vPlane.rowStride) {
            val uPosition = uPlane.buffer.position()
            val vPosition = vPlane.buffer.position()
            return when {
                vPosition < uPosition -> ChromaLayout.NV21
                uPosition < vPosition -> ChromaLayout.NV12
                else -> ChromaLayout.PLANAR
            }
        }
        return ChromaLayout.PLANAR
    }

    private fun logLayoutOnce(layout: ImageLayout) {
        val layoutKey = layoutSignature(layout)
        if (!loggedLayouts.add(layoutKey)) return
        Log.d(
            TAG,
            "YUV_420_888 format=${ImageFormat.YUV_420_888} width=${layout.width} height=${layout.height} " +
                "y(row=${layout.yRowStride}, pixel=${layout.yPixelStride}, bytes=${layout.yBufferSize}) " +
                "u(row=${layout.uRowStride}, pixel=${layout.uPixelStride}, bytes=${layout.uBufferSize}, pos=${layout.uBufferPosition}) " +
                "v(row=${layout.vRowStride}, pixel=${layout.vPixelStride}, bytes=${layout.vBufferSize}, pos=${layout.vBufferPosition}) " +
                "chroma=${layout.chromaLayout}"
        )
    }

    private fun logInterpretationOnce(layoutKey: String, interpretation: ColorInterpretation) {
        val interpretationKey = "$layoutKey:${interpretation.colorRange}:${interpretation.swapUv}"
        if (!loggedInterpretations.add(interpretationKey)) return
        Log.d(
            TAG,
            "YUV interpretation range=${interpretation.colorRange} swapUv=${interpretation.swapUv} layout=$layoutKey"
        )
    }

    private fun logSemiPlanarConversionOnce(
        layout: ImageLayout,
        outputLayout: ChromaLayout,
        requestedSwapUv: Boolean,
        interpretedSwapUv: Boolean,
        effectiveSwapUv: Boolean
    ) {
        val key = buildString {
            append(layoutSignature(layout))
            append(":out=").append(outputLayout.name)
            append(":reqSwap=").append(requestedSwapUv)
            append(":autoSwap=").append(interpretedSwapUv)
            append(":effectiveSwap=").append(effectiveSwapUv)
        }
        if (!loggedSemiPlanarConversions.add(key)) return
        Log.d(
            TAG,
            "Semi-planar conversion format=${ImageFormat.YUV_420_888} " +
                "src=${layout.width}x${layout.height} out=$outputLayout " +
                "reqSwapUv=$requestedSwapUv autoSwapUv=$interpretedSwapUv effectiveSwapUv=$effectiveSwapUv"
        )
    }

    private fun logCoverageWarnings(layout: ImageLayout) {
        val yRequired = requiredPlaneBytes(
            width = layout.width,
            height = layout.height,
            rowStride = layout.yRowStride,
            pixelStride = layout.yPixelStride
        )
        val chromaWidth = ((layout.width + 1) / 2).coerceAtLeast(1)
        val chromaHeight = ((layout.height + 1) / 2).coerceAtLeast(1)
        val uRequired = requiredPlaneBytes(
            width = chromaWidth,
            height = chromaHeight,
            rowStride = layout.uRowStride,
            pixelStride = layout.uPixelStride
        )
        val vRequired = requiredPlaneBytes(
            width = chromaWidth,
            height = chromaHeight,
            rowStride = layout.vRowStride,
            pixelStride = layout.vPixelStride
        )
        if (layout.yBufferSize < yRequired || layout.uBufferSize < uRequired || layout.vBufferSize < vRequired) {
            Log.w(
                TAG,
                "Plane coverage warning " +
                    "y=${layout.yBufferSize}/$yRequired " +
                    "u=${layout.uBufferSize}/$uRequired " +
                    "v=${layout.vBufferSize}/$vRequired " +
                    "width=${layout.width} height=${layout.height} chroma=${layout.chromaLayout}"
            )
        }
    }

    private fun readByte(buffer: ByteBuffer, index: Int): Int =
        if (index >= 0 && index < buffer.limit()) {
            buffer.get(index).toInt() and 0xFF
        } else {
            0
        }

    private fun readByte(bytes: ByteArray, index: Int): Int =
        if (index >= 0 && index < bytes.size) {
            bytes[index].toInt() and 0xFF
        } else {
            0
        }

    private fun readP010SampleAs8(
        buffer: ByteBuffer,
        index: Int,
        fallback: Int
    ): Int {
        if (index < 0 || index + 1 >= buffer.limit()) return fallback
        val lo = buffer.get(index).toInt() and 0xFF
        val hi = buffer.get(index + 1).toInt() and 0xFF
        val packed = (hi shl 8) or lo
        // P010 stores the 10-bit sample MSB/left-justified in [15:6] with the low 6 bits zero, so the
        // shift is unconditional. A conditional guard on `packed > 1023` mis-handled genuine deep-shadow
        // samples (10-bit value < 16 => packed < 1024) and returned them unshifted, brightening them to
        // near-white. Always right-shift by 6 to recover the true 10-bit value.
        val tenBit = packed ushr 6
        return ((tenBit.coerceIn(0, 1023) * 255) + 511) / 1023
    }

    private fun readSnapshotSampleAs8(
        bytes: ByteArray,
        index: Int,
        bitDepth: Int,
        fallback: Int
    ): Int {
        if (index < 0 || index >= bytes.size) return fallback
        if (bitDepth >= 10) {
            val lo = bytes[index].toInt() and 0xFF
            val hi = bytes.getOrNull(index + 1)?.toInt()?.and(0xFF) ?: 0
            val packed = (hi shl 8) or lo
            // P010 snapshot words are MSB/left-justified (10 bits in [15:6]); shift is unconditional.
            // The old `packed > 1023` guard inverted deep-shadow samples to near-white (see readP010SampleAs8).
            val tenBit = packed ushr 6
            return ((tenBit.coerceIn(0, 1023) * 255) + 511) / 1023
        }
        return bytes[index].toInt() and 0xFF
    }

    private fun requiredNv21Size(width: Int, height: Int): Int {
        val chromaWidth = ((width + 1) / 2).coerceAtLeast(1)
        val chromaHeight = ((height + 1) / 2).coerceAtLeast(1)
        return width.coerceAtLeast(1) * height.coerceAtLeast(1) + chromaWidth * chromaHeight * 2
    }

    private fun scoreInterpretation(
        layout: ImageLayout,
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        samples: List<Pair<Int, Int>>,
        interpretation: ColorInterpretation
    ): Float {
        val strictHighEnd = strictHighEndEnabled()
        var score = 0f
        samples.forEach { (srcX, srcY) ->
            val chromaX = (srcX / 2).coerceAtLeast(0)
            val chromaY = (srcY / 2).coerceAtLeast(0)
            val y = readByte(
                yBuffer,
                srcY * layout.yRowStride + srcX * layout.yPixelStride
            )
            var u = readByte(
                uBuffer,
                chromaY * layout.uRowStride + chromaX * layout.uPixelStride
            )
            var v = readByte(
                vBuffer,
                chromaY * layout.vRowStride + chromaX * layout.vPixelStride
            )
            if (interpretation.swapUv) {
                val chroma = u
                u = v
                v = chroma
            }
            val argb = packArgb(y, u, v, interpretation.colorRange)
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            val reconstructedLuma = (0.299f * r) + (0.587f * g) + (0.114f * b)
            val spread = (abs(r - g) + abs(g - b) + abs(b - r)).toFloat()
            val darknessWeight = 1f + ((255 - y).toFloat() / 255f)
            val clippedChannels =
                (if (r <= 2 || r >= 253) 1 else 0) +
                    (if (g <= 2 || g >= 253) 1 else 0) +
                    (if (b <= 2 || b >= 253) 1 else 0)
            score += abs(reconstructedLuma - y) * if (strictHighEnd) 0.36f else 0.30f
            score += spread * if (strictHighEnd) 0.010f else 0.008f * darknessWeight
            score += clippedChannels * (if (strictHighEnd) 0.95f else (0.80f + darknessWeight * 0.15f))
        }
        return score
    }

    private fun buildHeuristicSamples(width: Int, height: Int): List<Pair<Int, Int>> {
        val safeWidth = (width - 1).coerceAtLeast(0)
        val safeHeight = (height - 1).coerceAtLeast(0)
        val fractions = if (strictHighEndEnabled()) {
            listOf(0.12f, 0.22f, 0.35f, 0.5f, 0.65f, 0.78f, 0.88f)
        } else {
            listOf(0.18f, 0.35f, 0.5f, 0.65f, 0.82f)
        }
        return buildList {
            fractions.forEach { yFraction ->
                fractions.forEach { xFraction ->
                    add(
                        Pair(
                            (safeWidth * xFraction).toInt().coerceIn(0, safeWidth),
                            (safeHeight * yFraction).toInt().coerceIn(0, safeHeight)
                        )
                    )
                }
            }
        }
    }

    private fun layoutSignature(layout: ImageLayout): String =
        buildString {
            append(layout.width).append('x').append(layout.height)
            append(":Y(").append(layout.yRowStride).append(',').append(layout.yPixelStride).append(',').append(layout.yBufferSize).append(')')
            append(":U(").append(layout.uRowStride).append(',').append(layout.uPixelStride).append(',').append(layout.uBufferSize).append('@').append(layout.uBufferPosition).append(')')
            append(":V(").append(layout.vRowStride).append(',').append(layout.vPixelStride).append(',').append(layout.vBufferSize).append('@').append(layout.vBufferPosition).append(')')
            append(':').append(layout.chromaLayout.name)
        }

    private fun requiredPlaneBytes(
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): Int {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        return ((safeHeight - 1) * rowStride) + ((safeWidth - 1) * pixelStride) + 1
    }
}
