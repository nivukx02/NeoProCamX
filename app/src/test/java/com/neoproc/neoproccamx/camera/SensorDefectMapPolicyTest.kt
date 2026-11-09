package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorDefectMapPolicyTest {

    private val width = 64
    private val height = 64
    private val black = 64
    private val white = 4095

    private fun darkFrame(vararg hot: Pair<Int, Int>): IntArray {
        val samples = IntArray(width * height) { black }
        for ((x, y) in hot) {
            samples[y * width + x] = white
        }
        return samples
    }

    @Test
    fun flatDarkFrameProducesIdentityMapAndChangesNothing() {
        val samples = IntArray(width * height) { black }
        val map = SensorDefectMapPolicy.fromDarkFrame(samples, width, height, black, white)

        assertTrue(map.isIdentity)
        assertEquals(0, map.defectCount)
        assertEquals(0f, map.darkCurrentMeanOffset, 0.001f)
        // No correction anywhere — every sample passes through unchanged (NO-DOWNGRADE).
        for (y in 0 until height) {
            for (x in 0 until width) {
                assertEquals(samples[y * width + x], map.correctedSample(samples, x, y, width, height))
            }
        }
        // hotPixelMask (demosaic binding 3) is all zero.
        assertTrue(map.toHotPixelMask().all { it.toInt() == 0 })
    }

    @Test
    fun isolatedHotPixelsAreDetectedAndNeighboursAreNot() {
        val samples = darkFrame(10 to 10, 40 to 30)
        val map = SensorDefectMapPolicy.fromDarkFrame(samples, width, height, black, white)

        assertFalse(map.isIdentity)
        assertEquals(2, map.defectCount)
        assertTrue(map.isDefect(10, 10))
        assertTrue(map.isDefect(40, 30))
        // Orthogonal neighbours of a hot pixel are clean.
        assertFalse(map.isDefect(11, 10))
        assertFalse(map.isDefect(9, 10))
        assertFalse(map.isDefect(10, 11))
        assertFalse(map.isDefect(0, 0))
        assertTrue(map.signature.contains("hot-pixels"))
    }

    @Test
    fun darkFrameGoldenNoIsolatedSpeckleSurvivesCorrection() {
        val samples = darkFrame(10 to 10, 40 to 30)
        val map = SensorDefectMapPolicy.fromDarkFrame(samples, width, height, black, white)

        // Precondition: the raw frame really does contain bright speckle.
        assertTrue(samples.any { it - black > 256 })

        val speckleCeiling = black + 64
        var maxCorrected = Int.MIN_VALUE
        for (y in 0 until height) {
            for (x in 0 until width) {
                val corrected = map.correctedSample(samples, x, y, width, height)
                maxCorrected = maxOf(maxCorrected, corrected)
                if (!map.isDefect(x, y)) {
                    // Non-defect photosites are never altered.
                    assertEquals(samples[y * width + x], corrected)
                }
            }
        }
        // After correction, no isolated hot speckle survives into interpolation.
        assertTrue("max corrected=$maxCorrected exceeds speckle ceiling", maxCorrected <= speckleCeiling)
        // The hot pixel collapses to its (dark) neighbourhood average.
        assertEquals(black, map.correctedSample(samples, 10, 10, width, height))
    }

    @Test
    fun uniformDarkCurrentIsReportedNotFlagged() {
        val offset = 10
        val samples = IntArray(width * height) { black + offset }
        val map = SensorDefectMapPolicy.fromDarkFrame(samples, width, height, black, white)

        assertTrue(map.isIdentity)
        assertEquals(0, map.defectCount)
        assertEquals(offset.toFloat(), map.darkCurrentMeanOffset, 0.001f)
    }

    @Test
    fun fewOutliersAreDetectedBelowTheMisCaptureGuard() {
        val samples = IntArray(width * height) { black }
        for (i in 0 until 10) samples[i * 7] = white // 10 isolated hot pixels (0.24%)
        val map = SensorDefectMapPolicy.fromDarkFrame(samples, width, height, black, white)

        assertEquals(10, map.defectCount)
        assertFalse(map.isIdentity)
    }

    @Test
    fun misCaptureGuardFallsBackToIdentityWhenTooManyWouldFlag() {
        // ~5% of photosites are bright — this is not a real dark frame; never reject real signal.
        val samples = IntArray(width * height) { black }
        val flagged = (width * height) * 5 / 100
        for (i in 0 until flagged) samples[i] = 200
        val map = SensorDefectMapPolicy.fromDarkFrame(
            samples, width, height, black, white,
            SensorDefectMapPolicy.Config(hotPixelSigma = 1f)
        )

        assertTrue(map.isIdentity)
        assertEquals(0, map.defectCount)
    }

    @Test
    fun fromHotPixelCoordinatesFlagsValidPointsAndIgnoresOutOfRangeAndDuplicates() {
        val map = SensorDefectMapPolicy.fromHotPixelCoordinates(
            listOf(1 to 1, 5 to 5, 1 to 1, 100 to 100, -1 to 0),
            width = 8,
            height = 8
        )

        assertEquals(2, map.defectCount)
        assertTrue(map.isDefect(1, 1))
        assertTrue(map.isDefect(5, 5))
        assertFalse(map.isDefect(0, 0))

        // hotPixelMask carries exactly those two defects.
        val mask = map.toHotPixelMask()
        assertEquals(64, mask.size)
        assertEquals(1, mask[1 * 8 + 1].toInt())
        assertEquals(1, mask[5 * 8 + 5].toInt())
        assertEquals(2, mask.count { it.toInt() != 0 })
    }

    @Test
    fun emptyHotPixelListAndInvalidDimensionsYieldIdentity() {
        assertTrue(SensorDefectMapPolicy.fromHotPixelCoordinates(emptyList(), 8, 8).isIdentity)
        assertTrue(SensorDefectMapPolicy.fromHotPixelCoordinates(listOf(1 to 1), 0, 0).isIdentity)
        assertTrue(SensorDefectMapPolicy.fromDarkFrame(IntArray(0), 0, 0, black, white).isIdentity)
        assertTrue(SensorDefectMapPolicy.fromDarkFrame(IntArray(4), 64, 64, black, white).isIdentity)
    }

    @Test
    fun hotPixelAtOrAboveReportedWhiteLevelIsStillFlagged() {
        // A saturated stuck-on photosite that reads above a conservatively-reported white level is the
        // strongest hot-pixel evidence and must NOT be gated out by whiteLevel.
        val samples = IntArray(width * height) { black }
        samples[20 * width + 20] = 5000 // above whiteLevel = 4095
        val map = SensorDefectMapPolicy.fromDarkFrame(samples, width, height, black, white)

        assertTrue(map.isDefect(20, 20))
        assertEquals(1, map.defectCount)
    }

    @Test
    fun correctedSampleLeavesSampleUnchangedWhenFrameDimensionsMismatchMap() {
        val samples = darkFrame(10 to 10)
        val map = SensorDefectMapPolicy.fromDarkFrame(samples, width, height, black, white)
        assertTrue(map.isDefect(10, 10))

        // Same buffer queried with the wrong declared width → no correction (never mis-correct a wrong site).
        val mismatched = map.correctedSample(samples, 10, 10, width + 1, height)
        assertEquals(samples[10 * (width + 1) + 10], mismatched)
    }

    @Test
    fun identitySentinelNeverDefectsAndPassesSamplesThrough() {
        val samples = intArrayOf(5, 9, 13, 17)
        assertFalse(SensorDefectMap.IDENTITY.isDefect(0, 0))
        assertEquals(13, SensorDefectMap.IDENTITY.correctedSample(samples, 0, 1, 2, 2))
        assertTrue(SensorDefectMap.IDENTITY.toHotPixelMask().isEmpty())
        assertTrue(SensorDefectMap.identity(64, 64).isIdentity)
    }
}
