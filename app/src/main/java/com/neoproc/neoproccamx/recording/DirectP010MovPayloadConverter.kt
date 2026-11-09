package com.neoproc.neoproccamx.recording

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Converts tightly packed P010 into an uncompressed MOV sample using caller-owned buffers.
 *
 * The export path reuses one output buffer for the entire clip, so this implementation overwrites
 * every output byte (including v210 row padding) and never creates a frame-sized heap mirror.
 */
internal object DirectP010MovPayloadConverter {
    fun requiredOutputBytes(
        width: Int,
        height: Int,
        outputFormat: Npv10MovConverter.OutputFormat
    ): Int {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val rowBytes = when (outputFormat) {
            Npv10MovConverter.OutputFormat.R210_444 ->
                RawTenBitPacker.rowBytes(RawTenBitPacker.Format.R210_444, safeWidth)

            Npv10MovConverter.OutputFormat.V210_422 -> v210RowBytes(safeWidth)
            Npv10MovConverter.OutputFormat.HEVC_MAIN10_420 ->
                error("HEVC Main10 consumes the developed P010 buffer directly")
        }
        val totalBytes = rowBytes.toLong() * safeHeight.toLong()
        require(totalBytes in 1..Int.MAX_VALUE.toLong()) {
            "MOV sample is too large: ${safeWidth}x$safeHeight $outputFormat"
        }
        return totalBytes.toInt()
    }

    fun convert(
        p010Payload: ByteBuffer,
        width: Int,
        height: Int,
        outputFormat: Npv10MovConverter.OutputFormat,
        colorConfig: QuickTimeColorAtomConfig,
        target: ByteBuffer
    ): ByteBuffer {
        val source = p010Payload.slice().order(ByteOrder.LITTLE_ENDIAN)
        validateP010PayloadLayout(source, width, height)
        val requiredBytes = requiredOutputBytes(width, height, outputFormat)
        require(target.capacity() >= requiredBytes) {
            "MOV output buffer is too small capacity=${target.capacity()} required=$requiredBytes"
        }
        val output = target.duplicate().apply {
            clear()
            limit(requiredBytes)
        }
        when (outputFormat) {
            Npv10MovConverter.OutputFormat.R210_444 ->
                convertR210(source, width, height, colorConfig, output.order(ByteOrder.BIG_ENDIAN))

            Npv10MovConverter.OutputFormat.V210_422 ->
                convertV210(source, width, height, output.order(ByteOrder.LITTLE_ENDIAN))

            Npv10MovConverter.OutputFormat.HEVC_MAIN10_420 ->
                error("HEVC Main10 consumes the developed P010 buffer directly")
        }
        output.flip()
        return output
    }

