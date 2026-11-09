package com.neoproc.neoproccamx.ui

internal object RecordingRealtimeRoutePolicy {
    private const val HIGH_RESOLUTION_P010_PIXEL_THRESHOLD = 7_000_000L
    private const val REALTIME_FPS_FLOOR = 24
    // Raised 1024 -> 1920: the copy-bound bottleneck this once guarded lives on the P010 record
    // readout + YUV analysis ImageReaders, NOT on the composited direct-camera preview SurfaceView,
    // so preview can track a full 1080p stream (the guaranteed Camera2 PREVIEW size bucket) without
    // touching record fps. 1024 was upscaled by SurfaceFlinger to the panel -> the soft preview.
    private const val REALTIME_PREVIEW_PARITY_PREVIEW_MAX_LONG_EDGE = 1920
    private const val REALTIME_PREVIEW_PARITY_ANALYSIS_MAX_LONG_EDGE = 800

    fun shouldBypassByteBufferP010ForRealtime(
        manufacturer: String?,
        model: String?,
        device: String?,
        product: String?,
        width: Int,
        height: Int,
        requestedFps: Int,
        deliveryKind: VideoDeliveryKind,
        effectiveHdrMode: String
    ): Boolean {
        if (deliveryKind == VideoDeliveryKind.RAW) return false
        if (!effectiveHdrMode.equals("OFF", ignoreCase = true)) return false
        if (requestedFps < REALTIME_FPS_FLOOR) return false

        val pixelCount = width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0).toLong()
        if (pixelCount < HIGH_RESOLUTION_P010_PIXEL_THRESHOLD) return false

        val normalizedManufacturer = manufacturer.normalizeDeviceToken()
        val deviceTokens = listOf(model, device, product).map { it.normalizeDeviceToken() }
        val vivoDevice = normalizedManufacturer.contains("vivo")
        val i2405Family = deviceTokens.any { it == "i2405" || it == "i2405i" }
        return vivoDevice && i2405Family
    }

    fun resolveRecordingAnalysisMaxLongEdgeForRealtime(
        manufacturer: String?,
        model: String?,
        device: String?,
        product: String?,
        width: Int,
        height: Int,
        requestedFps: Int,
        deliveryKind: VideoDeliveryKind,
        effectiveHdrMode: String,
        defaultMaxLongEdge: Int
    ): Int {
        if (!shouldBypassByteBufferP010ForRealtime(
                manufacturer,
                model,
                device,
                product,
                width,
                height,
                requestedFps,
                deliveryKind,
                effectiveHdrMode
            )
        ) {
            return defaultMaxLongEdge
        }
        return minOf(defaultMaxLongEdge, REALTIME_PREVIEW_PARITY_ANALYSIS_MAX_LONG_EDGE)
    }

    fun resolvePreviewMaxLongEdgeForRealtime(
        manufacturer: String?,
        model: String?,
        device: String?,
        product: String?,
        width: Int,
        height: Int,
        requestedFps: Int,
        deliveryKind: VideoDeliveryKind,
        effectiveHdrMode: String,
        defaultMaxLongEdge: Int
    ): Int {
        if (!shouldBypassByteBufferP010ForRealtime(
                manufacturer,
                model,
                device,
                product,
                width,
                height,
                requestedFps,
                deliveryKind,
                effectiveHdrMode
            )
        ) {
            return defaultMaxLongEdge
        }
        return minOf(defaultMaxLongEdge, REALTIME_PREVIEW_PARITY_PREVIEW_MAX_LONG_EDGE)
    }

    private fun String?.normalizeDeviceToken(): String =
        orEmpty().trim().lowercase()
}
