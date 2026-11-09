package com.neoproc.neoproccamx.camera

internal data class CameraStreamBasedFpsEntry(
    val width: Int,
    val height: Int,
    val previewFps: Int,
    val videoFps: Int
)

internal enum class CameraStreamBasedFpsRequestMode {
    NONE,
    STREAM_PREVIEW_VIDEO,
    VENDOR_DYNAMIC_FPS
}

internal data class CameraStreamBasedFpsOverridePlan(
    val enabled: Boolean,
    val appliedFps: Int,
    val previewFps: Int,
    val videoFps: Int,
    val publicAeRange: CameraIntRange?,
    val requestMode: CameraStreamBasedFpsRequestMode,
    val dynamicFpsConfig: Float?,
    val vendorVideoFrameRate: Int?,
    val reason: String
) {
    companion object {
        fun disabled(appliedFps: Int, publicAeRange: CameraIntRange?, reason: String) =
            CameraStreamBasedFpsOverridePlan(
                enabled = false,
                appliedFps = appliedFps,
                previewFps = appliedFps,
                videoFps = appliedFps,
                publicAeRange = publicAeRange,
                requestMode = CameraStreamBasedFpsRequestMode.NONE,
                dynamicFpsConfig = null,
                vendorVideoFrameRate = null,
                reason = reason
            )
    }
}

internal object CameraStreamBasedFpsPolicy {
    const val STREAM_TABLE_KEY_NAME = "org.quic.camera2.streamBasedFPS.info.StreamBasedFPSTable"
    const val PREVIEW_FPS_KEY_NAME = "org.quic.camera2.streamBasedFPS.info.PreviewFPS"
    const val VIDEO_FPS_KEY_NAME = "org.quic.camera2.streamBasedFPS.info.VideoFPS"
    const val DYNAMIC_FPS_CONFIG_KEY_NAME = "org.codeaurora.qcamera3.sessionParameters.dynamicFPSConfig"
    const val VENDOR_VIDEO_FRAME_RATE_KEY_NAME = "vivo.control.videoFrameRate"
    private const val STREAM_ASPECT_TOLERANCE = 0.005f
    private const val HIGH_RESOLUTION_VENDOR_FPS_PIXEL_THRESHOLD = 7_000_000L

    fun parseStreamBasedTable(values: IntArray?): List<CameraStreamBasedFpsEntry> {
        if (values == null || values.size < 4) return emptyList()
        return buildList {
            var index = 0
            while (index + 3 < values.size) {
                val width = values[index]
                val height = values[index + 1]
                val previewFps = values[index + 2]
                val videoFps = values[index + 3]
                if (width > 0 && height > 0 && previewFps > 0 && videoFps > 0) {
                    add(CameraStreamBasedFpsEntry(width, height, previewFps, videoFps))
                }
                index += 4
            }
        }.distinct()
    }

