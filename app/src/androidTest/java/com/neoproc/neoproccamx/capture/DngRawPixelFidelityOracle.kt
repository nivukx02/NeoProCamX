package com.neoproc.neoproccamx.capture

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * Fail-closed pixel oracle for CP-143.
 *
 * The production DNG writer is Android-native, but the verification logic deliberately depends
 * only on the JDK. It canonicalizes the RAW_SENSOR plane and the selected TIFF/DNG CFA IFD into
 * the same little-endian 16-bit sample stream, so equality is independent of row padding, TIFF
 * byte order, strip/tile placement, or metadata layout.
 */
internal object DngRawPixelFidelityOracle {

    data class CanonicalRaw(
        val width: Int,
        val height: Int,
        val samples: IntArray,
        val bytes: ByteArray,
        val sha256: String,
    ) {
        val sampleCount: Int get() = samples.size
    }

    data class DngRaw(
        val width: Int,
        val height: Int,
        val samples: IntArray,
        val bytes: ByteArray,
        val sha256: String,
        val ifdOffset: Long,
        val layout: String,
        val segmentCount: Int,
        val compression: Int,
        val bitsPerSample: Int,
        val byteOrder: String,
    ) {
        val sampleCount: Int get() = samples.size
    }

    data class Comparison(
        val source: CanonicalRaw,
        val dng: DngRaw,
        val mismatchCount: Int,
        val maxAbs: Int,
    )

    class InvalidDngException(message: String, cause: Throwable? = null) :
        IllegalArgumentException(message, cause)

    /**
     * Copies every logical RAW_SENSOR sample without mutating [buffer]'s position or byte order.
     * RAW_SENSOR uses an unpacked 16-bit little-endian container even when the sensor ADC has
     * fewer significant bits. Padding bytes and row padding are intentionally excluded.
     */
    fun canonicalizeRaw16(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): CanonicalRaw {
        require(width > 0 && height > 0) { "RAW dimensions must be positive" }
        require(rowStride > 0) { "RAW rowStride must be positive" }
        require(pixelStride >= BYTES_PER_SAMPLE) {
            "RAW pixelStride=$pixelStride cannot carry a 16-bit sample"
        }

        val sampleCountLong = width.toLong() * height.toLong()
        require(sampleCountLong in 1..Int.MAX_VALUE.toLong()) {
            "RAW sample count is not representable: $sampleCountLong"
        }
        val rowPayload = (width - 1L) * pixelStride.toLong() + BYTES_PER_SAMPLE
        require(rowPayload <= rowStride.toLong()) {
            "RAW rowStride=$rowStride is smaller than row payload=$rowPayload"
        }

        val view = buffer.duplicate()
        val base = view.position().toLong()
        val finalExclusive = base +
            (height - 1L) * rowStride.toLong() +
            (width - 1L) * pixelStride.toLong() +
            BYTES_PER_SAMPLE
        require(finalExclusive <= view.limit().toLong()) {
            "RAW plane is truncated: need=$finalExclusive limit=${view.limit()} base=$base"
        }

        val sampleCount = sampleCountLong.toInt()
        val samples = IntArray(sampleCount)
        val canonical = ByteArray(Math.multiplyExact(sampleCount, BYTES_PER_SAMPLE))
        var destination = 0
        for (y in 0 until height) {
            val rowBase = base + y.toLong() * rowStride.toLong()
            for (x in 0 until width) {
                val source = rowBase + x.toLong() * pixelStride.toLong()
                val low = view.get(source.toInt()).toInt() and 0xFF
                val high = view.get((source + 1L).toInt()).toInt() and 0xFF
                val value = low or (high shl 8)
                samples[destination] = value
                canonical[destination * 2] = low.toByte()
                canonical[destination * 2 + 1] = high.toByte()
                destination++
            }
        }
        check(destination == sampleCount)
        return CanonicalRaw(width, height, samples, canonical, sha256(canonical))
    }

