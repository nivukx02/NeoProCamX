package com.neoproc.neoproccamx.rendering

import android.hardware.HardwareBuffer
import android.util.Log
import android.view.Surface
import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.nio.ByteBuffer
import java.io.Closeable

private const val TAG = "VulkanBridge"

enum class VulkanEncoderSurfacePrecision {
    UNKNOWN,
    EIGHT_BIT,
    PACKED_10_BIT,
    TRUSTED_EXTERNAL_10_BIT,
    FP16
}

data class VulkanEncoderSurfaceStatus(
    val bound: Boolean,
    val format: String,
    val bitDepth: Int,
    val precision: VulkanEncoderSurfacePrecision,
    val precisionFallback: Boolean,
    val colorSpace: String,
    val deliveryMode: String,
    val deliveryCompatible: Boolean,
    val sourceFormat: String = "UNDEFINED",
    val sourceBitDepth: Int = 0,
    val sourcePrecision: VulkanEncoderSurfacePrecision = VulkanEncoderSurfacePrecision.UNKNOWN,
    val sourcePrecisionProven: Boolean = false
) {
    val surfacePrecisionProven: Boolean
        get() = bound &&
            isRecognizedEncoderSurfaceFormat(format, precision) &&
            isRecognizedEncoderSurfaceBitDepth(bitDepth, precision) &&
            isKnownEncoderDeliveryMode(deliveryMode) &&
            isKnownEncoderColorSpace(colorSpace) &&
            deliveryCompatible

    val precisionProven: Boolean
        get() = surfacePrecisionProven &&
            (
                !requiresSeparateSourcePrecisionProof(format) ||
                    (
                        sourcePrecisionProven &&
                            isRecognizedEncoderSourceFormat(sourceFormat, sourcePrecision) &&
                            isRecognizedEncoderSurfaceBitDepth(sourceBitDepth, sourcePrecision)
                        )
                )

    val supportsTenBitProducer: Boolean
        get() = precisionProven &&
            bitDepth >= 10 &&
            (
                !requiresSeparateSourcePrecisionProof(format) ||
                    sourceBitDepth >= 10
                )

    companion object {
        val UNBOUND: VulkanEncoderSurfaceStatus = VulkanEncoderSurfaceStatus(
            bound = false,
            format = "UNDEFINED",
            bitDepth = 0,
            precision = VulkanEncoderSurfacePrecision.UNKNOWN,
            precisionFallback = false,
            colorSpace = "UNDEFINED",
            deliveryMode = "UNKNOWN",
            deliveryCompatible = false
        )
    }
}

internal object VulkanEncoderSurfaceStatusParser {
    fun parse(summary: String): VulkanEncoderSurfaceStatus {
        val tokens: Map<String, String> = parseEncoderSurfaceTokens(summary)
        val bound = tokens["encoderSurfaceBound"]?.toBooleanStrictOrNull()
            ?: return VulkanEncoderSurfaceStatus.UNBOUND
        if (!bound) return VulkanEncoderSurfaceStatus.UNBOUND

        val format: String = tokens["encoderSurfaceFormat"].orEmpty()
        val bitDepth: Int = tokens["encoderSurfaceBitDepth"]?.toIntOrNull() ?: 0
        val reportedPrecision: VulkanEncoderSurfacePrecision =
            parsePrecision(tokens["encoderSurfacePrecision"])
        val precisionFallback: Boolean =
            tokens["encoderSurfacePrecisionFallback"]?.toBooleanStrictOrNull() ?: false
        val colorSpace: String = tokens["encoderSurfaceColorSpace"].orEmpty()
        val deliveryMode: String = tokens["encoderSurfaceDeliveryMode"].orEmpty()
        val reportedDeliveryCompatible: Boolean =
            tokens["encoderSurfaceDeliveryCompatible"]?.toBooleanStrictOrNull() ?: false
        val sourceFormat: String = tokens["encoderSourceFormat"].orEmpty()
        val sourceBitDepth: Int = tokens["encoderSourceBitDepth"]?.toIntOrNull() ?: 0
        val sourcePrecision: VulkanEncoderSurfacePrecision =
            parsePrecision(tokens["encoderSourcePrecision"])
        val reportedSourcePrecisionProven: Boolean =
            tokens["encoderSourcePrecisionProven"]?.toBooleanStrictOrNull() ?: false
        val precisionCoherent: Boolean =
            isRecognizedEncoderSurfaceFormat(format, reportedPrecision) &&
                isRecognizedEncoderSurfaceBitDepth(bitDepth, reportedPrecision) &&
                precisionFallback ==
                    (reportedPrecision == VulkanEncoderSurfacePrecision.EIGHT_BIT)
        if (!precisionCoherent) {
            return VulkanEncoderSurfaceStatus.UNBOUND.copy(bound = true)
        }

        val colorSpaceCompatible: Boolean =
            isEncoderColorSpaceCompatibleWithDelivery(deliveryMode, colorSpace)
        return VulkanEncoderSurfaceStatus(
            bound = true,
            format = format,
            bitDepth = bitDepth,
            precision = reportedPrecision,
            precisionFallback = precisionFallback,
            colorSpace = normalizedEvidenceToken(colorSpace),
            deliveryMode = normalizedEvidenceToken(deliveryMode),
            deliveryCompatible = reportedDeliveryCompatible && colorSpaceCompatible,
            sourceFormat = normalizedEvidenceToken(sourceFormat),
            sourceBitDepth = sourceBitDepth,
            sourcePrecision = sourcePrecision,
            sourcePrecisionProven =
                reportedSourcePrecisionProven &&
                    isRecognizedEncoderSourceFormat(sourceFormat, sourcePrecision) &&
                    isRecognizedEncoderSurfaceBitDepth(sourceBitDepth, sourcePrecision)
        )
    }

    fun parsePrecision(value: String?): VulkanEncoderSurfacePrecision {
        return when (value) {
            "EIGHT_BIT" -> VulkanEncoderSurfacePrecision.EIGHT_BIT
            "PACKED_10_BIT" -> VulkanEncoderSurfacePrecision.PACKED_10_BIT
            "TRUSTED_EXTERNAL_10_BIT" -> VulkanEncoderSurfacePrecision.TRUSTED_EXTERNAL_10_BIT
            "FP16" -> VulkanEncoderSurfacePrecision.FP16
            else -> VulkanEncoderSurfacePrecision.UNKNOWN
        }
    }
}

