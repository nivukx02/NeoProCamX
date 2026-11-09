package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.rendering.GpuSignalFamily
import com.neoproc.neoproccamx.rendering.NdrRecordEnhancementCapsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Locks [Npv10ExportEnhancementPolicy.enhanceLiveCinemaLumaP010] — the CPU port of the live cinema record
 * shader's edge-aware bilateral denoise + halo-guarded unsharp used by the SDR/NDR develop converter for
 * WYSIWYG parity with the cinema camera.
 *
 * Proves it: (1) REDUCES flat-region luma grain (denoise) instead of amplifying it like the legacy
 * lift+Apex stack; (2) leaves CHROMA byte-untouched (luma-only); (3) caps edge overshoot near the shader's
 * ±(0.05 + 0.05·amt) bound so there are no runaway halos while still steepening a real edge;
 * (4) is deterministic and returns a FRESH array (the source stays pristine as the temporal prior).
 */
class RecordDevelopLiveCinemaLumaParityTest {

    private val shift = 6

    private fun putCode(out: ByteArray, offset: Int, code: Int) {
        val v = (code.coerceIn(0, 1023) and 0x3FF) shl shift
        out[offset] = (v and 0xFF).toByte()
        out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun readCode(buf: ByteArray, offset: Int): Int {
        val v = (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)
        return (v ushr shift) and 0x3FF
    }

    private fun buildP010(
        width: Int,
        height: Int,
        luma: (x: Int, y: Int) -> Int,
        chroma: (cx: Int, cy: Int) -> Pair<Int, Int> = { _, _ -> 512 to 512 }
    ): ByteArray {
        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        val lumaBytes = width * height * 2
        val out = ByteArray(lumaBytes + chromaW * chromaH * 2 * 2)
        for (y in 0 until height) for (x in 0 until width) putCode(out, (y * width + x) * 2, luma(x, y))
        var o = lumaBytes
        for (cy in 0 until chromaH) for (cx in 0 until chromaW) {
            val (cb, cr) = chroma(cx, cy)
            putCode(out, o, cb); putCode(out, o + 2, cr); o += 4
        }
        return out
    }

    private fun lumaStd(buf: ByteArray, w: Int, h: Int): Double {
        var sum = 0.0
        var sum2 = 0.0
        val n = w * h
        for (i in 0 until n) {
            val v = readCode(buf, i * 2).toDouble()
            sum += v; sum2 += v * v
        }
        val mean = sum / n
        return sqrt((sum2 / n - mean * mean).coerceAtLeast(0.0))
    }

    @Test
    fun reducesFlatRegionGrain() {
        val w = 32; val h = 32
        // Deterministic pseudo-noise ±10 codes about a flat mid grey (600). Use the live noisy operating
        // point (uSharp 0.30 / uDenoise 0.72) where the bilateral clearly dominates.
        val src = buildP010(w, h, luma = { x, y -> 600 + ((x * 131 + y * 977 + x * y * 13) % 21) - 10 })
        val out = Npv10ExportEnhancementPolicy.enhanceLiveCinemaLumaP010(
            payload = src, width = w, height = h, uSharpness = 0.30, uDenoise = 0.72
        )
        assertNotSame("must return a fresh array (source stays pristine)", src, out)
        val before = lumaStd(src, w, h)
        val after = lumaStd(out, w, h)
        assertTrue("denoise must REDUCE flat-region grain, not amplify it (before=$before after=$after)",
            after < before)
    }

    @Test
    fun leavesChromaUntouched() {
        val w = 16; val h = 16
        val src = buildP010(
            w, h,
            luma = { x, _ -> if (x < w / 2) 300 else 800 },
            chroma = { cx, cy -> (512 + ((cx * 40) % 120)) to (512 - ((cy * 30) % 90)) }
        )
        val out = Npv10ExportEnhancementPolicy.enhanceLiveCinemaLumaP010(
            payload = src, width = w, height = h, uSharpness = 0.60, uDenoise = 0.50
        )
        val lumaBytes = w * h * 2
        for (i in lumaBytes until src.size) {
            assertEquals("chroma byte $i must be byte-untouched", src[i], out[i])
        }
    }

    @Test
    fun edgeOvershootStaysBoundedButStillSteepens() {
        val w = 16; val h = 8
        val dark = 300; val bright = 800
        val src = buildP010(w, h, luma = { x, _ -> if (x < w / 2) dark else bright })
        val out = Npv10ExportEnhancementPolicy.enhanceLiveCinemaLumaP010(
            payload = src, width = w, height = h, uSharpness = 0.60, uDenoise = 0.50
        )
        var maxV = 0; var minV = 1023
        for (i in 0 until w * h) {
            val v = readCode(out, i * 2)
            if (v > maxV) maxV = v
            if (v < minV) minV = v
        }
        // amt = 0.60·0.85 = 0.51 → cap = 0.05 + 0.05·0.51 ≈ 0.0755 → ~77 codes; allow generous slack.
        assertTrue("bright overshoot must stay bounded (max=$maxV)", maxV <= bright + 120)
        assertTrue("dark undershoot must stay bounded (min=$minV)", minV >= dark - 120)
        // It must still add SOME sharpening (overshoot beyond the bright plateau).
        assertTrue("must steepen the edge (max=$maxV vs bright=$bright)", maxV > bright)
    }

    @Test
    fun ndrOperatingPointAdaptsToMeasuredNoise() {
        // The NDR develop operating point is resolved per frame via the SAME policy the live HDR record
        // shader uses, driven by the measured normalized luma noise. Noisier => MORE denoise, LESS sharpen;
        // a clean frame keeps the live clean baseline (0.35 denoise / 0.45 sharpen).
        val w = 64; val h = 64
        val clean = buildP010(w, h, luma = { _, _ -> 500 })
        val noisy = buildP010(w, h, luma = { x, y -> 500 + ((x * 131 + y * 977 + x * y * 13) % 201) - 100 })
        val cleanNoise = Npv10ExportEnhancementPolicy.measureP010NoiseMetrics(clean, null, w, h).lumaNoise
        val noisyNoise = Npv10ExportEnhancementPolicy.measureP010NoiseMetrics(noisy, null, w, h).lumaNoise
        assertTrue("noisy frame must read higher luma noise (clean=$cleanNoise noisy=$noisyNoise)",
            noisyNoise > cleanNoise)

        val cleanCaps = NdrRecordEnhancementCapsPolicy.resolve(GpuSignalFamily.HDR, 0f, cleanNoise)
        val noisyCaps = NdrRecordEnhancementCapsPolicy.resolve(GpuSignalFamily.HDR, 0f, noisyNoise)
        assertEquals("clean NDR denoise = live clean cap", 0.35f, cleanCaps.denoiseCap, 1e-4f)
        assertEquals("clean NDR sharpen = live clean cap", 0.45f, cleanCaps.sharpenCap, 1e-4f)
        assertTrue("noisy NDR must denoise MORE (${cleanCaps.denoiseCap} -> ${noisyCaps.denoiseCap})",
            noisyCaps.denoiseCap > cleanCaps.denoiseCap)
        assertTrue("noisy NDR must sharpen LESS (${cleanCaps.sharpenCap} -> ${noisyCaps.sharpenCap})",
            noisyCaps.sharpenCap < cleanCaps.sharpenCap)
    }

    @Test
    fun isDeterministic() {
        val w = 24; val h = 24
        val src = buildP010(w, h, luma = { x, y -> 400 + ((x * 17 + y * 29) % 200) })
        val a = Npv10ExportEnhancementPolicy.enhanceLiveCinemaLumaP010(src, w, h, 0.45, 0.35)
        val b = Npv10ExportEnhancementPolicy.enhanceLiveCinemaLumaP010(src, w, h, 0.45, 0.35)
        assertTrue("same input must yield byte-identical output", a.contentEquals(b))
        // And the source is never mutated.
        val fresh = buildP010(w, h, luma = { x, y -> 400 + ((x * 17 + y * 29) % 200) })
        assertTrue("source payload must be left pristine", src.contentEquals(fresh))
    }
}
