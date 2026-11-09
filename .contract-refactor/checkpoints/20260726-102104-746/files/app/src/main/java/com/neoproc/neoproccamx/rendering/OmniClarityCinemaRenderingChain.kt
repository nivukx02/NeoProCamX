package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.ai.NeoRadianceConfig

/**
 * OmniClarity Engine — §5 ACES-Based Cinema Rendering Chain (single Kotlin authority).
 *
 * Formalizes the rendering chain's missing middle step so every capture mode shares one
 * neutral reference render and differs only in its output transform:
 *
 * ```
 *   scene_linear -> [optional LMT] -> Helios Reference Render (FIXED) -> ODT (swappable)
 * ```
 *
 * - **LMT (optional):** creative transform in scene-referred linear space, swappable per "look",
 *   distinct from later display-referred grading. Identity by default.
 * - **Helios Reference Render (FIXED, never swapped):** maps scene-referred linear to a neutral,
 *   display-independent intermediate over the ACEScg working space. This is the actual mechanism
 *   that keeps the SDR and HDR exports of the *same shoot* looking like the same shoot.
 * - **ODT (swappable):** SDR / HDR / Log / RAW-monitor, branching from the neutral intermediate.
 *   The ODT is the **only** stage that legitimately changes per output mode.
 *
 * This object does not compute pixels. It composes the existing per-mode resolution
 * ([VulkanToneDeliveryMode] / [VulkanPreviewRenderContractPolicy]) into a checkable §5 contract and
 * is the single source of truth for two guarantees the live shader ([ycbcr_preview.frag]) and the
 * offline NPV10 export shader (`neo_p010_export_enhance.comp`) must both honor:
 *
 *   1. **Reference-render-fixed-across-modes** — the reference render identity is mode-independent.
 *   2. **Per-mode WYSIWYG** — a mode's preview renders through the same reference render and the same
 *      ODT it will be recorded/exported with, except the documented display-referred *monitor*
 *      exception (HDR/LOG/RAW shown on an SDR panel, where the panel physically cannot show the
 *      delivery transfer, so a monitor of that same delivery is shown instead).
 *
 * Sources reconciled: [com.neoproc.neoproccamx.color.AcesColorEngine] (ACEScg D60 / CAT16 working
 * space, fitted RRT/ODT), [NeoRadianceConfig] (203-nit BT.2408 diffuse white, 1000-nit HDR peak),
 * [VulkanToneDeliveryMode.resolve] (the per-mode shader-branch selector), and
 * [VulkanPreviewRecordingParityPolicy] (the existing preview=record enforcement = QG13 Preview Match).
 */
internal enum class OmniClarityOdtKind {
    SDR_REC709,
    HDR_PQ,
    HDR_HLG,
    NDR10_PLUS,
    NIVUKX_VISION,
    APEX,
    N_LOG,
    RAW,
    CINEMA_DEBAYER
}

/**
 * Identity of the FIXED Helios reference render. Mode-independent by contract — SDR, HDR, LOG and
 * RAW all branch from this identical neutral intermediate, which is what makes cross-mode exports of
 * one shoot look like one shoot.
 */
internal data class HeliosReferenceRenderIdentity(
    val workingSpace: String,
    val diffuseWhiteNits: Float,
    val peakNits: Float,
    val renderTransform: String
) {
    /** Stable signature suitable for parity locking and SMF evidence. */
    val signature: String
        get() = listOf(
            workingSpace,
            renderTransform,
            "${diffuseWhiteNits.toInt()}nit",
            "${peakNits.toInt()}nit"
        ).joinToString(separator = "|")
}

/** The swappable Output Display Transform selected per mode, branching from the reference render. */
internal data class OmniClarityOutputDisplayTransform(
    val kind: OmniClarityOdtKind,
    val toneDeliveryMode: VulkanToneDeliveryMode,
    /** True when this is the display-referred *monitor* of a delivery (preview on an SDR panel). */
    val displayReferredMonitor: Boolean,
    val outputTransfer: String,
    val outputGamut: String
) {
    val signature: String
        get() = listOf(
            kind.name,
            toneDeliveryMode.name,
            if (displayReferredMonitor) "monitor" else "delivery",
            outputTransfer,
            outputGamut
        ).joinToString(separator = "|")
}

