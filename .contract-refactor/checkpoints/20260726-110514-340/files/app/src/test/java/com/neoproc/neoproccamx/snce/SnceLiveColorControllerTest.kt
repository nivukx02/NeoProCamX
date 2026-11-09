package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceLiveColorControllerTest {

    private fun uniformYuv(w: Int, h: Int, y: Int, u: Int, v: Int): SnceYuvThumbnail.Frame {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        return SnceYuvThumbnail.Frame(
            width = w, height = h,
            yBytes = ByteArray(w * h) { y.toByte() },
            uBytes = ByteArray(cw * ch) { u.toByte() },
            vBytes = ByteArray(cw * ch) { v.toByte() },
            yRowStride = w, yPixelStride = 1,
            uRowStride = cw, vRowStride = cw,
            uPixelStride = 1, vPixelStride = 1,
            bitDepth = 8
        )
    }

    @Test
    fun `neutral grey yuv converts to an equal-channel thumbnail`() {
        val thumb = SnceYuvThumbnail.fromYuv(uniformYuv(8, 8, 128, 128, 128), 16)!!
        for (i in 0 until thumb.pixelCount()) {
            assertEquals(thumb.rgb[i * 3], thumb.rgb[i * 3 + 1], 1e-3f)
            assertEquals(thumb.rgb[i * 3 + 1], thumb.rgb[i * 3 + 2], 1e-3f)
        }
    }

    @Test
    fun `empty chroma yields a null thumbnail`() {
        val frame = SnceYuvThumbnail.Frame(
            8, 8, ByteArray(64) { 100 }, ByteArray(0), ByteArray(0),
            8, 1, 0, 0, 0, 0, 8
        )
        assertEquals(null, SnceYuvThumbnail.fromYuv(frame, 16))
    }

    @Test
    fun `default controller leaves the grade untouched`() {
        val controller = SnceLiveColorController()
        val grade = FloatArray(16) { it.toFloat() }
        val before = grade.copyOf()
        controller.injectIntoPreviewGrade(grade)
        // identity nudge: slots 1, 4, 6 unchanged
        assertEquals(before[1], grade[1], 1e-6f)
        assertEquals(before[4], grade[4], 1e-6f)
        assertEquals(before[6], grade[6], 1e-6f)
    }

    @Test
    fun `neutral scene keeps the nudge near unity`() {
        val controller = SnceLiveColorController()
        controller.analyze(uniformYuv(8, 8, 128, 128, 128), estimatedLux = 300f)
        val nudge = controller.currentNudge()
        assertEquals(1f, nudge[0], 0.03f)
        assertEquals(1f, nudge[1], 0.03f)
    }

    @Test
    fun `warm-cast preview is corrected within the fail-safe bound`() {
        val controller = SnceLiveColorController()
        // reddish preview: high V, low U
        controller.analyze(uniformYuv(8, 8, 130, 110, 165), estimatedLux = 300f)
        val nudge = controller.currentNudge()
        assertTrue("red scale pulled down", nudge[0] < 1.0f)
        assertTrue("blue scale pushed up", nudge[1] > 1.0f)
        // hard bounds respected
        assertTrue(nudge[0] >= 1f - SnceLiveColorController.MAX_WB_NUDGE - 1e-4f)
        assertTrue(nudge[1] <= 1f + SnceLiveColorController.MAX_WB_NUDGE + 1e-4f)
    }

    @Test
    fun `injection respects the bound on a real grade`() {
        val controller = SnceLiveColorController()
        controller.analyze(uniformYuv(8, 8, 130, 110, 165), estimatedLux = 300f)
        val grade = FloatArray(16) { 1f }
        controller.injectIntoPreviewGrade(grade)
        // slots 4 (R) and 6 (B) can move at most +/-5% from their input of 1.0
        assertTrue(grade[4] in 0.95f..1.05f)
        assertTrue(grade[6] in 0.95f..1.05f)
        assertTrue(grade.all { it.isFinite() })
    }

    @Test
    fun `low light raises the saturation nudge via the hunt effect`() {
        val controller = SnceLiveColorController()
        controller.analyze(uniformYuv(8, 8, 60, 128, 128), estimatedLux = 6f)
        assertTrue("dim scene boosts saturation", controller.currentNudge()[2] > 1.0f)
    }

    private fun neutralP010(width: Int, height: Int, y10: Int, c10: Int): ByteArray {
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val ySamples = width * height
        val bytes = ByteArray((ySamples + cw * ch * 2) * 2)
        fun put(sampleIndex: Int, v10: Int) {
            val b = sampleIndex * 2
            val packed = (v10 and 0x3FF) shl 6
            bytes[b] = (packed and 0xFF).toByte()
            bytes[b + 1] = ((packed shr 8) and 0xFF).toByte()
        }
        for (i in 0 until ySamples) put(i, y10)
        for (i in 0 until cw * ch) { put(ySamples + i * 2, c10); put(ySamples + i * 2 + 1, c10) }
        return bytes
    }

    @Test
    fun `neutral P010 payload converts to an equal-channel thumbnail`() {
        val thumb = SnceYuvThumbnail.fromP010Payload(neutralP010(8, 8, 600, 512), 8, 8, 16)!!
        for (i in 0 until thumb.pixelCount()) {
            assertEquals(thumb.rgb[i * 3], thumb.rgb[i * 3 + 1], 1e-3f)
            assertEquals(thumb.rgb[i * 3 + 1], thumb.rgb[i * 3 + 2], 1e-3f)
        }
    }

    @Test
    fun `short P010 payload returns null`() {
        assertEquals(null, SnceYuvThumbnail.fromP010Payload(ByteArray(8), 64, 64, 16))
    }

    @Test
    fun `analyzeP010 on a neutral frame keeps the nudge near unity`() {
        val controller = SnceLiveColorController()
        controller.analyzeP010(neutralP010(16, 16, 600, 512), 16, 16)
        val nudge = controller.currentNudge()
        assertEquals(1f, nudge[0], 0.03f)
        assertEquals(1f, nudge[1], 0.03f)
        assertTrue(controller.p010SaturationMul() in 0.94f..1.06f)
        assertTrue(controller.p010WarmthBias() in -0.1f..0.1f)
    }

    @Test
    fun `disabling the flag makes the controller a no-op`() {
        val controller = SnceLiveColorController()
        controller.analyze(uniformYuv(8, 8, 130, 110, 165), estimatedLux = 300f)
        try {
            SnceColorEngineFlag.set(false)
            val grade = FloatArray(16) { 1f }
            controller.injectIntoPreviewGrade(grade)
            assertTrue("disabled -> untouched", grade.all { it == 1f })
            assertFalse(controller.analyze(uniformYuv(8, 8, 130, 110, 165), estimatedLux = 300f))
        } finally {
            SnceColorEngineFlag.set(true)
        }
    }
}
