package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.recording.RawCompressionMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * NprawFrameCodec — the single source of truth for the NPRAW frame container.
 *
 * Previously the pack/predict/header logic lived inline in [RawVideoManager] and was
 * **write-only**: there was no decoder, so the serialised stream could not be verified,
 * round-tripped, or proven bit-exact. This object owns both directions:
 *
 *   encodeFrame(samples) ──► NPRAW bytes ──► decodeFrame(bytes) ──► samples
 *
 * having both in one place makes the format a *contract* the test-suite can pin
 * (golden bytes), round-trip (bit-exact for the lossless tier), and fuzz (CRC detects
 * a corrupt/short frame instead of silently rendering garbage).
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * CONTAINER (little/big-endian per java.io DataOutput, i.e. big-endian fields)
 * ══════════════════════════════════════════════════════════════════════════════
 *   magic         5  "NPRAW"
 *   version       1  2 = legacy (no companding), 3 = companded (adds 2 trailing ints)
 *   modeOrdinal   4  RawCompressionMode.ordinal  (ABI — enum order is frozen)
 *   width         4
 *   height        4
 *   rowStride     4
 *   pixelStride   4
 *   timestamp     8
 *   originalSize  4  sensor plane byte count (pre-encode)
 *   compressedSize4  payload byte count (what follows the header)
 *   bitDepth      4  storage bit depth of the packed samples
 *   blackLevel    4  payload-domain min
 *   whiteLevel    4  payload-domain max
 *   dynRangeStops 4  float
 *   noiseEstimate 4  float
 *   crc32         8  CRC32 of the payload
 *   ── v3 only ──
 *   compandingType4  RawCompandingCurve.COMPANDING_*
 *   sensorWhiteLvl4  sensor white level (curve domain for the inverse)
 *   payload       compressedSize bytes
 *
 * ══════════════════════════════════════════════════════════════════════════════
 * BACK-COMPAT / NO-DOWNGRADE
 * ══════════════════════════════════════════════════════════════════════════════
 * The writer emits **version 2 (byte-identical to the legacy layout)** whenever no
 * companding is applied — i.e. on every device already at native depth, and for every
 * 16/14-bit container. Only a frame that genuinely companded a wider sensor word into
 * a smaller container is stamped version 3. The decoder reads both, so old streams keep
 * decoding and current-device output is unchanged.
 */
object NprawFrameCodec {

    /** Camera2 RAW_SENSOR delivers samples in a 16-bit transport word even on 10/12-bit sensors. */
    const val REALTIME_SOURCE_TRANSPORT_BIT_DEPTH = 16

    private val MAGIC = byteArrayOf(
        'N'.code.toByte(), 'P'.code.toByte(), 'R'.code.toByte(), 'A'.code.toByte(), 'W'.code.toByte()
    )

    /** Legacy layout — no companding fields. */
    const val VERSION_LINEAR = 2

    /** Companded layout — adds [compandingType] + [sensorWhiteLevel] trailing ints. */
    const val VERSION_COMPANDED = 3

    /** Bit-exact independent block-range layout used by the thermally resilient RAW writer. */
    const val VERSION_BLOCK_RANGE_LOSSLESS = 4

    private const val BLOCK_RANGE_TILE_WIDTH = 64
    private const val BLOCK_RANGE_TILE_HEIGHT = 4
    private const val BLOCK_RANGE_PAYLOAD_HEADER_BYTES = 12
    private const val BLOCK_RANGE_TILE_HEADER_BYTES = 3
    private const val BLOCK_RANGE_PLAYBACK_READ_BYTES = 256 * 1024
    private val BLOCK_RANGE_MAGIC = byteArrayOf(
        'N'.code.toByte(), 'B'.code.toByte(), 'R'.code.toByte(), '1'.code.toByte()
    )

    /**
     * Upper bound on width*height. The geometry comes from the (CRC-unprotected) header, so
     * the decoder validates it with a 64-bit multiply against this bound before allocating —
     * a corrupt width/height can never overflow Int into a negative array size.
     */
    private const val MAX_SAMPLES = 512_000_000L

    /**
     * Per-dimension upper bound for the CRC-unprotected width/height header fields. A single
     * bit-flip can inflate a plausible size (e.g. 4096×3072) into a value that still satisfies
     * [MAX_SAMPLES] yet forces a multi-GB `IntArray` allocation and an uncaught `OutOfMemoryError`.
     * Clamping each axis to a generous-but-sane cap keeps corrupt geometry surfacing as
     * [NprawFormatException] (as the guard comment promises) rather than an `Error`.
     */
    private const val MAX_DIMENSION = 16384
    private const val DIRECT_BUFFER_SCALAR_TAIL_BYTES = 64

    // ── Encode result ──────────────────────────────────────────────────────────

    interface EncodeMetadata {
        val version: Int
        val bitDepth: Int
        val compandingType: Int
        val payloadSize: Int
        val compressionRatio: Float
    }

    class EncodeResult(
        val bytes: ByteArray,
        override val version: Int,
        override val bitDepth: Int,
        override val compandingType: Int,
        override val payloadSize: Int,
        override val compressionRatio: Float
    ) : EncodeMetadata

    class DirectEncodeResult(
        val packetSize: Int,
        override val version: Int,
        override val bitDepth: Int,
        override val compandingType: Int,
        override val payloadSize: Int,
        override val compressionRatio: Float
    ) : EncodeMetadata

    /** Header + metadata for a packet whose tightly packed payload remains camera-owned. */
    class BorrowedDirectEncodeResult(
        val header: ByteArray,
        val packetSize: Int,
        override val version: Int,
        override val bitDepth: Int,
        override val compandingType: Int,
        override val payloadSize: Int,
        override val compressionRatio: Float
    ) : EncodeMetadata

    // ── Decode result ──────────────────────────────────────────────────────────

    class DecodedFrame(
        val version: Int,
        val mode: RawCompressionMode?,
        val modeOrdinal: Int,
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val pixelStride: Int,
        val timestamp: Long,
        val originalSize: Int,
        val compressedSize: Int,
        val bitDepth: Int,
        val blackLevel: Int,
        val whiteLevel: Int,
        val dynamicRangeStops: Float,
        val noiseEstimate: Float,
        val crc32: Long,
        val compandingType: Int,
        val sensorWhiteLevel: Int,
        /** Reconstructed sensor-domain samples (companding inverted when applicable). */
        val samples: IntArray
    )

    class NprawFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ── Internal payload-shaping types (moved verbatim from RawVideoManager) ─────

    private data class RawFrameStats(
        val blackLevel: Int,
        val whiteLevel: Int,
        val dynamicRangeStops: Float,
        val noiseEstimate: Float
    )

    private data class CompressionProfile(
        val targetBitDepth: Int,
        val deflaterLevel: Int,
        val usePredictive: Boolean,
        val useAdaptiveQuantization: Boolean
    )

    // ─────────────────────────────────────────────────────────────────────────
    // ENCODE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Serialise a sensor frame into an NPRAW container.
     *
     * @param rawSamples       sensor-domain samples (0..sensorWhiteLevel), row-major.
     * @param sensorWhiteLevel `SENSOR_INFO_WHITE_LEVEL`; drives companding/native-depth.
     * @param preserveNativeDepth device-gated; keep the sensor word at native depth.
     */
    fun encodeFrame(
        mode: RawCompressionMode,
        rawSamples: IntArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        timestamp: Long,
        originalSize: Int,
        sensorWhiteLevel: Int,
        preserveNativeDepth: Boolean = false
    ): EncodeResult {
        val profile = resolveCompressionProfile(mode, sensorWhiteLevel, preserveNativeDepth)
        val sourceStats = analyzeRawFrame(rawSamples)

        // CP16: the companding curve (monotone, invertible) is the SOLE bit-depth reducer,
        // replacing the legacy linear right-shift.
        val curve = RawCompandingCurve.of(sensorWhiteLevel, profile.targetBitDepth)

        // Adaptive (lossy) pre-quantisation is a compression aid for the visually-lossless
        // tiers — but ONLY when a genuine reduction is happening. When the sensor word already
        // fits the container (companding is identity) it would be pure, gratuitous shadow
        // destruction (snapping deep-shadow codes toward zero) for zero size benefit, so it is
        // skipped. The lossless tiers never request it.
        val quantized = if (profile.useAdaptiveQuantization && !curve.isIdentity) {
            applyAdaptiveQuantization(rawSamples, profile, sourceStats)
        } else {
            rawSamples
        }

        val processedSamples = curve.encodeAll(quantized)
        val compandingType = curve.type

        val payloadStats = analyzeRawFrame(processedSamples)
        val encoded = if (profile.usePredictive) {
            predictiveEncode(processedSamples, width, height, profile.targetBitDepth)
        } else {
            packSamples(processedSamples, profile.targetBitDepth)
        }
        val payload = if (profile.deflaterLevel == Deflater.NO_COMPRESSION) {
            encoded
        } else {
            compressBytes(encoded, profile.deflaterLevel)
        }
        val crc = CRC32().apply { update(payload) }.value
        val version = if (compandingType == RawCompandingCurve.COMPANDING_NONE) {
            VERSION_LINEAR
        } else {
            VERSION_COMPANDED
        }

        val output = ByteArrayOutputStream(payload.size + 144)
        DataOutputStream(output).use { out ->
            out.write(MAGIC)
            out.writeByte(version)
            out.writeInt(mode.ordinal)
            out.writeInt(width)
            out.writeInt(height)
            out.writeInt(rowStride)
            out.writeInt(pixelStride)
            out.writeLong(timestamp)
            out.writeInt(originalSize)
            out.writeInt(payload.size)
            out.writeInt(profile.targetBitDepth)
            out.writeInt(payloadStats.blackLevel)
            out.writeInt(payloadStats.whiteLevel)
            out.writeFloat(payloadStats.dynamicRangeStops)
            out.writeFloat(payloadStats.noiseEstimate)
            out.writeLong(crc)
            if (version >= VERSION_COMPANDED) {
                out.writeInt(compandingType)
                out.writeInt(sensorWhiteLevel.coerceIn(1, 0xFFFF))
            }
            out.write(payload)
            out.flush()
        }
        val bytes = output.toByteArray()
        val ratio = if (payload.isNotEmpty()) {
            originalSize.toFloat() / payload.size.toFloat()
        } else {
            1f
        }
        return EncodeResult(
            bytes = bytes,
            version = version,
            bitDepth = profile.targetBitDepth,
            compandingType = compandingType,
            payloadSize = payload.size,
            compressionRatio = ratio
        )
    }

