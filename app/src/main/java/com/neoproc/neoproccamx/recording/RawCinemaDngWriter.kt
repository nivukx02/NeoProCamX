package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.niv99.Niv99RawFrameEvidence
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * RawCinemaDngWriter — writes ONE standard, uncompressed CinemaDNG frame (a baseline TIFF/EP + DNG
 * colour-tagged mosaic image) from a decoded RAW sensor frame + its `develop.meta` sidecar.
 *
 * WHY a from-scratch encoder: Android's `DngCreator` can only write a DNG from a LIVE
 * `TotalCaptureResult` at capture time; there is no way to reconstruct one offline from a recorded
 * `stream.npraw` clip. This writer instead sources the exact colour-defining metadata the sidecar
 * already carries (CFA phase, black/white level, WB gains, sensor→XYZ(D50) matrix) and emits a
 * minimal-but-valid DNG that DaVinci Resolve / Lightroom debayer directly.
 *
 * PATENT-SAFE: DNG is Adobe's open, published TIFF/EP-derived standard. Each frame is written in
 * ISOLATION — no multi-frame merge, temporal accumulation, exposure fusion, or wavelet denoise. A
 * clip becomes a **CinemaDNG sequence** (one `.dng` per frame in a folder), the industry-standard
 * portable RAW-video form.
 *
 * The image is a single full-resolution CFA IFD (IFD0), uncompressed 16-bit samples, little-endian
 * ("II"). The colour tags mirror what [com.neoproc.neoproccamx.capture.DngTiffReader] can re-read,
 * so the written tags are JVM-round-trip-verifiable without a device.
 *
 * The writer is pure Kotlin (no Android) so the byte layout is fully unit-testable.
 */
object RawCinemaDngWriter {
    /**
     * Batch the sensor plane into large, sample-aligned writes.  The previous row-at-a-time loop
     * issued 2K-8K writes thousands of times per full-resolution frame through MediaStore/FUSE.
     * One MiB keeps memory bounded while reducing those calls by well over 10x at every supported
     * video resolution.  It changes only write granularity; emitted DNG bytes remain identical.
     */
    internal const val PIXEL_WRITE_CHUNK_BYTES: Int = 1024 * 1024

    // ── TIFF field types ─────────────────────────────────────────────────────────
    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_RATIONAL = 5
    private const val TYPE_SRATIONAL = 10

    // ── Tags (baseline TIFF + TIFF/EP CFA + DNG colour) ────────────────────────────
    private const val TAG_NEW_SUBFILE_TYPE = 254
    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_IMAGE_DESCRIPTION = 270
    private const val TAG_MAKE = 271
    private const val TAG_MODEL = 272
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_ORIENTATION = 274
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_PLANAR_CONFIG = 284
    private const val TAG_SOFTWARE = 305
    private const val TAG_CFA_REPEAT_PATTERN_DIM = 33421
    private const val TAG_CFA_PATTERN = 33422
    private const val TAG_DNG_VERSION = 50706
    private const val TAG_DNG_BACKWARD_VERSION = 50707
    private const val TAG_UNIQUE_CAMERA_MODEL = 50708
    private const val TAG_CFA_PLANE_COLOR = 50710
    private const val TAG_CFA_LAYOUT = 50711
    private const val TAG_BLACK_LEVEL_REPEAT_DIM = 50713
    private const val TAG_BLACK_LEVEL = 50714
    private const val TAG_WHITE_LEVEL = 50717
    private const val TAG_COLOR_MATRIX_1 = 50721
    private const val TAG_AS_SHOT_NEUTRAL = 50728
    private const val TAG_CALIBRATION_ILLUMINANT_1 = 50778

    private const val PHOTOMETRIC_CFA = 32803
    private const val COMPRESSION_NONE = 1
    private const val ILLUMINANT_D50 = 23 // ExifTag LightSource / DNG CalibrationIlluminant: D50
    private const val CFA_LAYOUT_RECTANGULAR = 1

