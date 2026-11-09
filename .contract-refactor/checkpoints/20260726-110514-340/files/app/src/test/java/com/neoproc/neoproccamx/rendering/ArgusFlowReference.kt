package com.neoproc.neoproccamx.rendering

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * CP54 — host-side faithful reference of the OmniClarity §2 Argus hybrid optical-flow pipeline
 * (argus_flow_blockmatch.comp → argus_flow_refine.comp → argus_flow_select.comp).
 *
 * This is a JVM oracle used by [ArgusFlowGoldenTest] to validate the flow algorithm against
 * synthetic ground-truth motion (endpoint error / occlusion handling) WITHOUT a device. It ports the
 * shader math line-for-line. Frames are represented as single-channel luma planes: the shaders load
 * RGB and take luma = dot(rgb, (0.2126,0.7152,0.0722)); for a gray scene (r=g=b=luma) that is exactly
 * the luma plane, and luma-of-bilinear == bilinear-of-luma, so operating on luma directly is identical
 * to the shader for the gray inputs the golden tests use.
 *
 * Convention (matches the shaders): forward flow f(p) satisfies previous(p - f(p)) ≈ current(p).
 */
object ArgusFlowReference {

    /** A w×h single-channel luma image, row-major, values typically in [0,1]. */
    class Plane(val width: Int, val height: Int, val luma: FloatArray) {
        init { require(luma.size == width * height) }
        fun at(x: Int, y: Int): Float {
            val cx = x.coerceIn(0, width - 1)
            val cy = y.coerceIn(0, height - 1)
            return luma[cy * width + cx]
        }
        /** Bilinear sample with edge clamp (mirrors previousLumaBilinear / bilinearSample). */
        fun bilinear(fx: Float, fy: Float): Float {
            val bx = floor(fx).toInt()
            val by = floor(fy).toInt()
            val tx = fx - bx
            val ty = fy - by
            val a = at(bx, by)
            val b = at(bx + 1, by)
            val c = at(bx, by + 1)
            val d = at(bx + 1, by + 1)
            return lerp(lerp(a, b, tx), lerp(c, d, tx), ty)
        }
    }