    fun resolve(
        requestedSize: CameraStreamSize?,
        requestedFps: Int,
        publicAeRanges: List<CameraIntRange>,
        streamBasedEntries: List<CameraStreamBasedFpsEntry>,
        requestKeyNames: Set<String>,
        sessionKeyNames: Set<String>
    ): CameraStreamBasedFpsOverridePlan {
        val streamRequestedFps = sanitizeStreamOverrideRequestedFps(requestedFps)
        val publicPlan = CameraPreviewFpsPolicy.resolve(streamRequestedFps, publicAeRanges)
        val requested = publicPlan.requestedPreviewFps.coerceAtLeast(1)
        val publicRange = publicPlan.resolvedRange
        val hasDynamicFpsKey = requestKeyNames.contains(DYNAMIC_FPS_CONFIG_KEY_NAME) ||
            sessionKeyNames.contains(DYNAMIC_FPS_CONFIG_KEY_NAME)
        val hasVideoFrameRateKey = requestKeyNames.contains(VENDOR_VIDEO_FRAME_RATE_KEY_NAME) ||
            sessionKeyNames.contains(VENDOR_VIDEO_FRAME_RATE_KEY_NAME)
        val vendorFpsOnlyHighResolution = requestedSize != null &&
            streamBasedEntries.isEmpty() &&
            hasVideoFrameRateKey &&
            requestedSize.area() >= HIGH_RESOLUTION_VENDOR_FPS_PIXEL_THRESHOLD &&
            requested >= 24
        val publicRangeLocksRequestedFps =
            publicRange != null &&
                publicRange.lower >= requested &&
                publicRange.upper >= requested
        if (
            publicAeRanges.isNotEmpty() &&
            publicPlan.appliedPreviewFps >= requested &&
            publicRangeLocksRequestedFps &&
            !vendorFpsOnlyHighResolution
        ) {
            return CameraStreamBasedFpsOverridePlan.disabled(
                appliedFps = publicPlan.appliedPreviewFps,
                publicAeRange = publicRange,
                reason = "public Camera2 AE range locks requested fps"
            )
        }
        if (requestedSize == null) {
            return CameraStreamBasedFpsOverridePlan.disabled(
                appliedFps = publicPlan.appliedPreviewFps,
                publicAeRange = publicRange,
                reason = "no encoder size available for stream based fps matching"
            )
        }
        if (vendorFpsOnlyHighResolution) {
            return vendorDynamicPlan(
                appliedFps = publicPlan.appliedPreviewFps.coerceAtMost(requested).coerceAtLeast(1),
                vendorFps = requested,
                publicRange = resolveVendorPublicAeRange(
                    requestedFps = requested,
                    publicAeRanges = publicAeRanges,
                    fallback = publicRange
                ),
                hasDynamicFpsKey = hasDynamicFpsKey,
                hasVideoFrameRateKey = hasVideoFrameRateKey,
                reason = "vendor dynamic fps enabled for high-resolution recording without stream table"
            )
        }

        val normalizedSize = requestedSize.normalized()
        val bestEntry = streamBasedEntries
            .filter { entry ->
                entry.supportsRequestedSize(normalizedSize) &&
                    entry.previewFps >= requested &&
                    entry.videoFps >= requested
            }
            .minWithOrNull(
                compareBy<CameraStreamBasedFpsEntry> {
                    if (it.normalizedSize() == normalizedSize) 0 else 1
                }
                    .thenBy { kotlin.math.abs(it.videoFps - requested) }
                    .thenBy { kotlin.math.abs(it.previewFps - requested) }
                    .thenBy { it.normalizedSize().width.toLong() * it.normalizedSize().height.toLong() }
                    .thenByDescending { it.previewFps }
            )
            ?: return CameraStreamBasedFpsOverridePlan.disabled(
                appliedFps = publicPlan.appliedPreviewFps,
                publicAeRange = publicRange,
                reason = "stream based fps table has no preview/recording parity entry for ${normalizedSize.width}x${normalizedSize.height}@$requested"
            )

        val hasPreviewVideoRequestKeys = requestKeyNames.contains(PREVIEW_FPS_KEY_NAME) &&
            requestKeyNames.contains(VIDEO_FPS_KEY_NAME)
        val hasPreviewVideoSessionKeys = sessionKeyNames.contains(PREVIEW_FPS_KEY_NAME) &&
            sessionKeyNames.contains(VIDEO_FPS_KEY_NAME)
        val hasPreviewVideoKeys = hasPreviewVideoRequestKeys || hasPreviewVideoSessionKeys
        if (!hasPreviewVideoKeys && !hasDynamicFpsKey && !hasVideoFrameRateKey) {
            val missingRequestKeys = listOf(PREVIEW_FPS_KEY_NAME, VIDEO_FPS_KEY_NAME, DYNAMIC_FPS_CONFIG_KEY_NAME)
                .filterNot { keyName -> requestKeyNames.contains(keyName) || sessionKeyNames.contains(keyName) }
            return CameraStreamBasedFpsOverridePlan.disabled(
                appliedFps = publicPlan.appliedPreviewFps,
                publicAeRange = publicRange,
                reason = "stream based fps request/session keys missing: ${missingRequestKeys.joinToString()}"
            )
        }

        val requestMode = if (hasPreviewVideoKeys) {
            CameraStreamBasedFpsRequestMode.STREAM_PREVIEW_VIDEO
        } else {
            CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS
        }
        val sessionReason = when (requestMode) {
            CameraStreamBasedFpsRequestMode.STREAM_PREVIEW_VIDEO ->
                if (
                    sessionKeyNames.contains(PREVIEW_FPS_KEY_NAME) &&
                    sessionKeyNames.contains(VIDEO_FPS_KEY_NAME)
                ) {
                    "request and session keys available"
                } else {
                    "request keys available; session keys not exposed"
                }
            CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS ->
                if (
                    sessionKeyNames.contains(DYNAMIC_FPS_CONFIG_KEY_NAME) ||
                    sessionKeyNames.contains(VENDOR_VIDEO_FRAME_RATE_KEY_NAME)
                ) {
                    "vendor dynamic fps request and session key available"
                } else {
                    "vendor dynamic fps request key available; session key not exposed"
                }
            CameraStreamBasedFpsRequestMode.NONE -> "no stream based fps request mode"
        }
        return CameraStreamBasedFpsOverridePlan(
            enabled = true,
            appliedFps = requested,
            previewFps = requested,
            videoFps = requested,
            publicAeRange = if (requestMode == CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS) {
                resolveVendorPublicAeRange(
                    requestedFps = requested,
                    publicAeRanges = publicAeRanges,
                    fallback = publicRange
                )
            } else {
                publicRange
            },
            requestMode = requestMode,
            dynamicFpsConfig = if (
                requestMode == CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS &&
                hasDynamicFpsKey
            ) {
                requested.toFloat()
            } else {
                null
            },
            vendorVideoFrameRate = if (
                requestMode == CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS &&
                hasVideoFrameRateKey
            ) {
                requested
            } else {
                null
            },
            reason = "stream based fps override enabled for ${normalizedSize.width}x${normalizedSize.height}@$requested; $sessionReason"
        )
    }

