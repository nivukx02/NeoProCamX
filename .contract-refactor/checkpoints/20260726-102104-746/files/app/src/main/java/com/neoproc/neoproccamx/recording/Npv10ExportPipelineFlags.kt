package com.neoproc.neoproccamx.recording

/**
 * s6-f05 no-downgrade gates for the NPV10→MOV export upgrade paths. The CP73 transfer-queue path is
 * requested by default but still requires device/runtime proof before any zero-copy claim is made;
 * the other gates stay default OFF until their device validation exists.
 */

/**
 * CP73 — zero-copy transfer queue. DEFAULT REQUESTED ON. The converter no longer performs the old
 * reader-local DirectBufferPool copy because that buffer was not consumed by the native path. Device
 * proof still owns any true zero-copy claim; unsupported devices keep the validated byte-array input
 * path plus the accelerator's reusable direct scratch buffers.
 *
 * On-device validation still owns the final proof; this flag must not downgrade export quality.
 */
internal object Npv10ExportTransferQueueFlag {
    @Volatile
    var enabled: Boolean = true
}

/**
 * CP81 — unify export denoise on the live nivukx coefficients ([Npv10ExportNivukxDenoiseCoeffs]).
 * OFF: export uses today's separate denoise math/coefficients unchanged. ON (device-gated): export
 * denoise reads the shared nivukx coefficient set so the export look matches the live kernel.
 */
internal object Npv10ExportUnifiedDenoiseFlag {
    @Volatile
    var enabled: Boolean = true
}

/**
 * CP80 — real motion ([Npv10ExportArgusMotionFoundation] / Argus·Chronos·Riven). OFF: the GPU
 * temporal blend keeps today's analytic synthetic flow. ON (device-gated): real SAD block-match
 * flow + directional deblur foundation replaces the synthetic flow.
 */
internal object Npv10ExportRealMotionFlag {
    @Volatile
    var enabled: Boolean = true
}
