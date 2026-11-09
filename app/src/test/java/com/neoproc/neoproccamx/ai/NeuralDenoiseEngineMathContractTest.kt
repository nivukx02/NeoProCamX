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
    fun frameFeatherWeightsNeverZeroTheOuterImageBorder() {
        val width = 512
        val height = 384
        val accumulatedWeights = FloatArray(width * height)

        NeuralDenoiseEngine.buildTileGrid(width, height).forEach { tile ->
            for (localY in 0 until tile.height) {
                for (localX in 0 until tile.width) {
                    val x = tile.x + localX
                    val y = tile.y + localY
                    accumulatedWeights[y * width + x] += NeuralDenoiseEngine.frameFeatherWeight(
                        localX = localX,
                        localY = localY,
                        tile = tile,
                        frameWidth = width,
                        frameHeight = height
                    )
                }
            }
        }

        assertTrue(accumulatedWeights.all { it > 0f })
        assertEquals(1f, accumulatedWeights[0], 0.0001f)
        assertEquals(1f, accumulatedWeights[width - 1], 0.0001f)
        assertEquals(1f, accumulatedWeights[(height - 1) * width], 0.0001f)
        assertEquals(1f, accumulatedWeights.last(), 0.0001f)
    }

    @Test
    fun boundedGuidedTilesCoverEveryCorePixelOnceWithSixPixelHalo() {
        val width = 1_200
        val height = 777
        val tiles = NeuralDenoiseEngine.buildBoundedTileGrid(width, height)
        val coverage = ByteArray(width * height)

        tiles.forEach { tile ->
            assertTrue(tile.sourceX >= 0 && tile.sourceY >= 0)
            assertTrue(tile.sourceX + tile.sourceWidth <= width)
            assertTrue(tile.sourceY + tile.sourceHeight <= height)
            assertTrue(tile.sourceX <= tile.coreX && tile.sourceY <= tile.coreY)
            assertTrue(tile.sourceX + tile.sourceWidth >= tile.coreX + tile.coreWidth)
            assertTrue(tile.sourceY + tile.sourceHeight >= tile.coreY + tile.coreHeight)
            for (y in tile.coreY until tile.coreY + tile.coreHeight) {
                for (x in tile.coreX until tile.coreX + tile.coreWidth) {
                    coverage[y * width + x]++
                }
            }
        }

        val interior = tiles.first { it.coreX > 0 && it.coreY > 0 }
        assertEquals(6, interior.coreX - interior.sourceX)
        assertEquals(6, interior.coreY - interior.sourceY)
        assertTrue(coverage.all { it.toInt() == 1 })
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
