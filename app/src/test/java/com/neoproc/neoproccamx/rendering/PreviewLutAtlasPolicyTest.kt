package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewLutAtlasPolicyTest {

    @Test
    fun `65 cube uses compact atlas below common texture limits`() {
        val layout = requireNotNull(PreviewLutAtlasPolicy.resolveLayout(65, 4096))

        assertEquals(9, layout.columns)
        assertEquals(8, layout.rows)
        assertEquals(585, layout.width)
        assertEquals(520, layout.height)
        assertTrue(layout.width <= 4096)
        assertTrue(layout.height <= 4096)
    }

    @Test
    fun `layout rejects devices that cannot hold all slices`() {
        assertNull(PreviewLutAtlasPolicy.resolveLayout(65, 512))
        assertNull(PreviewLutAtlasPolicy.resolveLayout(65, 64))
    }

    @Test
    fun `strict precision never falls back to eight bit`() {
        assertEquals(
            PreviewLutTexturePrecision.RGBA16F,
            PreviewLutAtlasPolicy.resolvePrecision(
                halfFloatTextureSupported = true,
                strictHighEnd = true
            )
        )
        assertNull(
            PreviewLutAtlasPolicy.resolvePrecision(
                halfFloatTextureSupported = false,
                strictHighEnd = true
            )
        )
        assertEquals(
            PreviewLutTexturePrecision.RGBA8_EXPLICIT_NON_STRICT,
            PreviewLutAtlasPolicy.resolvePrecision(
                halfFloatTextureSupported = false,
                strictHighEnd = false
            )
        )
    }

    @Test
    fun `source indexing retains r fastest cube order`() {
        assertEquals(162, PreviewLutAtlasPolicy.sourceFloatOffset(2, 1, 3, 4))
        assertEquals(0, PreviewLutAtlasPolicy.sourceFloatOffset(0, 0, 0, 65))
        assertEquals(
            (64 + 64 * 65 + 64 * 65 * 65) * 3,
            PreviewLutAtlasPolicy.sourceFloatOffset(64, 64, 64, 65)
        )
    }

    @Test
    fun `half conversion preserves canonical LUT nodes`() {
        assertEquals(0x0000, PreviewLutAtlasPolicy.floatToHalfBits(0f).toInt() and 0xffff)
        assertEquals(0x3800, PreviewLutAtlasPolicy.floatToHalfBits(0.5f).toInt() and 0xffff)
        assertEquals(0x3c00, PreviewLutAtlasPolicy.floatToHalfBits(1f).toInt() and 0xffff)
    }

    @Test
    fun `runtime admission owns data and upload swaps transactionally`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt"
        ).readText()
        val admission = source
            .substringAfter("fun setCreativeLut(data: FloatArray?, size: Int)")
            .substringBefore("fun creativeLutUploadEvidence()")
        val upload = source
            .substringAfter("private fun uploadCreativeLutIfDirty()")
            .substringBefore("private fun buildCreativeLutAtlasBuffer(")

        assertTrue(admission.contains("data.copyOf(requiredFloats)"))
        assertTrue(admission.contains("!snapshot[index].isFinite()"))
        assertTrue(admission.contains("PreviewCompositorRedrawPolicy.creativeLutChanged("))
        assertTrue(
            admission.indexOf("PreviewCompositorRedrawPolicy.creativeLutChanged(") <
                admission.lastIndexOf("requestTemporalHistoryReset(PreviewTemporalFusionReason.LUT_CHANGED)")
        )
        assertTrue(upload.contains("val candidateTexture = candidateArray[0]"))
        assertTrue(upload.contains("val retiredTexture = lutTexId"))
        assertTrue(
            upload.indexOf("val candidateTexture = candidateArray[0]") <
                upload.indexOf("val retiredTexture = lutTexId")
        )
        assertTrue(upload.contains("RGB8 fallback rejected"))
    }

    @Test
    fun `redraw identity policy rejects equivalent state without weakening changed state`() {
        assertFalse(PreviewCompositorRedrawPolicy.debayerPresentationChanged(false, false))
        assertTrue(PreviewCompositorRedrawPolicy.debayerPresentationChanged(false, true))

        val identity = floatArrayOf(1f, 0f, 0f, 1f)
        assertFalse(
            PreviewCompositorRedrawPolicy.debayerOrientationChanged(
                identity,
                identity.copyOf()
            )
        )
        assertTrue(
            PreviewCompositorRedrawPolicy.debayerOrientationChanged(
                identity,
                floatArrayOf(0f, 1f, -1f, 0f)
            )
        )

        val lut = floatArrayOf(0f, 0.5f, 1f)
        assertFalse(
            PreviewCompositorRedrawPolicy.creativeLutChanged(
                currentData = lut,
                currentSize = 2,
                requestedData = lut.copyOf(),
                requestedSize = 2
            )
        )
        assertTrue(
            PreviewCompositorRedrawPolicy.creativeLutChanged(
                currentData = lut,
                currentSize = 2,
                requestedData = floatArrayOf(0f, 0.5f, 0.9f),
                requestedSize = 2
            )
        )
        assertFalse(
            PreviewCompositorRedrawPolicy.creativeLutChanged(
                currentData = null,
                currentSize = 0,
                requestedData = null,
                requestedSize = 0
            )
        )
    }

    @Test
    fun `egl context recreation reuploads retained cube and rejected replacement cannot sample stale look`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt"
        ).readText()
        val initialization = source
            .substringAfter("private fun initGl(output: Surface?, width: Int, height: Int): Boolean")
            .substringBefore("private fun isStrictTenBitConfig")
        val rejection = source
            .substringAfter("private fun rejectCreativeLutUpload(")
            .substringBefore("private fun clearGlErrors()")

        assertTrue(
            "a retained creative cube must be re-uploaded after EGL context loss",
            initialization.contains("if (pendingLut != null)") &&
                initialization.contains("lutDirty = true")
        )
        assertTrue(
            "a failed replacement must disable stale LUT sampling",
            rejection.contains("lutUploaded = false")
        )

        val admissionRejection = source
            .substringAfter("if (rejection != null || requiredFloats == null)")
            .substringBefore("// Admission owns an immutable snapshot")
        assertTrue(
            "a malformed replacement must immediately close the old LUT sampling gate",
            admissionRejection.contains("lutUploaded = false") &&
                admissionRejection.contains("pendingLut = null") &&
                admissionRejection.contains("lutDirty = true")
        )
    }
}
