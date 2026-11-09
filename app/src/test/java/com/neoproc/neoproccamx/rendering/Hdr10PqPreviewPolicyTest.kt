package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Hdr10PqPreviewPolicyTest {

    @Test
    fun defaultsOn_forCorrectedNdrMonitorPreview() {
        assertTrue(Hdr10PqPreviewPolicy.enabled)
    }

    @Test
    fun shouldComposite_onlyWhenGateOnPqActiveAndNoOtherSurfaceOwner() {
        assertTrue(
            "engage when gate on + PQ active + no other owner",
            Hdr10PqPreviewPolicy.shouldComposite(
                gateEnabled = true,
                ndrPqDeliveryActive = true,
                nativeVulkanPreviewActive = false,
                glSdrCompositorActive = false
            )
        )
        assertFalse(
            "gate off never composites",
            Hdr10PqPreviewPolicy.shouldComposite(false, ndrPqDeliveryActive = true, nativeVulkanPreviewActive = false, glSdrCompositorActive = false)
        )
        assertFalse(
            "non-PQ mode never composites",
            Hdr10PqPreviewPolicy.shouldComposite(true, ndrPqDeliveryActive = false, nativeVulkanPreviewActive = false, glSdrCompositorActive = false)
        )
        assertFalse(
            "native Vulkan preview owns the surface -> bypass",
            Hdr10PqPreviewPolicy.shouldComposite(true, ndrPqDeliveryActive = true, nativeVulkanPreviewActive = true, glSdrCompositorActive = false)
        )
        assertFalse(
            "8-bit SDR compositor owns the surface -> bypass",
            Hdr10PqPreviewPolicy.shouldComposite(true, ndrPqDeliveryActive = true, nativeVulkanPreviewActive = false, glSdrCompositorActive = true)
        )
    }

    @Test
    fun disableRequestIsRejectedWithoutRebindingToDirectHlg() {
        var notifications = 0
        Hdr10PqPreviewPolicy.registerOnChanged { notifications++ }
        try {
            Hdr10PqPreviewPolicy.set(false)
            assertTrue(Hdr10PqPreviewPolicy.enabled)
            assertEquals(0, notifications)

            Hdr10PqPreviewPolicy.set(true)
            assertTrue(Hdr10PqPreviewPolicy.enabled)
            assertEquals(0, notifications)
        } finally {
            Hdr10PqPreviewPolicy.registerOnChanged(null)
            Hdr10PqPreviewPolicy.set(true)
        }
    }
}
