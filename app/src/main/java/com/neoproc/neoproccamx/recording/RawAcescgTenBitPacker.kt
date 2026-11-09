package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine
import com.neoproc.neoproccamx.color.NdrToneOperator
import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.pipeline.RawDevelopEliteDetailPolicy
import com.neoproc.neoproccamx.pipeline.RawExposureNormalizationPolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * Packs the RAW->NeoLog converter's shared LINEAR ACEScg seam directly into the final MOV sample.
 *
 * R210 intentionally mirrors RawNeoLogDevelop.finishFromAcescg() + RawTenBitPacker.pack(), while the
 * chroma-subsampled outputs keep NeoLog RGB continuous through the YCbCr matrix and quantize only the
 * final component codes. For a 4096x3072 frame this avoids materialising about 72 MiB of intermediate
 * RGB data, and it avoids compounding an RGB quantization error with a second YCbCr quantization.
 */
internal object RawAcescgTenBitPacker {
    private const val MAX10 = 1023
    private const val QUANTIZE_DITHER_LSB = 0.5f

    private const val KR = 0.2627
    private const val KB = 0.0593
    private const val KG = 1.0 - KR - KB

    private const val LUMA_MIN = 64.0
    private const val LUMA_MAX = 940.0
    private const val CHROMA_MAX = 960.0
    private const val CHROMA_ZERO = 512.0

    fun pack(
        acescg: FloatArray,
        width: Int,
        height: Int,
        variant: NeoLogCurve.Variant,
        exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS,
        outputBitDepth: Int = 10,
        format: RawTenBitPacker.Format,
        detailAlreadyApplied: Boolean = false
    ): ByteArray {
        require(outputBitDepth == 10) { "Raw MOV packing requires 10-bit output" }
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(acescg.size >= width * height * 3) {
            "acescg plane too small for ${width}x$height"
        }
        if (!detailAlreadyApplied) RawDevelopEliteDetailPolicy.enhanceInPlace(acescg, width, height)
        return when (format) {
            RawTenBitPacker.Format.R210_444 -> packR210(acescg, width, height, variant, exposureStops)
            RawTenBitPacker.Format.V210_422 -> packV210(acescg, width, height, variant, exposureStops)
        }
    }

    /**
     * NeoLog RGB to canonical Main10 P010. NeoLog stays continuous Float32 through RGB-to-YCbCr,
     * chroma is averaged over each real 2x2 block, and deterministic TPDF is applied only at the final
     * 10-bit component quantizer. This prevents double-quantization bias and saturated-detail shimmer.
     */
    fun packP010420(
        acescg: FloatArray,
        width: Int,
        height: Int,
        variant: NeoLogCurve.Variant,
        exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS,
        detailAlreadyApplied: Boolean = false
    ): ByteArray {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(acescg.size >= width * height * 3) { "acescg plane too small for ${width}x$height" }
        if (!detailAlreadyApplied) RawDevelopEliteDetailPolicy.enhanceInPlace(acescg, width, height)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaBytes = width * height * Short.SIZE_BYTES
        val output = ByteArray(lumaBytes + chromaWidth * chromaHeight * 2 * Short.SIZE_BYTES)
        val expScale = RawExposureNormalizationPolicy.exposureScale(exposureStops)
        val row0 = FloatArray(width * 3)
        val row1 = FloatArray(width * 3)

        for (cy in 0 until chromaHeight) {
            val y0 = cy * 2
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            fillNeoLogRow(acescg, width, y0, expScale, variant, row0)
            if (y1 != y0) fillNeoLogRow(acescg, width, y1, expScale, variant, row1)

            for (x in 0 until width) {
                val i = x * 3
                val r = row0[i].toDouble()
                val g = row0[i + 1].toDouble()
                val b = row0[i + 2].toDouble()
                val yp = KR * r + KG * g + KB * b
                writeP010Sample(
                    output,
                    (y0 * width + x) * Short.SIZE_BYTES,
                    quantizeFinal10(
                        yp * (LUMA_MAX - LUMA_MIN) + LUMA_MIN,
                        y0 * width + x
                    )
                )
                if (y1 != y0) {
                    val r1 = row1[i].toDouble()
                    val g1 = row1[i + 1].toDouble()
                    val b1 = row1[i + 2].toDouble()
                    val yp1 = KR * r1 + KG * g1 + KB * b1
                    writeP010Sample(
                        output,
                        (y1 * width + x) * Short.SIZE_BYTES,
                        quantizeFinal10(
                            yp1 * (LUMA_MAX - LUMA_MIN) + LUMA_MIN,
                            y1 * width + x
                        )
                    )
                }
            }

            var chromaOffset = lumaBytes + cy * chromaWidth * 2 * Short.SIZE_BYTES
            for (cx in 0 until chromaWidth) {
                var cbSum = 0.0
                var crSum = 0.0
                var samples = 0
                val rowCount = if (y1 == y0) 1 else 2
                for (rowIndex in 0 until rowCount) {
                    val row = if (rowIndex == 0) row0 else row1
                    for (dx in 0..1) {
                        val x = cx * 2 + dx
                        if (x >= width) continue
                        val i = x * 3
                        val r = row[i].toDouble()
                        val g = row[i + 1].toDouble()
                        val b = row[i + 2].toDouble()
                        val yp = KR * r + KG * g + KB * b
                        cbSum += (b - yp) / (2.0 * (1.0 - KB))
                        crSum += (r - yp) / (2.0 * (1.0 - KR))
                        samples += 1
                    }
                }
                val divisor = samples.coerceAtLeast(1).toDouble()
                val chromaSample = cy * chromaWidth + cx
                val ditherIndex = width * height + chromaSample * 2
                writeP010Sample(
                    output,
                    chromaOffset,
                    quantizeFinal10(
                        (cbSum / divisor) * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO,
                        ditherIndex
                    )
                )
                writeP010Sample(
                    output,
                    chromaOffset + Short.SIZE_BYTES,
                    quantizeFinal10(
                        (crSum / divisor) * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO,
                        ditherIndex + 1
                    )
                )
                chromaOffset += 2 * Short.SIZE_BYTES
            }
        }
        return output
    }

