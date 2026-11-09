package com.neoproc.neoproccamx.stabilization

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP-QV19 — host-golden lock for the dormant GPU EIS warp shader `eis_warp.comp` via the faithful
 * Kotlin port [EisWarpShaderReference]. TESTS ONLY: no production file is touched.
 *
 * Pairs with the CP-QV18 CPU-grid goldens ([EisPipelineFoundationGoldenTest]) so the full gyro-EIS
 * chain is foundation-locked before device-side activation:
 *   CP-QV18 locks the CPU producer that BUILDS the per-scanline (dx, dy, roll) grid;
 *   CP-QV19 (here) locks the GPU shader that CONSUMES that packed grid to resample pixels.
 *
 * Locks the shader's three load-bearing invariants, exactly as transcribed from eis_warp.comp:
 *   1. USE_EIS_WARP == false  => byte-identical passthrough (no-downgrade).
 *   2. A known grid warps as analytically expected — a uniform translation shifts the sampled image by
 *      +(dx, dy) (bilinear), and a small constant roll rotates sample coords about the configured center.
 *   3. Never-reveal-border — a grid whose correction would fetch outside [lo, hi] is clamped so every
 *      output pixel came from an in-bounds, on-margin coordinate; no black/OOB border.
 */
class EisWarpShaderGoldenTest {

    private val w = 48
    private val h = 32
    private val centerX = (w - 1) / 2f // 23.5
    private val centerY = (h - 1) / 2f // 15.5

    /** Smooth diagonal gradient so any (dx, dy) shift or roll is detectable pixel-by-pixel. */
    private fun gradient(): FloatArray = FloatArray(w * h) { i ->
        val x = i % w
        val y = i / w
        (0.15f + 0.6f * (x / (w - 1f)) + 0.2f * (y / (h - 1f)))
    }

    /** A checkerboard so translations produce a visibly different (not near-constant) field. */
    private fun checker(): FloatArray = FloatArray(w * h) { i ->
        val x = i % w
        val y = i / w
        if (((x / 4) + (y / 4)) % 2 == 0) 0.85f else 0.15f
    }

    /** Pack a per-row uniform (dx, dy, roll) into the rgba grid the shader samples. */
    private fun uniformGrid(dx: Float, dy: Float, roll: Float, rows: Int): FloatArray {
        val g = FloatArray(rows * 4)
        for (r in 0 until rows) {
            g[r * 4] = dx
            g[r * 4 + 1] = dy
            g[r * 4 + 2] = roll
            g[r * 4 + 3] = 1f
        }
        return g
    }

