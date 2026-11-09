package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NeoGamutCat16Math
import com.neoproc.neoproccamx.core.NeoLogCurve
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.pipeline.RawExposureNormalizationPolicy
import com.neoproc.neoproccamx.pipeline.RawNeoLogDevelop
import com.neoproc.neoproccamx.rendering.RawLensShadingGridPolicy
import com.neoproc.neoproccamx.rendering.XYZ_D50_TO_ACESCG
import kotlin.math.roundToInt

/** A small opaque ARGB_8888 image (row-major [argb] ints) ready to hand to Bitmap.createBitmap. */
data class ThumbnailImage(val width: Int, val height: Int, val argb: IntArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ThumbnailImage) return false
        return width == other.width && height == other.height && argb.contentEquals(other.argb)
    }
    override fun hashCode(): Int = (width * 31 + height) * 31 + argb.contentHashCode()
}

/**
 * Raw12ThumbnailRenderer — renders a small "identify this clip" thumbnail from a RAW clip's FIRST
 * frame by running the exact NeoLog develop and downscaling to an ARGB image. Pure (no Android), so
 * the develop→downscale is unit-testable; the caller wraps [ThumbnailImage.argb] in a Bitmap
 * (Bitmap.createBitmap(argb, w, h, ARGB_8888)).
 *
 * The develop runs at 10-bit (the NeoLog quantiser supports 10/12/16 only) and is shifted to 8-bit
 * for display. Large mosaics are Bayer-preserving downsampled before develop so opening Settings does
 * not allocate a full-resolution RGB float working set.
 */
object Raw12ThumbnailRenderer {

    class ReusableWorkspace internal constructor() {
        internal val develop = RawNeoLogDevelop.ReusableWorkspace()
        private var previewTransferVariant: NeoLogCurve.Variant? = null
        private var previewTransferLut = ByteArray(0)
        private var playbackMetadata: RawDevelopMetadata? = null
        private var playbackVariant: NeoLogCurve.Variant? = null
        private var playbackExposureStops = Float.NaN
        private var playbackCalibration: PlaybackCalibration? = null

        internal fun previewTransferLut(variant: NeoLogCurve.Variant): ByteArray {
            if (previewTransferVariant == variant && previewTransferLut.size == PREVIEW_TRANSFER_LUT_SIZE) {
                return previewTransferLut
            }
            val maxLinear = NeoLogCurve.maxLinear(variant)
            val scale = maxLinear / (PREVIEW_TRANSFER_LUT_SIZE - 1).toFloat()
            val rebuilt = ByteArray(PREVIEW_TRANSFER_LUT_SIZE)
            for (index in rebuilt.indices) {
                rebuilt[index] = (NeoLogCurve.encode(index.toFloat() * scale, variant) * 255f)
                    .roundToInt()
                    .coerceIn(0, 255)
                    .toByte()
            }
            previewTransferVariant = variant
            previewTransferLut = rebuilt
            return rebuilt
        }

