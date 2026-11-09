package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Npv10RawNativeAndDemosaicContractTest {

    @Test
    fun nativeRawModuleOwnsNeonBitPackingAndHardwareBufferVulkanSyncContracts() {
        val native = File("src/main/cpp/npv10_raw_native.cpp").readText()
        val cmake = File("src/main/cpp/CMakeLists.txt").readText()

        assertTrue(cmake.contains("npv10_raw_native.cpp"))
        assertTrue(native.contains("Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePackP010Neon"))
        assertTrue(native.contains("nativePackRaw12Npv10"))
        assertTrue(native.contains("nativeUnpackRaw12Npv10"))
        assertTrue(native.contains("nativePackRaw14Npv10"))
        assertTrue(native.contains("nativeUnpackRaw14Npv10"))
        assertTrue(native.contains("#include <arm_neon.h>"))
        assertTrue(native.contains("vld1q_u16"))
        assertTrue(native.contains("vst1q_u16"))
        assertTrue(native.contains("AHardwareBuffer_fromHardwareBuffer"))
        assertTrue(native.contains("VK_ANDROID_external_memory_android_hardware_buffer"))
        assertTrue(native.contains("VkSemaphoreTypeCreateInfo"))
        assertTrue(native.contains("VK_SEMAPHORE_TYPE_TIMELINE"))
        assertTrue(native.contains("vkGetSemaphoreFdKHR"))
        assertFalse(native.contains("GetPrimitiveArrayCritical"))
    }

    @Test
    fun demosaicShaderUsesSharedTileEdgeDirectedMathDarkCalibrationAndAcescgMatrix() {
        val shader = File("src/main/cpp/shaders/vulkan/demosaic_vulkan.comp").readText()

        assertTrue(shader.contains("layout(local_size_x = 16, local_size_y = 16"))
        assertTrue(shader.contains("shared uint rawTile"))
        assertTrue(shader.contains("EDGE_DIRECTED_5X5"))
        assertTrue(shader.contains("horizontalGradient"))
        assertTrue(shader.contains("verticalGradient"))
        assertTrue(shader.contains("DarkCurrent"))
        assertTrue(shader.contains("Q10"))
        assertTrue(shader.contains("hotPixelMask"))
        assertTrue(shader.contains("M_SENSOR_TO_XYZ"))
        assertTrue(shader.contains("M_XYZ_TO_ACESCG"))
        assertTrue(shader.contains("imageStore"))
    }
}
