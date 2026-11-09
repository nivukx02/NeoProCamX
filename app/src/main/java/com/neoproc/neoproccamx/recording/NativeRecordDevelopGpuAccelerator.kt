package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NdrToneOperator
import com.neoproc.neoproccamx.recording.RecordDevelopP010ColorIngress.OutputTransfer
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorSpace
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorTransfer
import com.neoproc.neoproccamx.snce.SnceCam16
import com.neoproc.neoproccamx.snce.SnceColorPrimitives
import com.neoproc.neoproccamx.snce.SnceIlluminantEstimator
import com.neoproc.neoproccamx.snce.SnceNeuralEnhancement
import com.neoproc.neoproccamx.snce.SnceNrliAnalyticLut

/**
 * Native Vulkan implementation of the record-develop colour lane.
 *
 * This accelerates the parity-proven SDR/NDR path: P010 decode, SNCE appearance, SDR film-print look,
 * optional Rec.709->Rec.2020 widening, and SDR/PQ 10-bit egress. ACES variants stay on CPU until their
 * shader ports are added and accepted by the converter's first-frame parity gate.
 */
internal class NativeRecordDevelopGpuAccelerator(
    private val context: RecordDevelopGpuContext
) : RecordDevelopGpuAccelerator {

    private val params: FloatArray? = buildParamsOrNull(context)

    override fun isAvailable(): Boolean =
        params != null &&
            RecordDevelopGpuAccelerationFlag.enabled &&
            NativeRecordDevelopGpuBridge.isAvailable()

    override fun developFrameToCodes(p010: ByteArray, width: Int, height: Int): ShortArray? {
        val packet = params ?: return null
        if (width != context.format.width || height != context.format.height || p010.isEmpty()) return null
        return NativeRecordDevelopGpuBridge.developP010ToRgb10Codes(
            payload = p010,
            width = width,
            height = height,
            params = packet
        )
    }

    override fun developFrameToPackedPayload(
        p010: ByteArray,
        width: Int,
        height: Int,
        outputFormat: RecordDevelopOutputFormat
    ): ByteArray? {
        val packet = params ?: return null
        if (width != context.format.width || height != context.format.height || p010.isEmpty()) return null
        return NativeRecordDevelopGpuBridge.developP010ToPackedPayload(
            payload = p010,
            width = width,
            height = height,
            params = packet,
            outputFormat = outputFormat
        )
    }

    override fun developFrameToP010420Payload(
        p010: ByteArray,
        width: Int,
        height: Int,
        bt709Matrix: Boolean
    ): ByteArray? {
        val packet = params ?: return null
        if (width != context.format.width || height != context.format.height || p010.isEmpty()) return null
        return NativeRecordDevelopGpuBridge.developP010ToP010420Payload(
            payload = p010,
            width = width,
            height = height,
            params = packet,
            bt709Matrix = bt709Matrix
        )
    }

    companion object {
        // Edge size of the baked film-print show-LUT (33^3), matching SnceNrliAnalyticLut.buildFilmPrintLut()
        // and VideoDevelopColorPipeline's Stage-C LUT. The LUT is appended to the param packet so the develop
        // shader tetrahedral-samples the EXACT CPU LUT (record-develop SDR film-print parity).
        private const val FILM_LUT_EDGE = 33
        private const val FILM_LUT_FLOAT_COUNT = FILM_LUT_EDGE * FILM_LUT_EDGE * FILM_LUT_EDGE * 3
        // 64 scalar params, then the LUT floats at [FILM_LUT_OFFSET, FILM_LUT_OFFSET+FILM_LUT_FLOAT_COUNT).
        // Slots 43..52 carry the canonical calibrated NDR delivery profile; slot 53 carries the independently
        // resolved SDR correction strength. Keeping those values in the
        // existing storage-buffer packet (instead of hardcoding a second curve in GLSL) makes the CPU engine
        // the single source of truth and prevents profile updates from silently darkening GPU conversion.
        internal const val FILM_LUT_OFFSET = 64
        internal const val PARAM_FLOAT_COUNT = FILM_LUT_OFFSET + FILM_LUT_FLOAT_COUNT
        private const val PARAM_FILM_PRINT_STRENGTH = 38
        // p[42] = LUT edge size (33) when the baked LUT is uploaded, else 0 (analytic fallback in-shader).
        private const val PARAM_FILM_LUT_SIZE = 42
        // Hoisted CAM16 viewing-condition constants (free slots 39..41). Uploading the exact CPU
        // vc bits lets the shader skip its per-pixel fp32 re-derivation of these three, removing
        // avoidable GPU<->CPU drift in the SNCE forward/inverse (no ABI change; count stays 48).
        private const val PARAM_FL_ROOT = 39
        private const val PARAM_T_EXPONENT_BASE = 40
        private const val PARAM_CZ = 41
        internal const val PARAM_NDR_SIGNAL_GAMMA = 43
        internal const val PARAM_NDR_TOE_WIDTH = 44
        internal const val PARAM_NDR_TOE_AMOUNT = 45
        internal const val PARAM_NDR_PEAK_NITS = 46
        internal const val PARAM_NDR_HIGHLIGHT_DESAT = 47
        internal const val PARAM_NDR_SHADOW_MIDTONE_LIFT = 48
        internal const val PARAM_NDR_MIDTONE_CONTRAST = 49
        internal const val PARAM_NDR_CHROMA_BOOST = 50
        internal const val PARAM_NDR_SKY_CHROMA_BONUS = 51
        internal const val PARAM_NDR_WARM_SKIN_RED_TAME = 52
        internal const val PARAM_SDR_WARM_SKIN_RED_TAME = 53
        private const val DECODE_SPACE_REC709 = 0f
        private const val DECODE_SPACE_REC2020 = 1f
        private const val TRANSFER_SDR = 0f
        private const val TRANSFER_HLG = 1f
        private const val TRANSFER_PQ = 2f
        private const val OUTPUT_SDR = 0f
        private const val OUTPUT_PQ = 1f

        internal fun buildParamsOrNull(context: RecordDevelopGpuContext): FloatArray? {
            if (context.acesToneMap) return null
            if (context.semanticClass != SnceNeuralEnhancement.SemanticClass.OTHER) return null

            val rgbToXyz = context.workingRgbToXyz
                ?.takeIf { it.size == 9 }
                ?.copyOf()
                ?: when (context.target) {
                    DevelopTarget.SDR_REC709 -> SnceIlluminantEstimator.REC709_TO_XYZ.copyOf()
                    DevelopTarget.SDR_REC2020,
                    DevelopTarget.NDR_PQ_BT2020 -> SnceIlluminantEstimator.REC2020_TO_XYZ.copyOf()
                }
            val xyzToRgb = SnceColorPrimitives.invert3(rgbToXyz)
            val vc = SnceCam16.SnceViewingConditions(
                whiteXyz = SnceColorPrimitives.D65_XYZ.copyOf(),
                adaptingLuminance = context.sceneLuminanceLA,
                backgroundLuminance = VideoDevelopColorPipeline.DEFAULT_BACKGROUND_YB,
                surround = SnceCam16.Surround.AVERAGE
            )
            // Record-develop currently owns one genuine-NDR delivery mode: NDR10+. This is the SAME prepared
            // profile used by RecordDevelopP010ColorIngress/NdrNivukxDeliveryEngine on the CPU parity lane.
            // When a distinct NIVUKX record-develop mode is introduced, its mode must be threaded through the
            // GPU context and selected here in lock-step with the CPU encoder.
            val ndr = NdrToneOperator.ndr10Plus()

            return FloatArray(PARAM_FLOAT_COUNT).also { out ->
                out[0] = when (context.decodeSpace) {
                    DevelopColorSpace.REC709 -> DECODE_SPACE_REC709
                    DevelopColorSpace.REC2020 -> DECODE_SPACE_REC2020
                }
                out[1] = when (context.decodeTransfer) {
                    DevelopColorTransfer.SDR -> TRANSFER_SDR
                    DevelopColorTransfer.HLG -> TRANSFER_HLG
                    DevelopColorTransfer.PQ -> TRANSFER_PQ
                }
                out[2] = when (context.outputTransfer) {
                    OutputTransfer.SDR_REC709_OETF -> OUTPUT_SDR
                    OutputTransfer.PQ_ST2084 -> OUTPUT_PQ
                }
                out[3] = context.snceAppearanceStrength
                out[4] = if (context.ndrGenuineHdr) 1f else 0f
                out[5] = if (context.ndrGenuineHdr) ndr.diffuseWhiteNits.toFloat() else context.diffuseWhiteNits
                out[6] = if (context.widenToRec2020) 1f else 0f
                out[7] = context.sceneLuminanceLA
                rgbToXyz.copyInto(out, destinationOffset = 8)
                xyzToRgb.copyInto(out, destinationOffset = 17)
                out[26] = vc.fl
                out[27] = vc.n
                out[28] = vc.z
                out[29] = vc.nbb
                out[30] = vc.ncb
                out[31] = vc.aw
                out[32] = vc.dRgb[0]
                out[33] = vc.dRgb[1]
                out[34] = vc.dRgb[2]
                out[35] = vc.c
                out[36] = vc.nc
                out[37] = if (context.clampOutput) 1f else 0f
                out[PARAM_FILM_PRINT_STRENGTH] = context.filmPrintStrength
                out[PARAM_FL_ROOT] = vc.flRoot
                out[PARAM_T_EXPONENT_BASE] = vc.tExponentBase
                out[PARAM_CZ] = vc.cz
                out[PARAM_NDR_SIGNAL_GAMMA] = ndr.signalGamma.toFloat()
                out[PARAM_NDR_TOE_WIDTH] = ndr.toeWidth.toFloat()
                out[PARAM_NDR_TOE_AMOUNT] = ndr.toeAmount.toFloat()
                out[PARAM_NDR_PEAK_NITS] = ndr.peakNits.toFloat()
                out[PARAM_NDR_HIGHLIGHT_DESAT] = ndr.highlightDesat.toFloat()
                out[PARAM_NDR_SHADOW_MIDTONE_LIFT] = ndr.shadowMidtoneLift.toFloat()
                out[PARAM_NDR_MIDTONE_CONTRAST] = ndr.midtoneContrast.toFloat()
                out[PARAM_NDR_CHROMA_BOOST] = ndr.chromaBoost.toFloat()
                out[PARAM_NDR_SKY_CHROMA_BONUS] = ndr.skyChromaBonus.toFloat()
                out[PARAM_NDR_WARM_SKIN_RED_TAME] = if (context.ndrGenuineHdr) {
                    context.warmSkinRedTameStrength
                } else {
                    0f
                }
                out[PARAM_SDR_WARM_SKIN_RED_TAME] = if (context.ndrGenuineHdr) {
                    0f
                } else {
                    context.warmSkinRedTameStrength
                }

                // Append the baked 33^3 film-print LUT so the develop shader tetrahedral-samples the EXACT CPU
                // LUT (SnceNrliAnalyticLut.buildFilmPrintLut) that VideoDevelopColorPipeline Stage C uses.
                // Gated on the SAME condition as the CPU (filmLut != null && target != NDR): the analytic
                // grade cannot reproduce the LUT's interpolated near-black blue floor, which caused the
                // ~35-code channel-2 shadow parity divergence. When off, out[PARAM_FILM_LUT_SIZE] stays 0 and
                // the shader keeps the analytic path (the LUT tail stays zero-filled). No downgrade: the
                // first-frame parity gate + CPU fallback still guard every produced pixel.
                if (context.filmPrintStrength > 0f && context.target != DevelopTarget.NDR_PQ_BT2020) {
                    val filmLut = SnceNrliAnalyticLut.buildFilmPrintLut(FILM_LUT_EDGE)
                    if (filmLut.size == FILM_LUT_EDGE && filmLut.rgb.size == FILM_LUT_FLOAT_COUNT) {
                        filmLut.rgb.copyInto(out, destinationOffset = FILM_LUT_OFFSET)
                        out[PARAM_FILM_LUT_SIZE] = FILM_LUT_EDGE.toFloat()
                    }
                }
            }
        }
    }
}

