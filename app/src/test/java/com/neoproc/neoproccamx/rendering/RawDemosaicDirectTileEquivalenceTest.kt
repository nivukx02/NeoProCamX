package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Numerical lock for the Vulkan demosaic's direct shared-tile indexing. The reference path evaluates
 * every neighbour through clamped image coordinates; [directTileDemosaic] first builds the exact
 * 20x20 calibrated halo used by a 16x16 workgroup and then indexes it locally like the optimized shader.
 */
class RawDemosaicDirectTileEquivalenceTest {
    private companion object {
        const val WORKGROUP = 16
        const val GUARD = 2
        const val TILE = WORKGROUP + 2 * GUARD
        val IDENTITY_3X3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    }

    @Test
    fun `xor Bayer mapping equals all four reference tables`() {
        for (pattern in 0..3) {
            for (y in 0 until 8) for (x in 0 until 8) {
                val xorChannel = (((y and 1) shl 1) or (x and 1)) xor pattern
                assertEquals(
                    "pattern=$pattern coordinate=($x,$y)",
                    RawDemosaicReference.bayerChannel(x, y, pattern),
                    xorChannel
                )
            }
        }
    }

    @Test
    fun `out of range Bayer values preserve legacy BGGR fallback`() {
        for (pattern in listOf(Int.MIN_VALUE, -1, 4, 99, Int.MAX_VALUE)) {
            val safeMask = if (pattern in 0..3) pattern else 3
            for (y in 0 until 4) for (x in 0 until 4) {
                val xorChannel = ((((y and 1) shl 1) or (x and 1)) xor safeMask)
                assertEquals(
                    "invalid pattern=$pattern coordinate=($x,$y)",
                    RawDemosaicReference.bayerChannel(x, y, pattern),
                    xorChannel
                )
            }
        }
    }

    @Test
    fun `direct tile indexing is exactly equivalent on every Bayer pattern and clamped edge`() {
        val dimensions = listOf(
            1 to 1,
            5 to 7,
            15 to 16,
            17 to 19,
            31 to 33
        )
        for (pattern in 0..3) {
            for ((width, height) in dimensions) {
                val raw = IntArray(width * height) { index ->
                    val x = index % width
                    val y = index / width
                    96 + ((x * 173 + y * 257 + (x xor y) * 31 + pattern * 47) % 4000)
                }
                val mosaic = RawDemosaicReference.Mosaic(width, height, raw)
                val params = RawDemosaicReference.Params(
                    bayerPattern = pattern,
                    whiteLevel = 4095,
                    blackLevelRggb = floatArrayOf(64f, 68f, 72f, 80f),
                    whiteBalanceRggb = floatArrayOf(2.05f, 1.0f, 1.035f, 1.61f),
                    mSensorToXyz = IDENTITY_3X3,
                    mXyzToAcescg = IDENTITY_3X3
                )

                val coordinateReference = RawDemosaicReference.demosaicSensorRgb(mosaic, params)
                val directTile = directTileDemosaic(mosaic, params)
                assertArrayEquals(
                    "direct tile mismatch for pattern=$pattern at ${width}x$height",
                    coordinateReference,
                    directTile,
                    0f
                )
            }
        }
    }

    private fun directTileDemosaic(
        mosaic: RawDemosaicReference.Mosaic,
        params: RawDemosaicReference.Params
    ): FloatArray {
        val output = FloatArray(mosaic.width * mosaic.height * 3)
        val groupsX = (mosaic.width + WORKGROUP - 1) / WORKGROUP
        val groupsY = (mosaic.height + WORKGROUP - 1) / WORKGROUP

        for (groupY in 0 until groupsY) for (groupX in 0 until groupsX) {
            val baseX = groupX * WORKGROUP - GUARD
            val baseY = groupY * WORKGROUP - GUARD
            val tile = FloatArray(TILE * TILE)
            for (tileY in 0 until TILE) for (tileX in 0 until TILE) {
                val sourceX = (baseX + tileX).coerceIn(0, mosaic.width - 1)
                val sourceY = (baseY + tileY).coerceIn(0, mosaic.height - 1)
                tile[tileY * TILE + tileX] =
                    RawDemosaicReference.calibratedRaw(mosaic, params, sourceX, sourceY)
            }

            for (localY in 0 until WORKGROUP) for (localX in 0 until WORKGROUP) {
                val x = groupX * WORKGROUP + localX
                val y = groupY * WORKGROUP + localY
                if (x >= mosaic.width || y >= mosaic.height) continue
                val channel = RawDemosaicReference.bayerChannel(x, y, params.bayerPattern)
                val rgb = demosaicFromTile(tile, localX + GUARD, localY + GUARD, channel)
                val outputOffset = (y * mosaic.width + x) * 3
                output[outputOffset] = rgb[0]
                output[outputOffset + 1] = rgb[1]
                output[outputOffset + 2] = rgb[2]
            }
        }
        return output
    }

    private fun demosaicFromTile(tile: FloatArray, centerX: Int, centerY: Int, channel: Int): FloatArray {
        fun sample(x: Int, y: Int): Float = tile[y * TILE + x]

        val center = sample(centerX, centerY)
        val xM1 = sample(centerX - 1, centerY)
        val xP1 = sample(centerX + 1, centerY)
        val yM1 = sample(centerX, centerY - 1)
        val yP1 = sample(centerX, centerY + 1)

        if (channel == 1 || channel == 2) {
            val greenOnRedRow = channel == 1
            val red = if (greenOnRedRow) 0.5f * (xM1 + xP1) else 0.5f * (yM1 + yP1)
            val blue = if (greenOnRedRow) 0.5f * (yM1 + yP1) else 0.5f * (xM1 + xP1)
            return floatArrayOf(maxOf(red, 0f), maxOf(center, 0f), maxOf(blue, 0f))
        }

        val xM2 = sample(centerX - 2, centerY)
        val xP2 = sample(centerX + 2, centerY)
        val yM2 = sample(centerX, centerY - 2)
        val yP2 = sample(centerX, centerY + 2)
        val horizontal = kotlin.math.abs((xM2 + 2f * center + xP2) - (2f * xM1 + 2f * xP1))
        val vertical = kotlin.math.abs((yM2 + 2f * center + yP2) - (2f * yM1 + 2f * yP1))
        val gH = 0.5f * (xM1 + xP1) + 0.25f * (2f * center - xM2 - xP2)
        val gV = 0.5f * (yM1 + yP1) + 0.25f * (2f * center - yM2 - yP2)
        val gA = 0.25f * (xM1 + xP1 + yM1 + yP1) +
            0.125f * (4f * center - xM2 - xP2 - yM2 - yP2)
        val green = if (horizontal < vertical) gH else if (vertical < horizontal) gV else gA
        val diagonal = 0.25f * (
            sample(centerX - 1, centerY - 1) + sample(centerX + 1, centerY - 1) +
                sample(centerX - 1, centerY + 1) + sample(centerX + 1, centerY + 1)
            ) + (center - green) * 0.5f
        val red = if (channel == 0) center else diagonal
        val blue = if (channel == 3) center else diagonal
        return floatArrayOf(maxOf(red, 0f), maxOf(green, 0f), maxOf(blue, 0f))
    }
}