private fun parseEncoderSurfaceTokens(summary: String): Map<String, String> {
    val tokens = LinkedHashMap<String, String>()
    val parts: List<String> = summary.split(' ')
    for (token: String in parts) {
        val splitIndex: Int = token.indexOf('=')
        if (splitIndex > 0 && splitIndex < token.lastIndex) {
            val key: String = token.substring(0, splitIndex)
            val value: String = token.substring(splitIndex + 1)
            tokens[key] = value
        }
    }
    return tokens
}

private fun normalizedEvidenceToken(value: String): String {
    return if (value.isBlank()) "UNKNOWN" else value
}

private fun isRecognizedEncoderSurfaceBitDepth(
    bitDepth: Int,
    precision: VulkanEncoderSurfacePrecision
): Boolean {
    return when (precision) {
        VulkanEncoderSurfacePrecision.EIGHT_BIT -> bitDepth == 8
        VulkanEncoderSurfacePrecision.PACKED_10_BIT -> bitDepth == 10
        VulkanEncoderSurfacePrecision.TRUSTED_EXTERNAL_10_BIT -> bitDepth == 10
        VulkanEncoderSurfacePrecision.FP16 -> bitDepth == 16
        VulkanEncoderSurfacePrecision.UNKNOWN -> false
    }
}

private fun isRecognizedEncoderSurfaceFormat(
    format: String,
    precision: VulkanEncoderSurfacePrecision
): Boolean {
    return when (precision) {
        VulkanEncoderSurfacePrecision.EIGHT_BIT ->
            format == "RGBA8" ||
                format == "BGRA8" ||
                format == "RGBA8_SRGB" ||
                format == "BGRA8_SRGB" ||
                format == "R8G8B8A8_UNORM" ||
                format == "B8G8R8A8_UNORM" ||
                format == "R8G8B8A8_SRGB" ||
                format == "B8G8R8A8_SRGB"
        VulkanEncoderSurfacePrecision.PACKED_10_BIT ->
            format == "A2B10G10R10" ||
                format == "A2R10G10B10" ||
                format == "EGL_RGB10_A2" ||
                format == "A2B10G10R10_UNORM_PACK32" ||
                format == "A2R10G10B10_UNORM_PACK32"
        VulkanEncoderSurfacePrecision.TRUSTED_EXTERNAL_10_BIT -> false
        VulkanEncoderSurfacePrecision.FP16 ->
            format == "RGBA16F" || format == "R16G16B16A16_SFLOAT"
        VulkanEncoderSurfacePrecision.UNKNOWN -> false
    }
}

private fun requiresSeparateSourcePrecisionProof(format: String): Boolean =
    format == "EGL_RGB10_A2"

private fun isRecognizedEncoderSourceFormat(
    format: String,
    precision: VulkanEncoderSurfacePrecision
): Boolean {
    return when (precision) {
        VulkanEncoderSurfacePrecision.PACKED_10_BIT ->
            format == "AHARDWAREBUFFER_FORMAT_YCbCr_P010" ||
                format == "AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM"
        VulkanEncoderSurfacePrecision.TRUSTED_EXTERNAL_10_BIT ->
            isOpaqueVendorHardwareBufferFormat(format)
        else -> false
    }
}

private fun isOpaqueVendorHardwareBufferFormat(format: String): Boolean {
    val numericSuffix = format.removePrefix("AHARDWAREBUFFER_FORMAT_")
    return numericSuffix.length != format.length && numericSuffix.isNotEmpty() &&
        numericSuffix.all { it in '0'..'9' }
}

private fun isKnownEncoderDeliveryMode(deliveryMode: String): Boolean {
    return deliveryMode == "SDR" || deliveryMode == "HLG" || deliveryMode == "PQ"
}

private fun isKnownEncoderColorSpace(colorSpace: String): Boolean {
    return colorSpace == "SRGB_NONLINEAR" ||
        colorSpace == "EXTENDED_SRGB_LINEAR" ||
        colorSpace == "EXTENDED_SRGB_NONLINEAR" ||
        colorSpace == "BT2020_LINEAR" ||
        colorSpace == "HDR10_ST2084" ||
        colorSpace == "HDR10_HLG"
}

private fun isEncoderHdrFallbackColorSpace(colorSpace: String): Boolean {
    // Wide-gamut / extended-range encodings that can genuinely carry HDR are accepted as a
    // last-resort HDR fallback. Plain sRGB (SRGB_NONLINEAR) is an SDR transfer curve and is
    // deliberately EXCLUDED: an sRGB-tagged surface must never be treated as HLG/PQ-compatible
    // (no-downgrade — a non-HDR surface is never reported HDR-compatible).
    return colorSpace == "BT2020_LINEAR" ||
        colorSpace == "EXTENDED_SRGB_LINEAR" ||
        colorSpace == "EXTENDED_SRGB_NONLINEAR"
}

private fun isEncoderColorSpaceCompatibleWithDelivery(
    deliveryMode: String,
    colorSpace: String
): Boolean {
    return when (deliveryMode) {
        "SDR" ->
            colorSpace == "SRGB_NONLINEAR" ||
                colorSpace == "EXTENDED_SRGB_LINEAR" ||
                colorSpace == "EXTENDED_SRGB_NONLINEAR"
        "HLG" ->
            colorSpace == "HDR10_HLG" || isEncoderHdrFallbackColorSpace(colorSpace)
        "PQ" ->
            colorSpace == "HDR10_ST2084" || isEncoderHdrFallbackColorSpace(colorSpace)
        else -> false
    }
}

data class VulkanEnhancementTelemetry(
    val nativeP010Compute: String,
    val averageComputeMs: Double,
    val totalDispatches: Long,
    val intermediateFormat: String,
    val barrierSync: String,
    val lensWarping: String,
    val cinematicHalation: String,
    val syncPolicy: String,
    val timestampQueries: String,
    val rawSummary: String
) {
    val computeDispatched: Boolean
        get() = nativeP010Compute == "active-dispatched"
}

data class HDR10Metadata(
    val maxCLL: Int,
    val maxFALL: Int,
    val minLuminance: Int
)