    fun compare(source: CanonicalRaw, dngBytes: ByteArray): Comparison {
        val dng = parseDngRaw(dngBytes, source.width, source.height)
        if (source.sampleCount != dng.sampleCount) {
            throw InvalidDngException(
                "sample-count mismatch source=${source.sampleCount} dng=${dng.sampleCount}",
            )
        }

        var mismatchCount = 0
        var maxAbs = 0
        for (index in source.samples.indices) {
            val delta = abs(source.samples[index] - dng.samples[index])
            if (delta != 0) mismatchCount++
            if (delta > maxAbs) maxAbs = delta
        }
        return Comparison(source, dng, mismatchCount, maxAbs)
    }

    fun parseDngRaw(data: ByteArray, expectedWidth: Int, expectedHeight: Int): DngRaw {
        require(expectedWidth > 0 && expectedHeight > 0) {
            "expected dimensions must be positive"
        }
        val expectedSamples = expectedWidth.toLong() * expectedHeight.toLong()
        if (expectedSamples !in 1..Int.MAX_VALUE.toLong()) {
            throw InvalidDngException("expected sample count is not representable: $expectedSamples")
        }
        if (data.size < TIFF_HEADER_BYTES) {
            throw InvalidDngException("truncated TIFF header: ${data.size} bytes")
        }

        val order = when {
            data[0] == 'I'.code.toByte() && data[1] == 'I'.code.toByte() -> ByteOrder.LITTLE
            data[0] == 'M'.code.toByte() && data[1] == 'M'.code.toByte() -> ByteOrder.BIG
            else -> throw InvalidDngException("invalid TIFF byte-order marker")
        }
        val reader = Reader(data, order)
        if (reader.u16(2L, "TIFF magic") != TIFF_CLASSIC_MAGIC) {
            throw InvalidDngException("only classic TIFF/DNG (magic 42) is supported")
        }
        val firstIfd = reader.u32(4L, "first IFD offset")
        if (firstIfd == 0L) throw InvalidDngException("missing first IFD")

        val metadataRanges = mutableListOf(Range(0L, TIFF_HEADER_BYTES.toLong(), "header"))
        val directories = collectDirectories(reader, firstIfd, metadataRanges)
        val cfaDirectories = directories.filter { directory ->
            directory.optionalScalar(TAG_PHOTOMETRIC, setOf(TYPE_SHORT), reader) == PHOTOMETRIC_CFA
        }
        if (cfaDirectories.isEmpty()) throw InvalidDngException("no CFA IFD found")

        val matching = cfaDirectories.filter { directory ->
            val width = directory.requiredIntScalar(
                TAG_IMAGE_WIDTH,
                setOf(TYPE_SHORT, TYPE_LONG),
                reader,
                "ImageWidth",
            )
            val height = directory.requiredIntScalar(
                TAG_IMAGE_LENGTH,
                setOf(TYPE_SHORT, TYPE_LONG),
                reader,
                "ImageLength",
            )
            width == expectedWidth && height == expectedHeight
        }
        if (matching.size != 1) {
            throw InvalidDngException(
                "expected exactly one ${expectedWidth}x$expectedHeight CFA IFD, found ${matching.size}",
            )
        }

        val directory = matching.single()
        val width = directory.requiredIntScalar(
            TAG_IMAGE_WIDTH,
            setOf(TYPE_SHORT, TYPE_LONG),
            reader,
            "ImageWidth",
        )
        val height = directory.requiredIntScalar(
            TAG_IMAGE_LENGTH,
            setOf(TYPE_SHORT, TYPE_LONG),
            reader,
            "ImageLength",
        )
        val bits = directory.requiredIntScalar(
            TAG_BITS_PER_SAMPLE,
            setOf(TYPE_SHORT),
            reader,
            "BitsPerSample",
        )
        if (bits != 16) throw InvalidDngException("BitsPerSample=$bits; exact RAW16 requires 16")

        val compression = directory.requiredIntScalar(
            TAG_COMPRESSION,
            setOf(TYPE_SHORT),
            reader,
            "Compression",
        )
        if (compression != COMPRESSION_NONE) {
            throw InvalidDngException("unsupported DNG compression=$compression; require uncompressed")
        }
        val photometric = directory.requiredIntScalar(
            TAG_PHOTOMETRIC,
            setOf(TYPE_SHORT),
            reader,
            "PhotometricInterpretation",
        )
        if (photometric != PHOTOMETRIC_CFA) {
            throw InvalidDngException("selected IFD is not CFA: $photometric")
        }
        val samplesPerPixel = directory.requiredIntScalar(
            TAG_SAMPLES_PER_PIXEL,
            setOf(TYPE_SHORT),
            reader,
            "SamplesPerPixel",
        )
        if (samplesPerPixel != 1) {
            throw InvalidDngException("SamplesPerPixel=$samplesPerPixel; require one CFA sample")
        }
        directory.optionalScalar(TAG_PLANAR_CONFIGURATION, setOf(TYPE_SHORT), reader)?.let { planar ->
            if (planar != PLANAR_CHUNKY) {
                throw InvalidDngException("unsupported PlanarConfiguration=$planar")
            }
        }
        directory.optionalScalar(TAG_SAMPLE_FORMAT, setOf(TYPE_SHORT), reader)?.let { format ->
            if (format != SAMPLE_FORMAT_UNSIGNED) {
                throw InvalidDngException("unsupported SampleFormat=$format")
            }
        }

        val hasAnyStrip = STRIP_TAGS.any(directory.entries::containsKey)
        val hasAllStrips = STRIP_TAGS.all(directory.entries::containsKey)
        val hasAnyTile = TILE_TAGS.any(directory.entries::containsKey)
        val hasAllTiles = TILE_TAGS.all(directory.entries::containsKey)
        if ((hasAnyStrip && !hasAllStrips) || (hasAnyTile && !hasAllTiles)) {
            throw InvalidDngException("partial strip/tile layout is not accepted")
        }
        if (hasAllStrips == hasAllTiles) {
            throw InvalidDngException("CFA IFD must contain exactly one complete strip or tile layout")
        }

        val extraction = if (hasAllStrips) {
            extractStrips(reader, directory, width, height, metadataRanges)
        } else {
            extractTiles(reader, directory, width, height, metadataRanges)
        }
        val canonical = canonicalBytes(extraction.samples)
        return DngRaw(
            width = width,
            height = height,
            samples = extraction.samples,
            bytes = canonical,
            sha256 = sha256(canonical),
            ifdOffset = directory.offset,
            layout = extraction.layout,
            segmentCount = extraction.segmentCount,
            compression = compression,
            bitsPerSample = bits,
            byteOrder = order.label,
        )
    }

