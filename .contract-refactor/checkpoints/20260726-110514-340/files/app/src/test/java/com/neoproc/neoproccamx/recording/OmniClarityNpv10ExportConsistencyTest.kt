package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.rendering.OmniClarityCinemaRenderingChain
import com.neoproc.neoproccamx.rendering.OmniClarityOutputDisplayTransform
import com.neoproc.neoproccamx.rendering.VulkanToneDeliveryMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OmniClarity §5 cross-surface consistency: the OFFLINE NPV10 MOV export path
 * (`Npv10MovColorTagPolicy.resolve` → `neo_p010_export_enhance.comp`) must carry the same
 * per-mode output transport as the LIVE preview/record ODT ([OmniClarityCinemaRenderingChain]).
 *
 * This is the one place a mode's exported look can silently drift from its live look, because the
 * NPV10 master is written verbatim and enhanced by a *separate* shader during export. This test
 * binds that path to the live §5 ODT for every app-owned delivery mode.
 *
 * Note on N-Log: the true N-Log curve lives in the NPV10 master pixels; the MOV is tagged on the
 * BT.709 transfer axis as a display carrier (matching the live LOG preview, which is also
 * display-referred). RAW is intentionally excluded — a RAW master is not exported through the MOV
 * color path (it requires demosaic first), so it has no MOV transfer to compare.
 */
class OmniClarityNpv10ExportConsistencyTest {

    @Test
    fun npv10ExportTransferMatchesLiveOdtForEveryDeliveryMode() {
        data class Case(
            val name: String,
            val metadata: Map<String, String>,
            val liveMode: VulkanToneDeliveryMode
        )

        val cases = listOf(
            Case("SDR-PRO", mapOf("recordingmode" to "PRO", "selectedcolorspace" to "Rec.709"),
                VulkanToneDeliveryMode.DIRECT),
            Case("NDR10+", mapOf("recordingmode" to "NDR10+"),
                VulkanToneDeliveryMode.NDR10_PLUS_HDR),
            Case("NIVUKX", mapOf("hdrmode" to "NIVUKX_VISION"),
                VulkanToneDeliveryMode.NIVUKX_VISION_HDR),
            Case("APEX", mapOf("hdrmode" to "APEX_CINEMA"),
                VulkanToneDeliveryMode.APEX_CINEMA),
            Case("LOG", mapOf("recordingmode" to "LOG"),
                VulkanToneDeliveryMode.LOG_N_LOG_BASE)
        )

        cases.forEach { case ->
            val exportPlan = Npv10MovColorTagPolicy.resolve(headerFor(case.metadata))
            val liveOdt = OmniClarityCinemaRenderingChain.outputDisplayTransform(case.liveMode)
            assertEquals(
                "${case.name}: NPV10 export transfer must match the live §5 ODT transport",
                quickTimeTransferFor(liveOdt),
                exportPlan.colorConfig.transferCharacteristics
            )
        }
    }

    /** Maps a live §5 ODT transport to the QuickTime transfer characteristic the MOV export carries. */
    private fun quickTimeTransferFor(odt: OmniClarityOutputDisplayTransform): Int =
        when (odt.outputTransfer) {
            // SDR and the N-Log display carrier both ride the BT.709 transfer axis in the container.
            "sRGB", "N-LOG" -> QuickTimeColorAtomPolicy.TRANSFER_BT709
            "PQ_ST2084" -> QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084
            "HLG_BT2100" -> QuickTimeColorAtomPolicy.TRANSFER_HLG
            else -> error("transport ${odt.outputTransfer} is not exported via the MOV color path")
        }

    private fun headerFor(metadata: Map<String, String>): Npv10FileHeader =
        Npv10FileHeader(
            version = 1,
            width = 1920,
            height = 1080,
            fps = 30,
            bitDepth = 10,
            metadata = "",
            metadataValues = metadata,
            payloadFormat = Npv10PayloadFormat.P010_LE,
            sourceFormat = "P010",
            sourceBitDepth = 10,
            storageBitDepth = 10,
            exactSelectedResolution = true
        )
}
