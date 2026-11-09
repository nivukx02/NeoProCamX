package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.NeoLogCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanToneDeliveryModeTest {

    @Test
    fun rawVideoUsesNeutralEncoderMode() {
        val mode = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = true,
            rawVideoActive = true,
            logCaptureActive = false,
            normalizedHdrMode = "OFF",
            logCurve = NeoLogCurve.NONE
        )

        assertEquals(VulkanToneDeliveryMode.RAW_NEUTRAL, mode)
    }

    @Test
    fun logVideoUsesDedicatedLogEncoderMode() {
        val mode = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = true,
            normalizedHdrMode = "OFF",
            logCurve = NeoLogCurve.N_LOG
        )

        assertEquals(VulkanToneDeliveryMode.LOG_N_LOG_BASE, mode)
    }

    @Test
    fun selectedLogCurveUsesMatchingEncoderMode() {
        val cinema = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = true,
            normalizedHdrMode = "OFF",
            logCurve = NeoLogCurve.N_LOG_CINEMA
        )
        val shadow = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = true,
            normalizedHdrMode = "OFF",
            logCurve = NeoLogCurve.N_LOG_SHADOW
        )

        assertEquals(VulkanToneDeliveryMode.LOG_N_LOG_CINEMA, cinema)
        assertEquals(VulkanToneDeliveryMode.LOG_N_LOG_SHADOW, shadow)
    }

    @Test
    fun selectedNLogVariantsUseShaderReachableVariantModes() {
        fun resolveLog(curve: NeoLogCurve) = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = true,
            normalizedHdrMode = "OFF",
            logCurve = curve
        )

        val base = resolveLog(NeoLogCurve.N_LOG_BASE)
        val shadow = resolveLog(NeoLogCurve.N_LOG_SHADOW)
        val highlight = resolveLog(NeoLogCurve.N_LOG_HIGHLIGHT)
        val cinema = resolveLog(NeoLogCurve.N_LOG_CINEMA)

        assertEquals(VulkanToneDeliveryMode.LOG_N_LOG_BASE, base)
        assertEquals(VulkanToneDeliveryMode.LOG_N_LOG_SHADOW, shadow)
        assertEquals(VulkanToneDeliveryMode.LOG_N_LOG_HIGHLIGHT, highlight)
        assertEquals(VulkanToneDeliveryMode.LOG_N_LOG_CINEMA, cinema)
        listOf(base, shadow, highlight, cinema).forEach { mode ->
            assertTrue(mode.shaderModeFlag in 10f..13f)
        }
    }

    @Test
    fun deliveryModesDoNotExposeExternalLogModes() {
        val names = VulkanToneDeliveryMode.entries.joinToString(separator = " ") { it.name }
        listOf("SONY", "ARRI", "CANON", "PANASONIC", "RED", "BLACKMAGIC", "DAVINCI").forEach { token ->
            assertFalse(names.contains(token))
        }
    }

    @Test
    fun legacyHlgHdrUsesNeoPqEncoderMode() {
        val mode = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = false,
            normalizedHdrMode = "HLG",
            logCurve = NeoLogCurve.NONE
        )

        assertEquals(VulkanToneDeliveryMode.NDR10_PLUS_HDR, mode)
    }

    @Test
    fun hdr10FamilyUsesPqEncoderMode() {
        val hdr10 = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = false,
            normalizedHdrMode = "HDR10",
            logCurve = NeoLogCurve.NONE
        )
        val hdr10Plus = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = false,
            normalizedHdrMode = "HDR10+",
            logCurve = NeoLogCurve.NONE
        )
        val vendorText = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = false,
            normalizedHdrMode = "DOLBYVISION",
            logCurve = NeoLogCurve.NONE
        )

        assertEquals(VulkanToneDeliveryMode.NDR10_PLUS_HDR, hdr10)
        assertEquals(VulkanToneDeliveryMode.NDR10_PLUS_HDR, hdr10Plus)
        assertEquals(VulkanToneDeliveryMode.DIRECT, vendorText)
    }

    @Test
    fun neoHdrProfilesUseDistinctPqTransportShaderModes() {
        fun resolveHdr(profile: String) = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = false,
            normalizedHdrMode = profile,
            logCurve = NeoLogCurve.NONE
        )

        assertEquals(VulkanToneDeliveryMode.NDR10_PLUS_HDR, resolveHdr("NDR10+"))
        assertEquals(VulkanToneDeliveryMode.NIVUKX_VISION_HDR, resolveHdr("NIVUKXVISION"))
        assertNotEquals(
            VulkanToneDeliveryMode.NDR10_PLUS_HDR.shaderModeFlag,
            VulkanToneDeliveryMode.NIVUKX_VISION_HDR.shaderModeFlag
        )
    }

    @Test
    fun apexHdrFamilyUsesDedicatedEncoderModes() {
        val sharp = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = false,
            normalizedHdrMode = "APEX_SHARP",
            logCurve = NeoLogCurve.NONE
        )
        val zero = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = false,
            normalizedHdrMode = "APEX_ZERO",
            logCurve = NeoLogCurve.NONE
        )
        val cinema = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = false,
            normalizedHdrMode = "APEX_CINEMA",
            logCurve = NeoLogCurve.NONE
        )

        assertEquals(VulkanToneDeliveryMode.APEX_SHARP, sharp)
        assertEquals(VulkanToneDeliveryMode.APEX_ZERO, zero)
        assertEquals(VulkanToneDeliveryMode.APEX_CINEMA, cinema)
    }

    @Test
    fun apexPreviewMonitoringUsesPqMonitorMode() {
        val mode = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = false,
            normalizedHdrMode = "APEX_CINEMA",
            logCurve = NeoLogCurve.NONE,
            previewMonitoringEnabled = true
        )

        assertEquals(VulkanToneDeliveryMode.PQ_MONITOR, mode)
    }

    @Test
    fun previewLutModeRemainsExclusiveToPreviewLut() {
        assertTrue(VulkanToneDeliveryMode.PREVIEW_LUT.usesPreviewLut())
        assertFalse(VulkanToneDeliveryMode.LOG_N_LOG_BASE.usesPreviewLut())
        assertFalse(VulkanToneDeliveryMode.PQ_HDR.usesPreviewLut())
    }

    @Test
    fun logPreviewMonitoringUsesDedicatedMonitorMode() {
        val mode = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = false,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = true,
            normalizedHdrMode = "OFF",
            logCurve = NeoLogCurve.N_LOG,
            previewMonitoringEnabled = true
        )

        assertEquals(VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_BASE, mode)
    }

    @Test
    fun hdrPreviewMonitoringUsesDedicatedMonitorMode() {
        val mode = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = false,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = false,
            normalizedHdrMode = "HDR10",
            logCurve = NeoLogCurve.NONE,
            previewMonitoringEnabled = true
        )

        assertEquals(VulkanToneDeliveryMode.NDR10_PLUS_MONITOR, mode)
    }

    @Test
    fun hdrPreviewMonitoringOverridesTruthfulDeliveryForVisiblePreview() {
        val mode = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = false,
            normalizedHdrMode = "HLG",
            logCurve = NeoLogCurve.NONE,
            previewMonitoringEnabled = true
        )

        assertEquals(VulkanToneDeliveryMode.NDR10_PLUS_MONITOR, mode)
    }

    @Test
    fun logPreviewMonitoringOverridesTruthfulDeliveryForVisiblePreview() {
        val mode = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = false,
            rawVideoActive = false,
            logCaptureActive = true,
            normalizedHdrMode = "OFF",
            logCurve = NeoLogCurve.N_LOG,
            previewMonitoringEnabled = true
        )

        assertEquals(VulkanToneDeliveryMode.LOG_MONITOR_N_LOG_BASE, mode)
    }

    @Test
    fun rawPreviewMonitoringUsesDedicatedMonitorMode() {
        val mode = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = false,
            rawVideoDelivery = false,
            rawVideoActive = true,
            logCaptureActive = false,
            normalizedHdrMode = "OFF",
            logCurve = NeoLogCurve.NONE,
            previewMonitoringEnabled = true
        )

        assertEquals(VulkanToneDeliveryMode.RAW_MONITOR, mode)
    }

    @Test
    fun rawTruthfulPreviewCanUseMonitorTransformWithoutChangingRawEncoderDelivery() {
        val mode = VulkanToneDeliveryMode.resolve(
            previewLutEnabled = false,
            truthfulColorDelivery = true,
            rawVideoDelivery = true,
            rawVideoActive = true,
            logCaptureActive = false,
            normalizedHdrMode = "OFF",
            logCurve = NeoLogCurve.NONE,
            previewMonitoringEnabled = true
        )

        assertEquals(VulkanToneDeliveryMode.RAW_MONITOR, mode)
    }
}