    private const val MATRIX_RATIONAL_DEN = 1_000_000
    private const val NEUTRAL_RATIONAL_DEN = 1_000_000

    private const val CAMERA_MODEL = "NeoProCamX RAW"
    private const val CAMERA_MAKE = "NeoProCamX"

    /** A single TIFF IFD entry with its serialized (little-endian) value bytes. */
    private class Field(val tag: Int, val type: Int, val count: Int, var data: ByteArray)

    /**
     * Write one CinemaDNG frame to [out]. [mosaic] is the decoded sensor mosaic (row-major, length
     * ≥ width*height, values in the sensor domain 0..whiteLevel), [meta] the clip's develop sidecar.
     * The pixel plane is streamed directly to [out] (never fully buffered) so a 50-MP frame does not
     * balloon the heap.
     */
    fun writeFrame(
        out: OutputStream,
        mosaic: IntArray,
        meta: RawDevelopMetadata,
        niv99Evidence: Niv99RawFrameEvidence? = null
    ) {
        val width = meta.width
        val height = meta.height
        require(width > 0 && height > 0) { "invalid dimensions ${width}x$height" }
        val sampleCountLong = width.toLong() * height.toLong()
        require(sampleCountLong in 1..Int.MAX_VALUE.toLong()) {
            "CinemaDNG dimensions exceed addressable sample storage: ${width}x$height"
        }
        val sampleCount = sampleCountLong.toInt()
        require(mosaic.size == sampleCount) {
            "mosaic sample count ${mosaic.size} does not exactly match ${width}x$height"
        }

        val head = buildHeader(width, height, meta, niv99Evidence)
        out.write(head)

        // Pixel plane: 16-bit little-endian samples, streamed in large sample-aligned chunks.
        // Do not use ByteBuffer/ShortBuffer here: its per-call view setup is slower on some ART
        // releases and offers no precision benefit for this exact integer serialization.
        val pixelBytes = ByteArray(PIXEL_WRITE_CHUNK_BYTES)
        var sampleIndex = 0
        while (sampleIndex < sampleCount) {
            val samplesInChunk = minOf(pixelBytes.size / 2, sampleCount - sampleIndex)
            var byteIndex = 0
            val chunkEnd = sampleIndex + samplesInChunk
            while (sampleIndex < chunkEnd) {
                val value = mosaic[sampleIndex]
                require(value in 0..0xFFFF) {
                    "mosaic sample $sampleIndex is outside unsigned 16-bit storage: $value"
                }
                pixelBytes[byteIndex] = (value and 0xFF).toByte()
                pixelBytes[byteIndex + 1] = ((value ushr 8) and 0xFF).toByte()
                sampleIndex++
                byteIndex += 2
            }
            out.write(pixelBytes, 0, byteIndex)
        }
        out.flush()
    }

    /** Serialize a whole single-frame DNG (header + pixels) into a byte array. For unit tests. */
    fun frameToBytes(
        mosaic: IntArray,
        meta: RawDevelopMetadata,
        niv99Evidence: Niv99RawFrameEvidence? = null
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        writeFrame(bos, mosaic, meta, niv99Evidence)
        return bos.toByteArray()
    }

    /** Suggested per-frame file name in a CinemaDNG sequence (1-based, zero-padded). */
    fun frameFileName(frameIndex: Int): String = "frame_%06d.dng".format(frameIndex + 1)

    // ───────────────────────────────────────────────────────────────────────────────