class HDRMetadataGenerator {
    fun calculateMetadata(histogram: IntArray): HDR10Metadata {
        require(histogram.size == 256) { "histogram must contain 256 bins" }
        val totalPixels = histogram.fold(0L) { acc, count -> acc + count.coerceAtLeast(0) }
        if (totalPixels <= 0L) {
            return HDR10Metadata(maxCLL = 100, maxFALL = 100, minLuminance = 0)
        }
        val percentileTarget = maxOf(1L, (totalPixels * 99L + 99L) / 100L)
        var cumulative = 0L
        var percentileBin = 0
        histogram.forEachIndexed { bin, count ->
            if (cumulative < percentileTarget) {
                cumulative += count.coerceAtLeast(0)
                percentileBin = bin
            }
        }
        val weightedBins = histogram.foldIndexed(0L) { bin, acc, count ->
            acc + bin.toLong() * count.coerceAtLeast(0).toLong()
        }
        fun binToNits(bin: Int): Int =
            ((bin.coerceIn(0, 255).toDouble() / 255.0) * 4000.0).toInt().coerceIn(0, 4000)

        val maxCll = binToNits(percentileBin).coerceIn(100, 4000)
        val meanBin = weightedBins.toDouble() / totalPixels.toDouble()
        val maxFall = (((meanBin / 255.0) * 4000.0).toInt())
            .coerceIn(0, maxCll)
        return HDR10Metadata(
            maxCLL = maxCll,
            maxFALL = maxFall,
            minLuminance = 0
        )
    }
}

class VulkanBridge : Closeable {

    companion object {
        const val ENHANCEMENT_UNIFORM_FLOAT_COUNT = 44
        const val vendorHdr10PlusCertified: Boolean = false
        const val vendorDolbyVisionCertified: Boolean = false

        init {
            System.loadLibrary("neoproc_vulkan")
        }

        @JvmStatic
        external fun nativeSetPipelineCacheConfig(cacheDir: String?, enabled: Boolean)

        /**
         * s7-f13/CP9C — set the process-global disk pipeline-cache config BEFORE the bridge's eager
         * native init (createGlobalResources). Default-off callers (cacheDir null / enabled false)
         * reproduce today's fresh-compile cold start bit-for-bit.
         */
        fun configurePipelineCache(cacheDir: String?, enabled: Boolean) {
            nativeSetPipelineCacheConfig(cacheDir, enabled)
        }

        private fun parseCapabilityTokenMap(summary: String): Map<String, String> =
            summary
                .split(' ')
                .mapNotNull { token ->
                    val split = token.indexOf('=')
                    if (split <= 0 || split == token.lastIndex) {
                        null
                    } else {
                        token.substring(0, split) to token.substring(split + 1)
                    }
                }
                .toMap()

        fun parseEnhancementTelemetry(summary: String): VulkanEnhancementTelemetry {
            val tokens = parseCapabilityTokenMap(summary)
            return VulkanEnhancementTelemetry(
                nativeP010Compute = tokens["nativeP010Compute"].orEmpty(),
                averageComputeMs = tokens["avgComputeMs"]?.toDoubleOrNull() ?: 0.0,
                totalDispatches = tokens["totalDispatches"]?.toLongOrNull() ?: 0L,
                intermediateFormat = tokens["intermediateFormat"].orEmpty(),
                barrierSync = tokens["barrierSync"].orEmpty(),
                lensWarping = tokens["lensWarping"].orEmpty(),
                cinematicHalation = tokens["cinematicHalation"].orEmpty(),
                syncPolicy = tokens["syncPolicy"].orEmpty(),
                timestampQueries = tokens["timestampQueries"].orEmpty(),
                rawSummary = summary
            )
        }

        fun parseHdrMetadata(summary: String): HDR10Metadata {
            val tokens = parseCapabilityTokenMap(summary)
            return HDR10Metadata(
                maxCLL = tokens["maxCLL"]?.toIntOrNull()?.coerceIn(100, 4000) ?: 100,
                maxFALL = tokens["maxFALL"]?.toIntOrNull()?.coerceAtLeast(0) ?: 100,
                minLuminance = tokens["minLuminance"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            ).let { metadata ->
                metadata.copy(maxFALL = metadata.maxFALL.coerceAtMost(metadata.maxCLL))
            }
        }
    }

    private var nativeHandle: Long = nativeCreate()
    
    private val summaryBuffer = ByteBuffer.allocateDirect(1024)
    private val summaryBytes = ByteArray(1024)
    // The summary buffer/byte-array are shared instance scratch. capabilitySummary() /
    // enhancementCapabilitySummary() are now read both by the frame pipeline thread (per-frame
    // refreshCapabilitySummary) AND by the runtime health monitor on the main thread every ~150ms, so
    // the clear()->nativeGet()->get() sequence must be mutually exclusive to avoid a torn read.
    private val summaryLock = Any()

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun isSupported(): Boolean {
        val handle = nativeHandle
        return handle != 0L && nativeIsSupported(handle)
    }

    fun setPreviewSurface(surface: Surface?): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        return nativeSetPreviewSurface(handle, surface)
    }

