package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawPreviewFrameTransportContractTest {
    private val appRoot = sequenceOf(File("."), File("../../app"))
        .first { File(it, "src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt").isFile }
    private val compositor = File(
        appRoot,
        "src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt"
    ).readText()
    private val presenter = File(
        appRoot,
        "src/main/java/com/neoproc/neoproccamx/rendering/RawDebayerPreviewPresenter.kt"
    ).readText()

    @Test
    fun `raw preview publication is one immutable leased frame`() {
        assertTrue(compositor.contains("private val debayerFrameMailbox = RawPreviewFrameMailbox()"))
        assertTrue(compositor.contains("fun presentDebayerRgba8(frame: RawPreviewFrameLease)"))
        assertTrue(compositor.contains("debayerFrameMailbox.publish(frame)"))
        assertTrue(compositor.contains("val frame = debayerFrameMailbox.take()"))
        assertFalse(compositor.contains("pendingDebayerBytes"))
        assertFalse(compositor.contains("pendingDebayerPresentationDirty"))
    }

    @Test
    fun `raw preview buffers remain leased through validated gl upload`() {
        val draw = compositor
            .substringAfter("private fun drawDebayer2d(frame: RawPreviewFrameLease)")
            .substringBefore("private fun build2dProgram")
        assertTrue(draw.indexOf("uploadDebayerTexture(frame)") < draw.indexOf("frame.close()"))
        assertTrue(draw.contains("glTexSubImage2D"))
        assertTrue(draw.contains("tex2dAllocatedWidth != frame.width"))
        assertTrue(draw.contains("frame.pixels.capacity() < requiredBytes"))
        assertTrue(draw.contains("failClosedDebayerUpload"))
    }

    @Test
    fun `producer uses bounded four buffer pool instead of unsafe ping pong reuse`() {
        assertTrue(presenter.contains("RawPreviewBufferPool(BUFFER_COUNT, MAX_PREVIEW_BYTES)"))
        assertTrue(presenter.contains("const val BUFFER_COUNT = 4"))
        assertTrue(presenter.contains("recycle = bufferPool::recycle"))
        assertFalse(presenter.contains("bufferIndex xor 1"))
    }
}