    /**
     * Direct-buffer equivalent of [packP010420] for the full-resolution Vulkan path. The ACEScg plane
     * and reusable encoder input remain outside the Java heap; colour math and final quantization are
     * identical to the array path.
     */
    fun packP010420Direct(
        acescg: FloatBuffer,
        width: Int,
        height: Int,
        variant: NeoLogCurve.Variant,
        output: ByteBuffer,
        exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS,
        detailAlreadyApplied: Boolean = false
    ): ByteBuffer {
        val source = requireDirectAcescg(acescg, width, height)
        val target = prepareDirectP010(output, width, height)
        if (!detailAlreadyApplied) RawDevelopEliteDetailPolicy.enhanceDirectInPlace(source, width, height)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaBytes = Math.multiplyExact(Math.multiplyExact(width, height), Short.SIZE_BYTES)
        val expScale = RawExposureNormalizationPolicy.exposureScale(exposureStops)
        val row0 = FloatArray(width * 3)
        val row1 = FloatArray(width * 3)

        for (cy in 0 until chromaHeight) {
            val y0 = cy * 2
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            fillNeoLogRow(source, width, y0, expScale, variant, row0)
            if (y1 == y0) row0.copyInto(row1) else fillNeoLogRow(source, width, y1, expScale, variant, row1)
            for (x in 0 until width) {
                val i = x * 3
                val r0 = row0[i].toDouble()
                val g0 = row0[i + 1].toDouble()
                val b0 = row0[i + 2].toDouble()
                writeP010Sample(
                    target,
                    (y0 * width + x) * Short.SIZE_BYTES,
                    quantizeFinal10(
                        (KR * r0 + KG * g0 + KB * b0) * (LUMA_MAX - LUMA_MIN) + LUMA_MIN,
                        y0 * width + x
                    )
                )
                if (y1 != y0) {
                    val r1 = row1[i].toDouble()
                    val g1 = row1[i + 1].toDouble()
                    val b1 = row1[i + 2].toDouble()
                    writeP010Sample(
                        target,
                        (y1 * width + x) * Short.SIZE_BYTES,
                        quantizeFinal10(
                            (KR * r1 + KG * g1 + KB * b1) * (LUMA_MAX - LUMA_MIN) + LUMA_MIN,
                            y1 * width + x
                        )
                    )
                }
            }
            var chromaOffset = lumaBytes + cy * chromaWidth * 2 * Short.SIZE_BYTES
            for (cx in 0 until chromaWidth) {
                var cbSum = 0.0
                var crSum = 0.0
                var samples = 0
                val rowCount = if (y1 == y0) 1 else 2
                for (rowIndex in 0 until rowCount) {
                    val row = if (rowIndex == 0) row0 else row1
                    for (dx in 0..1) {
                        val x = cx * 2 + dx
                        if (x >= width) continue
                        val i = x * 3
                        val r = row[i].toDouble()
                        val g = row[i + 1].toDouble()
                        val b = row[i + 2].toDouble()
                        val yp = KR * r + KG * g + KB * b
                        cbSum += (b - yp) / (2.0 * (1.0 - KB))
                        crSum += (r - yp) / (2.0 * (1.0 - KR))
                        samples++
                    }
                }
                val divisor = samples.coerceAtLeast(1).toDouble()
                val chromaSample = cy * chromaWidth + cx
                val ditherIndex = width * height + chromaSample * 2
                writeP010Sample(
                    target,
                    chromaOffset,
                    quantizeFinal10((cbSum / divisor) * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO, ditherIndex)
                )
                writeP010Sample(
                    target,
                    chromaOffset + Short.SIZE_BYTES,
                    quantizeFinal10((crSum / divisor) * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO, ditherIndex + 1)
                )
                chromaOffset += 2 * Short.SIZE_BYTES
            }
        }
        target.position(0)
        return target
    }