    // ---------------------------------------------------------------------------------------------
    // PROPERTY 1 — USE_EIS_WARP == false => byte-identical passthrough (no-downgrade default).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun eisWarpOffIsByteIdenticalPassthrough() {
        val input = gradient()
        // Even with a large, non-trivial grid loaded, OFF must ignore it entirely (identity resample).
        val loadedGrid = uniformGrid(dx = 7.3f, dy = -4.1f, roll = 0.08f, rows = h)
        val out = EisWarpShaderReference.warp(
            input = input, width = w, height = h,
            useEisWarp = false,
            packedGrid = loadedGrid, gridRows = h,
            cropMarginPixels = 4f, centerX = centerX, centerY = centerY,
        )
        assertEquals("output size must equal input size", input.size, out.size)
        for (i in input.indices) {
            // Exactly equal (0f eps) — the shader does imageStore(out, p, imageLoad(in, p)).
            assertEquals("OFF must passthrough pixel $i bit-for-bit", input[i], out[i], 0f)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // PROPERTY 2a — a uniform translation grid shifts the sampled image by exactly +(dx, dy).
    // ---------------------------------------------------------------------------------------------

    @Test
    fun uniformIntegerTranslationShiftsSampledImageByExactlyDxDy() {
        val input = checker()
        val dx = 4f
        val dy = 3f // integer shift + roll 0 => bilinear is exact, no interpolation error
        val margin = 0f // no clamp interference in the interior
        val grid = uniformGrid(dx, dy, roll = 0f, rows = h)
        val out = EisWarpShaderReference.warp(
            input = input, width = w, height = h,
            useEisWarp = true,
            packedGrid = grid, gridRows = h,
            cropMarginPixels = margin, centerX = centerX, centerY = centerY,
        )
        // src = p - (dx, dy) => out[p] == input[p - (dx, dy)] wherever p-(dx,dy) is in-bounds (interior).
        var checked = 0
        for (py in dy.toInt() until h) {
            for (px in dx.toInt() until w) {
                val expected = input[(py - dy.toInt()) * w + (px - dx.toInt())]
                assertEquals(
                    "interior pixel ($px,$py) must be source shifted by +($dx,$dy)",
                    expected, out[py * w + px], 1e-4f,
                )
                checked++
            }
        }
        assertTrue("must have checked a real interior region", checked > 100)
    }

    @Test
    fun uniformFractionalTranslationMatchesAnalyticBilinearSampleOfSource() {
        val input = gradient() // smooth => fractional bilinear is well-behaved & analytically comparable
        val dx = 5.5f
        val dy = -2.25f
        val margin = 0f
        val grid = uniformGrid(dx, dy, roll = 0f, rows = h)
        val out = EisWarpShaderReference.warp(
            input = input, width = w, height = h,
            useEisWarp = true,
            packedGrid = grid, gridRows = h,
            cropMarginPixels = margin, centerX = centerX, centerY = centerY,
        )
        // Independent analytic bilinear of the source at (px-dx, py-dy) in a strictly-interior region
        // (kept away from edges so no clamp is engaged and the +1 neighbour never clamps either).
        var checked = 0
        for (py in 4 until h - 4) {
            for (px in 8 until w - 8) {
                val expected = analyticBilinear(input, px - dx, py - dy)
                assertEquals(
                    "interior pixel ($px,$py) must equal analytic bilinear at (px-dx, py-dy)",
                    expected, out[py * w + px], 1e-4f,
                )
                checked++
            }
        }
        assertTrue("must have checked a real interior region", checked > 100)
    }

    // ---------------------------------------------------------------------------------------------
    // PROPERTY 2b — a small constant roll rotates the sample coordinate about the configured center.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun constantRollRotatesSampleCoordinateAboutCenterAsExpected() {
        val input = gradient()
        val roll = 0.05f // ~2.9°: small enough that the rotated fetch stays interior for center pixels
        val margin = 0f
        val grid = uniformGrid(dx = 0f, dy = 0f, roll = roll, rows = h)
        val cr = cos(roll.toDouble()).toFloat()
        val sr = sin(roll.toDouble()).toFloat()

        // (i) The reference's src coordinate must equal the exact shader rotation formula about center.
        for (py in 8 until h - 8) {
            for (px in 12 until w - 12) {
                val src = EisWarpShaderReference.sourceCoord(
                    px, py, w, h, grid, gridRows = h, cropMarginPixels = margin,
                    centerX = centerX, centerY = centerY,
                )
                val dX = px - centerX
                val dY = py - centerY
                val expX = cr * dX - sr * dY + centerX
                val expY = sr * dX + cr * dY + centerY // dx=dy=0 => no translation after rotation
                assertEquals("rotated src.x at ($px,$py)", expX, src.x, 1e-4f)
                assertEquals("rotated src.y at ($px,$py)", expY, src.y, 1e-4f)
            }
        }

        // (ii) The full warp output must equal the analytic bilinear of the source at that rotated coord.
        val out = EisWarpShaderReference.warp(
            input = input, width = w, height = h,
            useEisWarp = true, packedGrid = grid, gridRows = h,
            cropMarginPixels = margin, centerX = centerX, centerY = centerY,
        )
        for (py in 8 until h - 8) {
            for (px in 12 until w - 12) {
                val dX = px - centerX
                val dY = py - centerY
                val srcX = cr * dX - sr * dY + centerX
                val srcY = sr * dX + cr * dY + centerY
                assertEquals(
                    "rolled output at ($px,$py)",
                    analyticBilinear(input, srcX, srcY), out[py * w + px], 1e-4f,
                )
            }
        }

        // (iii) The center pixel is the rotation pivot => it is (essentially) unchanged by pure roll.
        // centerX/centerY are on a half-pixel grid, so use the bilinear source at the (fixed) center.
        val cpx = centerX.toInt()
        val cpy = centerY.toInt()
        assertEquals(
            "center-ish pixel must be near the roll pivot (barely moved)",
            analyticBilinear(input, cpx.toFloat(), cpy.toFloat()),
            out[cpy * w + cpx],
            5e-3f,
        )
    }

    // ---------------------------------------------------------------------------------------------
    // PROPERTY 3 — never-reveal-border: an over-large correction is clamped to [lo, hi]; no OOB/black.
    // ---------------------------------------------------------------------------------------------

