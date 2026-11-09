package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.rendering.GpuImageEnhancementUniforms
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class Npv10GpuExportAccelerationResult(
    val payload: ByteArray,
    val evidence: Npv10ExportProcessingEvidence
)

internal enum class LiveP010EnhanceDisposition {
    FAILED,
    HISTORY_SEEDED,
    TEMPORAL_USED,
    HISTORY_RESET_SESSION,
    HISTORY_RESET_GEOMETRY,
    HISTORY_RESET_TIMESTAMP
}

internal data class LiveP010EnhanceResult(
    val disposition: LiveP010EnhanceDisposition
) {
    val enhanced: Boolean get() = disposition != LiveP010EnhanceDisposition.FAILED
    val temporalUsed: Boolean get() = disposition == LiveP010EnhanceDisposition.TEMPORAL_USED
    val historyReset: Boolean get() = disposition == LiveP010EnhanceDisposition.HISTORY_RESET_SESSION ||
        disposition == LiveP010EnhanceDisposition.HISTORY_RESET_GEOMETRY ||
        disposition == LiveP010EnhanceDisposition.HISTORY_RESET_TIMESTAMP
}

internal object Npv10ExportGpuAccelerator {
    private const val NATIVE_LIBRARY = "neoproc_vulkan"
    // The Adreno driver on the validated 4K target needs slightly more than three seconds to compile
    // the cold export-enhance pipeline.  Treating that normal one-time compile as a permanent failure
    // routed every full-resolution frame through the CPU reference path and made conversion appear to
    // freeze.  Offline conversion runs on its worker dispatcher, so give the bounded cold-start probe
    // enough time to finish; steady-state frame dispatch remains governed by the native fence timeout.
    private const val NATIVE_INITIALIZATION_TIMEOUT_MS = 30_000L
    const val VULKAN_STAGING_PATH = "VULKAN_P010_COMPUTE_STAGING"
    const val ZERO_COPY_VULKAN_PATH = "ZERO_COPY_VULKAN_P010_DOUBLE_BUFFER"
    const val MIXED_STAGING_CPU_PATH = "MIXED_VULKAN_P010_COMPUTE_STAGING_CPU_FALLBACK"
    const val PERSISTENT_MAPPED_HOST_COHERENT = "PERSISTENT_MAPPED_HOST_COHERENT"
    private val directScratch = ThreadLocal<DirectP010Scratch>()