    private fun fillNeoLogRow(
        acescg: FloatArray,
        width: Int,
        y: Int,
        expScale: Float,
        variant: NeoLogCurve.Variant,
        out: FloatArray
    ) {
        val sourceRow = y * width * 3
        for (component in out.indices) {
            out[component] = NeoLogCurve.encode(
                acescg[sourceRow + component] * expScale,
                variant
            )
        }
    }

    private fun fillNeoLogRow(
        acescg: FloatBuffer,
        width: Int,
        y: Int,
        expScale: Float,
        variant: NeoLogCurve.Variant,
        out: FloatArray
    ) {
        val sourceRow = y * width * 3
        for (component in out.indices) {
            out[component] = NeoLogCurve.encode(
                acescg.get(sourceRow + component) * expScale,
                variant
            )
        }
    }

    /**
     * Canonical RAW ACEScg to NDR10+/NIVUKX Vision P010 delivery. The RAW scene remains linear until
     * the shared NDR engine maps it into absolute-nit BT.2020 PQ; chroma is averaged from the unquantized
     * PQ RGB values over every real 2x2 block and quantized only once into limited-range P010.
     */
    fun packNdrP010420(
        acescg: FloatArray,
        width: Int,
        height: Int,
        mode: NdrNivukxDeliveryEngine.Mode,
        exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS,
        detailAlreadyApplied: Boolean = false
    ): ByteArray {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(acescg.size >= width * height * 3) { "acescg plane too small for ${width}x$height" }
        if (!detailAlreadyApplied) RawDevelopEliteDetailPolicy.enhanceInPlace(acescg, width, height)

        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaBytes = width * height * Short.SIZE_BYTES
        val output = ByteArray(lumaBytes + chromaWidth * chromaHeight * 2 * Short.SIZE_BYTES)
        val delivery = NdrNivukxDeliveryEngine.prepare(mode)
        val sceneScale = RawExposureNormalizationPolicy.exposureScale(exposureStops).toDouble() /
            NdrToneOperator.SENSOR_DIFFUSE_SCENE
        val row0 = DoubleArray(width * 3)
        val row1 = DoubleArray(width * 3)
        val scratch = DoubleArray(6)

        for (cy in 0 until chromaHeight) {
            val y0 = cy * 2
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            fillNdrPqRow(acescg, width, y0, sceneScale, delivery, row0, scratch)
            if (y1 == y0) {
                row0.copyInto(row1)
            } else {
                fillNdrPqRow(acescg, width, y1, sceneScale, delivery, row1, scratch)
            }

            for (x in 0 until width) {
                writeP010Luma(output, y0 * width + x, row0, x * 3)
                if (y1 != y0) writeP010Luma(output, y1 * width + x, row1, x * 3)
            }

            var chromaOffset = lumaBytes + cy * chromaWidth * 2 * Short.SIZE_BYTES
            for (cx in 0 until chromaWidth) {
                val x0 = cx * 2
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                var cbSum = 0.0
                var crSum = 0.0
                var sampleCount = 0
                val rowCount = if (y1 == y0) 1 else 2
                val columnCount = if (x1 == x0) 1 else 2
                for (rowIndex in 0 until rowCount) {
                    val row = if (rowIndex == 0) row0 else row1
                    for (columnIndex in 0 until columnCount) {
                        val x = if (columnIndex == 0) x0 else x1
                        val i = x * 3
                        val r = row[i]
                        val g = row[i + 1]
                        val b = row[i + 2]
                        val yp = KR * r + KG * g + KB * b
                        cbSum += (b - yp) / (2.0 * (1.0 - KB))
                        crSum += (r - yp) / (2.0 * (1.0 - KR))
                        sampleCount++
                    }
                }
                val divisor = sampleCount.coerceAtLeast(1).toDouble()
                writeP010Sample(
                    output,
                    chromaOffset,
                    ((cbSum / divisor) * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO).roundToInt()
                )
                writeP010Sample(
                    output,
                    chromaOffset + Short.SIZE_BYTES,
                    ((crSum / divisor) * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO).roundToInt()
                )
                chromaOffset += 2 * Short.SIZE_BYTES
            }
        }
        return output
    }

