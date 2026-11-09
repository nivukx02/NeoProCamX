package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceModelRegistryTest {

    @Test
    fun `empty registry is fully classical`() {
        val registry = SnceModelRegistry()
        assertTrue(registry.isFullyClassical)
        assertFalse(registry.hasIlluminantModel)
        assertTrue(registry.summary().contains("classical"))
    }

    @Test
    fun `installing a hook flips the classical flag`() {
        val registry = SnceModelRegistry()
        registry.illuminantModel = SnceIlluminantModel { SnceModelIlluminant(floatArrayOf(1f, 1f, 1f), 0.9f) }
        assertTrue(registry.hasIlluminantModel)
        assertFalse(registry.isFullyClassical)
    }

    @Test
    fun `expected model assets list every directive role`() {
        val expected = SnceModelAssets.EXPECTED
        assertEquals(6, expected.size)
        // MediaPipe face/skin is algorithmic (bundled, no shipped file)
        assertTrue(expected.any { it.role == SnceModelAssets.Role.FACE_SKIN && it.algorithmic })
        // shipped total excludes algorithmic entries
        val shipped = SnceModelAssets.totalShippedBytes()
        assertTrue(shipped > 20_000_000L && shipped < 22_000_000L)
    }

    @Test
    fun `every shipped model declares an Apache or MIT license`() {
        SnceModelAssets.EXPECTED.forEach {
            assertTrue("permissive license", it.license == "Apache-2.0" || it.license == "MIT")
        }
    }
}
