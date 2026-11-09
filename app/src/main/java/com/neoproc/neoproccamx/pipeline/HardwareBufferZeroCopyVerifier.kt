package com.neoproc.neoproccamx.pipeline

import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.neoproc.neoproccamx.core.RuntimeStageTelemetry
import java.nio.ByteBuffer

/**
 * HardwareBufferZeroCopyVerifier — Validates and monitors zero-copy frame paths.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * PURPOSE
 * ═════════════════════════════════════════════════════════════════════════════════════
 * NeoProCamX targets "Zero-Copy Pipeline" for flagship tier: camera frames should flow
 * from Camera2 → Vulkan → MediaCodec without ever touching CPU RAM.  This verifier
 * provides:
 *
 *   1. Capabilities probe: Does the device support HardwareBuffer import/export?
 *   2. Runtime validation: Checks that Image.getHardwareBuffer() returns non-null.
 *   3. Performance telemetry: Measures CPU→GPU transfer time (should be ~0 for zero-copy candidate).
 *   4. Fallback advice: If zero-copy fails, suggests YUV_420_888 CPU path.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * ZERO-COPY PATH
 * ═════════════════════════════════════════════════════════════════════════════════════
 *   Camera2 ImageReader (PRIVATE, USAGE_GPU_SAMPLED_IMAGE | USAGE_VIDEO_ENCODE)
 *           ↓
 *   Image.getHardwareBuffer() → EGLImageKHR / AHardwareBuffer
 *           ↓
 *   Vulkan importExternalMemory → sampled image in compute shader
 *           ↓
 *   Vulkan render to encoder Surface (also HardwareBuffer-backed)
 *           ↓
 *   MediaCodec receives GPU-composited frame → H.265 bitstream
 *
 * At no point does glTexSubImage or memcpy() touch application-visible memory.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * USAGE
 * ═════════════════════════════════════════════════════════════════════════════════════
 *   val verifier = HardwareBufferZeroCopyVerifier(context)
 *   if (verifier.isSupported()) {
 *       // Configure ImageReader with HardwareBuffer usage flags
 *   }
 *   verifier.validateFrame(image) // Call for every frame
 */
object HardwareBufferZeroCopyVerifier {

    private const val TAG = "HardwareBufferZeroCopy"

    /** Required HardwareBuffer usage flags for camera→GPU zero-copy sampled encoder input. */
    private val REQUIRED_USAGE_FLAGS =
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
            HardwareBuffer.USAGE_VIDEO_ENCODE

    data class VerificationResult(
        val zeroCopyAvailable: Boolean,
        val hardwareBuffer: HardwareBuffer?,
        val bufferFormat: Int,
        val bufferWidth: Int,
        val bufferHeight: Int,
        val gpuLayerCount: Int,
        val validationTimeNs: Long,
        val isTrulyZeroCopy: Boolean
    ) {
        val summary: String
            get() = buildString {
                append("zeroCopy=$zeroCopyAvailable")
                append(" format=$bufferFormat")
                append(" ${bufferWidth}x${bufferHeight}")
                append(" layers=$gpuLayerCount")
                append(" timeNs=${validationTimeNs}")
                append(if (isTrulyZeroCopy) " [ZERO-COPY]" else " [CPU-FALLBACK]")
            }
    }

    /**
     * Probes device capabilities for HardwareBuffer zero-copy support.
     * Returns true only if all required features are present.
     */
    fun isSupported(): Boolean {
        // Check HardwareBuffer is creatable
        val testBuffer = try {
            HardwareBuffer.create(
                64, 64,
                HardwareBuffer.RGBA_8888,
                1,
                REQUIRED_USAGE_FLAGS
            )
        } catch (t: Throwable) {
            Log.d(TAG, "HardwareBuffer creation failed: ${t.message}")
            return false
        }

        val supported = try {
            testBuffer.format == HardwareBuffer.RGBA_8888
        } catch (t: Throwable) {
            false
        } finally {
            try { testBuffer.close() } catch (t: Throwable) {
                Log.w(TAG, "HardwareBuffer probe close failed", t)
            }
        }

        Log.i(TAG, "Zero-copy probe: supported=$supported")
        return supported
    }

    /**
     * Validates a captured Image for zero-copy properties.
     * Call this from ImageReader.OnImageAvailableListener for telemetry.
     */
    fun validateFrame(image: Image?): VerificationResult {
        val startNs = System.nanoTime()

        if (image == null) {
            return VerificationResult(
                zeroCopyAvailable = false,
                hardwareBuffer = null,
                bufferFormat = -1,
                bufferWidth = 0,
                bufferHeight = 0,
                gpuLayerCount = 0,
                validationTimeNs = System.nanoTime() - startNs,
                isTrulyZeroCopy = false
            )
        }

        val hwBuffer = try {
            image.hardwareBuffer
        } catch (t: Throwable) {
            Log.w(TAG, "getHardwareBuffer() failed", t)
            null
        }

        val elapsed = System.nanoTime() - startNs

        // Telemetry
        RuntimeStageTelemetry.recordDuration(
            tag = TAG,
            stage = "validate",
            durationMs = elapsed / 1_000_000L,
            slowThresholdMs = 1L
        )

        return if (hwBuffer != null) {
            VerificationResult(
                zeroCopyAvailable = true,
                hardwareBuffer = hwBuffer,
                bufferFormat = hwBuffer.format,
                bufferWidth = hwBuffer.width,
                bufferHeight = hwBuffer.height,
                gpuLayerCount = hwBuffer.layers,
                validationTimeNs = elapsed,
                isTrulyZeroCopy = elapsed < 100_000  // < 100 µs suggests zero-copy candidate
            )
        } else {
            VerificationResult(
                zeroCopyAvailable = false,
                hardwareBuffer = null,
                bufferFormat = image.format,
                bufferWidth = image.width,
                bufferHeight = image.height,
                gpuLayerCount = 0,
                validationTimeNs = elapsed,
                isTrulyZeroCopy = false
            )
        }
    }

    /**
     * Dumps diagnostic information about a HardwareBuffer.
     * Useful for bug reports and vendor compatibility testing.
     */
    fun dumpHardwareBufferInfo(buffer: HardwareBuffer?): String {
        if (buffer == null) return "HardwareBuffer: null"
        return buildString {
            append("HardwareBuffer{")
            append("format=${buffer.format}")
            append(", ${buffer.width}x${buffer.height}")
            append(", layers=${buffer.layers}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                append(", usage=${buffer.usage}")
            }
            append('}')
        }
    }

    /**
     * Suggests the optimal ImageReader configuration for zero-copy on this device.
     */
    fun recommendConfig(width: Int, height: Int): Triple<Int, Int, Long> {
        val format = if (isSupported()) {
            // PRIVATE enables HAL buffer reuse
            android.graphics.ImageFormat.PRIVATE
        } else {
            // Fall back to YUV for CPU processing
            android.graphics.ImageFormat.YUV_420_888
        }

        val usage = if (isSupported()) {
            REQUIRED_USAGE_FLAGS
        } else {
            0L
        }

        val maxImages = if (isSupported()) 8 else 4

        return Triple(format, maxImages, usage)
    }
}