    /** Direct-buffer, bounded-heap equivalent of [packNdrP010420]. */
    fun packNdrP010420Direct(
        acescg: FloatBuffer,
        width: Int,
        height: Int,
        mode: NdrNivukxDeliveryEngine.Mode,
        output: ByteBuffer,
        exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS,
        detailAlreadyApplied: Boolean = false
    ): ByteBuffer {
        val source = requireDirectAcescg(acescg, width, height)
        val target = prepareDirectP010(output, width, height)
        if (!detailAlreadyApplied) RawDevelopEliteDetailPolicy.enhanceDirectInPlace(source, width, height)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val lumaBytes = Math.multiplyExact(Math.multiplyExact(width, height), Short.SIZE_BYTES)
        val delivery = NdrNivukxDeliveryEngine.prepare(mode)
        val sceneScale = RawExposureNormalizationPolicy.exposureScale(exposureStops).toDouble() /
            NdrToneOperator.SENSOR_DIFFUSE_SCENE
        val row0 = DoubleArray(width * 3)
        val row1 = DoubleArray(width * 3)
        val scratch = DoubleArray(6)
        for (cy in 0 until chromaHeight) {
            val y0 = cy * 2
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            fillNdrPqRow(source, width, y0, sceneScale, delivery, row0, scratch)
            if (y1 == y0) row0.copyInto(row1) else fillNdrPqRow(source, width, y1, sceneScale, delivery, row1, scratch)
            for (x in 0 until width) {
                writeP010Luma(target, y0 * width + x, row0, x * 3)
                if (y1 != y0) writeP010Luma(target, y1 * width + x, row1, x * 3)
            }
            var chromaOffset = lumaBytes + cy * chromaWidth * 2 * Short.SIZE_BYTES
            for (cx in 0 until chromaWidth) {
                val x0 = cx * 2
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                var cbSum = 0.0
                var crSum = 0.0
                var sampleCount = 0
                val rowCount = if (y1 == y0) 1 else 2
                val columnCount = if (x1 == x0) 1 else 2
                for (rowIndex in 0 until rowCount) {
                    val row = if (rowIndex == 0) row0 else row1
                    for (columnIndex in 0 until columnCount) {
                        val x = if (columnIndex == 0) x0 else x1
                        val i = x * 3
                        val r = row[i]
                        val g = row[i + 1]
                        val b = row[i + 2]
                        val yp = KR * r + KG * g + KB * b
                        cbSum += (b - yp) / (2.0 * (1.0 - KB))
                        crSum += (r - yp) / (2.0 * (1.0 - KR))
                        sampleCount++
                    }
                }
                val divisor = sampleCount.coerceAtLeast(1).toDouble()
                writeP010Sample(
                    target,
                    chromaOffset,
                    ((cbSum / divisor) * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO).roundToInt()
                )
                writeP010Sample(
                    target,
                    chromaOffset + Short.SIZE_BYTES,
                    ((crSum / divisor) * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO).roundToInt()
                )
                chromaOffset += 2 * Short.SIZE_BYTES
            }
        }
        target.position(0)
        return target
    }

