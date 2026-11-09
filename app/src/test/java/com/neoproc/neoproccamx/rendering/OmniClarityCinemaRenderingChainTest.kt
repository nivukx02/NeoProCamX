package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.NeoLogCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the OmniClarity §5 rendering-chain contract:
 *  - the Helios reference render is FIXED and mode-independent, and
 *  - every mode's live preview is the same mode's own ODT (or the documented display-referred
 *    monitor of that same delivery) — i.e. per-mode WYSIWYG.
 *
 * The headline test [resolverPreviewAndOutputAreWysiwygForEveryMode] binds the contract to the real
 * [VulkanToneDeliveryMode.resolve] selector, so any future change that breaks the preview/record
 * correspondence fails here.
 */
class OmniClarityCinemaRenderingChainTest {

    @Test
    fun referenceRenderIsFixedAndModeIndependent() {
        val ref = OmniClarityCinemaRenderingChain.REFERENCE_RENDER
        assertEquals("ACESCG_LINEAR", ref.workingSpace)
        assertEquals(203f, ref.diffuseWhiteNits, 0.0001f)
        assertEquals(1000f, ref.peakNits, 0.0001f)
        assertEquals("HELIOS_NEUTRAL_REFERENCE_V1", ref.renderTransform)
        assertEquals("ACESCG_LINEAR|HELIOS_NEUTRAL_REFERENCE_V1|203nit|1000nit", ref.signature)
    }

    @Test
    fun everyToneDeliveryModeResolvesToAnOdtDescriptor() {
        VulkanToneDeliveryMode.entries.forEach { mode ->
            val odt = OmniClarityCinemaRenderingChain.outputDisplayTransform(mode)
            assertEquals(mode, odt.toneDeliveryMode)
            assertTrue("transfer must be set for $mode", odt.outputTransfer.isNotBlank())
            assertTrue("gamut must be set for $mode", odt.outputGamut.isNotBlank())
        }
    }

    @Test
    fun sdrModesPreviewExactlyAsDelivered() {
        listOf(VulkanToneDeliveryMode.DIRECT, VulkanToneDeliveryMode.PREVIEW_LUT).forEach { sdr ->
            val verdict = OmniClarityCinemaRenderingChain.verifyWysiwyg(sdr, sdr)
            assertEquals(OmniClarityWysiwygStatus.EXACT_MATCH, verdict.status)
            assertTrue(verdict.isWysiwyg)
            assertTrue(verdict.referenceRenderFixed)
            assertEquals(OmniClarityOdtKind.SDR_REC709, verdict.outputOdt.kind)
        }
    }

    @Test
    fun hdrLogRawPreviewAsDisplayReferredMonitorOfTheSameDelivery() {
        val pairs = listOf(
            VulkanToneDeliveryMode.NDR10_PLUS_MONITOR to VulkanToneDeliveryMode.NDR10_PLUS_HDR,
            VulkanToneDeliveryMode.NIVUKX_VISION_MONITOR to VulkanToneDeliveryMode.NIVUKX_VISION_HDR,
            VulkanToneDeliveryMode.PQ_MONITOR to VulkanToneDeliveryMode.APEX_CINEMA,
            VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_BASE to VulkanToneDeliveryMode.LOG_N_LOG_BASE,
            VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_CINEMA to VulkanToneDeliveryMode.LOG_N_LOG_CINEMA,
            VulkanToneDeliveryMode.RAW_MONITOR to VulkanToneDeliveryMode.RAW_NEUTRAL,
            VulkanToneDeliveryMode.RAW_MONITOR to VulkanToneDeliveryMode.CINEMA_DEBAYER
        )
        pairs.forEach { (previewMode, outputMode) ->
            val verdict = OmniClarityCinemaRenderingChain.verifyWysiwyg(previewMode, outputMode)
            assertEquals(
                "preview $previewMode should monitor delivery $outputMode",
                OmniClarityWysiwygStatus.MONITORED_MATCH,
                verdict.status
            )
            assertTrue(verdict.isWysiwyg)
            // Same delivery transport — only the final display encode differs. (APEX delivery is
            // monitored via the shared PQ monitor, so the ODT *kind* may differ while transport matches.)
            assertEquals(verdict.previewOdt.outputTransfer, verdict.outputOdt.outputTransfer)
            assertTrue(verdict.previewOdt.displayReferredMonitor)
        }
    }

    @Test
    fun previewOfADifferentDeliveryIsFlaggedAsMismatch() {
        val mismatches = listOf(
            // RAW monitor cannot represent an NDR delivery.
            VulkanToneDeliveryMode.RAW_MONITOR to VulkanToneDeliveryMode.NDR10_PLUS_HDR,
            // Wrong HDR family.
            VulkanToneDeliveryMode.NDR10_PLUS_MONITOR to VulkanToneDeliveryMode.NIVUKX_VISION_HDR,
            // Wrong N-Log curve variant.
            VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_BASE to VulkanToneDeliveryMode.LOG_N_LOG_CINEMA,
            // SDR preview cannot represent a LOG delivery.
            VulkanToneDeliveryMode.DIRECT to VulkanToneDeliveryMode.LOG_N_LOG_BASE
        )
        mismatches.forEach { (previewMode, outputMode) ->
            val verdict = OmniClarityCinemaRenderingChain.verifyWysiwyg(previewMode, outputMode)
            assertEquals(
                "preview $previewMode must not pass as delivery $outputMode",
                OmniClarityWysiwygStatus.MISMATCH,
                verdict.status
            )
            assertEquals(false, verdict.isWysiwyg)
        }
    }

