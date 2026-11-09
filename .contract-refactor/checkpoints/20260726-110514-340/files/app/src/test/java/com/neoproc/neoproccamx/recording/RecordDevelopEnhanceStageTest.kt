package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorSpace
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorTransfer
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.VideoDevelopColorMetadata
import com.neoproc.neoproccamx.snce.SnceIlluminantEstimator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Phase-4 — locks the OFFLINE denoise/sharpen/temporal stage that runs the proven
 * [Npv10ExportEnhancementPolicy.enhanceP010] lanes on the decoded P010 BEFORE the colour develop.
 *
 * Every behavioural test isolates the enhance stage by running the develop at strength 0 (a pure
 * decode→encode passthrough, per [RecordDevelopMovConverterTest]), so the ONLY thing that can move a pixel
 * is the enhancer. The r210 output is unpacked back to 10-bit RGB codes to make the assertions concrete:
 *   • the stage is COLOUR-CLEAN — an achromatic input stays achromatic (no chroma injected, SNCE nudge off);
 *   • spatial chroma denoise REDUCES colour speckle;
 *   • edge-aware detail STEEPENS a luma edge (overshoot beyond the plateau);
 *   • motion-gated temporal coherence PULLS a near-static frame toward its predecessor;
 *   • the develop stays byte-identical across CPU worker counts WITH the temporal-prior path live;
 *   • the audit faithfully records which colour-clean profile was chosen per target.
 */
class RecordDevelopEnhanceStageTest {

    private val temps = mutableListOf<File>()

    @After
    fun cleanup() {
        temps.forEach { it.delete() }
        // Restore the default-ON develop flags in case a test toggled them (global @Volatile objects).
        RecordDevelopLiveParityFlag.set(true)
        RecordDevelopHonoredBitrateFlag.set(true)
    }

    private fun tempFile(name: String): File =
        File.createTempFile("recdev_enh_$name", ".bin").also { temps += it }

    // ── P010 frame builders (canonical MSB-justified layout: luma plane then interleaved Cb,Cr half-res) ──