    /** A w×h flow field; rg stored interleaved (fx,fy) per pixel. */
    class Flow(val width: Int, val height: Int, val data: FloatArray) {
        init { require(data.size == 2 * width * height) }
        fun fx(x: Int, y: Int): Float = data[2 * (y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1))]
        fun fy(x: Int, y: Int): Float = data[2 * (y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)) + 1]
    }

    data class BlockMatchParams(
        val searchRadius: Int = 8,
        val coarseStep: Int = 3,
        val patchRadius: Int = 2,
        val maxFlow: Float = 16f,
        val previousValid: Boolean = true,
        val minTexture: Float = 0.02f,
    )

    data class RefineParams(
        val regularization: Float = 0.05f,
        val maxFlow: Float = 16f,
        val previousValid: Boolean = true,
        val edgeStopSigma: Float = 0.01f,
    )

    data class SelectParams(
        val occlusionThreshold: Float = 1.5f,
        val minTexture: Float = 0.02f,
        val maxFlow: Float = 16f,
        val previousValid: Boolean = true,
    )

    /** Per-pixel reliability returned alongside the block-match flow (distinct × textureGate). */
    class BlockMatchResult(val flow: Flow, val reliability: FloatArray)

    // ─────────────────────────── Stage A: classical block matching ───────────────────────────
    fun blockMatch(current: Plane, previous: Plane, params: BlockMatchParams): BlockMatchResult {
        val w = current.width
        val h = current.height
        val flow = FloatArray(2 * w * h)
        val reliability = FloatArray(w * h)
        if (!params.previousValid) return BlockMatchResult(Flow(w, h, flow), reliability)

        val r = maxOf(params.patchRadius, 1)
        val bigR = maxOf(params.searchRadius, 1)
        val step = maxOf(params.coarseStep, 1)

        fun patchSad(px: Int, py: Int, dx: Int, dy: Int): Float {
            var s = 0f
            for (oy in -r..r) for (ox in -r..r) {
                s += abs(current.at(px + ox, py + oy) - previous.at(px - dx + ox, py - dy + oy))
            }
            return s
        }

        for (py in 0 until h) for (px in 0 until w) {
            val gx = abs(current.at(px + 1, py) - current.at(px - 1, py))
            val gy = abs(current.at(px, py + 1) - current.at(px, py - 1))
            val texture = gx + gy

            var best = patchSad(px, py, 0, 0)
            var secondBest = best
            var bestDx = 0
            var bestDy = 0
            var dy = -bigR
            while (dy <= bigR) {
                var dx = -bigR
                while (dx <= bigR) {
                    if (!(dx == 0 && dy == 0)) {
                        val sad = patchSad(px, py, dx, dy)
                        if (sad < best) { secondBest = best; best = sad; bestDx = dx; bestDy = dy }
                        else if (sad < secondBest) { secondBest = sad }
                    }
                    dx += step
                }
                dy += step
            }

            // Fine local search around the coarse winner.
            for (fy in (-step + 1)..(step - 1)) for (fx in (-step + 1)..(step - 1)) {
                val dxC = bestDx + fx
                val dyC = bestDy + fy
                if (abs(dxC) > bigR || abs(dyC) > bigR) continue
                val sad = patchSad(px, py, dxC, dyC)
                if (sad < best) { secondBest = best; best = sad; bestDx = dxC; bestDy = dyC }
            }

            var flowX = bestDx.toFloat()
            var flowY = bestDy.toFloat()

            // 3-point parabola sub-pixel refinement per axis.
            val centerSad = best
            val leftSad = patchSad(px, py, bestDx - 1, bestDy)
            val rightSad = patchSad(px, py, bestDx + 1, bestDy)
            val denomX = leftSad - 2f * centerSad + rightSad
            if (abs(denomX) > 1e-5f) flowX += (0.5f * (leftSad - rightSad) / denomX).coerceIn(-0.5f, 0.5f)
            val upSad = patchSad(px, py, bestDx, bestDy - 1)
            val downSad = patchSad(px, py, bestDx, bestDy + 1)
            val denomY = upSad - 2f * centerSad + downSad
            if (abs(denomY) > 1e-5f) flowY += (0.5f * (upSad - downSad) / denomY).coerceIn(-0.5f, 0.5f)

            val distinct = if (secondBest > 1e-5f) (1f - best / secondBest).coerceIn(0f, 1f) else 0f
            val textureGate = smoothstep(params.minTexture, params.minTexture * 4f + 1e-4f, texture)
            val rel = distinct * textureGate
            flowX *= rel
            flowY *= rel

            val mag = hypot(flowX.toDouble(), flowY.toDouble()).toFloat()
            if (mag > params.maxFlow && mag > 1e-5f) {
                flowX *= params.maxFlow / mag
                flowY *= params.maxFlow / mag
            }

            val idx = py * w + px
            flow[2 * idx] = flowX
            flow[2 * idx + 1] = flowY
            reliability[idx] = rel
        }
        return BlockMatchResult(Flow(w, h, flow), reliability)
    }

    // ─────────────── Stage B: variational (edge-aware Horn-Schunck Jacobi) refinement ───────────────
    fun refine(current: Plane, previous: Plane, flowIn: Flow, params: RefineParams, iterations: Int): Flow {
        val w = current.width
        val h = current.height
        var inData = flowIn.data
        val zero = Flow(w, h, FloatArray(2 * w * h))
        if (!params.previousValid) return zero

        repeat(maxOf(iterations, 1)) {
            val src = Flow(w, h, inData)
            val out = FloatArray(2 * w * h)
            for (py in 0 until h) for (px in 0 until w) {
                val fX = src.fx(px, py)
                val fY = src.fy(px, py)
                val centerLuma = current.at(px, py)
                val offsets = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
                var wfX = 0f; var wfY = 0f; var wSum = 0f
                for (o in offsets) {
                    val dl = current.at(px + o[0], py + o[1]) - centerLuma
                    val wgt = exp((-(dl * dl) / maxOf(params.edgeStopSigma, 1e-4f)).toDouble()).toFloat()
                    wfX += src.fx(px + o[0], py + o[1]) * wgt
                    wfY += src.fy(px + o[0], py + o[1]) * wgt
                    wSum += wgt
                }
                val fbarX = if (wSum > 1e-5f) wfX / wSum else fX
                val fbarY = if (wSum > 1e-5f) wfY / wSum else fY

                val sx = px - fX
                val sy = py - fY
                val warped = previous.bilinear(sx, sy)
                val residual = warped - centerLuma
                val gx = 0.5f * (previous.bilinear(sx + 1f, sy) - previous.bilinear(sx - 1f, sy))
                val gy = 0.5f * (previous.bilinear(sx, sy + 1f) - previous.bilinear(sx, sy - 1f))

                val numerator = residual + (gx * (fX - fbarX) + gy * (fY - fbarY))
                val denom = params.regularization + (gx * gx + gy * gy) + 1e-6f
                var refX = fbarX + gx * (numerator / denom)
                var refY = fbarY + gy * (numerator / denom)

                val mag = hypot(refX.toDouble(), refY.toDouble()).toFloat()
                if (mag > params.maxFlow && mag > 1e-5f) {
                    refX *= params.maxFlow / mag
                    refY *= params.maxFlow / mag
                }
                val idx = py * w + px
                out[2 * idx] = refX
                out[2 * idx + 1] = refY
            }
            inData = out
        }
        return Flow(w, h, inData)
    }

    // ─────────────── Occlusion + hybrid selection ───────────────
    fun select(
        forward: Flow,
        backward: Flow,
        coarse: Flow,
        current: Plane,
        params: SelectParams,
    ): Flow {
        val w = current.width
        val h = current.height
        val out = FloatArray(2 * w * h)
        if (!params.previousValid) return Flow(w, h, out)

        for (py in 0 until h) for (px in 0 until w) {
            val fwdX = forward.fx(px, py)
            val fwdY = forward.fy(px, py)
            val coarseX = coarse.fx(px, py)
            val coarseY = coarse.fy(px, py)

            val srcX = (px - fwdX).roundToInt().coerceIn(0, w - 1)
            val srcY = (py - fwdY).roundToInt().coerceIn(0, h - 1)
            val bwdX = backward.fx(srcX, srcY)
            val bwdY = backward.fy(srcX, srcY)
            val residual = hypot((fwdX + bwdX).toDouble(), (fwdY + bwdY).toDouble()).toFloat()
            val consistent = 1f - smoothstep(params.occlusionThreshold, params.occlusionThreshold * 2f + 1e-4f, residual)

            val gx = abs(current.at(px + 1, py) - current.at(px - 1, py))
            val gy = abs(current.at(px, py + 1) - current.at(px, py - 1))
            val texture = gx + gy
            val textureGate = smoothstep(params.minTexture, params.minTexture * 4f + 1e-4f, texture)

            val useRefined = consistent * textureGate
            var finalX = lerp(coarseX, fwdX, useRefined)
            var finalY = lerp(coarseY, fwdY, useRefined)

            val mag = hypot(finalX.toDouble(), finalY.toDouble()).toFloat()
            if (mag > params.maxFlow && mag > 1e-5f) {
                finalX *= params.maxFlow / mag
                finalY *= params.maxFlow / mag
            }
            val idx = py * w + px
            out[2 * idx] = finalX
            out[2 * idx + 1] = finalY
        }
        return Flow(w, h, out)
    }

    // ─────────────────────────── helpers ───────────────────────────
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
