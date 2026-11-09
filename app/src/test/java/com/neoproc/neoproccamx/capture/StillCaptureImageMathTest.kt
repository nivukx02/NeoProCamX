package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StillCaptureImageMathTest {

    @Test
    fun requiresAspectCrop_returnsFalseWhenAspectMatches() {
        assertFalse(StillCaptureImageMath.requiresAspectCrop(4f / 3f, 4f / 3f))
    }

    @Test
    fun requiresAspectCrop_returnsTrueWhenAspectDiffers() {
        assertTrue(StillCaptureImageMath.requiresAspectCrop(16f / 9f, 4f / 3f))
    }

    @Test
    fun resolveCenteredAspectCrop_returnsCenteredSquareCrop() {
        val crop = StillCaptureImageMath.resolveCenteredAspectCrop(
            width = 4000,
            height = 3000,
            targetAspect = 1f
        )

        assertNotNull(crop)
        assertEquals(500, crop?.left)
        assertEquals(0, crop?.top)
        assertEquals(3000, crop?.width)
        assertEquals(3000, crop?.height)
    }

    @Test
    fun resolveCenteredAspectCrop_preservesLandscapeOrientation() {
        val crop = StillCaptureImageMath.resolveCenteredAspectCrop(
            width = 4096,
            height = 3072,
            targetAspect = 16f / 9f
        )

        assertEquals(ImageCropRect(left = 0, top = 384, width = 4096, height = 2304), crop)
    }

    @Test
    fun resolveCenteredAspectCrop_usesReciprocalTargetForPortraitBitmap() {
        val crop = StillCaptureImageMath.resolveCenteredAspectCrop(
            width = 3072,
            height = 4096,
            targetAspect = 16f / 9f
        )

        assertEquals(ImageCropRect(left = 384, top = 0, width = 2304, height = 4096), crop)
    }

    @Test
    fun resolveCenteredAspectCrop_rejectsInvalidTarget() {
        assertEquals(null, StillCaptureImageMath.resolveCenteredAspectCrop(4096, 3072, 0f))
        assertEquals(null, StillCaptureImageMath.resolveCenteredAspectCrop(4096, 3072, Float.NaN))
    }

    @Test
    fun requiresAspectCrop_isMoreSensitiveInStrictHighEndMode() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true

            val requiresCrop = StillCaptureImageMath.requiresAspectCrop(
                outputAspect = 1.33340f,
                sensorAspect = 1.33332f
            )

            assertTrue(requiresCrop)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

}
