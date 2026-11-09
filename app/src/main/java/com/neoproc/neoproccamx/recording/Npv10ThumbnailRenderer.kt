package com.neoproc.neoproccamx.recording

import androidx.annotation.Keep
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Npv10ThumbnailRenderer — renders a small "identify this clip" thumbnail from an NPV10 master's
 * FIRST frame. NPV10 masters are the compressed 10-bit LOG/HDR video masters (P010 / planar YUV420
 * 10-bit, or a raw-packed Bayer mosaic); this decodes the first frame and downscales it to an ARGB
 * image the settings clip row can wrap in a Bitmap.
 *
 * Thumbnail and private-master playback resolve the colour profile embedded in the NPV10 header.
 * SDR retains the allocation-free BT.709 path; NeoLog, HLG, and BT.2020/PQ receive their matching
 * non-destructive monitor transform. Raw-packed formats use a normalized grayscale of the mosaic.
 * Any failure returns null (the row then shows a text-only label), so a thumbnail never blocks the
 * converter list.
 *
 * The reader uses plain File I/O — safe because NPV10 masters live in app-private storage
 * (filesDir), which is fully readable without scoped-storage indirection.
 */
object Npv10ThumbnailRenderer {

    const val DEFAULT_MAX_LONG_EDGE = 256

