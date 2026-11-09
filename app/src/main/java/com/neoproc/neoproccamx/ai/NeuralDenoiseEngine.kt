package com.neoproc.neoproccamx.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.hardware.HardwareBuffer
import android.os.PowerManager
import androidx.core.graphics.createBitmap
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class NeuralDenoiseEngine(context: Context) {

    data class DenoiseTile(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    internal data class BoundedDenoiseTile(
        val coreX: Int,
        val coreY: Int,
        val coreWidth: Int,
        val coreHeight: Int,
        val sourceX: Int,
        val sourceY: Int,
        val sourceWidth: Int,
        val sourceHeight: Int
    )

    data class DenoiseRequest(
        val estimatedIso: Float = 400f,
        val lumaThreshold: Float = AIConstants.DENOISE_LUMA_WEIGHT,
        val chromaThreshold: Float = AIConstants.DENOISE_CHROMA_WEIGHT,
        val texturePreservation: Float = 0.65f,
        val temporalWeight: Float = AIConstants.DENOISE_TEMPORAL_WEIGHT,
        val preferNeuralModel: Boolean = true,
        val thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE,
        val chromaSpeckleGuardStrength: Float = 0f,
        val flowX: FloatArray? = null,
        val flowY: FloatArray? = null
    )

    data class DenoiseResult(
        val pixels: IntArray,
        val usedNeuralModel: Boolean,
        val usedGuidedFallback: Boolean,
        val thermalFallback: Boolean
    )

    private val appContext = context.applicationContext
    private val inputBuffers = Array(2) { directModelBuffer() }
    private val outputBuffers = Array(2) { directModelBuffer() }
    private val preloaded = AtomicBoolean(false)
    private var nextBufferIndex = 0
    private var previousDenoisedRgb: FloatArray? = null
    private var previousWidth = 0
    private var previousHeight = 0

    fun markModelPreloaded() {
        preloaded.set(true)
    }

    @Synchronized
    fun denoisePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        request: DenoiseRequest = DenoiseRequest()
    ): DenoiseResult {
        val pixelCountLong = width.toLong() * height.toLong()
        if (pixels.isEmpty() || width <= 0 || height <= 0 ||
            pixelCountLong > Int.MAX_VALUE || pixels.size.toLong() < pixelCountLong
        ) {
            return DenoiseResult(pixels, usedNeuralModel = false, usedGuidedFallback = false, thermalFallback = false)
        }
        val pixelCount = pixelCountLong.toInt()

        val thermalFallback = request.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        if (request.preferNeuralModel && !thermalFallback &&
            pixelCount <= MAX_NEURAL_FRAME_PIXELS && isDenoiseModelAvailable()
        ) {
            runCatching {
                runBlocking(Dispatchers.Default) {
                    denoiseWithModel(pixels, width, height, request)
                }
            }.getOrNull()?.let { neuralPixels ->
                return DenoiseResult(
                    pixels = neuralPixels,
                    usedNeuralModel = true,
                    usedGuidedFallback = false,
                    thermalFallback = false
                )
            }
        }

        return DenoiseResult(
            pixels = guidedFallbackPixels(pixels, width, height, request),
            usedNeuralModel = false,
            usedGuidedFallback = true,
            thermalFallback = thermalFallback
        )
    }

    @Synchronized
    fun bindHardwareBufferToModelInput(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int
    ): ByteBuffer? {
        val buffer = inputBuffers[nextBufferIndex]
        nextBufferIndex = (nextBufferIndex + 1) and 1
        buffer.clear()
        return if (nativeBindHardwareBuffer(hardwareBuffer, buffer, width, height)) {
            buffer.rewind()
            buffer
        } else {
            null
        }
    }

    private fun isDenoiseModelAvailable(): Boolean {
        return runCatching {
            val engine = AINeuralEngine.getInstance(appContext)
            preloaded.get() || engine.isModelAvailable(AINeuralEngine.MODEL_DENOISING)
        }.getOrDefault(false)
    }

    private suspend fun denoiseWithModel(
        pixels: IntArray,
        width: Int,
        height: Int,
        request: DenoiseRequest
    ): IntArray? = withContext(Dispatchers.Default) {
        val accumulator = FloatArray(width * height * 3)
        val weights = FloatArray(width * height)
        val tiles = buildTileGrid(width, height)
        val engine = AINeuralEngine.getInstance(appContext)

        for (tile in tiles) {
            val tileBitmap = makeModelTileBitmap(pixels, width, height, tile)
            val result = try {
                engine.runDenoiseTile(
                    inputData = tileBitmap,
                    outputMapper = { output ->
                        extractFloatRgb(
                            output,
                            AIConstants.DENOISE_MODEL_INPUT_WIDTH * AIConstants.DENOISE_MODEL_INPUT_HEIGHT
                        )
                    }
                )
            } finally {
                if (!tileBitmap.isRecycled) tileBitmap.recycle()
            }
            val tileRgb = when (result) {
                is AINeuralEngine.InferenceResult.Success -> result.data
                // Release the full-frame neural accumulators before entering the bounded fallback.
                else -> return@withContext null
            }
            accumulateTile(tileRgb, accumulator, weights, width, height, tile)
        }

        val spatial = FloatArray(width * height * 3)
        for (i in weights.indices) {
            val weight = weights[i].coerceAtLeast(1.0e-6f)
            val base = i * 3
            spatial[base] = accumulator[base] / weight
            spatial[base + 1] = accumulator[base + 1] / weight
            spatial[base + 2] = accumulator[base + 2] / weight
        }

        val temporal = applyTemporalHistory(spatial, width, height, request)
        applyPostDenoiseChromaGuard(
            rgbFloatToPixelsWithTextureBlend(pixels, temporal, width, height, request),
            width,
            height,
            request
        )
    }

    private fun guidedFallbackPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        request: DenoiseRequest
    ): IntArray {
        val count = width * height
        if (count > MAX_FULL_FRAME_GUIDED_PIXELS) {
            return guidedFallbackPixelsTiled(pixels, width, height, request)
        }
        return guidedFallbackPixelsFullFrame(
            pixels = pixels,
            width = width,
            height = height,
            request = request,
            useTemporalHistory = true
        )
    }

    private fun guidedFallbackPixelsTiled(
        pixels: IntArray,
        width: Int,
        height: Int,
        request: DenoiseRequest
    ): IntArray {
        // A full-resolution frame cannot retain three float channels plus guided-filter intermediates
        // without risking heap exhaustion. Large inputs are normally independent stills; if a caller
        // requests temporal blending anyway, fail safely to spatial-only and clear history once so a
        // later neural/small-frame call can never blend against a stale, partially tiled frame.
        resetTemporalHistory()
        val output = IntArray(width * height)
        val spatialRequest = request.copy(temporalWeight = 0f, flowX = null, flowY = null)
        for (tile in buildBoundedTileGrid(width, height)) {
            val tilePixels = IntArray(tile.sourceWidth * tile.sourceHeight)
            for (row in 0 until tile.sourceHeight) {
                System.arraycopy(
                    pixels,
                    (tile.sourceY + row) * width + tile.sourceX,
                    tilePixels,
                    row * tile.sourceWidth,
                    tile.sourceWidth
                )
            }
            val filtered = guidedFallbackPixelsFullFrame(
                pixels = tilePixels,
                width = tile.sourceWidth,
                height = tile.sourceHeight,
                request = spatialRequest,
                useTemporalHistory = false
            )
            val sourceCoreX = tile.coreX - tile.sourceX
            val sourceCoreY = tile.coreY - tile.sourceY
            for (row in 0 until tile.coreHeight) {
                System.arraycopy(
                    filtered,
                    (sourceCoreY + row) * tile.sourceWidth + sourceCoreX,
                    output,
                    (tile.coreY + row) * width + tile.coreX,
                    tile.coreWidth
                )
            }
        }
        return output
    }

    private fun guidedFallbackPixelsFullFrame(
        pixels: IntArray,
        width: Int,
        height: Int,
        request: DenoiseRequest,
        useTemporalHistory: Boolean
    ): IntArray {
        val count = width * height
        val y = FloatArray(count)
        val cb = FloatArray(count)
        val cr = FloatArray(count)
        for (i in 0 until count) {
            val pixel = pixels[i]
            val r = ((pixel shr 16) and 0xFF).toFloat()
            val g = ((pixel shr 8) and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            y[i] = luma
            cb[i] = -0.168736f * r - 0.331264f * g + 0.5f * b
            cr[i] = 0.5f * r - 0.418688f * g - 0.081312f * b
        }

        val lumaFiltered = applyGuidedFilter(y, y, width, height, r = 2)
        val cbFiltered = applyGuidedFilter(cb, y, width, height, r = 2)
        val crFiltered = applyGuidedFilter(cr, y, width, height, r = 2)
        val lumaMix = (request.lumaThreshold * (1.0f - request.texturePreservation * 0.72f)).coerceIn(0f, 0.55f)
        val chromaMix = (request.chromaThreshold * 0.72f).coerceIn(0f, 0.96f)

        val rgb = FloatArray(count * 3)
        for (i in 0 until count) {
            val outY = y[i] + (lumaFiltered[i] - y[i]) * lumaMix
            val outCb = cb[i] + (cbFiltered[i] - cb[i]) * chromaMix
            val outCr = cr[i] + (crFiltered[i] - cr[i]) * chromaMix
            val base = i * 3
            rgb[base] = ((outY + 1.402f * outCr) / 255f).coerceIn(0f, 1f)
            rgb[base + 1] = ((outY - 0.344136f * outCb - 0.714136f * outCr) / 255f).coerceIn(0f, 1f)
            rgb[base + 2] = ((outY + 1.772f * outCb) / 255f).coerceIn(0f, 1f)
        }

        val temporal = if (useTemporalHistory) {
            applyTemporalHistory(rgb, width, height, request)
        } else {
            rgb
        }
        return applyPostDenoiseChromaGuard(
            rgbFloatToPixelsWithTextureBlend(pixels, temporal, width, height, request),
            width,
            height,
            request
        )
    }

    private fun applyPostDenoiseChromaGuard(
        pixels: IntArray,
        width: Int,
        height: Int,
        request: DenoiseRequest
    ): IntArray {
        val guardStrength = request.chromaSpeckleGuardStrength.coerceIn(0f, 1f)
        if (guardStrength <= 0.001f) return pixels
        return PerceptualRefinementMath.applyOklabChromaSpeckleGuard(
            pixels = pixels,
            width = width,
            height = height,
            strength = guardStrength
        )
    }

    private fun applyTemporalHistory(
        spatialRgb: FloatArray,
        width: Int,
        height: Int,
        request: DenoiseRequest
    ): FloatArray {
        // Independent still tiles are spatial neighbours, not successive frames. With a zero temporal
        // request, do not retain or blend tile N into tile N+1; doing so both wastes a full-frame copy
        // and can ghost unrelated detail across the photograph.
        if (request.temporalWeight <= 0.001f) {
            resetTemporalHistory()
            return spatialRgb
        }
        val previous = previousDenoisedRgb
        val blended = if (previous != null && previousWidth == width && previousHeight == height) {
            val output = FloatArray(spatialRgb.size)
            val validFlowX = request.flowX?.takeIf { it.size >= width * height }
            val validFlowY = request.flowY?.takeIf { it.size >= width * height }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val vx = validFlowX?.get(index) ?: 0f
                    val vy = validFlowY?.get(index) ?: 0f
                    val sampleX = x - vx
                    val sampleY = y - vy
                    val boundaryGuard = if (
                        sampleX in 0f..(width - 1).toFloat() &&
                        sampleY in 0f..(height - 1).toFloat()
                    ) 1f else 0f
                    val motion2 = vx * vx + vy * vy
                    val base = index * 3
                    for (channel in 0..2) {
                        val current = spatialRgb[base + channel]
                        val prior = sampleBilinearRgb(previous, width, height, sampleX, sampleY, channel)
                        val diff = current - prior
                        val alpha = (request.temporalWeight.coerceIn(0f, 1f) *
                            exp(-TEMPORAL_MOTION_GAMMA * motion2) *
                            exp(-TEMPORAL_DIFFERENCE_GAMMA * diff * diff) *
                            boundaryGuard).coerceIn(0f, 1f)
                        output[base + channel] = (1f - alpha) * current + alpha * prior
                    }
                }
            }
            output
        } else {
            spatialRgb
        }

        previousDenoisedRgb = blended
        previousWidth = width
        previousHeight = height
        return blended
    }

    private fun resetTemporalHistory() {
        previousDenoisedRgb = null
        previousWidth = 0
        previousHeight = 0
    }

    private fun makeModelTileBitmap(
        pixels: IntArray,
        width: Int,
        height: Int,
        tile: DenoiseTile
    ): Bitmap {
        val modelWidth = AIConstants.DENOISE_MODEL_INPUT_WIDTH
        val modelHeight = AIConstants.DENOISE_MODEL_INPUT_HEIGHT
        val tilePixels = IntArray(modelWidth * modelHeight)
        for (y in 0 until modelHeight) {
            val srcY = (tile.y + (y + 0.5f) * tile.height / modelHeight).toInt().coerceIn(0, height - 1)
            for (x in 0 until modelWidth) {
                val srcX = (tile.x + (x + 0.5f) * tile.width / modelWidth).toInt().coerceIn(0, width - 1)
                tilePixels[y * modelWidth + x] = pixels[srcY * width + srcX]
            }
        }
        return createBitmap(modelWidth, modelHeight, Config.ARGB_8888).apply {
            setPixels(tilePixels, 0, modelWidth, 0, 0, modelWidth, modelHeight)
        }
    }

    private fun accumulateTile(
        tileRgb: FloatArray,
        accumulator: FloatArray,
        weights: FloatArray,
        width: Int,
        height: Int,
        tile: DenoiseTile
    ) {
        val modelWidth = AIConstants.DENOISE_MODEL_INPUT_WIDTH
        val modelHeight = AIConstants.DENOISE_MODEL_INPUT_HEIGHT
        for (y in 0 until tile.height) {
            val dstY = tile.y + y
            if (dstY !in 0 until height) continue
            val modelY = (y * modelHeight / tile.height).coerceIn(0, modelHeight - 1)
            for (x in 0 until tile.width) {
                val dstX = tile.x + x
                if (dstX !in 0 until width) continue
                val modelX = (x * modelWidth / tile.width).coerceIn(0, modelWidth - 1)
                val weight = frameFeatherWeight(x, y, tile, width, height)
                val srcBase = (modelY * modelWidth + modelX) * 3
                val dstIndex = dstY * width + dstX
                val dstBase = dstIndex * 3
                accumulator[dstBase] += tileRgb[srcBase].coerceIn(0f, 1f) * weight
                accumulator[dstBase + 1] += tileRgb[srcBase + 1].coerceIn(0f, 1f) * weight
                accumulator[dstBase + 2] += tileRgb[srcBase + 2].coerceIn(0f, 1f) * weight
                weights[dstIndex] += weight
            }
        }
    }

    private fun rgbFloatToPixelsWithTextureBlend(
        originalPixels: IntArray,
        denoisedRgb: FloatArray,
        width: Int,
        height: Int,
        request: DenoiseRequest
    ): IntArray {
        val out = IntArray(width * height)
        val texturePreserve = request.texturePreservation.coerceIn(0f, 1f)
        val lumaMix = (request.lumaThreshold * (1f - texturePreserve * 0.72f)).coerceIn(0f, 0.62f)
        val chromaMix = request.chromaThreshold.coerceIn(0f, 1f)
        for (i in out.indices) {
            val original = originalPixels[i]
            val a = (original ushr 24) and 0xFF
            val or = (original shr 16) and 0xFF
            val og = (original shr 8) and 0xFF
            val ob = original and 0xFF
            val originalY = 0.299f * or + 0.587f * og + 0.114f * ob

            val base = i * 3
            val nr = denoisedRgb[base] * 255f
            val ng = denoisedRgb[base + 1] * 255f
            val nb = denoisedRgb[base + 2] * 255f
            val neuralY = 0.299f * nr + 0.587f * ng + 0.114f * nb
            val y = originalY + (neuralY - originalY) * lumaMix

            val cbOriginal = -0.168736f * or - 0.331264f * og + 0.5f * ob
            val crOriginal = 0.5f * or - 0.418688f * og - 0.081312f * ob
            val cbNeural = -0.168736f * nr - 0.331264f * ng + 0.5f * nb
            val crNeural = 0.5f * nr - 0.418688f * ng - 0.081312f * nb
            val cb = cbOriginal + (cbNeural - cbOriginal) * chromaMix
            val cr = crOriginal + (crNeural - crOriginal) * chromaMix

            val r = (y + 1.402f * cr).roundToInt().coerceIn(0, 255)
            val g = (y - 0.344136f * cb - 0.714136f * cr).roundToInt().coerceIn(0, 255)
            val b = (y + 1.772f * cb).roundToInt().coerceIn(0, 255)
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private external fun nativeBindHardwareBuffer(
        hardwareBuffer: HardwareBuffer,
        directBuffer: ByteBuffer,
        width: Int,
        height: Int
    ): Boolean

    companion object {
        private const val TAG = "NeuralDenoiseEngine"
        private const val TILE_SIZE = 256
        private const val TILE_OVERLAP = 16
        private const val MAX_NEURAL_FRAME_PIXELS = 2_100_000
        private const val MAX_FULL_FRAME_GUIDED_PIXELS = 786_432
        private const val GUIDED_TILE_CORE_SIZE = 512
        private const val GUIDED_FILTER_RADIUS = 2
        private const val GUIDED_FILTER_EFFECTIVE_RADIUS = GUIDED_FILTER_RADIUS * 2
        private const val CHROMA_GUARD_RADIUS = 2
        private const val GUIDED_TILE_HALO = GUIDED_FILTER_EFFECTIVE_RADIUS + CHROMA_GUARD_RADIUS
        private const val TEMPORAL_MOTION_GAMMA = 1.8f
        private const val TEMPORAL_DIFFERENCE_GAMMA = 16f

        init {
            runCatching { System.loadLibrary("neoproc_vulkan") }
                .onFailure { NeoLogger.w(TAG, "Native denoise bridge unavailable; using JVM buffers", it) }
        }

        fun buildTileGrid(
            width: Int,
            height: Int,
            tileSize: Int = TILE_SIZE,
            overlap: Int = TILE_OVERLAP
        ): List<DenoiseTile> {
            if (width <= 0 || height <= 0) return emptyList()
            val xs = tilePositions(width, tileSize, overlap)
            val ys = tilePositions(height, tileSize, overlap)
            return ys.flatMap { y ->
                xs.map { x ->
                    DenoiseTile(
                        x = x,
                        y = y,
                        width = min(tileSize, width - x),
                        height = min(tileSize, height - y)
                    )
                }
            }
        }

        internal fun buildBoundedTileGrid(
            width: Int,
            height: Int,
            coreSize: Int = GUIDED_TILE_CORE_SIZE,
            halo: Int = GUIDED_TILE_HALO
        ): List<BoundedDenoiseTile> {
            if (width <= 0 || height <= 0) return emptyList()
            require(coreSize > 0) { "coreSize must be positive" }
            require(halo >= 0) { "halo must not be negative" }
            val tiles = mutableListOf<BoundedDenoiseTile>()
            var coreY = 0
            while (coreY < height) {
                val coreHeight = min(coreSize, height - coreY)
                var coreX = 0
                while (coreX < width) {
                    val coreWidth = min(coreSize, width - coreX)
                    val sourceX = max(0, coreX - halo)
                    val sourceY = max(0, coreY - halo)
                    val sourceRight = min(width, coreX + coreWidth + halo)
                    val sourceBottom = min(height, coreY + coreHeight + halo)
                    tiles += BoundedDenoiseTile(
                        coreX = coreX,
                        coreY = coreY,
                        coreWidth = coreWidth,
                        coreHeight = coreHeight,
                        sourceX = sourceX,
                        sourceY = sourceY,
                        sourceWidth = sourceRight - sourceX,
                        sourceHeight = sourceBottom - sourceY
                    )
                    coreX += coreWidth
                }
                coreY += coreHeight
            }
            return tiles
        }

        fun featherWeight(
            localX: Int,
            localY: Int,
            tileWidth: Int,
            tileHeight: Int,
            overlap: Int = TILE_OVERLAP
        ): Float {
            val left = localX / overlap.toFloat()
            val right = (tileWidth - 1 - localX) / overlap.toFloat()
            val top = localY / overlap.toFloat()
            val bottom = (tileHeight - 1 - localY) / overlap.toFloat()
            return min(min(left, right), min(top, bottom)).coerceIn(0f, 1f)
        }

        internal fun frameFeatherWeight(
            localX: Int,
            localY: Int,
            tile: DenoiseTile,
            frameWidth: Int,
            frameHeight: Int,
            overlap: Int = TILE_OVERLAP
        ): Float {
            if (overlap <= 0) return 1f
            val left = if (tile.x == 0) 1f else localX / overlap.toFloat()
            val right = if (tile.x + tile.width >= frameWidth) {
                1f
            } else {
                (tile.width - 1 - localX) / overlap.toFloat()
            }
            val top = if (tile.y == 0) 1f else localY / overlap.toFloat()
            val bottom = if (tile.y + tile.height >= frameHeight) {
                1f
            } else {
                (tile.height - 1 - localY) / overlap.toFloat()
            }
            return min(min(left, right), min(top, bottom)).coerceIn(0f, 1f)
        }

        fun applyGuidedFilter(
            input: FloatArray,
            guide: FloatArray,
            width: Int,
            height: Int,
            r: Int = 2,
            eps: Float = 0.02f * 255f * 255f
        ): FloatArray {
            require(input.size >= width * height && guide.size >= width * height)
            val meanI = boxBlur(guide, width, height, r)
            val meanP = boxBlur(input, width, height, r)
            val meanIp = boxBlur(multiply(guide, input), width, height, r)
            val covIp = subtract(meanIp, multiply(meanI, meanP))
            val meanIi = boxBlur(multiply(guide, guide), width, height, r)
            val varI = subtract(meanIi, multiply(meanI, meanI))
            val a = divide(covIp, add(varI, eps))
            val b = subtract(meanP, multiply(a, meanI))
            val meanA = boxBlur(a, width, height, r)
            val meanB = boxBlur(b, width, height, r)
            return add(multiply(meanA, guide), meanB)
        }

        fun motionCompensatedTemporalBlend(
            current: FloatArray,
            previous: FloatArray,
            flowX: FloatArray,
            flowY: FloatArray,
            width: Int,
            height: Int,
            alphaBase: Float = AIConstants.DENOISE_TEMPORAL_WEIGHT,
            gammaMotion: Float = 1.8f,
            gammaDiff: Float = 16f
        ): FloatArray {
            require(current.size >= width * height && previous.size >= width * height)
            val out = FloatArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val index = y * width + x
                    val vx = flowX.getOrElse(index) { 0f }
                    val vy = flowY.getOrElse(index) { 0f }
                    val prev = sampleBilinear(previous, width, height, x - vx, y - vy)
                    val boundaryGuard = if (x - vx in 0f..(width - 1).toFloat() && y - vy in 0f..(height - 1).toFloat()) 1f else 0f
                    val diff = current[index] - prev
                    val motion2 = vx * vx + vy * vy
                    val alpha = (alphaBase.coerceIn(0f, 1f) *
                        exp(-gammaMotion * motion2) *
                        exp(-gammaDiff * diff * diff) *
                        boundaryGuard).coerceIn(0f, 1f)
                    out[index] = (1f - alpha) * current[index] + alpha * prev
                }
            }
            return out
        }

        fun extractFloatRgb(outputRoot: Array<*>, expectedPixels: Int): FloatArray {
            val values = mutableListOf<Float>()
            flattenFloats(outputRoot, values)
            val required = expectedPixels * 3
            if (values.size < required) return FloatArray(required)
            return FloatArray(required) { values[it].coerceIn(0f, 1f) }
        }

        private fun directModelBuffer(): ByteBuffer =
            ByteBuffer.allocateDirect(
                AIConstants.DENOISE_MODEL_INPUT_WIDTH *
                    AIConstants.DENOISE_MODEL_INPUT_HEIGHT *
                    3 *
                    java.lang.Float.BYTES
            ).order(ByteOrder.nativeOrder())

        private fun tilePositions(length: Int, tileSize: Int, overlap: Int): List<Int> {
            if (length <= tileSize) return listOf(0)
            val step = (tileSize - overlap).coerceAtLeast(1)
            val positions = mutableListOf<Int>()
            var pos = 0
            while (pos + tileSize < length) {
                positions += pos
                pos += step
            }
            val finalPos = length - tileSize
            if (positions.lastOrNull() != finalPos) positions += finalPos
            return positions
        }

        private fun boxBlur(values: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
            if (radius <= 0) return values.copyOf()
            val temp = FloatArray(width * height)
            val out = FloatArray(width * height)
            val windowSize = radius * 2 + 1

            // Horizontal pass
            for (y in 0 until height) {
                val rowOffset = y * width
                var sum = 0f
                for (dx in -radius..radius) {
                    val xx = dx.coerceIn(0, width - 1)
                    sum += values[rowOffset + xx]
                }
                temp[rowOffset] = sum / windowSize

                for (x in 1 until width) {
                    val rightX = (x + radius).coerceAtMost(width - 1)
                    val leftX = (x - radius - 1).coerceAtLeast(0)
                    sum += values[rowOffset + rightX] - values[rowOffset + leftX]
                    temp[rowOffset + x] = sum / windowSize
                }
            }

            // Vertical pass
            for (x in 0 until width) {
                var sum = 0f
                for (dy in -radius..radius) {
                    val yy = dy.coerceIn(0, height - 1)
                    sum += temp[yy * width + x]
                }
                out[x] = sum / windowSize

                for (y in 1 until height) {
                    val bottomY = (y + radius).coerceAtMost(height - 1)
                    val topY = (y - radius - 1).coerceAtLeast(0)
                    sum += temp[bottomY * width + x] - temp[topY * width + x]
                    out[y * width + x] = sum / windowSize
                }
            }
            return out
        }

        private fun sampleBilinear(values: FloatArray, width: Int, height: Int, x: Float, y: Float): Float {
            val clampedX = x.coerceIn(0f, (width - 1).toFloat())
            val clampedY = y.coerceIn(0f, (height - 1).toFloat())
            val x0 = clampedX.toInt().coerceIn(0, width - 1)
            val y0 = clampedY.toInt().coerceIn(0, height - 1)
            val x1 = (x0 + 1).coerceAtMost(width - 1)
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val tx = clampedX - x0
            val ty = clampedY - y0
            val a = values[y0 * width + x0] * (1f - tx) + values[y0 * width + x1] * tx
            val b = values[y1 * width + x0] * (1f - tx) + values[y1 * width + x1] * tx
            return a * (1f - ty) + b * ty
        }

        private fun sampleBilinearRgb(
            values: FloatArray,
            width: Int,
            height: Int,
            x: Float,
            y: Float,
            channel: Int
        ): Float {
            val clampedX = x.coerceIn(0f, (width - 1).toFloat())
            val clampedY = y.coerceIn(0f, (height - 1).toFloat())
            val x0 = clampedX.toInt().coerceIn(0, width - 1)
            val y0 = clampedY.toInt().coerceIn(0, height - 1)
            val x1 = (x0 + 1).coerceAtMost(width - 1)
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val tx = clampedX - x0
            val ty = clampedY - y0
            val a = values[(y0 * width + x0) * 3 + channel] * (1f - tx) +
                values[(y0 * width + x1) * 3 + channel] * tx
            val b = values[(y1 * width + x0) * 3 + channel] * (1f - tx) +
                values[(y1 * width + x1) * 3 + channel] * tx
            return a * (1f - ty) + b * ty
        }

        private fun flattenFloats(value: Any?, out: MutableList<Float>) {
            when (value) {
                is Float -> out += value
                is FloatArray -> value.forEach { out += it }
                is Array<*> -> value.forEach { flattenFloats(it, out) }
            }
        }

        private fun multiply(a: FloatArray, b: FloatArray): FloatArray =
            FloatArray(a.size) { i -> a[i] * b[i] }

        private fun subtract(a: FloatArray, b: FloatArray): FloatArray =
            FloatArray(a.size) { i -> a[i] - b[i] }

        private fun add(a: FloatArray, b: FloatArray): FloatArray =
            FloatArray(a.size) { i -> a[i] + b[i] }

        private fun add(a: FloatArray, scalar: Float): FloatArray =
            FloatArray(a.size) { i -> a[i] + scalar }

        private fun divide(a: FloatArray, b: FloatArray): FloatArray =
            FloatArray(a.size) { i -> a[i] / b[i].coerceAtLeast(1.0e-6f) }
    }
}
