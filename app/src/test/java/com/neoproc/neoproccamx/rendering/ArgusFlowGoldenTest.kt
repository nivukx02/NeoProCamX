package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.rendering.ArgusFlowReference.BlockMatchParams
import com.neoproc.neoproccamx.rendering.ArgusFlowReference.Flow
import com.neoproc.neoproccamx.rendering.ArgusFlowReference.Plane
import com.neoproc.neoproccamx.rendering.ArgusFlowReference.RefineParams
import com.neoproc.neoproccamx.rendering.ArgusFlowReference.SelectParams
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP54 — host-side golden endpoint-error (EPE) and occlusion tests for the Argus optical-flow
 * pipeline, run against synthetic scenes with KNOWN ground-truth motion. These validate the flow
 * algorithm (block-match → refine → select) against physics, not against expectation, so a bug in
 * either the algorithm or the [ArgusFlowReference] port shows up as a high EPE on known translations.
 * Fully in-repo / no device required.
 */
class ArgusFlowGoldenTest {

    private val w = 56
    private val h = 56
    private val cell = 3f

    // Deterministic value-noise scene (no RNG): bilinear-smoothstep interpolation of a hash lattice.
    private fun hash(ix: Int, iy: Int): Float {
        var hsh = ix * -1640531527 + iy * 2654435761.toInt() + ix * iy * 1013904223
        hsh = hsh xor (hsh ushr 15)
        hsh *= -2048144789
        hsh = hsh xor (hsh ushr 13)
        return ((hsh ushr 8) and 0xFFFFFF).toFloat() / 0x1000000.toFloat()
    }

    private fun scene(x: Float, y: Float): Float {
        val gx = x / cell
        val gy = y / cell
        val ix = floor(gx).toInt()
        val iy = floor(gy).toInt()
        val tx = gx - ix
        val ty = gy - iy
        val sx = tx * tx * (3f - 2f * tx)
        val sy = ty * ty * (3f - 2f * ty)
        val a = hash(ix, iy)
        val b = hash(ix + 1, iy)
        val c = hash(ix, iy + 1)
        val d = hash(ix + 1, iy + 1)
        return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sy
    }