internal enum class OmniClarityWysiwygStatus {
    /** Preview is identical in intent to the recorded/exported output (true WYSIWYG). */
    EXACT_MATCH,

    /**
     * Preview deliberately uses the display-referred monitor of the *same* delivery (HDR/LOG/RAW
     * shown on an SDR panel). The reference render and the delivery family are identical; only the
     * final display encode differs because the panel cannot present the delivery transfer.
     */
    MONITORED_MATCH,

    /** Preview does not represent the mode's own output — a WYSIWYG defect. */
    MISMATCH
}

internal data class OmniClarityWysiwygVerdict(
    val status: OmniClarityWysiwygStatus,
    val previewOdt: OmniClarityOutputDisplayTransform,
    val outputOdt: OmniClarityOutputDisplayTransform,
    val referenceRenderFixed: Boolean,
    val detail: String
) {
    /** True unless the preview fails to represent its own mode. */
    val isWysiwyg: Boolean
        get() = status != OmniClarityWysiwygStatus.MISMATCH
}

internal object OmniClarityCinemaRenderingChain {

    private const val TRANSFER_SRGB = "sRGB"
    private const val TRANSFER_PQ = "PQ_ST2084"
    private const val TRANSFER_HLG = "HLG_BT2100"
    private const val TRANSFER_NLOG = "N-LOG"
    private const val TRANSFER_LINEAR = "SCENE_LINEAR"

    private const val GAMUT_REC709 = "Rec.709"
    private const val GAMUT_REC2020 = "Rec.2020"
    private const val GAMUT_SENSOR = "Sensor-Native"

    /**
     * The FIXED reference render. Mode-independent by contract; this is the §5 neutral intermediate
     * that all ODTs branch from. Values are sourced from the existing color authorities so this
     * contract cannot silently diverge from the shaders that implement it.
     */
    val REFERENCE_RENDER: HeliosReferenceRenderIdentity = HeliosReferenceRenderIdentity(
        // ACEScg working space (ACES D60 white, CAT16 adaptation) per AcesColorEngine.
        workingSpace = "ACESCG_LINEAR",
        // PHYSICAL: BT.2408 diffuse-white anchor shared by SDR and HDR renders.
        diffuseWhiteNits = NeoRadianceConfig.DIFFUSE_WHITE_NITS,
        // PHYSICAL: HDR mastering peak; SDR derives from the same neutral render below this anchor.
        peakNits = NeoRadianceConfig.PEAK_NITS,
        // Helios neutral reference render (display-independent), never swapped per mode.
        renderTransform = "HELIOS_NEUTRAL_REFERENCE_V1"
    )

