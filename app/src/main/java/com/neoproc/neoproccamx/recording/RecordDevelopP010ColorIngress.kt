package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine
import com.neoproc.neoproccamx.color.NdrToneOperator
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorSpace
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorTransfer
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  RECORD-THEN-DEVELOP — Phase 2 colour ingress / egress  (P010 ⇄ linear working RGB)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The one place that owns the pixel colour transforms around [VideoDevelopColorPipeline]. The pipeline
 * operates in a LINEAR working-RGB space (its default working matrix is the linear Rec.709→XYZ) and does
 * NO de-gamma / HLG-linearisation itself, so this object:
 *
 *   DECODE (per pixel):  P010 Y′CbCr code → [matrix] R′G′B′ → [EOTF] linear working RGB  ([decodeToLinearRgb])
 *   DEVELOP:             handled by the pipeline (linear in, linear out)
 *   ENCODE (per pixel):  linear RGB → [OETF] R′G′B′ → 10-bit code 0..1023               ([encodeLinearRgb])
 *
 * The decoded output stays in the INTERMEDIATE's primaries (Rec.709 or Rec.2020); the develop keeps that
 * space, and the container's colour tag is set to match (see [RecordDevelopMovConverter]). For the v210
 * compatibility format on a Rec.709 clip, [rec709LinearToRec2020] widens the gamut so the delivered file
 * is a clean, self-consistent BT.2020 v210 rather than a primaries/matrix-mismatched one.
 *
 * RANGE: P010 samples are decoded as LIMITED range (Y' = (code−64)/876, chroma = (code−512)/896) — the
 * standard-video convention and an exact match for the app's own 10-bit decoder
 * [Npv10ThumbnailRenderer.yuvToArgb]. The chroma divisor is the FULL excursion span (896 limited / 1023
 * full), NOT the half-span, because the BT.709/BT.2020 non-constant-luminance matrix coefficients
 * (1.5748 = 2(1−Kr), 1.8556 = 2(1−Kb), …) are defined for E′_Cb/E′_Cr ∈ [−0.5, +0.5]. If a device is later
 * shown to record FULL-range P010, flip [P010_LIMITED_RANGE] — it is the single knob.
 * ⚠ R3: the Phase-1 encoder's actual KEY_COLOR_RANGE is device-gated; confirm on-device before shipping.
 *
 * All maths is pure + deterministic → unit-testable with no device. Decode and encode transfer functions
 * are exact inverses, so a strength-0 develop round-trips within ≤±1 code (the no-downgrade proof).
 */
internal object RecordDevelopP010ColorIngress {

    /** P010 stores the 10 valid bits MSB-justified in a 16-bit LE word (bits [15:6]); low 6 are padding. */
    private const val P010_VALID_BITS_SHIFT = 6
    private const val MAX10 = 1023
    private const val CHROMA_NEUTRAL = 512.0

    // Limited-range 10-bit spans (BT.2100 narrow): luma 64..940 (876), chroma 64..960 centred 512 (896).
    private const val LUMA_LIMITED_MIN = 64.0
    private const val LUMA_LIMITED_SPAN = 876.0
    private const val CHROMA_LIMITED_SPAN = 896.0

    /** See class doc — LIMITED-range decode matches [Npv10ThumbnailRenderer] and standard HEVC video. */
    private const val P010_LIMITED_RANGE = true

    // ── P010 sample access (canonical MSB-justified layout, see RecordDevelopP010FrameReader) ─────────

    /** Recover a 10-bit code (0..1023) from a 16-bit LE MSB-justified P010 sample at [offset]. */
    fun readP010Code(payload: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < payload.size) { "P010 payload is truncated at $offset" }
        val value = (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
        return (value ushr P010_VALID_BITS_SHIFT).coerceIn(0, MAX10)
    }

    /** Luma code at pixel ([x],[y]) with clamp-to-edge. */
    fun readLumaCode(payload: ByteArray, width: Int, height: Int, x: Int, y: Int): Int {
        val cx = x.coerceIn(0, width - 1)
        val cy = y.coerceIn(0, height - 1)
        return readP010Code(payload, (cy * width + cx) * 2)
    }

    /** Chroma code at pixel ([x],[y]); [component] 0 = Cb, 1 = Cr. 4:2:0 half-res, interleaved. */
    fun readChromaCode(payload: ByteArray, width: Int, height: Int, x: Int, y: Int, component: Int): Int {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val cx = (x / 2).coerceIn(0, chromaWidth - 1)
        val cy = (y / 2).coerceIn(0, chromaHeight - 1)
        val lumaBytes = width * height * 2
        val offset = lumaBytes + (cy * chromaWidth + cx) * 2 * 2 + component.coerceIn(0, 1) * 2
        return readP010Code(payload, offset)
    }

    // ── DECODE: P010 Y′CbCr → linear working RGB ─────────────────────────────────────────────────────

    /**
     * Decode one Y′CbCr sample triplet (10-bit codes) into a LINEAR working-RGB triplet in the
     * intermediate's primaries, using [space] to pick the YCbCr matrix and [transfer] to pick the EOTF.
     */
    fun decodeToLinearRgb(
        yCode: Int,
        cbCode: Int,
        crCode: Int,
        space: DevelopColorSpace,
        transfer: DevelopColorTransfer
    ): FloatArray = decodeToLinearRgbInto(yCode, cbCode, crCode, space, transfer, FloatArray(3), 0)

    fun decodeToLinearRgbInto(
        yCode: Int,
        cbCode: Int,
        crCode: Int,
        space: DevelopColorSpace,
        transfer: DevelopColorTransfer,
        out: FloatArray,
        offset: Int = 0
    ): FloatArray {
        // Recover Y′ ∈ [0,1] and E′_Cb/E′_Cr ∈ [−0.5,+0.5] using the FULL excursion span (never the
        // half-span) so the matrix coefficients below are colorimetrically correct.
        val yn: Double
        val cb: Double
        val cr: Double
        if (P010_LIMITED_RANGE) {
            yn = (yCode - LUMA_LIMITED_MIN) / LUMA_LIMITED_SPAN
            cb = (cbCode - CHROMA_NEUTRAL) / CHROMA_LIMITED_SPAN
            cr = (crCode - CHROMA_NEUTRAL) / CHROMA_LIMITED_SPAN
        } else {
            yn = yCode.toDouble() / MAX10.toDouble()
            cb = (cbCode - CHROMA_NEUTRAL) / MAX10.toDouble()
            cr = (crCode - CHROMA_NEUTRAL) / MAX10.toDouble()
        }

        // Y′CbCr → non-linear R′G′B′ (per-primaries luma coefficients).
        val rp: Double
        val gp: Double
        val bp: Double
        when (space) {
            DevelopColorSpace.REC709 -> {
                rp = yn + 1.5748 * cr
                gp = yn - 0.1873 * cb - 0.4681 * cr
                bp = yn + 1.8556 * cb
            }
            DevelopColorSpace.REC2020 -> {
                rp = yn + 1.4746 * cr
                gp = yn - 0.16455 * cb - 0.57135 * cr
                bp = yn + 1.8814 * cb
            }
        }

        // R′G′B′ → linear via the intermediate's EOTF.
        out[offset] = eotf(rp.coerceIn(0.0, 1.0), transfer)
        out[offset + 1] = eotf(gp.coerceIn(0.0, 1.0), transfer)
        out[offset + 2] = eotf(bp.coerceIn(0.0, 1.0), transfer)
        return out
    }

    private fun eotf(signal: Double, transfer: DevelopColorTransfer): Float = when (transfer) {
        DevelopColorTransfer.SDR -> rec709ToLinear(signal)
        DevelopColorTransfer.HLG -> hlgSignalToScene(signal)
        DevelopColorTransfer.PQ -> pqToLinear(signal)
    }.toFloat()

    // ── ENCODE: linear develop-output RGB → 10-bit R′G′B′ code ───────────────────────────────────────

    /** Output transfer for the DEVELOP DELIVERY (independent of the intermediate's decode transfer). */
    enum class OutputTransfer { SDR_REC709_OETF, PQ_ST2084 }

    /**
     * Encode one develop-output RGB triplet into a 10-bit R′G′B′ code triplet. For [OutputTransfer.PQ_ST2084]
     * the display-referred [0,1] triplet is anchored to [diffuseWhiteNits] before the PQ curve (R6).
     */
    fun encodeLinearRgb(
        r: Float,
        g: Float,
        b: Float,
        transfer: OutputTransfer,
        diffuseWhiteNits: Float
    ): IntArray = encodeLinearRgbInto(r, g, b, transfer, diffuseWhiteNits, IntArray(3), 0)

    fun encodeLinearRgbInto(
        r: Float,
        g: Float,
        b: Float,
        transfer: OutputTransfer,
        diffuseWhiteNits: Float,
        out: IntArray,
        offset: Int = 0
    ): IntArray = when (transfer) {
        OutputTransfer.SDR_REC709_OETF -> {
            out[offset] = toCode(linearToRec709(r.toDouble()))
            out[offset + 1] = toCode(linearToRec709(g.toDouble()))
            out[offset + 2] = toCode(linearToRec709(b.toDouble()))
            out
        }
        OutputTransfer.PQ_ST2084 -> {
            val scale = diffuseWhiteNits.toDouble() / PQ_PEAK_NITS
            out[offset] = toCode(pqOetf(r.toDouble() * scale))
            out[offset + 1] = toCode(pqOetf(g.toDouble() * scale))
            out[offset + 2] = toCode(pqOetf(b.toDouble() * scale))
            out
        }
    }

    private fun toCode(signal01: Double): Int = (signal01.coerceIn(0.0, 1.0) * MAX10).roundToInt().coerceIn(0, MAX10)

    /**
     * GENUINE-HDR NDR egress (Phase-6 / R6). The SNCE-developed linear BT.2020 triplet is scene-referred with
     * 1.0 == the intermediate's peak; its diffuse white therefore sits BELOW 1.0 (at [HLG_REFERENCE_WHITE_SCENE]
     * for the HLG-coherent NDR intermediate). This re-normalises so diffuse white lands at the delivery's
     * 1.0, then runs the canonical, host-golden [NdrNivukxDeliveryEngine.deliverPqCodes] — the ONE nit-accurate
     * PQ DELIVERY TRANSFORM the live record shader and the PQ preview also use, so the delivery matches live by
     * construction (the offline develop additionally folds in the heavier SNCE appearance stage upstream).
     *
     * Returns 10-bit PQ codes (0..1023): black → 0, diffuse white → 203 nits (BT.2408), highlights rolled
     * through the NDR C1 shoulder toward the exact 5000-nit master peak. Unlike the legacy ACES-SDR-fit anchor
     * (which clamps every highlight to 203 nits), this uses the full HDR headroom.
     *
     * @param decodeTransfer the intermediate's transfer, so the diffuse-white normaliser matches how the
     *   scene-linear was decoded: HLG → the 75%-signal reference white; PQ → the 203/10000 anchor.
     */
    fun encodeNdrNivukxPqCodes(
        r: Float,
        g: Float,
        b: Float,
        decodeTransfer: DevelopColorTransfer,
        warmSkinRedTame: Double = 0.0
    ): IntArray {
        val k = 1.0 / diffuseWhiteSceneFor(decodeTransfer)
        // NDR10_PLUS is the ONLY mode the record-develop NDR lane produces (RecordDevelopMasterKind is SDR/NDR;
        // there is no NIVUKX record-develop lane), and it matches the Phase-4 enhance profile (NDR10_PLUS). If a
        // NIVUKX develop lane is ever added, thread its mode here in lock-step with enhanceProfileFor().
        // warmSkinRedTame (default 0.0 = byte-identical) post-corrects the camera-baked warm/red skin cast.
        return NdrNivukxDeliveryEngine.deliverPqCodes(
            r.toDouble() * k, g.toDouble() * k, b.toDouble() * k,
            NdrNivukxDeliveryEngine.Mode.NDR10_PLUS, bits = 10, warmSkinRedTame = warmSkinRedTame
        )
    }

    /**
     * The scene-linear value that maps to DIFFUSE WHITE for a given intermediate decode transfer — the
     * anchor used to re-normalise the SNCE-developed scene-linear so diffuse white lands at the delivery's
     * 1.0. HLG → the 75%-signal reference white; PQ → the 203/10000 anchor (BT.2408); SDR → 1.0. Single
     * source of truth shared by [encodeNdrNivukxPqCodes] and the offline develop white-normalisation lever.
     */
    fun diffuseWhiteSceneFor(decodeTransfer: DevelopColorTransfer): Double = when (decodeTransfer) {
        DevelopColorTransfer.HLG -> HLG_REFERENCE_WHITE_SCENE
        DevelopColorTransfer.PQ -> NdrToneOperator.DIFFUSE_WHITE_NITS / PQ_PEAK_NITS
        DevelopColorTransfer.SDR -> 1.0 // NDR from an SDR intermediate is not expected; ref == 1.0.
    }

    // ── Gamut: linear Rec.709 → linear Rec.2020 (D65, no CAT) ────────────────────────────────────────

    /**
     * Widen a linear Rec.709 RGB triplet to linear Rec.2020 (both D65) so a v210 delivery of an SDR-Rec.709
     * clip is a self-consistent BT.2020 file (primaries == matrix == BT.2020) instead of a mismatch.
     * Rec.709 ⊂ Rec.2020, so this is lossless (no gamut clipping).
     */
    fun rec709LinearToRec2020(rgb: FloatArray): FloatArray {
        val r = rgb[0].toDouble(); val g = rgb[1].toDouble(); val b = rgb[2].toDouble()
        return rec709LinearToRec2020Into(r.toFloat(), g.toFloat(), b.toFloat(), FloatArray(3), 0)
    }

    fun rec709LinearToRec2020Into(
        r: Float,
        g: Float,
        b: Float,
        out: FloatArray,
        offset: Int = 0
    ): FloatArray {
        val rd = r.toDouble(); val gd = g.toDouble(); val bd = b.toDouble()
        out[offset] = (0.6274039 * rd + 0.3292830 * gd + 0.0433131 * bd).toFloat()
        out[offset + 1] = (0.0690973 * rd + 0.9195404 * gd + 0.0113623 * bd).toFloat()
        out[offset + 2] = (0.0163914 * rd + 0.0880132 * gd + 0.8955953 * bd).toFloat()
        return out
    }

    // ── Transfer functions (BT.709 OETF/EOTF, HLG, PQ ST.2084) ───────────────────────────────────────

    /** BT.709 signal → linear (inverse camera OETF); exact inverse of [linearToRec709]. */
    fun rec709ToLinear(signal: Double): Double =
        if (signal < REC709_SIGNAL_BREAK) signal / 4.5 else ((signal + 0.099) / 1.099).pow(1.0 / 0.45)

    /** BT.709 linear → signal (camera OETF). */
    fun linearToRec709(linear: Double): Double {
        val l = linear.coerceIn(0.0, 1.0)
        return if (l < REC709_LINEAR_BREAK) 4.5 * l else 1.099 * l.pow(0.45) - 0.099
    }

    /** HLG signal [0,1] → normalised scene-linear [0,1] (inverse HLG OETF, ARIB STD-B67). */
    fun hlgSignalToScene(signal: Double): Double {
        val e = signal.coerceIn(0.0, 1.0)
        return if (e <= 0.5) (e * e) / 3.0 else (exp((e - HLG_C) / HLG_A) + HLG_B) / 12.0
    }

    /** PQ signal [0,1] → normalised linear [0,1] where 1.0 == [PQ_PEAK_NITS] (inverse ST.2084). */
    fun pqToLinear(signal: Double): Double {
        val e = signal.coerceIn(0.0, 1.0)
        val ePow = e.pow(1.0 / PQ_M2)
        val num = (ePow - PQ_C1).coerceAtLeast(0.0)
        val den = PQ_C2 - PQ_C3 * ePow
        if (den <= 0.0) return 1.0
        return (num / den).pow(1.0 / PQ_M1)
    }

    /** Normalised linear [0,1] (1.0 == [PQ_PEAK_NITS]) → PQ signal [0,1] (ST.2084 OETF). */
    fun pqOetf(linear01: Double): Double {
        val y = linear01.coerceIn(0.0, 1.0)
        val yPow = y.pow(PQ_M1)
        return ((PQ_C1 + PQ_C2 * yPow) / (1.0 + PQ_C3 * yPow)).pow(PQ_M2)
    }

    // BT.709 OETF breakpoints (linear 0.018 ↔ signal 0.081).
    private const val REC709_LINEAR_BREAK = 0.018
    private const val REC709_SIGNAL_BREAK = 0.081

    // HLG (ARIB STD-B67) constants.
    private const val HLG_A = 0.17883277
    private const val HLG_B = 0.28466892
    private const val HLG_C = 0.55991073

    /**
     * BT.2408 HLG reference (diffuse) white sits at the 75% HLG signal. [encodeNdrNivukxPqCodes] scales the
     * HLG-decoded scene-linear (1.0 == HLG peak) so this level maps to the delivery's 1.0 == diffuse white.
     * Exposed + tunable for the on-device R6 A/B against the live NDR delivery. (Scene value ≈ 0.2649.)
     */
    const val HLG_REFERENCE_WHITE_SIGNAL = 0.75
    private val HLG_REFERENCE_WHITE_SCENE = hlgSignalToScene(HLG_REFERENCE_WHITE_SIGNAL)

    // PQ (SMPTE ST.2084) constants; peak = 10000 cd/m².
    const val PQ_PEAK_NITS = 10000.0
    private const val PQ_M1 = 0.1593017578125
    private const val PQ_M2 = 78.84375
    private const val PQ_C1 = 0.8359375
    private const val PQ_C2 = 18.8515625
    private const val PQ_C3 = 18.6875
}
