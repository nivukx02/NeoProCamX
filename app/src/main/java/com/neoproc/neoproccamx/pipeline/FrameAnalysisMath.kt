package com.neoproc.neoproccamx.pipeline

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal data class PqHighlightPressure(
    val luminanceFraction: Float,
    val redChannelFraction: Float
)

internal data class FrameHighlightMetrics(
    val redClipFraction: Float,
    val pqPressure: PqHighlightPressure
)

internal object FrameAnalysisMath {
    fun computeSceneStats(
        width: Int,
        height: Int,
        luma: ByteArray,
        maxStatsLongEdge: Int,
        allowNative: Boolean = true
    ): SceneStats {
        if (allowNative) {
            NativeAnalysisIngress.computeSceneStats(
                width = width,
                height = height,
                luma = luma,
                maxStatsLongEdge = maxStatsLongEdge
            )?.let { return it }
        }
        val histogram = IntArray(256)
        val effectiveMaxStatsLongEdge = max(1, maxStatsLongEdge)
        val step = max(1, max(width, height) / effectiveMaxStatsLongEdge)
        var lumaSum = 0L
        var sampleCount = 0

        for (y in 0 until height step step) {
            val rowOffset = y * width
            for (x in 0 until width step step) {
                val value = luma[rowOffset + x].toInt() and 0xFF
                histogram[value]++
                lumaSum += value.toLong()
                sampleCount++
            }
        }

        val averageLuma =
            if (sampleCount == 0) 0.5f else (lumaSum.toFloat() / sampleCount.toFloat() / 255f)
        return SceneStats(histogram, averageLuma)
    }

    /**
     * Reconstructed-red pre-clip code on the 8-bit limited-range scale.
     *
     * Code 235 is already clipped nominal white. Meter at code 224 (~95% signal) so Camera2's
     * multi-frame AE latency can lower exposure before the red photosites/YUV channel flatten.
     */
    const val DEFAULT_RED_CLIP_CODE = 224

    /** NDR exposure starts protecting before highlights reach its 5000-nit master ceiling. */
    const val DEFAULT_PQ_HIGHLIGHT_NITS = 1_000f

    private const val LIMITED_Y_BLACK_8 = 16f
    private const val LIMITED_Y_RANGE_8 = 219f
    private const val LIMITED_CHROMA_MID_8 = 128f
    private const val LIMITED_CHROMA_RANGE_8 = 224f
    private const val LIMITED_Y_BLACK_10 = 64f
    private const val LIMITED_Y_RANGE_10 = 876f
    private const val LIMITED_CHROMA_MID_10 = 512f
    private const val LIMITED_CHROMA_RANGE_10 = 896f
    private const val BT2020_RED_FROM_CR = 1.4746f

    /**
     * Monotonic ST.2084 interval bounds. A power-of-two grid has exactly representable Float edges,
     * so every signal is rigorously enclosed by its two table entries. Positive BT.2020 weights keep
     * the weighted Float sum monotonic: a lower sum above the threshold or an upper sum below it is an
     * exact decision. Only the narrow unresolved interval executes the original channel equations.
     */
    private class ExactPqIntervalBounds {
        private val boundaryNits = FloatArray(PQ_INTERVAL_BIN_COUNT + 1) { index ->
            pqSignalToNits(index.toFloat() / PQ_INTERVAL_BIN_COUNT.toFloat())
        }

        fun lowerNits(signal: Float): Float = boundaryNits[lowerIndex(signal)]

        fun upperNits(signal: Float): Float {
            val clamped = signal.coerceIn(0f, 1f)
            val scaled = clamped * PQ_INTERVAL_BIN_COUNT.toFloat()
            val lower = scaled.toInt().coerceIn(0, PQ_INTERVAL_BIN_COUNT)
            val upper = if (
                lower == PQ_INTERVAL_BIN_COUNT || scaled == lower.toFloat()
            ) lower else lower + 1
            return boundaryNits[upper]
        }

        private fun lowerIndex(signal: Float): Int =
            (signal.coerceIn(0f, 1f) * PQ_INTERVAL_BIN_COUNT.toFloat())
                .toInt()
                .coerceIn(0, PQ_INTERVAL_BIN_COUNT)
    }

