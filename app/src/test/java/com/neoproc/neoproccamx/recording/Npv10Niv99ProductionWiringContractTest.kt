package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Npv10Niv99ProductionWiringContractTest {
    @Test
    fun `production records bit exact P010 and applies NIV99 to offline derivatives`() {
        val mainRoot = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val viewModel = File(
            mainRoot,
            "java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()
        val manager = File(
            mainRoot,
            "java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt"
        ).readText()
        val packer = File(
            mainRoot,
            "java/com/neoproc/neoproccamx/recording/Npv10FramePacker.kt"
        ).readText()
        val converter = File(
            mainRoot,
            "java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt"
        ).readText()

        // NIV99 is intentionally outside the capture deadline. The private NPV10 source receives
        // the camera's ten-bit codes without a denoise/WB rewrite, then every visible derivative is
        // processed by the full-frame offline lane.
        assertTrue(viewModel.contains("niv99ProcessingRequired = false"))
        assertTrue(viewModel.contains("directMappedCommitRequired = true"))
        assertTrue(manager.contains("packP010ImageToDirectMappedTenBitWithCrc("))
        assertTrue(manager.contains("CAMERA_PLANE_DIRECT_MAPPED_FUSED_NATIVE"))
        assertTrue(packer.contains("nativePackP010ImagePlanesToDirectMappedTenBitWithCrc("))

        assertTrue(converter.contains("Niv99OfflineConversionPolicy.npv10("))
        assertTrue(converter.contains("niv99Plan.requireFrameContract("))
        assertTrue(converter.contains("niv99PixelProcessor.recordGpuFrame("))
        assertTrue(converter.contains("Npv10ExportGpuAccelerator.enhanceP010Direct("))
        assertTrue(converter.contains("gpuAcceleratedFrames += 1L"))
        assertTrue(converter.contains("Npv10ExportProcessingEvidence.fromFrameCounts("))
        assertTrue(converter.contains("Npv10ExportGpuAccelerator.enhanceP010"))
        assertTrue(converter.contains("niv99PixelProcessor.processCpu("))
        assertTrue(converter.contains("asyncExport.frameCount == expectedFrameCount.toLong()"))
        assertTrue(converter.contains("inputMasterHashUnchanged"))
    }
}
