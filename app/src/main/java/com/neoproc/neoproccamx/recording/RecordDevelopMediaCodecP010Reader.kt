package com.neoproc.neoproccamx.recording

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  RECORD-THEN-DEVELOP — Phase 2 DEVICE-ONLY HEVC→P010 decode reader
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The real decode behind the [RecordDevelopP010FrameReader] seam: `MediaExtractor` → `MediaCodec`
 * (video/hevc, ByteBuffer mode, P010 output) → per-frame `Image` → the CANONICAL MSB-justified P010 payload
 * via [Npv10FramePacker.copyP010ImageIntoDirectBuffer] (so chroma de-interleave + MSB justification match
 * the rest of the codebase and [RecordDevelopP010ColorIngress]).
 *
 * DEVICE-ONLY / DORMANT (mirrors [EliteVisionMediaCodecDecodeProvider]): `MediaCodec` is not mockable and
 * Robolectric is banned, so this is NEVER constructed in the pure-JVM unit suite — the converter loop is
 * covered there through [InMemoryP010FrameReader]. On device the Phase-5 develop card injects this via the
 * converter's `readerFactory`. It is a straight pull decoder: each [readNextFrame] pumps input + drains
 * output until one decoded frame (or EOS) is available.
 *
 * ⚠ R8 (device): the decoded `Image`'s chroma interleave/stride is vendor-specific; the normaliser handles
 * the documented P010 layout, and a non-P010 / mismatched buffer throws (surfaced on-device, not silently).
 */