    @Test
    fun overLargeCorrectionIsClampedInsideMarginAndNeverRevealsBorder() {
        val input = gradient()
        val margin = 6f
        // A correction so large every fetch would leave the frame if not clamped (dx >> width).
        val grid = uniformGrid(dx = 500f, dy = -500f, roll = 0.6f, rows = h)
        val m = margin
        val loX = m; val loY = m
        val hiX = maxOf(w - 1f - m, loX) // 41
        val hiY = maxOf(h - 1f - m, loY) // 25

        // Every output pixel's SOURCE coordinate must be inside [lo, hi] on both axes (clamped, in-bounds).
        for (py in 0 until h) {
            for (px in 0 until w) {
                val src = EisWarpShaderReference.sourceCoord(
                    px, py, w, h, grid, gridRows = h, cropMarginPixels = margin,
                    centerX = centerX, centerY = centerY,
                )
                assertTrue(
                    "src.x=${src.x} at ($px,$py) escaped clamp [$loX,$hiX]",
                    src.x in (loX - 1e-4f)..(hiX + 1e-4f),
                )
                assertTrue(
                    "src.y=${src.y} at ($px,$py) escaped clamp [$loY,$hiY]",
                    src.y in (loY - 1e-4f)..(hiY + 1e-4f),
                )
            }
        }

        // And the resampled output can only contain values that exist in the input (no black/zero border
        // introduced) — every output pixel is a bilinear blend of in-bounds interior source pixels.
        val out = EisWarpShaderReference.warp(
            input = input, width = w, height = h,
            useEisWarp = true, packedGrid = grid, gridRows = h,
            cropMarginPixels = margin, centerX = centerX, centerY = centerY,
        )
        val inMin = input.min()
        val inMax = input.max()
        for (i in out.indices) {
            assertTrue(
                "output[$i]=${out[i]} outside input range [$inMin,$inMax] — a border must have been revealed",
                out[i] in (inMin - 1e-4f)..(inMax + 1e-4f),
            )
        }
    }

    @Test
    fun marginLargerThanFrameCollapsesClampToLowBoundNotNegative() {
        // Degenerate crop margin bigger than the frame: hi = max(size-1-m, lo) must collapse to lo (=m),
        // NEVER produce hi < lo (which would invert the clamp / read OOB). Locks the shader's max() guard.
        val margin = 1000f
        val grid = uniformGrid(dx = 3f, dy = 3f, roll = 0.1f, rows = h)
        for (py in 0 until h) {
            for (px in 0 until w) {
                val src = EisWarpShaderReference.sourceCoord(
                    px, py, w, h, grid, gridRows = h, cropMarginPixels = margin,
                    centerX = centerX, centerY = centerY,
                )
                // lo == hi == margin on both axes => src is pinned to (margin, margin).
                assertEquals("collapsed src.x at ($px,$py)", margin, src.x, 0f)
                assertEquals("collapsed src.y at ($px,$py)", margin, src.y, 0f)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    /** Independent reference bilinear (matches the shader's clamp-to-[0,size-1] + clamped +1 neighbour). */
    private fun analyticBilinear(img: FloatArray, cx: Float, cy: Float): Float {
        val clX = cx.coerceIn(0f, w - 1f)
        val clY = cy.coerceIn(0f, h - 1f)
        val bx = floor(clX).toInt()
        val by = floor(clY).toInt()
        val fx = clX - bx
        val fy = clY - by
        val bx1 = minOf(bx + 1, w - 1)
        val by1 = minOf(by + 1, h - 1)
        val a = img[by * w + bx]
        val b = img[by * w + bx1]
        val c = img[by1 * w + bx]
        val d = img[by1 * w + bx1]
        val top = a + (b - a) * fx
        val bot = c + (d - c) * fx
        return top + (bot - top) * fy
    }

    /** Sanity: my analytic bilinear equals the reference's own sampleInputBilinear (same math). */
    @Test
    fun analyticBilinearAgreesWithReferenceSampler() {
        val input = gradient()
        val coords = listOf(2.3f to 4.7f, 10.0f to 6.0f, 30.5f to 18.25f, -3f to 40f, 100f to 100f)
        for ((cx, cy) in coords) {
            assertEquals(
                "sampler mismatch at ($cx,$cy)",
                analyticBilinear(input, cx, cy),
                EisWarpShaderReference.sampleInputBilinear(input, w, h, cx, cy),
                0f,
            )
        }
        // touch abs so unused-import guards never complain if the file is trimmed later
        assertTrue(abs(-1f) == 1f)
    }
}