internal object NativeRecordDevelopGpuBridge {
    private const val NATIVE_LIBRARY = "neoproc_vulkan"

    private val nativeLibraryAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary(NATIVE_LIBRARY)
            true
        }.getOrDefault(false)
    }

    fun isAvailable(): Boolean {
        if (!nativeLibraryAvailable) return false
        return runCatching { nativeStatus() == "ready" }.getOrDefault(false)
    }

    fun developP010ToRgb10Codes(
        payload: ByteArray,
        width: Int,
        height: Int,
        params: FloatArray
    ): ShortArray? {
        if (!nativeLibraryAvailable || width <= 0 || height <= 0 || payload.isEmpty()) return null
        return runCatching {
            nativeDevelopP010ToRgb10Codes(payload, width, height, params)
        }.getOrNull()
    }

    fun developP010ToPackedPayload(
        payload: ByteArray,
        width: Int,
        height: Int,
        params: FloatArray,
        outputFormat: RecordDevelopOutputFormat
    ): ByteArray? {
        if (!nativeLibraryAvailable || width <= 0 || height <= 0 || payload.isEmpty()) return null
        val formatCode = when (outputFormat) {
            RecordDevelopOutputFormat.R210_444 -> 0
            RecordDevelopOutputFormat.V210_422 -> 1
        }
        return runCatching {
            nativeDevelopP010ToPackedPayload(payload, width, height, params, formatCode)
        }.getOrNull()
    }

    fun developP010ToP010420Payload(
        payload: ByteArray,
        width: Int,
        height: Int,
        params: FloatArray,
        bt709Matrix: Boolean
    ): ByteArray? {
        if (!nativeLibraryAvailable || width <= 0 || height <= 0 || payload.isEmpty()) return null
        return runCatching {
            nativeDevelopP010ToP010420Payload(payload, width, height, params, bt709Matrix)
        }.getOrNull()
    }

    @JvmStatic
    private external fun nativeDevelopP010ToRgb10Codes(
        currentPayload: ByteArray,
        width: Int,
        height: Int,
        params: FloatArray
    ): ShortArray?

    @JvmStatic
    private external fun nativeDevelopP010ToPackedPayload(
        currentPayload: ByteArray,
        width: Int,
        height: Int,
        params: FloatArray,
        outputFormat: Int
    ): ByteArray?

    @JvmStatic
    private external fun nativeDevelopP010ToP010420Payload(
        currentPayload: ByteArray,
        width: Int,
        height: Int,
        params: FloatArray,
        bt709Matrix: Boolean
    ): ByteArray?

    @JvmStatic
    private external fun nativeStatus(): String
}
