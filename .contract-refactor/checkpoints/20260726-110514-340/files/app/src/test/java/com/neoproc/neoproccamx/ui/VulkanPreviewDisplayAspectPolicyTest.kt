package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VulkanPreviewDisplayAspectPolicyTest {

    @Test
    fun resolve_usesPhysicalViewAspectForNonFourThreeResolvedPreviewAspect() {
        val aspect = VulkanPreviewDisplayAspectPolicy.resolve(
            viewWidth = 1080,
            viewHeight = 2400,
            resolvedPreviewAspect = 9f / 16f
        )

        assertEquals(1080f / 2400f, aspect, 0.0001f)
    }

    @Test
    fun resolve_usesResolvedFourThreeAspectToPreventShaderCrop() {
        val aspect = VulkanPreviewDisplayAspectPolicy.resolve(
            viewWidth = 1080,
            viewHeight = 2400,
            resolvedPreviewAspect = 3f / 4f
        )

        assertEquals(3f / 4f, aspect, 0.0001f)
    }

    @Test
    fun resolve_fallsBackToViewAspectWhenResolvedAspectUnavailable() {
        val aspect = VulkanPreviewDisplayAspectPolicy.resolve(
            viewWidth = 1080,
            viewHeight = 2400,
            resolvedPreviewAspect = null
        )

        assertEquals(1080f / 2400f, aspect, 0.0001f)
    }
}