    private fun buildHeader(
        width: Int,
        height: Int,
        meta: RawDevelopMetadata,
        niv99Evidence: Niv99RawFrameEvidence?
    ): ByteArray {
        val stripByteCount = width.toLong() * height.toLong() * 2L

        val fields = ArrayList<Field>()
        fields += Field(TAG_NEW_SUBFILE_TYPE, TYPE_LONG, 1, longLe(0))
        fields += Field(TAG_IMAGE_WIDTH, TYPE_LONG, 1, longLe(width.toLong()))
        fields += Field(TAG_IMAGE_LENGTH, TYPE_LONG, 1, longLe(height.toLong()))
        fields += Field(TAG_BITS_PER_SAMPLE, TYPE_SHORT, 1, shortLe(16))
        fields += Field(TAG_COMPRESSION, TYPE_SHORT, 1, shortLe(COMPRESSION_NONE))
        fields += Field(TAG_PHOTOMETRIC, TYPE_SHORT, 1, shortLe(PHOTOMETRIC_CFA))
        niv99Evidence?.let { evidence ->
            val description = buildNiv99Description(evidence)
            fields += Field(
                TAG_IMAGE_DESCRIPTION,
                TYPE_ASCII,
                description.length + 1,
                asciiz(description)
            )
            fields += Field(TAG_SOFTWARE, TYPE_ASCII, NIV99_SOFTWARE.length + 1, asciiz(NIV99_SOFTWARE))
        }
        fields += Field(TAG_MAKE, TYPE_ASCII, CAMERA_MAKE.length + 1, asciiz(CAMERA_MAKE))
        fields += Field(TAG_MODEL, TYPE_ASCII, CAMERA_MODEL.length + 1, asciiz(CAMERA_MODEL))
        // StripOffsets value is patched once the header length is known (pixels follow the header).
        val stripOffsets = Field(TAG_STRIP_OFFSETS, TYPE_LONG, 1, longLe(0))
        fields += stripOffsets
        fields += Field(TAG_ORIENTATION, TYPE_SHORT, 1, shortLe(1))
        fields += Field(TAG_SAMPLES_PER_PIXEL, TYPE_SHORT, 1, shortLe(1))
        fields += Field(TAG_ROWS_PER_STRIP, TYPE_LONG, 1, longLe(height.toLong()))
        fields += Field(TAG_STRIP_BYTE_COUNTS, TYPE_LONG, 1, longLe(stripByteCount))
        fields += Field(TAG_PLANAR_CONFIG, TYPE_SHORT, 1, shortLe(1))
        fields += Field(TAG_CFA_REPEAT_PATTERN_DIM, TYPE_SHORT, 2, shortLeArray(intArrayOf(2, 2)))
        fields += Field(TAG_CFA_PATTERN, TYPE_BYTE, 4, cfaPattern(meta.bayerPatternValue))
        fields += Field(TAG_DNG_VERSION, TYPE_BYTE, 4, byteArrayOf(1, 4, 0, 0))
        fields += Field(TAG_DNG_BACKWARD_VERSION, TYPE_BYTE, 4, byteArrayOf(1, 1, 0, 0))
        fields += Field(TAG_UNIQUE_CAMERA_MODEL, TYPE_ASCII, CAMERA_MODEL.length + 1, asciiz(CAMERA_MODEL))
        fields += Field(TAG_CFA_PLANE_COLOR, TYPE_BYTE, 3, byteArrayOf(0, 1, 2))
        fields += Field(TAG_CFA_LAYOUT, TYPE_SHORT, 1, shortLe(CFA_LAYOUT_RECTANGULAR))
        // Preserve all four physical CFA offsets. DNG BlackLevel is ordered by the repeated 2x2
        // sample pattern, not semantic RGGB order, so rotate the sidecar calibration with the CFA.
        fields += Field(TAG_BLACK_LEVEL_REPEAT_DIM, TYPE_SHORT, 2, shortLeArray(intArrayOf(2, 2)))
        fields += Field(TAG_BLACK_LEVEL, TYPE_RATIONAL, 4, physicalBlackLevels(meta))
        fields += Field(TAG_WHITE_LEVEL, TYPE_LONG, 1, longLe(meta.whiteLevel.toLong().coerceIn(1, 0xFFFFFFFFL)))
        fields += Field(TAG_COLOR_MATRIX_1, TYPE_SRATIONAL, 9, colorMatrix1(meta.sensorToXyz))
        fields += Field(TAG_AS_SHOT_NEUTRAL, TYPE_RATIONAL, 3, asShotNeutral(meta))
        fields += Field(TAG_CALIBRATION_ILLUMINANT_1, TYPE_SHORT, 1, shortLe(ILLUMINANT_D50))

        fields.sortBy { it.tag }

        val n = fields.size
        val ifdStart = 8
        val ifdSize = 2 + n * 12 + 4

        // Lay out the out-of-line value blocks (data > 4 bytes), each 2-byte aligned.
        val offsets = HashMap<Field, Int>()
        var cursor = ifdStart + ifdSize
        for (f in fields) {
            if (f.data.size > 4) {
                if (cursor and 1 == 1) cursor++
                offsets[f] = cursor
                cursor += f.data.size
            }
        }
        if (cursor and 1 == 1) cursor++
        val pixelOffset = cursor
        stripOffsets.data = longLe(pixelOffset.toLong())

        val bos = ByteArrayOutputStream(pixelOffset)
        // TIFF header: byte order II, magic 42, offset to IFD0.
        bos.write('I'.code); bos.write('I'.code)
        bos.write(shortLe(TIFF_MAGIC))
        bos.write(longLe(ifdStart.toLong()))
        // IFD0.
        bos.write(shortLe(n))
        for (f in fields) {
            bos.write(shortLe(f.tag))
            bos.write(shortLe(f.type))
            bos.write(longLe(f.count.toLong()))
            if (f.data.size <= 4) {
                val padded = ByteArray(4)
                System.arraycopy(f.data, 0, padded, 0, f.data.size)
                bos.write(padded)
            } else {
                bos.write(longLe(offsets.getValue(f).toLong()))
            }
        }
        bos.write(longLe(0)) // next IFD = none
        // Out-of-line value blocks, in the same order offsets were assigned.
        for (f in fields) {
            if (f.data.size > 4) {
                while (bos.size() < offsets.getValue(f)) bos.write(0)
                bos.write(f.data)
            }
        }
        while (bos.size() < pixelOffset) bos.write(0)
        return bos.toByteArray()
    }

