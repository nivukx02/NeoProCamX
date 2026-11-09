package com.neoproc.neoproccamx.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer

/** Deterministic parser/canonicalization fixtures that accompany the physical CP-143 capture. */
@RunWith(AndroidJUnit4::class)
class DngRawPixelFidelityOracleFixtureInstrumentedTest {

    @Test
    fun rawPlaneCanonicalization_respectsPositionRowStrideAndPixelStride() {
        val storage = ByteArray(24) { 0x5A.toByte() }
        val buffer = ByteBuffer.wrap(storage)
        buffer.position(3)
        putLittleEndian(storage, 3, 0x0123)
        putLittleEndian(storage, 6, 0x4567)
        putLittleEndian(storage, 11, 0x89AB)
        putLittleEndian(storage, 14, 0xCDEF)

        val canonical = DngRawPixelFidelityOracle.canonicalizeRaw16(
            buffer = buffer,
            width = 2,
            height = 2,
            rowStride = 8,
            pixelStride = 3,
        )

        assertEquals(listOf(0x0123, 0x4567, 0x89AB, 0xCDEF), canonical.samples.toList())
        assertEquals(3, buffer.position())
    }

    @Test
    fun littleEndianSubIfdStrips_reconstructsEverySample() {
        val samples = intArrayOf(
            0x0011, 0x2233, 0x4455,
            0x6677, 0x8899, 0xAABB,
            0xCCDD, 0xEEFF, 0x1357,
        )
        val fixture = ClassicDngFixture.strips(
            order = FixtureByteOrder.LITTLE,
            width = 3,
            height = 3,
            rowsPerStrip = 2,
            samples = samples,
            link = DirectoryLink.SUB_IFD,
        )

        val parsed = DngRawPixelFidelityOracle.parseDngRaw(fixture, 3, 3)

        assertEquals("STRIPS", parsed.layout)
        assertEquals("II", parsed.byteOrder)
        assertEquals(2, parsed.segmentCount)
        assertEquals(samples.toList(), parsed.samples.toList())
    }

    @Test
    fun bigEndianNextIfdTiles_reconstructsEdgesWithoutPaddingSamples() {
        val samples = intArrayOf(
            0x0102, 0x0304, 0x0506,
            0x0708, 0x090A, 0x0B0C,
            0x0D0E, 0x0F10, 0x1112,
        )
        val fixture = ClassicDngFixture.tiles(
            order = FixtureByteOrder.BIG,
            width = 3,
            height = 3,
            tileWidth = 2,
            tileHeight = 2,
            samples = samples,
            link = DirectoryLink.NEXT_IFD,
        )

        val parsed = DngRawPixelFidelityOracle.parseDngRaw(fixture, 3, 3)

        assertEquals("TILES", parsed.layout)
        assertEquals("MM", parsed.byteOrder)
        assertEquals(4, parsed.segmentCount)
        assertEquals(samples.toList(), parsed.samples.toList())
    }

    @Test
    fun unsupportedCompression_isRejectedFailClosed() {
        val fixture = ClassicDngFixture.strips(
            order = FixtureByteOrder.LITTLE,
            width = 2,
            height = 2,
            rowsPerStrip = 2,
            samples = intArrayOf(1, 2, 3, 4),
            compression = 7,
        )

        assertInvalidDng("compression") {
            DngRawPixelFidelityOracle.parseDngRaw(fixture, 2, 2)
        }
    }

    @Test
    fun overlappingStrips_areRejectedFailClosed() {
        val fixture = ClassicDngFixture.strips(
            order = FixtureByteOrder.LITTLE,
            width = 2,
            height = 4,
            rowsPerStrip = 2,
            samples = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            corruptOffsets = CorruptOffsets.OVERLAP,
        )

        assertInvalidDng("overlap") {
            DngRawPixelFidelityOracle.parseDngRaw(fixture, 2, 4)
        }
    }

    @Test
    fun outOfBoundsStrip_isRejectedFailClosed() {
        val fixture = ClassicDngFixture.strips(
            order = FixtureByteOrder.BIG,
            width = 2,
            height = 4,
            rowsPerStrip = 2,
            samples = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            corruptOffsets = CorruptOffsets.OUT_OF_BOUNDS,
        )

        assertInvalidDng("out of bounds", "invalid range") {
            DngRawPixelFidelityOracle.parseDngRaw(fixture, 2, 4)
        }
    }

    private fun assertInvalidDng(vararg messageFragments: String, block: () -> Unit) {
        var failure: DngRawPixelFidelityOracle.InvalidDngException? = null
        try {
            block()
        } catch (caught: DngRawPixelFidelityOracle.InvalidDngException) {
            failure = caught
        }
        assertTrue("expected InvalidDngException", failure != null)
        assertTrue(
            "expected message containing one of ${messageFragments.contentToString()}, " +
                "actual='${failure?.message}'",
            messageFragments.any { fragment ->
                failure?.message?.contains(fragment, ignoreCase = true) == true
            },
        )
    }

