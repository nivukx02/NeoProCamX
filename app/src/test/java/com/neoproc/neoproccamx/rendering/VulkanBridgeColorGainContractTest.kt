package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanBridgeColorGainContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt").readText()
    }

    @Test
    fun `strict high end clamps rgb gain but preserves zero blend alpha`() {
        val body = source.substringAfter("val effectiveColorGain = if (strictHighEndEnabled())")
            .substringBefore("nativeUpdateRenderConfig(")

        assertTrue(body.contains("colorGain[0].coerceIn(0.85f, 1.35f)"))
        assertTrue(body.contains("colorGain[1].coerceIn(0.85f, 1.35f)"))
        assertTrue(body.contains("colorGain[2].coerceIn(0.85f, 1.35f)"))
        assertTrue(body.contains("colorGain[3].coerceIn(0f, 1f)"))
        assertFalse(
            "colorGain[3] is shader blend strength, not an RGB multiplier; clamping it to " +
                "0.85 forces full color tint because the shader multiplies uColorGain.a by 18.",
            body.contains("colorGain[3].coerceIn(0.85f, 1.35f)")
        )
    }
}
