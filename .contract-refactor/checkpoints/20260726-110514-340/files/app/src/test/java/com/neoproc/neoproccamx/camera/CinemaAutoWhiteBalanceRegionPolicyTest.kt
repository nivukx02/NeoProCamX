package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaAutoWhiteBalanceRegionPolicyTest {

    @Test
    fun `auto white balance tracks subject and broader color context`() {
        val regions = CinemaAutoWhiteBalanceRegionPolicy.resolve(
            subjectRegion = CameraRect(1520, 1140, 2480, 1860),
            activeArray = CameraRect(0, 0, 4000, 3000),
            maxRegions = 2
        )

        assertEquals(2, regions.size)
        assertEquals(CameraRect(1520, 1140, 2480, 1860), regions[0].rect)
        assertEquals(900, regions[0].weight)
        assertEquals(CameraRect(1168, 876, 2832, 2124), regions[1].rect)
        assertEquals(460, regions[1].weight)
    }

    @Test
    fun `auto white balance uses center weighted frame when no subject exists`() {
        val regions = CinemaAutoWhiteBalanceRegionPolicy.resolve(
            subjectRegion = null,
            activeArray = CameraRect(0, 0, 4000, 3000),
            maxRegions = 2
        )

        assertEquals(2, regions.size)
        assertEquals(CameraRect(800, 600, 3200, 2400), regions[0].rect)
        assertEquals(720, regions[0].weight)
        assertEquals(CameraRect(0, 0, 4000, 3000), regions[1].rect)
        assertEquals(220, regions[1].weight)
    }

    @Test
    fun `auto white balance regions honor hal max count and sensor bounds`() {
        val regions = CinemaAutoWhiteBalanceRegionPolicy.resolve(
            subjectRegion = CameraRect(0, 0, 300, 180),
            activeArray = CameraRect(0, 0, 4000, 3000),
            maxRegions = 1
        )

        assertEquals(1, regions.size)
        assertEquals(CameraRect(0, 0, 300, 180), regions[0].rect)
        assertTrue(regions[0].rect.left >= 0)
        assertTrue(regions[0].rect.top >= 0)
    }
}