    private fun putLittleEndian(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}

private enum class FixtureByteOrder {
    LITTLE,
    BIG,
}

private enum class DirectoryLink {
    DIRECT,
    SUB_IFD,
    NEXT_IFD,
}

private enum class CorruptOffsets {
    NONE,
    OVERLAP,
    OUT_OF_BOUNDS,
}

private object ClassicDngFixture {
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_IFD = 13
    private const val CFA = 32_803L
    private const val PAD_SAMPLE = 0xDEAD

    fun strips(
        order: FixtureByteOrder,
        width: Int,
        height: Int,
        rowsPerStrip: Int,
        samples: IntArray,
        link: DirectoryLink = DirectoryLink.DIRECT,
        compression: Int = 1,
        corruptOffsets: CorruptOffsets = CorruptOffsets.NONE,
    ): ByteArray {
        require(samples.size == width * height)
        val segmentRows = generateSequence(0) { previous -> previous + rowsPerStrip }
            .takeWhile { it < height }
            .map { firstRow -> minOf(rowsPerStrip, height - firstRow) }
            .toList()
        val segments = segmentRows.mapIndexed { index, rows ->
            val firstRow = index * rowsPerStrip
            IntArray(width * rows) { position ->
                samples[firstRow * width + position]
            }
        }
        val entries = commonEntries(width, height, compression).toMutableList().apply {
            add(EntryDefinition(273, TYPE_LONG, LongArray(segments.size)))
            add(EntryDefinition(278, TYPE_LONG, longArrayOf(rowsPerStrip.toLong())))
            add(
                EntryDefinition(
                    279,
                    TYPE_LONG,
                    segments.map { segment -> segment.size.toLong() * 2L }.toLongArray(),
                ),
            )
        }
        return build(
            order = order,
            entries = entries,
            segments = segments,
            link = link,
            corruptOffsets = corruptOffsets,
        )
    }

    fun tiles(
        order: FixtureByteOrder,
        width: Int,
        height: Int,
        tileWidth: Int,
        tileHeight: Int,
        samples: IntArray,
        link: DirectoryLink = DirectoryLink.DIRECT,
    ): ByteArray {
        require(samples.size == width * height)
        val tilesAcross = (width + tileWidth - 1) / tileWidth
        val tilesDown = (height + tileHeight - 1) / tileHeight
        val segments = mutableListOf<IntArray>()
        for (tileY in 0 until tilesDown) {
            for (tileX in 0 until tilesAcross) {
                segments += IntArray(tileWidth * tileHeight) { position ->
                    val localX = position % tileWidth
                    val localY = position / tileWidth
                    val x = tileX * tileWidth + localX
                    val y = tileY * tileHeight + localY
                    if (x < width && y < height) samples[y * width + x] else PAD_SAMPLE
                }
            }
        }
        val entries = commonEntries(width, height, compression = 1).toMutableList().apply {
            add(EntryDefinition(322, TYPE_LONG, longArrayOf(tileWidth.toLong())))
            add(EntryDefinition(323, TYPE_LONG, longArrayOf(tileHeight.toLong())))
            add(EntryDefinition(324, TYPE_LONG, LongArray(segments.size)))
            add(
                EntryDefinition(
                    325,
                    TYPE_LONG,
                    LongArray(segments.size) { tileWidth.toLong() * tileHeight.toLong() * 2L },
                ),
            )
        }
        return build(
            order = order,
            entries = entries,
            segments = segments,
            link = link,
            corruptOffsets = CorruptOffsets.NONE,
        )
    }

    private fun commonEntries(width: Int, height: Int, compression: Int): List<EntryDefinition> =
        listOf(
            EntryDefinition(256, TYPE_LONG, longArrayOf(width.toLong())),
            EntryDefinition(257, TYPE_LONG, longArrayOf(height.toLong())),
            EntryDefinition(258, TYPE_SHORT, longArrayOf(16)),
            EntryDefinition(259, TYPE_SHORT, longArrayOf(compression.toLong())),
            EntryDefinition(262, TYPE_SHORT, longArrayOf(CFA)),
            EntryDefinition(277, TYPE_SHORT, longArrayOf(1)),
            EntryDefinition(284, TYPE_SHORT, longArrayOf(1)),
            EntryDefinition(339, TYPE_SHORT, longArrayOf(1)),
        )