    private fun collectDirectories(
        reader: Reader,
        firstIfd: Long,
        metadataRanges: MutableList<Range>,
    ): List<Directory> {
        val pending = ArrayDeque<Long>()
        val visited = mutableSetOf<Long>()
        val result = mutableListOf<Directory>()
        pending.add(firstIfd)
        while (pending.isNotEmpty()) {
            if (result.size >= MAX_IFD_COUNT) {
                throw InvalidDngException("too many IFDs (limit=$MAX_IFD_COUNT)")
            }
            val offset = pending.removeFirst()
            if (offset == 0L) continue
            if (!visited.add(offset)) {
                throw InvalidDngException("IFD cycle or duplicate reference at offset=$offset")
            }
            val directory = parseDirectory(reader, offset, metadataRanges)
            result += directory
            directory.entries[TAG_SUB_IFDS]?.let { entry ->
                val children = entry.unsignedValues(reader, setOf(TYPE_LONG, TYPE_IFD), "SubIFDs")
                if (children.isEmpty() || children.any { it == 0L }) {
                    throw InvalidDngException("SubIFDs contains an empty/null reference")
                }
                children.forEach(pending::addLast)
            }
            if (directory.nextIfd != 0L) pending.addLast(directory.nextIfd)
        }
        return result
    }

