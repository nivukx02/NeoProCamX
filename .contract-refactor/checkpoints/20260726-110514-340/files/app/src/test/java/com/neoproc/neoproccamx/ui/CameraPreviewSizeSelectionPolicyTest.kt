package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPreviewSizeSelectionPolicyTest {

    // android.util.Size is a default-return Android stub in local JVM tests, so
    // these checks pin the extracted policy contract at source level.
    private val source = File(
        "src/main/java/com/neoproc/neoproccamx/ui/CameraPreviewSizeSelectionPolicy.kt"
    ).readText()

    @Test
    fun chooseSizeForAspect_sourcePinsClosestAspectAndLargestAreaTieBreak() {
        val body = chooseSizeForAspectBody()

        assertTrue(body.contains("return candidates.minWithOrNull("))
        assertTrue(body.contains("compareBy<Size>"))
        assertTrue(
            body.contains(
                "abs((normalized.width.toFloat() / normalized.height.toFloat()) - aspect)"
            )
        )
        assertTrue(body.contains("}.thenByDescending {"))
        assertTrue(body.contains("normalized.width * normalized.height"))
    }

    @Test
    fun chooseSizeForAspect_sourcePinsNormalizedMaxAreaFilterAndFallback() {
        val body = chooseSizeForAspectBody()

        assertTrue(body.contains("val filtered = sizes.filter"))
        assertTrue(
            body.contains("val normalized = RecordingSelectionPolicy.normalizeForOrientation(it)")
        )
        assertTrue(body.contains("normalized.width * normalized.height <= maxArea"))
        assertTrue(body.contains("val candidates = if (filtered.isNotEmpty()) filtered else sizes"))
    }

    @Test
    fun chooseSizeForAspect_sourcePinsNullAspectLargestAreaFallback() {
        val body = chooseSizeForAspectBody()

        assertTrue(
            body.contains(
                "val aspect = targetAspect ?: return candidates.maxByOrNull { it.width * it.height }"
            )
        )
    }

    @Test
    fun distinctNormalizedSizes_sourcePinsNormalizedDedupeAndSort() {
        val body = distinctNormalizedSizesBody()

        assertTrue(body.contains("groups.forEach(::addAll)"))
        assertTrue(body.contains("}.distinctBy {"))
        assertTrue(
            body.contains(
                "val normalized = RecordingSelectionPolicy.normalizeForOrientation(it)"
            )
        )
        assertTrue(body.contains("\"${'$'}{normalized.width}x${'$'}{normalized.height}\""))
        assertTrue(body.contains("}.sortedByDescending {"))
        assertTrue(body.contains("normalized.width.toLong() * normalized.height.toLong()"))
    }

    @Test
    fun findNormalizedSizeMatch_sourcePinsNormalizedDimensionMatch() {
        val body = findNormalizedSizeMatchBody()

        assertTrue(body.contains("sizes.firstOrNull"))
        assertTrue(
            body.contains("val normalized = RecordingSelectionPolicy.normalizeForOrientation(it)")
        )
        assertTrue(
            body.contains(
                "normalized.width == target.width && normalized.height == target.height"
            )
        )
    }

    @Test
    fun cameraViewModel_keepsPrivateWrappersDelegatingToPolicy() {
        val viewModelSource = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()
        val wrapperRegion = viewModelSource
            .substringAfter("private fun chooseSizeForAspect(")
            .substringBefore("private fun shouldAttemptNativeVulkanPreview(")

        assertTrue(wrapperRegion.contains("CameraPreviewSizeSelectionPolicy.chooseSizeForAspect("))
        assertTrue(wrapperRegion.contains("CameraPreviewSizeSelectionPolicy.distinctNormalizedSizes(*groups)"))
        assertTrue(wrapperRegion.contains("CameraPreviewSizeSelectionPolicy.findNormalizedSizeMatch("))
    }

    private fun chooseSizeForAspectBody(): String =
        source.substringAfter("fun chooseSizeForAspect(")
            .substringBefore("fun distinctNormalizedSizes(")

    private fun distinctNormalizedSizesBody(): String =
        source.substringAfter("fun distinctNormalizedSizes(")
            .substringBefore("fun findNormalizedSizeMatch(")

    private fun findNormalizedSizeMatchBody(): String =
        source.substringAfter("fun findNormalizedSizeMatch(")
            .substringBefore("\n}")
}
