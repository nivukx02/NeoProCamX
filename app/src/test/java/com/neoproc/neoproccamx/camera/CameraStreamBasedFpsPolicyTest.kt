package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStreamBasedFpsPolicyTest {

    @Test
    fun `enables exposed stream based 4k60 when public ae range is capped at 30`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = CameraStreamSize(3840, 2160),
            requestedFps = 60,
            publicAeRanges = listOf(CameraIntRange(24, 30), CameraIntRange(30, 30)),
            streamBasedEntries = listOf(
                CameraStreamBasedFpsEntry(width = 3840, height = 2160, previewFps = 30, videoFps = 60),
                CameraStreamBasedFpsEntry(width = 3840, height = 2160, previewFps = 60, videoFps = 60)
            ),
            requestKeyNames = setOf(
                CameraStreamBasedFpsPolicy.PREVIEW_FPS_KEY_NAME,
                CameraStreamBasedFpsPolicy.VIDEO_FPS_KEY_NAME
            ),
            sessionKeyNames = setOf(
                CameraStreamBasedFpsPolicy.PREVIEW_FPS_KEY_NAME,
                CameraStreamBasedFpsPolicy.VIDEO_FPS_KEY_NAME
            )
        )

        assertTrue(plan.enabled)
        assertEquals(60, plan.appliedFps)
        assertEquals(60, plan.previewFps)
        assertEquals(60, plan.videoFps)
        assertEquals(CameraIntRange(30, 30), plan.publicAeRange)
    }

    @Test
    fun `does not enable stream based fps without exposed request keys`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = CameraStreamSize(3840, 2160),
            requestedFps = 60,
            publicAeRanges = listOf(CameraIntRange(30, 30)),
            streamBasedEntries = listOf(
                CameraStreamBasedFpsEntry(width = 3840, height = 2160, previewFps = 60, videoFps = 60)
            ),
            requestKeyNames = emptySet(),
            sessionKeyNames = emptySet()
        )

        assertFalse(plan.enabled)
        assertEquals(30, plan.appliedFps)
        assertTrue(plan.reason.contains("request/session keys", ignoreCase = true))
    }

    @Test
    fun `enables exposed vendor fps keys when stream preview video keys are missing`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = CameraStreamSize(3840, 2160),
            requestedFps = 60,
            publicAeRanges = listOf(CameraIntRange(30, 30)),
            streamBasedEntries = listOf(
                CameraStreamBasedFpsEntry(width = 3840, height = 2160, previewFps = 60, videoFps = 60)
            ),
            requestKeyNames = setOf(
                CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME,
                CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME
            ),
            sessionKeyNames = setOf(
                CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME,
                CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME
            )
        )

        assertTrue(plan.enabled)
        assertEquals(CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS, plan.requestMode)
        assertEquals(60, plan.appliedFps)
        assertEquals(60f, plan.dynamicFpsConfig)
        assertEquals(60, plan.vendorVideoFrameRate)
        assertEquals(CameraIntRange(30, 30), plan.publicAeRange)
    }

    @Test
    fun `enables stream based fps when public ae range can drop below stale 30 request sanitized to 25`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = CameraStreamSize(1920, 1080),
            requestedFps = 30,
            publicAeRanges = listOf(CameraIntRange(3, 30)),
            streamBasedEntries = listOf(
                CameraStreamBasedFpsEntry(width = 1920, height = 1080, previewFps = 30, videoFps = 30)
            ),
            requestKeyNames = setOf(
                CameraStreamBasedFpsPolicy.PREVIEW_FPS_KEY_NAME,
                CameraStreamBasedFpsPolicy.VIDEO_FPS_KEY_NAME
            ),
            sessionKeyNames = emptySet()
        )

        assertTrue(plan.enabled)
        assertEquals(30, plan.appliedFps)
        assertEquals(30, plan.previewFps)
        assertEquals(30, plan.videoFps)
        assertEquals(CameraIntRange(3, 30), plan.publicAeRange)
    }

    @Test
    fun `enables session-only dynamic fps key for stale 30 request sanitized to 25`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = CameraStreamSize(1920, 1080),
            requestedFps = 30,
            publicAeRanges = listOf(CameraIntRange(3, 30)),
            streamBasedEntries = listOf(
                CameraStreamBasedFpsEntry(width = 1920, height = 1080, previewFps = 30, videoFps = 30)
            ),
            requestKeyNames = emptySet(),
            sessionKeyNames = setOf(CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME)
        )

        assertTrue(plan.enabled)
        assertEquals(CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS, plan.requestMode)
        assertEquals(30, plan.previewFps)
        assertEquals(30, plan.videoFps)
        assertEquals(30f, plan.dynamicFpsConfig)
        assertEquals(CameraIntRange(30, 30), plan.publicAeRange)
    }

    @Test
    fun `vendor dynamic 4k25 clamps broad public ae range to exact selected fps`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = CameraStreamSize(3840, 2160),
            requestedFps = 25,
            publicAeRanges = listOf(CameraIntRange(15, 30)),
            streamBasedEntries = emptyList(),
            requestKeyNames = setOf(
                CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME,
                CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME
            ),
            sessionKeyNames = setOf(
                CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME,
                CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME
            )
        )

        assertTrue(plan.enabled)
        assertEquals(CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS, plan.requestMode)
        assertEquals(25, plan.appliedFps)
        assertEquals(25, plan.previewFps)
        assertEquals(25, plan.videoFps)
        assertEquals(25f, plan.dynamicFpsConfig)
        assertEquals(25, plan.vendorVideoFrameRate)
        assertEquals(CameraIntRange(25, 25), plan.publicAeRange)
    }

    @Test
    fun `vivo vendor key locks low resolution 25 fps when public ae range is variable`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = CameraStreamSize(1632, 1224),
            requestedFps = 25,
            publicAeRanges = listOf(CameraIntRange(15, 30)),
            streamBasedEntries = emptyList(),
            requestKeyNames = setOf(CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME),
            sessionKeyNames = setOf(CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME)
        )

        assertTrue(plan.enabled)
        assertEquals(CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS, plan.requestMode)
        assertEquals(25, plan.appliedFps)
        assertEquals(25, plan.vendorVideoFrameRate)
        assertEquals(CameraIntRange(25, 25), plan.publicAeRange)
        assertTrue(plan.reason.contains("lock broad public AE range", ignoreCase = true))
    }

    @Test
    fun `vivo vendor key keeps post-record preview fixed at 25 without encoder size`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = null,
            requestedFps = 25,
            publicAeRanges = listOf(CameraIntRange(15, 30)),
            streamBasedEntries = emptyList(),
            requestKeyNames = setOf(CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME),
            sessionKeyNames = setOf(CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME)
        )

        assertTrue(plan.enabled)
        assertEquals(CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS, plan.requestMode)
        assertEquals(25, plan.appliedFps)
        assertEquals(25, plan.vendorVideoFrameRate)
        assertEquals(CameraIntRange(25, 25), plan.publicAeRange)
    }

    @Test
    fun `enables vivo vendor fps keys for high resolution stale 30 request as 25 recording without stream table`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = CameraStreamSize(3264, 2448),
            requestedFps = 30,
            publicAeRanges = listOf(CameraIntRange(30, 30)),
            streamBasedEntries = emptyList(),
            requestKeyNames = setOf(
                CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME,
                CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME
            ),
            sessionKeyNames = setOf(
                CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME,
                CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME
            )
        )

        assertTrue(plan.enabled)
        assertEquals(CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS, plan.requestMode)
        assertEquals(30, plan.appliedFps)
        assertEquals(30f, plan.dynamicFpsConfig)
        assertEquals(30, plan.vendorVideoFrameRate)
        assertEquals(CameraIntRange(30, 30), plan.publicAeRange)
        assertTrue(plan.reason.contains("high-resolution", ignoreCase = true))
    }

    @Test
    fun `does not enable vendor fps fallback for low resolution stale 30 request without stream table`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = CameraStreamSize(1920, 1080),
            requestedFps = 30,
            publicAeRanges = listOf(CameraIntRange(30, 30)),
            streamBasedEntries = emptyList(),
            requestKeyNames = setOf(
                CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME,
                CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME
            ),
            sessionKeyNames = setOf(
                CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME,
                CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME
            )
        )

        assertFalse(plan.enabled)
        assertEquals(30, plan.appliedFps)
        assertTrue(plan.reason.contains("public", ignoreCase = true))
    }

    @Test
    fun `strict image reader cadence reinforces exact public range with exposed fps keys`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = CameraStreamSize(1920, 1080),
            requestedFps = 30,
            publicAeRanges = listOf(CameraIntRange(30, 30)),
            streamBasedEntries = emptyList(),
            requestKeyNames = setOf(
                CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME,
                CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME
            ),
            sessionKeyNames = setOf(
                CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME,
                CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME
            ),
            preferVendorCadenceLockWhenAvailable = true
        )

        assertTrue(plan.enabled)
        assertEquals(CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS, plan.requestMode)
        assertEquals(30, plan.appliedFps)
        assertEquals(30f, plan.dynamicFpsConfig)
        assertEquals(30, plan.vendorVideoFrameRate)
        assertEquals(CameraIntRange(30, 30), plan.publicAeRange)
        assertTrue(plan.reason.contains("strict image-reader cadence", ignoreCase = true))
    }

    @Test
    fun `high resolution vendor fps fallback clamps requested fps to public applied fps`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = CameraStreamSize(3264, 2448),
            requestedFps = 60,
            publicAeRanges = listOf(CameraIntRange(30, 30)),
            streamBasedEntries = emptyList(),
            requestKeyNames = setOf(CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME),
            sessionKeyNames = setOf(CameraStreamBasedFpsPolicy.VENDOR_VIDEO_FRAME_RATE_KEY_NAME)
        )

        assertTrue(plan.enabled)
        assertEquals(CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS, plan.requestMode)
        assertEquals(30, plan.appliedFps)
        assertEquals(60, plan.vendorVideoFrameRate)
    }

    @Test
    fun `enables compatible larger same aspect stream row for selected 4k60 output`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = CameraStreamSize(3840, 2160),
            requestedFps = 60,
            publicAeRanges = listOf(CameraIntRange(30, 30)),
            streamBasedEntries = listOf(
                CameraStreamBasedFpsEntry(width = 4096, height = 2304, previewFps = 60, videoFps = 60)
            ),
            requestKeyNames = setOf(CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME),
            sessionKeyNames = setOf(CameraStreamBasedFpsPolicy.DYNAMIC_FPS_CONFIG_KEY_NAME)
        )

        assertTrue(plan.enabled)
        assertEquals(CameraStreamBasedFpsRequestMode.VENDOR_DYNAMIC_FPS, plan.requestMode)
        assertEquals(60, plan.appliedFps)
        assertEquals(60f, plan.dynamicFpsConfig)
    }

    @Test
    fun `does not override when public ae already supports requested fps`() {
        val plan = CameraStreamBasedFpsPolicy.resolve(
            requestedSize = CameraStreamSize(3840, 2160),
            requestedFps = 60,
            publicAeRanges = listOf(CameraIntRange(60, 60)),
            streamBasedEntries = listOf(
                CameraStreamBasedFpsEntry(width = 3840, height = 2160, previewFps = 60, videoFps = 60)
            ),
            requestKeyNames = setOf(
                CameraStreamBasedFpsPolicy.PREVIEW_FPS_KEY_NAME,
                CameraStreamBasedFpsPolicy.VIDEO_FPS_KEY_NAME
            ),
            sessionKeyNames = emptySet()
        )

        assertFalse(plan.enabled)
        assertEquals(60, plan.appliedFps)
        assertTrue(plan.reason.contains("public", ignoreCase = true))
    }

    @Test
    fun `parses stream table rows and ignores incomplete rows`() {
        val entries = CameraStreamBasedFpsPolicy.parseStreamBasedTable(
            intArrayOf(
                3840, 2160, 60, 60,
                1920, 1080, 30
            )
        )

        assertEquals(listOf(CameraStreamBasedFpsEntry(3840, 2160, 60, 60)), entries)
    }
}