    /**
     * Canonical RAW ACEScg to an uncompressed 10-bit NDR/NIVUKX delivery sample.
     *
     * Unlike the Main10 route, this path never invokes a hardware encoder. R210 preserves every
     * BT.2020 PQ RGB component at 4:4:4/full range; V210 performs exactly one BT.2020-NCL conversion
     * and one horizontal 4:2:2 chroma reduction. Both variants share the same absolute-nit delivery
     * engine as [packNdrP010420], so selecting a carrier changes only packing, never the NDR look.
     */
    fun packNdrUncompressed(
        acescg: FloatArray,
        width: Int,
        height: Int,
        mode: NdrNivukxDeliveryEngine.Mode,
        format: RawTenBitPacker.Format,
        exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS,
        detailAlreadyApplied: Boolean = false
    ): ByteArray {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        require(acescg.size >= width * height * 3) { "acescg plane too small for ${width}x$height" }
        if (!detailAlreadyApplied) RawDevelopEliteDetailPolicy.enhanceInPlace(acescg, width, height)
        val delivery = NdrNivukxDeliveryEngine.prepare(mode)
        val sceneScale = RawExposureNormalizationPolicy.exposureScale(exposureStops).toDouble() /
            NdrToneOperator.SENSOR_DIFFUSE_SCENE
        return when (format) {
            RawTenBitPacker.Format.R210_444 ->
                packNdrR210444(acescg, width, height, sceneScale, delivery)
            RawTenBitPacker.Format.V210_422 ->
                packNdrV210422(acescg, width, height, sceneScale, delivery)
        }
    }

    private fun fillNdrPqRow(
        acescg: FloatArray,
        width: Int,
        y: Int,
        sceneScale: Double,
        delivery: NdrNivukxDeliveryEngine.PreparedDelivery,
        out: DoubleArray,
        scratch: DoubleArray
    ) {
        for (x in 0 until width) {
            val i = (y * width + x) * 3
            NdrNivukxDeliveryEngine.acesCgToLinearBt2020Into(
                acescg[i].toDouble() * sceneScale,
                acescg[i + 1].toDouble() * sceneScale,
                acescg[i + 2].toDouble() * sceneScale,
                scratch,
                3
            )
            NdrNivukxDeliveryEngine.deliverPqInto(
                rLinear = scratch[3],
                gLinear = scratch[4],
                bLinear = scratch[5],
                delivery = delivery,
                out = out,
                offset = x * 3,
                scratch = scratch
            )
        }
    }

    private fun fillNdrPqRow(
        acescg: FloatBuffer,
        width: Int,
        y: Int,
        sceneScale: Double,
        delivery: NdrNivukxDeliveryEngine.PreparedDelivery,
        out: DoubleArray,
        scratch: DoubleArray
    ) {
        for (x in 0 until width) {
            val i = (y * width + x) * 3
            NdrNivukxDeliveryEngine.acesCgToLinearBt2020Into(
                acescg.get(i).toDouble() * sceneScale,
                acescg.get(i + 1).toDouble() * sceneScale,
                acescg.get(i + 2).toDouble() * sceneScale,
                scratch,
                3
            )
            NdrNivukxDeliveryEngine.deliverPqInto(
                rLinear = scratch[3],
                gLinear = scratch[4],
                bLinear = scratch[5],
                delivery = delivery,
                out = out,
                offset = x * 3,
                scratch = scratch
            )
        }
    }

    private fun writeP010Luma(output: ByteArray, pixel: Int, pqRgb: DoubleArray, offset: Int) {
        val y = KR * pqRgb[offset] + KG * pqRgb[offset + 1] + KB * pqRgb[offset + 2]
        writeP010Sample(
            output,
            pixel * Short.SIZE_BYTES,
            (y * (LUMA_MAX - LUMA_MIN) + LUMA_MIN).roundToInt()
        )
    }

