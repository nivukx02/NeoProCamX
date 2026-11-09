package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewColorParityFlagTest {
    @After fun tearDown() {
        PreviewColorParityFlag.registerOnChanged(null)
        PreviewColorParityFlag.set(true)
    }

    @Test fun `default is on so high end preview matches recorded color`() {
        assertTrue(PreviewColorParityFlag.enabled)
    }

    @Test fun `set toggles and fires onChanged only on a real change`() {
        var count = 0
        PreviewColorParityFlag.registerOnChanged { count++ }
        PreviewColorParityFlag.set(true); assertEquals(0, count)
        PreviewColorParityFlag.set(false); assertFalse(PreviewColorParityFlag.enabled); assertEquals(1, count)
        PreviewColorParityFlag.set(false); assertEquals(1, count)
        PreviewColorParityFlag.set(true); assertTrue(PreviewColorParityFlag.enabled); assertEquals(2, count)
    }

    // Source-contract guard: the WYSIWYG preview LUT stage must stay SDR-gated (only GRADE_SDR_ENHANCE)
    // and reproduce ColorScience's R-fastest 2D packing, so it can never silently alter LOG/RAW/HDR or
    // desync from the record cube.
    @Test fun `preview compositor creative-LUT is SDR-gated and R-fastest`() {
        val src = repoFile(
            "app/src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt"
        ).readText()
        assertTrue("uLutEnabled uniform missing", src.contains("uLutEnabled"))
        assertTrue("sampleCreativeLut helper missing", src.contains("sampleCreativeLut"))
        assertTrue(
            "R-fastest 2D packing (y = gi + bi*N) missing",
            src.contains("(gi + bi * n + 0.5) / (n * n)")
        )
        assertTrue(
            "creative LUT must bind only for GRADE_SDR_ENHANCE",
            src.contains("gradeCode == GRADE_SDR_ENHANCE")
        )
    }

    private fun repoFile(relativePath: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(5) {
            val candidate = File(dir, relativePath)
            if (candidate.exists()) return candidate
            val parent = dir.parentFile
            if (parent != null) dir = parent
        }
        return File(relativePath)
    }
}
