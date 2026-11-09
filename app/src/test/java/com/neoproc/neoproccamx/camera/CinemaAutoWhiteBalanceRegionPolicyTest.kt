package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaAutoWhiteBalanceRegionPolicyTest {

    @Test
    fun `focused subject cannot override full-frame white balance`() {
        val regions = CinemaAutoWhiteBalanceRegionPolicy.resolve(
            subjectRegion = CameraRect(1520, 1140, 2480, 1860),
            activeArray = CameraRect(0, 0, 4000, 3000),
            maxRegions = 2
        )

        assertEquals(1, regions.size)
        assertEquals(CameraRect(0, 0, 4000, 3000), regions.single().rect)
        assertEquals(1_000, regions.single().weight)
    }

    @Test
    fun `auto white balance meters the complete frame when no subject exists`() {
        val regions = CinemaAutoWhiteBalanceRegionPolicy.resolve(
            subjectRegion = null,
            activeArray = CameraRect(0, 0, 4000, 3000),
            maxRegions = 2
        )

        assertEquals(1, regions.size)
        assertEquals(CameraRect(0, 0, 4000, 3000), regions.single().rect)
        assertEquals(1_000, regions.single().weight)
    }

    @Test
    fun `auto white balance regions honor hal max count and sensor bounds`() {
        val regions = CinemaAutoWhiteBalanceRegionPolicy.resolve(
            subjectRegion = CameraRect(0, 0, 300, 180),
            activeArray = CameraRect(0, 0, 4000, 3000),
            maxRegions = 1
        )

        assertEquals(1, regions.size)
        assertEquals(CameraRect(0, 0, 4000, 3000), regions[0].rect)
        assertTrue(regions[0].rect.left >= 0)
        assertTrue(regions[0].rect.top >= 0)
    }
}
