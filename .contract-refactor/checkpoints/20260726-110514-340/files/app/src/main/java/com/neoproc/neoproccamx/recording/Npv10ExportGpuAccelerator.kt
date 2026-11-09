package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.rendering.GpuImageEnhancementUniforms
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class Npv10GpuExportAccelerationResult(
    val payload: ByteArray,
    val evidence: Npv10ExportProcessingEvidence
)

internal object Npv10ExportGpuAccelerator {
    private const val NATIVE_LIBRARY = "neoproc_vulkan"
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

    fun enhanceP010(
        payload: ByteArray,
        previousP010Payload: ByteArray?,
        width: Int,
        height: Int,
        uniforms: GpuImageEnhancementUniforms
    ): Npv10GpuExportAccelerationResult? {
        if (!nativeLibraryAvailable || width <= 0 || height <= 0 || payload.isEmpty()) return null
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
                uniforms = packedUniforms
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
                uniforms = packedUniforms
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
        if (!nativeLibraryAvailable || width <= 0 || height <= 0 || !buffer.isDirect) return false
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
                uniforms = uniforms
            )
        }.getOrDefault(false)
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

    /**
     * True only when the native Vulkan export accelerator library loaded. When false, [enhanceP010]
     * is a guaranteed null-returning no-op, so callers can skip work whose sole consumer is that path.
     */
    fun isAccelerationAvailable(): Boolean = nativeLibraryAvailable

    fun unavailableReason(): String {
        if (!nativeLibraryAvailable) return "NATIVE_LIBRARY_UNAVAILABLE"
        return runCatching { nativeStatus() }
            .getOrDefault("NATIVE_STATUS_UNAVAILABLE")
            .ifBlank { "NATIVE_STATUS_UNAVAILABLE" }
    }

    @JvmStatic
    private external fun nativeEnhanceP010(
        currentPayload: ByteArray,
        previousPayload: ByteArray?,
        width: Int,
        height: Int,
        uniforms: FloatArray
    ): ByteArray?

    @JvmStatic
    private external fun nativeEnhanceP010Direct(
        currentPayload: ByteBuffer,
        previousPayload: ByteBuffer?,
        outputPayload: ByteBuffer,
        width: Int,
        height: Int,
        uniforms: FloatArray
    ): Boolean

    @JvmStatic
    private external fun nativeStatus(): String
}
