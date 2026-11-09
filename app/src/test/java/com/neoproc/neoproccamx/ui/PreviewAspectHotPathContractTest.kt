package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewAspectHotPathContractTest {
    private val viewModelSource = File(
        "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
    ).readText()
    private val previewViewSource = File(
        "src/main/java/com/neoproc/neoproccamx/rendering/CameraVulkanPreviewView.kt"
    ).readText()

    @Test
    fun previewAspectCommitHasOnePersistenceAndDomainPublicationPath() {
        val publicSetter = sourceSection(
            source = viewModelSource,
            start = "fun setPreviewAspectRatio(aspectRatio: PreviewAspectRatio)",
            end = "private fun applyPreviewAspectRatio("
        )
        val commit = sourceSection(
            source = viewModelSource,
            start = "private fun applyPreviewAspectRatio(",
            end = "fun setDeviceRotationDegrees("
        )

        assertFalse(publicSetter.contains("previewController.setPreviewAspectRatio"))
        assertEquals(1, Regex("applyPreviewAspectRatio\\(").findAll(publicSetter).count())
        assertEquals(0, Regex("pushVulkanPreviewRenderConfig\\(").findAll(commit).count())
        assertFalse(commit.contains("applyAutomaticVideoBitrateIfNeeded"))
        assertFalse(commit.contains("syncGeometryTargets()"))
        assertTrue(commit.contains("syncPreviewAspectPresentationTargets()"))
    }

    @Test
    fun geometrySyncCommitsPresentationAsOneTransformUpdate() {
        val sync = sourceSection(
            source = viewModelSource,
            start = "private fun syncGeometryTargets()",
            end = "private fun shouldFillPreviewViewport()"
        )

        assertTrue(sync.contains("setPresentationAspectRatios("))
        assertFalse(sync.contains("setDisplayContentAspectRatioOverride("))
        assertFalse(sync.contains("setAspectRatioOverride("))
    }

    @Test
    fun presentationOnlyAspectChangeNeverRequestsHierarchyLayout() {
        val atomicSetter = sourceSection(
            source = previewViewSource,
            start = "fun setPresentationAspectRatios(",
            end = "fun setEncoderContentAspectRatioOverride("
        )

        assertFalse(atomicSetter.contains("requestLayout()"))
        assertEquals(1, Regex("updateTransformState\\(\\)").findAll(atomicSetter).count())
    }

    @Test
    fun presentationOnlyAspectChangeDoesNotRebuildPipelineConfiguration() {
        val presentationSync = sourceSection(
            source = viewModelSource,
            start = "private fun syncPreviewAspectPresentationTargets()",
            end = "private fun shouldFillPreviewViewport()"
        )

        assertTrue(presentationSync.contains("setPresentationAspectRatios("))
        assertTrue(presentationSync.contains("setEncoderContentAspectRatioOverride("))
        assertFalse(presentationSync.contains("applyFrameGeometry("))
        assertFalse(presentationSync.contains("syncPreviewPerformanceMode("))
        assertFalse(presentationSync.contains("pushVulkanPreviewRenderConfig("))
    }

    private fun sourceSection(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex + start.length)
        assertTrue("Missing source section start: $start", startIndex >= 0)
        assertTrue("Missing source section end: $end", endIndex > startIndex)
        return source.substring(startIndex, endIndex)
    }
}