    fun render(file: File, maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE): ThumbnailImage? = try {
        Npv10FileReader(file).use { reader ->
            val header = reader.header
            val frame = reader.readNextFrame()
            if (frame == null) null else renderFrameForPlayback(header, frame, maxLongEdge)
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Display-only decode used by the private-master player. It never writes, converts, publishes, or
     * mutates the NPV10 source. Keeping the colour decode here also guarantees thumbnail/player parity.
     */
    internal fun renderFrameForPlayback(
        header: Npv10FileHeader,
        frame: Npv10FrameRecord,
        maxLongEdge: Int,
        reusableArgb: IntArray? = null
    ): ThumbnailImage = renderPayloadForPlayback(
        header = header,
        width = frame.width,
        height = frame.height,
        maxLongEdge = maxLongEdge,
        reusableArgb = reusableArgb,
        sample16At = { sampleIndex -> sample16(frame.payload, sampleIndex) }
    )

    /** Direct-buffer variant used by the player so decoded 4K P010 never enters a frame-sized heap array. */
    internal fun renderDirectFrameForPlayback(
        header: Npv10FileHeader,
        frame: Npv10DirectFrameRecord,
        maxLongEdge: Int,
        reusableArgb: IntArray? = null
    ): ThumbnailImage {
        val payload = frame.payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val width = frame.width
        val height = frame.height
        val longEdge = maxOf(width, height)
        val scale = if (longEdge <= maxLongEdge) 1 else (longEdge + maxLongEdge - 1) / maxLongEdge
        val destinationWidth = maxOf(1, width / scale)
        val destinationHeight = maxOf(1, height / scale)
        val outputSize = Math.multiplyExact(destinationWidth, destinationHeight)
        val output = reusableArgb?.takeIf { it.size == outputSize } ?: IntArray(outputSize)
        val colorPlan = Npv10PlaybackColorPolicy.resolve(header)
        if (header.payloadFormat == Npv10PayloadFormat.P010_LE &&
            colorPlan.directBt709Sdr &&
            Npv10PlaybackNativeRenderer.renderP010Bt709(
                payload = payload,
                width = width,
                height = height,
                scale = scale,
                output = output
            )
        ) {
            return ThumbnailImage(destinationWidth, destinationHeight, output)
        }
        return renderPayloadForPlayback(
            header = header,
            width = width,
            height = height,
            maxLongEdge = maxLongEdge,
            reusableArgb = output,
            sample16At = { sampleIndex -> sample16(payload, sampleIndex) }
        )
    }

    private inline fun renderPayloadForPlayback(
        header: Npv10FileHeader,
        width: Int,
        height: Int,
        maxLongEdge: Int,
        reusableArgb: IntArray?,
        sample16At: (Int) -> Int
    ): ThumbnailImage {
        val w = width
        val h = height
        val longEdge = maxOf(w, h)
        val scale = if (longEdge <= maxLongEdge) 1 else (longEdge + maxLongEdge - 1) / maxLongEdge
        val dstW = maxOf(1, w / scale)
        val dstH = maxOf(1, h / scale)
        val outSize = dstW * dstH
        val out = reusableArgb?.takeIf { it.size == outSize } ?: IntArray(outSize)

        val format = header.payloadFormat
        val colorPlan = Npv10PlaybackColorPolicy.resolve(header)
        val ySize = w * h
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val cbCrPlane = ySize + cw * ch

        for (y in 0 until dstH) {
            val sy = (y * scale).coerceAtMost(h - 1)
            for (x in 0 until dstW) {
                val sx = (x * scale).coerceAtMost(w - 1)
                out[y * dstW + x] = when {
                    format.isRawPacked -> {
                        // NPV10 raw-packed words store the sample LEFT-shifted by 6 (the P010
                        // valid-bits shift, per Npv10FramePacker), so the top 10 bits are the
                        // high-order sample. Reading the raw word directly would saturate to white;
                        // normalize the top-10-bit value instead (works for both RAW12 and RAW14).
                        val hi10 = (sample16At(sy * w + sx) ushr 6).coerceIn(0, 1023)
                        val g8 = (hi10 * 255) / 1023
                        (0xFF shl 24) or (g8 shl 16) or (g8 shl 8) or g8
                    }
                    format == Npv10PayloadFormat.P010_LE -> {
                        val yv = sample16At(sy * w + sx) ushr 6
                        val cIndex = cbCrBase(ySize, (sx / 2), (sy / 2), cw)
                        val cb = sample16At(cIndex) ushr 6
                        val cr = sample16At(cIndex + 1) ushr 6
                        playbackYuvToArgb(colorPlan, yv, cb, cr)
                    }
                    else -> { // planar YUV420 (10-bit or 8-bit-expanded)
                        val yv = sample16At(sy * w + sx) and 0x3FF
                        val ci = (sy / 2) * cw + (sx / 2)
                        val cb = sample16At(ySize + ci) and 0x3FF
                        val cr = sample16At(cbCrPlane + ci) and 0x3FF
                        playbackYuvToArgb(colorPlan, yv, cb, cr)
                    }
                }
            }
        }
        return ThumbnailImage(dstW, dstH, out)
    }

    /** Interleaved P010 CbCr pair base sample index for a chroma coordinate. */
    private fun cbCrBase(ySize: Int, cx: Int, cy: Int, cw: Int): Int = ySize + (cy * cw + cx) * 2

    private fun sample16(payload: ByteArray, sampleIndex: Int): Int {
        val o = sampleIndex * 2
        if (o < 0 || o + 1 >= payload.size) return 0
        return (payload[o].toInt() and 0xFF) or ((payload[o + 1].toInt() and 0xFF) shl 8)
    }

    private fun sample16(payload: ByteBuffer, sampleIndex: Int): Int {
        val offset = sampleIndex * Short.SIZE_BYTES
        if (offset < 0 || offset + 1 >= payload.limit()) return 0
        return payload.getShort(offset).toInt() and 0xFFFF
    }

    private fun playbackYuvToArgb(
        colorPlan: Npv10PlaybackColorPolicy.Plan,
        y10: Int,
        cb10: Int,
        cr10: Int
    ): Int = if (colorPlan.directBt709Sdr) {
        yuvToArgb(y10, cb10, cr10)
    } else {
        Npv10PlaybackColorPolicy.yuvToDisplayArgb(colorPlan, y10, cb10, cr10)
    }

    /** Limited-range 10-bit BT.709 YCbCr → 8-bit sRGB-ish ARGB. Pure, unit-testable. */
    fun yuvToArgb(y10: Int, cb10: Int, cr10: Int): Int {
        val yCode = y10.coerceIn(0, 1023)
        val cbCode = cb10.coerceIn(0, 1023)
        val crCode = cr10.coerceIn(0, 1023)
        val yl = LUMA_TO_LINEAR[yCode]
        val r = yl + CR_TO_RED[crCode]
        val g = yl - CB_TO_GREEN[cbCode] - CR_TO_GREEN[crCode]
        val b = yl + CB_TO_BLUE[cbCode]
        val r8 = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val g8 = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val b8 = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
    }

    /*
     * These are the same BT.709 equations cached by 10-bit code value. Playback performs millions
     * of conversions per second; the compact lookup tables remove repeated division/multiplication
     * while retaining the original Float arithmetic and display result.
     */
    private val LUMA_TO_LINEAR = FloatArray(1_024) { code ->
        (code - 64).toFloat() / 876f
    }
    private val CB_TO_GREEN = FloatArray(1_024) { code ->
        0.1873f * ((code - 512).toFloat() / 896f)
    }
    private val CR_TO_GREEN = FloatArray(1_024) { code ->
        0.4681f * ((code - 512).toFloat() / 896f)
    }
    private val CR_TO_RED = FloatArray(1_024) { code ->
        1.5748f * ((code - 512).toFloat() / 896f)
    }
    private val CB_TO_BLUE = FloatArray(1_024) { code ->
        1.8556f * ((code - 512).toFloat() / 896f)
    }
}

/** Native display-only P010 sampler. It is byte-read-only and falls back to the Kotlin reference. */
@Keep
internal object Npv10PlaybackNativeRenderer {
    private const val NATIVE_LIBRARY = "neoproc_vulkan"

    private val available: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            System.loadLibrary(NATIVE_LIBRARY)
            true
        }.getOrDefault(false)
    }

    fun renderP010Bt709(
        payload: ByteBuffer,
        width: Int,
        height: Int,
        scale: Int,
        output: IntArray
    ): Boolean {
        if (!available || !payload.isDirect || width <= 0 || height <= 0 || scale <= 0) return false
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val expectedBytes = runCatching {
            Math.multiplyExact(
                Math.addExact(
                    Math.multiplyExact(width.toLong(), height.toLong()),
                    Math.multiplyExact(
                        Math.multiplyExact(chromaWidth.toLong(), chromaHeight.toLong()),
                        2L
                    )
                ),
                Short.SIZE_BYTES.toLong()
            )
        }.getOrElse { return false }
        if (expectedBytes > payload.limit().toLong()) return false
        return runCatching {
            nativeRenderP010Bt709(payload, width, height, scale, output)
        }.getOrDefault(false)
    }

    @JvmStatic
    private external fun nativeRenderP010Bt709(
        payload: ByteBuffer,
        width: Int,
        height: Int,
        scale: Int,
        output: IntArray
    ): Boolean
}
