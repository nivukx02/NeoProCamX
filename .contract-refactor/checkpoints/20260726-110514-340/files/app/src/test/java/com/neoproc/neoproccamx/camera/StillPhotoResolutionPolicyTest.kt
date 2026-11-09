package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class StillPhotoResolutionPolicyTest {
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