    private fun writeP010Luma(output: ByteBuffer, pixel: Int, pqRgb: DoubleArray, offset: Int) {
        val y = KR * pqRgb[offset] + KG * pqRgb[offset + 1] + KB * pqRgb[offset + 2]
        writeP010Sample(
            output,
            pixel * Short.SIZE_BYTES,
            (y * (LUMA_MAX - LUMA_MIN) + LUMA_MIN).roundToInt()
        )
    }

    private fun packNdrR210444(
        acescg: FloatArray,
        width: Int,
        height: Int,
        sceneScale: Double,
        delivery: NdrNivukxDeliveryEngine.PreparedDelivery
    ): ByteArray {
        val stride = RawTenBitPacker.rowBytes(RawTenBitPacker.Format.R210_444, width)
        val output = ByteArray(stride * height)
        val pqRow = DoubleArray(width * 3)
        val scratch = DoubleArray(6)
        for (y in 0 until height) {
            fillNdrPqRow(acescg, width, y, sceneScale, delivery, pqRow, scratch)
            var offset = y * stride
            for (x in 0 until width) {
                val component = x * 3
                val ditherIndex = (y * width + x) * 3
                val r = quantizeFinal10(pqRow[component] * MAX10, ditherIndex)
                val g = quantizeFinal10(pqRow[component + 1] * MAX10, ditherIndex + 1)
                val b = quantizeFinal10(pqRow[component + 2] * MAX10, ditherIndex + 2)
                val word = (r shl 20) or (g shl 10) or b
                output[offset] = (word ushr 24).toByte()
                output[offset + 1] = (word ushr 16).toByte()
                output[offset + 2] = (word ushr 8).toByte()
                output[offset + 3] = word.toByte()
                offset += 4
            }
        }
        return output
    }

    private fun packNdrV210422(
        acescg: FloatArray,
        width: Int,
        height: Int,
        sceneScale: Double,
        delivery: NdrNivukxDeliveryEngine.PreparedDelivery
    ): ByteArray {
        val stride = RawTenBitPacker.rowBytes(RawTenBitPacker.Format.V210_422, width)
        val output = ByteArray(stride * height)
        val pqRow = DoubleArray(width * 3)
        val scratch = DoubleArray(6)
        val luma = IntArray(width)
        val cb = IntArray(width)
        val cr = IntArray(width)
        val chromaWidth = (width + 1) / 2
        for (y in 0 until height) {
            fillNdrPqRow(acescg, width, y, sceneScale, delivery, pqRow, scratch)
            val sourceRow = y * width
            for (x in 0 until width) {
                val component = x * 3
                val r = pqRow[component]
                val g = pqRow[component + 1]
                val b = pqRow[component + 2]
                val yp = KR * r + KG * g + KB * b
                luma[x] = quantizeFinal10(
                    yp * (LUMA_MAX - LUMA_MIN) + LUMA_MIN,
                    sourceRow + x
                )
            }
            for (x in 0 until width step 2) {
                var cbSum = 0.0
                var crSum = 0.0
                val sampleCount = if (x + 1 < width) 2 else 1
                for (sample in 0 until sampleCount) {
                    val component = (x + sample) * 3
                    val r = pqRow[component]
                    val g = pqRow[component + 1]
                    val b = pqRow[component + 2]
                    val yp = KR * r + KG * g + KB * b
                    cbSum += (b - yp) / (2.0 * (1.0 - KB))
                    crSum += (r - yp) / (2.0 * (1.0 - KR))
                }
                val chromaSample = y * chromaWidth + x / 2
                val ditherIndex = width * height + chromaSample * 2
                cb[x] = quantizeFinal10(
                    cbSum / sampleCount * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO,
                    ditherIndex
                )
                cr[x] = quantizeFinal10(
                    crSum / sampleCount * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO,
                    ditherIndex + 1
                )
            }
            var offset = y * stride
            var x = 0
            while (x < width) {
                val y0 = luma[x]
                val y1 = luma.getOrElse(x + 1) { y0 }
                val y2 = luma.getOrElse(x + 2) { y1 }
                val y3 = luma.getOrElse(x + 3) { y2 }
                val y4 = luma.getOrElse(x + 4) { y3 }
                val y5 = luma.getOrElse(x + 5) { y4 }
                val cb0 = cb[x]
                val cr0 = cr[x]
                val cb2 = cb.getOrElse(x + 2) { cb0 }
                val cr2 = cr.getOrElse(x + 2) { cr0 }
                val cb4 = cb.getOrElse(x + 4) { cb2 }
                val cr4 = cr.getOrElse(x + 4) { cr2 }
                writeWordLe(output, offset, (cr0 shl 20) or (y0 shl 10) or cb0); offset += 4
                writeWordLe(output, offset, (y2 shl 20) or (cb2 shl 10) or y1); offset += 4
                writeWordLe(output, offset, (cb4 shl 20) or (y3 shl 10) or cr2); offset += 4
                writeWordLe(output, offset, (y5 shl 20) or (cr4 shl 10) or y4); offset += 4
                x += 6
            }
        }
        return output
    }