    /**
     * Single-pass encoder for the realtime 16-bit RAW-video master.
     *
     * The camera RAW_SENSOR plane is already a little-endian 16-bit Bayer raster. This path copies
     * only the active pixels into one NPRAW v2 container while calculating its statistics, then CRCs
     * the packed payload. It never builds an IntArray, predicts, quantizes, deflates, or creates an
     * individual frame file. The decoder uses the ordinary non-predictive 16-bit NPRAW path, so the
     * reconstructed sensor codes are bit-exact.
     */
    fun encodeRealtimeLinear16Frame(
        rawPlane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        timestamp: Long,
        originalSize: Int
    ): EncodeResult {
        require(width > 0 && height > 0) { "RAW geometry must be positive" }
        require(width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
            "RAW geometry ${width}x${height} exceeds the NPRAW limit"
        }
        require(rowStride > 0 && pixelStride >= 2) { "RAW plane strides are invalid" }
        val sampleCountLong = width.toLong() * height.toLong()
        require(sampleCountLong <= MAX_SAMPLES) { "RAW geometry ${width}x${height} is too large" }
        val sampleCount = sampleCountLong.toInt()
        val payloadSize = Math.multiplyExact(sampleCount, 2)
        val headerSize = HEADER_PREFIX_BYTES + HEADER_SUFFIX_V2_BYTES
        val container = ByteArray(Math.addExact(headerSize, payloadSize))
        val source = rawPlane.duplicate()
        val sampleStep = maxOf(1, sampleCount / 4096)
        var minValue = Int.MAX_VALUE
        var maxValue = Int.MIN_VALUE
        var previousSampled = 0
        var diffAccum = 0L
        var diffCount = 0
        var sampleIndex = 0
        copyRawPlaneToLinear16Payload(
            source = source,
            destination = container,
            destinationOffset = headerSize,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride
        )
        var payloadOffset = headerSize
        while (sampleIndex < sampleCount) {
            val sample = (container[payloadOffset].toInt() and 0xFF) or
                ((container[payloadOffset + 1].toInt() and 0xFF) shl 8)
            payloadOffset += 2
            if (sample < minValue) minValue = sample
            if (sample > maxValue) maxValue = sample
            if (sampleIndex == 0) {
                previousSampled = sample
            } else if (sampleIndex % sampleStep == 0) {
                diffAccum += kotlin.math.abs(sample - previousSampled).toLong()
                diffCount++
                previousSampled = sample
            }
            sampleIndex++
        }

        val blackLevel = minValue.coerceIn(0, 0xFFFF)
        val whiteLevel = maxValue.coerceIn(blackLevel, 0xFFFF)
        val dynamicRangeStops = if (whiteLevel <= blackLevel) {
            0f
        } else {
            (
                kotlin.math.ln((whiteLevel + 1.0) / (blackLevel + 1.0)) /
                    kotlin.math.ln(2.0)
                ).toFloat().coerceAtLeast(0f)
        }
        val noiseEstimate = if (diffCount > 0) {
            (diffAccum.toFloat() / diffCount.toFloat() / 65535f).coerceIn(0f, 1f)
        } else {
            0f
        }
        val crc = CRC32().apply { update(container, headerSize, payloadSize) }.value
        ByteBuffer.wrap(container).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC)
            put(VERSION_LINEAR.toByte())
            putInt(RawCompressionMode.LOSSLESS_REALTIME.ordinal)
            putInt(width)
            putInt(height)
            putInt(rowStride)
            putInt(pixelStride)
            putLong(timestamp)
            putInt(originalSize)
            putInt(payloadSize)
            putInt(16)
            putInt(blackLevel)
            putInt(whiteLevel)
            putFloat(dynamicRangeStops)
            putFloat(noiseEstimate)
            putLong(crc)
        }
        return EncodeResult(
            bytes = container,
            version = VERSION_LINEAR,
            bitDepth = 16,
            compandingType = RawCompandingCurve.COMPANDING_NONE,
            payloadSize = payloadSize,
            compressionRatio = if (payloadSize > 0) originalSize.toFloat() / payloadSize else 1f
        )
    }

    /** Exact packet size for the uncompressed 16-bit realtime NPRAW layout. */
    fun realtimeLinear16PacketSize(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "RAW geometry must be positive" }
        val sampleCount = Math.multiplyExact(width, height)
        val payloadSize = Math.multiplyExact(sampleCount, 2)
        return Math.addExact(HEADER_PREFIX_BYTES + HEADER_SUFFIX_V2_BYTES, payloadSize)
    }

    /**
     * Storage word used by the realtime lossless master. A <=12-bit sensor is packed into 12 bits;
     * wider sensors retain the existing 16-bit container. This changes container padding only: every
     * sensor code remains exact and the decoder reconstructs the original Bayer raster bit-for-bit.
     */
    fun realtimeLosslessStorageBitDepth(sensorWhiteLevel: Int): Int =
        if (sensorWhiteLevel in 1..0x0FFF) 12 else 16

    /** Number of meaningful sensor-code bits declared by the HAL white level. */
    fun realtimeSensorCodeBitDepth(sensorWhiteLevel: Int): Int {
        require(sensorWhiteLevel in 1..0xFFFF) { "Sensor white level is out of range" }
        return Int.SIZE_BITS - Integer.numberOfLeadingZeros(sensorWhiteLevel)
    }

    /** Exact packet size for the sensor-lossless realtime layout selected from its white level. */
    fun realtimeLosslessPacketSize(width: Int, height: Int, sensorWhiteLevel: Int): Int {
        require(width > 0 && height > 0) { "RAW geometry must be positive" }
        val sampleCount = Math.multiplyExact(width, height)
        val payloadSize = if (realtimeLosslessStorageBitDepth(sensorWhiteLevel) == 12) {
            Math.addExact(Math.multiplyExact(sampleCount, 3), 1) / 2
        } else {
            Math.multiplyExact(sampleCount, 2)
        }
        return Math.addExact(HEADER_PREFIX_BYTES + HEADER_SUFFIX_V2_BYTES, payloadSize)
    }

    /**
     * Worst-case capacity for the bit-exact block-range packet. Ordinary scenes use fewer bits per
     * tile; this value exists only to reserve a bounded direct buffer before camera admission opens.
     */
    fun realtimeBlockRangePacketCapacity(width: Int, height: Int, sensorWhiteLevel: Int): Int {
        require(width > 0 && height > 0) { "RAW geometry must be positive" }
        val sampleCount = Math.multiplyExact(width.toLong(), height.toLong())
        require(sampleCount <= MAX_SAMPLES) { "RAW geometry ${width}x${height} is too large" }
        val sensorBits = realtimeSensorCodeBitDepth(sensorWhiteLevel)
        require(sensorBits <= 12) {
            "Block-range realtime packing supports sensor code depths up to 12 bits"
        }
        val tileColumns = (width.toLong() + BLOCK_RANGE_TILE_WIDTH - 1L) / BLOCK_RANGE_TILE_WIDTH
        val tileRows = (height.toLong() + BLOCK_RANGE_TILE_HEIGHT - 1L) / BLOCK_RANGE_TILE_HEIGHT
        val tileCount = Math.multiplyExact(tileColumns, tileRows)
        val packedBits = Math.multiplyExact(sampleCount, sensorBits.toLong())
        val packedBytes = Math.addExact(packedBits, 7L) / 8L
        val payloadCapacity = Math.addExact(
            BLOCK_RANGE_PAYLOAD_HEADER_BYTES.toLong(),
            Math.addExact(
                Math.multiplyExact(tileCount, (BLOCK_RANGE_TILE_HEADER_BYTES + 1).toLong()),
                packedBytes
            )
        )
        val packetCapacity = Math.addExact(
            (HEADER_PREFIX_BYTES + HEADER_SUFFIX_V2_BYTES + HEADER_SUFFIX_V3_EXTRA_BYTES).toLong(),
            payloadCapacity
        )
        require(packetCapacity <= Int.MAX_VALUE.toLong()) { "RAW packet capacity is too large" }
        return packetCapacity.toInt()
    }

    /** Allocation bound that preserves room for the established packed-12 fallback. */
    fun realtimeLosslessPacketBufferCapacity(width: Int, height: Int, sensorWhiteLevel: Int): Int =
        if (realtimeLosslessStorageBitDepth(sensorWhiteLevel) == 12) {
            maxOf(
                realtimeLosslessPacketSize(width, height, sensorWhiteLevel),
                realtimeBlockRangePacketCapacity(width, height, sensorWhiteLevel)
            )
        } else {
            realtimeLosslessPacketSize(width, height, sensorWhiteLevel)
        }

    /**
     * Build a byte-identical NPRAW v2 header while leaving a contiguous RAW16 camera plane in place.
     * This is the preferred realtime path: the 25 MB sensor payload is analyzed once, then written
     * directly from its Image-owned buffer. Padded/interleaved planes return null and retain the
     * existing exact packing path.
     */
    fun encodeRealtimeLinear16BorrowedFrame(
        rawPlane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        timestamp: Long,
        originalSize: Int
    ): BorrowedDirectEncodeResult? {
        require(width > 0 && height > 0) { "RAW geometry must be positive" }
        require(width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
            "RAW geometry ${width}x${height} exceeds the NPRAW limit"
        }
        require(rowStride > 0 && pixelStride >= 2) { "RAW plane strides are invalid" }
        val sampleCountLong = width.toLong() * height.toLong()
        require(sampleCountLong <= MAX_SAMPLES) { "RAW geometry ${width}x${height} is too large" }
        val payloadSize = Math.multiplyExact(sampleCountLong.toInt(), 2)
        if (pixelStride != 2 || rowStride.toLong() != width.toLong() * 2L ||
            !rawPlane.isDirect || rawPlane.limit() < payloadSize
        ) return null

        val stats = NprawRealtimeNative.analyzeContiguousLinear16Payload(
            source = rawPlane,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride
        ) ?: return null
        val blackLevel = stats.minimum.coerceIn(0, 0xFFFF)
        val whiteLevel = stats.maximum.coerceIn(blackLevel, 0xFFFF)
        val dynamicRangeStops = if (whiteLevel <= blackLevel) {
            0f
        } else {
            (
                kotlin.math.ln((whiteLevel + 1.0) / (blackLevel + 1.0)) /
                    kotlin.math.ln(2.0)
                ).toFloat().coerceAtLeast(0f)
        }
        val noiseEstimate = if (stats.sampledDifferenceCount > 0) {
            (
                stats.sampledDifferenceSum.toFloat() /
                    stats.sampledDifferenceCount.toFloat() /
                    65535f
                ).coerceIn(0f, 1f)
        } else {
            0f
        }
        val header = ByteArray(HEADER_PREFIX_BYTES + HEADER_SUFFIX_V2_BYTES)
        ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC)
            put(VERSION_LINEAR.toByte())
            putInt(RawCompressionMode.LOSSLESS_REALTIME.ordinal)
            putInt(width)
            putInt(height)
            putInt(rowStride)
            putInt(pixelStride)
            putLong(timestamp)
            putInt(originalSize)
            putInt(payloadSize)
            putInt(16)
            putInt(blackLevel)
            putInt(whiteLevel)
            putFloat(dynamicRangeStops)
            putFloat(noiseEstimate)
            putLong(stats.crc32 and 0xFFFF_FFFFL)
        }
        return BorrowedDirectEncodeResult(
            header = header,
            packetSize = Math.addExact(header.size, payloadSize),
            version = VERSION_LINEAR,
            bitDepth = 16,
            compandingType = RawCompandingCurve.COMPANDING_NONE,
            payloadSize = payloadSize,
            compressionRatio = if (payloadSize > 0) originalSize.toFloat() / payloadSize else 1f
        )
    }

    /**
     * Native zero-copy variant used by the realtime recorder. The packet is written directly into an
     * engine-owned direct buffer, eliminating both the per-frame 25 MB heap allocation and the subsequent
     * heap-to-direct copy. Header math remains in Kotlin so bytes are identical to
     * [encodeRealtimeLinear16Frame]. Returns null when the native bridge is unavailable; callers then use
     * the ordinary Kotlin encoder without changing the container or sensor precision.
     */
    fun encodeRealtimeLinear16FrameInto(
        rawPlane: ByteBuffer,
        destination: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        timestamp: Long,
        originalSize: Int
    ): DirectEncodeResult? {
        require(width > 0 && height > 0) { "RAW geometry must be positive" }
        require(width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
            "RAW geometry ${width}x${height} exceeds the NPRAW limit"
        }
        require(rowStride > 0 && pixelStride >= 2) { "RAW plane strides are invalid" }
        val sampleCountLong = width.toLong() * height.toLong()
        require(sampleCountLong <= MAX_SAMPLES) { "RAW geometry ${width}x${height} is too large" }
        val payloadSize = Math.multiplyExact(sampleCountLong.toInt(), 2)
        val headerSize = HEADER_PREFIX_BYTES + HEADER_SUFFIX_V2_BYTES
        val packetSize = Math.addExact(headerSize, payloadSize)
        require(destination.isDirect && destination.capacity() >= packetSize) {
            "Direct NPRAW destination is too small"
        }

        val stats = NprawRealtimeNative.packLinear16Payload(
            source = rawPlane,
            destination = destination,
            destinationOffset = headerSize,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride
        ) ?: return null
        val blackLevel = stats.minimum.coerceIn(0, 0xFFFF)
        val whiteLevel = stats.maximum.coerceIn(blackLevel, 0xFFFF)
        val dynamicRangeStops = if (whiteLevel <= blackLevel) {
            0f
        } else {
            (
                kotlin.math.ln((whiteLevel + 1.0) / (blackLevel + 1.0)) /
                    kotlin.math.ln(2.0)
                ).toFloat().coerceAtLeast(0f)
        }
        val noiseEstimate = if (stats.sampledDifferenceCount > 0) {
            (
                stats.sampledDifferenceSum.toFloat() /
                    stats.sampledDifferenceCount.toFloat() /
                    65535f
                ).coerceIn(0f, 1f)
        } else {
            0f
        }

        destination.duplicate().order(ByteOrder.BIG_ENDIAN).apply {
            clear()
            put(MAGIC)
            put(VERSION_LINEAR.toByte())
            putInt(RawCompressionMode.LOSSLESS_REALTIME.ordinal)
            putInt(width)
            putInt(height)
            putInt(rowStride)
            putInt(pixelStride)
            putLong(timestamp)
            putInt(originalSize)
            putInt(payloadSize)
            putInt(16)
            putInt(blackLevel)
            putInt(whiteLevel)
            putFloat(dynamicRangeStops)
            putFloat(noiseEstimate)
            putLong(stats.crc32 and 0xFFFF_FFFFL)
        }
        destination.position(0)
        destination.limit(packetSize)
        return DirectEncodeResult(
            packetSize = packetSize,
            version = VERSION_LINEAR,
            bitDepth = 16,
            compandingType = RawCompandingCurve.COMPANDING_NONE,
            payloadSize = payloadSize,
            compressionRatio = if (payloadSize > 0) originalSize.toFloat() / payloadSize else 1f
        )
    }

    /**
     * Native, allocation-free realtime encoder for a sensor whose complete code range fits in a
     * 12-bit container. The native packer validates every source code before accepting the packet,
     * so an incorrect HAL white-level declaration fails closed instead of losing precision.
     */
    fun encodeRealtimePacked12FrameInto(
        rawPlane: ByteBuffer,
        destination: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        timestamp: Long,
        originalSize: Int,
        sensorWhiteLevel: Int
    ): DirectEncodeResult? {
        require(width > 0 && height > 0) { "RAW geometry must be positive" }
        require(width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
            "RAW geometry ${width}x${height} exceeds the NPRAW limit"
        }
        require(rowStride > 0 && pixelStride >= 2) { "RAW plane strides are invalid" }
        require(realtimeLosslessStorageBitDepth(sensorWhiteLevel) == 12) {
            "Sensor white level $sensorWhiteLevel does not fit the lossless packed-12 route"
        }
        val sampleCountLong = width.toLong() * height.toLong()
        require(sampleCountLong <= MAX_SAMPLES) { "RAW geometry ${width}x${height} is too large" }
        val sampleCount = sampleCountLong.toInt()
        val payloadSize = Math.addExact(Math.multiplyExact(sampleCount, 3), 1) / 2
        val headerSize = HEADER_PREFIX_BYTES + HEADER_SUFFIX_V2_BYTES
        val packetSize = Math.addExact(headerSize, payloadSize)
        require(destination.isDirect && destination.capacity() >= packetSize) {
            "Direct packed-12 NPRAW destination is too small"
        }

        val stats = NprawRealtimeNative.packLinear12Payload(
            source = rawPlane,
            destination = destination,
            destinationOffset = headerSize,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride
        ) ?: return null
        val blackLevel = stats.minimum.coerceIn(0, sensorWhiteLevel)
        val whiteLevel = stats.maximum.coerceIn(blackLevel, sensorWhiteLevel)
        val dynamicRangeStops = if (whiteLevel <= blackLevel) {
            0f
        } else {
            (
                kotlin.math.ln((whiteLevel + 1.0) / (blackLevel + 1.0)) /
                    kotlin.math.ln(2.0)
                ).toFloat().coerceAtLeast(0f)
        }
        val noiseEstimate = if (stats.sampledDifferenceCount > 0) {
            (
                stats.sampledDifferenceSum.toFloat() /
                    stats.sampledDifferenceCount.toFloat() /
                    65535f
                ).coerceIn(0f, 1f)
        } else {
            0f
        }

        destination.duplicate().order(ByteOrder.BIG_ENDIAN).apply {
            clear()
            put(MAGIC)
            put(VERSION_LINEAR.toByte())
            putInt(RawCompressionMode.LOSSLESS_REALTIME.ordinal)
            putInt(width)
            putInt(height)
            putInt(rowStride)
            putInt(pixelStride)
            putLong(timestamp)
            putInt(originalSize)
            putInt(payloadSize)
            putInt(12)
            putInt(blackLevel)
            putInt(whiteLevel)
            putFloat(dynamicRangeStops)
            putFloat(noiseEstimate)
            putLong(stats.crc32 and 0xFFFF_FFFFL)
        }
        destination.position(0)
        destination.limit(packetSize)
        return DirectEncodeResult(
            packetSize = packetSize,
            version = VERSION_LINEAR,
            bitDepth = 12,
            compandingType = RawCompandingCurve.COMPANDING_NONE,
            payloadSize = payloadSize,
            compressionRatio = if (payloadSize > 0) originalSize.toFloat() / payloadSize else 1f
        )
    }

    /**
     * Allocation-free native encoder for the thermally resilient, bit-exact block-range layout.
     * Every tile is independently reconstructable and CRC-protected at the frame level.
     */
    fun encodeRealtimeBlockRangeFrameInto(
        rawPlane: ByteBuffer,
        destination: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        timestamp: Long,
        originalSize: Int,
        sensorWhiteLevel: Int
    ): DirectEncodeResult? {
        require(width > 0 && height > 0) { "RAW geometry must be positive" }
        require(width <= MAX_DIMENSION && height <= MAX_DIMENSION) {
            "RAW geometry ${width}x${height} exceeds the NPRAW limit"
        }
        require(rowStride > 0 && pixelStride >= 2) { "RAW plane strides are invalid" }
        val sensorBits = realtimeSensorCodeBitDepth(sensorWhiteLevel)
        require(sensorBits <= 12) {
            "Sensor white level $sensorWhiteLevel does not fit the block-range realtime route"
        }
        val packetCapacity = realtimeBlockRangePacketCapacity(width, height, sensorWhiteLevel)
        require(destination.isDirect && destination.capacity() >= packetCapacity) {
            "Direct block-range NPRAW destination is too small"
        }
        val headerSize = HEADER_PREFIX_BYTES + HEADER_SUFFIX_V2_BYTES + HEADER_SUFFIX_V3_EXTRA_BYTES
        val stats = NprawRealtimeNative.packBlockRangePayload(
            source = rawPlane,
            destination = destination,
            destinationOffset = headerSize,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            sensorWhiteLevel = sensorWhiteLevel
        ) ?: return null
        val payloadSize = stats.payloadBytes
        val packetSize = Math.addExact(headerSize, payloadSize)
        if (packetSize > destination.capacity()) return null
        val blackLevel = stats.minimum.coerceIn(0, sensorWhiteLevel)
        val whiteLevel = stats.maximum.coerceIn(blackLevel, sensorWhiteLevel)
        val dynamicRangeStops = if (whiteLevel <= blackLevel) {
            0f
        } else {
            (
                kotlin.math.ln((whiteLevel + 1.0) / (blackLevel + 1.0)) /
                    kotlin.math.ln(2.0)
                ).toFloat().coerceAtLeast(0f)
        }
        val noiseEstimate = if (stats.sampledDifferenceCount > 0) {
            (
                stats.sampledDifferenceSum.toFloat() /
                    stats.sampledDifferenceCount.toFloat() /
                    65535f
                ).coerceIn(0f, 1f)
        } else {
            0f
        }

        destination.duplicate().order(ByteOrder.BIG_ENDIAN).apply {
            clear()
            put(MAGIC)
            put(VERSION_BLOCK_RANGE_LOSSLESS.toByte())
            putInt(RawCompressionMode.LOSSLESS_REALTIME.ordinal)
            putInt(width)
            putInt(height)
            putInt(rowStride)
            putInt(pixelStride)
            putLong(timestamp)
            putInt(originalSize)
            putInt(payloadSize)
            putInt(sensorBits)
            putInt(blackLevel)
            putInt(whiteLevel)
            putFloat(dynamicRangeStops)
            putFloat(noiseEstimate)
            putLong(stats.crc32 and 0xFFFF_FFFFL)
            putInt(RawCompandingCurve.COMPANDING_NONE)
            putInt(sensorWhiteLevel)
        }
        destination.position(0)
        destination.limit(packetSize)
        return DirectEncodeResult(
            packetSize = packetSize,
            version = VERSION_BLOCK_RANGE_LOSSLESS,
            bitDepth = sensorBits,
            compandingType = RawCompandingCurve.COMPANDING_NONE,
            payloadSize = payloadSize,
            compressionRatio = if (payloadSize > 0) originalSize.toFloat() / payloadSize else 1f
        )
    }

    /**
     * Copy a RAW gralloc plane without issuing one JNI/direct-buffer access per byte. The final bytes of
     * the mapped direct buffer deliberately remain scalar: the Vivo I2405 driver has previously faulted
     * when a bulk primitive-array copy ended exactly at the gralloc mapping boundary. Keeping a full
     * cache line before that boundary prevents a vectorized memcpy from touching the next unmapped page.
     */
    private fun copyRawPlaneToLinear16Payload(
        source: ByteBuffer,
        destination: ByteArray,
        destinationOffset: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ) {
        val sourceLimit = source.limit()
        val scalarTailStart = (sourceLimit - DIRECT_BUFFER_SCALAR_TAIL_BYTES).coerceAtLeast(0)
        var destinationRow = destinationOffset
        for (y in 0 until height) {
            val rowStartLong = y.toLong() * rowStride.toLong()
            if (rowStartLong < 0L || rowStartLong >= sourceLimit.toLong()) {
                destinationRow += width * 2
                continue
            }
            val rowStart = rowStartLong.toInt()
            if (pixelStride == 2) {
                val requestedEnd = (rowStartLong + width.toLong() * 2L)
                    .coerceAtMost(sourceLimit.toLong())
                    .toInt()
                val bulkEnd = requestedEnd.coerceAtMost(scalarTailStart)
                if (bulkEnd > rowStart) {
                    val bulk = source.duplicate()
                    bulk.position(rowStart)
                    bulk.limit(bulkEnd)
                    bulk.get(destination, destinationRow, bulkEnd - rowStart)
                }
                var sourceIndex = maxOf(rowStart, bulkEnd)
                var destinationIndex = destinationRow + (bulkEnd - rowStart)
                if (sourceIndex > bulkEnd) destinationIndex = destinationRow
                while (sourceIndex < requestedEnd) {
                    destination[destinationIndex++] = source.get(sourceIndex++)
                }
            } else {
                for (x in 0 until width) {
                    val sourceIndexLong = rowStartLong + x.toLong() * pixelStride.toLong()
                    if (sourceIndexLong >= 0L && sourceIndexLong + 1L < sourceLimit.toLong()) {
                        val sourceIndex = sourceIndexLong.toInt()
                        val destinationIndex = destinationRow + x * 2
                        destination[destinationIndex] = source.get(sourceIndex)
                        destination[destinationIndex + 1] = source.get(sourceIndex + 1)
                    }
                }
            }
            destinationRow += width * 2
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DECODE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse and fully reconstruct an NPRAW frame. Throws [NprawFormatException] on a
     * bad magic, a truncated/short frame, or a CRC mismatch — a corrupt frame is never
     * silently returned.
     */
    // ── Streaming frame reader (stream.npraw = concatenated frames) ──────────────
    // Byte offsets are FROZEN to the decodeFrame() field order above; readFrame only measures the
    // frame length here and delegates the actual field parse + CRC check to decodeFrame(), so the
    // format stays single-sourced.
    private const val HEADER_PREFIX_BYTES = 42   // magic5+ver1+mode4+w4+h4+rowStride4+pixelStride4+ts8+originalSize4+compressedSize4
    private const val VERSION_BYTE_OFFSET = 5
    private const val COMPRESSED_SIZE_OFFSET = 38
    private const val HEADER_SUFFIX_V2_BYTES = 28 // bitDepth4+black4+white4+dyn4+noise4+crc8
    private const val HEADER_SUFFIX_V3_EXTRA_BYTES = 8 // compandingType4+sensorWhiteLevel4
    private const val STREAM_SKIP_SCRATCH_BYTES = 64 * 1024

    /** Return the first packet version without allocating or reading its multi-megabyte payload. */
    internal fun firstFrameVersion(input: java.io.InputStream): Int? {
        val signature = ByteArray(MAGIC.size + 1)
        val first = input.read()
        if (first < 0) return null
        signature[0] = first.toByte()
        readFullyOrThrow(input, signature, 1, "NPRAW signature")
        for (index in MAGIC.indices) {
            if (signature[index] != MAGIC[index]) {
                throw NprawFormatException("Not an NPRAW frame (bad magic)")
            }
        }
        return (signature[VERSION_BYTE_OFFSET].toInt() and 0xFF).also { version ->
            if (version != VERSION_LINEAR && version != VERSION_COMPANDED &&
                version != VERSION_BLOCK_RANGE_LOSSLESS
            ) {
                throw NprawFormatException("Unsupported NPRAW version $version")
            }
        }
    }

    /**
     * Reusable packet walker for playback deadline catch-up. It validates every skipped frame header
     * but never allocates or inflates that frame's potentially multi-megabyte payload.
     */
    internal class StreamFrameSkipper {
        private val headerPrefix = ByteArray(HEADER_PREFIX_BYTES)
        private val skipScratch = ByteArray(STREAM_SKIP_SCRATCH_BYTES)

        fun skipNext(input: java.io.InputStream): Boolean {
            val bodyBytes = readFrameBodySizeOrNull(input, headerPrefix) ?: return false
            skipFullyOrThrow(
                input = input,
                n = bodyBytes.toLong(),
                what = "NPRAW frame body",
                scratch = skipScratch
            )
            return true
        }
    }

    /**
     * Playback-only streaming decoder for the v4 block-range layout.
     *
     * The master payload is still read in full and covered by its frame CRC, but only the Bayer
     * coordinates selected by the caller are reconstructed. Tile storage is bounded to 384 bytes,
     * so a 12.6 MP frame does not create a payload copy or a sensor-sized [IntArray]. The selected
     * sensor codes are bit-exact; this class performs no colour, denoise, or conversion work.
     */
    internal class BlockRangePlaybackDecoder(
        private val sourceWidth: Int,
        private val sourceHeight: Int,
        private val reducedWidth: Int,
        private val reducedHeight: Int,
        selectedSourceSampleIndices: IntArray
    ) {
        private val frameHeader = ByteArray(
            HEADER_PREFIX_BYTES + HEADER_SUFFIX_V2_BYTES + HEADER_SUFFIX_V3_EXTRA_BYTES
        )
        private val frameHeaderView = ByteBuffer.wrap(frameHeader).order(ByteOrder.BIG_ENDIAN)
        private val payloadHeader = ByteArray(BLOCK_RANGE_PAYLOAD_HEADER_BYTES)
        private val tileHeader = ByteArray(BLOCK_RANGE_TILE_HEADER_BYTES)
        private val tilePayload = ByteArray(
            (BLOCK_RANGE_TILE_WIDTH * BLOCK_RANGE_TILE_HEIGHT * 12 + 7) / 8
        )
        private val payloadReadBuffer = ByteArray(BLOCK_RANGE_PLAYBACK_READ_BYTES)
        private val payloadCrc = CRC32()
        private val selectedSourceXs: IntArray
        private val sourceToReducedY: IntArray
        private val selectedXStartByTileColumn: IntArray
        private val selectedXEndByTileColumn: IntArray
        private val tileColumns: Int
        private val tileRows: Int
        private var payloadBytesRemaining = 0
        private var payloadInputBytesRemaining = 0
        private var payloadReadPosition = 0
        private var payloadReadLimit = 0

        init {
            require(sourceWidth in 1..MAX_DIMENSION && sourceHeight in 1..MAX_DIMENSION) {
                "RAW playback geometry is invalid"
            }
            require(reducedWidth > 0 && reducedHeight > 0) {
                "Reduced RAW playback geometry must be positive"
            }
            val sourceSamples = Math.multiplyExact(sourceWidth, sourceHeight)
            val reducedSamples = Math.multiplyExact(reducedWidth, reducedHeight)
            require(selectedSourceSampleIndices.size == reducedSamples) {
                "RAW playback selection does not match reduced geometry"
            }

            selectedSourceXs = IntArray(reducedWidth)
            sourceToReducedY = IntArray(sourceHeight) { -1 }
            var previousSourceY = -1
            for (reducedY in 0 until reducedHeight) {
                val rowOffset = reducedY * reducedWidth
                val firstIndex = selectedSourceSampleIndices[rowOffset]
                require(firstIndex in 0 until sourceSamples) {
                    "RAW playback selection is outside the source raster"
                }
                val sourceY = firstIndex / sourceWidth
                require(sourceY > previousSourceY) {
                    "RAW playback rows must be strictly increasing"
                }
                sourceToReducedY[sourceY] = reducedY
                previousSourceY = sourceY
                var previousSourceX = -1
                for (reducedX in 0 until reducedWidth) {
                    val sourceIndex = selectedSourceSampleIndices[rowOffset + reducedX]
                    require(sourceIndex in 0 until sourceSamples && sourceIndex / sourceWidth == sourceY) {
                        "RAW playback selection must contain complete source rows"
                    }
                    val sourceX = sourceIndex % sourceWidth
                    require(sourceX > previousSourceX) {
                        "RAW playback columns must be strictly increasing"
                    }
                    if (reducedY == 0) {
                        selectedSourceXs[reducedX] = sourceX
                    } else {
                        require(selectedSourceXs[reducedX] == sourceX) {
                            "RAW playback selection must use the same columns on every row"
                        }
                    }
                    previousSourceX = sourceX
                }
            }

            tileColumns = (sourceWidth + BLOCK_RANGE_TILE_WIDTH - 1) / BLOCK_RANGE_TILE_WIDTH
            tileRows = (sourceHeight + BLOCK_RANGE_TILE_HEIGHT - 1) / BLOCK_RANGE_TILE_HEIGHT
            selectedXStartByTileColumn = IntArray(tileColumns)
            selectedXEndByTileColumn = IntArray(tileColumns)
            var selectedX = 0
            for (tileColumn in 0 until tileColumns) {
                val tileStartX = tileColumn * BLOCK_RANGE_TILE_WIDTH
                val tileEndX = minOf(sourceWidth, tileStartX + BLOCK_RANGE_TILE_WIDTH)
                while (selectedX < selectedSourceXs.size && selectedSourceXs[selectedX] < tileStartX) {
                    selectedX += 1
                }
                selectedXStartByTileColumn[tileColumn] = selectedX
                while (selectedX < selectedSourceXs.size && selectedSourceXs[selectedX] < tileEndX) {
                    selectedX += 1
                }
                selectedXEndByTileColumn[tileColumn] = selectedX
            }
            require(selectedX == selectedSourceXs.size) {
                "RAW playback selection contains an unmapped source column"
            }
        }

        /** Read one frame into [destination], returning false only at a clean packet boundary EOF. */
        fun readNext(input: java.io.InputStream, destination: IntArray): Boolean {
            require(destination.size == Math.multiplyExact(reducedWidth, reducedHeight)) {
                "Reduced RAW playback destination has the wrong size"
            }
            val first = input.read()
            if (first < 0) return false
            frameHeader[0] = first.toByte()
            readFullyOrThrow(input, frameHeader, 1, "NPRAW v4 playback header")
            for (index in MAGIC.indices) {
                if (frameHeader[index] != MAGIC[index]) {
                    throw NprawFormatException("Not an NPRAW frame (bad magic)")
                }
            }

            val version = frameHeader[VERSION_BYTE_OFFSET].toInt() and 0xFF
            val modeOrdinal = frameHeaderView.getInt(MAGIC.size + 1)
            val width = frameHeaderView.getInt(MAGIC.size + 1 + Int.SIZE_BYTES)
            val height = frameHeaderView.getInt(MAGIC.size + 1 + Int.SIZE_BYTES * 2)
            val payloadBytes = frameHeaderView.getInt(COMPRESSED_SIZE_OFFSET)
            val sensorBitDepth = frameHeaderView.getInt(42)
            val expectedCrc = frameHeaderView.getLong(62)
            val compandingType = frameHeaderView.getInt(70)
            val sensorWhiteLevel = frameHeaderView.getInt(74)
            if (version != VERSION_BLOCK_RANGE_LOSSLESS ||
                modeOrdinal != RawCompressionMode.LOSSLESS_REALTIME.ordinal ||
                width != sourceWidth || height != sourceHeight ||
                compandingType != RawCompandingCurve.COMPANDING_NONE
            ) {
                throw NprawFormatException(
                    "NPRAW v4 playback packet does not match its immutable RAW metadata"
                )
            }
            if (payloadBytes < BLOCK_RANGE_PAYLOAD_HEADER_BYTES || payloadBytes.toLong() > MAX_SAMPLES) {
                throw NprawFormatException("NPRAW frame declares implausible payload size $payloadBytes")
            }
            if (sensorBitDepth !in 1..12 || sensorWhiteLevel !in 1..0x0FFF) {
                throw NprawFormatException(
                    "Invalid block-range sensor domain bits=$sensorBitDepth white=$sensorWhiteLevel"
                )
            }

            beginPayload(payloadBytes)
            readPayload(input, payloadHeader, payloadHeader.size, "block-range payload header")
            for (index in BLOCK_RANGE_MAGIC.indices) {
                if (payloadHeader[index] != BLOCK_RANGE_MAGIC[index]) {
                    throw NprawFormatException("Block-range NPRAW payload has bad magic")
                }
            }
            val tileWidth = readUnsignedShortLe(payloadHeader, 4)
            val tileHeight = readUnsignedShortLe(payloadHeader, 6)
            val declaredTileCount = readUnsignedIntLe(payloadHeader, 8)
            val expectedTileCount = Math.multiplyExact(tileColumns.toLong(), tileRows.toLong())
            if (tileWidth != BLOCK_RANGE_TILE_WIDTH || tileHeight != BLOCK_RANGE_TILE_HEIGHT) {
                throw NprawFormatException(
                    "Unsupported block-range tile geometry ${tileWidth}x${tileHeight}"
                )
            }
            if (declaredTileCount != expectedTileCount) {
                throw NprawFormatException(
                    "Block-range NPRAW tile count $declaredTileCount != $expectedTileCount"
                )
            }

            var selectedSamplesWritten = 0
            for (tileRow in 0 until tileRows) {
                val tileY = tileRow * BLOCK_RANGE_TILE_HEIGHT
                val blockHeight = minOf(BLOCK_RANGE_TILE_HEIGHT, sourceHeight - tileY)
                for (tileColumn in 0 until tileColumns) {
                    val tileX = tileColumn * BLOCK_RANGE_TILE_WIDTH
                    val blockWidth = minOf(BLOCK_RANGE_TILE_WIDTH, sourceWidth - tileX)
                    readPayload(input, tileHeader, tileHeader.size, "block-range tile header")
                    val base = readUnsignedShortLe(tileHeader, 0)
                    val bits = tileHeader[2].toInt() and 0xFF
                    if (base > sensorWhiteLevel || bits > sensorBitDepth) {
                        throw NprawFormatException(
                            "Invalid block-range tile base=$base bits=$bits sensorBits=$sensorBitDepth"
                        )
                    }
                    val blockSamples = Math.multiplyExact(blockWidth, blockHeight)
                    val packedBytes = Math.addExact(Math.multiplyExact(blockSamples, bits), 7) / 8
                    check(packedBytes <= tilePayload.size) { "Block-range tile scratch is too small" }
                    readPayload(input, tilePayload, packedBytes, "block-range tile payload")
                    validateCanonicalPadding(tilePayload, packedBytes, blockSamples, bits)

                    val selectedXStart = selectedXStartByTileColumn[tileColumn]
                    val selectedXEnd = selectedXEndByTileColumn[tileColumn]
                    if (selectedXStart == selectedXEnd) continue
                    for (localY in 0 until blockHeight) {
                        val reducedY = sourceToReducedY[tileY + localY]
                        if (reducedY < 0) continue
                        val destinationRow = reducedY * reducedWidth
                        for (reducedX in selectedXStart until selectedXEnd) {
                            val localX = selectedSourceXs[reducedX] - tileX
                            val localSample = localY * blockWidth + localX
                            val residual = readPackedUnsigned(tilePayload, packedBytes, localSample, bits)
                            val sample = base + residual
                            if (sample > sensorWhiteLevel) {
                                throw NprawFormatException(
                                    "Block-range NPRAW sample $sample exceeds white level $sensorWhiteLevel"
                                )
                            }
                            destination[destinationRow + reducedX] = sample
                            selectedSamplesWritten += 1
                        }
                    }
                }
            }
            if (payloadBytesRemaining != 0) {
                throw NprawFormatException(
                    "Block-range NPRAW payload contains $payloadBytesRemaining trailing bytes"
                )
            }
            if (payloadCrc.value != expectedCrc) {
                throw NprawFormatException(
                    "NPRAW payload CRC mismatch (expected=$expectedCrc actual=${payloadCrc.value}) — corrupt frame"
                )
            }
            if (selectedSamplesWritten != destination.size) {
                throw NprawFormatException(
                    "Block-range NPRAW frame omitted selected Bayer samples " +
                        "($selectedSamplesWritten/${destination.size})"
                )
            }
            return true
        }

        private fun readPayload(
            input: java.io.InputStream,
            target: ByteArray,
            length: Int,
            what: String
        ) {
            if (length < 0 || length > target.size || length > payloadBytesRemaining) {
                throw NprawFormatException("Block-range NPRAW $what exceeds the declared payload")
            }
            var targetOffset = 0
            while (targetOffset < length) {
                if (payloadReadPosition >= payloadReadLimit) refillPayload(input, what)
                val copied = minOf(length - targetOffset, payloadReadLimit - payloadReadPosition)
                System.arraycopy(
                    payloadReadBuffer,
                    payloadReadPosition,
                    target,
                    targetOffset,
                    copied
                )
                payloadReadPosition += copied
                targetOffset += copied
                payloadBytesRemaining -= copied
            }
        }

        private fun beginPayload(payloadBytes: Int) {
            payloadCrc.reset()
            payloadBytesRemaining = payloadBytes
            payloadInputBytesRemaining = payloadBytes
            payloadReadPosition = 0
            payloadReadLimit = 0
        }

        /**
         * Batch the CRC across large reusable chunks. Calling CRC32.update for every three-byte tile
         * header enters JNI tens of thousands of times per frame on a full sensor and dominates debug
         * playback CPU; chunking keeps the identical CRC coverage with only a few dozen transitions.
         */
        private fun refillPayload(input: java.io.InputStream, what: String) {
            if (payloadInputBytesRemaining <= 0) {
                throw NprawFormatException("Block-range NPRAW $what exceeds the declared payload")
            }
            val requested = minOf(payloadReadBuffer.size, payloadInputBytesRemaining)
            var loaded = 0
            while (loaded < requested) {
                val read = input.read(payloadReadBuffer, loaded, requested - loaded)
                if (read <= 0) {
                    throw NprawFormatException(
                        "Block-range NPRAW payload is truncated at " +
                            "${payloadInputBytesRemaining - requested + loaded} bytes remaining"
                    )
                }
                loaded += read
            }
            payloadCrc.update(payloadReadBuffer, 0, loaded)
            payloadInputBytesRemaining -= loaded
            payloadReadPosition = 0
            payloadReadLimit = loaded
        }

        private fun validateCanonicalPadding(
            packed: ByteArray,
            packedBytes: Int,
            sampleCount: Int,
            bits: Int
        ) {
            val usedBitsInLastByte = Math.multiplyExact(sampleCount, bits) and 7
            if (usedBitsInLastByte == 0 || packedBytes == 0) return
            val paddingMask = (0xFF shl usedBitsInLastByte) and 0xFF
            if ((packed[packedBytes - 1].toInt() and paddingMask) != 0) {
                throw NprawFormatException("Block-range NPRAW tile has non-canonical padding")
            }
        }

        private fun readPackedUnsigned(
            packed: ByteArray,
            packedBytes: Int,
            sampleIndex: Int,
            bits: Int
        ): Int {
            if (bits == 0) return 0
            val bitOffset = Math.multiplyExact(sampleIndex, bits)
            val firstByte = bitOffset ushr 3
            val shift = bitOffset and 7
            var word = 0
            for (byteOffset in 0 until 3) {
                val index = firstByte + byteOffset
                if (index < packedBytes) {
                    word = word or ((packed[index].toInt() and 0xFF) shl (byteOffset * 8))
                }
            }
            return (word ushr shift) and ((1 shl bits) - 1)
        }
    }

    /**
     * Read the NEXT NPRAW frame from a concatenated stream, or null at a clean end-of-stream (frame
     * boundary). Throws [NprawFormatException] on a truncated/corrupt frame. Streaming (does not load
     * the whole clip into memory).
     */
    fun readFrame(input: java.io.InputStream): DecodedFrame? =
        readRawFrameBytes(input)?.let { decodeFrame(it) }

    /**
     * Read the NEXT NPRAW frame's RAW CONTAINER BYTES (header + payload) WITHOUT decoding, or null at a
     * clean end-of-stream (frame boundary). This is the cheap, inherently-sequential half of [readFrame]:
     * the expensive inflate + predictive-decode is deferred to [decodeFrame]. A converter can therefore
     * read frames on ONE thread (stream I/O is serial) and fan the per-frame [decodeFrame] + develop out
     * across worker threads — each returned byte array is a self-contained frame. Throws on a
     * truncated/corrupt frame.
     */
    fun readRawFrameBytes(input: java.io.InputStream): ByteArray? {
        val prefix = ByteArray(HEADER_PREFIX_BYTES)
        val bodyBytes = readFrameBodySizeOrNull(input, prefix) ?: return null
        val rest = ByteArray(bodyBytes)
        readFullyOrThrow(input, rest, 0, "NPRAW frame body")
        val frame = ByteArray(HEADER_PREFIX_BYTES + rest.size)
        System.arraycopy(prefix, 0, frame, 0, HEADER_PREFIX_BYTES)
        System.arraycopy(rest, 0, frame, HEADER_PREFIX_BYTES, rest.size)
        return frame
    }

    /** Sensor-clock field shared by every current NPRAW header version; never decodes the mosaic. */
    fun validatedEncodedSensorTimestampNs(bytes: ByteArray): Long {
        validateEncodedFrameContainer(bytes)
        val timestampOffset = MAGIC.size + 1 + 5 * Int.SIZE_BYTES
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getLong(timestampOffset)
    }

    /** Validate one encoded NPRAW frame's structure and payload CRC without inflating/debayering it. */
    fun validateEncodedFrameContainer(bytes: ByteArray) {
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC)) throw NprawFormatException("Not an NPRAW frame (bad magic)")
                val version = input.readUnsignedByte()
                if (version != VERSION_LINEAR && version != VERSION_COMPANDED &&
                    version != VERSION_BLOCK_RANGE_LOSSLESS
                ) {
                    throw NprawFormatException("Unsupported NPRAW version $version")
                }
                input.readInt() // mode ordinal
                input.readInt() // width
                input.readInt() // height
                input.readInt() // row stride
                input.readInt() // pixel stride
                input.readLong() // timestamp
                input.readInt() // original size
                val compressedSize = input.readInt()
                if (compressedSize < 0 || compressedSize.toLong() > MAX_SAMPLES) {
                    throw NprawFormatException("NPRAW frame declares implausible payload size $compressedSize")
                }
                input.readInt() // bit depth
                input.readInt() // black level
                input.readInt() // white level
                input.readFloat() // dynamic range
                input.readFloat() // noise estimate
                val expectedCrc = input.readLong()
                if (version >= VERSION_COMPANDED) {
                    input.readInt() // companding type
                    input.readInt() // sensor white level
                }
                val payload = ByteArray(compressedSize)
                input.readFully(payload)
                if (input.available() != 0) {
                    throw NprawFormatException("NPRAW frame contains trailing bytes")
                }
                val actualCrc = CRC32().apply { update(payload) }.value
                if (actualCrc != expectedCrc) {
                    throw NprawFormatException(
                        "NPRAW payload CRC mismatch (expected=$expectedCrc actual=$actualCrc) — corrupt frame"
                    )
                }
            }
        } catch (e: NprawFormatException) {
            throw e
        } catch (e: EOFException) {
            throw NprawFormatException("NPRAW frame truncated (short payload/header)", e)
        }
    }

    /**
     * Count the frames in a concatenated NPRAW [input] by walking the frame headers and SKIPPING each
     * payload (no inflate / no decode) — fast enough to run as a pre-pass so an offline converter knows
     * the EXACT total frame count for an accurate progress bar and ETA. Throws [NprawFormatException] on
     * a corrupt header; a clean end-of-stream ends the walk.
     */
    fun countFrames(input: java.io.InputStream): Int {
        var count = 0
        val skipper = StreamFrameSkipper()
        while (skipper.skipNext(input)) count += 1
        return count
    }

    /**
     * O(1) exact count for the default uncompressed realtime RAW stream. Every LOSSLESS_REALTIME packet
     * has one fixed v2 header and width*height*2 payload; a non-divisible file is rejected so a damaged or
     * variable stream can never receive a fabricated total. Returns null for legacy/compressed layouts,
     * whose existing header walk remains the exact fallback.
     */
    fun estimateFixedFrameCount(input: java.io.InputStream, streamBytes: Long): Int? {
        if (streamBytes <= 0L) return 0
        val prefix = ByteArray(HEADER_PREFIX_BYTES)
        return try {
            readFullyOrThrow(input, prefix, 0, "NPRAW first header prefix")
            for (i in MAGIC.indices) if (prefix[i] != MAGIC[i]) return null
            val version = prefix[VERSION_BYTE_OFFSET].toInt() and 0xFF
            val modeOrdinal = readIntBe(prefix, MAGIC.size + 1)
            if (version != VERSION_LINEAR || modeOrdinal != RawCompressionMode.LOSSLESS_REALTIME.ordinal) {
                return null
            }
            val width = readIntBe(prefix, MAGIC.size + 1 + Int.SIZE_BYTES)
            val height = readIntBe(prefix, MAGIC.size + 1 + Int.SIZE_BYTES * 2)
            if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) return null
            val expectedPayload = Math.multiplyExact(Math.multiplyExact(width, height), Short.SIZE_BYTES)
            val declaredPayload = readIntBe(prefix, COMPRESSED_SIZE_OFFSET)
            if (declaredPayload != expectedPayload) return null
            val packetBytes = HEADER_PREFIX_BYTES.toLong() + HEADER_SUFFIX_V2_BYTES + expectedPayload
            if (packetBytes <= 0L || streamBytes % packetBytes != 0L) return null
            (streamBytes / packetBytes).takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
        } catch (_: Exception) {
            null
        }
    }

    private fun readFrameBodySizeOrNull(
        input: java.io.InputStream,
        prefix: ByteArray
    ): Int? {
        require(prefix.size >= HEADER_PREFIX_BYTES) { "NPRAW frame header scratch is too small" }
        val firstRead = input.read(prefix, 0, 1)
        if (firstRead < 0) return null // clean EOF between frames
        readFullyOrThrow(input, prefix, firstRead, "NPRAW header prefix")
        for (i in MAGIC.indices) {
            if (prefix[i] != MAGIC[i]) throw NprawFormatException("Not an NPRAW frame (bad magic)")
        }
        val version = prefix[VERSION_BYTE_OFFSET].toInt() and 0xFF
        if (version != VERSION_LINEAR && version != VERSION_COMPANDED &&
            version != VERSION_BLOCK_RANGE_LOSSLESS
        ) {
            throw NprawFormatException("Unsupported NPRAW version $version")
        }
        val compressedSize = readIntBe(prefix, COMPRESSED_SIZE_OFFSET)
        if (compressedSize < 0 || compressedSize.toLong() > MAX_SAMPLES) {
            throw NprawFormatException("NPRAW frame declares implausible payload size $compressedSize")
        }
        val suffixBytes = HEADER_SUFFIX_V2_BYTES +
            if (version >= VERSION_COMPANDED) HEADER_SUFFIX_V3_EXTRA_BYTES else 0
        return Math.addExact(suffixBytes, compressedSize)
    }

    private fun skipFullyOrThrow(
        input: java.io.InputStream,
        n: Long,
        what: String,
        scratch: ByteArray
    ) {
        var remaining = n
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                // InputStream.skip may legally return 0 (e.g. at a buffer boundary); fall back to a
                // bounded read so the walk always makes forward progress.
                val toRead = minOf(scratch.size.toLong(), remaining).toInt()
                val read = input.read(scratch, 0, toRead)
                if (read < 0) throw NprawFormatException("$what truncated during skip ($remaining left)")
                remaining -= read.toLong()
            }
        }
    }

    private fun readFullyOrThrow(input: java.io.InputStream, buf: ByteArray, from: Int, what: String) {
        var off = from
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw NprawFormatException("$what truncated at $off/${buf.size}")
            off += n
        }
    }

    private fun readIntBe(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    fun decodeFrame(bytes: ByteArray): DecodedFrame {
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = ByteArray(MAGIC.size)
            try {
                input.readFully(magic)
            } catch (e: EOFException) {
                throw NprawFormatException("NPRAW frame truncated before magic", e)
            }
            if (!magic.contentEquals(MAGIC)) {
                throw NprawFormatException("Not an NPRAW frame (bad magic)")
            }
            val version: Int
            val modeOrdinal: Int
            val width: Int
            val height: Int
            val rowStride: Int
            val pixelStride: Int
            val timestamp: Long
            val originalSize: Int
            val compressedSize: Int
            val bitDepth: Int
            val blackLevel: Int
            val whiteLevel: Int
            val dynamicRangeStops: Float
            val noiseEstimate: Float
            val crc32: Long
            var compandingType = RawCompandingCurve.COMPANDING_NONE
            var sensorWhiteLevel = -1
            val payload: ByteArray
            try {
                version = input.readUnsignedByte()
                if (version != VERSION_LINEAR && version != VERSION_COMPANDED &&
                    version != VERSION_BLOCK_RANGE_LOSSLESS
                ) {
                    throw NprawFormatException("Unsupported NPRAW version $version")
                }
                modeOrdinal = input.readInt()
                width = input.readInt()
                height = input.readInt()
                rowStride = input.readInt()
                pixelStride = input.readInt()
                timestamp = input.readLong()
                originalSize = input.readInt()
                compressedSize = input.readInt()
                bitDepth = input.readInt()
                blackLevel = input.readInt()
                whiteLevel = input.readInt()
                dynamicRangeStops = input.readFloat()
                noiseEstimate = input.readFloat()
                crc32 = input.readLong()
                if (version >= VERSION_COMPANDED) {
                    compandingType = input.readInt()
                    sensorWhiteLevel = input.readInt()
                }
                if (compressedSize < 0 || compressedSize > bytes.size) {
                    throw NprawFormatException(
                        "NPRAW frame declares implausible payload size $compressedSize (frame=${bytes.size})"
                    )
                }
                payload = ByteArray(compressedSize)
                input.readFully(payload)
            } catch (e: EOFException) {
                throw NprawFormatException("NPRAW frame truncated (short payload/header)", e)
            }

            val actualCrc = CRC32().apply { update(payload) }.value
            if (actualCrc != crc32) {
                throw NprawFormatException(
                    "NPRAW payload CRC mismatch (expected=$crc32 actual=$actualCrc) — corrupt frame"
                )
            }

            val mode = RawCompressionMode.values().getOrNull(modeOrdinal)
            val (usePredictive, deflated) = codingForMode(modeOrdinal)

            val samples: IntArray = if (width <= 0 || height <= 0) {
                IntArray(0)
            } else {
                // Geometry is CRC-unprotected — validate each axis AND the 64-bit product so a
                // corrupt width/height surfaces as NprawFormatException, never a raw
                // NegativeArraySizeException from an Int-overflowed allocation nor an uncaught
                // OutOfMemoryError from a plausible-product-but-oversized IntArray allocation.
                if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw NprawFormatException("NPRAW frame declares implausible geometry ${width}x${height}")
                }
                val sampleCount = width.toLong() * height.toLong()
                if (sampleCount > MAX_SAMPLES) {
                    throw NprawFormatException("NPRAW frame declares implausible geometry ${width}x${height}")
                }
                val count = sampleCount.toInt()
                val coded = if (version == VERSION_BLOCK_RANGE_LOSSLESS) {
                    decodeBlockRangePayload(
                        payload = payload,
                        width = width,
                        height = height,
                        sensorBitDepth = bitDepth,
                        sensorWhiteLevel = sensorWhiteLevel
                    )
                } else {
                    val inflated = if (deflated) inflate(payload) else payload
                    if (usePredictive) {
                        predictiveDecode(inflated, width, height, bitDepth)
                    } else {
                        unpackSamples(inflated, bitDepth, count)
                    }
                }
                if (compandingType == RawCompandingCurve.COMPANDING_NONE || sensorWhiteLevel <= 0) {
                    coded
                } else {
                    RawCompandingCurve.of(sensorWhiteLevel, bitDepth).decodeAll(coded)
                }
            }

            return DecodedFrame(
                version = version,
                mode = mode,
                modeOrdinal = modeOrdinal,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = pixelStride,
                timestamp = timestamp,
                originalSize = originalSize,
                compressedSize = compressedSize,
                bitDepth = bitDepth,
                blackLevel = blackLevel,
                whiteLevel = whiteLevel,
                dynamicRangeStops = dynamicRangeStops,
                noiseEstimate = noiseEstimate,
                crc32 = crc32,
                compandingType = compandingType,
                sensorWhiteLevel = sensorWhiteLevel,
                samples = samples
            )
        }
    }

    /**
     * Coding flags for a mode ordinal — mirrors [resolveCompressionProfile] for the
     * predictive/deflate decision (which depend only on the mode, not the bit depth),
     * so legacy v2 frames (which carry no explicit flags) decode unambiguously.
     */
    private fun decodeBlockRangePayload(
        payload: ByteArray,
        width: Int,
        height: Int,
        sensorBitDepth: Int,
        sensorWhiteLevel: Int
    ): IntArray {
        if (sensorBitDepth !in 1..12 || sensorWhiteLevel !in 1..0x0FFF) {
            throw NprawFormatException(
                "Invalid block-range sensor domain bits=$sensorBitDepth white=$sensorWhiteLevel"
            )
        }
        if (payload.size < BLOCK_RANGE_PAYLOAD_HEADER_BYTES) {
            throw NprawFormatException("Block-range NPRAW payload is truncated")
        }
        for (index in BLOCK_RANGE_MAGIC.indices) {
            if (payload[index] != BLOCK_RANGE_MAGIC[index]) {
                throw NprawFormatException("Block-range NPRAW payload has bad magic")
            }
        }
        val tileWidth = readUnsignedShortLe(payload, 4)
        val tileHeight = readUnsignedShortLe(payload, 6)
        if (tileWidth != BLOCK_RANGE_TILE_WIDTH || tileHeight != BLOCK_RANGE_TILE_HEIGHT) {
            throw NprawFormatException(
                "Unsupported block-range tile geometry ${tileWidth}x${tileHeight}"
            )
        }
        val tileColumns = (width.toLong() + tileWidth - 1L) / tileWidth.toLong()
        val tileRows = (height.toLong() + tileHeight - 1L) / tileHeight.toLong()
        val expectedTileCount = Math.multiplyExact(tileColumns, tileRows)
        val declaredTileCount = readUnsignedIntLe(payload, 8)
        if (declaredTileCount != expectedTileCount) {
            throw NprawFormatException(
                "Block-range NPRAW tile count $declaredTileCount != $expectedTileCount"
            )
        }

        val samples = IntArray(Math.multiplyExact(width, height))
        var payloadOffset = BLOCK_RANGE_PAYLOAD_HEADER_BYTES
        for (tileY in 0 until height step tileHeight) {
            val blockHeight = minOf(tileHeight, height - tileY)
            for (tileX in 0 until width step tileWidth) {
                val blockWidth = minOf(tileWidth, width - tileX)
                if (payloadOffset > payload.size - BLOCK_RANGE_TILE_HEADER_BYTES) {
                    throw NprawFormatException("Block-range NPRAW tile header is truncated")
                }
                val base = readUnsignedShortLe(payload, payloadOffset)
                val bits = payload[payloadOffset + 2].toInt() and 0xFF
                payloadOffset += BLOCK_RANGE_TILE_HEADER_BYTES
                if (base > sensorWhiteLevel || bits > sensorBitDepth) {
                    throw NprawFormatException(
                        "Invalid block-range tile base=$base bits=$bits sensorBits=$sensorBitDepth"
                    )
                }
                val blockSamples = Math.multiplyExact(blockWidth, blockHeight)
                val packedBytes = Math.addExact(Math.multiplyExact(blockSamples, bits), 7) / 8
                val packedEnd = try {
                    Math.addExact(payloadOffset, packedBytes)
                } catch (failure: ArithmeticException) {
                    throw NprawFormatException("Block-range NPRAW tile size overflow", failure)
                }
                if (packedEnd > payload.size) {
                    throw NprawFormatException("Block-range NPRAW tile payload is truncated")
                }

                var inputOffset = payloadOffset
                var accumulator = 0L
                var accumulatorBits = 0
                val residualMask = if (bits == 0) 0L else (1L shl bits) - 1L
                for (localY in 0 until blockHeight) {
                    val outputRow = (tileY + localY) * width + tileX
                    for (localX in 0 until blockWidth) {
                        while (accumulatorBits < bits) {
                            if (inputOffset >= packedEnd) {
                                throw NprawFormatException("Block-range NPRAW residual stream is truncated")
                            }
                            accumulator = accumulator or
                                ((payload[inputOffset++].toLong() and 0xFFL) shl accumulatorBits)
                            accumulatorBits += 8
                        }
                        val residual = if (bits == 0) 0 else (accumulator and residualMask).toInt()
                        if (bits != 0) {
                            accumulator = accumulator ushr bits
                            accumulatorBits -= bits
                        }
                        val sample = base + residual
                        if (sample > sensorWhiteLevel) {
                            throw NprawFormatException(
                                "Block-range NPRAW sample $sample exceeds white level $sensorWhiteLevel"
                            )
                        }
                        samples[outputRow + localX] = sample
                    }
                }
                if (inputOffset != packedEnd || accumulator != 0L) {
                    throw NprawFormatException("Block-range NPRAW tile has non-canonical padding")
                }
                payloadOffset = packedEnd
            }
        }
        if (payloadOffset != payload.size) {
            throw NprawFormatException("Block-range NPRAW payload contains trailing bytes")
        }
        return samples
    }

    private fun readUnsignedShortLe(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUnsignedIntLe(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    private fun codingForMode(ordinal: Int): Pair<Boolean, Boolean> {
        val mode = RawCompressionMode.values().getOrNull(ordinal) ?: return false to false
        return when (mode) {
            RawCompressionMode.OFF -> false to false
            RawCompressionMode.HIGH_COMPRESSION -> false to false
            RawCompressionMode.LOSSLESS_REALTIME -> false to false
            RawCompressionMode.LOSSLESS -> true to true
            RawCompressionMode.LOSSLESS_ENHANCED -> true to true
            RawCompressionMode.VISUALLY_LOSSLESS -> true to true
            RawCompressionMode.HIGH_QUALITY -> true to true
            RawCompressionMode.NEO_SPECTRAL_RAW -> true to true
            RawCompressionMode.NEO_PURE_RAW -> true to true
            RawCompressionMode.NEO_FINE_RAW -> true to true
            RawCompressionMode.NEO_DENSE_RAW -> true to true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PROFILE (moved verbatim from RawVideoManager, plus native-depth gate)
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveCompressionProfile(
        mode: RawCompressionMode,
        sensorWhiteLevel: Int,
        preserveNativeDepth: Boolean
    ): CompressionProfile {
        val targetBitDepth = RawVideoBitDepthPolicy.resolveStorageBitDepth(
            requestedBitDepth = RawCompressionMode.getBitDepth(mode),
            sourceWhiteLevel = sensorWhiteLevel,
            preserveNativeDepth = preserveNativeDepth
        ).coerceIn(10, 16)
        return when (mode) {
            RawCompressionMode.OFF -> CompressionProfile(
                targetBitDepth = 16,
                deflaterLevel = Deflater.NO_COMPRESSION,
                usePredictive = false,
                useAdaptiveQuantization = false
            )
            RawCompressionMode.LOSSLESS -> CompressionProfile(
                targetBitDepth = 16,
                deflaterLevel = Deflater.BEST_SPEED,
                usePredictive = true,
                useAdaptiveQuantization = false
            )
            RawCompressionMode.LOSSLESS_ENHANCED -> CompressionProfile(
                targetBitDepth = 16,
                deflaterLevel = Deflater.BEST_COMPRESSION,
                usePredictive = true,
                useAdaptiveQuantization = false
            )
            RawCompressionMode.VISUALLY_LOSSLESS,
            RawCompressionMode.NEO_PURE_RAW -> CompressionProfile(
                targetBitDepth = targetBitDepth,
                deflaterLevel = Deflater.BEST_SPEED,
                usePredictive = true,
                useAdaptiveQuantization = true
            )
            RawCompressionMode.HIGH_QUALITY,
            RawCompressionMode.NEO_FINE_RAW,
            RawCompressionMode.NEO_SPECTRAL_RAW -> CompressionProfile(
                targetBitDepth = targetBitDepth,
                deflaterLevel = Deflater.DEFAULT_COMPRESSION,
                usePredictive = true,
                useAdaptiveQuantization = true
            )
            RawCompressionMode.HIGH_COMPRESSION -> CompressionProfile(
                targetBitDepth = targetBitDepth,
                deflaterLevel = Deflater.NO_COMPRESSION,
                usePredictive = false,
                useAdaptiveQuantization = false
            )
            RawCompressionMode.NEO_DENSE_RAW -> CompressionProfile(
                targetBitDepth = targetBitDepth,
                deflaterLevel = Deflater.BEST_COMPRESSION,
                usePredictive = true,
                useAdaptiveQuantization = true
            )
            RawCompressionMode.LOSSLESS_REALTIME -> CompressionProfile(
                targetBitDepth = 16,
                deflaterLevel = Deflater.NO_COMPRESSION,
                usePredictive = false,
                useAdaptiveQuantization = false
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ANALYSIS / ADAPTIVE QUANTIZATION (moved verbatim from RawVideoManager)
    // ─────────────────────────────────────────────────────────────────────────

    private fun analyzeRawFrame(samples: IntArray): RawFrameStats {
        if (samples.isEmpty()) {
            return RawFrameStats(0, 0, 0f, 0f)
        }
        var minValue = Int.MAX_VALUE
        var maxValue = Int.MIN_VALUE
        for (sample in samples) {
            if (sample < minValue) minValue = sample
            if (sample > maxValue) maxValue = sample
        }
        val blackLevel = minValue.coerceIn(0, 0xFFFF)
        val whiteLevel = maxValue.coerceIn(blackLevel, 0xFFFF)
        val dynamicRangeStops = if (whiteLevel <= blackLevel) {
            0f
        } else {
            (
                kotlin.math.ln((whiteLevel + 1.0) / (blackLevel + 1.0)) /
                    kotlin.math.ln(2.0)
                ).toFloat().coerceAtLeast(0f)
        }
        val sampleStep = maxOf(1, samples.size / 4096)
        var diffAccum = 0L
        var diffCount = 0
        var i = sampleStep
        while (i < samples.size) {
            diffAccum += kotlin.math.abs(samples[i] - samples[i - sampleStep]).toLong()
            diffCount++
            i += sampleStep
        }
        val noiseEstimate = if (diffCount > 0) {
            (diffAccum.toFloat() / diffCount.toFloat()) / 65535f
        } else {
            0f
        }
        return RawFrameStats(
            blackLevel = blackLevel,
            whiteLevel = whiteLevel,
            dynamicRangeStops = dynamicRangeStops,
            noiseEstimate = noiseEstimate.coerceIn(0f, 1f)
        )
    }

    private fun applyAdaptiveQuantization(
        samples: IntArray,
        profile: CompressionProfile,
        stats: RawFrameStats
    ): IntArray {
        if (!profile.useAdaptiveQuantization || profile.targetBitDepth >= 16) {
            return samples.copyOf()
        }
        val quantized = IntArray(samples.size)
        val shift = (16 - profile.targetBitDepth).coerceAtLeast(1)
        val highlightThreshold = (stats.whiteLevel - (1 shl (shift + 1))).coerceAtLeast(0)
        for (index in samples.indices) {
            val sample = samples[index].coerceIn(0, 0xFFFF)
            val adaptiveShift = if (sample >= highlightThreshold && shift > 1) shift - 1 else shift
            if (adaptiveShift <= 0) {
                quantized[index] = sample
            } else {
                val rounded = ((sample + (1 shl (adaptiveShift - 1))) shr adaptiveShift) shl adaptiveShift
                quantized[index] = rounded.coerceIn(0, 0xFFFF)
            }
        }
        return quantized
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PREDICTIVE / PACK ENCODE (moved verbatim from RawVideoManager)
    // ─────────────────────────────────────────────────────────────────────────

    private fun predictiveEncode(
        samples: IntArray,
        width: Int,
        height: Int,
        targetBitDepth: Int
    ): ByteArray {
        if (samples.isEmpty() || width <= 0 || height <= 0) return ByteArray(0)
        val maxValue = ((1 shl targetBitDepth.coerceIn(1, 16)) - 1).coerceAtLeast(1)
        val out = ByteArrayOutputStream(samples.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (index >= samples.size) break
                val sample = samples[index].coerceIn(0, maxValue)
                val predictor = when {
                    x == 0 && y == 0 -> 0
                    y == 0 -> samples[index - 1].coerceIn(0, maxValue)
                    x == 0 -> samples[index - width].coerceIn(0, maxValue)
                    else -> {
                        val left = samples[index - 1]
                        val top = samples[index - width]
                        val diagonal = samples[index - width - 1]
                        (left + top - diagonal).coerceIn(0, maxValue)
                    }
                }
                val residual = sample - predictor
                writeVarInt(out, zigZagEncode(residual))
            }
        }
        return out.toByteArray()
    }

    private fun packSamples(samples: IntArray, targetBitDepth: Int): ByteArray =
        if (targetBitDepth <= 12) {
            packSamplesPacked12Bit(samples)
        } else {
            packSamplesLittleEndian(samples)
        }

    private fun packSamplesPacked12Bit(samples: IntArray): ByteArray {
        if (samples.isEmpty()) return ByteArray(0)
        val output = ByteArray((samples.size * 3 + 1) / 2)
        var inIndex = 0
        var outIndex = 0
        while (inIndex < samples.size) {
            val sample0 = samples[inIndex].coerceIn(0, 0x0FFF)
            val hasSecond = inIndex + 1 < samples.size
            val sample1 = if (hasSecond) samples[inIndex + 1].coerceIn(0, 0x0FFF) else 0
            output[outIndex] = (sample0 and 0xFF).toByte()
            output[outIndex + 1] = (((sample0 ushr 8) and 0x0F) or ((sample1 and 0x0F) shl 4)).toByte()
            if (hasSecond) {
                output[outIndex + 2] = ((sample1 ushr 4) and 0xFF).toByte()
                outIndex += 3
                inIndex += 2
            } else {
                outIndex += 2
                inIndex += 1
            }
        }
        return output
    }

    private fun packSamplesLittleEndian(samples: IntArray): ByteArray {
        val output = ByteArray(samples.size * 2)
        var offset = 0
        for (sample in samples) {
            val clamped = sample.coerceIn(0, 0xFFFF)
            output[offset] = (clamped and 0xFF).toByte()
            output[offset + 1] = ((clamped ushr 8) and 0xFF).toByte()
            offset += 2
        }
        return output
    }

    private fun writeVarInt(out: ByteArrayOutputStream, value: Int) {
        var encoded = value
        while ((encoded and 0xFFFFFF80.toInt()) != 0) {
            out.write((encoded and 0x7F) or 0x80)
            encoded = encoded ushr 7
        }
        out.write(encoded and 0x7F)
    }

    private fun zigZagEncode(value: Int): Int = (value shl 1) xor (value shr 31)

    private fun compressBytes(data: ByteArray, level: Int): ByteArray {
        val deflater = Deflater(level)
        return try {
            deflater.setInput(data)
            deflater.finish()
            val buffer = ByteArray(16 * 1024)
            ByteArrayOutputStream(data.size).use { out ->
                while (!deflater.finished()) {
                    val written = deflater.deflate(buffer)
                    if (written <= 0) break
                    out.write(buffer, 0, written)
                }
                out.toByteArray()
            }
        } finally {
            deflater.end()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PREDICTIVE / PACK DECODE (the inverses — new)
    // ─────────────────────────────────────────────────────────────────────────

    private fun predictiveDecode(
        data: ByteArray,
        width: Int,
        height: Int,
        targetBitDepth: Int
    ): IntArray {
        val count = width * height
        val rec = IntArray(count)
        if (count == 0) return rec
        val maxValue = ((1 shl targetBitDepth.coerceIn(1, 16)) - 1).coerceAtLeast(1)
        val cursor = VarIntCursor(data)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (index >= rec.size) break
                val residual = zigZagDecode(cursor.next())
                val predictor = when {
                    x == 0 && y == 0 -> 0
                    y == 0 -> rec[index - 1].coerceIn(0, maxValue)
                    x == 0 -> rec[index - width].coerceIn(0, maxValue)
                    else -> {
                        val left = rec[index - 1]
                        val top = rec[index - width]
                        val diagonal = rec[index - width - 1]
                        (left + top - diagonal).coerceIn(0, maxValue)
                    }
                }
                rec[index] = (predictor + residual).coerceIn(0, maxValue)
            }
        }
        return rec
    }

    private fun unpackSamples(data: ByteArray, bitDepth: Int, count: Int): IntArray =
        if (bitDepth <= 12) {
            unpackPacked12Bit(data, count)
        } else {
            unpackLittleEndian(data, count)
        }

    private fun unpackPacked12Bit(data: ByteArray, count: Int): IntArray {
        val out = IntArray(count)
        var inIndex = 0
        var outIndex = 0
        while (outIndex < count) {
            if (inIndex + 1 >= data.size + 1) break
            val b0 = if (inIndex < data.size) data[inIndex].toInt() and 0xFF else 0
            val b1 = if (inIndex + 1 < data.size) data[inIndex + 1].toInt() and 0xFF else 0
            out[outIndex] = b0 or ((b1 and 0x0F) shl 8)
            if (outIndex + 1 < count) {
                val b2 = if (inIndex + 2 < data.size) data[inIndex + 2].toInt() and 0xFF else 0
                out[outIndex + 1] = ((b1 ushr 4) and 0x0F) or (b2 shl 4)
                inIndex += 3
                outIndex += 2
            } else {
                inIndex += 2
                outIndex += 1
            }
        }
        return out
    }

    private fun unpackLittleEndian(data: ByteArray, count: Int): IntArray {
        val out = IntArray(count)
        var offset = 0
        for (i in 0 until count) {
            val lo = if (offset < data.size) data[offset].toInt() and 0xFF else 0
            val hi = if (offset + 1 < data.size) data[offset + 1].toInt() and 0xFF else 0
            out[i] = lo or (hi shl 8)
            offset += 2
        }
        return out
    }

    private fun zigZagDecode(value: Int): Int = (value ushr 1) xor -(value and 1)

    private fun inflate(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val buffer = ByteArray(16 * 1024)
            ByteArrayOutputStream(data.size * 2).use { out ->
                while (!inflater.finished()) {
                    val written = inflater.inflate(buffer)
                    if (written == 0) {
                        if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break
                    } else {
                        out.write(buffer, 0, written)
                    }
                }
                out.toByteArray()
            }
        } finally {
            inflater.end()
        }
    }

    /** Minimal forward cursor for LEB128 varints over a byte array. */
    private class VarIntCursor(private val data: ByteArray) {
        private var pos = 0
        fun next(): Int {
            var result = 0
            var shift = 0
            while (true) {
                if (pos >= data.size) {
                    // Stream exhausted mid-varint: treat the remainder as zero residual.
                    return result
                }
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift >= 35) break
            }
            return result
        }
    }
}