    private fun buildNiv99Description(evidence: Niv99RawFrameEvidence): String =
        evidence.metadata().entries.joinToString(
            separator = ";",
            prefix = "NIV99;"
        ) { (key, value) -> "$key=$value" }

    private const val TIFF_MAGIC = 42
    private const val NIV99_SOFTWARE = "NeoProCamX NIV99 offline RAW develop"

    // ── CFA pattern (0=Red,1=Green,2=Blue) from the bayer nativeValue ──────────────
    // RGGB=0 / GRBG=1 / GBRG=2 / BGGR=3 (matches RawDevelopMetadata.bayerPatternValue).
    private fun cfaPattern(bayer: Int): ByteArray = when (bayer) {
        0 -> byteArrayOf(0, 1, 1, 2) // RGGB
        1 -> byteArrayOf(1, 0, 2, 1) // GRBG
        2 -> byteArrayOf(1, 2, 0, 1) // GBRG
        else -> byteArrayOf(2, 1, 1, 0) // BGGR
    }

    private fun physicalBlackLevels(meta: RawDevelopMetadata): ByteArray {
        val semantic = meta.blackLevelRggb.takeIf { levels ->
            levels.size == 4 && levels.all { it.isFinite() && it >= 0f }
        } ?: FloatArray(4) { meta.blackLevel.toFloat().coerceAtLeast(0f) }
        val pattern = if (meta.bayerPatternValue in 0..3) meta.bayerPatternValue else 3
        val out = ByteArrayOutputStream(32)
        for (physicalIndex in 0 until 4) {
            val semanticIndex = physicalIndex xor pattern
            out.write(rationalUnsigned(semantic[semanticIndex].toDouble(), MATRIX_RATIONAL_DEN))
        }
        return out.toByteArray()
    }

