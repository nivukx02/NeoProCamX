package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.niv99.Niv99OfflineConversionPlan
import com.neoproc.neoproccamx.niv99.Niv99OfflinePixelDomain
import com.neoproc.neoproccamx.rendering.GpuImageEnhancementUniforms
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max

/** The pixel executor that produced the accepted NIV99 offline P010 frame. */
internal enum class Niv99OfflineP010Backend {
    CPU_REFERENCE,
    NATIVE_SIMD,
    VULKAN_COMPUTE,
    PARITY_GATED_CPU_OR_GPU
}

/**
 * Bounded, measured evidence for one complete P010 conversion session.  This is deliberately derived
 * from accepted output frames rather than from requested settings, so a certificate cannot claim NIV99
 * merely because a policy object was constructed.
 */
internal data class Niv99OfflineP010Evidence(
    val route: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val expectedFrames: Long,
    val processedFrames: Long,
    val cpuFrames: Long,
    val nativeSimdFrames: Long,
    val gpuFrames: Long,
    val parityGatedFrames: Long,
    val temporalFrames: Long,
    val residualWhiteBalanceFrames: Long,
    val fullFrameLumaSamples: Long,
    val fullFrameChromaSamples: Long,
    val averageProcessingMs: Double,
    val p95ProcessingMs: Double,
    val maximumProcessingMs: Double
) {
    val complete: Boolean
        get() = processedFrames > 0L && processedFrames == expectedFrames

    fun metadata(): Map<String, String> = linkedMapOf(
        "niv99OfflinePixelProcessor" to "NIV99_P010_FULL_FRAME_V1",
        "niv99OfflinePixelRoute" to route,
        "niv99OfflinePixelProcessingProven" to complete.toString(),
        "niv99OfflinePixelWidth" to width.toString(),
        "niv99OfflinePixelHeight" to height.toString(),
        "niv99OfflinePixelFps" to fps.toString(),
        "niv99OfflinePixelExpectedFrames" to expectedFrames.toString(),
        "niv99OfflinePixelProcessedFrames" to processedFrames.toString(),
        "niv99OfflinePixelCpuFrames" to cpuFrames.toString(),
        "niv99OfflinePixelNativeSimdFrames" to nativeSimdFrames.toString(),
        "niv99OfflinePixelGpuFrames" to gpuFrames.toString(),
        "niv99OfflinePixelParityGatedFrames" to parityGatedFrames.toString(),
        "niv99OfflinePixelTemporalFrames" to temporalFrames.toString(),
        "niv99OfflineResidualWbFrames" to residualWhiteBalanceFrames.toString(),
        "niv99OfflineCapturedWbPreserved" to "true",
        "niv99OfflineFullFrameLumaSamples" to fullFrameLumaSamples.toString(),
        "niv99OfflineFullFrameChromaSamples" to fullFrameChromaSamples.toString(),
        "niv99OfflineProcessingAverageMs" to "%.3f".format(java.util.Locale.US, averageProcessingMs),
        "niv99OfflineProcessingP95Ms" to "%.3f".format(java.util.Locale.US, p95ProcessingMs),
        "niv99OfflineProcessingMaximumMs" to "%.3f".format(java.util.Locale.US, maximumProcessingMs)
    )
}

/**
 * NIV99's mandatory offline P010 pixel boundary.
 *
 * The mature export kernels remain the mathematical implementation: motion-gated temporal fusion,
 * chroma cleanup, edge-aware luma denoise, detail reconstruction, halo limiting, highlight protection,
 * and ten-bit dithering.  Routing those kernels through this owner adds the missing production contract:
 * every accepted frame is attributed to a real CPU/GPU pixel execution, the complete raster is retained,
 * and the conversion fails if the number of processed frames differs from the source timeline.
 *
 * The class owns only fixed-size counters and a 64-bin timing histogram.  It creates no history queue and
 * never keeps a frame reference; source and predecessor ownership remains with the bounded converter.
 */