    private fun parseDirectory(
        reader: Reader,
        offset: Long,
        metadataRanges: MutableList<Range>,
    ): Directory {
        val entryCount = reader.u16(offset, "IFD entry count")
        if (entryCount > MAX_IFD_ENTRIES) {
            throw InvalidDngException("IFD at $offset has too many entries: $entryCount")
        }
        val directoryBytes = 2L + entryCount.toLong() * TIFF_ENTRY_BYTES + 4L
        reader.requireRange(offset, directoryBytes, "IFD at $offset")
        metadataRanges += Range(offset, offset + directoryBytes, "IFD@$offset")

        val entries = linkedMapOf<Int, Entry>()
        repeat(entryCount) { index ->
            val entryOffset = offset + 2L + index.toLong() * TIFF_ENTRY_BYTES
            val tag = reader.u16(entryOffset, "IFD tag")
            if (entries.containsKey(tag)) {
                throw InvalidDngException("duplicate TIFF tag=$tag in IFD@$offset")
            }
            val type = reader.u16(entryOffset + 2L, "tag $tag type")
            val typeBytes = TYPE_BYTES[type]
                ?: throw InvalidDngException("tag=$tag uses unsupported TIFF type=$type")
            val count = reader.u32(entryOffset + 4L, "tag $tag count")
            if (count == 0L) throw InvalidDngException("tag=$tag has zero values")
            val payloadBytes = checkedMultiply(count, typeBytes.toLong(), "tag $tag payload")
            val payloadOffset = if (payloadBytes <= TIFF_INLINE_BYTES) {
                entryOffset + 8L
            } else {
                val external = reader.u32(entryOffset + 8L, "tag $tag value offset")
                if (external == 0L) throw InvalidDngException("tag=$tag has null value offset")
                reader.requireRange(external, payloadBytes, "tag $tag payload")
                metadataRanges += Range(external, external + payloadBytes, "tag=$tag payload")
                external
            }
            entries[tag] = Entry(tag, type, count, payloadOffset, payloadBytes)
        }
        val nextIfdOffset = offset + 2L + entryCount.toLong() * TIFF_ENTRY_BYTES
        return Directory(offset, entries, reader.u32(nextIfdOffset, "next IFD offset"))
    }

    private fun extractStrips(
        reader: Reader,
        directory: Directory,
        width: Int,
        height: Int,
        metadataRanges: List<Range>,
    ): Extraction {
        val rowsPerStrip = directory.requiredIntScalar(
            TAG_ROWS_PER_STRIP,
            setOf(TYPE_SHORT, TYPE_LONG),
            reader,
            "RowsPerStrip",
        )
        if (rowsPerStrip <= 0) throw InvalidDngException("RowsPerStrip must be positive")
        val expectedStrips = ceilDiv(height, rowsPerStrip)
        val offsets = directory.requiredValues(
            TAG_STRIP_OFFSETS,
            setOf(TYPE_SHORT, TYPE_LONG),
            reader,
            "StripOffsets",
        )
        val byteCounts = directory.requiredValues(
            TAG_STRIP_BYTE_COUNTS,
            setOf(TYPE_SHORT, TYPE_LONG),
            reader,
            "StripByteCounts",
        )
        if (offsets.size != expectedStrips || byteCounts.size != expectedStrips) {
            throw InvalidDngException(
                "strip-count mismatch expected=$expectedStrips offsets=${offsets.size} counts=${byteCounts.size}",
            )
        }

        val ranges = mutableListOf<Range>()
        for (strip in 0 until expectedStrips) {
            val visibleRows = minOf(rowsPerStrip, height - strip * rowsPerStrip)
            val expectedBytes = checkedMultiply(
                width.toLong() * visibleRows.toLong(),
                BYTES_PER_SAMPLE.toLong(),
                "strip $strip byte count",
            )
            val actualBytes = byteCounts[strip]
            if (actualBytes != expectedBytes) {
                throw InvalidDngException(
                    "strip $strip byte count=$actualBytes expected=$expectedBytes",
                )
            }
            ranges += checkedImageRange(
                reader,
                offsets[strip],
                actualBytes,
                "strip[$strip]",
                metadataRanges,
            )
        }
        rejectOverlaps(ranges)

        val samples = IntArray(Math.multiplyExact(width, height))
        val assigned = BooleanArray(samples.size)
        for (strip in 0 until expectedStrips) {
            val firstRow = strip * rowsPerStrip
            val visibleRows = minOf(rowsPerStrip, height - firstRow)
            val start = offsets[strip]
            for (row in 0 until visibleRows) {
                val rowStart = start + row.toLong() * width.toLong() * BYTES_PER_SAMPLE
                for (x in 0 until width) {
                    assignSample(
                        samples,
                        assigned,
                        (firstRow + row) * width + x,
                        reader.u16(rowStart + x.toLong() * BYTES_PER_SAMPLE, "strip sample"),
                    )
                }
            }
        }
        requireCompleteCoverage(assigned)
        return Extraction(samples, "STRIPS", expectedStrips)
    }

