package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PreviewLutOwnerRoutingContractTest {
    private val cameraSource =
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()

    @Test
    fun `software LUT fallback claims an SDR compositor before native preference`() {
        val ownerResolution = cameraSource
            .substringAfter("private fun desiredPreviewPipelineOwner(preferNativePreview: Boolean)")
            .substringBefore("private fun currentPreviewPipelineOwner()")
        val softwareFallback = ownerResolution.indexOf(
            "if (softwarePreviewLutOwnerRequired && !shouldCompositeHdr10PqPreview())"
        )
        val nativePreference = ownerResolution.indexOf(
            "if (preferNativePreview) return PreviewPipelineOwner.NATIVE"
        )

        assertTrue(softwareFallback >= 0)
        assertTrue(nativePreference > softwareFallback)
        assertTrue(ownerResolution.contains("PreviewPipelineOwner.PERSISTENT_SDR"))
        assertTrue(ownerResolution.contains("PreviewPipelineOwner.GL_SDR"))
    }

    @Test
    fun `HDR ownership cannot be replaced by software LUT fallback`() {
        val persistentOwnerResolution = cameraSource
            .substringAfter("private fun desiredPersistentPreviewOwner()")
            .substringBefore("private fun desiredPreviewPipelineOwner(")
        val ownerResolution = cameraSource
            .substringAfter("private fun desiredPreviewPipelineOwner(preferNativePreview: Boolean)")
            .substringBefore("private fun currentPreviewPipelineOwner()")

        assertTrue(
            persistentOwnerResolution.contains(
                "if (shouldCompositeHdr10PqPreview()) PersistentPreviewOwner.HDR " +
                    "else PersistentPreviewOwner.SDR"
            )
        )
        assertTrue(
            ownerResolution.contains(
                "softwarePreviewLutOwnerRequired && !shouldCompositeHdr10PqPreview()"
            )
        )
        assertTrue(ownerResolution.contains("shouldCompositeHdr10PqPreview() -> PreviewPipelineOwner.GL_HDR"))
    }
}
