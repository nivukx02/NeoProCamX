package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.rendering.GlVisiblePreviewPolicy.PreviewGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic contract for the additive per-mode preview gate. This is the single source of truth for
 * "which look, if any, the GL preview compositor applies" — it must keep the compositor FULLY BYPASSED
 * (IDENTITY) for HDR, the native Vulkan preview, and normal SDR, and engage ONLY for LOG / RAW preview.
 * The bypass rules are the load-bearing no-downgrade guarantee (an 8-bit GL stage must never sit in
 * front of the 10-bit HDR path), so they are asserted exhaustively.
 */
class GlVisiblePreviewPolicyTest {

    private fun grade(
        gateEnabled: Boolean = true,
        log: Boolean = false,
        raw: Boolean = false,
        hdr: Boolean = false,
        nativeVulkan: Boolean = false
    ): PreviewGrade = GlVisiblePreviewPolicy.resolveGrade(
        gateEnabled = gateEnabled,
        logPreviewActive = log,
        rawPreviewActive = raw,
        hdrActive = hdr,
        nativeVulkanPreviewActive = nativeVulkan
    )

    @Test
    fun logPreviewResolvesNeoLog() {
        assertEquals(PreviewGrade.NEOLOG, grade(log = true))
    }

    @Test
    fun rawPreviewResolvesRawMonitor() {
        assertEquals(PreviewGrade.RAW_MONITOR, grade(raw = true))
    }

    @Test
    fun normalSdrResolvesIdentity() {
        // Normal SDR/PRO uses the stable direct Camera2 SurfaceView path; the GL compositor is reserved
        // for LOG/RAW monitor transforms so P010 prearm cannot strand the visible preview behind GL.
        assertEquals(PreviewGrade.IDENTITY, grade())
    }

    @Test
    fun disabledGateForcesIdentityEvenForLogAndRaw() {
        assertEquals(PreviewGrade.IDENTITY, grade(gateEnabled = false, log = true))
        assertEquals(PreviewGrade.IDENTITY, grade(gateEnabled = false, raw = true))
    }

    @Test
    fun hdrForcesIdentityEvenWhenLogOrRawActive() {
        // NEVER route the 10-bit HDR path through the 8-bit compositor — that would downgrade it.
        assertEquals(PreviewGrade.IDENTITY, grade(log = true, hdr = true))
        assertEquals(PreviewGrade.IDENTITY, grade(raw = true, hdr = true))
        assertEquals(PreviewGrade.IDENTITY, grade(hdr = true))
    }

    @Test
    fun nativeVulkanPreviewForcesIdentity() {
        // Mutually exclusive with the native Vulkan visible-preview owner.
        assertEquals(PreviewGrade.IDENTITY, grade(log = true, nativeVulkan = true))
        assertEquals(PreviewGrade.IDENTITY, grade(raw = true, nativeVulkan = true))
    }

    @Test
    fun rawTakesPrecedenceOverLogWhenBothSignalled() {
        assertEquals(PreviewGrade.RAW_MONITOR, grade(log = true, raw = true))
    }

    @Test
    fun shouldCompositeMatchesNonIdentityGrade() {
        assertTrue(
            GlVisiblePreviewPolicy.shouldComposite(
                gateEnabled = true,
                logPreviewActive = true,
                rawPreviewActive = false,
                hdrActive = false,
                nativeVulkanPreviewActive = false
            )
        )
        assertFalse(
            GlVisiblePreviewPolicy.shouldComposite(
                gateEnabled = true,
                logPreviewActive = true,
                rawPreviewActive = false,
                hdrActive = true,
                nativeVulkanPreviewActive = false
            )
        )
        // Plain SDR stays direct camera -> SurfaceView.
        assertFalse(
            GlVisiblePreviewPolicy.shouldComposite(
                gateEnabled = true,
                logPreviewActive = false,
                rawPreviewActive = false,
                hdrActive = false,
                nativeVulkanPreviewActive = false
            )
        )
        // Gate OFF is still a bypass for plain SDR.
        assertFalse(
            GlVisiblePreviewPolicy.shouldComposite(
                gateEnabled = false,
                logPreviewActive = false,
                rawPreviewActive = false,
                hdrActive = false,
                nativeVulkanPreviewActive = false
            )
        )
    }
}
