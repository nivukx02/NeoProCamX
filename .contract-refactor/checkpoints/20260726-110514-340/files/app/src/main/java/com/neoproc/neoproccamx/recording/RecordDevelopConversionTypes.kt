package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.snce.SnceNeuralEnhancement
import java.util.Locale

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  RECORD-THEN-DEVELOP — Phase 2 offline develop value types
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Config + result types for [RecordDevelopMovConverter], the OFFLINE develop pass that turns a flat
 * LIGHT record intermediate (see [RecordDevelopMasterStore] / [VideoDevelopColorSidecar]) into a graded
 * uncompressed 10-bit master.
 *
 * NO-DOWNGRADE: none of this is reachable from the UI until the Phase-5 Settings cards are wired, and the
 * converter never touches the live record path. An [RecordDevelopConversionOptions] left at its defaults
 * runs the bounded elite SNCE colour-appearance stack with no creative LUT (a faithful develop); setting
 * the two strengths to 0 makes the develop a pipeline-passthrough (see [VideoDevelopColorPipeline]).
 */

/**
 * Uncompressed output container flavour, mirroring [RawToNeoLogMovConverter.OutputFormat]. Both are
 * bit-exact 10-bit; [R210_444] is the MASTER (no chroma subsampling, no YCbCr matrix — the develop RGB is
 * stored verbatim, so the colour tag can never mismatch a baked matrix). [V210_422] is the ~⅓-smaller
 * compatibility format that every professional NLE decodes.
 */
internal enum class RecordDevelopOutputFormat(
    val movMime: String,
    val packerFormat: RawTenBitPacker.Format
) {
    /** 4:4:4 10-bit RGB master ('r210') — most faithful, bit-exact to the develop. The default. */
    R210_444("video/r210", RawTenBitPacker.Format.R210_444),

    /** 4:2:2 10-bit Y′CbCr ('v210') — universally decodable in Resolve/Premiere/FCP, ~⅓ smaller. */
    V210_422("video/v210", RawTenBitPacker.Format.V210_422)
}

/**
 * Final delivery format for record-then-develop. The uncompressed formats map to the existing bit-exact
 * packer path. HEVC deliveries are encoded after the same CPU+GPU hybrid develop, from a developed P010
 * encoder input, so the private master and uncompressed masters remain unchanged.
 */
