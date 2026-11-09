package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CustomLutInstallerTest {

    @Test
    fun invalidReplacementPreservesPreviouslyInstalledLut() {
        val directory = Files.createTempDirectory("custom-lut-preserve").toFile()
        try {
            val target = File(directory, "custom_import.cube").apply { writeText("previous-valid-lut") }
            val staged = File(directory, "replacement.cube.pending").apply {
                writeText("LUT_3D_SIZE 2\n0 0 0\n")
            }

            val result = CustomLutInstaller.installValidatedStagedFile(staged, target)

            assertTrue(result is CustomLutInstallResult.Failure)
            assertEquals("previous-valid-lut", target.readText())
            assertFalse(staged.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun validReplacementIsAtomicallyInstalled() {
        val directory = Files.createTempDirectory("custom-lut-atomic").toFile()
        try {
            val target = File(directory, "custom_import.cube").apply { writeText("previous-valid-lut") }
            val staged = File(directory, "replacement.cube.pending").apply { writeText(validIdentityCube()) }

            val result = CustomLutInstaller.installValidatedStagedFile(staged, target)

            assertTrue(result is CustomLutInstallResult.Success)
            assertEquals(validIdentityCube(), target.readText())
            assertFalse(staged.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun validIdentityCube(): String =
        """
        TITLE "Identity 2"
        LUT_3D_SIZE 2
        DOMAIN_MIN 0.0 0.0 0.0
        DOMAIN_MAX 1.0 1.0 1.0
        0.0 0.0 0.0
        1.0 0.0 0.0
        0.0 1.0 0.0
        1.0 1.0 0.0
        0.0 0.0 1.0
        1.0 0.0 1.0
        0.0 1.0 1.0
        1.0 1.0 1.0
        """.trimIndent()
}