    private fun extractTiles(
        reader: Reader,
        directory: Directory,
        width: Int,
        height: Int,
        metadataRanges: List<Range>,
    ): Extraction {
        val tileWidth = directory.requiredIntScalar(
            TAG_TILE_WIDTH,
            setOf(TYPE_SHORT, TYPE_LONG),
            reader,
            "TileWidth",
        )
        val tileLength = directory.requiredIntScalar(
            TAG_TILE_LENGTH,
            setOf(TYPE_SHORT, TYPE_LONG),
            reader,
            "TileLength",
        )
        if (tileWidth <= 0 || tileLength <= 0) {
            throw InvalidDngException("tile dimensions must be positive")
        }
        val tilesAcross = ceilDiv(width, tileWidth)
        val tilesDown = ceilDiv(height, tileLength)
        val expectedTilesLong = tilesAcross.toLong() * tilesDown.toLong()
        if (expectedTilesLong !in 1..Int.MAX_VALUE.toLong()) {
            throw InvalidDngException("tile count is not representable: $expectedTilesLong")
        }
        val expectedTiles = expectedTilesLong.toInt()
        val offsets = directory.requiredValues(
            TAG_TILE_OFFSETS,
            setOf(TYPE_LONG),
            reader,
            "TileOffsets",
        )
        val byteCounts = directory.requiredValues(
            TAG_TILE_BYTE_COUNTS,
            setOf(TYPE_SHORT, TYPE_LONG),
            reader,
            "TileByteCounts",
        )
        if (offsets.size != expectedTiles || byteCounts.size != expectedTiles) {
            throw InvalidDngException(
                "tile-count mismatch expected=$expectedTiles offsets=${offsets.size} counts=${byteCounts.size}",
            )
        }
        val nominalTileBytes = checkedMultiply(
            tileWidth.toLong() * tileLength.toLong(),
            BYTES_PER_SAMPLE.toLong(),
            "nominal tile bytes",
        )
        val ranges = mutableListOf<Range>()
        for (tile in 0 until expectedTiles) {
            if (byteCounts[tile] != nominalTileBytes) {
                throw InvalidDngException(
                    "tile $tile byte count=${byteCounts[tile]} expected=$nominalTileBytes",
                )
            }
            ranges += checkedImageRange(
                reader,
                offsets[tile],
                byteCounts[tile],
                "tile[$tile]",
                metadataRanges,
            )
        }
        rejectOverlaps(ranges)

        val samples = IntArray(Math.multiplyExact(width, height))
        val assigned = BooleanArray(samples.size)
        for (tile in 0 until expectedTiles) {
            val tileX = tile % tilesAcross
            val tileY = tile / tilesAcross
            val visibleWidth = minOf(tileWidth, width - tileX * tileWidth)
            val visibleHeight = minOf(tileLength, height - tileY * tileLength)
            val start = offsets[tile]
            for (row in 0 until visibleHeight) {
                val rowStart = start + row.toLong() * tileWidth.toLong() * BYTES_PER_SAMPLE
                for (column in 0 until visibleWidth) {
                    val destination =
                        (tileY * tileLength + row) * width + tileX * tileWidth + column
                    assignSample(
                        samples,
                        assigned,
                        destination,
                        reader.u16(
                            rowStart + column.toLong() * BYTES_PER_SAMPLE,
                            "tile sample",
                        ),
                    )
                }
            }
        }
        requireCompleteCoverage(assigned)
        return Extraction(samples, "TILES", expectedTiles)
    }

    private fun checkedImageRange(
        reader: Reader,
        offset: Long,
        byteCount: Long,
        label: String,
        metadataRanges: List<Range>,
    ): Range {
        if (offset == 0L || byteCount <= 0L) {
            throw InvalidDngException("$label has null offset or non-positive byte count")
        }
        reader.requireRange(offset, byteCount, label)
        val range = Range(offset, offset + byteCount, label)
        metadataRanges.firstOrNull(range::overlaps)?.let { metadata ->
            throw InvalidDngException("$label overlaps ${metadata.label}")
        }
        return range
    }