    fun setEncoderSurface(surface: Surface?): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        return nativeSetEncoderSurface(handle, surface)
    }

    fun updateRenderConfig(
        previewTransform: FloatArray,
        encoderTransform: FloatArray,
        colorGain: FloatArray,
        previewGrade: FloatArray,
        encoderGrade: FloatArray,
        previewColorScience: FloatArray,
        encoderColorScience: FloatArray
    ) {
        val handle = nativeHandle
        if (handle == 0L) return
        require(previewTransform.size == 16) { "previewTransform must contain 16 floats" }
        require(encoderTransform.size == 16) { "encoderTransform must contain 16 floats" }
        require(colorGain.size == 4) { "colorGain must contain 4 floats" }
        require(previewGrade.size == 16) { "previewGrade must contain 16 floats" }
        require(encoderGrade.size == 16) { "encoderGrade must contain 16 floats" }
        require(previewColorScience.size == VulkanColorScienceUniforms.FLOAT_COUNT) {
            "previewColorScience must contain ${VulkanColorScienceUniforms.FLOAT_COUNT} floats"
        }
        require(encoderColorScience.size == VulkanColorScienceUniforms.FLOAT_COUNT) {
            "encoderColorScience must contain ${VulkanColorScienceUniforms.FLOAT_COUNT} floats"
        }
        require(previewTransform.all { it.isFinite() }) { "previewTransform contains non-finite values" }
        require(encoderTransform.all { it.isFinite() }) { "encoderTransform contains non-finite values" }
        require(colorGain.all { it.isFinite() }) { "colorGain contains non-finite values" }
        require(previewGrade.all { it.isFinite() }) { "previewGrade contains non-finite values" }
        require(encoderGrade.all { it.isFinite() }) { "encoderGrade contains non-finite values" }
        require(previewColorScience.all { it.isFinite() }) { "previewColorScience contains non-finite values" }
        require(encoderColorScience.all { it.isFinite() }) { "encoderColorScience contains non-finite values" }
        val effectiveColorGain = if (strictHighEndEnabled()) {
            floatArrayOf(
                colorGain[0].coerceIn(0.85f, 1.35f),
                colorGain[1].coerceIn(0.85f, 1.35f),
                colorGain[2].coerceIn(0.85f, 1.35f),
                // Alpha is shader blend strength, not an RGB gain. A zero
                // blend must stay zero for color-critical SDR parity.
                colorGain[3].coerceIn(0f, 1f)
            )
        } else {
            colorGain
        }
        nativeUpdateRenderConfig(
            handle,
            previewTransform,
            encoderTransform,
            effectiveColorGain,
            previewGrade,
            encoderGrade,
            previewColorScience,
            encoderColorScience
        )
    }

    fun updateEnhancementUniforms(uniforms: GpuImageEnhancementUniforms): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        val packed = uniforms.toFloatArray()
        require(packed.size == ENHANCEMENT_UNIFORM_FLOAT_COUNT) {
            "enhancement uniforms must contain $ENHANCEMENT_UNIFORM_FLOAT_COUNT floats"
        }
        require(packed.all { it.isFinite() }) { "enhancement uniforms contain non-finite values" }
        return nativeUpdateEnhancementUniforms(handle, packed, uniforms.signature)
    }

    fun updatePreviewLut(
        lutData: FloatArray?,
        size: Int
    ) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeUpdatePreviewLut(handle, lutData, size)
    }

    fun updateSubjectDetailMask(
        maskWidth: Int,
        maskHeight: Int,
        alphaMask: ByteArray?,
        trackingId: Int
    ): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        if (alphaMask == null) {
            return nativeUpdateSubjectDetailMask(handle, 0, 0, null, -1)
        }
        require(maskWidth > 0 && maskHeight > 0) { "subject detail mask dimensions must be positive" }
        require(alphaMask.size == maskWidth * maskHeight) { "subject detail mask payload size does not match dimensions" }
        return nativeUpdateSubjectDetailMask(handle, maskWidth, maskHeight, alphaMask, trackingId)
    }

    fun capabilitySummary(): String {
        val handle = nativeHandle
        if (handle == 0L) return "Vulkan bridge released"
        val summary = synchronized(summaryLock) {
            summaryBuffer.clear()
            val length = nativeGetCapabilitySummary(handle, summaryBuffer)
            summaryBuffer.position(0)
            summaryBuffer.limit(length)
            summaryBuffer.get(summaryBytes, 0, length)
            String(summaryBytes, 0, length, Charsets.UTF_8)
        }
        return if (strictHighEndEnabled()) {
            "$summary policy=strict-high-end"
        } else {
            summary
        }
    }

    fun encoderSurfaceStatus(): VulkanEncoderSurfaceStatus =
        VulkanEncoderSurfaceStatusParser.parse(capabilitySummary())

    fun enhancementCapabilitySummary(): String {
        val handle = nativeHandle
        if (handle == 0L) return "gpuEnhancement=released"
        return synchronized(summaryLock) {
            summaryBuffer.clear()
            val length = nativeGetEnhancementCapabilitySummary(handle, summaryBuffer)
            summaryBuffer.position(0)
            summaryBuffer.limit(length)
            summaryBuffer.get(summaryBytes, 0, length)
            String(summaryBytes, 0, length, Charsets.UTF_8)
        }
    }

    fun parityBridgeSummary(): String =
        if (nativeHandle == 0L) {
            "previewRecordingParity=released"
        } else {
            nativeGetParityBridgeSummary()
        }

    fun enhancementTelemetry(): VulkanEnhancementTelemetry =
        parseEnhancementTelemetry(enhancementCapabilitySummary())

    fun latestHdrMetadata(): HDR10Metadata =
        parseHdrMetadata(enhancementCapabilitySummary())

    fun submitHardwareBuffer(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        format: Int,
        presentationTimeNs: Long = 0L
    ): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        return nativeSubmitHardwareBuffer(
            handle = handle,
            hardwareBuffer = hardwareBuffer,
            width = width,
            height = height,
            format = format,
            presentationTimeNs = presentationTimeNs
        )
    }

    /**
     * Inform the native bridge of the active HDR dynamic-range mode so
     * subsequent swapchain (re)builds pick the right
     * `(VkFormat, VkColorSpaceKHR)` pair via
     * [HdrSwapchainFormatPolicy]. The native side will rebuild any bound
     * preview/encoder swapchains whose currently-selected format no
     * longer matches the new mode.
     *
     * Safe to call before any surface is bound — the mode is cached and
     * applied at first bind.
     */
    fun setHdrDynamicRangeMode(mode: HdrDynamicRangeMode): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        return nativeSetHdrDynamicRangeMode(
            handle = handle,
            modeId = HdrSwapchainFormatPolicy.toNativeModeId(mode)
        )
    }

    /**
     * X2/R1: request (or revoke) a 10-bit packed sRGB DISPLAY (preview) swapchain in SDR. Falls back
     * byte-identically to 8-bit when the driver advertises no 10-bit SDR display pair. Distinct from
     * [setHdrDynamicRangeMode] because the flag flips while staying in SDR (mode unchanged), so the
     * native side rebuilds the preview target unconditionally rather than on an hdrModeId change.
     */
    fun setPreferTenBitSdrDisplay(enabled: Boolean): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        return nativeSetPreferTenBitSdrDisplay(handle = handle, enabled = enabled)
    }

    /**
     * ARCHITECTURAL FIX: Explicitly destroy VkImage and VkDeviceMemory for all
     * imported frames to prevent VRAM leaks during session resets.
     */
    fun cleanupImportedFrames() {
        runCatching {
            val handle = nativeHandle
            if (handle != 0L) nativeCleanupImportedFrames(handle)
        }.onFailure { Log.w(TAG, "cleanupImportedFrames failed", it) }
    }

    fun updateFocusMap(focusMap: FloatArray) {
        val handle = nativeHandle
        if (handle != 0L && focusMap.size == 256) {
            nativeUpdateFocusMap(handle, focusMap)
        }
    }


    /**
     * Enables the gated OmniClarity classical motion stack (Argus flow -> Riven deblur ->
     * Chronos temporal warp) in the live render loop. Default off — when off the shipping render
     * path is unchanged. Requires on-device GPU validation before shipping enabled.
     */
    fun setOmniClarityEnabled(enabled: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetOmniClarityEnabled(handle, enabled)
    }

    /**
     * s3-f01 / CP54b — Argus flow-stage governor levers. [refineIterations] is the Stage-B Jacobi
     * ping-pong count (clamped to [2,8] and forced even on the native side); [sceneCutActive] forces the
     * frame through the CP51 `previousValid=0` path on a detected hard cut. Resolved on the JVM by
     * [ArgusFlowStagePolicy.liveRefineIterations] / [ArgusFlowStagePolicy.isSceneCutSpike] and wired by the
     * s3-f04 OmniClarity policies. Neutral values (8, false) keep the live flow field bit-for-bit identical.
     */
    fun setOmniClarityFlowTuning(refineIterations: Int, sceneCutActive: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetOmniClarityFlowTuning(handle, refineIterations, sceneCutActive)
    }

    /**
     * s3-f02 / CP52b — Chronos temporal-blend governor lever. [baseTemporalAlpha] / [maxTemporalAlpha]
     * drive the warp's confidence-gated temporal blend (clamped to [0,1] on the native side). Resolved on
     * the JVM by [ChronosTemporalPolicy] and wired by the s3-f04 OmniClarity policies. Neutral values
     * (0.0, 0.9) keep the live warp output bit-for-bit identical to CP52. The first-frame / scene-cut
     * pass-through still force-overrides these to 0.
     */
    fun setOmniClarityWarpTuning(baseTemporalAlpha: Float, maxTemporalAlpha: Float) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetOmniClarityWarpTuning(handle, baseTemporalAlpha, maxTemporalAlpha)
    }

    /**
     * s3-f03 / CP53 — Riven noise-aware deblur governor lever. [rlIterations] is the van Cittert / RL
     * iteration count (floored at 1, capped at 8 native-side); [snrInverse] is the Wiener 1/SNR noise
     * floor (clamped >= 0). Resolved on the JVM by [RivenDeblurPolicy.liveDeblurTuning] and wired by the
     * s3-f04 OmniClarity policies. Neutral values (1, 0.0) keep the live deblur output bit-for-bit identical
     * to CP53 Path A.
     */
    fun setOmniClarityDeblurTuning(rlIterations: Int, snrInverse: Float) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetOmniClarityDeblurTuning(handle, rlIterations, snrInverse)
    }

    /**
     * s3-f04 / CP91 — push one resolved [OmniClarityMotionTuning] (from [OmniClarityMotionTuningResolver])
     * into the live OmniClarity dispatch by fanning it across the three stage setters. A neutral tuning
     * (the resolver's default) pushes the CP-locked native defaults, so this is a no-op on a cool/idle
     * device. Driven by [VulkanRuntimeManager.updateOmniClarityMotionTuning] once the governor is enabled.
     */
    internal fun applyOmniClarityMotionTuning(tuning: OmniClarityMotionTuning) {
        setOmniClarityFlowTuning(tuning.refineIterations, tuning.sceneCutActive)
        setOmniClarityWarpTuning(tuning.baseTemporalAlpha, tuning.maxTemporalAlpha)
        setOmniClarityDeblurTuning(tuning.rlIterations, tuning.snrInverse)
    }

    /** SNCE v2.0 HVC Stage-04 live opponent lateral-inhibition pass. Default on; toggle off if the look regresses. */
    fun setSnceLivePerceptualEnabled(enabled: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetSnceLivePerceptualEnabled(handle, enabled)
    }

    /**
     * CP-QV21 — replace the live SNCE Stage-11 NRLI 3D-LUT with a caller-supplied 33^3 look and set the
     * blend [strength]. Default behaviour is untouched: the native side ships an identity LUT at strength
     * 0 (exact passthrough), and there is deliberately **no live caller** of this method — it is device-side
     * plumbing gated by [com.neoproc.neoproccamx.snce.SnceNrliLutFlag]. When never called, the enhance path
     * is byte-for-byte identical to today.
     *
     * The native side is fail-safe: an invalid array (wrong length / non-finite) or a failed GPU upload
     * leaves the identity LUT + strength 0 intact. [lut] must be `size^3 * 3` floats (R-fastest, matching
     * [com.neoproc.neoproccamx.snce.SnceAdaptiveLut] and the native `createNrliIdentityLut`); the native
     * side re-uploads it into the existing NRLI LUT image (descriptors stay valid) and clamps [strength]
     * to [0,1]. Passing strength 0 keeps an exact passthrough regardless of LUT contents.
     */
    fun updateNrliLut(lut: FloatArray, strength: Float) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeUpdateNrliLut(handle, lut, strength)
    }

    /**
     * s1-f06 / CP12 — MFSR real-input control path. Default ON: routes the real heteroscedastic
     * noise / gyro / frame-count (and, once a ring exists, the live Argus flow) into the fusion pass.
     * The visible fusion stays gated by [setMfsrBurstRingReady] until a real full-res burst ring feeds
     * MFSR binding 0 on-device — until then the push constants stay byte-identical to shipping.
     */
    fun setMfsrRealInputsEnabled(enabled: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetMfsrRealInputsEnabled(handle, enabled)
    }

    /**
     * DEVICE-GATED: flip true only after building + validating a real full-res N-frame RAW/luma ring
     * into MFSR binding 0. The 1x1 zero placeholder array cannot carry real frames, so enabling fusion
     * before the ring exists makes MFSR emit ~zero into the visible chain (black-out). Default false.
     */
    fun setMfsrBurstRingReady(ready: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetMfsrBurstRingReady(handle, ready)
    }

    /**
     * Push the real MFSR fusion push constants resolved on the JVM by
     * `CameraSensorNoiseModelPolicy` → [MfsrFusionParams] (`activeFrameCount`, shot `noiseS`, read
     * `noiseO`, `gyroOffsetScale`). Consumed only when real inputs are enabled AND the burst ring is ready.
     */
    fun setMfsrFusionParams(activeFrameCount: Int, noiseS: Float, noiseO: Float, gyroOffsetScale: Float) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetMfsrFusionParams(handle, activeFrameCount, noiseS, noiseO, gyroOffsetScale)
    }

    /**
     * s1-f07 / CP10c — RAW-preview cinema-debayer gate. Default OFF (no-downgrade): with this false the
     * demosaic dispatch is unreachable and the shipping YUV preview path is byte-identical. The cinema
     * debayer pipeline flips it on only for RAW+VIDEO. DEVICE-GATED: actual on-device enablement still
     * requires a validation-layer-clean dispatch + visual confirmation on the target sensor.
     */
    fun setRawPreviewDemosaicEnabled(enabled: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetRawPreviewDemosaicEnabled(handle, enabled)
    }

    /**
     * s1-f07 / CP10c — dispatch demosaic_vulkan.comp on a 12-bit RAW (R16_UINT, format 0x20) mosaic:
     * WB-before-demosaic + hard-gradient green (CP14) → sensor→XYZ→ACEScg (CP11) into an owned rgba16f
     * image. Matrices are row-major 3x3; the native side transposes them into the std140 column-major
     * mat3 layout. Returns false (→ caller YUV fallback) when the gate is off, the pipeline is not yet
     * built, or the device can't import the mosaic as a storage R16_UINT image.
     */
    fun dispatchRawDemosaicPreview(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        bayerPattern: Int,
        whiteLevel: Int,
        blackLevelRggb: FloatArray,
        whiteBalanceRggb: FloatArray,
        exposureThermal: FloatArray,
        sensorToXyz: FloatArray,
        xyzToAcescg: FloatArray
    ): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        require(blackLevelRggb.size == 4) { "blackLevelRggb must contain 4 floats" }
        require(whiteBalanceRggb.size == 4) { "whiteBalanceRggb must contain 4 floats" }
        require(exposureThermal.size == 4) { "exposureThermal must contain 4 floats" }
        require(sensorToXyz.size == 9) { "sensorToXyz must contain 9 floats (row-major 3x3)" }
        require(xyzToAcescg.size == 9) { "xyzToAcescg must contain 9 floats (row-major 3x3)" }
        require(blackLevelRggb.all { it.isFinite() }) { "blackLevelRggb contains non-finite values" }
        require(whiteBalanceRggb.all { it.isFinite() }) { "whiteBalanceRggb contains non-finite values" }
        require(exposureThermal.all { it.isFinite() }) { "exposureThermal contains non-finite values" }
        require(sensorToXyz.all { it.isFinite() }) { "sensorToXyz contains non-finite values" }
        require(xyzToAcescg.all { it.isFinite() }) { "xyzToAcescg contains non-finite values" }
        return nativeDispatchRawDemosaicPreview(
            handle, hardwareBuffer, width, height, bayerPattern, whiteLevel,
            blackLevelRggb, whiteBalanceRggb, exposureThermal, sensorToXyz, xyzToAcescg
        )
    }

    /**
     * Record-time live-monitor variant of [dispatchRawDemosaicPreview]: demosaic a CPU RAW mosaic COPY
     * (already lifted off the camera RAW Image so the reader buffer is freed for the recorder) held in the
     * direct ByteBuffer [rawBytes] (row-strided uint16). [decimate] (2 = half-res) keeps the record-time
     * monitor at ~1/4 the GPU/bandwidth of the full-res idle path. Returns false (→ no present) when the
     * gate is off, the pipeline is not yet built, or the buffer is too small.
     */
    fun dispatchRawDemosaicPreviewFromBytes(
        rawBytes: java.nio.ByteBuffer,
        rowStrideBytes: Int,
        width: Int,
        height: Int,
        decimate: Int,
        bayerPattern: Int,
        whiteLevel: Int,
        blackLevelRggb: FloatArray,
        whiteBalanceRggb: FloatArray,
        exposureThermal: FloatArray,
        sensorToXyz: FloatArray,
        xyzToAcescg: FloatArray
    ): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        if (!rawBytes.isDirect) return false
        require(blackLevelRggb.size == 4) { "blackLevelRggb must contain 4 floats" }
        require(whiteBalanceRggb.size == 4) { "whiteBalanceRggb must contain 4 floats" }
        require(exposureThermal.size == 4) { "exposureThermal must contain 4 floats" }
        require(sensorToXyz.size == 9) { "sensorToXyz must contain 9 floats (row-major 3x3)" }
        require(xyzToAcescg.size == 9) { "xyzToAcescg must contain 9 floats (row-major 3x3)" }
        return nativeDispatchRawDemosaicPreviewFromBytes(
            handle, rawBytes, rowStrideBytes, width, height, decimate, bayerPattern, whiteLevel,
            blackLevelRggb, whiteBalanceRggb, exposureThermal, sensorToXyz, xyzToAcescg
        )
    }

    /**
     * STAGE 2 CPU-readback present: copy the latest tonemapped RGBA8 debayer monitor (display-ready sRGB)
     * into [dst] (a direct ByteBuffer) and return (previewW shl 16) or previewH, or 0 when no debayered
     * frame is ready or [dst] is too small. Call right after a successful dispatchRawDemosaicPreview.
     */
    fun copyRawDemosaicPreviewInto(dst: java.nio.ByteBuffer): Int {
        val handle = nativeHandle
        if (handle == 0L) return 0
        return nativeCopyRawDemosaicPreviewInto(handle, dst)
    }

    /**
     * OFFLINE RAW develop (RawDevelopGpuAccelerator): demosaic the CPU sensor mosaic in [mosaic] (direct,
     * tightly-packed little-endian uint16, [rowStrideBytes] per row) through the same demosaic_vulkan.comp
     * as the live cinema-debayer, then read the FULL-RES sensor→ACEScg plane back into [out] as
     * width*height*3 float32 RGB components (interleaved, linear ACEScg, PRE-exposure). Returns false on
     * any failure (unsupported, buffer too small, dispatch/readback error) so the caller falls back to the
     * CPU develop (no-downgrade). Synchronous (drains the compute queue before returning).
     */
    fun developRawToAcescg(
        mosaic: java.nio.ByteBuffer,
        rowStrideBytes: Int,
        width: Int,
        height: Int,
        bayerPattern: Int,
        whiteLevel: Int,
        blackLevel: Int,
        whiteBalanceR: Float,
        whiteBalanceGr: Float,
        whiteBalanceGb: Float,
        whiteBalanceB: Float,
        sensorToXyz: FloatArray,
        xyzToAcescg: FloatArray,
        out: java.nio.ByteBuffer
    ): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        if (!mosaic.isDirect || !out.isDirect) return false
        if (width <= 0 || height <= 0) return false
        require(sensorToXyz.size == 9) { "sensorToXyz must contain 9 floats (row-major 3x3)" }
        require(xyzToAcescg.size == 9) { "xyzToAcescg must contain 9 floats (row-major 3x3)" }
        val neededOut = width.toLong() * height.toLong() * 3L * Float.SIZE_BYTES
        if (out.capacity().toLong() < neededOut) return false
        return nativeDevelopRawToAcescg(
            handle, mosaic, rowStrideBytes, width, height, bayerPattern, whiteLevel, blackLevel,
            floatArrayOf(whiteBalanceR, whiteBalanceGr, whiteBalanceGb, whiteBalanceB),
            sensorToXyz, xyzToAcescg, out
        )
    }

    /**
     * s1-f10 / CP31 — dual-conversion-gain fusion gate. Default OFF (no-downgrade): with this false the
     * fusion dispatch is unreachable and the live render path is byte-identical. Enabled only on a device
     * with a real dual-gain readout ring (DEVICE-GATED).
     */
    fun setDualGainFusionEnabled(enabled: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetDualGainFusionEnabled(handle, enabled)
    }

    /**
     * CP-QV16 — gated single-frame spatial denoiser (denoise.comp). Default OFF (no-downgrade): with this
     * false the post-nivukx spatial-denoise dispatch is unreachable and the live render path is
     * byte-for-byte identical. Enabled only after an on-device quality/perf validation on vivo I2405
     * (DEVICE-GATED); this stays a gated foundation with no live caller (see [DenoiseSpatialFlag]).
     */
    fun setSpatialDenoiseEnabled(enabled: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetDenoiseSpatialEnabled(handle, enabled)
    }

    /**
     * CP-QV17 — gated optical-recovery sharpener (sharpen.comp). Default OFF (no-downgrade): with this
     * false the post-denoise sharpen dispatch is unreachable and the live render path is byte-for-byte
     * identical. Enabled only after an on-device quality/perf validation on vivo I2405 (DEVICE-GATED);
     * this stays a gated foundation with no live caller (see [SharpenOpticalFlag]).
     */
    fun setOpticalSharpenEnabled(enabled: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetSharpenOpticalEnabled(handle, enabled)
    }

    /**
     * CP24 — distant-detail elite gates (detail_preservation noise-aware + halo-guard, guided_upsample
     * edge-tighten) as per-dispatch push constants. Default OFF (no-downgrade): with this false all gate
     * push-constant fields are 0.0, so the detail mask + guided upsample are byte-for-byte identical.
     * Enabled only after on-device validation on vivo I2405 (DEVICE-GATED); see [DistantDetailGatesFlag].
     */
    fun setDistantDetailGatesEnabled(enabled: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetDistantDetailGatesEnabled(handle, enabled)
    }

    /**
     * CP30 — BT.2390 EETF peak-nits highlight shoulder (knee_curve.comp) as a per-dispatch push
     * constant. Default OFF (no-downgrade): with this false peakNitsGate is 0.0, so the knee uses
     * today's SDR-referred modified-Reinhard path bit-for-bit. The EETF is identity below diffuse white,
     * so enabling only rolls super-highlights to the display peak. Device-gated; see [ApexPeakNitsFlag].
     */
    fun setApexPeakNitsEnabled(enabled: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetApexPeakNitsEnabled(handle, enabled)
    }

    /**
     * CP-CUSP — exact Rec.2020 Oklab gamut-cusp clamp in the preview knee boundary, driven by a per-frame
     * UBO lane. Default OFF (no-downgrade): the shader takes the byte-identical analytic-guard branch.
     * When ON it is a TRUE gamut clamp that REDUCES out-of-gamut oversaturation (the exact cusp sits
     * below the loose analytic guard) — an accuracy tool, not a vibrance boost. See [ExactGamutCuspFlag].
     */
    fun setExactGamutCuspEnabled(enabled: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetExactGamutCuspEnabled(handle, enabled)
    }

    /**
     * s1-f10 / CP31 — dispatch dual_gain_fusion.comp merging the low- and high-conversion-gain rgba16f
     * ACEScg readouts into one high-DR ACEScg frame. Returns false (→ no fusion, passthrough) when the
     * gate is off, the pipeline is not built, or a readout cannot be imported as a storage rgba16f image.
     */
    internal fun dispatchDualGainFusion(
        lcgBuffer: HardwareBuffer,
        hcgBuffer: HardwareBuffer,
        params: DualGainFusionParams
    ): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        require(
            params.kGain.isFinite() && params.noiseFloor.isFinite() &&
                params.saturationThreshold.isFinite() && params.shadowStretch.isFinite()
        ) { "dual-gain fusion params must be finite" }
        return nativeDispatchDualGainFusion(
            handle, lcgBuffer, hcgBuffer, params.width, params.height,
            params.kGain, params.noiseFloor, params.saturationThreshold, params.shadowStretch
        )
    }

    /**
     * s5-f06 / CP-F7 — depth-driven Vogel-spiral bokeh gate. Default OFF (no-downgrade): with this false the
     * bokeh dispatch is unreachable and the live render path is byte-identical. Enabled only after the
     * on-device depth/color feed + occlusion validation (DEVICE-GATED); visible bokeh is never default-on.
     */
    fun setBokehBlendEnabled(enabled: Boolean) {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeSetBokehBlendEnabled(handle, enabled)
    }

    /**
     * s5-f06 / CP-F7 — dispatch bokeh_vogel_spiral.comp over the owned, deterministically-cleared bindings
     * with the supplied focus-plane + CoC-scale params. Returns false (-> no bokeh, passthrough) when the
     * gate is off or the pipeline is not built. blendStrength 0 is an exact passthrough.
     */
    internal fun dispatchBokehVogelSpiral(params: BokehFusionParams): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        require(
            params.chromaticAberration.isFinite() && params.apertureBladeCount.isFinite() &&
                params.apertureBladeStrength.isFinite() && params.foregroundGuardStrength.isFinite() &&
                params.focusDepth.isFinite() && params.blendStrength.isFinite()
        ) { "bokeh params must be finite" }
        return nativeDispatchBokehVogelSpiral(
            handle, params.width, params.height, params.sampleCount,
            params.chromaticAberration, params.apertureBladeCount, params.apertureBladeStrength,
            params.foregroundGuardStrength, params.focusDepth, params.blendStrength
        )
    }

    /**
     * Live inline bokeh feed: upload the producer's preview-resolution depth + circle-of-confusion
     * grids (row-major, size == width*height) into the inline pass's images and set the focus/blend
     * params. Returns false (=> the inline pass stays skipped / passthrough) if the extent matched no
     * target — a blind enable can never black the frame. Only takes effect while setBokehBlendEnabled(true).
     */
    internal fun uploadBokehInputs(
        depth: FloatArray, coc: FloatArray, width: Int, height: Int,
        focusDepth: Float, blendStrength: Float
    ): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        require(width > 0 && height > 0 && depth.size >= width * height && coc.size >= width * height) {
            "bokeh depth/coc must be width*height row-major grids"
        }
        require(focusDepth.isFinite() && blendStrength.isFinite()) { "bokeh focus/blend must be finite" }
        return nativeUploadBokehInputs(handle, depth, coc, width, height, focusDepth, blendStrength)
    }

    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeDestroy(handle)
        nativeHandle = 0L
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeIsSupported(handle: Long): Boolean
    private external fun nativeSetPreviewSurface(handle: Long, surface: Surface?): Boolean
    private external fun nativeSetEncoderSurface(handle: Long, surface: Surface?): Boolean
    private external fun nativeSetOmniClarityEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetOmniClarityFlowTuning(handle: Long, refineIterations: Int, sceneCutActive: Boolean)
    private external fun nativeSetOmniClarityWarpTuning(handle: Long, baseTemporalAlpha: Float, maxTemporalAlpha: Float)
    private external fun nativeSetOmniClarityDeblurTuning(handle: Long, rlIterations: Int, snrInverse: Float)
    private external fun nativeSetSnceLivePerceptualEnabled(handle: Long, enabled: Boolean)
    private external fun nativeUpdateNrliLut(handle: Long, lutRgb: FloatArray, strength: Float)
    private external fun nativeSetMfsrRealInputsEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetMfsrBurstRingReady(handle: Long, ready: Boolean)
    private external fun nativeSetMfsrFusionParams(
        handle: Long,
        activeFrameCount: Int,
        noiseS: Float,
        noiseO: Float,
        gyroOffsetScale: Float
    )
    private external fun nativeSetRawPreviewDemosaicEnabled(handle: Long, enabled: Boolean)
    private external fun nativeDispatchRawDemosaicPreview(
        handle: Long,
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        bayerPattern: Int,
        whiteLevel: Int,
        blackLevelRggb: FloatArray,
        whiteBalanceRggb: FloatArray,
        exposureThermal: FloatArray,
        sensorToXyz: FloatArray,
        xyzToAcescg: FloatArray
    ): Boolean
    private external fun nativeDispatchRawDemosaicPreviewFromBytes(
        handle: Long,
        rawBytes: java.nio.ByteBuffer,
        rowStrideBytes: Int,
        width: Int,
        height: Int,
        decimate: Int,
        bayerPattern: Int,
        whiteLevel: Int,
        blackLevelRggb: FloatArray,
        whiteBalanceRggb: FloatArray,
        exposureThermal: FloatArray,
        sensorToXyz: FloatArray,
        xyzToAcescg: FloatArray
    ): Boolean
    private external fun nativeCopyRawDemosaicPreviewInto(handle: Long, dst: java.nio.ByteBuffer): Int
    private external fun nativeDevelopRawToAcescg(
        handle: Long,
        mosaic: java.nio.ByteBuffer,
        rowStrideBytes: Int,
        width: Int,
        height: Int,
        bayerPattern: Int,
        whiteLevel: Int,
        blackLevel: Int,
        whiteBalanceRggb: FloatArray,
        sensorToXyz: FloatArray,
        xyzToAcescg: FloatArray,
        out: java.nio.ByteBuffer
    ): Boolean
    private external fun nativeSetDualGainFusionEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetDenoiseSpatialEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetSharpenOpticalEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetDistantDetailGatesEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetApexPeakNitsEnabled(handle: Long, enabled: Boolean)
    private external fun nativeSetExactGamutCuspEnabled(handle: Long, enabled: Boolean)
    private external fun nativeDispatchDualGainFusion(
        handle: Long,
        lcgBuffer: HardwareBuffer,
        hcgBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        kGain: Float,
        noiseFloor: Float,
        saturationThreshold: Float,
        shadowStretch: Float
    ): Boolean
    private external fun nativeSetBokehBlendEnabled(handle: Long, enabled: Boolean)
    private external fun nativeUploadBokehInputs(
        handle: Long,
        depth: FloatArray,
        coc: FloatArray,
        width: Int,
        height: Int,
        focusDepth: Float,
        blendStrength: Float
    ): Boolean
    private external fun nativeDispatchBokehVogelSpiral(
        handle: Long,
        width: Int,
        height: Int,
        sampleCount: Int,
        chromaticAberration: Float,
        apertureBladeCount: Float,
        apertureBladeStrength: Float,
        foregroundGuardStrength: Float,
        focusDepth: Float,
        blendStrength: Float
    ): Boolean
    private external fun nativeUpdateRenderConfig(
        handle: Long,
        previewTransform: FloatArray,
        encoderTransform: FloatArray,
        colorGain: FloatArray,
        previewGrade: FloatArray,
        encoderGrade: FloatArray,
        previewColorScience: FloatArray,
        encoderColorScience: FloatArray
    )
    private external fun nativeUpdateEnhancementUniforms(
        handle: Long,
        uniforms: FloatArray,
        signature: String
    ): Boolean
    private external fun nativeUpdatePreviewLut(
        handle: Long,
        lutData: FloatArray?,
        size: Int
    )
    private external fun nativeUpdateSubjectDetailMask(
        handle: Long,
        maskWidth: Int,
        maskHeight: Int,
        alphaMask: ByteArray?,
        trackingId: Int
    ): Boolean
    private external fun nativeGetCapabilitySummary(handle: Long, buffer: ByteBuffer): Int
    private external fun nativeGetEnhancementCapabilitySummary(handle: Long, buffer: ByteBuffer): Int
    private external fun nativeGetParityBridgeSummary(): String
    private external fun nativeSubmitHardwareBuffer(
        handle: Long,
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        format: Int,
        presentationTimeNs: Long
    ): Boolean
    private external fun nativeSetHdrDynamicRangeMode(
        handle: Long,
        modeId: Int
    ): Boolean
    private external fun nativeSetPreferTenBitSdrDisplay(
        handle: Long,
        enabled: Boolean
    ): Boolean
    private external fun nativeCleanupImportedFrames(handle: Long)
    private external fun nativeUpdateFocusMap(handle: Long, focusMap: FloatArray)
}
