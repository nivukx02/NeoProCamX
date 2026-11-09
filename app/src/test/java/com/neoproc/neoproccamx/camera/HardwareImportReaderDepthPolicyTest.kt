package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareImportReaderDepthPolicyTest {
    @Test
    fun fullSensorP010ReaderProvidesLargeMemoryFiftyFiveFrameThermalStallVaultV4() {
        val plan = HardwareImportReaderDepthPolicy.resolve(
            size = CameraStreamSize(4096, 3072),
            p010 = true,
            strictHighEnd = true,
            deviceTotalMemoryBytes = 12L * 1024L * 1024L * 1024L
        )

        assertEquals(55, plan.maxImages)
        assertEquals(36L * 1024L * 1024L, plan.estimatedFrameBytes)
        assertEquals(1_980L * 1024L * 1024L, plan.estimatedReaderBytes)
        assertTrue(plan.estimatedReaderBytes <= HardwareImportReaderDepthPolicy.MAX_READER_BYTES)
    }

    @Test
    fun fullSensorP010ReaderRetainsV3BoundOnLowerMemoryDevice() {
        val plan = HardwareImportReaderDepthPolicy.resolve(
            size = CameraStreamSize(4096, 3072),
            p010 = true,
            strictHighEnd = true,
            deviceTotalMemoryBytes = 8L * 1024L * 1024L * 1024L
        )

        assertEquals(24, plan.maxImages)
        assertEquals(864L * 1024L * 1024L, plan.estimatedReaderBytes)
        assertTrue(
            plan.estimatedReaderBytes <= HardwareImportReaderDepthPolicy.BASELINE_MAX_READER_BYTES
        )
    }

    @Test
    fun fullSensorP010NonStrictReaderRemainsCappedByNonStrictCeiling() {
        val plan = HardwareImportReaderDepthPolicy.resolve(
            size = CameraStreamSize(4096, 3072),
            p010 = true,
            strictHighEnd = false
        )

        assertEquals(CameraControllerConfig.ReaderDepth.MAX_HARDWARE_IMPORT, plan.maxImages)
        assertTrue(plan.estimatedReaderBytes <= HardwareImportReaderDepthPolicy.MAX_READER_BYTES)
    }

    @Test
    fun smallerPrivateReaderUsesBoundedStrictCeiling() {
        val plan = HardwareImportReaderDepthPolicy.resolve(
            size = CameraStreamSize(1920, 1080),
            p010 = false,
            strictHighEnd = true
        )

        assertEquals(CameraControllerConfig.ReaderDepth.STRICT_MAX_HARDWARE_IMPORT, plan.maxImages)
    }
}
