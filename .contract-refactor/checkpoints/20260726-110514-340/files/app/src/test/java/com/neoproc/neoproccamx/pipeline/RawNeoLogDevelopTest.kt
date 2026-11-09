package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.NeoLogCurve
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Golden contract for the RAW → NeoLog develop core. Locks the develop MATH (black point, the
 * 18%-gray → NeoLog GRAY_18_CODE anchor, Bayer channel routing, exposure compensation) so the
 * offline converter's look is reproducible and the GPU acceleration tiers have a reference to match.
 * A row-major identity colour matrix is used so the color transform is a no-op and the tests assert
 * the transfer / demosaic / exposure behaviour directly.
 */
class RawNeoLogDevelopTest {

    @Before
    fun lockPureDevelopMath() {
        // Golden-lock the pure develop MATH: disable the default-ON grade-safe elite-detail pass so these
        // assertions measure transfer / demosaic / exposure only. The detail pass has its own coverage in
        // RawDevelopEliteDetailPolicyTest; with it OFF the develop is byte-identical to the prior behaviour.
        RawDevelopEliteDetailFlag.set(false)
    }

    @After
    fun restoreEliteDetailDefault() {
        RawDevelopEliteDetailFlag.set(true) // restore the production default; never leak across classes
    }

    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val base = NeoLogCurve.Variant.BASE

    private fun develop(
        mosaic: IntArray,
        w: Int,
        h: Int,
        black: Int = 0,
        white: Int = 1000,
        bayer: Int = 0,
        wb: FloatArray = floatArrayOf(1f, 1f, 1f, 1f),
        exposureStops: Float = 0f
    ) = RawNeoLogDevelop.developWithMatrix(
        rawSamples = mosaic,
        width = w,
        height = h,
        blackLevel = black,
        whiteLevel = white,
        bayerValue = bayer,
        wbR = wb[0], wbGr = wb[1], wbGb = wb[2], wbB = wb[3],
        sensorToAcescg = identity,
        variant = base,
        exposureStops = exposureStops,
        outputBitDepth = 10
    )

    @Test
    fun blackLevelDevelopsToZeroCodes() {
        val f = develop(IntArray(16) { 0 }, 4, 4, black = 0, white = 1000)
        f.rgb.forEach { assertEquals(0, it.toInt()) }
    }

    @Test
    fun midGrayDevelopsToTheNeoLog18PercentAnchorCode() {
        // 18% of the black→white range must land on NeoLog GRAY_18_CODE (0.50) → ~mid of the 10-bit range.
        val gray = (NeoLogCurve.SCENE_GRAY_18 * 1000f).toInt() // = 180 with black=0, white=1000
        val f = develop(IntArray(16) { gray }, 4, 4, black = 0, white = 1000)
        val expected = (NeoLogCurve.GRAY_18_CODE * NeoLogCurve.quantize(1f, base, 10)) // sanity anchor
        // Every developed code should sit at the 18%-gray anchor (allow ±3 for quantise rounding).
        f.rgb.forEach {
            assertTrue("expected ~512 got ${it.toInt()}", it.toInt() in 508..515)
        }
        assertTrue(expected > 0)
    }

    @Test
    fun bayerRoutingIsolatesTheRedChannel() {
        // RGGB (bayer=0): light ONLY the R sites; every developed pixel must carry R>0 and G=B=0.
        val w = 4; val h = 4
        val mosaic = IntArray(w * h) { 0 }
        // RGGB red sites = even row, even col.
        for (y in 0 until h step 2) for (x in 0 until w step 2) mosaic[y * w + x] = 900
        val f = develop(mosaic, w, h, black = 0, white = 1000, bayer = 0)
        for (p in 0 until w * h) {
            val o = p * 3
            assertTrue("R must be lit at pixel $p", f.rgb[o].toInt() > 0)
            assertEquals("G must be zero at pixel $p", 0, f.rgb[o + 1].toInt())
            assertEquals("B must be zero at pixel $p", 0, f.rgb[o + 2].toInt())
        }
    }

    @Test
    fun exposureCompensationBrightensTheDevelopedCode() {
        val gray = (NeoLogCurve.SCENE_GRAY_18 * 1000f).toInt()
        val neutral = develop(IntArray(16) { gray }, 4, 4).rgb[0].toInt()
        val pushed = develop(IntArray(16) { gray }, 4, 4, exposureStops = 1f).rgb[0].toInt()
        assertTrue("expected +1 stop ($pushed) brighter than neutral ($neutral)", pushed > neutral)
    }

    @Test
    fun publicDevelopComposesMatrixFromSidecarMetadata() {
        // The default sensor→XYZ (inverse of XYZ_D50_TO_ACESCG) composes to an identity sensor→ACEScg,
        // so a neutral gray mosaic stays neutral through the full public path.
        val gray = (NeoLogCurve.SCENE_GRAY_18 * 1000f).toInt()
        val meta = RawDevelopMetadataSidecar.RawDevelopMetadata(
            width = 4, height = 4,
            storageBitDepth = 12, sensorWhiteLevel = 1000,
            blackLevel = 0, whiteLevel = 1000,
            bayerPatternValue = 0,
            whiteBalanceR = 1f, whiteBalanceGr = 1f, whiteBalanceGb = 1f, whiteBalanceB = 1f,
            exposureTimeNs = 0L, analogGain = 1f, digitalGain = 1f, noiseS = 0f, noiseO = 0f,
            sensorToXyz = com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ,
            fps = 30, colorProfileTag = "N-LOG-BASE"
        )
        val f = RawNeoLogDevelop.develop(IntArray(16) { gray }, meta)
        assertEquals(4, f.width)
        assertEquals(10, f.bitDepth)
        // Identity color transform → neutral gray → all three channels near the 18% anchor.
        val o = (5) * 3 // an interior pixel
        listOf(f.rgb[o].toInt(), f.rgb[o + 1].toInt(), f.rgb[o + 2].toInt()).forEach {
            assertTrue("expected ~512 got $it", it in 505..518)
        }
    }
}