internal class Niv99OfflineP010PixelProcessor(
    private val plan: Niv99OfflineConversionPlan,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val expectedFrames: Long?
) {
    private val expectedBytes = checkedP010Bytes(width, height)
    private val processedFrames = AtomicLong(0L)
    private val cpuFrames = AtomicLong(0L)
    private val nativeSimdFrames = AtomicLong(0L)
    private val gpuFrames = AtomicLong(0L)
    private val parityGatedFrames = AtomicLong(0L)
    private val temporalFrames = AtomicLong(0L)
    private val residualWhiteBalanceFrames = AtomicLong(0L)
    private val processingNanos = AtomicLong(0L)
    private val maximumProcessingNanos = AtomicLong(0L)
    private val timingHistogram = java.util.concurrent.atomic.AtomicLongArray(TIMING_BUCKETS)
    private val frameIndexSum = AtomicLong(0L)
    private val frameIndexXor = AtomicLong(0L)

    init {
        require(plan.enabled) { "NIV99 P010 conversion cannot be disabled for ${plan.route}" }
        require(plan.pixelDomain == Niv99OfflinePixelDomain.P010_TEN_BIT) {
            "NIV99 P010 processor cannot execute ${plan.pixelDomain}"
        }
        require(plan.fullFrame && plan.preserveDimensions && plan.preservePresentationTimestamps) {
            "NIV99 P010 conversion requires full-frame geometry and cadence preservation"
        }
        require(width > 0 && height > 0 && fps > 0) {
            "Invalid NIV99 P010 geometry/cadence: ${width}x$height@$fps"
        }
        require(expectedFrames == null || expectedFrames > 0L) {
            "NIV99 P010 conversion requires a non-empty source when its frame count is known"
        }
    }

    /** Runs the complete reference-quality NIV99 P010 stage without mutating [sourceP010]. */
    fun processCpu(
        frameIndex: Long,
        sensorTimestampNs: Long,
        sourceP010: ByteArray,
        previousSourceP010: ByteArray?,
        profile: Npv10ExportEnhancementProfile,
        liveCinemaSharpness: Double?,
        liveCinemaDenoise: Double?,
        gradeLut: FloatArray?,
        gradeLutSize: Int,
        saturation: Float,
        warmth: Float,
        rowScheduler: EnhanceRowScheduler = EnhanceRowScheduler.SEQUENTIAL,
        preprocessedLuma: ByteArray? = null
    ): ByteArray {
        val startedNs = System.nanoTime()
        val output = applyCpu(
            frameIndex = frameIndex,
            sensorTimestampNs = sensorTimestampNs,
            sourceP010 = sourceP010,
            previousSourceP010 = previousSourceP010,
            profile = profile,
            liveCinemaSharpness = liveCinemaSharpness,
            liveCinemaDenoise = liveCinemaDenoise,
            gradeLut = gradeLut,
            gradeLutSize = gradeLutSize,
            saturation = saturation,
            warmth = warmth,
            rowScheduler = rowScheduler,
            preprocessedLuma = preprocessedLuma
        )
        recordCpuFrame(
            frameIndex = frameIndex,
            sensorTimestampNs = sensorTimestampNs,
            output = output,
            temporalHistoryUsed = previousSourceP010 != null && plan.temporalDenoise,
            residualWhiteBalanceApplied = kotlin.math.abs(warmth) > COLOR_EFFECT_EPSILON,
            processingTimeNs = (System.nanoTime() - startedNs).coerceAtLeast(0L)
        )
        return output
    }

    /** Same NIV99 pixel math as [processCpu], with final-frame accounting left to the caller. */
    fun applyCpu(
        frameIndex: Long,
        sensorTimestampNs: Long,
        sourceP010: ByteArray,
        previousSourceP010: ByteArray?,
        profile: Npv10ExportEnhancementProfile,
        liveCinemaSharpness: Double?,
        liveCinemaDenoise: Double?,
        gradeLut: FloatArray?,
        gradeLutSize: Int,
        saturation: Float,
        warmth: Float,
        rowScheduler: EnhanceRowScheduler = EnhanceRowScheduler.SEQUENTIAL,
        preprocessedLuma: ByteArray? = null
    ): ByteArray {
        validateInput(sourceP010, previousSourceP010, frameIndex, sensorTimestampNs)
        require((liveCinemaSharpness == null) == (liveCinemaDenoise == null)) {
            "NIV99 live-cinema sharpness and denoise must be supplied atomically"
        }
        val lumaPrepared = preprocessedLuma ?: if (liveCinemaSharpness != null) {
            Npv10ExportEnhancementPolicy.enhanceLiveCinemaLumaP010(
                payload = sourceP010,
                width = width,
                height = height,
                uSharpness = liveCinemaSharpness,
                uDenoise = checkNotNull(liveCinemaDenoise),
                rowScheduler = rowScheduler
            )
        } else {
            sourceP010
        }
        require(lumaPrepared.size == expectedBytes) {
            "NIV99 luma stage returned ${lumaPrepared.size} bytes; expected $expectedBytes"
        }
        val output = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = lumaPrepared,
            width = width,
            height = height,
            previousP010Payload = previousSourceP010,
            profile = profile,
            gradeLut = gradeLut,
            gradeLutSize = gradeLutSize,
            saturation = saturation,
            warmth = warmth,
            rowScheduler = rowScheduler
        )
        return output
    }

    /**
     * Runs the bounded full-frame SIMD NIV99 path into caller-owned encoder-ready P010 storage.
     * A false return is not counted as processed and the caller must use an explicit quality path or fail.
     */
    fun processNativeDirect(
        frameIndex: Long,
        sensorTimestampNs: Long,
        sourceP010: ByteBuffer,
        previousSourceP010: ByteBuffer?,
        outputP010: ByteBuffer,
        encoding: Npv10Niv99ColorEncoding,
        uniforms: GpuImageEnhancementUniforms,
        residualWhiteBalanceAllowed: Boolean
    ): Boolean {
        validateDirectInput(
            sourceP010 = sourceP010,
            previousSourceP010 = previousSourceP010,
            outputP010 = outputP010,
            frameIndex = frameIndex,
            sensorTimestampNs = sensorTimestampNs
        )
        val startedNs = System.nanoTime()
        val token = Npv10FramePacker.processNiv99CanonicalP010ToP010(
            canonicalP010 = sourceP010,
            previousCanonicalP010 = previousSourceP010,
            width = width,
            height = height,
            target = outputP010,
            encoding = encoding,
            uniforms = uniforms,
            residualWhiteBalanceAllowed = residualWhiteBalanceAllowed
        )
        if (token < 0L) return false
        recordAcceptedFrame(
            frameIndex = frameIndex,
            sensorTimestampNs = sensorTimestampNs,
            outputBytes = expectedBytes,
            backend = Niv99OfflineP010Backend.NATIVE_SIMD,
            temporalHistoryUsed = Npv10FramePacker.niv99P010TemporalUsed(token),
            residualWhiteBalanceApplied = residualWhiteBalanceAllowed &&
                kotlin.math.abs(uniforms.warmth) > COLOR_EFFECT_EPSILON,
            processingTimeNs = (System.nanoTime() - startedNs).coerceAtLeast(0L)
        )
        return true
    }

    fun recordCpuFrame(
        frameIndex: Long,
        sensorTimestampNs: Long,
        output: ByteArray,
        temporalHistoryUsed: Boolean,
        residualWhiteBalanceApplied: Boolean,
        processingTimeNs: Long
    ) {
        recordAcceptedFrame(
            frameIndex = frameIndex,
            sensorTimestampNs = sensorTimestampNs,
            outputBytes = output.size,
            backend = Niv99OfflineP010Backend.CPU_REFERENCE,
            temporalHistoryUsed = temporalHistoryUsed,
            residualWhiteBalanceApplied = residualWhiteBalanceApplied,
            processingTimeNs = processingTimeNs
        )
    }

    fun recordParityGatedFrame(
        frameIndex: Long,
        sensorTimestampNs: Long,
        output: ByteArray,
        temporalHistoryUsed: Boolean,
        residualWhiteBalanceApplied: Boolean,
        processingTimeNs: Long
    ) {
        recordAcceptedFrame(
            frameIndex = frameIndex,
            sensorTimestampNs = sensorTimestampNs,
            outputBytes = output.size,
            backend = Niv99OfflineP010Backend.PARITY_GATED_CPU_OR_GPU,
            temporalHistoryUsed = temporalHistoryUsed,
            residualWhiteBalanceApplied = residualWhiteBalanceApplied,
            processingTimeNs = processingTimeNs
        )
    }

    /** Records a validated Vulkan result; there is no silent untreated-frame success state. */
    fun recordGpuFrame(
        frameIndex: Long,
        sensorTimestampNs: Long,
        output: ByteArray,
        temporalHistoryUsed: Boolean,
        residualWhiteBalanceApplied: Boolean,
        processingTimeNs: Long
    ) {
        recordAcceptedFrame(
            frameIndex = frameIndex,
            sensorTimestampNs = sensorTimestampNs,
            outputBytes = output.size,
            backend = Niv99OfflineP010Backend.VULKAN_COMPUTE,
            temporalHistoryUsed = temporalHistoryUsed,
            residualWhiteBalanceApplied = residualWhiteBalanceApplied,
            processingTimeNs = processingTimeNs
        )
    }

    /** Direct-buffer sibling used by the no-copy Vulkan export lane. */
    fun recordGpuFrame(
        frameIndex: Long,
        sensorTimestampNs: Long,
        output: ByteBuffer,
        temporalHistoryUsed: Boolean,
        residualWhiteBalanceApplied: Boolean,
        processingTimeNs: Long
    ) {
        require(output.remaining() == expectedBytes) {
            "NIV99 Vulkan P010 output has ${output.remaining()} bytes; expected $expectedBytes"
        }
        recordAcceptedFrame(
            frameIndex = frameIndex,
            sensorTimestampNs = sensorTimestampNs,
            outputBytes = output.remaining(),
            backend = Niv99OfflineP010Backend.VULKAN_COMPUTE,
            temporalHistoryUsed = temporalHistoryUsed,
            residualWhiteBalanceApplied = residualWhiteBalanceApplied,
            processingTimeNs = processingTimeNs
        )
    }

    fun requireComplete(actualFrames: Long): Niv99OfflineP010Evidence {
        val declaredFrames = expectedFrames
        require(declaredFrames == null || actualFrames == declaredFrames) {
            "NIV99 source/output frame mismatch expected=$expectedFrames actual=$actualFrames"
        }
        val count = processedFrames.get()
        check(count == actualFrames && actualFrames > 0L) {
            "NIV99 pixel processing incomplete: processed=$count output=$actualFrames"
        }
        val expectedIndexSum = Math.multiplyExact(actualFrames, actualFrames - 1L) / 2L
        check(frameIndexSum.get() == expectedIndexSum && frameIndexXor.get() == xorZeroTo(actualFrames - 1L)) {
            "NIV99 frame identity coverage is incomplete or duplicated"
        }
        val totalNs = processingNanos.get()
        return Niv99OfflineP010Evidence(
            route = plan.route.name,
            width = width,
            height = height,
            fps = fps,
            expectedFrames = declaredFrames ?: actualFrames,
            processedFrames = count,
            cpuFrames = cpuFrames.get(),
            nativeSimdFrames = nativeSimdFrames.get(),
            gpuFrames = gpuFrames.get(),
            parityGatedFrames = parityGatedFrames.get(),
            temporalFrames = temporalFrames.get(),
            residualWhiteBalanceFrames = residualWhiteBalanceFrames.get(),
            fullFrameLumaSamples = Math.multiplyExact(Math.multiplyExact(width.toLong(), height.toLong()), count),
            fullFrameChromaSamples = Math.multiplyExact(
                Math.multiplyExact(((width + 1L) / 2L), ((height + 1L) / 2L)) * 2L,
                count
            ),
            averageProcessingMs = if (count == 0L) 0.0 else totalNs / count.toDouble() / NANOS_PER_MILLISECOND,
            p95ProcessingMs = percentileNanos(0.95) / NANOS_PER_MILLISECOND,
            maximumProcessingMs = maximumProcessingNanos.get() / NANOS_PER_MILLISECOND
        )
    }

    private fun validateInput(
        sourceP010: ByteArray,
        previousSourceP010: ByteArray?,
        frameIndex: Long,
        sensorTimestampNs: Long
    ) {
        require(frameIndex >= 0L && (expectedFrames == null || frameIndex < expectedFrames)) {
            "NIV99 frame index $frameIndex is outside the declared source range"
        }
        require(sensorTimestampNs >= 0L) { "NIV99 sensor timestamp must be non-negative" }
        require(sourceP010.size == expectedBytes) {
            "NIV99 P010 source has ${sourceP010.size} bytes; expected $expectedBytes"
        }
        require(previousSourceP010 == null || previousSourceP010.size == expectedBytes) {
            "NIV99 temporal history geometry does not match the current frame"
        }
    }

    private fun validateDirectInput(
        sourceP010: ByteBuffer,
        previousSourceP010: ByteBuffer?,
        outputP010: ByteBuffer,
        frameIndex: Long,
        sensorTimestampNs: Long
    ) {
        require(frameIndex >= 0L && (expectedFrames == null || frameIndex < expectedFrames)) {
            "NIV99 frame index $frameIndex is outside the declared source range"
        }
        require(sensorTimestampNs >= 0L) { "NIV99 sensor timestamp must be non-negative" }
        require(sourceP010.isDirect && outputP010.isDirect && previousSourceP010?.isDirect != false) {
            "NIV99 native P010 conversion requires direct buffers"
        }
        require(sourceP010 !== outputP010 && previousSourceP010 !== outputP010) {
            "NIV99 native output must not alias a source frame"
        }
        require(sourceP010.remaining() >= expectedBytes && outputP010.remaining() >= expectedBytes) {
            "NIV99 native P010 source/output capacity is incomplete"
        }
        require(previousSourceP010 == null || previousSourceP010.remaining() >= expectedBytes) {
            "NIV99 native temporal history geometry does not match the current frame"
        }
    }

    private fun recordAcceptedFrame(
        frameIndex: Long,
        sensorTimestampNs: Long,
        outputBytes: Int,
        backend: Niv99OfflineP010Backend,
        temporalHistoryUsed: Boolean,
        residualWhiteBalanceApplied: Boolean,
        processingTimeNs: Long
    ) {
        require(frameIndex >= 0L && (expectedFrames == null || frameIndex < expectedFrames)) {
            "NIV99 frame index $frameIndex is outside the declared source range"
        }
        require(sensorTimestampNs >= 0L) { "NIV99 sensor timestamp must be non-negative" }
        require(outputBytes == expectedBytes) {
            "NIV99 P010 output has $outputBytes bytes; expected $expectedBytes"
        }
        processedFrames.incrementAndGet()
        when (backend) {
            Niv99OfflineP010Backend.CPU_REFERENCE -> cpuFrames.incrementAndGet()
            Niv99OfflineP010Backend.NATIVE_SIMD -> nativeSimdFrames.incrementAndGet()
            Niv99OfflineP010Backend.VULKAN_COMPUTE -> gpuFrames.incrementAndGet()
            Niv99OfflineP010Backend.PARITY_GATED_CPU_OR_GPU -> parityGatedFrames.incrementAndGet()
        }
        if (temporalHistoryUsed) temporalFrames.incrementAndGet()
        if (residualWhiteBalanceApplied) residualWhiteBalanceFrames.incrementAndGet()
        frameIndexSum.addAndGet(frameIndex)
        xorAtomic(frameIndexXor, frameIndex)
        val boundedNs = processingTimeNs.coerceAtLeast(0L)
        processingNanos.addAndGet(boundedNs)
        maximumProcessingNanos.accumulateAndGet(boundedNs, ::max)
        timingHistogram.incrementAndGet(timingBucket(boundedNs))
    }

    private fun percentileNanos(quantile: Double): Double {
        val count = processedFrames.get()
        if (count <= 0L) return 0.0
        val target = ceil(count * quantile.coerceIn(0.0, 1.0)).toLong().coerceAtLeast(1L)
        var accumulated = 0L
        for (index in 0 until timingHistogram.length()) {
            accumulated += timingHistogram.get(index)
            if (accumulated >= target) return bucketUpperBoundNs(index).toDouble()
        }
        return maximumProcessingNanos.get().toDouble()
    }

    private fun timingBucket(durationNs: Long): Int {
        if (durationNs <= 0L) return 0
        val millis = (durationNs + NANOS_PER_MILLISECOND.toLong() - 1L) /
            NANOS_PER_MILLISECOND.toLong()
        return (63 - java.lang.Long.numberOfLeadingZeros(millis)).coerceIn(0, TIMING_BUCKETS - 1)
    }

    private fun bucketUpperBoundNs(index: Int): Long {
        if (index <= 0) return NANOS_PER_MILLISECOND.toLong()
        val upperMillis = 1L shl (index + 1).coerceAtMost(60)
        return upperMillis * NANOS_PER_MILLISECOND.toLong()
    }

    private fun xorAtomic(target: AtomicLong, value: Long) {
        while (true) {
            val current = target.get()
            if (target.compareAndSet(current, current xor value)) return
        }
    }

    private fun xorZeroTo(value: Long): Long {
        if (value < 0L) return 0L
        return when ((value and 3L).toInt()) {
            0 -> value
            1 -> 1L
            2 -> value + 1L
            else -> 0L
        }
    }

    companion object {
        private const val TIMING_BUCKETS = 32
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
        private const val COLOR_EFFECT_EPSILON = 0.000_001f

        private fun checkedP010Bytes(width: Int, height: Int): Int {
            require(width > 0 && height > 0) { "NIV99 P010 dimensions must be positive" }
            val luma = Math.multiplyExact(width.toLong(), height.toLong())
            val chroma = Math.multiplyExact((width + 1L) / 2L, (height + 1L) / 2L) * 2L
            val bytes = Math.multiplyExact(luma + chroma, Short.SIZE_BYTES.toLong())
            require(bytes <= Int.MAX_VALUE.toLong()) {
                "NIV99 P010 frame exceeds addressable storage: ${width}x$height"
            }
            return bytes.toInt()
        }
    }
}