internal enum class RecordDevelopDeliveryFormat(
    val uiLabel: String,
    val auditLabel: String,
    val movMime: String,
    val compressed: Boolean,
    val uncompressedFormat: RecordDevelopOutputFormat?,
    val fileSuffix: String
) {
    R210_444_UNCOMPRESSED_MOV(
        uiLabel = "4:4:4 master (r210)",
        auditLabel = "R210_444_UNCOMPRESSED_MOV",
        movMime = "video/r210",
        compressed = false,
        uncompressedFormat = RecordDevelopOutputFormat.R210_444,
        fileSuffix = "r210_444"
    ),
    V210_422_UNCOMPRESSED_MOV(
        uiLabel = "4:2:2 compact (v210)",
        auditLabel = "V210_422_UNCOMPRESSED_MOV",
        movMime = "video/v210",
        compressed = false,
        uncompressedFormat = RecordDevelopOutputFormat.V210_422,
        fileSuffix = "v210_422"
    ),
    HEVC_MAIN10_420_MOV(
        uiLabel = "HEVC Main10 MOV 10-bit 4:2:0",
        auditLabel = "HEVC_MAIN10_420_MOV",
        movMime = "video/hevc",
        compressed = true,
        uncompressedFormat = null,
        fileSuffix = "hevc_main10_420"
    ),
    HEVC_MAIN10_422_MOV(
        uiLabel = "HEVC 10-bit 4:2:2 MOV",
        auditLabel = "HEVC_MAIN10_422_MOV_STRICT",
        movMime = "video/hevc",
        compressed = true,
        uncompressedFormat = null,
        fileSuffix = "hevc_main10_422"
    );

    companion object {
        fun fromLegacyOutput(format: RecordDevelopOutputFormat): RecordDevelopDeliveryFormat =
            when (format) {
                RecordDevelopOutputFormat.R210_444 -> R210_444_UNCOMPRESSED_MOV
                RecordDevelopOutputFormat.V210_422 -> V210_422_UNCOMPRESSED_MOV
            }

        fun fromLegacyCompatV210(compatV210: Boolean): RecordDevelopDeliveryFormat =
            if (compatV210) V210_422_UNCOMPRESSED_MOV else R210_444_UNCOMPRESSED_MOV
    }
}

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  Phase-4 — offline DENOISE / SHARPEN / TEMPORAL stage config
 * ════════════════════════════════════════════════════════════════════════════
 *
 * The develop runs the app's proven CPU enhancer ([Npv10ExportEnhancementPolicy.enhanceP010]) on the
 * decoded P010 BEFORE the colour develop, so noise is cleaned + detail lifted in the source's native
 * Y′CbCr code domain, then the clean signal is graded by [VideoDevelopColorPipeline]. Every COLOUR lane of
 * that enhancer is neutralised for this pass — a calibration-free profile (SDR/NDR both carry
 * `normalSdrCalibrationStrength = 0` ⇒ `applyNormalSdrColorCalibration` is a no-op), SNCE nudge at the
 * identity (saturation = 1, warmth = 0 ⇒ `applySnceChromaNudge` is a strict no-op), and no preview grade
 * LUT — so the LOOK is owned entirely by the develop pipeline and the enhancer only touches denoise/detail.
 *
 * TEMPORAL is deterministic + order-independent: each frame is enhanced against its predecessor's SOURCE
 * P010 (a pure function of the input), so the develop stays byte-identical regardless of CPU worker count.
 *
 * NO-DOWNGRADE / REVERSIBLE: [DISABLED] makes the whole stage a strict no-op, so the develop output is
 * byte-identical to the Phase-2 pure-develop passthrough. The elite default is [enabled] with temporal on.
 *
 * @param enabled         Master gate for the pre-develop enhance stage.
 * @param temporalDenoise Motion-gated temporal coherence lane (needs the predecessor's source P010).
 */
internal data class RecordDevelopEnhanceOptions(
    val enabled: Boolean = true,
    val temporalDenoise: Boolean = true
) {
    companion object {
        /** Elite offline default: full denoise/detail with temporal coherence. */
        val ELITE = RecordDevelopEnhanceOptions(enabled = true, temporalDenoise = true)

        /** Strict no-op ⇒ develop output byte-identical to the Phase-2 pure-develop passthrough. */
        val DISABLED = RecordDevelopEnhanceOptions(enabled = false, temporalDenoise = false)
    }
}

/**
 * Offline high-end AI tuning for record-develop SDR exports.
 *
 * This does not replace the 10-bit develop path. It provides bounded per-clip controls that the SDR
 * scene analyzer can lower/raise to avoid over-sharp, over-coloured, noisy, fog/rain/neon/sunset failures
 * while preserving the existing GPU colour-develop and 10-bit output.
 */
internal data class RecordDevelopHighEndAiTuning(
    val enabled: Boolean = true,
    val heavyModelProfile: String = "",
    val evidence: String = "",
    val sharpnessScale: Float = 1f,
    val denoiseScale: Float = 1f
) {
    init {
        require(sharpnessScale in 0.25f..1.25f) { "sharpnessScale must be in [0.25,1.25]" }
        require(denoiseScale in 0.75f..1.75f) { "denoiseScale must be in [0.75,1.75]" }
    }
}

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  Phase-6 (R6) — NDR HDR delivery mode
 * ════════════════════════════════════════════════════════════════════════════
 *
 * How the NDR (HDR) develop maps the graded linear signal into the PQ container.
 *
 * [GENUINE_HDR] (default, R6): routes the NDR egress through the canonical, host-golden
 * `NdrNivukxDeliveryEngine.deliverPqCodes` so diffuse white lands at 203 nits (BT.2408) and highlights roll
 * through a C1 exponential shoulder toward the NDR master peak (1420 nits) — the SAME nit-accurate PQ
 * delivery the live record shader and the PQ preview compositor match (one source of truth ⇒ develop == live
 * by construction). Genuinely HDR, un-blown.
 *
 * [LEGACY_SDR_ANCHORED]: the pre-R6 behaviour — an ACES SDR-fit RRT+ODT clamped to display [0,1] then
 * flat-anchored to [RecordDevelopConversionOptions.ndrDiffuseWhiteNits] before PQ encode, so the delivery
 * PEAKS at diffuse white (no HDR headroom — an SDR tone in a PQ container). Retained ONLY as a byte-for-byte
 * reversible A/B fallback; it is NOT a quality target.
 */
internal enum class RecordDevelopNdrDelivery { GENUINE_HDR, LEGACY_SDR_ANCHORED }

/**
 * The develop knobs the offline pass exposes. Threaded into [VideoDevelopColorOptions]; the delivery
 * target (SDR/NDR) is derived from the clip's [VideoDevelopColorSidecar] rather than set here, so the
 * develop always matches how the intermediate was actually recorded.
 *
 * @param snceAppearanceStrength Stage-A SNCE colour-appearance blend (0 = passthrough, 1 = full).
 * @param filmPrintStrength      Stage-C creative film-print LUT blend (SDR only; NDR forces it off).
 * @param outputFormat           r210 master (default) or v210 compat.
 * @param memoryBudgetBytes      Peak resident develop-buffer budget; caps the CPU worker count.
 * @param ndrDiffuseWhiteNits    NDR [LEGACY_SDR_ANCHORED] only: the display-referred [0,1] ODT output is
 *                               anchored to this nit level before PQ encode (BT.2408 reference white ≈ 203).
 *                               Ignored by [GENUINE_HDR], whose diffuse white is the canonical engine's 203.
 * @param ndrDelivery            NDR only: genuine-HDR canonical delivery (default) vs the legacy 203-anchor.
 * @param enhance                Phase-4 pre-develop denoise/sharpen/temporal stage (elite-ON, reversible).
 * @param highEndAiTuning        Bounded SDR scene-adaptive quality guard for colour, denoise, and sharpness.
 * @param requireGpuDevelop      Require the SDR/NDR GPU colour-develop lane to initialise and pass the
 *                               clip gate; unavailable/rejected/null GPU dispatch fails the conversion
 *                               instead of silently falling back to CPU-only colour develop. The explicit
 *                               hybrid CPU lane may still process its configured share after GPU acceptance.
 */
internal data class RecordDevelopConversionOptions(
    val snceAppearanceStrength: Float = 0.65f, // Reduced from 1f to prevent red over-boost in offline conversions
    val filmPrintStrength: Float = 0f,
    val outputFormat: RecordDevelopOutputFormat = RecordDevelopOutputFormat.R210_444,
    val deliveryFormat: RecordDevelopDeliveryFormat? = null,
    val memoryBudgetBytes: Long = ConversionParallelismPolicy.resolveMemoryBudget(),
    val ndrDiffuseWhiteNits: Float = DEFAULT_NDR_DIFFUSE_WHITE_NITS,
    val ndrDelivery: RecordDevelopNdrDelivery = RecordDevelopNdrDelivery.GENUINE_HDR,
    val semanticClass: SnceNeuralEnhancement.SemanticClass = SnceNeuralEnhancement.SemanticClass.OTHER,
    val enhance: RecordDevelopEnhanceOptions = RecordDevelopEnhanceOptions.ELITE,
    val highEndAiTuning: RecordDevelopHighEndAiTuning = RecordDevelopHighEndAiTuning(),
    val requireGpuDevelop: Boolean = false
) {
    init {
        require(snceAppearanceStrength in 0f..1f) { "snceAppearanceStrength must be in [0,1]" }
        require(filmPrintStrength in 0f..1f) { "filmPrintStrength must be in [0,1]" }
        require(ndrDiffuseWhiteNits > 0f) { "ndrDiffuseWhiteNits must be positive" }
    }

    companion object {
        /** BT.2408 HDR reference white (graphics/diffuse white) in nits, for anchoring SDR-fit ODT → PQ. */
        const val DEFAULT_NDR_DIFFUSE_WHITE_NITS: Float = 203f
    }

    fun resolvedDeliveryFormat(): RecordDevelopDeliveryFormat =
        deliveryFormat ?: RecordDevelopDeliveryFormat.fromLegacyOutput(outputFormat)
}

/**
 * Tamper-evidence + provenance record for one SUCCESSFUL develop, modelled on the NPV10 converter's audit
 * but lean (the ~40 NPV10-specific fields don't apply). [inputMasterHashUnchanged] proves the private
 * intermediate was read-only. Failures (empty stream, bad geometry) THROW after the writer is aborted, so
 * a partial/corrupt master is never left behind — mirroring the NPV10 converter's `require` + `abort()`.
 */
internal data class RecordDevelopConversionAudit(
    val outputPath: String,
    val outputBytes: Long,
    val frameCount: Long,
    val sourceKind: RecordDevelopMasterKind,
    val width: Int,
    val height: Int,
    val fps: Int,
    val developTarget: DevelopTarget,
    val outputFormat: RecordDevelopOutputFormat,
    val deliveryFormat: RecordDevelopDeliveryFormat = RecordDevelopDeliveryFormat.fromLegacyOutput(outputFormat),
    val colorPrimaries: Int,
    val transferCharacteristics: Int,
    val matrixCoefficients: Int,
    val fullRange: Boolean,
    val hdrMasteringAttached: Boolean,
    val sidecarPresent: Boolean,
    val snceAppearanceStrength: Float,
    val filmPrintStrength: Float,
    val acesToneMap: Boolean,
    val workerCount: Int,
    val inputMasterHashUnchanged: Boolean,
    val enhanceApplied: Boolean,
    val enhanceProfile: String,
    val temporalDenoise: Boolean,
    /** Phase-6 (R6): NDR was delivered as genuine HDR via the canonical engine (false = SDR path / legacy). */
    val ndrGenuineHdr: Boolean,
    /** Frames whose pre-develop P010 denoise/detail stage was produced by the native GPU enhancer. */
    val gpuEnhanceFrames: Long = 0L,
    /** Actual P010 enhance lane used for the clip: CPU, GPU, or mixed GPU-with-CPU-fallback. */
    val enhanceProcessingPath: String = "CPU_P010_ENHANCE",
    /** Strict colour-develop contract: fail instead of producing CPU-developed frames. */
    val requireGpuDevelop: Boolean = false,
    /** Phase-7: ≥1 written frame's per-pixel colour develop was ACTUALLY produced on the GPU (not merely the
     *  parity decision — a clip that commits to GPU but then falls every frame back to CPU reports false). */
    val gpuAccelerated: Boolean
) {
    /** Human-readable certificate written next to the output by [RecordDevelopMovConverter.convert]. */
    fun toCertificateText(): String = buildString {
        appendLine("NeoProCamX Record-Develop Conversion Certificate")
        appendLine("sourceKind=$sourceKind")
        appendLine("output=$outputPath")
        appendLine("outputBytes=$outputBytes")
        appendLine("frameCount=$frameCount")
        appendLine("geometry=${width}x$height@${fps}fps")
        appendLine("developTarget=$developTarget")
        appendLine("outputFormat=${outputFormat.name} (${outputFormat.movMime})")
        appendLine("deliveryFormat=${deliveryFormat.auditLabel} (${deliveryFormat.movMime})")
        appendLine(
            String.format(
                Locale.US,
                "colr.nclx=primaries:%d transfer:%d matrix:%d fullRange:%b",
                colorPrimaries, transferCharacteristics, matrixCoefficients, fullRange
            )
        )
        appendLine("hdrMasteringAttached=$hdrMasteringAttached")
        appendLine("sidecarPresent=$sidecarPresent")
        appendLine(
            String.format(
                Locale.US,
                "develop=snce:%.3f film:%.3f aces:%b workers:%d",
                snceAppearanceStrength, filmPrintStrength, acesToneMap, workerCount
            )
        )
        appendLine(
            String.format(
                Locale.US,
                "enhance=applied:%b profile:%s temporal:%b",
                enhanceApplied, enhanceProfile, temporalDenoise
            )
        )
        appendLine("ndrGenuineHdr=$ndrGenuineHdr")
        appendLine("gpuEnhanceFrames=$gpuEnhanceFrames")
        appendLine("enhanceProcessingPath=$enhanceProcessingPath")
        appendLine("requireGpuDevelop=$requireGpuDevelop")
        appendLine("gpuAccelerated=$gpuAccelerated")
        appendLine("inputMasterHashUnchanged=$inputMasterHashUnchanged")
    }
}