    private fun resolveVendorPublicAeRange(
        requestedFps: Int,
        publicAeRanges: List<CameraIntRange>,
        fallback: CameraIntRange?
    ): CameraIntRange? {
        val unitScale = if (publicAeRanges.any { it.upper >= 1_000 }) 1_000 else 1
        val scaledTarget = requestedFps.coerceAtLeast(1) * unitScale
        val exact = CameraIntRange(scaledTarget, scaledTarget)
        if (publicAeRanges.isEmpty()) return exact
        return if (publicAeRanges.any { it.contains(scaledTarget) }) {
            exact
        } else {
            fallback
        }
    }

    private fun vendorDynamicPlan(
        appliedFps: Int,
        vendorFps: Int = appliedFps,
        publicRange: CameraIntRange?,
        hasDynamicFpsKey: Boolean,
        hasVideoFrameRateKey: Boolean,
        reason: String
    ): CameraStreamBasedFpsOverridePlan =
        CameraStreamBasedFpsOverridePlan(
            enabled = true,
            appliedFps = appliedFps,
            previewFps = appliedFps,
            videoFps = vendorFps,
            publicAeRange = publicRange,
            requestMode = CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS,
            dynamicFpsConfig = if (hasDynamicFpsKey) vendorFps.toFloat() else null,
            vendorVideoFrameRate = if (hasVideoFrameRateKey) vendorFps else null,
            reason = reason
        )

    private fun sanitizeStreamOverrideRequestedFps(requestedFps: Int): Int =
        when (requestedFps.coerceAtLeast(1)) {
            30 -> 25
            else -> requestedFps.coerceAtLeast(1)
        }

    private fun CameraStreamSize.normalized(): CameraStreamSize =
        if (width >= height) this else CameraStreamSize(height, width)

    private fun CameraStreamSize.area(): Long =
        width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0).toLong()

    private fun CameraStreamBasedFpsEntry.normalizedSize(): CameraStreamSize =
        if (width >= height) {
            CameraStreamSize(width, height)
        } else {
            CameraStreamSize(height, width)
        }

    private fun CameraStreamBasedFpsEntry.supportsRequestedSize(requestedSize: CameraStreamSize): Boolean {
        val entrySize = normalizedSize()
        if (entrySize == requestedSize) return true
        if (entrySize.width < requestedSize.width || entrySize.height < requestedSize.height) return false
        val requestedAspect = requestedSize.width.toFloat() / requestedSize.height.coerceAtLeast(1).toFloat()
        val entryAspect = entrySize.width.toFloat() / entrySize.height.coerceAtLeast(1).toFloat()
        return kotlin.math.abs(entryAspect - requestedAspect) <= STREAM_ASPECT_TOLERANCE
    }
}