    private fun packR210(
        acescg: FloatArray,
        width: Int,
        height: Int,
        variant: NeoLogCurve.Variant,
        exposureStops: Float
    ): ByteArray {
        val stride = RawTenBitPacker.rowBytes(RawTenBitPacker.Format.R210_444, width)
        val out = ByteArray(stride * height)
        val expScale = RawExposureNormalizationPolicy.exposureScale(exposureStops)
        for (y in 0 until height) {
            var o = y * stride
            val row = y * width
            for (x in 0 until width) {
                val i = (row + x) * 3
                val r = quantize10(acescg[i], expScale, variant, i)
                val g = quantize10(acescg[i + 1], expScale, variant, i + 1)
                val b = quantize10(acescg[i + 2], expScale, variant, i + 2)
                val word = (r shl 20) or (g shl 10) or b
                out[o] = (word ushr 24).toByte()
                out[o + 1] = (word ushr 16).toByte()
                out[o + 2] = (word ushr 8).toByte()
                out[o + 3] = word.toByte()
                o += 4
            }
        }
        return out
    }

    /**
     * NeoLog RGB to v210 with a continuous Float32 matrix path. Each real horizontal pixel pair is
     * averaged in continuous chroma before one deterministic final quantization; an odd edge contributes
     * exactly once, so no synthetic duplicate biases the last sample.
     */
    private fun packV210(
        acescg: FloatArray,
        width: Int,
        height: Int,
        variant: NeoLogCurve.Variant,
        exposureStops: Float
    ): ByteArray {
        val stride = RawTenBitPacker.rowBytes(RawTenBitPacker.Format.V210_422, width)
        val out = ByteArray(stride * height)
        val expScale = RawExposureNormalizationPolicy.exposureScale(exposureStops)
        val luma = IntArray(width)
        val cb = IntArray(width)
        val cr = IntArray(width)
        val chromaWidth = (width + 1) / 2
        val encodedRow = FloatArray(width * 3)
        for (y in 0 until height) {
            val row = y * width
            fillNeoLogRow(acescg, width, y, expScale, variant, encodedRow)
            for (x in 0 until width) {
                val i = x * 3
                val rf = encodedRow[i].toDouble()
                val gf = encodedRow[i + 1].toDouble()
                val bf = encodedRow[i + 2].toDouble()
                val yp = KR * rf + KG * gf + KB * bf
                luma[x] = quantizeFinal10(
                    yp * (LUMA_MAX - LUMA_MIN) + LUMA_MIN,
                    row + x
                )
            }
            for (x in 0 until width step 2) {
                var cbSum = 0.0
                var crSum = 0.0
                val sampleCount = if (x + 1 < width) 2 else 1
                for (sample in 0 until sampleCount) {
                    val i = (x + sample) * 3
                    val rf = encodedRow[i].toDouble()
                    val gf = encodedRow[i + 1].toDouble()
                    val bf = encodedRow[i + 2].toDouble()
                    val yp = KR * rf + KG * gf + KB * bf
                    val cbv = (bf - yp) / (2.0 * (1.0 - KB))
                    val crv = (rf - yp) / (2.0 * (1.0 - KR))
                    cbSum += cbv
                    crSum += crv
                }
                val chromaSample = y * chromaWidth + x / 2
                val ditherIndex = width * height + chromaSample * 2
                cb[x] = quantizeFinal10(
                    cbSum / sampleCount * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO,
                    ditherIndex
                )
                cr[x] = quantizeFinal10(
                    crSum / sampleCount * (CHROMA_MAX - LUMA_MIN) + CHROMA_ZERO,
                    ditherIndex + 1
                )
            }
            var o = y * stride
            var x = 0
            while (x < width) {
                val y0 = luma[x]
                val y1 = luma.getOrElse(x + 1) { y0 }
                val y2 = luma.getOrElse(x + 2) { y1 }
                val y3 = luma.getOrElse(x + 3) { y2 }
                val y4 = luma.getOrElse(x + 4) { y3 }
                val y5 = luma.getOrElse(x + 5) { y4 }
                val cb0 = cb[x]; val cr0 = cr[x]
                val cb2 = cb.getOrElse(x + 2) { cb0 }; val cr2 = cr.getOrElse(x + 2) { cr0 }
                val cb4 = cb.getOrElse(x + 4) { cb2 }; val cr4 = cr.getOrElse(x + 4) { cr2 }
                writeWordLe(out, o, (cr0 shl 20) or (y0 shl 10) or cb0); o += 4
                writeWordLe(out, o, (y2 shl 20) or (cb2 shl 10) or y1); o += 4
                writeWordLe(out, o, (cb4 shl 20) or (y3 shl 10) or cr2); o += 4
                writeWordLe(out, o, (y5 shl 20) or (cr4 shl 10) or y4); o += 4
                x += 6
            }
        }
        return out
    }

