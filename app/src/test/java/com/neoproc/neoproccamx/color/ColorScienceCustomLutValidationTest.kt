package com.neoproc.neoproccamx.color

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ColorScienceCustomLutValidationTest {

    @Test
    fun validCubeIsAcceptedWithoutReplacingTheActiveLut() {
        val file = Files.createTempFile("custom-lut-valid", ".cube").toFile()
        try {
            file.writeText(
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
            )
            val colorScience = ColorScience()

            assertTrue(colorScience.validateCubeLUTBlocking(file))
            assertNull("validation must not mutate the active LUT", colorScience.activeLutLabel())
        } finally {
            file.delete()
        }
    }

    @Test
    fun malformedOrEmptyCubeIsRejectedWithoutMutation() {
        val malformed = Files.createTempFile("custom-lut-invalid", ".cube").toFile()
        val empty = Files.createTempFile("custom-lut-empty", ".cube").toFile()
        try {
            malformed.writeText("LUT_3D_SIZE 2\n0.0 0.0 0.0\n")
            val colorScience = ColorScience()

            assertFalse(colorScience.validateCubeLUTBlocking(malformed))
            assertFalse(colorScience.validateCubeLUTBlocking(empty))
            assertNull(colorScience.activeLutLabel())
        } finally {
            malformed.delete()
            empty.delete()
        }
    }
}