        internal fun playbackCalibration(
            meta: RawDevelopMetadata,
            variant: NeoLogCurve.Variant,
            exposureStops: Float,
            reducedWidth: Int,
            reducedHeight: Int
        ): PlaybackCalibration {
            val cached = playbackCalibration
            if (cached != null && playbackMetadata == meta && playbackVariant == variant &&
                playbackExposureStops == exposureStops && cached.reducedWidth == reducedWidth &&
                cached.reducedHeight == reducedHeight
            ) {
                return cached
            }

            val outputWidth = reducedWidth / 2
            val outputHeight = reducedHeight / 2
            val transfer = previewTransferLut(variant)
            val transferMax = NeoLogCurve.maxLinear(variant)
            val sensorToAcescg = NeoGamutCat16Math.multiply3x3(XYZ_D50_TO_ACESCG, meta.sensorToXyz)
            val black = FloatArray(4) { channel ->
                meta.blackLevelRggb[channel].takeIf { it.isFinite() } ?: 0f
            }
            val inverseRange = FloatArray(4) { channel ->
                1f / (meta.whiteLevel.toFloat() - black[channel]).coerceAtLeast(1f)
            }
            val whiteBalance = floatArrayOf(
                meta.whiteBalanceR,
                meta.whiteBalanceGr,
                meta.whiteBalanceGb,
                meta.whiteBalanceB
            )
            val nativePattern = meta.bayerPatternValue.takeIf { it in 0..3 } ?: 3
            val cfaOffsets = IntArray(4) { semanticChannel ->
                val cellPosition = semanticChannel xor nativePattern
                (if (cellPosition and 2 != 0) reducedWidth else 0) + (cellPosition and 1)
            }
            val lensShadingRgb = if (meta.lensShadingCorrectionEnabled) {
                val gains = FloatArray(
                    Math.multiplyExact(Math.multiplyExact(outputWidth, outputHeight), 3)
                )
                val scratch = FloatArray(3)
                var offset = 0
                for (y in 0 until outputHeight) {
                    val sourceY = y * 2
                    for (x in 0 until outputWidth) {
                        playbackLensShadingRgb(
                            x = x * 2 + 0.5f,
                            y = sourceY + 0.5f,
                            width = reducedWidth,
                            height = reducedHeight,
                            gridRggb = meta.lensShadingGridRggb,
                            output = scratch
                        )
                        gains[offset++] = scratch[0]
                        gains[offset++] = scratch[1]
                        gains[offset++] = scratch[2]
                    }
                }
                gains
            } else {
                null
            }
            return PlaybackCalibration(
                reducedWidth = reducedWidth,
                reducedHeight = reducedHeight,
                transfer = transfer,
                transferMax = transferMax,
                transferScale = (transfer.size - 1).toFloat() / transferMax,
                exposureScale = RawExposureNormalizationPolicy.exposureScale(exposureStops),
                sensorToAcescg = sensorToAcescg,
                black = black,
                inverseRange = inverseRange,
                whiteBalance = whiteBalance,
                cfaOffsets = cfaOffsets,
                lensShadingRgb = lensShadingRgb
            ).also { rebuilt ->
                playbackMetadata = meta
                playbackVariant = variant
                playbackExposureStops = exposureStops
                playbackCalibration = rebuilt
            }
        }
    }

    internal class PlaybackCalibration(
        val reducedWidth: Int,
        val reducedHeight: Int,
        val transfer: ByteArray,
        val transferMax: Float,
        val transferScale: Float,
        val exposureScale: Float,
        val sensorToAcescg: FloatArray,
        val black: FloatArray,
        val inverseRange: FloatArray,
        val whiteBalance: FloatArray,
        val cfaOffsets: IntArray,
        val lensShadingRgb: FloatArray?
    )

    const val DEFAULT_MAX_LONG_EDGE = 256