    /** ColorMatrix1 = XYZ(D50) → camera reference = inverse of the sensor→XYZ(D50) forward matrix. */
    private fun colorMatrix1(sensorToXyz: FloatArray): ByteArray {
        val inv = invert3x3(sensorToXyz)
        val bos = ByteArrayOutputStream(72)
        for (v in inv) bos.write(srational(v.toDouble(), MATRIX_RATIONAL_DEN))
        return bos.toByteArray()
    }

    /**
     * AsShotNeutral — the camera-native RGB of a neutral, = 1/WB-gain per channel, normalized to
     * green = 1 (green gain is the mean of the two CFA greens). Resolve renders the recorded white
     * point from this.
     */
    private fun asShotNeutral(meta: RawDevelopMetadata): ByteArray {
        val gr = meta.whiteBalanceR.takeIf { it > 0f } ?: 1f
        val gg = (((meta.whiteBalanceGr + meta.whiteBalanceGb) / 2f)).takeIf { it > 0f } ?: 1f
        val gb = meta.whiteBalanceB.takeIf { it > 0f } ?: 1f
        // neutral ∝ 1/gain; normalize so green = 1.
        val nr = (gg / gr).toDouble()
        val ng = 1.0
        val nb = (gg / gb).toDouble()
        val bos = ByteArrayOutputStream(24)
        bos.write(rationalUnsigned(nr, NEUTRAL_RATIONAL_DEN))
        bos.write(rationalUnsigned(ng, NEUTRAL_RATIONAL_DEN))
        bos.write(rationalUnsigned(nb, NEUTRAL_RATIONAL_DEN))
        return bos.toByteArray()
    }

    /** Row-major 3×3 inverse. Falls back to identity if the matrix is (near-)singular. */
    private fun invert3x3(m: FloatArray): FloatArray {
        if (m.size != 9) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
        val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
        val g = m[6].toDouble(); val h = m[7].toDouble(); val i = m[8].toDouble()
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (det == 0.0 || det.isNaN()) return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val inv = 1.0 / det
        return floatArrayOf(
            ((e * i - f * h) * inv).toFloat(),
            ((c * h - b * i) * inv).toFloat(),
            ((b * f - c * e) * inv).toFloat(),
            ((f * g - d * i) * inv).toFloat(),
            ((a * i - c * g) * inv).toFloat(),
            ((c * d - a * f) * inv).toFloat(),
            ((d * h - e * g) * inv).toFloat(),
            ((b * g - a * h) * inv).toFloat(),
            ((a * e - b * d) * inv).toFloat()
        )
    }

    // ── little-endian serializers ──────────────────────────────────────────────────

    private fun shortLe(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun shortLeArray(values: IntArray): ByteArray {
        val out = ByteArray(values.size * 2)
        for (idx in values.indices) {
            out[idx * 2] = (values[idx] and 0xFF).toByte()
            out[idx * 2 + 1] = ((values[idx] ushr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun longLe(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )

    private fun asciiz(s: String): ByteArray {
        val raw = s.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(raw.size + 1)
        System.arraycopy(raw, 0, out, 0, raw.size)
        return out // trailing NUL already zero
    }

    private fun rational(num: Long, den: Int): ByteArray {
        val out = ByteArray(8)
        System.arraycopy(longLe(num), 0, out, 0, 4)
        System.arraycopy(longLe(den.toLong()), 0, out, 4, 4)
        return out
    }

    private fun rationalUnsigned(value: Double, den: Int): ByteArray {
        val num = Math.round(value.coerceAtLeast(0.0) * den)
        return rational(num, den)
    }

    private fun srational(value: Double, den: Int): ByteArray {
        val num = Math.round(value * den).toInt()
        val out = ByteArray(8)
        System.arraycopy(longLe(num.toLong()), 0, out, 0, 4)
        System.arraycopy(longLe(den.toLong()), 0, out, 4, 4)
        return out
    }
}
