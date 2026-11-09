package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NeuralDenoiseEngineMathContractTest {

    @Test
    fun tileGridUsesOverlapping256TilesAndCoversFrame() {
        val tiles = NeuralDenoiseEngine.buildTileGrid(width = 512, height = 384)

        assertTrue(tiles.size >= 4)
        assertEquals(0, tiles.first().x)
        assertEquals(0, tiles.first().y)
        assertTrue(tiles.any { it.x + it.width == 512 })
        assertTrue(tiles.any { it.y + it.height == 384 })
        assertTrue(tiles.zipWithNext().any { (a, b) ->
            a.y == b.y && a.x + a.width > b.x
        })
    }

    @Test
    fun featherWeightSuppressesTileSeamsAtEdges() {
        val edge = NeuralDenoiseEngine.featherWeight(localX = 0, localY = 128, tileWidth = 256, tileHeight = 256)
        val center = NeuralDenoiseEngine.featherWeight(localX = 128, localY = 128, tileWidth = 256, tileHeight = 256)

        assertTrue(edge < 0.2f)
        assertEquals(1f, center, 0.0001f)
    }

    @Test
    fun guidedFilterPreservesHardEdgesWhileReducingFlatNoise() {
        val width = 8
        val height = 4
        val guide = FloatArray(width * height) { index ->
            val x = index % width
            if (x < width / 2) 32f else 220f
        }
        val input = guide.copyOf()
        input[1] = 50f
        input[2] = 22f
        input[width + 5] = 240f

        val filtered = NeuralDenoiseEngine.applyGuidedFilter(
            input = input,
            guide = guide,
            width = width,
            height = height,
            r = 2,
            eps = 0.02f * 255f * 255f
        )

        assertTrue(abs(filtered[1] - guide[1]) < abs(input[1] - guide[1]))
        assertTrue(abs(filtered[2] - guide[2]) < abs(input[2] - guide[2]))
        val leftMean = filtered[3]
        val rightMean = filtered[4]
        assertTrue("edge collapsed: left=$leftMean right=$rightMean", rightMean - leftMean > 120f)
    }

    @Test
    fun temporalBlendRejectsLargeMotionAndLargeDifferences() {
        val width = 3
        val height = 1
        val current = floatArrayOf(0.2f, 0.5f, 0.9f)
        val previous = floatArrayOf(0.1f, 0.5f, 0.1f)
        val stillFlow = FloatArray(width * height)
        val fastFlow = FloatArray(width * height) { 2.0f }

        val still = NeuralDenoiseEngine.motionCompensatedTemporalBlend(
            current = current,
            previous = previous,
            flowX = stillFlow,
            flowY = stillFlow,
            width = width,
            height = height
        )
        val moving = NeuralDenoiseEngine.motionCompensatedTemporalBlend(
            current = current,
            previous = previous,
            flowX = fastFlow,
            flowY = stillFlow,
            width = width,
            height = height
        )

        assertTrue(still[1] == 0.5f)
        assertTrue(abs(still[0] - previous[0]) < abs(current[0] - previous[0]))
        assertTrue(abs(moving[0] - current[0]) < abs(still[0] - current[0]))
        assertTrue("large luminance difference should suppress history", still[2] > 0.82f)
    }
}
