package com.neoproc.neoproccamx.recording

import java.security.MessageDigest

/**
 * s6-f06 / CP82 — tri-surface (preview / record / export) render-graph-signature lock. Extends the
 * 2-surface VulkanPreviewRecordingParityPolicy pattern to a third endpoint so a change to any one
 * surface's look transform without the others is caught by a build-time CI test. Pure / build-time
 * only — no camera-path caller, so it cannot affect recorded or exported bytes.
 *
 * The export endpoint is anchored to the LIVE look source identity (preview is the on-monitor
 * source-of-truth): a forked export look surfaces [FORKED_EXPORT] regardless of hash equality.
 */
internal data class TriSurfaceParityEndpoint(
    val lookCoefficientsSha256: String,
    val kernelSignature: String,
    val colorScience: String,
    val colorSpace: String,
    val stagesSignature: String,
    val outputTransfer: String,
    val outputGamut: String,
    val outputBitDepth: Int,
    val lookSourceIdentity: String
)

internal data class TriSurfaceRenderGraphLockResult(
    val previewRecordSignature: String,
    val previewExportSignature: String,
    val recordExportSignature: String,
    val failureCodes: List<String>
) {
    val parityPassed: Boolean get() = failureCodes.isEmpty()
}

internal object TriSurfaceRenderGraphLock {

    const val FORKED_EXPORT = "EXPORT_LOOK_FORKED_FROM_LIVE"

    fun resolveTriSurfaceLock(
        preview: TriSurfaceParityEndpoint,
        record: TriSurfaceParityEndpoint,
        export: TriSurfaceParityEndpoint
    ): TriSurfaceRenderGraphLockResult {
        val failures = mutableListOf<String>()
        failures += pairFailures("PREVIEW_RECORD", preview, record)
        failures += pairFailures("PREVIEW_EXPORT", preview, export)
        failures += pairFailures("RECORD_EXPORT", record, export)
        // Export must reference the live look source identity (preview is the live monitor truth).
        if (export.lookSourceIdentity != preview.lookSourceIdentity) {
            failures += FORKED_EXPORT
        }
        return TriSurfaceRenderGraphLockResult(
            previewRecordSignature = pairSignature(preview, record),
            previewExportSignature = pairSignature(preview, export),
            recordExportSignature = pairSignature(record, export),
            failureCodes = failures
        )
    }

    private fun pairFailures(
        pair: String,
        a: TriSurfaceParityEndpoint,
        b: TriSurfaceParityEndpoint
    ): List<String> {
        val codes = mutableListOf<String>()
        if (a.lookCoefficientsSha256 != b.lookCoefficientsSha256) codes += "${pair}_LOOK_HASH_MISMATCH"
        if (a.kernelSignature != b.kernelSignature) codes += "${pair}_KERNEL_MISMATCH"
        if (a.colorScience != b.colorScience) codes += "${pair}_COLOR_SCIENCE_MISMATCH"
        if (a.colorSpace != b.colorSpace) codes += "${pair}_COLOR_SPACE_MISMATCH"
        if (a.stagesSignature != b.stagesSignature) codes += "${pair}_STAGES_MISMATCH"
        if (a.outputTransfer != b.outputTransfer ||
            a.outputGamut != b.outputGamut ||
            a.outputBitDepth != b.outputBitDepth
        ) {
            codes += "${pair}_OUTPUT_IDENTITY_MISMATCH"
        }
        return codes
    }

    private fun signatureOf(e: TriSurfaceParityEndpoint): String =
        sha256(
            listOf(
                e.lookCoefficientsSha256,
                e.kernelSignature,
                e.colorScience,
                e.colorSpace,
                e.stagesSignature,
                e.outputTransfer,
                e.outputGamut,
                e.outputBitDepth.toString(),
                e.lookSourceIdentity
            ).joinToString("|")
        )

    private fun pairSignature(a: TriSurfaceParityEndpoint, b: TriSurfaceParityEndpoint): String =
        sha256(signatureOf(a) + ":" + signatureOf(b))

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
