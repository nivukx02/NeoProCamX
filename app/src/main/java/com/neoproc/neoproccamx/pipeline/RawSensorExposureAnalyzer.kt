package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.rendering.BayerPattern
import com.neoproc.neoproccamx.rendering.RawBufferDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/** Lightweight RAW-domain meter used by sensor-linear NDR; it never modifies or downsamples output. */
internal data class RawSensorExposureMetrics(
    val averageLuma: Float,
    val medianLuma: Float,
    val p95Luma: Float,
    val p99Luma: Float,
    val p99Red: Float,
    val p99Green: Float,
    val p99Blue: Float,
    val highlightClipFraction: Float,
    val redHighlightClipFraction: Float,
    val sampleCount: Int
)

/** Small immutable-by-ownership RAW sample; it never retains an Image, Plane, or ByteBuffer. */
internal data class RawSensorExposureCodeSnapshot(
    val samplesRggb: IntArray,
    val quadCount: Int
)

/** Exact sensor codes paired later with the matching frame's dynamic black/white calibration. */
internal data class RawSensorExposureSampleSnapshot(
    val samplesRggb: IntArray,
    val quadCount: Int,
    val blackLevelRggb: FloatArray,
    val whiteLevel: Int
)

internal object RawSensorExposureAnalyzer {
    private const val TARGET_QUADS = 1_024
    private const val CLIP_THRESHOLD = 0.985f

    fun analyze(
        plane: ByteBuffer,
        rowStrideBytes: Int,
        pixelStrideBytes: Int,
        descriptor: RawBufferDescriptor
    ): RawSensorExposureMetrics? = snapshot(
        plane = plane,
        rowStrideBytes = rowStrideBytes,
        pixelStrideBytes = pixelStrideBytes,
        descriptor = descriptor
    )?.let(::analyze)

    fun snapshot(
        plane: ByteBuffer,
        rowStrideBytes: Int,
        pixelStrideBytes: Int,
        descriptor: RawBufferDescriptor
    ): RawSensorExposureSampleSnapshot? = snapshotCodes(
        plane = plane,
        rowStrideBytes = rowStrideBytes,
        pixelStrideBytes = pixelStrideBytes,
        width = descriptor.width,
        height = descriptor.height,
        bayerPattern = descriptor.bayerPattern
    )?.let { codes -> calibrate(codes, descriptor) }

    /**
     * Copies at most [TARGET_QUADS] sparse Bayer quads without needing CaptureResult metadata. This
     * lets Image and result callbacks join in either order while retaining no camera-owned object.
     */
    fun snapshotCodes(
        plane: ByteBuffer,
        rowStrideBytes: Int,
        pixelStrideBytes: Int,
        width: Int,
        height: Int,
        bayerPattern: BayerPattern
    ): RawSensorExposureCodeSnapshot? {
        if (width < 2 || height < 2) return null
        if (rowStrideBytes <= 0 || pixelStrideBytes < 2) return null
        val quadColumns = width / 2
        val quadRows = height / 2
        if (quadColumns <= 0 || quadRows <= 0) return null
        val totalQuads = quadColumns.toLong() * quadRows.toLong()
        val requestedQuads = totalQuads.coerceAtMost(TARGET_QUADS.toLong()).toInt()
        val source = plane.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val samples = IntArray(requestedQuads * 4)
        val quad = IntArray(4)
        var quadCount = 0

        for (sampleIndex in 0 until requestedQuads) {
            // Use the centre of each equal-area linear bin. The hard loop bound guarantees no sensor
            // aspect ratio or resolution can exceed TARGET_QUADS.
            val linearQuad = ((sampleIndex.toLong() * 2L + 1L) * totalQuads) /
                (requestedQuads.toLong() * 2L)
            val qx = (linearQuad % quadColumns.toLong()).toInt()
            val qy = (linearQuad / quadColumns.toLong()).toInt()
            quad.fill(0)
            var validQuad = true
            for (dy in 0..1) {
                for (dx in 0..1) {
                    val x = qx * 2 + dx
                    val y = qy * 2 + dy
                    val offset = y.toLong() * rowStrideBytes.toLong() +
                        x.toLong() * pixelStrideBytes.toLong()
                    if (offset < 0L || offset + 1L >= source.limit().toLong()) {
                        validQuad = false
                        continue
                    }
                    val channel = semanticChannel(bayerPattern, dx, dy)
                    quad[channel] = source.getShort(offset.toInt()).toInt() and 0xFFFF
                }
            }
            if (!validQuad) continue
            val base = quadCount * 4
            samples[base] = quad[0]
            samples[base + 1] = quad[1]
            samples[base + 2] = quad[2]
            samples[base + 3] = quad[3]
            quadCount++
        }
        if (quadCount == 0) return null
        return RawSensorExposureCodeSnapshot(
            samplesRggb = if (quadCount == requestedQuads) samples else samples.copyOf(quadCount * 4),
            quadCount = quadCount
        )
    }