    fun render(
        firstFrameSamples: IntArray,
        meta: RawDevelopMetadata,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
        variant: NeoLogCurve.Variant = NeoLogCurve.Variant.BASE,
        exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS,
        reusableArgb: IntArray? = null,
        workspace: ReusableWorkspace? = null
    ): ThumbnailImage {
        require(maxLongEdge >= 2) { "RAW thumbnail maxLongEdge must be at least 2" }
        val reduced = reduceBayerForThumbnail(firstFrameSamples, meta, maxLongEdge)
        val developed = RawNeoLogDevelop.develop(
            reduced.samples,
            reduced.meta,
            variant,
            exposureStops,
            outputBitDepth = 10,
            workspace = workspace?.develop
        )
        val outputSize = developed.width * developed.height
        val out = reusableArgb?.takeIf { it.size == outputSize } ?: IntArray(outputSize)
        for (y in 0 until developed.height) {
            for (x in 0 until developed.width) {
                val i = (y * developed.width + x) * 3
                // 10-bit code -> 8-bit channel for display.
                val r = (developed.rgb[i].toInt() and 0x3FF) shr 2
                val g = (developed.rgb[i + 1].toInt() and 0x3FF) shr 2
                val b = (developed.rgb[i + 2].toInt() and 0x3FF) shr 2
                out[y * developed.width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return ThumbnailImage(developed.width, developed.height, out)
    }

    /**
     * Interactive RAW-video monitoring path. A Bayer-preserving reduction keeps one complete RGGB
     * cell per display pixel, then applies the recorded per-channel calibration, sensor-to-ACEScg
     * matrix, exposure, and N-Log transfer. The expensive full-resolution offline elite-detail and
     * 10-bit dither stages remain mandatory for conversion output and are intentionally not spent on
     * this 8-bit monitoring proxy. A dense transfer LUT removes millions of per-frame logarithms.
     */
    fun renderPlayback(
        firstFrameSamples: IntArray,
        meta: RawDevelopMetadata,
        maxLongEdge: Int,
        variant: NeoLogCurve.Variant = NeoLogCurve.Variant.BASE,
        exposureStops: Float = RawExposureNormalizationPolicy.DEFAULT_EXPOSURE_STOPS,
        reusableArgb: IntArray? = null,
        workspace: ReusableWorkspace? = null
    ): ThumbnailImage {
        require(maxLongEdge >= 2) { "RAW playback maxLongEdge must be at least 2" }
        val ownedWorkspace = workspace ?: ReusableWorkspace()
        val reduced = reduceBayerForThumbnail(
            firstFrameSamples,
            meta,
            playbackBayerLongEdge(maxLongEdge)
        )
        val reducedWidth = reduced.meta.width
        val reducedHeight = reduced.meta.height
        require(reducedWidth >= 2 && reducedHeight >= 2) { "RAW playback Bayer frame is too small" }
        val outputWidth = reducedWidth / 2
        val outputHeight = reducedHeight / 2
        val outputSize = outputWidth * outputHeight
        val out = reusableArgb?.takeIf { it.size == outputSize } ?: IntArray(outputSize)
        val calibration = ownedWorkspace.playbackCalibration(
            meta = reduced.meta,
            variant = variant,
            exposureStops = exposureStops,
            reducedWidth = reducedWidth,
            reducedHeight = reducedHeight
        )
        val transfer = calibration.transfer
        val sensorToAcescg = calibration.sensorToAcescg
        val black = calibration.black
        val inverseRange = calibration.inverseRange
        val whiteBalance = calibration.whiteBalance
        val cfaOffsets = calibration.cfaOffsets
        val lensShadingRgb = calibration.lensShadingRgb
        var outputIndex = 0
        for (y in 0 until outputHeight) {
            val sourceY = y * 2
            val row0 = sourceY * reducedWidth
            for (x in 0 until outputWidth) {
                val sourceX = x * 2
                val cell = row0 + sourceX
                var red = calibratedSample(
                    reduced.samples[cell + cfaOffsets[0]], black[0], inverseRange[0], whiteBalance[0]
                )
                val greenA = calibratedSample(
                    reduced.samples[cell + cfaOffsets[1]], black[1], inverseRange[1], whiteBalance[1]
                )
                val greenB = calibratedSample(
                    reduced.samples[cell + cfaOffsets[2]], black[2], inverseRange[2], whiteBalance[2]
                )
                var blue = calibratedSample(
                    reduced.samples[cell + cfaOffsets[3]], black[3], inverseRange[3], whiteBalance[3]
                )
                var green = (greenA + greenB) * 0.5f
                if (lensShadingRgb != null) {
                    val shadingOffset = outputIndex * 3
                    red *= lensShadingRgb[shadingOffset]
                    green *= lensShadingRgb[shadingOffset + 1]
                    blue *= lensShadingRgb[shadingOffset + 2]
                }
                val acesR = sensorToAcescg[0] * red + sensorToAcescg[1] * green + sensorToAcescg[2] * blue
                val acesG = sensorToAcescg[3] * red + sensorToAcescg[4] * green + sensorToAcescg[5] * blue
                val acesB = sensorToAcescg[6] * red + sensorToAcescg[7] * green + sensorToAcescg[8] * blue
                val displayR = transferCode(
                    acesR * calibration.exposureScale,
                    calibration.transferScale,
                    calibration.transferMax,
                    transfer
                )
                val displayG = transferCode(
                    acesG * calibration.exposureScale,
                    calibration.transferScale,
                    calibration.transferMax,
                    transfer
                )
                val displayB = transferCode(
                    acesB * calibration.exposureScale,
                    calibration.transferScale,
                    calibration.transferMax,
                    transfer
                )
                out[outputIndex++] =
                    (0xFF shl 24) or (displayR shl 16) or (displayG shl 8) or displayB
            }
        }
        return ThumbnailImage(outputWidth, outputHeight, out)
    }

    private fun calibratedSample(sample: Int, black: Float, inverseRange: Float, gain: Float): Float =
        ((sample.toFloat() - black) * inverseRange).coerceIn(0f, 1f) * gain

    internal fun playbackBayerLongEdge(outputLongEdge: Int): Int =
        Math.multiplyExact(outputLongEdge, 2)

    private fun transferCode(
        linear: Float,
        scale: Float,
        maxLinear: Float,
        transfer: ByteArray
    ): Int {
        val index = when {
            linear.isNaN() || linear <= 0f -> 0
            linear >= maxLinear -> transfer.lastIndex
            else -> (linear * scale).roundToInt().coerceIn(0, transfer.lastIndex)
        }
        return transfer[index].toInt() and 0xFF
    }

    private fun playbackLensShadingRgb(
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        gridRggb: FloatArray,
        output: FloatArray
    ) {
        val gridSide = RawLensShadingGridPolicy.GRID_SIDE
        val channels = RawLensShadingGridPolicy.CHANNEL_COUNT
        val gridX = (x / (width - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) * (gridSide - 1)
        val gridY = (y / (height - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) * (gridSide - 1)
        val x0 = gridX.toInt().coerceAtMost(gridSide - 2)
        val y0 = gridY.toInt().coerceAtMost(gridSide - 2)
        val fx = gridX - x0.toFloat()
        val fy = gridY - y0.toFloat()
        val i00 = (y0 * gridSide + x0) * channels
        val i10 = i00 + channels
        val i01 = i00 + gridSide * channels
        val i11 = i01 + channels
        fun gain(channel: Int): Float {
            val top = gridRggb[i00 + channel] + (gridRggb[i10 + channel] - gridRggb[i00 + channel]) * fx
            val bottom = gridRggb[i01 + channel] + (gridRggb[i11 + channel] - gridRggb[i01 + channel]) * fx
            return (top + (bottom - top) * fy).coerceIn(0.25f, 8f)
        }
        output[0] = gain(0)
        output[1] = (gain(1) + gain(2)) * 0.5f
        output[2] = gain(3)
    }

    private const val PREVIEW_TRANSFER_LUT_SIZE = 65_536

    internal data class ReducedBayer(
        val samples: IntArray,
        val meta: RawDevelopMetadata
    )

    internal fun reduceBayerForThumbnail(
        samples: IntArray,
        meta: RawDevelopMetadata,
        maxLongEdge: Int
    ): ReducedBayer {
        require(samples.size >= meta.width * meta.height) { "RAW thumbnail frame is truncated" }
        val longEdge = maxOf(meta.width, meta.height)
        if (longEdge <= maxLongEdge) return ReducedBayer(samples, meta)

        val scale = (longEdge + maxLongEdge - 1) / maxLongEdge
        val reducedWidth = (meta.width / scale).coerceAtLeast(2).and(-2)
        val reducedHeight = (meta.height / scale).coerceAtLeast(2).and(-2)
        val reduced = IntArray(reducedWidth * reducedHeight)
        val cellStride = scale * 2
        for (y in 0 until reducedHeight) {
            val sourceY = ((y / 2) * cellStride + (y and 1)).coerceAtMost(meta.height - 1)
            for (x in 0 until reducedWidth) {
                val sourceX = ((x / 2) * cellStride + (x and 1)).coerceAtMost(meta.width - 1)
                reduced[y * reducedWidth + x] = samples[sourceY * meta.width + sourceX]
            }
        }
        return ReducedBayer(
            samples = reduced,
            meta = meta.copy(width = reducedWidth, height = reducedHeight)
        )
    }
}
