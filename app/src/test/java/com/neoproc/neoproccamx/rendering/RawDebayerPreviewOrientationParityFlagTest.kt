package com.neoproc.neoproccamx.rendering

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class RawDebayerPreviewOrientationParityFlagTest {

    @Before
    fun reset() {
        RawDebayerPreviewOrientationParityFlag.registerOnChanged(null)
        RawDebayerPreviewOrientationParityFlag.set(false)
    }

    @After
    fun restore() {
        RawDebayerPreviewOrientationParityFlag.registerOnChanged(null)
        RawDebayerPreviewOrientationParityFlag.set(false)
    }

    @Test
    fun objectDefaultStaysOffForDebugBaseline() {
        assertFalse(RawDebayerPreviewOrientationParityFlag.enabled)
    }

    @Test
    fun setTogglesEnabledAndFiresOnlyOnRealChange() {
        var count = 0
        RawDebayerPreviewOrientationParityFlag.registerOnChanged { count++ }

        RawDebayerPreviewOrientationParityFlag.set(false)
        assertEquals(0, count)
        RawDebayerPreviewOrientationParityFlag.set(true)
        assertTrue(RawDebayerPreviewOrientationParityFlag.enabled)
        assertEquals(1, count)
        RawDebayerPreviewOrientationParityFlag.set(true)
        assertEquals(1, count)
        RawDebayerPreviewOrientationParityFlag.set(false)
        assertFalse(RawDebayerPreviewOrientationParityFlag.enabled)
        assertEquals(2, count)
    }

    @Test
    fun productDefaultsEnableRawDebayerPreviewOrientationAtStartup() {
        val candidates = listOf(
            File("app/src/main/java/com/neoproc/neoproccamx/ui/ProductDefaultsApplier.kt"),
            File("src/main/java/com/neoproc/neoproccamx/ui/ProductDefaultsApplier.kt")
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(
            "Startup must enable RAW debayer preview orientation parity so portrait RAW preview is upright.",
            source.contains("RawDebayerPreviewOrientationParityFlag.set(true)")
        )
    }
}
