package com.neoproc.neoproccamx.capture

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DngTiffReader — minimal, dependency-free DNG/TIFF tag reader for the colour-defining tags.
 *
 * This is the **re-read** half of the DNG colour-exactness contract (§ "DNG color exactness"):
 * it parses the IFD(s) of a DNG byte stream and extracts BlackLevel / WhiteLevel, the Colour
 * and Forward matrices, their CalibrationIlluminants and the NoiseProfile — so a field-equality
 * check can confirm a written DNG actually carries the neutral-defining metadata a third party
 * needs to render the same neutral.
 *
 * Android's `ExifInterface` only surfaces baseline EXIF/TIFF tags, not the DNG-specific colour
 * opcodes, hence a purpose-built reader. It is the JVM-verifiable counterpart to the instrumented
 * `DngCreator` round-trip, and pairs with [RawDngMetadataContract] for the present/missing set.
 */
object DngTiffReader {

    private const val TIFF_MAGIC = 42
    private const val TAG_SUB_IFDS = 330
    private const val TAG_BLACK_LEVEL = 50714
    private const val TAG_WHITE_LEVEL = 50717
    private const val TAG_COLOR_MATRIX_1 = 50721
    private const val TAG_COLOR_MATRIX_2 = 50722
    private const val TAG_CALIBRATION_ILLUMINANT_1 = 50778
    private const val TAG_CALIBRATION_ILLUMINANT_2 = 50779
    private const val TAG_FORWARD_MATRIX_1 = 50964
    private const val TAG_FORWARD_MATRIX_2 = 50965
    private const val TAG_NOISE_PROFILE = 51041

    class DngFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

    class DngTags(
        val blackLevel: DoubleArray?,
        val whiteLevel: Long?,
        val colorMatrix1: DoubleArray?,
        val colorMatrix2: DoubleArray?,
        val forwardMatrix1: DoubleArray?,
        val forwardMatrix2: DoubleArray?,
        val calibrationIlluminant1: Int?,
        val calibrationIlluminant2: Int?,
        val noiseProfile: DoubleArray?
    ) {
        /** The [RawDngMetadataContract] tags this DNG actually carries. */
        fun presentTags(): Set<RawDngMetadataContract.Tag> {
            val present = mutableSetOf<RawDngMetadataContract.Tag>()
            if (blackLevel != null) present += RawDngMetadataContract.Tag.BLACK_LEVEL
            if (whiteLevel != null) present += RawDngMetadataContract.Tag.WHITE_LEVEL
            if (colorMatrix1 != null || colorMatrix2 != null) present += RawDngMetadataContract.Tag.COLOR_MATRIX
            if (forwardMatrix1 != null || forwardMatrix2 != null) present += RawDngMetadataContract.Tag.FORWARD_MATRIX
            if (calibrationIlluminant1 != null) present += RawDngMetadataContract.Tag.CALIBRATION_ILLUMINANT
            if (noiseProfile != null) present += RawDngMetadataContract.Tag.NOISE_PROFILE
            return present
        }
    }

    private class Field(val type: Int, val count: Long, val dataOffset: Int)

    /** Parse a DNG/TIFF byte stream and extract the colour-defining tags. */
    fun read(bytes: ByteArray): DngTags {
        if (bytes.size < 8) throw DngFormatException("DNG too small (${bytes.size} bytes)")
        val order = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> throw DngFormatException("not a TIFF/DNG (bad byte-order mark)")
        }
        val buf = ByteBuffer.wrap(bytes).order(order)
        val magic = buf.getShort(2).toInt() and 0xFFFF
        if (magic != TIFF_MAGIC) throw DngFormatException("bad TIFF magic ($magic)")
        val ifd0 = buf.getInt(4).toLong() and 0xFFFFFFFFL

        val fields = HashMap<Int, Field>()
        collectIfd(buf, bytes.size, ifd0, fields, HashSet(), depth = 0)