@SuppressLint("InlinedApi")
internal class RecordDevelopMediaCodecP010Reader(
    master: File,
    mime: String = MIME_HEVC
) : RecordDevelopP010FrameReader {

    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val decoderLease: RecordDevelopP010DecoderLease
    private val bufferInfo = MediaCodec.BufferInfo()

    private var inputDone = false
    private var outputDone = false
    private var frameIndex = 0L
    private var reusableTarget: ByteBuffer? = null
    private var outputFormat: MediaFormat? = null
    private var nullImageFrames = 0L
    private var outputBufferFallbackFrames = 0L
    private var imageFrames = 0L
    private var decoderName = "uninitialized"
    private var lastPresentationTimeUs: Long? = null
    private var ptsViolations = 0L
    private var codecHealthy = true
    private var closed = false

    override val format: RecordDevelopDecodedFormat

    init {
        // Native resources are acquired in sequence; if ANY step throws (bad source, a decoder that rejects
        // P010 / this profile-level, start failure) the constructor never returns, so nothing can .use{}/
        // close() it. Release both handles here so a failed develop can't leak the scarce hardware codec.
        var initializingCodec: MediaCodec? = null
        try {
            extractor.setDataSource(master.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: error("No video track in record-develop master: ${master.absolutePath}")
            extractor.selectTrack(trackIndex)
            val trackFormat = extractor.getTrackFormat(trackIndex)

            val width = trackFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = trackFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val fps = resolveFrameRate(trackFormat)
            val sourceDurationUs = resolveSourceDurationUs(trackFormat)
            format = RecordDevelopDecodedFormat(
                width = width,
                height = height,
                fps = fps,
                storageBitDepth = 10,
                sourceDurationUs = sourceDurationUs
            )

            val decoderMime = trackFormat.getString(MediaFormat.KEY_MIME) ?: mime
            val decoderInfos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.mapNotNull { info ->
                if (info.isEncoder || !info.supportedTypes.any { it.equals(decoderMime, ignoreCase = true) }) {
                    return@mapNotNull null
                }
                val capabilities = runCatching { info.getCapabilitiesForType(decoderMime) }.getOrNull()
                    ?: return@mapNotNull null
                val supportsMain10 = capabilities.profileLevels.any { profileLevel ->
                    profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                }
                val supportsP010 = capabilities.colorFormats.contains(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
                )
                val supportsGeometry = runCatching {
                    capabilities.videoCapabilities?.isSizeSupported(width, height) != false
                }.getOrDefault(false)
                RecordDevelopDecoderCandidate(
                    name = info.name,
                    hardwareAccelerated = info.safeHardwareAccelerated(),
                    vendor = info.safeVendor(),
                    supportsMain10 = supportsMain10,
                    supportsP010 = supportsP010,
                    supportsRequestedGeometry = supportsGeometry
                )
            }
            val selectedDecoder = RecordDevelopDecoderQualityPolicy.select(decoderInfos)
                ?: error(
                    "No genuine HEVC Main10/P010 decoder available for ${width}x${height}; " +
                        "refusing silent 8-bit conversion"
                )
            decoderName = selectedDecoder.name
            // Request 10-bit P010 ByteBuffer output so getOutputImage yields a YCBCR_P010 Image.
            runCatching {
                trackFormat.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010
                )
            }
            val lease = RecordDevelopP010DecoderPool.acquireStarted(decoderName, trackFormat)
            decoderLease = lease
            val selectedCodec = lease.codec
            initializingCodec = selectedCodec
            codec = selectedCodec
            NeoLogger.i(
                TAG,
                "Record-develop decoder init source=${master.name} mime=$decoderMime " +
                    "geometry=${width}x${height}@${fps}fps decoder=${codec.name} " +
                    "hardware=${selectedDecoder.hardwareAccelerated} vendor=${selectedDecoder.vendor} " +
                    "main10=${selectedDecoder.supportsMain10} p010=${selectedDecoder.supportsP010} " +
                    "sourceDurationUs=${sourceDurationUs ?: "unavailable"} bytes=${master.length()}"
            )
            outputFormat = runCatching { codec.outputFormat }.getOrNull()
            outputFormat?.let { logOutputFormat("start", it) }
        } catch (t: Throwable) {
            Log.e(TAG, "Record-develop decoder init failed: ${t.message}", t)
            NeoLogger.e(TAG, "Record-develop decoder init failed: ${t.message}", t)
            initializingCodec?.let { createdCodec ->
                RecordDevelopP010DecoderPool.discard(
                    codec = createdCodec,
                    codecName = decoderName,
                    role = "hevc-main10-p010-decoder-init-failure"
                )
            }
            runCatching { extractor.release() }
            throw t
        }
    }

    override fun readNextFrame(): RecordDevelopP010Frame? = try {
        readNextFrameInternal()
    } catch (failure: Throwable) {
        codecHealthy = false
        throw failure
    }

    private fun readNextFrameInternal(): RecordDevelopP010Frame? {
        if (outputDone) return null
        var eosIdleDrains = 0
        while (true) {
            if (!inputDone && feedInput()) {
                eosIdleDrains = 0
            }

            when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputDone) {
                        eosIdleDrains += 1
                        if (eosIdleDrains >= EOS_DRAIN_STALL_LIMIT) {
                            throw IllegalStateException(
                                "Record-develop decoder stalled after EOS " +
                                    "(frames=$frameIndex imageFrames=$imageFrames " +
                                    "bufferFallbackFrames=$outputBufferFallbackFrames nullImages=$nullImageFrames)"
                            )
                        }
                    }
                    continue // keep pumping input + re-draining output
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = codec.outputFormat
                    outputFormat?.let { logOutputFormat("changed", it) }
                    continue
                }
                else -> {
                    if (outIndex < 0) continue
                    eosIdleDrains = 0
                    val endOfStream = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val hasPixels = bufferInfo.size > 0
                    var frame: RecordDevelopP010Frame? = null
                    if (hasPixels) {
                        frame = extractFrame(outIndex)
                    } else {
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                    if (endOfStream) outputDone = true
                    if (frame != null) return frame
                    if (outputDone) return null
                }
            }
        }
    }

    private fun feedInput(): Boolean {
        val inIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inIndex < 0) return false
        val inBuffer = codec.getInputBuffer(inIndex) ?: return false
        val sampleSize = extractor.readSampleData(inBuffer, 0)
        if (sampleSize < 0) {
            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            val pts = extractor.sampleTime
            codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
            extractor.advance()
        }
        return true
    }

    private fun extractFrame(outIndex: Int): RecordDevelopP010Frame? {
        try {
            val image = runCatching { codec.getOutputImage(outIndex) }
                .onFailure {
                    Log.w(TAG, "getOutputImage failed; trying output-buffer fallback", it)
                    NeoLogger.w(TAG, "getOutputImage failed; trying output-buffer fallback", it)
                }
                .getOrNull()
            if (image != null) {
                try {
                    val frame = extractFrameFromImage(image)
                    if (frame != null) {
                        imageFrames += 1L
                        return frame
                    }
                } finally {
                    image.close()
                }
            }
            nullImageFrames += 1L
            return extractFrameFromOutputBuffer(outIndex)
        } finally {
            codec.releaseOutputBuffer(outIndex, false)
        }
    }

    private fun extractFrameFromImage(image: android.media.Image): RecordDevelopP010Frame? {
        if (image.format != ImageFormat.YCBCR_P010) {
            Log.w(
                TAG,
                "Decoder Image is not P010 format=${image.format}; trying output-buffer fallback " +
                    "planes=${image.planes.size}"
            )
            NeoLogger.w(
                TAG,
                "Decoder Image is not P010 format=${image.format}; trying output-buffer fallback " +
                    "planes=${image.planes.size}"
            )
            return null
        }
        val w = image.width
        val h = image.height
        val yStrideBytes = image.planes.firstOrNull()?.rowStride ?: 0
        RecordDevelopDecoderQualityPolicy.requireOutput(
            expectedWidth = format.width,
            expectedHeight = format.height,
            actualWidth = w,
            actualHeight = h,
            p010 = true,
            strideBytes = yStrideBytes,
            sliceHeight = h
        )
        val payloadBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(w, h).toInt()
        val target = obtainTarget(payloadBytes)
        val copied = Npv10FramePacker.copyP010ImageIntoDirectBuffer(image, target)
        if (copied != payloadBytes) {
            Log.w(
                TAG,
                "Unexpected P010 Image copy size $copied expected=$payloadBytes " +
                    "geometry=${w}x${h} planes=${describePlanes(image)}; trying output-buffer fallback"
            )
            NeoLogger.w(
                TAG,
                "Unexpected P010 Image copy size $copied expected=$payloadBytes " +
                    "geometry=${w}x${h} planes=${describePlanes(image)}; trying output-buffer fallback"
            )
            return null
        }
        val bytes = ByteArray(copied)
        target.rewind()
        target.get(bytes)
        return buildFrame(w, h, bytes)
    }

    private fun extractFrameFromOutputBuffer(outIndex: Int): RecordDevelopP010Frame? {
        val buffer = codec.getOutputBuffer(outIndex) ?: return null
        val w = format.width
        val h = format.height
        val payloadBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(w, h).toInt()
        val fmt = outputFormat ?: runCatching { codec.outputFormat }.getOrNull()
        val formatStride = fmt.safeInteger("stride", w)
        val rowBytes = RecordDevelopP010OutputBufferLayout.rowBytesFromFormatStride(formatStride, w)
        val sliceHeight = fmt.safeInteger("slice-height", h).coerceAtLeast(h)
        val chromaWidth = (w + 1) / 2
        val chromaHeight = (h + 1) / 2
        val yRowBytes = rowBytes
        val uvRowBytes = rowBytes
        val yPlaneBytesLong = yRowBytes.toLong() * sliceHeight.toLong()
        val requiredBytesLong = RecordDevelopP010OutputBufferLayout.requiredBytes(
            width = w,
            height = h,
            formatStride = formatStride,
            sliceHeight = sliceHeight
        )
        val yPlaneBytes = yPlaneBytesLong.toInt()
        val requiredBytes = requiredBytesLong.toInt()
        val base = bufferInfo.offset.coerceAtLeast(0)
        val availableBytes = if (bufferInfo.size > 0) {
            bufferInfo.size
        } else {
            max(0, buffer.capacity() - base)
        }
        if (availableBytes < requiredBytes) {
            throw IllegalStateException(
                "Decoder output buffer too small for P010 fallback " +
                    "available=$availableBytes required=$requiredBytes payload=$payloadBytes " +
                    "geometry=${w}x${h} formatStride=$formatStride rowBytes=$rowBytes sliceHeight=$sliceHeight " +
                    "format=${fmt?.toString() ?: "unknown"}"
            )
        }
        RecordDevelopDecoderQualityPolicy.requireOutput(
            expectedWidth = format.width,
            expectedHeight = format.height,
            actualWidth = fmt.safeInteger(MediaFormat.KEY_WIDTH, w),
            actualHeight = fmt.safeInteger(MediaFormat.KEY_HEIGHT, h),
            // A selected Main10/P010 decoder plus a full 16-bit P010-sized output is the proof when a
            // vendor omits the standard color-format key on ByteBuffer output.
            p010 = availableBytes >= requiredBytes,
            strideBytes = rowBytes,
            sliceHeight = sliceHeight
        )
        val source = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        source.clear()
        source.limit(min(source.capacity(), base + availableBytes))
        val lsbPacked = detectLsbJustifiedP010(source, base, min(w * h, 4096))
        val bytes = ByteArray(payloadBytes)
        var out = 0
        for (row in 0 until h) {
            var src = base + row * yRowBytes
            repeat(w) {
                copyP010Word(source, src, bytes, out, lsbPacked)
                src += Short.SIZE_BYTES
                out += Short.SIZE_BYTES
            }
        }
        val uvBase = base + yPlaneBytes
        for (row in 0 until chromaHeight) {
            var src = uvBase + row * uvRowBytes
            repeat(chromaWidth) {
                copyP010Word(source, src, bytes, out, lsbPacked)
                copyP010Word(source, src + Short.SIZE_BYTES, bytes, out + Short.SIZE_BYTES, lsbPacked)
                src += Short.SIZE_BYTES * 2
                out += Short.SIZE_BYTES * 2
            }
        }
        outputBufferFallbackFrames += 1L
        if (outputBufferFallbackFrames == 1L) {
            NeoLogger.w(
                TAG,
                "Record-develop using decoder output-buffer P010 fallback " +
                    "geometry=${w}x${h} formatStride=$formatStride rowBytes=$rowBytes sliceHeight=$sliceHeight " +
                    "lsbPacked=$lsbPacked colorFormat=${fmt.safeInteger(MediaFormat.KEY_COLOR_FORMAT, -1)}"
            )
        }
        return buildFrame(w, h, bytes)
    }

    private fun buildFrame(w: Int, h: Int, bytes: ByteArray): RecordDevelopP010Frame {
        try {
            RecordDevelopDecoderQualityPolicy.requireMonotonicPts(
                previousPtsUs = lastPresentationTimeUs,
                currentPtsUs = bufferInfo.presentationTimeUs
            )
        } catch (failure: IllegalArgumentException) {
            ptsViolations += 1L
            throw failure
        }
        val frame = RecordDevelopP010Frame(
            index = frameIndex,
            presentationTimeUs = bufferInfo.presentationTimeUs,
            width = w,
            height = h,
            p010 = bytes
        )
        lastPresentationTimeUs = bufferInfo.presentationTimeUs
        frameIndex += 1L
        return frame
    }

    private fun obtainTarget(payloadBytes: Int): ByteBuffer {
        val existing = reusableTarget
        if (existing != null && existing.capacity() >= payloadBytes) {
            existing.clear()
            return existing
        }
        val fresh = ByteBuffer.allocateDirect(payloadBytes).order(ByteOrder.nativeOrder())
        reusableTarget = fresh
        return fresh
    }

    override fun close() {
        if (closed) return
        closed = true
        NeoLogger.i(
            TAG,
            "Record-develop decoder close frames=$frameIndex imageFrames=$imageFrames " +
                "bufferFallbackFrames=$outputBufferFallbackFrames nullImages=$nullImageFrames " +
                "ptsViolations=$ptsViolations decoder=$decoderName"
        )
        RecordDevelopP010DecoderPool.recycle(decoderLease, healthy = codecHealthy)
        runCatching { extractor.release() }
    }

    private fun resolveFrameRate(fmt: MediaFormat): Int {
        return runCatching {
            if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) fmt.getInteger(MediaFormat.KEY_FRAME_RATE) else DEFAULT_FPS
        }.recoverCatching {
            fmt.getFloat(MediaFormat.KEY_FRAME_RATE).toInt()
        }.getOrDefault(DEFAULT_FPS).coerceAtLeast(1)
    }

    private fun resolveSourceDurationUs(fmt: MediaFormat): Long? {
        if (!fmt.containsKey(MediaFormat.KEY_DURATION)) return null
        return runCatching { fmt.getLong(MediaFormat.KEY_DURATION) }
            .recoverCatching { fmt.getInteger(MediaFormat.KEY_DURATION).toLong() }
            .getOrNull()
            ?.takeIf { it > 0L }
    }

    private fun logOutputFormat(stage: String, fmt: MediaFormat) {
        NeoLogger.i(
            TAG,
            "Record-develop decoder output format $stage " +
                "mime=${fmt.getString(MediaFormat.KEY_MIME)} " +
                "colorFormat=${fmt.safeInteger(MediaFormat.KEY_COLOR_FORMAT, -1)} " +
                "width=${fmt.safeInteger(MediaFormat.KEY_WIDTH, format.width)} " +
                "height=${fmt.safeInteger(MediaFormat.KEY_HEIGHT, format.height)} " +
                "stride=${fmt.safeInteger("stride", -1)} " +
                "sliceHeight=${fmt.safeInteger("slice-height", -1)}"
        )
    }

    private fun describePlanes(image: android.media.Image): String =
        image.planes.mapIndexed { index, plane ->
            "#$index(row=${plane.rowStride},pixel=${plane.pixelStride},remaining=${plane.buffer.remaining()})"
        }.joinToString(separator = ",")

    private fun MediaFormat?.safeInteger(key: String, fallback: Int): Int {
        if (this == null || !containsKey(key)) return fallback
        return runCatching { getInteger(key) }
            .recoverCatching { getFloat(key).toInt() }
            .getOrDefault(fallback)
    }

    private fun MediaCodecInfo.safeHardwareAccelerated(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { isHardwareAccelerated }.getOrDefault(!safeSoftwareOnly())
        } else {
            !safeSoftwareOnly()
        }

    private fun MediaCodecInfo.safeSoftwareOnly(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { isSoftwareOnly }.getOrDefault(isSoftwareCodecName())
        } else {
            isSoftwareCodecName()
        }

    private fun MediaCodecInfo.safeVendor(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { isVendor }.getOrDefault(!safeSoftwareOnly())
        } else {
            !safeSoftwareOnly()
        }

    private fun MediaCodecInfo.isSoftwareCodecName(): Boolean =
        name.startsWith("OMX.google.", ignoreCase = true) ||
            name.startsWith("c2.android.", ignoreCase = true)

    private fun detectLsbJustifiedP010(source: ByteBuffer, base: Int, sampleCount: Int): Boolean {
        if (sampleCount <= 0) return false
        var msbLike = 0
        var lsbLike = 0
        val safeSamples = min(sampleCount, max(0, (source.capacity() - base) / Short.SIZE_BYTES))
        val stride = max(1, safeSamples / 256)
        var sample = 0
        while (sample < safeSamples) {
            val word = readLe16(source, base + sample * Short.SIZE_BYTES)
            if ((word and 0x003F) == 0) msbLike += 1
            if (word in 1..V10_MAX_CODE) lsbLike += 1
            sample += stride
        }
        return lsbLike > msbLike && lsbLike > 8
    }

    private fun copyP010Word(
        source: ByteBuffer,
        sourceOffset: Int,
        target: ByteArray,
        targetOffset: Int,
        lsbPacked: Boolean
    ) {
        val word = readLe16(source, sourceOffset)
        val normalized = if (lsbPacked) {
            (word and V10_MAX_CODE) shl P010_VALID_BITS_SHIFT
        } else {
            word and P010_MSB_MASK
        }
        target[targetOffset] = normalized.toByte()
        target[targetOffset + 1] = (normalized ushr 8).toByte()
    }

    private fun readLe16(source: ByteBuffer, offset: Int): Int =
        (source.get(offset).toInt() and 0xFF) or ((source.get(offset + 1).toInt() and 0xFF) shl 8)

    private companion object {
        const val TAG = "RecordDevelopP010Reader"
        const val MIME_HEVC = "video/hevc"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val EOS_DRAIN_STALL_LIMIT = 300
        const val DEFAULT_FPS = 30
        const val P010_VALID_BITS_SHIFT = 6
        const val P010_MSB_MASK = 0xFFC0
        const val V10_MAX_CODE = 1023
    }
}

internal object RecordDevelopP010OutputBufferLayout {
    fun rowBytesFromFormatStride(formatStride: Int, width: Int): Int {
        val minRowBytes = width.coerceAtLeast(1) * Short.SIZE_BYTES
        return when {
            formatStride <= 0 -> minRowBytes
            formatStride >= minRowBytes -> formatStride
            else -> formatStride * Short.SIZE_BYTES
        }.coerceAtLeast(minRowBytes)
    }

    fun requiredBytes(width: Int, height: Int, formatStride: Int, sliceHeight: Int): Long {
        val safeHeight = height.coerceAtLeast(1)
        val chromaHeight = (safeHeight + 1) / 2
        val safeSliceHeight = sliceHeight.coerceAtLeast(safeHeight)
        val rowBytes = rowBytesFromFormatStride(formatStride, width)
        return rowBytes.toLong() * safeSliceHeight.toLong() +
            rowBytes.toLong() * chromaHeight.toLong()
    }
}