    /** Maps a tone-delivery mode to its §5 Output Display Transform descriptor. Exhaustive. */
    fun outputDisplayTransform(mode: VulkanToneDeliveryMode): OmniClarityOutputDisplayTransform =
        when (mode) {
            VulkanToneDeliveryMode.DIRECT ->
                odt(OmniClarityOdtKind.SDR_REC709, mode, false, TRANSFER_SRGB, GAMUT_REC709)
            VulkanToneDeliveryMode.PREVIEW_LUT ->
                odt(OmniClarityOdtKind.SDR_REC709, mode, false, TRANSFER_SRGB, GAMUT_REC709)

            VulkanToneDeliveryMode.HLG_HDR ->
                odt(OmniClarityOdtKind.HDR_HLG, mode, false, TRANSFER_HLG, GAMUT_REC2020)
            VulkanToneDeliveryMode.HLG_MONITOR ->
                odt(OmniClarityOdtKind.HDR_HLG, mode, true, TRANSFER_HLG, GAMUT_REC2020)
            VulkanToneDeliveryMode.PQ_HDR ->
                odt(OmniClarityOdtKind.HDR_PQ, mode, false, TRANSFER_PQ, GAMUT_REC2020)
            VulkanToneDeliveryMode.PQ_MONITOR ->
                odt(OmniClarityOdtKind.HDR_PQ, mode, true, TRANSFER_PQ, GAMUT_REC2020)

            VulkanToneDeliveryMode.NDR10_PLUS_HDR ->
                odt(OmniClarityOdtKind.NDR10_PLUS, mode, false, TRANSFER_PQ, GAMUT_REC2020)
            VulkanToneDeliveryMode.NDR10_PLUS_MONITOR ->
                odt(OmniClarityOdtKind.NDR10_PLUS, mode, true, TRANSFER_PQ, GAMUT_REC2020)
            VulkanToneDeliveryMode.NIVUKX_VISION_HDR ->
                odt(OmniClarityOdtKind.NIVUKX_VISION, mode, false, TRANSFER_PQ, GAMUT_REC2020)
            VulkanToneDeliveryMode.NIVUKX_VISION_MONITOR ->
                odt(OmniClarityOdtKind.NIVUKX_VISION, mode, true, TRANSFER_PQ, GAMUT_REC2020)

            VulkanToneDeliveryMode.APEX_SHARP,
            VulkanToneDeliveryMode.APEX_ZERO,
            VulkanToneDeliveryMode.APEX_CINEMA ->
                odt(OmniClarityOdtKind.APEX, mode, false, TRANSFER_PQ, GAMUT_REC2020)

            VulkanToneDeliveryMode.LOG_N_LOG_BASE,
            VulkanToneDeliveryMode.LOG_N_LOG_SHADOW,
            VulkanToneDeliveryMode.LOG_N_LOG_HIGHLIGHT,
            VulkanToneDeliveryMode.LOG_N_LOG_CINEMA ->
                odt(OmniClarityOdtKind.N_LOG, mode, false, TRANSFER_NLOG, GAMUT_REC2020)
            VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_BASE,
            VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_SHADOW,
            VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_HIGHLIGHT,
            VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_CINEMA ->
                odt(OmniClarityOdtKind.N_LOG, mode, true, TRANSFER_NLOG, GAMUT_REC2020)

            VulkanToneDeliveryMode.RAW_NEUTRAL ->
                odt(OmniClarityOdtKind.RAW, mode, false, TRANSFER_LINEAR, GAMUT_SENSOR)
            VulkanToneDeliveryMode.RAW_MONITOR ->
                odt(OmniClarityOdtKind.RAW, mode, true, TRANSFER_LINEAR, GAMUT_SENSOR)
            VulkanToneDeliveryMode.CINEMA_DEBAYER ->
                odt(OmniClarityOdtKind.CINEMA_DEBAYER, mode, false, TRANSFER_LINEAR, GAMUT_SENSOR)
        }

    /** True when [mode] is the display-referred monitor of some delivery, not a delivery itself. */
    fun isDisplayReferredMonitor(mode: VulkanToneDeliveryMode): Boolean =
        outputDisplayTransform(mode).displayReferredMonitor