    /**
     * Exact ST.2084 channel lookup for the compact 8-bit analysis proxy.
     *
     * The proxy has only 256 possible Y/C values, so reconstructed red and blue each have 65,536
     * possible inputs. Computing those channel EOTFs on every pixel repeated the same expensive
     * powers tens of thousands of times per frame. Signal reconstruction is eagerly tabled because it
     * is cheap and used by every sample. The expensive ST.2084 values are evaluated only when a sampled
     * channel actually reaches the threshold boundary, then retained by exact table index; green remains
     * exact and is memoized by its Float bit pattern. This avoids 131,072 startup EOTF evaluations without
     * changing a code value, threshold, sample, coefficient, precision, or classification.
     */
    private class ExactEightBitPqLookup {
        val lumaSignal = FloatArray(256)
        val chromaSignal = FloatArray(256)
        val redUnclampedSignal = FloatArray(256 * 256)
        val redSignal = FloatArray(256 * 256)
        val defaultRedClipMinimumLumaCode = IntArray(256)
        private val redNits = FloatArray(256 * 256).also { it.fill(Float.NaN) }
        val blueSignal = FloatArray(256 * 256)
        private val blueNits = FloatArray(256 * 256).also { it.fill(Float.NaN) }
        private val pqIntervals = ExactPqIntervalBounds()
        private val greenNitsCache = ThreadLocal.withInitial { ExactFloatNitsCache(15) }

        init {
            for (code in 0..255) {
                lumaSignal[code] = (code.toFloat() - LIMITED_Y_BLACK_8) / LIMITED_Y_RANGE_8
                chromaSignal[code] =
                    (code.toFloat() - LIMITED_CHROMA_MID_8) / LIMITED_CHROMA_RANGE_8
            }
            for (yCode in 0..255) {
                val ySignal = lumaSignal[yCode]
                val row = yCode shl 8
                for (chromaCode in 0..255) {
                    val chroma = chromaSignal[chromaCode]
                    val index = row or chromaCode
                    val unclampedRed = ySignal + BT2020_RED_FROM_CR * chroma
                    val red = unclampedRed.coerceIn(0f, 1f)
                    val blue = (ySignal + 1.8814f * chroma).coerceIn(0f, 1f)
                    redUnclampedSignal[index] = unclampedRed
                    redSignal[index] = red
                    blueSignal[index] = blue
                }
            }
            val defaultRedPreClipSignal =
                (DEFAULT_RED_CLIP_CODE - LIMITED_Y_BLACK_8) / LIMITED_Y_RANGE_8
            for (vCode in 0..255) {
                var yCode = 0
                while (
                    yCode <= 255 &&
                    redUnclampedSignal[(yCode shl 8) or vCode] < defaultRedPreClipSignal
                ) {
                    yCode++
                }
                // 256 is an exact never-clipped sentinel for an unsigned 8-bit luma code.
                defaultRedClipMinimumLumaCode[vCode] = yCode
            }
        }

        fun redNits(index: Int): Float {
            val cached = redNits[index]
            if (!cached.isNaN()) return cached
            return pqSignalToNits(redSignal[index]).also { redNits[index] = it }
        }

        fun blueNits(index: Int): Float {
            val cached = blueNits[index]
            if (!cached.isNaN()) return cached
            return pqSignalToNits(blueSignal[index]).also { blueNits[index] = it }
        }

        fun luminanceAtOrAboveThreshold(
            rSignal: Float,
            gSignal: Float,
            bSignal: Float,
            redIndex: Int,
            blueIndex: Int,
            thresholdNits: Float
        ): Boolean {
            val lowerLuminance =
                0.2627f * pqIntervals.lowerNits(rSignal) +
                    0.6780f * pqIntervals.lowerNits(gSignal) +
                    0.0593f * pqIntervals.lowerNits(bSignal)
            if (lowerLuminance >= thresholdNits) return true
            val upperLuminance =
                0.2627f * pqIntervals.upperNits(rSignal) +
                    0.6780f * pqIntervals.upperNits(gSignal) +
                    0.0593f * pqIntervals.upperNits(bSignal)
            if (upperLuminance < thresholdNits) return false
            return 0.2627f * redNits(redIndex) +
                0.6780f * greenNits(gSignal) +
                0.0593f * blueNits(blueIndex) >= thresholdNits
        }

        fun greenNits(signal: Float): Float = greenNitsCache.get()!!.getOrCompute(signal)
    }

    /** Small direct-mapped cache keyed by the exact IEEE-754 signal bits. */
    private class ExactFloatNitsCache(capacityPower: Int) {
        private val capacity = 1 shl capacityPower
        private val shift = 32 - capacityPower
        private val keys = IntArray(capacity)
        private val values = FloatArray(capacity)
        private val occupied = BooleanArray(capacity)

        fun getOrCompute(signal: Float): Float {
            val key = signal.toRawBits()
            val slot = (key * -0x61c88647).ushr(shift)
            if (occupied[slot] && keys[slot] == key) return values[slot]
            val value = pqSignalToNits(signal)
            values[slot] = value
            keys[slot] = key
            occupied[slot] = true
            return value
        }
    }

