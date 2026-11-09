package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingPreviewContinuityPolicyTest {

    @Test
    fun `processed video defaults to hybrid P010 when native P010 is supported`() {
        assertEquals(
            RecordingFeedPath.BYTEBUFFER_P010_ENCODER,
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false,
                vulkanRuntimeSupported = true,
                byteBufferP010EncoderSupported = true,
                preferByteBufferP010Encoder = true
            )
        )
    }

    @Test
    fun `processed video uses internal hardware import only when P010 is unavailable`() {
        assertEquals(
            RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false,
                vulkanRuntimeSupported = true,
                preferInternalHardwareImport = true,
                internalHardwareImportStable = false
            )
        )
    }

    @Test
    fun `processed video uses verified stable internal hardware import without preference`() {
        assertEquals(
            RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false,
                vulkanRuntimeSupported = true,
                preferInternalHardwareImport = false,
                internalHardwareImportStable = true
            )
        )
    }

    @Test
    fun `raw video keeps direct Camera2 record surface path`() {
        assertEquals(
            RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE,
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = true,
                vulkanRuntimeSupported = true
            )
        )
    }

    @Test
    fun `proven-capable offscreen p010 precedes cpu bytebuffer p010 on processed modes`() {
        assertEquals(
            RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER,
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false,
                vulkanRuntimeSupported = true,
                offscreenAhbEglEncoderSupported = true,
                byteBufferP010EncoderSupported = true,
                preferByteBufferP010Encoder = true
            )
        )
    }

    @Test
    fun `preferred P010 remains fallback when offscreen candidate is unavailable`() {
        assertEquals(
            RecordingFeedPath.BYTEBUFFER_P010_ENCODER,
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false,
                vulkanRuntimeSupported = false,
                offscreenAhbEglEncoderSupported = false,
                byteBufferP010EncoderSupported = true,
                preferByteBufferP010Encoder = true
            )
        )
    }

    @Test
    fun `offscreen proven-unavailable on a vulkan device routes straight to proven P010 not offscreen`() {
        // Regression for the recording-start retry storm: on a device whose WSI path is blocked
        // (so P010 is preferred) and whose offscreen AHB-EGL encoder has proven unbindable at
        // runtime, the selector must skip OFFSCREEN and INTERNAL_HARDWARE_IMPORT and use the proven
        // legacy P010 path directly, even though Vulkan itself is supported.
        assertEquals(
            RecordingFeedPath.BYTEBUFFER_P010_ENCODER,
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false,
                vulkanRuntimeSupported = true,
                offscreenAhbEglEncoderSupported = false,
                byteBufferP010EncoderSupported = true,
                preferByteBufferP010Encoder = true
            )
        )
    }

    @Test
    fun `processed video still preserves preview when Vulkan is unsupported`() {
        assertEquals(
            RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE,
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false,
                vulkanRuntimeSupported = false
            )
        )
    }

    @Test
    fun `processed video uses offscreen ahb egl encoder when vulkan wsi path is blocked`() {
        assertEquals(
            RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER,
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false,
                vulkanRuntimeSupported = false,
                offscreenAhbEglEncoderSupported = true
            )
        )
    }

    // --- Unified-pipeline invariant locks -------------------------------------------------
    // The four processed modes (Video/Pro/NDR/Log) share this single selector; RAW is the one
    // structural exception. These lock the unified contract documented on resolveFeedPath.

    @Test
    fun `reserved import-preference params never alter routing`() {
        // preferInternalHardwareImport / internalHardwareImportStable are reserved no-ops:
        // toggling them must not change the selected feed path for any processed config.
        val processedConfigs = listOf(
            Triple(true, false, false),   // vulkan-only  -> INTERNAL_HARDWARE_IMPORT
            Triple(false, true, false),   // offscreen    -> OFFSCREEN_AHB_EGL
            Triple(false, false, true),   // p010-only    -> BYTEBUFFER_P010
            Triple(false, false, false)   // none         -> CAMERA2_PREVIEW_PARITY
        )
        for ((vulkan, offscreen, p010) in processedConfigs) {
            val without = RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false,
                vulkanRuntimeSupported = vulkan,
                offscreenAhbEglEncoderSupported = offscreen,
                byteBufferP010EncoderSupported = p010,
                preferByteBufferP010Encoder = p010,
                preferInternalHardwareImport = false,
                internalHardwareImportStable = false
            )
            val with = RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false,
                vulkanRuntimeSupported = vulkan,
                offscreenAhbEglEncoderSupported = offscreen,
                byteBufferP010EncoderSupported = p010,
                preferByteBufferP010Encoder = p010,
                preferInternalHardwareImport = true,
                internalHardwareImportStable = true
            )
            assertEquals(without, with)
        }
    }

    @Test
    fun `raw video always diverges to Camera2 direct regardless of gpu or p010 capability`() {
        val bools = listOf(false, true)
        for (vulkan in bools) for (offscreen in bools) for (p010 in bools) for (prefer in bools) {
            assertEquals(
                RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE,
                RecordingPreviewContinuityPolicy.resolveFeedPath(
                    rawVideoEnabled = true,
                    vulkanRuntimeSupported = vulkan,
                    offscreenAhbEglEncoderSupported = offscreen,
                    byteBufferP010EncoderSupported = p010,
                    preferByteBufferP010Encoder = prefer
                )
            )
        }
    }

    @Test
    fun `unified selector covers every RecordingFeedPath - completeness tripwire`() {
        // The documented config matrix must reach every feed path. If a new arm is added to the
        // unified selector, this trips until the matrix (and the KDoc contract) is extended.
        val reached = setOf(
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = true, vulkanRuntimeSupported = true
            ),
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false, vulkanRuntimeSupported = true,
                offscreenAhbEglEncoderSupported = true
            ),
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false, vulkanRuntimeSupported = false,
                offscreenAhbEglEncoderSupported = false,
                byteBufferP010EncoderSupported = true, preferByteBufferP010Encoder = true
            ),
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false, vulkanRuntimeSupported = true
            ),
            RecordingPreviewContinuityPolicy.resolveFeedPath(
                rawVideoEnabled = false, vulkanRuntimeSupported = false
            )
        )
        assertEquals(RecordingFeedPath.values().toSet(), reached)
    }
}