    private fun putCode(out: ByteArray, offset: Int, code: Int) {
        val v = (code.coerceIn(0, 1023) and 0x3FF) shl 6
        out[offset] = (v and 0xFF).toByte()
        out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    /** Builds a P010 frame from a per-luma-pixel and a per-chroma-sample generator. */
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

    private fun frameOf(index: Long, p010: ByteArray, w: Int, h: Int) =
        RecordDevelopP010Frame(index = index, presentationTimeUs = 0L, width = w, height = h, p010 = p010)

    private fun readerFactoryOf(frames: List<RecordDevelopP010Frame>, w: Int, h: Int, fps: Int = 30):
        (File) -> RecordDevelopP010FrameReader = {
        InMemoryP010FrameReader(frames, RecordDevelopDecodedFormat(w, h, fps))
    }

    // Passthrough develop so the enhance stage is the ONLY pixel mover.
    private fun devOpts(enhance: RecordDevelopEnhanceOptions) =
        RecordDevelopConversionOptions(snceAppearanceStrength = 0f, filmPrintStrength = 0f, enhance = enhance)

    private fun developAudited(
        frames: List<RecordDevelopP010Frame>, w: Int, h: Int,
        enhance: RecordDevelopEnhanceOptions, budgetBytes: Long? = null
    ): Pair<ByteArray, RecordDevelopConversionAudit> {
        val out = tempFile("out")
        val audit = RecordDevelopMovConverter.convertWithAudit(
            master = tempFile("master"), output = out, sidecar = null,
            options = budgetBytes?.let { devOpts(enhance).copy(memoryBudgetBytes = it) } ?: devOpts(enhance),
            sourceKind = RecordDevelopMasterKind.SDR,
            readerFactory = readerFactoryOf(frames, w, h)
        )
        return out.readBytes() to audit
    }

    private fun develop(
        frames: List<RecordDevelopP010Frame>, w: Int, h: Int,
        enhance: RecordDevelopEnhanceOptions, budgetBytes: Long? = null
    ): ByteArray = developAudited(frames, w, h, enhance, budgetBytes).first

    // ── r210 unpack (big-endian uint32/pixel: R=29-20, G=19-10, B=9-0; row stride padded to 256 bytes) ───

    private fun mdatDataStart(bytes: ByteArray): Int {
        val tag = byteArrayOf('m'.code.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte())
        for (i in 0..bytes.size - tag.size) {
            if (bytes[i] == tag[0] && bytes[i + 1] == tag[1] && bytes[i + 2] == tag[2] && bytes[i + 3] == tag[3]) {
                return i + 12
            }
        }
        error("no mdat box in output")
    }

    /** Unpacks frame [frameIndex] of an r210 output into a (w*h*3) RGB code array (row-major, R fastest). */
    private fun unpackR210(bytes: ByteArray, frameIndex: Int, w: Int, h: Int): IntArray {
        val stride = RawTenBitPacker.rowBytes(RawTenBitPacker.Format.R210_444, w)
        val frameBytes = stride * h
        val base = mdatDataStart(bytes) + frameIndex * frameBytes
        val rgb = IntArray(w * h * 3)
        for (y in 0 until h) {
            var o = base + y * stride
            for (x in 0 until w) {
                val word = ((bytes[o].toInt() and 0xFF) shl 24) or
                    ((bytes[o + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[o + 2].toInt() and 0xFF) shl 8) or
                    (bytes[o + 3].toInt() and 0xFF)
                val i = (y * w + x) * 3
                rgb[i] = (word ushr 20) and 0x3FF
                rgb[i + 1] = (word ushr 10) and 0x3FF
                rgb[i + 2] = word and 0x3FF
                o += 4
            }
        }
        return rgb
    }

    private fun colorSpeckle(rgb: IntArray): Long {
        var sum = 0L
        var i = 0
        while (i < rgb.size) {
            val r = rgb[i]; val g = rgb[i + 1]; val b = rgb[i + 2]
            sum += abs(r - g) + abs(g - b) + abs(r - b)
            i += 3
        }
        return sum
    }

    private fun maxChannel(rgb: IntArray): Int = rgb.maxOrNull() ?: 0
    private fun minChannel(rgb: IntArray): Int = rgb.minOrNull() ?: 0

    private fun ndrSidecar(): VideoDevelopColorMetadata = VideoDevelopColorMetadata(
        width = 16, height = 8, storageBitDepth = 10, fps = 30,
        whiteBalanceR = 1f, whiteBalanceGr = 1f, whiteBalanceGb = 1f, whiteBalanceB = 1f,
        workingRgbToXyz = SnceIlluminantEstimator.REC2020_TO_XYZ.copyOf(),
        adaptingLuminance = 0f, cctKelvin = 5500f, tint = 0f,
        colorSpace = DevelopColorSpace.REC2020, colorTransfer = DevelopColorTransfer.HLG, colorProfileTag = "ndr"
    )

    // ── tests ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun enhanceIsColourClean_achromaticEdgeStaysAchromatic() {
        val w = 16; val h = 8
        // Achromatic (chroma == neutral 512) with a strong interior luma edge so the detail lanes fire hard.
        val p010 = buildP010(w, h, luma = { x, _ -> if (x < w / 2) 300 else 800 })
        val out = develop(listOf(frameOf(0, p010, w, h)), w, h, RecordDevelopEnhanceOptions.ELITE)
        val rgb = unpackR210(out, 0, w, h)
        // The luma-detail lanes only touch the luma plane; chroma stays neutral ⇒ every pixel stays R==G==B.
        // If ANY colour were injected (SNCE nudge / calibration), this would fail.
        var i = 0
        while (i < rgb.size) {
            assertEquals("R==G at px ${i / 3}", rgb[i], rgb[i + 1])
            assertEquals("G==B at px ${i / 3}", rgb[i + 1], rgb[i + 2])
            i += 3
        }
    }

    @Test
    fun enhanceDoesNotBoostSaturationOnCoherentChroma() {
        val w = 16; val h = 8
        // A locally-COHERENT, non-neutral chroma block (U=+58, V=-52 about neutral — both beyond the shadow
        // deadband and same-signed with all neighbours) SURVIVES the denoise chain and reaches the SNCE
        // nudge with a real value. applySnceChromaNudge is MULTIPLICATIVE about neutral, so a neutral-only
        // input (used by the other colour-clean tests) can't expose a saturation leak — this one can: if the
        // converter ever fed saturation!=1, colourfulness would inflate. The denoise can only preserve/reduce
        // colourfulness, never boost it, so ELITE must not exceed the DISABLED baseline.
        val p010 = buildP010(w, h, luma = { _, _ -> 600 }, chroma = { _, _ -> (512 + 58) to (512 - 52) })
        val off = colorSpeckle(unpackR210(develop(listOf(frameOf(0, p010.copyOf(), w, h)), w, h, RecordDevelopEnhanceOptions.DISABLED), 0, w, h))
        val on = colorSpeckle(unpackR210(develop(listOf(frameOf(0, p010.copyOf(), w, h)), w, h, RecordDevelopEnhanceOptions.ELITE), 0, w, h))
        assertTrue("baseline must be genuinely colourful (was $off)", off > 0)
        assertTrue("enhancer must not boost saturation (off=$off on=$on)", on <= off + off / 10 + 3)
    }

    @Test
    fun spatialDenoiseReducesColourSpeckle() {
        val w = 16; val h = 8
        // Uniform luma; checkerboard chroma spikes (±120 about neutral) ⇒ heavy colour speckle to clean up.
        val p010 = buildP010(
            w, h,
            luma = { _, _ -> 520 },
            chroma = { cx, cy -> if ((cx + cy) % 2 == 0) (512 + 120) to (512 - 120) else (512 - 120) to (512 + 120) }
        )
        val frame = { frameOf(0, p010.copyOf(), w, h) }
        val speckleOff = colorSpeckle(unpackR210(develop(listOf(frame()), w, h, RecordDevelopEnhanceOptions.DISABLED), 0, w, h))
        val speckleOn = colorSpeckle(unpackR210(develop(listOf(frame()), w, h, RecordDevelopEnhanceOptions.ELITE), 0, w, h))
        assertTrue("enhance-off must show real colour speckle (was $speckleOff)", speckleOff > 0)
        assertTrue("chroma denoise must reduce speckle (off=$speckleOff on=$speckleOn)", speckleOn < speckleOff)
    }

    @Test
    fun edgeDetailSteepensLumaEdge() {
        val w = 16; val h = 8
        // Achromatic step edge at the interior column w/2 (plateaus 320 and 560).
        val p010 = buildP010(w, h, luma = { x, _ -> if (x < w / 2) 320 else 560 })
        val frame = { frameOf(0, p010.copyOf(), w, h) }
        val off = unpackR210(develop(listOf(frame()), w, h, RecordDevelopEnhanceOptions.DISABLED), 0, w, h)
        val on = unpackR210(develop(listOf(frame()), w, h, RecordDevelopEnhanceOptions.ELITE), 0, w, h)
        // Sharpening overshoots past the bright plateau and undershoots below the dark plateau ⇒ the code
        // range widens. If the detail lane were dead the ranges would match.
        assertTrue("edge detail must overshoot the bright plateau (off=${maxChannel(off)} on=${maxChannel(on)})",
            maxChannel(on) > maxChannel(off))
        assertTrue("edge detail must undershoot the dark plateau (off=${minChannel(off)} on=${minChannel(on)})",
            minChannel(on) < minChannel(off))
    }

    @Test
    fun temporalCoherencePullsNearStaticFrameTowardPredecessor() {
        // Legacy natural profile uses temporalLumaBlend=1.0 (full replace). The live-parity default caps it
        // to 0.35, so exercise the reversible OFF path here to lock the full-replace behaviour.
        RecordDevelopLiveParityFlag.set(false)
        val w = 16; val h = 8
        // Two uniform achromatic frames 5 codes apart ⇒ within the motion threshold (22) ⇒ temporal fires.
        val f0 = buildP010(w, h, luma = { _, _ -> 500 })
        val f1 = buildP010(w, h, luma = { _, _ -> 505 })
        fun frames() = listOf(frameOf(0, f0.copyOf(), w, h), frameOf(1, f1.copyOf(), w, h))
        // Spatial-only (temporal off) vs full elite (temporal on); uniform frames ⇒ spatial is a no-op, so
        // the ONLY difference is the temporal lane pulling frame 1's static pixels onto frame 0's source.
        val spatialOnly = RecordDevelopEnhanceOptions(enabled = true, temporalDenoise = false)
        val noTemporal = unpackR210(develop(frames(), w, h, spatialOnly), 1, w, h)
        val withTemporal = unpackR210(develop(frames(), w, h, RecordDevelopEnhanceOptions.ELITE), 1, w, h)
        val frame0 = unpackR210(develop(frames(), w, h, RecordDevelopEnhanceOptions.ELITE), 0, w, h)

        val driftNoTemporal = abs(noTemporal[0] - frame0[0])
        val driftWithTemporal = abs(withTemporal[0] - frame0[0])
        assertTrue("frame 1 must differ from frame 0 without temporal (drift=$driftNoTemporal)", driftNoTemporal > 0)
        assertEquals("temporal must snap the near-static frame 1 onto frame 0", 0, driftWithTemporal)
    }

    @Test
    fun developIsByteIdenticalAcrossWorkerCounts_withEnhanceAndTemporalLive() {
        val w = 16; val h = 8; val n = 24
        fun frames() = (0 until n).map { idx ->
            // Edge + small temporal drift (temporal-eligible) + checkerboard chroma noise ⇒ exercises every
            // enhance lane AND the predecessor-prior path, so determinism here proves the temporal wiring is
            // order-independent, not just the pure develop.
            val p010 = buildP010(
                w, h,
                luma = { x, y -> (if (x < w / 2) 300 else 520) + ((x * 7 + y * 13 + idx) % 9) },
                chroma = { cx, cy -> if ((cx + cy) % 2 == 0) (512 + 90) to 512 else (512 - 90) to 512 }
            )
            frameOf(idx.toLong(), p010, w, h)
        }
        val (single, singleAudit) = developAudited(frames(), w, h, RecordDevelopEnhanceOptions.ELITE, budgetBytes = 1L)
        assertEquals("tiny budget must force a single worker", 1, singleAudit.workerCount)

        Assume.assumeTrue(
            "needs >=3 cores to exercise the multi-worker reorder path",
            Runtime.getRuntime().availableProcessors() >= 3
        )
        val (multi, multiAudit) = developAudited(frames(), w, h, RecordDevelopEnhanceOptions.ELITE)
        // Without this the comparison could silently degrade to single-vs-single and prove nothing.
        assertTrue("default budget must fan out to >1 worker (got ${multiAudit.workerCount})", multiAudit.workerCount > 1)
        assertTrue("enhance+temporal develop must be byte-identical across worker counts",
            single.contentEquals(multi))
    }

    @Test
    fun auditRecordsColourCleanProfilePerTarget() {
        val w = 16; val h = 8
        val p010 = { buildP010(w, h, luma = { _, _ -> 500 }) }

        val sdr = RecordDevelopMovConverter.convertWithAudit(
            tempFile("m"), tempFile("o"), sidecar = null, options = RecordDevelopConversionOptions(),
            sourceKind = RecordDevelopMasterKind.SDR, readerFactory = readerFactoryOf(listOf(frameOf(0, p010(), w, h)), w, h)
        )
        assertTrue(sdr.enhanceApplied)
        // Live-cinema parity is the default: the SDR audit reports the parity profile.
        assertEquals("CINEMA_SDR_LIVE_PARITY", sdr.enhanceProfile)
        assertTrue("elite default enables temporal", sdr.temporalDenoise)

        val ndr = RecordDevelopMovConverter.convertWithAudit(
            tempFile("m"), tempFile("o"), sidecar = ndrSidecar(), options = RecordDevelopConversionOptions(),
            sourceKind = RecordDevelopMasterKind.NDR, readerFactory = readerFactoryOf(listOf(frameOf(0, p010(), w, h)), w, h)
        )
        assertTrue("NDR enhance applied", ndr.enhanceApplied)
        assertEquals("CINEMA_NDR_LIVE_PARITY", ndr.enhanceProfile)
        assertTrue("NDR elite default enables temporal", ndr.temporalDenoise)

        val off = RecordDevelopMovConverter.convertWithAudit(
            tempFile("m"), tempFile("o"), sidecar = null,
            options = RecordDevelopConversionOptions(enhance = RecordDevelopEnhanceOptions.DISABLED),
            sourceKind = RecordDevelopMasterKind.SDR, readerFactory = readerFactoryOf(listOf(frameOf(0, p010(), w, h)), w, h)
        )
        assertFalse(off.enhanceApplied)
        assertEquals("NONE", off.enhanceProfile)
        assertFalse(off.temporalDenoise)

        // Reversible A/B: with live parity OFF the audit reports the legacy natural profiles.
        RecordDevelopLiveParityFlag.set(false)
        val sdrLegacy = RecordDevelopMovConverter.convertWithAudit(
            tempFile("m"), tempFile("o"), sidecar = null, options = RecordDevelopConversionOptions(),
            sourceKind = RecordDevelopMasterKind.SDR, readerFactory = readerFactoryOf(listOf(frameOf(0, p010(), w, h)), w, h)
        )
        assertEquals("CINEMA_SDR_NATURAL", sdrLegacy.enhanceProfile)
        val ndrLegacy = RecordDevelopMovConverter.convertWithAudit(
            tempFile("m"), tempFile("o"), sidecar = ndrSidecar(), options = RecordDevelopConversionOptions(),
            sourceKind = RecordDevelopMasterKind.NDR, readerFactory = readerFactoryOf(listOf(frameOf(0, p010(), w, h)), w, h)
        )
        assertEquals("CINEMA_NDR_NATURAL", ndrLegacy.enhanceProfile)
    }
}
