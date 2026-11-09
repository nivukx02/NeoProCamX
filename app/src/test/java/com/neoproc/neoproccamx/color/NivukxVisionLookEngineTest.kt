package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NivukxVisionLookEngineTest {

    @Test
    fun houseLooksAreThirtyThreeCubeAndHashable() {
        val looks = NivukxVisionLookEngine.houseLooks()

        assertEquals(
            listOf("NVX-Cinema-Neutral", "NVX-Vivid-Neon", "NVX-Filmic-Warm"),
            looks.map { it.name }
        )
        looks.forEach { look ->
            assertEquals(33, look.lutSize)
            assertTrue(look.assetPath.endsWith(".cube"))
            assertEquals(64, look.sha256.length)
            assertFalse(look.name.contains("Dolby", ignoreCase = true))
            assertFalse(look.name.contains("HDR10", ignoreCase = true))
            assertFalse(look.name.contains("IMAX", ignoreCase = true))
        }
    }

    @Test
    fun shippedHouseLutAssetsMatchDeclaredHashes() {
        NivukxVisionLookEngine.houseLooks().forEach { look ->
            val file = File("src/main/assets/${look.assetPath}")
            assertTrue("${look.assetPath} must exist", file.isFile)
            assertEquals(look.sha256, NivukxVisionLookEngine.sha256(file.readBytes()))
            assertTrue(file.readText().contains("LUT_3D_SIZE 33"))
        }
    }

    @Test
    fun warmthTintAndSaturationKnobsPreserveJzLightness() {
        val color = NivukxVisionMath.JzAzBz(jz = 0.36, az = 0.08, bz = -0.04)
        val graded = NivukxVisionLookEngine.applyLook(
            color = color,
            knobs = NivukxVisionLookKnobs(
                exposureEv = 0.0,
                contrast = 1.0,
                saturation = 1.35,
                shadowWarmthDegrees = 6.0,
                midtoneTintDegrees = -4.0,
                highlightShoulderGain = 0.8
            )
        )

        assertEquals(color.jz, graded.jz, 1e-12)
    }
}