    private val exactEightBitPqLookupLock = Any()
    @Volatile private var exactEightBitPqLookup: ExactEightBitPqLookup? = null
    @Volatile private var exactPqWarmupChecksum = 0

    /** Exercise the real compact traversal before camera frames arrive so ART compiles its hot loop. */
    private fun prewarmCompactPqClassifier(lookup: ExactEightBitPqLookup) {
        val width = 160
        val height = 120
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val y = ByteArray(width * height) { index ->
            (16 + ((index * 73 + index / width * 29) % 220)).toByte()
        }
        val u = ByteArray(chromaWidth * chromaHeight) { index ->
            (16 + ((index * 47 + index / chromaWidth * 19) % 225)).toByte()
        }
        val v = ByteArray(chromaWidth * chromaHeight) { index ->
            (16 + ((index * 61 + index / chromaWidth * 31) % 225)).toByte()
        }
        val frame = RawYuvFrame(
            width = width,
            height = height,
            yBytes = y,
            uBytes = u,
            vBytes = v,
            yRowStride = width,
            yPixelStride = 1,
            uRowStride = chromaWidth,
            vRowStride = chromaWidth,
            uPixelStride = 1,
            vPixelStride = 1,
            timestamp = 0L,
            bitDepth = 8
        )
        var checksum = 0
        for (threshold in floatArrayOf(203f, 4_000f, 1_000f, 1_000f)) {
            val metrics = computeCompactEightBitHighlightMetrics(
                frame = frame,
                maxStatsLongEdge = width,
                redClipCode = DEFAULT_RED_CLIP_CODE,
                threshold = threshold,
                lookup = lookup
            ) ?: continue
            checksum = checksum xor metrics.redClipFraction.toRawBits()
            checksum = checksum xor metrics.pqPressure.luminanceFraction.toRawBits()
            checksum = checksum xor metrics.pqPressure.redChannelFraction.toRawBits()
        }
        // SDR deliberately bypasses the heavier PQ classifier in production. Exercise that exact
        // compact red-only traversal too, otherwise its first camera-frame invocation remains cold
        // even though the related PQ traversal above has already run.
        repeat(2) {
            val redClipFraction = computeCompactEightBitRedClipFraction(
                frame = frame,
                maxStatsLongEdge = width,
                redClipCode = DEFAULT_RED_CLIP_CODE,
                lookup = lookup
            ) ?: 0f
            checksum = checksum xor redClipFraction.toRawBits()
        }
        repeat(3) {
            checksum = checksum xor computeSceneStats(width, height, y, width).avgLuma.toRawBits()
        }
        exactPqWarmupChecksum = checksum
    }

    /** Build off the camera and UI threads; live analysis uses the exact legacy path until ready. */
    fun warmExactPqTables() {
        if (exactEightBitPqLookup != null) return
        synchronized(exactEightBitPqLookupLock) {
            if (exactEightBitPqLookup == null) {
                val lookup = ExactEightBitPqLookup()
                prewarmCompactPqClassifier(lookup)
                exactEightBitPqLookup = lookup
            }
        }
    }

    fun computeHighlightMetrics(
        frame: RawYuvFrame,
        maxStatsLongEdge: Int,
        sourcePqCandidate: Boolean,
        redClipCode: Int = DEFAULT_RED_CLIP_CODE,
        highlightNits: Float = DEFAULT_PQ_HIGHLIGHT_NITS
    ): FrameHighlightMetrics {
        if (frame.bitDepth == 8) {
            val lookup = exactEightBitPqLookup
            if (lookup != null) {
                if (sourcePqCandidate) {
                    val threshold = highlightNits.takeIf(Float::isFinite)?.coerceIn(203f, 10_000f)
                        ?: DEFAULT_PQ_HIGHLIGHT_NITS
                    computeCompactEightBitHighlightMetrics(
                        frame = frame,
                        maxStatsLongEdge = maxStatsLongEdge,
                        redClipCode = redClipCode,
                        threshold = threshold,
                        lookup = lookup
                    )?.let { return it }
                } else {
                    computeCompactEightBitRedClipFraction(
                        frame = frame,
                        maxStatsLongEdge = maxStatsLongEdge,
                        redClipCode = redClipCode,
                        lookup = lookup
                    )?.let { redClip ->
                        return FrameHighlightMetrics(redClip, PqHighlightPressure(0f, 0f))
                    }
                }
            }
        }
        return FrameHighlightMetrics(
            redClipFraction = computeRedHighlightClipFraction(
                frame = frame,
                maxStatsLongEdge = maxStatsLongEdge,
                redClipCode = redClipCode
            ),
            pqPressure = if (sourcePqCandidate) {
                computePqHighlightPressure(
                    frame = frame,
                    maxStatsLongEdge = maxStatsLongEdge,
                    highlightNits = highlightNits
                )
            } else {
                PqHighlightPressure(0f, 0f)
            }
        )
    }

