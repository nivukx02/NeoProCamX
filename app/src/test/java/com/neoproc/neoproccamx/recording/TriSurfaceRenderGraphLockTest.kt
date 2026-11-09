package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s6-f06 / CP82 — build-time tri-surface render-graph-signature lock. A change to any one surface's
 * look transform without the others must surface failure codes (the CI gate). Signatures are
 * derived from inputs (length 64 SHA-256 hex), never asserted as literal hashes.
 */
class TriSurfaceRenderGraphLockTest {

    private fun endpoint(
        lookHash: String = "a".repeat(64),
        lookSourceIdentity: String = "OMNICLARITY_LIVE_LOOK_V5"
    ) = TriSurfaceParityEndpoint(
        lookCoefficientsSha256 = lookHash,
        kernelSignature = "nivukx_denoise@gat",
        colorScience = "NeoGamutApex",
        colorSpace = "Rec.2020",
        stagesSignature = "idt>grade>odt",
        outputTransfer = "ST2084",
        outputGamut = "BT2020",
        outputBitDepth = 10,
        lookSourceIdentity = lookSourceIdentity
    )

    @Test
    fun matchingTriSurfaceEndpointsPassParityWithNoFailureCodes() {
        val e = endpoint()
        val result = TriSurfaceRenderGraphLock.resolveTriSurfaceLock(e, e.copy(), e.copy())
        assertTrue(result.parityPassed)
        assertTrue(result.failureCodes.isEmpty())
        assertEquals(64, result.previewRecordSignature.length)
        assertEquals(64, result.previewExportSignature.length)
        assertEquals(64, result.recordExportSignature.length)
    }

    @Test
    fun divergedExportLookHashFailsRecordExportAndPreviewExportParityOnly() {
        val live = endpoint()
        val export = endpoint(lookHash = "b".repeat(64))
        val result = TriSurfaceRenderGraphLock.resolveTriSurfaceLock(live, live.copy(), export)
        assertFalse(result.parityPassed)
        assertTrue(result.failureCodes.contains("RECORD_EXPORT_LOOK_HASH_MISMATCH"))
        assertTrue(result.failureCodes.contains("PREVIEW_EXPORT_LOOK_HASH_MISMATCH"))
        assertFalse(result.failureCodes.any { it.startsWith("PREVIEW_RECORD_") })
    }

    @Test
    fun forkedExportLookSourceIdentityEmitsExportLookForkedFromLive() {
        val live = endpoint()
        val forkedExport = endpoint(lookSourceIdentity = "NPV10_EXPORT_OWN_SNCE_FORK")
        val result = TriSurfaceRenderGraphLock.resolveTriSurfaceLock(live, live.copy(), forkedExport)
        assertFalse(result.parityPassed)
        assertTrue(result.failureCodes.contains(TriSurfaceRenderGraphLock.FORKED_EXPORT))
    }
}