    /**
     * Headline guardrail: for every capture scenario, the delivery mode (monitoring off) and the
     * live-preview mode (monitoring on) chosen by the real resolver must form a WYSIWYG pair, and
     * the reference render must stay fixed. If someone edits [VulkanToneDeliveryMode.resolve] so a
     * mode's preview stops representing its own output, this fails.
     */
    @Test
    fun resolverPreviewAndOutputAreWysiwygForEveryMode() {
        data class Scenario(
            val name: String,
            val truthful: Boolean,
            val rawDelivery: Boolean,
            val rawActive: Boolean,
            val log: Boolean,
            val hdr: String,
            val curve: NeoLogCurve,
            val previewMonitoring: Boolean
        )

        val scenarios = listOf(
            Scenario("SDR", true, false, false, false, "OFF", NeoLogCurve.NONE, false),
            Scenario("NDR10+", true, false, false, false, "NDR10+", NeoLogCurve.NONE, true),
            Scenario("NIVUKX", true, false, false, false, "NIVUKXVISION", NeoLogCurve.NONE, true),
            Scenario("HDR10-external", true, false, false, false, "HDR10", NeoLogCurve.NONE, true),
            Scenario("HLG-external", true, false, false, false, "HLG", NeoLogCurve.NONE, true),
            Scenario("APEX_SHARP", true, false, false, false, "APEX_SHARP", NeoLogCurve.NONE, true),
            Scenario("APEX_ZERO", true, false, false, false, "APEX_ZERO", NeoLogCurve.NONE, true),
            Scenario("APEX_CINEMA", true, false, false, false, "APEX_CINEMA", NeoLogCurve.NONE, true),
            Scenario("LOG-base", true, false, false, true, "OFF", NeoLogCurve.N_LOG, true),
            Scenario("LOG-shadow", true, false, false, true, "OFF", NeoLogCurve.N_LOG_SHADOW, true),
            Scenario("LOG-highlight", true, false, false, true, "OFF", NeoLogCurve.N_LOG_HIGHLIGHT, true),
            Scenario("LOG-cinema", true, false, false, true, "OFF", NeoLogCurve.N_LOG_CINEMA, true),
            Scenario("RAW", true, true, true, false, "OFF", NeoLogCurve.NONE, true)
        )

        scenarios.forEach { s ->
            val outputMode = VulkanToneDeliveryMode.resolve(
                previewLutEnabled = false,
                truthfulColorDelivery = s.truthful,
                rawVideoDelivery = s.rawDelivery,
                rawVideoActive = s.rawActive,
                logCaptureActive = s.log,
                normalizedHdrMode = s.hdr,
                logCurve = s.curve,
                previewMonitoringEnabled = false
            )
            val previewMode = VulkanToneDeliveryMode.resolve(
                previewLutEnabled = false,
                truthfulColorDelivery = s.truthful,
                rawVideoDelivery = s.rawDelivery,
                rawVideoActive = s.rawActive,
                logCaptureActive = s.log,
                normalizedHdrMode = s.hdr,
                logCurve = s.curve,
                previewMonitoringEnabled = s.previewMonitoring
            )

            val verdict = OmniClarityCinemaRenderingChain.verifyWysiwyg(previewMode, outputMode)
            assertTrue(
                "${s.name}: preview=$previewMode output=$outputMode -> ${verdict.detail}",
                verdict.isWysiwyg
            )
            assertTrue("${s.name}: reference render must stay fixed", verdict.referenceRenderFixed)
            assertEquals(
                "${s.name}: preview must match resolver's monitor for the delivery",
                previewMode,
                OmniClarityCinemaRenderingChain.expectedDisplayReferredMonitor(outputMode)
            )
        }
    }

    @Test
    fun deliveryAndItsMonitorShareTheSameReferenceRenderAndOdtFamily() {
        // The reference render is one constant for SDR, HDR, LOG and RAW alike.
        val sdr = OmniClarityCinemaRenderingChain.outputDisplayTransform(VulkanToneDeliveryMode.DIRECT)
        val hdr = OmniClarityCinemaRenderingChain.outputDisplayTransform(VulkanToneDeliveryMode.NDR10_PLUS_HDR)
        val log = OmniClarityCinemaRenderingChain.outputDisplayTransform(VulkanToneDeliveryMode.LOG_N_LOG_BASE)
        assertNotEquals(sdr.kind, hdr.kind)
        assertNotEquals(hdr.kind, log.kind)
        // Only one reference render exists; the ODT is the only per-mode swap.
        assertTrue(OmniClarityCinemaRenderingChain.REFERENCE_RENDER.signature.contains("ACESCG_LINEAR"))
        assertTrue(OmniClarityCinemaRenderingChain.REFERENCE_RENDER.signature.contains("203nit"))
        assertTrue(OmniClarityCinemaRenderingChain.REFERENCE_RENDER.signature.contains("1000nit"))
    }
}
