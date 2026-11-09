package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillPhotoResolutionPolicyTest {
    @Test
    fun `fast photo mode never rebuilds a dedicated maximum-resolution session`() {
        val shouldRebuild = StillPhotoResolutionPolicy.shouldUseDedicatedMaximumResolutionSession(
            maximumResolutionEnabled = false,
            recordingActive = false,
            captureRaw = false,
            captureJpeg = true,
            maximumResolutionCaptureActive = false,
            platformSupportsMaximumResolution = true,
            normalMaximumArea = 12_000_000L,
            maximumResolutionArea = 50_000_000L
        )

        assertFalse(shouldRebuild)
    }

    @Test
    fun `maximum sensor detail rebuilds only for an eligible jpeg still`() {
        fun resolve(
            recording: Boolean = false,
            raw: Boolean = false,
            jpeg: Boolean = true,
            captureActive: Boolean = false,
            supported: Boolean = true,
            maximumArea: Long = 50_000_000L
        ) = StillPhotoResolutionPolicy.shouldUseDedicatedMaximumResolutionSession(
            maximumResolutionEnabled = true,
            recordingActive = recording,
            captureRaw = raw,
            captureJpeg = jpeg,
            maximumResolutionCaptureActive = captureActive,
            platformSupportsMaximumResolution = supported,
            normalMaximumArea = 12_000_000L,
            maximumResolutionArea = maximumArea
        )

        assertTrue(resolve())
        assertFalse(resolve(recording = true))
        assertFalse(resolve(raw = true))
        assertFalse(resolve(jpeg = false))
        assertFalse(resolve(captureActive = true))
        assertFalse(resolve(supported = false))
        assertFalse(resolve(maximumArea = 12_000_000L))
    }

    @Test
    fun `normal video graph never consumes maximum-resolution-only jpeg size`() {
        val plan = StillPhotoResolutionPolicy.resolve(
            standardMapSizes = listOf(
                CameraStreamSize(4096, 3072),
                CameraStreamSize(1920, 1440)
            ),
            maximumResolutionMapSizes = listOf(
                CameraStreamSize(8192, 6144),
                CameraStreamSize(4096, 3072)
            )
        )

        assertEquals(CameraStreamSize(4096, 3072), plan.normalSessionSizes.first())
        assertEquals(CameraStreamSize(8192, 6144), plan.maximumResolutionSessionSizes.first())
    }

    @Test
    fun `maximum pixel recording prefers largest size exposed by both maps`() {
        val plan = StillPhotoResolutionPolicy.resolve(
            standardMapSizes = listOf(
                CameraStreamSize(4096, 3072),
                CameraStreamSize(1920, 1440)
            ),
            maximumResolutionMapSizes = listOf(
                CameraStreamSize(8192, 6144),
                CameraStreamSize(4096, 3072),
                CameraStreamSize(1920, 1440)
            )
        )

        assertEquals(
            listOf(CameraStreamSize(4096, 3072), CameraStreamSize(1920, 1440)),
            plan.maximumPixelModeConcurrentSizes
        )
    }

    @Test
    fun `maximum-only camera retains a valid normal and maximum capture route`() {
        val plan = StillPhotoResolutionPolicy.resolve(
            standardMapSizes = emptyList(),
            maximumResolutionMapSizes = listOf(CameraStreamSize(8192, 6144))
        )

        assertEquals(CameraStreamSize(8192, 6144), plan.normalSessionSizes.single())
        assertEquals(CameraStreamSize(8192, 6144), plan.maximumResolutionSessionSizes.single())
        assertEquals(CameraStreamSize(8192, 6144), plan.maximumPixelModeConcurrentSizes.single())
    }
}