    private val nativeLibraryAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary(NATIVE_LIBRARY)
            true
        }.getOrDefault(false)
    }

    private val nativeReadiness: Npv10ExportNativeReadinessGate by lazy {
        Npv10ExportNativeReadinessGate(
            timeoutMillis = NATIVE_INITIALIZATION_TIMEOUT_MS,
            probe = { nativeStatus() }
        )
    }

    fun enhanceP010(
        payload: ByteArray,
        previousP010Payload: ByteArray?,
        width: Int,
        height: Int,
        uniforms: GpuImageEnhancementUniforms,
        gradeLut: FloatArray? = null,
        gradeLutSize: Int = 0
    ): Npv10GpuExportAccelerationResult? {
        if (!nativeReady() || width <= 0 || height <= 0 || payload.isEmpty()) return null
        if (!isValidGradeLut(gradeLut, gradeLutSize)) return null
        val packedUniforms = uniforms.toFloatArray()
        val directResult = runCatching {
            val scratch = directScratch()
            val currentDirect = scratch.current(payload)
            val previousDirect = previousP010Payload?.let { scratch.previous(it) }
            val outputDirect = scratch.output(payload.size)
            val enhanced = nativeEnhanceP010Direct(
                currentPayload = currentDirect,
                previousPayload = previousDirect,
                outputPayload = outputDirect,
                width = width,
                height = height,
                uniforms = packedUniforms,
                gradeLut = gradeLut,
                gradeLutSize = gradeLutSize
            )
            if (!enhanced) {
                null
            } else {
                val output = ByteArray(payload.size)
                outputDirect.clear()
                outputDirect.limit(payload.size)
                outputDirect.get(output)
                Npv10GpuExportAccelerationResult(
                    payload = output,
                    evidence = Npv10ExportProcessingEvidence.zeroCopyVulkanP010DoubleBuffer(uniforms.signature)
                )
            }
        }.getOrNull()
        if (directResult != null) return directResult

        val enhanced = runCatching {
            nativeEnhanceP010(
                currentPayload = payload,
                previousPayload = previousP010Payload,
                width = width,
                height = height,
                uniforms = packedUniforms,
                gradeLut = gradeLut,
                gradeLutSize = gradeLutSize
            )
        }.getOrNull() ?: return null
        return Npv10GpuExportAccelerationResult(
            payload = enhanced,
            evidence = Npv10ExportProcessingEvidence.vulkanP010ComputeStaging(uniforms.signature)
        )
    }

    /**
     * Enhances a DIRECT P010 [buffer] IN PLACE (the tightly-packed payload at position 0 .. limit),
     * reusing the process-wide GPU export engine for live recording. Best-effort: returns false and
     * leaves [buffer] untouched if the native library is unavailable, the buffer is not direct, or the
     * enhance fails — so the caller's already-valid raw payload always stands and recording never breaks.
     * [uniforms] must be GpuImageEnhancementUniforms.toFloatArray() (exact float count validated natively).
     */
    fun enhanceP010InPlace(
        buffer: ByteBuffer,
        previous: ByteBuffer?,
        width: Int,
        height: Int,
        uniforms: FloatArray
    ): Boolean {
        if (!nativeReady() || width <= 0 || height <= 0 || !buffer.isDirect) return false
        if (buffer.remaining() <= 0) return false
        return runCatching {
            // outputPayload == currentPayload: native fully uploads the source into its GPU slot before
            // writing the enhanced output back, so aliasing in == out is safe (no read-after-write hazard).
            nativeEnhanceP010Direct(
                currentPayload = buffer,
                previousPayload = previous,
                outputPayload = buffer,
                width = width,
                height = height,
                uniforms = uniforms,
                gradeLut = null,
                gradeLutSize = 0
            )
        }.getOrDefault(false)
    }

    /**
     * Live-recording variant with a native-owned previous SOURCE frame. The codec buffer is enhanced
     * in place, but native code snapshots the unprocessed input after a successful dispatch and never
     * feeds a processed frame back into the temporal stage. The returned disposition is authoritative
     * evidence of whether temporal pixels were actually consumed or history was safely reset.
     */
    fun enhanceLiveP010InPlace(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        uniforms: FloatArray,
        sessionGeneration: Long,
        timestampNs: Long,
        maximumHistoryGapNs: Long
    ): LiveP010EnhanceResult {
        if (!nativeReady() || width <= 0 || height <= 0 || !buffer.isDirect) {
            return LiveP010EnhanceResult(LiveP010EnhanceDisposition.FAILED)
        }
        val expectedBytes = runCatching {
            Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                width,
                height,
                Npv10PayloadFormat.P010_LE
            ).toInt()
        }.getOrNull() ?: return LiveP010EnhanceResult(LiveP010EnhanceDisposition.FAILED)
        val frame = buffer.directFrameView(expectedBytes)
            ?: return LiveP010EnhanceResult(LiveP010EnhanceDisposition.FAILED)
        val code = runCatching {
            nativeEnhanceLiveP010InPlace(
                currentAndOutputPayload = frame,
                width = width,
                height = height,
                uniforms = uniforms,
                sessionGeneration = sessionGeneration,
                timestampNs = timestampNs,
                maximumHistoryGapNs = maximumHistoryGapNs
            )
        }.getOrDefault(LIVE_ENHANCE_FAILED)
        val disposition = when (code) {
            LIVE_ENHANCE_SEEDED -> LiveP010EnhanceDisposition.HISTORY_SEEDED
            LIVE_ENHANCE_TEMPORAL -> LiveP010EnhanceDisposition.TEMPORAL_USED
            LIVE_ENHANCE_RESET_SESSION -> LiveP010EnhanceDisposition.HISTORY_RESET_SESSION
            LIVE_ENHANCE_RESET_GEOMETRY -> LiveP010EnhanceDisposition.HISTORY_RESET_GEOMETRY
            LIVE_ENHANCE_RESET_TIMESTAMP -> LiveP010EnhanceDisposition.HISTORY_RESET_TIMESTAMP
            else -> LiveP010EnhanceDisposition.FAILED
        }
        return LiveP010EnhanceResult(disposition)
    }

    /**
     * Enhances one direct P010 frame into a caller-owned direct output buffer. The buffers may alias:
     * native code uploads both temporal inputs completely before copying the completed GPU output back.
     * No frame-sized Java array is allocated by this path.
     */
    fun enhanceP010Direct(
        current: ByteBuffer,
        previous: ByteBuffer?,
        output: ByteBuffer,
        width: Int,
        height: Int,
        uniforms: GpuImageEnhancementUniforms,
        gradeLut: FloatArray? = null,
        gradeLutSize: Int = 0
    ): Npv10ExportProcessingEvidence? {
        if (!nativeReady() || width <= 0 || height <= 0) return null
        if (!isValidGradeLut(gradeLut, gradeLutSize)) return null
        val expectedBytes = runCatching {
            Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
                width,
                height,
                Npv10PayloadFormat.P010_LE
            ).toInt()
        }.getOrNull() ?: return null
        val currentView = current.directFrameView(expectedBytes) ?: return null
        val previousView = previous?.directFrameView(expectedBytes)
        if (previous != null && previousView == null) return null
        val outputView = output.directFrameView(expectedBytes) ?: return null
        val enhanced = runCatching {
            nativeEnhanceP010Direct(
                currentPayload = currentView,
                previousPayload = previousView,
                outputPayload = outputView,
                width = width,
                height = height,
                uniforms = uniforms.toFloatArray(),
                gradeLut = gradeLut,
                gradeLutSize = gradeLutSize
            )
        }.getOrDefault(false)
        return if (enhanced) {
            Npv10ExportProcessingEvidence.zeroCopyVulkanP010DoubleBuffer(uniforms.signature)
        } else {
            null
        }
    }

    private fun directScratch(): DirectP010Scratch {
        val existing = directScratch.get()
        if (existing != null) return existing
        return DirectP010Scratch().also { directScratch.set(it) }
    }

    private class DirectP010Scratch {
        private var currentPayload: ByteBuffer? = null
        private var previousPayload: ByteBuffer? = null
        private var outputPayload: ByteBuffer? = null

        fun current(payload: ByteArray): ByteBuffer {
            val buffer = currentPayload.reusableDirect(payload.size)
            currentPayload = buffer
            return buffer.fillFrom(payload)
        }

        fun previous(payload: ByteArray): ByteBuffer {
            val buffer = previousPayload.reusableDirect(payload.size)
            previousPayload = buffer
            return buffer.fillFrom(payload)
        }

        fun output(size: Int): ByteBuffer {
            val buffer = outputPayload.reusableDirect(size)
            outputPayload = buffer
            buffer.clear()
            buffer.limit(size)
            return buffer
        }
    }

    private fun ByteBuffer?.reusableDirect(size: Int): ByteBuffer =
        if (this != null && capacity() >= size) {
            this
        } else {
            ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        }

    private fun ByteBuffer.fillFrom(payload: ByteArray): ByteBuffer {
        clear()
        put(payload)
        flip()
        return this
    }

    private fun ByteBuffer.directFrameView(size: Int): ByteBuffer? {
        if (!isDirect || size <= 0 || remaining() < size) return null
        return slice().apply { limit(size) }
    }

    /**
     * True only when the native Vulkan export accelerator library loaded. When false, [enhanceP010]
     * is a guaranteed null-returning no-op, so callers can skip work whose sole consumer is that path.
     */
    fun isAccelerationAvailable(): Boolean = nativeReady()

    private fun isValidGradeLut(lut: FloatArray?, size: Int): Boolean {
        if (lut == null) return size == 0
        if (size !in MIN_LUT_EDGE..MAX_LUT_EDGE) return false
        val expected = runCatching {
            Math.multiplyExact(Math.multiplyExact(Math.multiplyExact(size, size), size), 3)
        }.getOrNull() ?: return false
        return lut.size == expected && lut.all(Float::isFinite)
    }

    fun unavailableReason(): String {
        if (!nativeLibraryAvailable) return "NATIVE_LIBRARY_UNAVAILABLE"
        return nativeReadiness.unavailableReason()
    }

    private fun nativeReady(): Boolean =
        nativeLibraryAvailable && nativeReadiness.awaitReady()

    @JvmStatic
    private external fun nativeEnhanceP010(
        currentPayload: ByteArray,
        previousPayload: ByteArray?,
        width: Int,
        height: Int,
        uniforms: FloatArray,
        gradeLut: FloatArray?,
        gradeLutSize: Int
    ): ByteArray?

    @JvmStatic
    private external fun nativeEnhanceP010Direct(
        currentPayload: ByteBuffer,
        previousPayload: ByteBuffer?,
        outputPayload: ByteBuffer,
        width: Int,
        height: Int,
        uniforms: FloatArray,
        gradeLut: FloatArray?,
        gradeLutSize: Int
    ): Boolean

    @JvmStatic
    private external fun nativeEnhanceLiveP010InPlace(
        currentAndOutputPayload: ByteBuffer,
        width: Int,
        height: Int,
        uniforms: FloatArray,
        sessionGeneration: Long,
        timestampNs: Long,
        maximumHistoryGapNs: Long
    ): Int

    @JvmStatic
    private external fun nativeStatus(): String

    private const val LIVE_ENHANCE_FAILED = 0
    private const val LIVE_ENHANCE_SEEDED = 1
    private const val LIVE_ENHANCE_TEMPORAL = 2
    private const val LIVE_ENHANCE_RESET_SESSION = 3
    private const val LIVE_ENHANCE_RESET_GEOMETRY = 4
    private const val LIVE_ENHANCE_RESET_TIMESTAMP = 5
    private const val MIN_LUT_EDGE = 2
    private const val MAX_LUT_EDGE = 65
}
