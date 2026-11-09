package com.neoproc.neoproccamx.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillSingleCapturePolicyTest {

    @Test
    fun resolvePlan_prefersFastJpegSaveWhenNoBitmapWorkIsNeeded() {
        val plan = StillSingleCapturePolicy.resolvePlan(
            capture = newCapture(processSingleBitmap = false),
            requiresAspectCrop = false
        )

        assertEquals("IMG", plan.outputTag)
        assertFalse(plan.requiresBitmapProcessing)
        assertTrue(plan.canFastSaveJpeg)
    }

    @Test
    fun resolvePlan_requiresBitmapProcessingWhenPostProcessIsEnabled() {
        val plan = StillSingleCapturePolicy.resolvePlan(
            capture = newCapture(processSingleBitmap = true),
            requiresAspectCrop = false
        )

        assertTrue(plan.requiresBitmapProcessing)
        assertFalse(plan.canFastSaveJpeg)
    }

    @Test
    fun resolvePlan_requiresBitmapProcessingWhenAspectCropIsNeeded() {
        val plan = StillSingleCapturePolicy.resolvePlan(
            capture = newCapture(processSingleBitmap = false),
            requiresAspectCrop = true
        )

        assertTrue(plan.requiresBitmapProcessing)
        assertFalse(plan.canFastSaveJpeg)
    }

    private fun newCapture(processSingleBitmap: Boolean) = ActiveStillCapture(
        id = 1,
        mode = StillCaptureMode.SINGLE,
        primaryTag = "IMG",
        rawCompanionTag = null,
        debugLabel = null,
        failureLabel = "Photo",
        hdrMode = "OFF",
        expectedJpegs = 1,
        rawRequested = false,
        singleCaptureRawMode = StillCaptureCapturePolicy.SingleCaptureRawMode.JPEG_ONLY,
        processSingleBitmap = processSingleBitmap,
        rawCompanionAfterPrimary = false
    )
}
