package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VulkanEncoderSurfaceSafetyPolicyTest {

    @Test
    fun `vivo i2405 blocks codec owned vulkan encoder surface to avoid graphics pipeline anr`() {
        val reason = VulkanEncoderSurfaceSafetyPolicy.blockingReason(
            manufacturer = "vivo",
            model = "I2405",
            device = "I2405",
            product = "I2405i"
        )

        assertEquals(
            "device-vivo-i2405-vulkan-encoder-surface-pipeline-anr",
            reason
        )
    }

    @Test
    fun `other devices remain eligible for vulkan encoder surface qualification`() {
        val reason = VulkanEncoderSurfaceSafetyPolicy.blockingReason(
            manufacturer = "Google",
            model = "Pixel 9 Pro",
            device = "komodo",
            product = "komodo"
        )

        assertNull(reason)
    }
}