        return DngTags(
            blackLevel = fields[TAG_BLACK_LEVEL]?.let { readNumbers(buf, bytes.size, it) },
            whiteLevel = fields[TAG_WHITE_LEVEL]?.let { readNumbers(buf, bytes.size, it).firstOrNull()?.toLong() },
            colorMatrix1 = fields[TAG_COLOR_MATRIX_1]?.let { readNumbers(buf, bytes.size, it) },
            colorMatrix2 = fields[TAG_COLOR_MATRIX_2]?.let { readNumbers(buf, bytes.size, it) },
            forwardMatrix1 = fields[TAG_FORWARD_MATRIX_1]?.let { readNumbers(buf, bytes.size, it) },
            forwardMatrix2 = fields[TAG_FORWARD_MATRIX_2]?.let { readNumbers(buf, bytes.size, it) },
            calibrationIlluminant1 = fields[TAG_CALIBRATION_ILLUMINANT_1]
                ?.let { readNumbers(buf, bytes.size, it).firstOrNull()?.toInt() },
            calibrationIlluminant2 = fields[TAG_CALIBRATION_ILLUMINANT_2]
                ?.let { readNumbers(buf, bytes.size, it).firstOrNull()?.toInt() },
            noiseProfile = fields[TAG_NOISE_PROFILE]?.let { readNumbers(buf, bytes.size, it) }
        )
    }

    private fun collectIfd(
        buf: ByteBuffer,
        size: Int,
        ifdOffset: Long,
        fields: HashMap<Int, Field>,
        visited: HashSet<Long>,
        depth: Int
    ) {
        if (depth > 6 || ifdOffset <= 0L || ifdOffset + 2 > size) return
        if (!visited.add(ifdOffset)) return
        val base = ifdOffset.toInt()
        val count = buf.getShort(base).toInt() and 0xFFFF
        val subIfdOffsets = ArrayList<Long>()
        for (i in 0 until count) {
            val entry = base + 2 + i * 12
            if (entry + 12 > size) break
            val tag = buf.getShort(entry).toInt() and 0xFFFF
            val type = buf.getShort(entry + 2).toInt() and 0xFFFF
            val cnt = buf.getInt(entry + 4).toLong() and 0xFFFFFFFFL
            val valueSize = typeSize(type).toLong() * cnt
            val dataOffset = if (valueSize <= 4L) {
                entry + 8
            } else {
                (buf.getInt(entry + 8).toLong() and 0xFFFFFFFFL).toInt()
            }
            if (tag == TAG_SUB_IFDS) {
                readNumbers(buf, size, Field(type, cnt, dataOffset)).forEach { subIfdOffsets.add(it.toLong()) }
            } else {
                fields.putIfAbsent(tag, Field(type, cnt, dataOffset))
            }
        }
        for (sub in subIfdOffsets) collectIfd(buf, size, sub, fields, visited, depth + 1)
    }

    private fun readNumbers(buf: ByteBuffer, size: Int, field: Field): DoubleArray {
        val type = field.type
        val n = field.count.coerceIn(0L, 1_000_000L).toInt()
        val ts = typeSize(type)
        val out = DoubleArray(n)
        var off = field.dataOffset
        for (i in 0 until n) {
            if (off < 0 || off + ts > size) return out.copyOf(i)
            out[i] = when (type) {
                1 -> (buf.get(off).toInt() and 0xFF).toDouble()                 // BYTE
                3 -> (buf.getShort(off).toInt() and 0xFFFF).toDouble()          // SHORT
                4 -> (buf.getInt(off).toLong() and 0xFFFFFFFFL).toDouble()      // LONG
                8 -> buf.getShort(off).toInt().toDouble()                       // SSHORT
                9 -> buf.getInt(off).toDouble()                                 // SLONG
                5 -> {                                                          // RATIONAL
                    val num = buf.getInt(off).toLong() and 0xFFFFFFFFL
                    val den = buf.getInt(off + 4).toLong() and 0xFFFFFFFFL
                    if (den == 0L) 0.0 else num.toDouble() / den.toDouble()
                }
                10 -> {                                                         // SRATIONAL
                    val num = buf.getInt(off)
                    val den = buf.getInt(off + 4)
                    if (den == 0) 0.0 else num.toDouble() / den.toDouble()
                }
                11 -> buf.getFloat(off).toDouble()                             // FLOAT
                12 -> buf.getDouble(off)                                        // DOUBLE
                else -> 0.0
            }
            off += ts
        }
        return out
    }

    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12 -> 8
        else -> 1
    }
}
