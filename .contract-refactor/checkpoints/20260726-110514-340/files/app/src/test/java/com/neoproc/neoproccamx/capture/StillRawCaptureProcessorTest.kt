package com.neoproc.neoproccamx.capture

import android.hardware.camera2.CameraCharacteristics
import com.neoproc.neoproccamx.ai.SceneType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StillRawCaptureProcessorTest {

    @Test
    fun lensFacingLabel_mapsKnownFrontAndBackValues() {
        assertEquals(
            "FRONT",
            StillRawCaptureProcessor.lensFacingLabel(CameraCharacteristics.LENS_FACING_FRONT)
        )
        assertEquals(
            "BACK",
            StillRawCaptureProcessor.lensFacingLabel(CameraCharacteristics.LENS_FACING_BACK)
        )
    }

    @Test
    fun buildSceneMetadata_capturesRawFallbackContext() {
        val metadata = StillRawCaptureProcessor.buildSceneMetadata(
            sceneType = SceneType.PORTRAIT,
            enhancementEnabled = true,
            hdrSuggested = false
        )

        assertTrue(metadata.contains("mode=RAW_SOFTWARE"))
        assertTrue(metadata.contains("scene=PORTRAIT"))
        assertTrue(metadata.contains("aiEnhance=true"))
        assertTrue(metadata.contains("hdrSuggested=false"))
    }

    @Test
    fun softwareRawFallbackMetadataLabelsJpegDerivedEightBitPackageTruthfully() {
        val source = File(repoRoot(), "app/src/main/java/com/neoproc/neoproccamx/capture/StillRawCaptureProcessor.kt")
            .readText()
        val fallbackBlock = source
            .substringAfter("sourceType = \"single_jpeg_fallback\"")
            .substringBefore("processingPipeline = processingPipeline")

        assertTrue(fallbackBlock.contains("bitDepth = 8"))
        assertTrue(fallbackBlock.contains("compressionMode = \"ZIP_JPEG_ASSETS\""))
        assertFalse(fallbackBlock.contains("bitDepth = 16"))
        assertFalse(fallbackBlock.contains("ZIP_LOSSLESS_16BIT"))
    }

    private fun repoRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .filterNotNull()
            .first { File(it, "settings.gradle").isFile }
}
