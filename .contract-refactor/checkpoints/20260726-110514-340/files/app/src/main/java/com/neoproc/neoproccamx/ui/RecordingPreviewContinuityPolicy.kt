package com.neoproc.neoproccamx.ui

internal enum class RecordingFeedPath {
    INTERNAL_HARDWARE_IMPORT_ENCODER,
    OFFSCREEN_AHB_EGL_ENCODER,
    BYTEBUFFER_P010_ENCODER,
    CAMERA2_PREVIEW_PARITY_RECORD_SURFACE,
    CAMERA2_DIRECT_RECORD_SURFACE
}

internal object RecordingPreviewContinuityPolicy {
    /**
     * Unified processed-mode recording feed-path selector.
     *
     * All compressed video modes (Video, Pro Video, NDR, Log) share this single selection
     * surface. The default compressed recording route is the hybrid P010 path: prefer the
     * GPU offscreen P010 encoder feed when available, otherwise use CPU byte-buffer P010.
     * RAW video is the structural exception because P010 is 10-bit YUV, not sensor RAW.
     *
     * Priority ladder (first match wins):
     *  1. [rawVideoEnabled]                                   -> CAMERA2_DIRECT_RECORD_SURFACE (RAW exception)
     *  2. [offscreenAhbEglEncoderSupported]                   -> OFFSCREEN_AHB_EGL_ENCODER (GPU P010 default)
     *  3. [preferByteBufferP010Encoder] && [byteBufferP010EncoderSupported]
     *                                                         -> BYTEBUFFER_P010_ENCODER (CPU P010 strict route)
     *  4. [vulkanRuntimeSupported]                            -> INTERNAL_HARDWARE_IMPORT_ENCODER
     *  5. otherwise                                           -> CAMERA2_PREVIEW_PARITY_RECORD_SURFACE
     *
     * The strict no-fallback start gate lives in [ProcessedGpuRecordingRequirementPolicy]. This
     * selector still returns legacy arms for tests/callers that do not request strict hybrid P010.
     *
     * [preferInternalHardwareImport] and [internalHardwareImportStable] are RESERVED: they are
     * intentionally not read here (the Vulkan arm is already the ranked default when supported).
     * They are retained for call-site/telemetry compatibility and must not be removed without
     * updating those call sites.
     */
    fun resolveFeedPath(
        rawVideoEnabled: Boolean,
        vulkanRuntimeSupported: Boolean,
        offscreenAhbEglEncoderSupported: Boolean = false,
        byteBufferP010EncoderSupported: Boolean = false,
        preferByteBufferP010Encoder: Boolean = false,
        preferInternalHardwareImport: Boolean = false,
        internalHardwareImportStable: Boolean = false
    ): RecordingFeedPath =
        when {
            rawVideoEnabled -> RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE
            offscreenAhbEglEncoderSupported ->
                RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER
            preferByteBufferP010Encoder && byteBufferP010EncoderSupported ->
                RecordingFeedPath.BYTEBUFFER_P010_ENCODER
            vulkanRuntimeSupported -> RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER
            else -> RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE
        }
}