    private fun rejectOverlaps(ranges: List<Range>) {
        val sorted = ranges.sortedBy(Range::start)
        for (index in 1 until sorted.size) {
            if (sorted[index].start < sorted[index - 1].endExclusive) {
                throw InvalidDngException(
                    "${sorted[index - 1].label} overlaps ${sorted[index].label}",
                )
            }
        }
    }

    private fun assignSample(
        samples: IntArray,
        assigned: BooleanArray,
        index: Int,
        value: Int,
    ) {
        if (index !in samples.indices) {
            throw InvalidDngException("sample destination out of bounds: $index")
        }
        if (assigned[index]) throw InvalidDngException("sample destination assigned twice: $index")
        assigned[index] = true
        samples[index] = value
    }

    private fun requireCompleteCoverage(assigned: BooleanArray) {
        val missing = assigned.indexOfFirst { !it }
        if (missing >= 0) throw InvalidDngException("sample destination not populated: $missing")
    }

    private fun canonicalBytes(samples: IntArray): ByteArray {
        val bytes = ByteArray(Math.multiplyExact(samples.size, BYTES_PER_SAMPLE))
        samples.forEachIndexed { index, value ->
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    private fun checkedMultiply(left: Long, right: Long, label: String): Long = try {
        Math.multiplyExact(left, right)
    } catch (overflow: ArithmeticException) {
        throw InvalidDngException("$label overflows", overflow)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int =
        ((value.toLong() + divisor.toLong() - 1L) / divisor.toLong()).toInt()

    private data class Extraction(
        val samples: IntArray,
        val layout: String,
        val segmentCount: Int,
    )

    private data class Range(
        val start: Long,
        val endExclusive: Long,
        val label: String,
    ) {
        fun overlaps(other: Range): Boolean =
            start < other.endExclusive && other.start < endExclusive
    }

    private data class Directory(
        val offset: Long,
        val entries: Map<Int, Entry>,
        val nextIfd: Long,
    ) {
        fun requiredValues(
            tag: Int,
            allowedTypes: Set<Int>,
            reader: Reader,
            label: String,
        ): LongArray = entries[tag]?.unsignedValues(reader, allowedTypes, label)
            ?: throw InvalidDngException("missing $label tag=$tag in IFD@$offset")

        fun requiredIntScalar(
            tag: Int,
            allowedTypes: Set<Int>,
            reader: Reader,
            label: String,
        ): Int {
            val values = requiredValues(tag, allowedTypes, reader, label)
            if (values.size != 1) throw InvalidDngException("$label must have exactly one value")
            if (values[0] !in 0..Int.MAX_VALUE.toLong()) {
                throw InvalidDngException("$label is not representable: ${values[0]}")
            }
            return values[0].toInt()
        }

        fun optionalScalar(
            tag: Int,
            allowedTypes: Set<Int>,
            reader: Reader,
        ): Int? {
            val entry = entries[tag] ?: return null
            val values = entry.unsignedValues(reader, allowedTypes, "tag $tag")
            if (values.size != 1 || values[0] > Int.MAX_VALUE.toLong()) {
                throw InvalidDngException("tag=$tag must contain one representable scalar")
            }
            return values[0].toInt()
        }
    }

    private data class Entry(
        val tag: Int,
        val type: Int,
        val count: Long,
        val payloadOffset: Long,
        val payloadBytes: Long,
    ) {
        fun unsignedValues(reader: Reader, allowedTypes: Set<Int>, label: String): LongArray {
            if (type !in allowedTypes) {
                throw InvalidDngException("$label tag=$tag has type=$type; allowed=$allowedTypes")
            }
            if (count > MAX_DECODED_VALUES || count > Int.MAX_VALUE.toLong()) {
                throw InvalidDngException("$label has excessive value count=$count")
            }
            val expectedBytes = checkedMultiply(
                count,
                TYPE_BYTES.getValue(type).toLong(),
                "$label payload",
            )
            if (expectedBytes != payloadBytes) {
                throw InvalidDngException("$label payload-size inconsistency")
            }
            return LongArray(count.toInt()) { index ->
                val elementOffset = payloadOffset + index.toLong() * TYPE_BYTES.getValue(type)
                when (type) {
                    TYPE_BYTE -> reader.u8(elementOffset, label).toLong()
                    TYPE_SHORT -> reader.u16(elementOffset, label).toLong()
                    TYPE_LONG, TYPE_IFD -> reader.u32(elementOffset, label)
                    else -> throw InvalidDngException("$label is not an unsigned integer type")
                }
            }
        }
    }

    private enum class ByteOrder(val label: String) {
        LITTLE("II"),
        BIG("MM"),
    }

    private class Reader(private val data: ByteArray, private val order: ByteOrder) {
        fun requireRange(offset: Long, length: Long, label: String) {
            if (offset < 0L || length < 0L || offset > data.size.toLong()) {
                throw InvalidDngException("$label has invalid range offset=$offset length=$length")
            }
            val end = try {
                Math.addExact(offset, length)
            } catch (overflow: ArithmeticException) {
                throw InvalidDngException("$label range overflows", overflow)
            }
            if (end > data.size.toLong()) {
                throw InvalidDngException(
                    "$label is out of bounds: offset=$offset length=$length size=${data.size}",
                )
            }
        }

        fun u8(offset: Long, label: String): Int {
            requireRange(offset, 1L, label)
            return data[offset.toInt()].toInt() and 0xFF
        }

        fun u16(offset: Long, label: String): Int {
            requireRange(offset, 2L, label)
            val a = data[offset.toInt()].toInt() and 0xFF
            val b = data[(offset + 1L).toInt()].toInt() and 0xFF
            return if (order == ByteOrder.LITTLE) a or (b shl 8) else (a shl 8) or b
        }

        fun u32(offset: Long, label: String): Long {
            requireRange(offset, 4L, label)
            val a = data[offset.toInt()].toLong() and 0xFFL
            val b = data[(offset + 1L).toInt()].toLong() and 0xFFL
            val c = data[(offset + 2L).toInt()].toLong() and 0xFFL
            val d = data[(offset + 3L).toInt()].toLong() and 0xFFL
            return if (order == ByteOrder.LITTLE) {
                a or (b shl 8) or (c shl 16) or (d shl 24)
            } else {
                (a shl 24) or (b shl 16) or (c shl 8) or d
            }
        }
    }

    private const val BYTES_PER_SAMPLE = 2
    private const val TIFF_HEADER_BYTES = 8
    private const val TIFF_CLASSIC_MAGIC = 42
    private const val TIFF_ENTRY_BYTES = 12L
    private const val TIFF_INLINE_BYTES = 4L
    private const val MAX_IFD_COUNT = 128
    private const val MAX_IFD_ENTRIES = 4_096
    private const val MAX_DECODED_VALUES = 1_000_000L

    private const val TYPE_BYTE = 1
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_IFD = 13
    private val TYPE_BYTES = mapOf(
        1 to 1,
        2 to 1,
        3 to 2,
        4 to 4,
        5 to 8,
        6 to 1,
        7 to 1,
        8 to 2,
        9 to 4,
        10 to 8,
        11 to 4,
        12 to 8,
        13 to 4,
    )

    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_PLANAR_CONFIGURATION = 284
    private const val TAG_TILE_WIDTH = 322
    private const val TAG_TILE_LENGTH = 323
    private const val TAG_TILE_OFFSETS = 324
    private const val TAG_TILE_BYTE_COUNTS = 325
    private const val TAG_SUB_IFDS = 330
    private const val TAG_SAMPLE_FORMAT = 339

    private const val COMPRESSION_NONE = 1
    private const val PHOTOMETRIC_CFA = 32_803
    private const val PLANAR_CHUNKY = 1
    private const val SAMPLE_FORMAT_UNSIGNED = 1

    private val STRIP_TAGS = setOf(TAG_STRIP_OFFSETS, TAG_ROWS_PER_STRIP, TAG_STRIP_BYTE_COUNTS)
    private val TILE_TAGS = setOf(TAG_TILE_WIDTH, TAG_TILE_LENGTH, TAG_TILE_OFFSETS, TAG_TILE_BYTE_COUNTS)
}