    fun validateP010PayloadLayout(payload: ByteBuffer, width: Int, height: Int) {
        val expectedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            width,
            height,
            Npv10PayloadFormat.P010_LE
        ).toInt()
        require(payload.remaining() == expectedBytes) {
            "P010 payload size ${payload.remaining()} does not match expected $expectedBytes"
        }
        require(payload.remaining() % Short.SIZE_BYTES == 0) {
            "P010 payload layout must contain complete 16-bit samples"
        }
        val source = payload.slice().order(ByteOrder.LITTLE_ENDIAN)
        // Validate four 16-bit samples per load. The old scalar loop performed one bounds check,
        // one branch and one getShort for every sample before the conversion read the frame again.
        // This preserves strict low-bit validation while substantially reducing the validation-side
        // instruction count and branch pressure for UHD frames.
        var offset = 0
        var invalidPaddingBits = 0L
        val vectorEnd = source.remaining() - source.remaining() % Long.SIZE_BYTES
        while (offset < vectorEnd) {
            invalidPaddingBits = invalidPaddingBits or
                (source.getLong(offset) and P010_LOW_BITS_VECTOR_MASK)
            offset += Long.SIZE_BYTES
        }
        while (offset < source.remaining()) {
            invalidPaddingBits = invalidPaddingBits or
                (source.getShort(offset).toLong() and P010_LOW_BITS_MASK.toLong())
            offset += Short.SIZE_BYTES
        }
        require(invalidPaddingBits == 0L) {
            "P010 payload layout mismatch: low padding bits are not zero"
        }
    }

    private fun convertR210(
        source: ByteBuffer,
        width: Int,
        height: Int,
        colorConfig: QuickTimeColorAtomConfig,
        output: ByteBuffer
    ) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val rowBytes = RawTenBitPacker.rowBytes(RawTenBitPacker.Format.R210_444, safeWidth)
        val bt2020 = colorConfig.matrixCoefficients == QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL
        val kr = if (bt2020) 0.2627 else 0.2126
        val kb = if (bt2020) 0.0593 else 0.0722
        val kg = 1.0 - kr - kb
        val redCr = 2.0 * (1.0 - kr)
        val blueCb = 2.0 * (1.0 - kb)
        val greenCb = 2.0 * kb * (1.0 - kb) / kg
        val greenCr = 2.0 * kr * (1.0 - kr) / kg

        for (row in 0 until safeHeight) {
            val rowEnd = (row + 1) * rowBytes
            for (column in 0 until safeWidth) {
                val yCode = readP010Luma(source, safeWidth, safeHeight, row, column)
                val cbCode = readP010Chroma(source, safeWidth, safeHeight, row, column, 0)
                val crCode = readP010Chroma(source, safeWidth, safeHeight, row, column, 1)
                val y = if (colorConfig.fullRange) {
                    yCode / 1023.0
                } else {
                    ((yCode - 64) / 876.0).coerceIn(0.0, 1.0)
                }
                val cb = if (colorConfig.fullRange) {
                    (cbCode - 512) / 1023.0
                } else {
                    (cbCode - 512) / 896.0
                }
                val cr = if (colorConfig.fullRange) {
                    (crCode - 512) / 1023.0
                } else {
                    (crCode - 512) / 896.0
                }
                val red = ((y + redCr * cr).coerceIn(0.0, 1.0) * 1023.0).roundToInt()
                val green = ((y - greenCb * cb - greenCr * cr).coerceIn(0.0, 1.0) * 1023.0)
                    .roundToInt()
                val blue = ((y + blueCb * cb).coerceIn(0.0, 1.0) * 1023.0).roundToInt()
                output.putInt((red shl 20) or (green shl 10) or blue)
            }
            while (output.position() < rowEnd) output.put(0.toByte())
        }
    }

    private fun convertV210(
        source: ByteBuffer,
        width: Int,
        height: Int,
        output: ByteBuffer
    ) {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val rowBytes = v210RowBytes(safeWidth)
        val roundedWidth = ((safeWidth + V210_PIXELS_PER_GROUP - 1) / V210_PIXELS_PER_GROUP) *
            V210_PIXELS_PER_GROUP
        for (row in 0 until safeHeight) {
            val rowEnd = (row + 1) * rowBytes
            var column = 0
            while (column < roundedWidth) {
                val y0 = readP010Luma(source, safeWidth, safeHeight, row, column)
                val y1 = readP010Luma(source, safeWidth, safeHeight, row, column + 1)
                val y2 = readP010Luma(source, safeWidth, safeHeight, row, column + 2)
                val y3 = readP010Luma(source, safeWidth, safeHeight, row, column + 3)
                val y4 = readP010Luma(source, safeWidth, safeHeight, row, column + 4)
                val y5 = readP010Luma(source, safeWidth, safeHeight, row, column + 5)
                val u0 = readP010Chroma(source, safeWidth, safeHeight, row, column, 0)
                val v0 = readP010Chroma(source, safeWidth, safeHeight, row, column, 1)
                val u1 = readP010Chroma(source, safeWidth, safeHeight, row, column + 2, 0)
                val v1 = readP010Chroma(source, safeWidth, safeHeight, row, column + 2, 1)
                val u2 = readP010Chroma(source, safeWidth, safeHeight, row, column + 4, 0)
                val v2 = readP010Chroma(source, safeWidth, safeHeight, row, column + 4, 1)
                output.putInt(v210Word(u0, y0, v0))
                output.putInt(v210Word(y1, u1, y2))
                output.putInt(v210Word(v1, y3, u2))
                output.putInt(v210Word(y4, v2, y5))
                column += V210_PIXELS_PER_GROUP
            }
            while (output.position() < rowEnd) output.put(0.toByte())
        }
    }

    private fun readP010Luma(
        source: ByteBuffer,
        width: Int,
        height: Int,
        row: Int,
        column: Int
    ): Int {
        val y = row.coerceIn(0, height - 1)
        val x = column.coerceIn(0, width - 1)
        return readP010(source, (y * width + x) * Short.SIZE_BYTES)
    }

    private fun readP010Chroma(
        source: ByteBuffer,
        width: Int,
        height: Int,
        row: Int,
        column: Int,
        component: Int
    ): Int {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val chromaRow = (row / 2).coerceIn(0, chromaHeight - 1)
        val chromaColumn = (column / 2).coerceIn(0, chromaWidth - 1)
        val offset = width * height * Short.SIZE_BYTES +
            (chromaRow * chromaWidth + chromaColumn) * Short.SIZE_BYTES * 2 +
            component.coerceIn(0, 1) * Short.SIZE_BYTES
        return readP010(source, offset)
    }

    private fun readP010(source: ByteBuffer, offset: Int): Int =
        ((source.getShort(offset).toInt() and 0xFFFF) ushr P010_VALID_BITS_SHIFT)
            .coerceIn(0, V210_SAMPLE_MASK)

    private fun v210Word(first: Int, second: Int, third: Int): Int =
        (first and V210_SAMPLE_MASK) or
            ((second and V210_SAMPLE_MASK) shl 10) or
            ((third and V210_SAMPLE_MASK) shl 20)

    private fun v210RowBytes(width: Int): Int =
        ((width.coerceAtLeast(1) + V210_ROW_ALIGNMENT_PIXELS - 1) / V210_ROW_ALIGNMENT_PIXELS) *
            V210_ROW_ALIGNMENT_BYTES

    private const val P010_VALID_BITS_SHIFT = 6
    private const val P010_LOW_BITS_MASK = (1 shl P010_VALID_BITS_SHIFT) - 1
    private const val P010_LOW_BITS_VECTOR_MASK = 0x003F003F003F003FL
    private const val V210_SAMPLE_MASK = 0x3FF
    private const val V210_PIXELS_PER_GROUP = 6
    private const val V210_ROW_ALIGNMENT_PIXELS = 48
    private const val V210_ROW_ALIGNMENT_BYTES = 128
}
