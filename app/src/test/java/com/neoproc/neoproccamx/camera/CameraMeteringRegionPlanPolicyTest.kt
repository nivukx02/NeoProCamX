package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMeteringRegionPlanPolicyTest {

    @Test
    fun `focus metering uses primary guard and context zones when supported`() {
        val regions = CameraMeteringRegionPlanPolicy.resolveFocusRegions(
            primaryRegion = CameraRect(1840, 1380, 2160, 1620),
            activeArray = CameraRect(0, 0, 4000, 3000),
            maxRegions = 3
        )

        assertEquals(3, regions.size)
        assertEquals(CameraRect(1840, 1380, 2160, 1620), regions[0].rect)
        assertEquals(1000, regions[0].weight)
        assertEquals(CameraRect(1600, 1200, 2400, 1800), regions[1].rect)
        assertEquals(560, regions[1].weight)
        assertEquals(CameraRect(1360, 1020, 2640, 1980), regions[2].rect)
        assertEquals(260, regions[2].weight)
    }

    @Test
    fun `exposure metering adds subject context without exceeding hal region count`() {
        val regions = CameraMeteringRegionPlanPolicy.resolveExposureRegions(
            primaryRegion = CameraRect(1520, 1140, 2480, 1860),
            activeArray = CameraRect(0, 0, 4000, 3000),
            maxRegions = 2
        )

        assertEquals(2, regions.size)
        assertEquals(CameraRect(1520, 1140, 2480, 1860), regions[0].rect)
        assertEquals(880, regions[0].weight)
        assertEquals(CameraRect(1280, 960, 2720, 2040), regions[1].rect)
        assertEquals(520, regions[1].weight)
    }

    @Test
    fun `expanded regions are clamped inside active sensor array`() {
        val regions = CameraMeteringRegionPlanPolicy.resolveFocusRegions(
            primaryRegion = CameraRect(0, 0, 320, 240),
            activeArray = CameraRect(0, 0, 4000, 3000),
            maxRegions = 3
        )

        assertEquals(3, regions.size)
        assertTrue(regions.all { it.rect.left >= 0 && it.rect.top >= 0 })
        assertTrue(regions.all { it.rect.right <= 4000 && it.rect.bottom <= 3000 })
        assertTrue(regions.all { it.rect.width > 0 && it.rect.height > 0 })
    }

    @Test
    fun `single region hal keeps only the primary metering region`() {
        val regions = CameraMeteringRegionPlanPolicy.resolveFocusRegions(
            primaryRegion = CameraRect(1840, 1380, 2160, 1620),
            activeArray = CameraRect(0, 0, 4000, 3000),
            maxRegions = 1
        )

        assertEquals(listOf(CameraWeightedMeteringRegion(CameraRect(1840, 1380, 2160, 1620), 1000)), regions)
    }
}
