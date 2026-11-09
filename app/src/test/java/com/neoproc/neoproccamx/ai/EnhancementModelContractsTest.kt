package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnhancementModelContractsTest {

    @Test
    fun denoiseEnhancementModelAsset_isPresentAndLooksLikeTfliteFlatbuffer() {
        val model = locateModelAsset()

        assertTrue("enhancement_denoise.tflite must be packaged as a real model asset", model.isFile)
        assertTrue("enhancement_denoise.tflite must not be an empty placeholder", model.length() > 64 * 1024)

        val header = model.inputStream().use { input ->
            ByteArray(8).also { input.read(it) }
        }

        assertEquals('T'.code.toByte(), header[4])
        assertEquals('F'.code.toByte(), header[5])
        assertEquals('L'.code.toByte(), header[6])
        assertEquals('3'.code.toByte(), header[7])
    }

    private fun locateModelAsset(): File {
        val candidates = listOf(
            "app/src/main/assets/models/enhancement_denoise.tflite",
            "src/main/assets/models/enhancement_denoise.tflite"
        )
        return candidates
            .map { File(it).absoluteFile }
            .firstOrNull { it.isFile }
            ?: error("Could not locate enhancement_denoise.tflite in ${candidates.joinToString()}")
    }
}