    private fun currentFrame(): Plane {
        val luma = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) luma[y * w + x] = scene(x.toFloat(), y.toFloat())
        return Plane(w, h, luma)
    }

    /** previous(q) = scene(q + fTrue) so that previous(p - fTrue) == current(p): forward flow == fTrue. */
    private fun previousFrame(fTrueX: Float, fTrueY: Float): Plane {
        val luma = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) luma[y * w + x] = scene(x + fTrueX, y + fTrueY)
        return Plane(w, h, luma)
    }

    private fun constFlow(fx: Float, fy: Float): Flow {
        val data = FloatArray(2 * w * h)
        for (i in 0 until w * h) { data[2 * i] = fx; data[2 * i + 1] = fy }
        return Flow(w, h, data)
    }

    /** Mean endpoint error over confident interior pixels; also returns the confident fraction. */
    private fun confidentEpe(
        flow: Flow,
        reliability: FloatArray,
        fTrueX: Float,
        fTrueY: Float,
        margin: Int,
        relThreshold: Float,
    ): Pair<Float, Float> {
        var sum = 0.0
        var count = 0
        var interior = 0
        for (y in margin until h - margin) for (x in margin until w - margin) {
            interior++
            if (reliability[y * w + x] < relThreshold) continue
            val ex = flow.fx(x, y) - fTrueX
            val ey = flow.fy(x, y) - fTrueY
            sum += hypot(ex.toDouble(), ey.toDouble())
            count++
        }
        val epe = if (count > 0) (sum / count).toFloat() else Float.NaN
        val fraction = count.toFloat() / interior.toFloat()
        return epe to fraction
    }

    @Test
    fun blockMatchRecoversKnownIntegerTranslation() {
        val fx = 3f
        val fy = -2f
        val res = ArgusFlowReference.blockMatch(currentFrame(), previousFrame(fx, fy), BlockMatchParams())
        val (epe, fraction) = confidentEpe(res.flow, res.reliability, fx, fy, margin = 12, relThreshold = 0.5f)
        assertTrue("confident-interior fraction too low: $fraction", fraction > 0.4f)
        assertTrue("integer-translation EPE too high: $epe", epe < 0.25f)
    }

    /** Mean brightness-constancy residual |previous(p - flow) - current(p)| over textured interior. */
    private fun warpResidual(
        current: Plane,
        previous: Plane,
        flow: Flow,
        margin: Int,
        texFloor: Float,
    ): Float {
        var sum = 0.0
        var n = 0
        for (y in margin until h - margin) for (x in margin until w - margin) {
            val tex = abs(current.at(x + 1, y) - current.at(x - 1, y)) + abs(current.at(x, y + 1) - current.at(x, y - 1))
            if (tex < texFloor) continue
            val warped = previous.bilinear(x - flow.fx(x, y), y - flow.fy(x, y))
            sum += abs(warped - current.at(x, y))
            n++
        }
        return if (n > 0) (sum / n).toFloat() else Float.NaN
    }

    /** Mean EPE over textured interior pixels (refine output is not reliability-gated). */
    private fun texturedEpe(
        current: Plane,
        flow: Flow,
        fTrueX: Float,
        fTrueY: Float,
        margin: Int,
        texFloor: Float,
    ): Float {
        var sum = 0.0
        var n = 0
        for (y in margin until h - margin) for (x in margin until w - margin) {
            val tex = abs(current.at(x + 1, y) - current.at(x - 1, y)) + abs(current.at(x, y + 1) - current.at(x, y - 1))
            if (tex < texFloor) continue
            sum += hypot((flow.fx(x, y) - fTrueX).toDouble(), (flow.fy(x, y) - fTrueY).toDouble())
            n++
        }
        return if (n > 0) (sum / n).toFloat() else Float.NaN
    }

    @Test
    fun refineReducesBrightnessConstancyResidualForSubPixelMotion() {
        val fx = 0.7f
        val fy = -0.5f
        val current = currentFrame()
        val previous = previousFrame(fx, fy)
        val zero = constFlow(0f, 0f)
        val refined = ArgusFlowReference.refine(
            current, previous, zero, RefineParams(regularization = 0.01f), iterations = 24,
        )
        val before = warpResidual(current, previous, zero, margin = 4, texFloor = 0.05f)
        val after = warpResidual(current, previous, refined, margin = 4, texFloor = 0.05f)
        // The variational refine must materially reduce the warp error (motion compensation works).
        assertTrue("refine should reduce warp residual: before=$before after=$after", after < 0.5f * before)
    }

    @Test
    fun refineRecoversSmallSubPixelTranslation() {
        val fx = 0.5f
        val fy = 0f
        val current = currentFrame()
        val previous = previousFrame(fx, fy)
        val refined = ArgusFlowReference.refine(
            current, previous, constFlow(0f, 0f), RefineParams(regularization = 0.01f), iterations = 32,
        )
        val epe = texturedEpe(current, refined, fx, fy, margin = 4, texFloor = 0.12f)
        assertTrue("sub-pixel EPE after refine too high: $epe", epe < 0.4f)
    }

    @Test
    fun blockMatchEmitsZeroFlowWhenNoPreviousFrame() {
        val res = ArgusFlowReference.blockMatch(
            currentFrame(),
            previousFrame(3f, -2f),
            BlockMatchParams(previousValid = false),
        )
        for (v in res.flow.data) assertEquals(0f, v, 0f)
    }

    @Test
    fun blockMatchEmitsNearZeroFlowOnIdenticalFrames() {
        val current = currentFrame()
        val res = ArgusFlowReference.blockMatch(current, current, BlockMatchParams())
        val (epe, _) = confidentEpe(res.flow, res.reliability, 0f, 0f, margin = 12, relThreshold = 0.0f)
        assertTrue("identical frames should yield ~zero flow, got EPE $epe", epe < 0.05f)
    }

    @Test
    fun refineImprovesGlobalMotionCompensation() {
        val fx = 3f
        val fy = -2f
        val current = currentFrame()
        val previous = previousFrame(fx, fy)
        // Block-match trusts only the exact-match (high-distinctiveness) pixels and zeros the rest; the
        // refine stage must propagate that motion into the un-trusted regions, lowering the overall
        // warp error rather than raising it. That improvement — not bit-exactness — is what "refine
        // helps" means in the hybrid design (select() then keeps refined only where it stays consistent).
        val seed = ArgusFlowReference.blockMatch(current, previous, BlockMatchParams())
        val refined = ArgusFlowReference.refine(current, previous, seed.flow, RefineParams(), iterations = 8)
        val seedResidual = warpResidual(current, previous, seed.flow, margin = 4, texFloor = 0.05f)
        val refinedResidual = warpResidual(current, previous, refined, margin = 4, texFloor = 0.05f)
        assertTrue(
            "refine must lower the global warp residual: seed=$seedResidual refined=$refinedResidual",
            refinedResidual < seedResidual,
        )
    }

    @Test
    fun selectUsesRefinedFlowWhenForwardBackwardConsistent() {
        val current = currentFrame()
        val forward = constFlow(4f, 0f)
        val backward = constFlow(-4f, 0f) // negates forward => residual 0 => consistent
        val coarse = constFlow(1f, 0f)
        val out = ArgusFlowReference.select(
            forward, backward, coarse, current,
            SelectParams(occlusionThreshold = 1.5f, minTexture = 0.001f),
        )
        // Over strongly-textured interior pixels (textureGate ~ 1) the refined forward flow wins.
        var sx = 0.0; var sy = 0.0; var n = 0
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val tex = abs(current.at(x + 1, y) - current.at(x - 1, y)) + abs(current.at(x, y + 1) - current.at(x, y - 1))
            if (tex < 0.05f) continue
            sx += out.fx(x, y); sy += out.fy(x, y); n++
        }
        assertTrue("need textured pixels", n > 200)
        assertEquals("consistent => uses forward fx", 4f, (sx / n).toFloat(), 0.25f)
        assertEquals("consistent => uses forward fy", 0f, (sy / n).toFloat(), 0.1f)
    }

    @Test
    fun selectFallsBackToCoarseFlowUnderOcclusion() {
        val current = currentFrame()
        val forward = constFlow(4f, 0f)
        val backward = constFlow(4f, 0f) // does NOT negate forward => residual 8 => occluded
        val coarse = constFlow(1f, 0f)
        val out = ArgusFlowReference.select(
            forward, backward, coarse, current,
            SelectParams(occlusionThreshold = 1.5f, minTexture = 0.001f),
        )
        // Inconsistent forward/backward => occlusion => fall back to the coarse Stage-A field everywhere.
        var sx = 0.0; var sy = 0.0; var n = 0
        for (y in 2 until h - 2) for (x in 2 until w - 2) { sx += out.fx(x, y); sy += out.fy(x, y); n++ }
        assertEquals("occlusion => uses coarse fx", 1f, (sx / n).toFloat(), 0.05f)
        assertEquals("occlusion => uses coarse fy", 0f, (sy / n).toFloat(), 0.05f)
    }

    @Test
    fun selectEmitsZeroWhenNoPreviousFrame() {
        val out = ArgusFlowReference.select(
            constFlow(4f, 0f), constFlow(-4f, 0f), constFlow(1f, 0f), currentFrame(),
            SelectParams(previousValid = false),
        )
        for (v in out.data) assertEquals(0f, v, 0f)
    }

    // ───────────────────── s3-f01 / CP54b — extended EPE coverage + scene cut ─────────────────────

    private val cx = (w - 1) / 2f
    private val cy = (h - 1) / 2f

    /** previous(q) = scene(R(theta)·(q − c) + c): a frame the camera rotated INTO by +theta about centre. */
    private fun previousFrameRotated(theta: Float): Plane {
        val ct = cos(theta)
        val st = sin(theta)
        val luma = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx
            val dy = y - cy
            val rx = ct * dx - st * dy
            val ry = st * dx + ct * dy
            luma[y * w + x] = scene(cx + rx, cy + ry)
        }
        return Plane(w, h, luma)
    }

    /** Analytic forward flow for [previousFrameRotated]: f(p) = (p−c) − R(−theta)·(p−c). */
    private fun rotationFlowAt(theta: Float, x: Int, y: Int): Pair<Float, Float> {
        val ct = cos(theta)
        val st = sin(theta)
        val dx = x - cx
        val dy = y - cy
        val rmx = ct * dx + st * dy   // R(−theta) applied to (dx,dy)
        val rmy = -st * dx + ct * dy
        return (dx - rmx) to (dy - rmy)
    }

    /** previous(q) = scene(c + s·(q − c)): a frame the camera zoomed INTO by factor s (divergent flow). */
    private fun previousFrameZoomed(scale: Float): Plane {
        val luma = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            luma[y * w + x] = scene(cx + scale * (x - cx), cy + scale * (y - cy))
        }
        return Plane(w, h, luma)
    }

    /** Analytic forward flow for [previousFrameZoomed]: f(p) = (p−c)·(1 − 1/s). */
    private fun zoomFlowAt(scale: Float, x: Int, y: Int): Pair<Float, Float> {
        val k = 1f - 1f / scale
        return ((x - cx) * k) to ((y - cy) * k)
    }

    /** Mean EPE of [flow] vs an analytic ground-truth field, plus the zero-flow baseline EPE, over textured interior. */
    private fun fieldEpeVsBaseline(
        current: Plane,
        flow: Flow,
        groundTruth: (Int, Int) -> Pair<Float, Float>,
        margin: Int,
        texFloor: Float,
    ): Pair<Float, Float> {
        var sum = 0.0
        var baseline = 0.0
        var n = 0
        for (y in margin until h - margin) for (x in margin until w - margin) {
            val tex = abs(current.at(x + 1, y) - current.at(x - 1, y)) + abs(current.at(x, y + 1) - current.at(x, y - 1))
            if (tex < texFloor) continue
            val (tx, ty) = groundTruth(x, y)
            sum += hypot((flow.fx(x, y) - tx).toDouble(), (flow.fy(x, y) - ty).toDouble())
            baseline += hypot(tx.toDouble(), ty.toDouble())
            n++
        }
        return (if (n > 0) (sum / n).toFloat() else Float.NaN) to (if (n > 0) (baseline / n).toFloat() else Float.NaN)
    }

    /** Full forward pipeline: coarse block-match → 8-iter refine → occlusion select (matches the live stack order). */
    private fun selectedFlow(current: Plane, previous: Plane): Flow {
        val coarse = ArgusFlowReference.blockMatch(current, previous, BlockMatchParams()).flow
        val forward = ArgusFlowReference.refine(current, previous, coarse, RefineParams(), iterations = 8)
        val backward = ArgusFlowReference.blockMatch(previous, current, BlockMatchParams()).flow
        return ArgusFlowReference.select(forward, backward, coarse, current, SelectParams())
    }

    @Test
    fun pipelineRecoversRotationFlowBeatingZeroBaseline() {
        val theta = 0.06f // ~3.4° in-plane rotation
        val current = currentFrame()
        val previous = previousFrameRotated(theta)
        val flow = selectedFlow(current, previous)
        val (epe, baseline) = fieldEpeVsBaseline(current, flow, { x, y -> rotationFlowAt(theta, x, y) }, margin = 6, texFloor = 0.08f)
        // The rotational field is non-trivial (a pure zero-flow guess scores `baseline`); the pipeline must
        // both beat that baseline and land under a tight EPE ceiling, proving it tracks rotation.
        assertTrue("rotation baseline must be non-trivial: $baseline", baseline > 0.3f)
        assertTrue("rotation EPE too high: $epe (baseline $baseline)", epe < 0.5f)
        assertTrue("rotation flow must beat the zero-flow baseline: epe=$epe baseline=$baseline", epe < 0.6f * baseline)
    }

    @Test
    fun pipelineRecoversZoomFlowBeatingZeroBaseline() {
        val scale = 1.05f // 5% divergent zoom
        val current = currentFrame()
        val previous = previousFrameZoomed(scale)
        val flow = selectedFlow(current, previous)
        val (epe, baseline) = fieldEpeVsBaseline(current, flow, { x, y -> zoomFlowAt(scale, x, y) }, margin = 6, texFloor = 0.08f)
        assertTrue("zoom baseline must be non-trivial: $baseline", baseline > 0.25f)
        assertTrue("zoom EPE too high: $epe (baseline $baseline)", epe < 0.5f)
        assertTrue("zoom flow must beat the zero-flow baseline: epe=$epe baseline=$baseline", epe < 0.6f * baseline)
    }

    @Test
    fun refineResidualIsNonIncreasingAcrossIterations() {
        // Brightness-constancy convergence proof: the warp residual must not rise as refine iterations
        // accumulate (Horn-Schunck/Jacobi convergence on a non-degenerate scene), and must net-decrease.
        val fx = 1.5f
        val fy = -1.0f
        val current = currentFrame()
        val previous = previousFrame(fx, fy)
        val seed = ArgusFlowReference.blockMatch(current, previous, BlockMatchParams()).flow
        val schedule = intArrayOf(1, 2, 3, 4, 6, 8)
        var prev = Float.MAX_VALUE
        val residuals = schedule.map { iters ->
            val refined = ArgusFlowReference.refine(current, previous, seed, RefineParams(), iterations = iters)
            val r = warpResidual(current, previous, refined, margin = 4, texFloor = 0.05f)
            assertTrue("residual rose at iters=$iters: $prev -> $r", r <= prev + 1e-4f)
            prev = r
            r
        }
        assertTrue("refine must net-reduce the residual: ${residuals.first()} -> ${residuals.last()}", residuals.last() < residuals.first())
    }

    @Test
    fun sceneCutResidualStraddlesFastPanAndHardCut() {
        val current = currentFrame()
        // Fast pan: a large but coherent translation the flow fully reconciles -> small residual.
        val pan = previousFrame(6f, 0f)
        val panResidual = warpResidual(current, pan, selectedFlow(current, pan), margin = 6, texFloor = 0.05f)
        // Hard cut: the frame is fully replaced with unrelated high-contrast content (lens covered then
        // uncovered onto a different scene) — no flow can reconcile a smooth field onto it -> large residual.
        val cutLuma = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) cutLuma[y * w + x] = if (((x / 2) + (y / 2)) % 2 == 0) 0.05f else 0.95f
        val cut = Plane(w, h, cutLuma)
        val cutResidual = warpResidual(current, cut, selectedFlow(current, cut), margin = 6, texFloor = 0.05f)

        // The post-flow residual cleanly discriminates a cut from a pan...
        assertTrue("cut residual must dominate pan residual: pan=$panResidual cut=$cutResidual", cutResidual > 1.5f * panResidual)
        // ...and the default threshold sits between them, so it never false-triggers on a fast pan.
        val t = ArgusFlowStagePolicy.SCENE_CUT_RESIDUAL_THRESHOLD
        assertTrue("fast pan must stay below the cut threshold: pan=$panResidual t=$t", panResidual < t)
        assertTrue("hard cut must exceed the cut threshold: cut=$cutResidual t=$t", cutResidual > t)
        assertFalse("fast pan must not be flagged as a cut", ArgusFlowStagePolicy.isSceneCutSpike(panResidual))
        assertTrue("hard cut must be flagged", ArgusFlowStagePolicy.isSceneCutSpike(cutResidual))
    }
}