    private fun build(
        order: FixtureByteOrder,
        entries: MutableList<EntryDefinition>,
        segments: List<IntArray>,
        link: DirectoryLink,
        corruptOffsets: CorruptOffsets,
    ): ByteArray {
        val sorted = entries.sortedBy(EntryDefinition::tag).toMutableList()
        val primarySize = when (link) {
            DirectoryLink.DIRECT -> 0
            DirectoryLink.SUB_IFD -> directorySize(1)
            DirectoryLink.NEXT_IFD -> directorySize(0)
        }
        val cfaOffset = 8 + primarySize
        val cfaSize = directorySize(sorted.size)
        var externalCursor = cfaOffset + cfaSize
        val externalOffsets = mutableMapOf<Int, Int>()
        sorted.forEach { entry ->
            if (entry.payloadBytes > 4) {
                externalOffsets[entry.tag] = externalCursor
                externalCursor += entry.payloadBytes
            }
        }
        val imageStart = externalCursor
        val segmentOffsets = LongArray(segments.size)
        var imageCursor = imageStart
        segments.forEachIndexed { index, segment ->
            segmentOffsets[index] = imageCursor.toLong()
            imageCursor += segment.size * 2
        }
        when (corruptOffsets) {
            CorruptOffsets.NONE -> Unit
            CorruptOffsets.OVERLAP -> if (segmentOffsets.size > 1) {
                segmentOffsets[1] = segmentOffsets[0]
            }
            CorruptOffsets.OUT_OF_BOUNDS -> {
                segmentOffsets[segmentOffsets.lastIndex] = imageCursor.toLong() + 32L
            }
        }
        val offsetTag = if (sorted.any { it.tag == 273 }) 273 else 324
        sorted[sorted.indexOfFirst { it.tag == offsetTag }] =
            EntryDefinition(offsetTag, TYPE_LONG, segmentOffsets)

        val output = ByteArray(imageCursor)
        val writer = FixtureWriter(output, order)
        writer.byte(0, if (order == FixtureByteOrder.LITTLE) 'I'.code else 'M'.code)
        writer.byte(1, if (order == FixtureByteOrder.LITTLE) 'I'.code else 'M'.code)
        writer.u16(2, 42)
        writer.u32(4, 8L)

        when (link) {
            DirectoryLink.DIRECT -> Unit
            DirectoryLink.SUB_IFD -> writeDirectory(
                writer = writer,
                offset = 8,
                entries = listOf(EntryDefinition(330, TYPE_IFD, longArrayOf(cfaOffset.toLong()))),
                externalOffsets = emptyMap(),
                nextIfd = 0,
            )
            DirectoryLink.NEXT_IFD -> writeDirectory(
                writer = writer,
                offset = 8,
                entries = emptyList(),
                externalOffsets = emptyMap(),
                nextIfd = cfaOffset,
            )
        }
        writeDirectory(writer, cfaOffset, sorted, externalOffsets, nextIfd = 0)
        sorted.forEach { entry ->
            externalOffsets[entry.tag]?.let { payloadOffset ->
                writer.values(payloadOffset, entry.type, entry.values)
            }
        }
        segments.forEachIndexed { index, segment ->
            val physicalOffset = if (index == 0) imageStart else {
                imageStart + segments.take(index).sumOf { it.size * 2 }
            }
            segment.forEachIndexed { sampleIndex, sample ->
                writer.u16(physicalOffset + sampleIndex * 2, sample)
            }
        }
        return output
    }

    private fun writeDirectory(
        writer: FixtureWriter,
        offset: Int,
        entries: List<EntryDefinition>,
        externalOffsets: Map<Int, Int>,
        nextIfd: Int,
    ) {
        writer.u16(offset, entries.size)
        entries.forEachIndexed { index, entry ->
            val base = offset + 2 + index * 12
            writer.u16(base, entry.tag)
            writer.u16(base + 2, entry.type)
            writer.u32(base + 4, entry.values.size.toLong())
            if (entry.payloadBytes <= 4) {
                writer.values(base + 8, entry.type, entry.values)
            } else {
                writer.u32(base + 8, requireNotNull(externalOffsets[entry.tag]).toLong())
            }
        }
        writer.u32(offset + 2 + entries.size * 12, nextIfd.toLong())
    }

    private fun directorySize(entryCount: Int): Int = 2 + entryCount * 12 + 4

    private data class EntryDefinition(
        val tag: Int,
        val type: Int,
        val values: LongArray,
    ) {
        val payloadBytes: Int = values.size * if (type == TYPE_SHORT) 2 else 4
    }
}

private class FixtureWriter(
    private val target: ByteArray,
    private val order: FixtureByteOrder,
) {
    fun byte(offset: Int, value: Int) {
        target[offset] = value.toByte()
    }

    fun u16(offset: Int, value: Int) {
        if (order == FixtureByteOrder.LITTLE) {
            byte(offset, value)
            byte(offset + 1, value ushr 8)
        } else {
            byte(offset, value ushr 8)
            byte(offset + 1, value)
        }
    }

    fun u32(offset: Int, value: Long) {
        if (order == FixtureByteOrder.LITTLE) {
            repeat(4) { index -> byte(offset + index, (value ushr (index * 8)).toInt()) }
        } else {
            repeat(4) { index -> byte(offset + index, (value ushr ((3 - index) * 8)).toInt()) }
        }
    }

    fun values(offset: Int, type: Int, values: LongArray) {
        val elementBytes = if (type == 3) 2 else 4
        values.forEachIndexed { index, value ->
            if (elementBytes == 2) u16(offset + index * 2, value.toInt())
            else u32(offset + index * 4, value)
        }
    }
}