    fun calibrate(
        codes: RawSensorExposureCodeSnapshot,
        descriptor: RawBufferDescriptor
    ): RawSensorExposureSampleSnapshot? {
        if (codes.quadCount <= 0 || codes.quadCount > TARGET_QUADS ||
            codes.samplesRggb.size < codes.quadCount * 4 || descriptor.whiteLevel <= 0
        ) return null
        val levels = descriptor.blackLevelRggb.takeIf { values ->
            values.size == 4 && values.all {
                it.isFinite() && it >= 0f && it < descriptor.whiteLevel
            }
        }?.copyOf() ?: FloatArray(4) { descriptor.blackLevel.toFloat() }
        return RawSensorExposureSampleSnapshot(
            samplesRggb = codes.samplesRggb,
            quadCount = codes.quadCount,
            blackLevelRggb = levels,
            whiteLevel = descriptor.whiteLevel
        )
    }

    fun analyze(snapshot: RawSensorExposureSampleSnapshot): RawSensorExposureMetrics? {
        val quadCount = snapshot.quadCount
        if (quadCount <= 0 || snapshot.samplesRggb.size < quadCount * 4 ||
            snapshot.blackLevelRggb.size != 4 || snapshot.whiteLevel <= 0
        ) return null
        val luma = FloatArray(quadCount)
        val red = FloatArray(quadCount)
        val green = FloatArray(quadCount)
        val blue = FloatArray(quadCount)
        val normalized = FloatArray(4)
        var lumaSum = 0.0
        var broadlyClippedQuads = 0
        var redClipped = 0
        for (sampleIndex in 0 until quadCount) {
            val base = sampleIndex * 4
            for (channel in 0..3) {
                val black = snapshot.blackLevelRggb[channel]
                normalized[channel] = (
                    (snapshot.samplesRggb[base + channel].toFloat() - black) /
                        (snapshot.whiteLevel.toFloat() - black).coerceAtLeast(1f)
                    ).coerceAtLeast(0f)
            }
                val greenValue = 0.5f * (normalized[1] + normalized[2])
                // Sensor-domain luminance proxy. Percentiles are used for exposure only; colour is never
                // derived from this meter, so the calibrated ACEScg path remains the sole colour pipeline.
                val quadLuma = (0.25f * normalized[0] + 0.50f * greenValue + 0.25f * normalized[3])
                    .coerceIn(0f, 4f)
                val clippedChannels = normalized.count { it >= CLIP_THRESHOLD }
                // Exposure protection is area-based. One clipped colour sample (a lamp, LED or specular)
                // must not darken the complete frame; require clipped green, two clipped channels, or a
                // genuinely near-white quad. The per-channel p99 values below still expose colour-channel
                // headroom to the controller without converting a sparse outlier into a global EV command.
                if (greenValue >= CLIP_THRESHOLD || clippedChannels >= 2 || quadLuma >= 0.975f) {
                    broadlyClippedQuads++
                }
                if (normalized[0] >= CLIP_THRESHOLD) redClipped++
                luma[sampleIndex] = quadLuma
                red[sampleIndex] = normalized[0]
                green[sampleIndex] = greenValue
                blue[sampleIndex] = normalized[3]
                lumaSum += quadLuma.toDouble()
        }
        luma.sort()
        red.sort()
        green.sort()
        blue.sort()
        fun percentile(values: FloatArray, fraction: Float): Float {
            val index = (ceil(quadCount.toDouble() * fraction.toDouble()).toInt() - 1)
                .coerceIn(0, quadCount - 1)
            return values[index]
        }
        return RawSensorExposureMetrics(
            averageLuma = (lumaSum / quadCount.toDouble()).toFloat(),
            medianLuma = percentile(luma, 0.50f),
            p95Luma = percentile(luma, 0.95f),
            p99Luma = percentile(luma, 0.99f),
            p99Red = percentile(red, 0.99f),
            p99Green = percentile(green, 0.99f),
            p99Blue = percentile(blue, 0.99f),
            highlightClipFraction = broadlyClippedQuads.toFloat() / quadCount.toFloat(),
            redHighlightClipFraction = redClipped.toFloat() / quadCount.toFloat(),
            sampleCount = quadCount * 4
        )
    }

    /** 0=R, 1=Gr, 2=Gb, 3=B, matching the Vulkan demosaic UBO. */
    private fun semanticChannel(pattern: BayerPattern, xInQuad: Int, yInQuad: Int): Int {
        val position = yInQuad * 2 + xInQuad
        return when (pattern) {
            BayerPattern.RGGB -> position
            BayerPattern.GRBG -> when (position) { 0 -> 1; 1 -> 0; 2 -> 3; else -> 2 }
            BayerPattern.GBRG -> when (position) { 0 -> 2; 1 -> 3; 2 -> 0; else -> 1 }
            BayerPattern.BGGR -> when (position) { 0 -> 3; 1 -> 2; 2 -> 1; else -> 0 }
        }
    }
}