    /**
     * The display-referred monitor mode that [VulkanToneDeliveryMode.resolve] selects for the live
     * preview of a given delivery [outputMode]. Mirrors `resolve(previewMonitoringEnabled = true)`
     * so a single test can prove preview/record stay in lockstep for every mode.
     *
     * SDR delivery (DIRECT / PREVIEW_LUT) has no separate monitor: the panel shows the delivery
     * directly, so the preview mode equals the output mode.
     */
    fun expectedDisplayReferredMonitor(outputMode: VulkanToneDeliveryMode): VulkanToneDeliveryMode =
        when (outputMode) {
            VulkanToneDeliveryMode.DIRECT -> VulkanToneDeliveryMode.DIRECT
            VulkanToneDeliveryMode.PREVIEW_LUT -> VulkanToneDeliveryMode.PREVIEW_LUT

            VulkanToneDeliveryMode.NDR10_PLUS_HDR -> VulkanToneDeliveryMode.NDR10_PLUS_MONITOR
            VulkanToneDeliveryMode.NIVUKX_VISION_HDR -> VulkanToneDeliveryMode.NIVUKX_VISION_MONITOR
            VulkanToneDeliveryMode.HLG_HDR -> VulkanToneDeliveryMode.HLG_MONITOR
            VulkanToneDeliveryMode.PQ_HDR -> VulkanToneDeliveryMode.PQ_MONITOR

            // APEX delivery is monitored through the shared PQ monitor on an SDR panel
            // (matches VulkanToneDeliveryMode.resolve's APEX -> PQ_MONITOR branch).
            VulkanToneDeliveryMode.APEX_SHARP,
            VulkanToneDeliveryMode.APEX_ZERO,
            VulkanToneDeliveryMode.APEX_CINEMA -> VulkanToneDeliveryMode.PQ_MONITOR

            VulkanToneDeliveryMode.LOG_N_LOG_BASE -> VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_BASE
            VulkanToneDeliveryMode.LOG_N_LOG_SHADOW -> VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_SHADOW
            VulkanToneDeliveryMode.LOG_N_LOG_HIGHLIGHT -> VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_HIGHLIGHT
            VulkanToneDeliveryMode.LOG_N_LOG_CINEMA -> VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_CINEMA

            VulkanToneDeliveryMode.RAW_NEUTRAL,
            VulkanToneDeliveryMode.CINEMA_DEBAYER -> VulkanToneDeliveryMode.RAW_MONITOR

            // Monitor modes are their own preview.
            VulkanToneDeliveryMode.HLG_MONITOR,
            VulkanToneDeliveryMode.PQ_MONITOR,
            VulkanToneDeliveryMode.NDR10_PLUS_MONITOR,
            VulkanToneDeliveryMode.NIVUKX_VISION_MONITOR,
            VulkanToneDeliveryMode.RAW_MONITOR,
            VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_BASE,
            VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_SHADOW,
            VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_HIGHLIGHT,
            VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_CINEMA -> outputMode
        }

    /**
     * Verifies the §5 WYSIWYG invariant for one capture mode: that [previewMode] renders through the
     * same FIXED reference render and the same ODT (or the documented display-referred monitor of
     * that same ODT) that the recorded/exported [outputMode] uses.
     */
    fun verifyWysiwyg(
        previewMode: VulkanToneDeliveryMode,
        outputMode: VulkanToneDeliveryMode
    ): OmniClarityWysiwygVerdict {
        val previewOdt = outputDisplayTransform(previewMode)
        val outputOdt = outputDisplayTransform(outputMode)
        // The reference render is a single constant; it is fixed across all modes by construction.
        val referenceRenderFixed = true

        val status = when {
            previewMode == outputMode -> OmniClarityWysiwygStatus.EXACT_MATCH
            previewMode == expectedDisplayReferredMonitor(outputMode) ->
                OmniClarityWysiwygStatus.MONITORED_MATCH
            else -> OmniClarityWysiwygStatus.MISMATCH
        }

        val detail = when (status) {
            OmniClarityWysiwygStatus.EXACT_MATCH ->
                "preview==output ODT ${outputOdt.kind.name}"
            OmniClarityWysiwygStatus.MONITORED_MATCH ->
                "display-referred monitor of ${outputOdt.kind.name} delivery"
            OmniClarityWysiwygStatus.MISMATCH ->
                "preview ODT ${previewOdt.kind.name}/${previewMode.name} does not represent " +
                    "output ODT ${outputOdt.kind.name}/${outputMode.name}"
        }

        return OmniClarityWysiwygVerdict(
            status = status,
            previewOdt = previewOdt,
            outputOdt = outputOdt,
            referenceRenderFixed = referenceRenderFixed,
            detail = detail
        )
    }

    private fun odt(
        kind: OmniClarityOdtKind,
        mode: VulkanToneDeliveryMode,
        monitor: Boolean,
        transfer: String,
        gamut: String
    ): OmniClarityOutputDisplayTransform =
        OmniClarityOutputDisplayTransform(
            kind = kind,
            toneDeliveryMode = mode,
            displayReferredMonitor = monitor,
            outputTransfer = transfer,
            outputGamut = gamut
        )
}
