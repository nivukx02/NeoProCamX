package com.neoproc.neoproccamx.camera

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  s1-f05 — CANONICAL SESSION ATTACH ORDER + HDR ROLE MAPPING (single source of truth)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * A cinema body's signal routing is deterministic: the monitoring (preview) and
 * the master (encoder) paths are attached in a fixed, lossless order so a
 * reconfigure can never reorder them into a black gap. This object is the
 * documented, testable contract for that order.
 *
 * The live session-build site (`CameraController.createSession`, the
 * `buildList { previewSurface … recordSurface … jpeg … raw … yuv … hardwareImport }`
 * at the top of the surface assembly) MUST attach surfaces in exactly
 * [CANONICAL_ORDER]; the per-surface dynamic-range role applied during that build
 * (`CameraController.hdrSurfaceRoleFor`) MUST match [hdrRoleFor] for the standard
 * (non-NPV10) surface roles. A characterization test pins both, and a source-text
 * contract test locks the live controller against reordering. This object adds no
 * live behavior on its own — it freezes the existing contract so a future edit
 * cannot silently reorder the attach sequence.
 *
 * Ordering rationale (preview first, encoder second, readers last):
 *  - The visible preview Surface attaches first so the monitoring path is always
 *    the lead output and a reconfigure brings it up before the optional readers.
 *  - The encoder (record/master) Surface attaches second so the full-fidelity
 *    record path is never starved by an optional analysis/still stream.
 *  - JPEG / RAW / YUV-analysis / hardware-import readers attach last and only when
 *    the active stream graph includes them.
 */
internal data class CameraSessionAttachStep(
    val kind: CameraSessionSurfaceKind,
    val hdrRole: HdrSurfaceRole
)

internal object CameraSessionAttachPlanPolicy {

    /**
     * The frozen attach order. Mirrors the live `buildList` in
     * `CameraController.createSession`. Order is contract; do not reorder without
     * updating the live builder and the order-lock tests in the same commit.
     */
    val CANONICAL_ORDER: List<CameraSessionSurfaceKind> = listOf(
        CameraSessionSurfaceKind.PREVIEW,
        CameraSessionSurfaceKind.RECORD,
        CameraSessionSurfaceKind.JPEG,
        CameraSessionSurfaceKind.RAW,
        CameraSessionSurfaceKind.YUV_ANALYSIS,
        CameraSessionSurfaceKind.HARDWARE_IMPORT
    )

    /**
     * The standard surface-kind → dynamic-range role mapping. This mirrors
     * `CameraController.hdrSurfaceRoleFor` for the canonical surface roles:
     * preview → PREVIEW, record/hardware-import (encoder feed) → ENCODER,
     * jpeg → JPEG, raw → RAW, yuv-analysis → ANALYSIS.
     *
     * NOTE: the live controller intentionally keeps the YUV reader Surface on the
     * ANALYSIS role even in the NPV10 ten-bit readout case (where its session
     * *kind* is RECORD) because the YUV reader carries pre-tonemap analysis data
     * and must stay on STANDARD. That NPV10 exception lives in the controller, not
     * here; this mapping is the canonical role for the surface's own kind.
     */
    fun hdrRoleFor(kind: CameraSessionSurfaceKind): HdrSurfaceRole = when (kind) {
        CameraSessionSurfaceKind.PREVIEW -> HdrSurfaceRole.PREVIEW
        CameraSessionSurfaceKind.RECORD -> HdrSurfaceRole.ENCODER
        CameraSessionSurfaceKind.JPEG -> HdrSurfaceRole.JPEG
        CameraSessionSurfaceKind.RAW -> HdrSurfaceRole.RAW
        CameraSessionSurfaceKind.YUV_ANALYSIS -> HdrSurfaceRole.ANALYSIS
        CameraSessionSurfaceKind.HARDWARE_IMPORT -> HdrSurfaceRole.ENCODER
        CameraSessionSurfaceKind.UNKNOWN -> HdrSurfaceRole.ANALYSIS
    }

    /**
     * Resolve the ordered attach plan for a session given which surfaces are
     * present (preview/record) and which optional readers the active stream graph
     * includes. The result is [CANONICAL_ORDER] filtered to the attached surfaces,
     * each tagged with its [hdrRoleFor] role — exactly the membership and order the
     * live `buildList` produces.
     */
    fun resolveAttachOrder(
        hasPreview: Boolean,
        hasRecord: Boolean,
        includeJpeg: Boolean,
        includeRaw: Boolean,
        includeYuvAnalysis: Boolean,
        includeHardwareImport: Boolean
    ): List<CameraSessionAttachStep> {
        val present = mapOf(
            CameraSessionSurfaceKind.PREVIEW to hasPreview,
            CameraSessionSurfaceKind.RECORD to hasRecord,
            CameraSessionSurfaceKind.JPEG to includeJpeg,
            CameraSessionSurfaceKind.RAW to includeRaw,
            CameraSessionSurfaceKind.YUV_ANALYSIS to includeYuvAnalysis,
            CameraSessionSurfaceKind.HARDWARE_IMPORT to includeHardwareImport
        )
        return CANONICAL_ORDER
            .filter { present[it] == true }
            .map { CameraSessionAttachStep(kind = it, hdrRole = hdrRoleFor(it)) }
    }
}
