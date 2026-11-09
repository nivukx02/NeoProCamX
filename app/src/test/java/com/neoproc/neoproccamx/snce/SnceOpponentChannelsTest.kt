package com.neoproc.neoproccamx.snce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnceOpponentChannelsTest {

    @Test
    fun `neutral gray has near-zero opponent chroma`() {
        val n = 4 * 4
        val rgb = FloatArray(n * 3) { 0.5f }
        val ipt = SnceOpponentChannels.toOpponentField(SnceThumbnail(4, 4, rgb))
        for (i in 0 until n) {
            assertEquals("P near 0", 0f, ipt[i * 3 + 1], 0.03f)
            assertEquals("T near 0", 0f, ipt[i * 3 + 2], 0.03f)
        }
    }

    @Test
    fun `lateral inhibition enhances chromatic contrast`() {
        val w = 8; val h = 8; val n = w * h
        val ipt = FloatArray(n * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            ipt[i] = 0.5f
            ipt[i + 1] = if (x < w / 2) 0.05f else -0.05f // red-green edge
            ipt[i + 2] = 0f
        }
        val inhibited = SnceOpponentChannels.lateralInhibition(ipt, w, h)
        val maxBefore = (0 until n).maxOf { kotlin.math.abs(ipt[it * 3 + 1]) }
        val maxAfter = (0 until n).maxOf { kotlin.math.abs(inhibited[it * 3 + 1]) }
        assertTrue("DoG should boost chroma magnitude", maxAfter > maxBefore)
    }

    @Test
    fun `mesopic weight is zero in daylight and high in deep dark`() {
        assertEquals(0f, SnceOpponentChannels.mesopicWeight(3.0f), 1e-4f)
        assertTrue(SnceOpponentChannels.mesopicWeight(0.01f) > 0.9f)
    }

    @Test
    fun `rod injection only acts in low light`() {
        val ipt = FloatArray(3 * 3) { 0.3f }
        val bright = SnceOpponentChannels.rodInject(ipt.copyOf(), sceneLuminanceCdm2 = 50f)
        assertTrue(bright.contentEquals(ipt))
        val dark = SnceOpponentChannels.rodInject(
            ipt.copyOf(), sceneLuminanceCdm2 = 0.5f, scotopicLuma = FloatArray(3) { 0.9f }
        )
        assertTrue("I channel lifted toward rod signal", dark[0] > ipt[0])
    }
}