    /**
     * Fraction of (down-sampled) pixels whose reconstructed RED channel is at/near clipping, 0..1.
     *
     * WHY: [computeSceneStats] builds a LUMA-only histogram, and BT.2020 luma weights red at just
     * 0.2627, so a warm subject can hard-clip its RED channel while luma still reads moderate — the
     * capture highlight-protect exposure (luma-driven) then never reacts and the red channel clips into
     * the shared HDR capture (the NDR/NIVUKX "red skin"). This reconstructs BT.2020-NCL red from the
     * limited-range Y + Cr(V) planes so the capture meter can react to the actual clipping channel, per
     * the ITU-T H.Sup15 lesson that luma Y' is not luminance. Both 8-bit YUV and P010 are accepted;
     * P010 is decoded for metering only and never changes the 10-bit capture/encoder data.
     */
    fun computeRedHighlightClipFraction(
        frame: RawYuvFrame,
        maxStatsLongEdge: Int,
        redClipCode: Int = DEFAULT_RED_CLIP_CODE
    ): Float {
        if (frame.bitDepth != 8 && frame.bitDepth != 10) return 0f
        val luma = frame.yBytes
        val cr = frame.vBytes
        val width = frame.width
        val height = frame.height
        if (luma.isEmpty() || cr.isEmpty() || width <= 0 || height <= 0) return 0f
        val effectiveMaxStatsLongEdge = max(1, maxStatsLongEdge)
        val step = max(1, max(width, height) / effectiveMaxStatsLongEdge)
        val redPreClipSignal =
            (redClipCode.coerceIn(16, 235) - LIMITED_Y_BLACK_8) / LIMITED_Y_RANGE_8
        var total = 0
        var redClipped = 0
        // The live scene proxy is compact planar 8-bit. Avoid three nullable readCode calls (and
        // their boxed Float results) per sample while retaining the generic P010 path for callers
        // that supply native 10-bit analysis frames.
        if (frame.bitDepth == 8) {
            var y = 0
            while (y < height) {
                val yRow = y * frame.yRowStride
                val crRow = (y / 2) * frame.vRowStride
                var x = 0
                while (x < width) {
                    val yIdx = yRow + x * frame.yPixelStride
                    val crIdx = crRow + (x / 2) * frame.vPixelStride
                    if (yIdx in luma.indices && crIdx in cr.indices) {
                        val yCode = (luma[yIdx].toInt() and 0xFF).toFloat()
                        val crCode = (cr[crIdx].toInt() and 0xFF).toFloat()
                        val ySignal = (yCode - LIMITED_Y_BLACK_8) / LIMITED_Y_RANGE_8
                        val crSignal = (crCode - LIMITED_CHROMA_MID_8) / LIMITED_CHROMA_RANGE_8
                        total++
                        if (ySignal + BT2020_RED_FROM_CR * crSignal >= redPreClipSignal) {
                            redClipped++
                        }
                    }
                    x += step
                }
                y += step
            }
            if (total == 0) return 0f
            return (redClipped.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }
        var y = 0
        while (y < height) {
            val yRow = y * frame.yRowStride
            val crRow = (y / 2) * frame.vRowStride
            var x = 0
            while (x < width) {
                val yIdx = yRow + x * frame.yPixelStride
                val crIdx = crRow + (x / 2) * frame.vPixelStride
                if (yIdx in luma.indices && crIdx in cr.indices) {
                    val yCode = readCode(luma, yIdx, frame.bitDepth)
                    val crCode = readCode(cr, crIdx, frame.bitDepth)
                    if (yCode != null && crCode != null) {
                        val ySignal: Float
                        val crSignal: Float
                        if (frame.bitDepth == 10) {
                            ySignal = (yCode - LIMITED_Y_BLACK_10) / LIMITED_Y_RANGE_10
                            crSignal = (crCode - LIMITED_CHROMA_MID_10) / LIMITED_CHROMA_RANGE_10
                        } else {
                            ySignal = (yCode - LIMITED_Y_BLACK_8) / LIMITED_Y_RANGE_8
                            crSignal = (crCode - LIMITED_CHROMA_MID_8) / LIMITED_CHROMA_RANGE_8
                        }
                        val reconstructedRed = ySignal + BT2020_RED_FROM_CR * crSignal
                        total++
                        if (reconstructedRed >= redPreClipSignal) redClipped++
                    }
                }
                x += step
            }
            y += step
        }
        if (total == 0) return 0f
        return (redClipped.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * PQ-aware highlight pressure from a limited-range BT.2020 YUV analysis proxy.
     *
     * A PQ code is absolute luminance: legal-range white (code 235/940) represents roughly
     * 10,000 nits, not the usable NDR highlight ceiling. The legacy code-domain clip meter therefore
     * reported zero while live highlights were already above the NDR mastering range. Reconstruct
     * R'G'B', apply the PQ EOTF, and meter in nits so the existing exposure governor receives real
     * highlight evidence. This operates only on the small CPU proxy; capture/record buffers stay P010.
     */
    fun computePqHighlightPressure(
        frame: RawYuvFrame,
        maxStatsLongEdge: Int,
        highlightNits: Float = DEFAULT_PQ_HIGHLIGHT_NITS
    ): PqHighlightPressure {
        if (frame.bitDepth != 8 && frame.bitDepth != 10) return PqHighlightPressure(0f, 0f)
        if (frame.yBytes.isEmpty() || frame.uBytes.isEmpty() || frame.vBytes.isEmpty()) {
            return PqHighlightPressure(0f, 0f)
        }
        val threshold = highlightNits.takeIf(Float::isFinite)?.coerceIn(203f, 10_000f)
            ?: DEFAULT_PQ_HIGHLIGHT_NITS
        // ST.2084 is strictly monotonic. Most monitor pixels are wholly below (or wholly above) the
        // highlight threshold, so compare in signal space first and reserve the three expensive EOTF
        // evaluations for mixed-channel boundary pixels. The mixed case still executes the original
        // exact luminance math; no sample, threshold, precision, or classification is approximated.
        val thresholdSignal = pqNitsToSignal(threshold)
        val step = max(1, max(frame.width, frame.height) / max(1, maxStatsLongEdge))
        if (frame.bitDepth == 8) {
            val lookup = exactEightBitPqLookup
            if (lookup != null) {
                computeCompactEightBitHighlightMetrics(
                    frame = frame,
                    maxStatsLongEdge = maxStatsLongEdge,
                    redClipCode = DEFAULT_RED_CLIP_CODE,
                    threshold = threshold,
                    lookup = lookup
                )?.let { return it.pqPressure }
            }
            return computePqHighlightPressureEightBit(
                frame = frame,
                threshold = threshold,
                thresholdSignal = thresholdSignal,
                step = step
            )
        }
        var total = 0
        var luminanceHighlights = 0
        var redHighlights = 0
        var y = 0
        while (y < frame.height) {
            val yRow = y * frame.yRowStride
            val chromaRow = (y / 2)
            var x = 0
            while (x < frame.width) {
                val yCode = readCode(frame.yBytes, yRow + x * frame.yPixelStride, frame.bitDepth)
                val uCode = readCode(
                    frame.uBytes,
                    chromaRow * frame.uRowStride + (x / 2) * frame.uPixelStride,
                    frame.bitDepth
                )
                val vCode = readCode(
                    frame.vBytes,
                    chromaRow * frame.vRowStride + (x / 2) * frame.vPixelStride,
                    frame.bitDepth
                )
                if (yCode != null && uCode != null && vCode != null) {
                    val tenBit = frame.bitDepth == 10
                    val ySignal = if (tenBit) {
                        (yCode - LIMITED_Y_BLACK_10) / LIMITED_Y_RANGE_10
                    } else {
                        (yCode - LIMITED_Y_BLACK_8) / LIMITED_Y_RANGE_8
                    }
                    val cb = if (tenBit) {
                        (uCode - LIMITED_CHROMA_MID_10) / LIMITED_CHROMA_RANGE_10
                    } else {
                        (uCode - LIMITED_CHROMA_MID_8) / LIMITED_CHROMA_RANGE_8
                    }
                    val cr = if (tenBit) {
                        (vCode - LIMITED_CHROMA_MID_10) / LIMITED_CHROMA_RANGE_10
                    } else {
                        (vCode - LIMITED_CHROMA_MID_8) / LIMITED_CHROMA_RANGE_8
                    }
                    val rSignal = (ySignal + 1.4746f * cr).coerceIn(0f, 1f)
                    val gSignal = (ySignal - 0.164553f * cb - 0.571353f * cr).coerceIn(0f, 1f)
                    val bSignal = (ySignal + 1.8814f * cb).coerceIn(0f, 1f)
                    val luminanceAtOrAboveThreshold = pqLuminanceAtOrAboveThreshold(
                        rSignal, gSignal, bSignal, thresholdSignal, threshold
                    )
                    val redAtOrAboveThreshold =
                        pqSignalAtOrAboveThreshold(rSignal, thresholdSignal, threshold)
                    total++
                    if (luminanceAtOrAboveThreshold) luminanceHighlights++
                    if (redAtOrAboveThreshold) redHighlights++
                }
                x += step
            }
            y += step
        }
        if (total == 0) return PqHighlightPressure(0f, 0f)
        return PqHighlightPressure(
            luminanceFraction = (luminanceHighlights.toFloat() / total).coerceIn(0f, 1f),
            redChannelFraction = (redHighlights.toFloat() / total).coerceIn(0f, 1f)
        )
    }

    /**
     * One exact traversal for red pre-clip and both PQ pressure metrics. This fast path is deliberately
     * limited to the manager's compact planar proxy; unusual caller layouts retain the generic path.
     */
    private fun computeCompactEightBitHighlightMetrics(
        frame: RawYuvFrame,
        maxStatsLongEdge: Int,
        redClipCode: Int,
        threshold: Float,
        lookup: ExactEightBitPqLookup
    ): FrameHighlightMetrics? {
        val width = frame.width
        val height = frame.height
        if (width <= 0 || height <= 0) return null
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val compact =
            frame.bitDepth == 8 &&
                frame.yPixelStride == 1 && frame.yRowStride == width &&
                frame.uPixelStride == 1 && frame.vPixelStride == 1 &&
                frame.uRowStride == chromaWidth && frame.vRowStride == chromaWidth &&
                frame.yBytes.size >= width * height &&
                frame.uBytes.size >= chromaWidth * chromaHeight &&
                frame.vBytes.size >= chromaWidth * chromaHeight
        if (!compact) return null

        val thresholdSignal = pqNitsToSignal(threshold)
        val belowBoundary = thresholdSignal - PQ_SIGNAL_BOUNDARY_EPSILON
        val aboveBoundary = thresholdSignal + PQ_SIGNAL_BOUNDARY_EPSILON
        val redPreClipSignal =
            (redClipCode.coerceIn(16, 235) - LIMITED_Y_BLACK_8) / LIMITED_Y_RANGE_8
        val step = max(1, max(width, height) / max(1, maxStatsLongEdge))
        val yBytes = frame.yBytes
        val uBytes = frame.uBytes
        val vBytes = frame.vBytes
        var total = 0
        var redClipped = 0
        var luminanceHighlights = 0
        var redHighlights = 0
        var y = 0
        while (y < height) {
            val yRow = y * width
            val chromaRow = (y / 2) * chromaWidth
            var x = 0
            while (x < width) {
                val yCode = yBytes[yRow + x].toInt() and 0xFF
                val chromaIndex = chromaRow + x / 2
                val uCode = uBytes[chromaIndex].toInt() and 0xFF
                val vCode = vBytes[chromaIndex].toInt() and 0xFF
                val redIndex = (yCode shl 8) or vCode
                val blueIndex = (yCode shl 8) or uCode
                val rSignal = lookup.redSignal[redIndex]
                val bSignal = lookup.blueSignal[blueIndex]
                var gSignal = lookup.lumaSignal[yCode] -
                    0.164553f * lookup.chromaSignal[uCode] -
                    0.571353f * lookup.chromaSignal[vCode]
                if (gSignal < 0f) gSignal = 0f else if (gSignal > 1f) gSignal = 1f

                total++
                if (lookup.redUnclampedSignal[redIndex] >= redPreClipSignal) redClipped++

                val maxSignal = max(rSignal, max(gSignal, bSignal))
                if (maxSignal >= belowBoundary) {
                    val minSignal = min(rSignal, min(gSignal, bSignal))
                    if (
                        minSignal >= aboveBoundary ||
                        lookup.luminanceAtOrAboveThreshold(
                            rSignal = rSignal,
                            gSignal = gSignal,
                            bSignal = bSignal,
                            redIndex = redIndex,
                            blueIndex = blueIndex,
                            thresholdNits = threshold
                        )
                    ) {
                        luminanceHighlights++
                    }
                }
                if (
                    rSignal >= aboveBoundary ||
                    (rSignal >= belowBoundary && lookup.redNits(redIndex) >= threshold)
                ) {
                    redHighlights++
                }
                x += step
            }
            y += step
        }
        if (total == 0) {
            return FrameHighlightMetrics(0f, PqHighlightPressure(0f, 0f))
        }
        return FrameHighlightMetrics(
            redClipFraction = (redClipped.toFloat() / total.toFloat()).coerceIn(0f, 1f),
            pqPressure = PqHighlightPressure(
                luminanceFraction =
                    (luminanceHighlights.toFloat() / total.toFloat()).coerceIn(0f, 1f),
                redChannelFraction =
                    (redHighlights.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            )
        )
    }

    private fun computeCompactEightBitRedClipFraction(
        frame: RawYuvFrame,
        maxStatsLongEdge: Int,
        redClipCode: Int,
        lookup: ExactEightBitPqLookup
    ): Float? {
        val width = frame.width
        val height = frame.height
        if (width <= 0 || height <= 0) return null
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        if (
            frame.bitDepth != 8 ||
            frame.yPixelStride != 1 || frame.yRowStride != width ||
            frame.vPixelStride != 1 || frame.vRowStride != chromaWidth ||
            frame.yBytes.size < width * height ||
            frame.vBytes.size < chromaWidth * chromaHeight
        ) return null
        val redPreClipSignal =
            (redClipCode.coerceIn(16, 235) - LIMITED_Y_BLACK_8) / LIMITED_Y_RANGE_8
        val step = max(1, max(width, height) / max(1, maxStatsLongEdge))
        if (step == 1 && redClipCode.coerceIn(16, 235) == DEFAULT_RED_CLIP_CODE) {
            val minimumLumaCode = lookup.defaultRedClipMinimumLumaCode
            val yBytes = frame.yBytes
            val vBytes = frame.vBytes
            var redClipped = 0
            var sourceY = 0
            var chromaRow = 0
            while (sourceY < height) {
                val firstLumaRow = sourceY * width
                val hasSecondLumaRow = sourceY + 1 < height
                val secondLumaRow = firstLumaRow + width
                var chromaX = 0
                while (chromaX < chromaWidth) {
                    val sourceX = chromaX * 2
                    val clipAtLumaCode =
                        minimumLumaCode[vBytes[chromaRow + chromaX].toInt() and 0xFF]
                    if ((yBytes[firstLumaRow + sourceX].toInt() and 0xFF) >= clipAtLumaCode) {
                        redClipped++
                    }
                    if (
                        sourceX + 1 < width &&
                        (yBytes[firstLumaRow + sourceX + 1].toInt() and 0xFF) >= clipAtLumaCode
                    ) {
                        redClipped++
                    }
                    if (hasSecondLumaRow) {
                        if ((yBytes[secondLumaRow + sourceX].toInt() and 0xFF) >= clipAtLumaCode) {
                            redClipped++
                        }
                        if (
                            sourceX + 1 < width &&
                            (yBytes[secondLumaRow + sourceX + 1].toInt() and 0xFF) >= clipAtLumaCode
                        ) {
                            redClipped++
                        }
                    }
                    chromaX++
                }
                sourceY += 2
                chromaRow += chromaWidth
            }
            val total = width * height
            return if (total == 0) 0f else {
                (redClipped.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            }
        }
        var total = 0
        var redClipped = 0
        var y = 0
        while (y < height) {
            val yRow = y * width
            val chromaRow = (y / 2) * chromaWidth
            var x = 0
            while (x < width) {
                val yCode = frame.yBytes[yRow + x].toInt() and 0xFF
                val vCode = frame.vBytes[chromaRow + x / 2].toInt() and 0xFF
                total++
                if (lookup.redUnclampedSignal[(yCode shl 8) or vCode] >= redPreClipSignal) {
                    redClipped++
                }
                x += step
            }
            y += step
        }
        return if (total == 0) 0f else (redClipped.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

    private fun computePqHighlightPressureEightBit(
        frame: RawYuvFrame,
        threshold: Float,
        thresholdSignal: Float,
        step: Int
    ): PqHighlightPressure {
        val yBytes = frame.yBytes
        val uBytes = frame.uBytes
        val vBytes = frame.vBytes
        var total = 0
        var luminanceHighlights = 0
        var redHighlights = 0
        var y = 0
        while (y < frame.height) {
            val yRow = y * frame.yRowStride
            val chromaRow = y / 2
            var x = 0
            while (x < frame.width) {
                val yIndex = yRow + x * frame.yPixelStride
                val uIndex = chromaRow * frame.uRowStride + (x / 2) * frame.uPixelStride
                val vIndex = chromaRow * frame.vRowStride + (x / 2) * frame.vPixelStride
                if (yIndex in yBytes.indices && uIndex in uBytes.indices && vIndex in vBytes.indices) {
                    val yCode = (yBytes[yIndex].toInt() and 0xFF).toFloat()
                    val uCode = (uBytes[uIndex].toInt() and 0xFF).toFloat()
                    val vCode = (vBytes[vIndex].toInt() and 0xFF).toFloat()
                    val ySignal = (yCode - LIMITED_Y_BLACK_8) / LIMITED_Y_RANGE_8
                    val cb = (uCode - LIMITED_CHROMA_MID_8) / LIMITED_CHROMA_RANGE_8
                    val cr = (vCode - LIMITED_CHROMA_MID_8) / LIMITED_CHROMA_RANGE_8
                    val rSignal = (ySignal + 1.4746f * cr).coerceIn(0f, 1f)
                    val gSignal = (ySignal - 0.164553f * cb - 0.571353f * cr).coerceIn(0f, 1f)
                    val bSignal = (ySignal + 1.8814f * cb).coerceIn(0f, 1f)
                    total++
                    if (pqLuminanceAtOrAboveThreshold(
                            rSignal,
                            gSignal,
                            bSignal,
                            thresholdSignal,
                            threshold
                        )
                    ) luminanceHighlights++
                    if (pqSignalAtOrAboveThreshold(rSignal, thresholdSignal, threshold)) {
                        redHighlights++
                    }
                }
                x += step
            }
            y += step
        }
        if (total == 0) return PqHighlightPressure(0f, 0f)
        return PqHighlightPressure(
            luminanceFraction = (luminanceHighlights.toFloat() / total).coerceIn(0f, 1f),
            redChannelFraction = (redHighlights.toFloat() / total).coerceIn(0f, 1f)
        )
    }

    private fun pqLuminanceAtOrAboveThreshold(
        rSignal: Float,
        gSignal: Float,
        bSignal: Float,
        thresholdSignal: Float,
        thresholdNits: Float
    ): Boolean {
        val minSignal = min(rSignal, min(gSignal, bSignal))
        val maxSignal = max(rSignal, max(gSignal, bSignal))
        return when {
            maxSignal < thresholdSignal - PQ_SIGNAL_BOUNDARY_EPSILON -> false
            minSignal >= thresholdSignal + PQ_SIGNAL_BOUNDARY_EPSILON -> true
            else -> {
                val rNits = pqSignalToNits(rSignal)
                val gNits = pqSignalToNits(gSignal)
                val bNits = pqSignalToNits(bSignal)
                0.2627f * rNits + 0.6780f * gNits + 0.0593f * bNits >= thresholdNits
            }
        }
    }

    private fun pqSignalAtOrAboveThreshold(
        signal: Float,
        thresholdSignal: Float,
        thresholdNits: Float
    ): Boolean = when {
        signal < thresholdSignal - PQ_SIGNAL_BOUNDARY_EPSILON -> false
        signal >= thresholdSignal + PQ_SIGNAL_BOUNDARY_EPSILON -> true
        else -> pqSignalToNits(signal) >= thresholdNits
    }

    private fun pqSignalToNits(signal: Float): Float {
        val e = signal.coerceIn(0f, 1f).toDouble()
        val ep = e.pow(1.0 / 78.84375)
        val numerator = (ep - 0.8359375).coerceAtLeast(0.0)
        val denominator = (18.8515625 - 18.6875 * ep).coerceAtLeast(1e-12)
        return (10_000.0 * (numerator / denominator).pow(1.0 / 0.1593017578125)).toFloat()
    }

    private fun pqNitsToSignal(nits: Float): Float {
        val normalized = (nits.coerceIn(0f, 10_000f) / 10_000f).toDouble()
        val lm = normalized.pow(0.1593017578125)
        val numerator = 0.8359375 + 18.8515625 * lm
        val denominator = 1.0 + 18.6875 * lm
        return (numerator / denominator).pow(78.84375).toFloat().coerceIn(0f, 1f)
    }

    /** Read an 8-bit code or a little-endian, MSB-aligned P010 10-bit code. */
    private fun readCode(bytes: ByteArray, index: Int, bitDepth: Int): Float? {
        if (bitDepth == 8) {
            if (index !in bytes.indices) return null
            return (bytes[index].toInt() and 0xFF).toFloat()
        }
        if (index !in bytes.indices || index + 1 !in bytes.indices) return null
        val lo = bytes[index].toInt() and 0xFF
        val hi = bytes[index + 1].toInt() and 0xFF
        return (((hi shl 8) or lo) ushr 6).coerceIn(0, 1023).toFloat()
    }

    private const val PQ_SIGNAL_BOUNDARY_EPSILON = 1e-6f
    private const val PQ_INTERVAL_BIN_COUNT = 4_096

}
