package com.neoproc.neoproccamx.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StillCaptureOutputStoreTest {

    @Test
    fun buildDisplayName_sanitizesDebugSuffixAndUppercasesTag() {
        val name = StillCaptureOutputStore.buildDisplayName(
            tag = "raw",
            extension = "jpg",
            timestampMs = 0L,
            debugLabel = "Pixel 8 Pro / test"
        )

        assertTrue(name.startsWith("CAMX_RAW_"))
        assertTrue(name.contains("_Pixel_8_Pro_test."))
        assertTrue(name.endsWith(".jpg"))
    }

    @Test
    fun buildDisplayName_fallsBackToImgWhenTagIsBlank() {
        val name = StillCaptureOutputStore.buildDisplayName(
            tag = "   ",
            extension = "dng",
            timestampMs = 0L,
            debugLabel = null
        )

        assertTrue(name.startsWith("CAMX_IMG_"))
        assertEquals(".dng", name.takeLast(4))
    }
}