    private fun quantizeFinal10(code: Double, index: Int): Int =
        clamp10(
            (code + QUANTIZE_DITHER_LSB.toDouble() * tpdfUnit(index).toDouble()).roundToInt()
        )

    private fun quantize10(
        linearAcescg: Float,
        expScale: Float,
        variant: NeoLogCurve.Variant,
        index: Int
    ): Int {
        val scaled = linearAcescg * expScale
        val base = NeoLogCurve.quantize(scaled, variant, 10)
        return if (base in 1 until MAX10) {
            NeoLogCurve.quantize(scaled, variant, 10, QUANTIZE_DITHER_LSB * tpdfUnit(index))
        } else {
            base
        }
    }

    private fun writeWordLe(out: ByteArray, o: Int, word: Int) {
        out[o] = word.toByte()
        out[o + 1] = (word ushr 8).toByte()
        out[o + 2] = (word ushr 16).toByte()
        out[o + 3] = (word ushr 24).toByte()
    }

    private fun writeP010Sample(out: ByteArray, offset: Int, sample: Int) {
        val value = clamp10(sample) shl 6
        out[offset] = value.toByte()
        out[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeP010Sample(out: ByteBuffer, offset: Int, sample: Int) {
        out.putShort(offset, (clamp10(sample) shl 6).toShort())
    }

    private fun requireDirectAcescg(acescg: FloatBuffer, width: Int, height: Int): FloatBuffer {
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        val required = Math.multiplyExact(Math.multiplyExact(width, height), 3)
        require(acescg.isDirect) { "ACEScg input must use direct storage" }
        require(acescg.remaining() >= required) { "acescg direct plane too small for ${width}x$height" }
        return acescg.slice()
    }

    private fun prepareDirectP010(output: ByteBuffer, width: Int, height: Int): ByteBuffer {
        val required = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(width, height).toInt()
        require(output.isDirect) { "P010 output must use direct storage" }
        require(output.capacity() >= required) { "P010 direct output capacity ${output.capacity()} < $required" }
        return output.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            clear()
            limit(required)
        }.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun clamp10(v: Int): Int = if (v < 0) 0 else if (v > MAX10) MAX10 else v

    private fun tpdfUnit(index: Int): Float = hashUnit(index * 2) - hashUnit(index * 2 + 1)

    private fun hashUnit(n: Int): Float {
        var h = n * -0x61c88647
        h = h xor (h ushr 16)
        h *= -0x7ee3623b
        h = h xor (h ushr 13)
        h *= -0x3361d2af
        h = h xor (h ushr 16)
        return (h ushr 8).toFloat() / (1 shl 24).toFloat()
    }
}
