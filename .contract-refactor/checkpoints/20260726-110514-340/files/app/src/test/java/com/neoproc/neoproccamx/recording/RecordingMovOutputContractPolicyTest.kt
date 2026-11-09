package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingMovOutputContractPolicyTest {

    @Test
    fun `resolve returns premium mov media store contract`() {
        val contract = RecordingMovOutputContractPolicy.resolve(
            baseName = "CamX 14052026",
            outputLabelSuffix = "_LOG",
            cinemaMode = CinemaVideoMode.LOG,
            rawVideoEnabled = false,
            logVideoEnabled = true,
            hdrMode = "HDR10",
            descriptionSnapshot = snapshot()
        )

        assertEquals(RecordingContainer.MOV, contract.selection.container)
        assertEquals("mov", contract.selection.fileExtension)
        assertEquals("video/quicktime", contract.selection.mediaStoreMime)
        assertEquals("CamX 14052026_LOG.mov", contract.descriptor.fileName)
        assertEquals("video/quicktime", contract.descriptor.mimeType)
        assertEquals("QuickTime MOV", contract.descriptor.containerLabel)
        assertEquals("DCIM/CamX", contract.route.relativePath)
        assertTrue(contract.description.contains("codec=HEVC"))
        assertTrue(contract.description.contains("color=Rec.2020"))
        assertTrue(contract.description.contains("dynamicRange=HDR10"))
    }

    @Test
    fun `resolve keeps raw video truthful as mov archive`() {
        val contract = RecordingMovOutputContractPolicy.resolve(
            baseName = "CamX 14052026",
            outputLabelSuffix = "_RAW",
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoEnabled = true,
            logVideoEnabled = false,
            hdrMode = "OFF",
            descriptionSnapshot = snapshot(
                dynamicRangeLabel = "RAW_SENSOR archive",
                sceneMetadataDescription = "rawSensorCapture=ON;rawVideoArchive=ON"
            )
        )

        assertEquals(RecordingContainer.MOV, contract.selection.container)
        assertEquals("CamX 14052026_RAW.mov", contract.descriptor.fileName)
        assertEquals("Original Quality-Preserving MOV", contract.descriptor.containerLabel)
        assertTrue(contract.description.contains("dynamicRange=RAW_SENSOR archive"))
        assertTrue(contract.description.contains("rawSensorCapture=ON"))
        assertTrue(contract.description.contains("rawVideoArchive=ON"))
    }

    private fun snapshot(
        dynamicRangeLabel: String = "HDR10",
        sceneMetadataDescription: String = "mode=LOG"
    ): RecordingOutputDescriptionSnapshot =
        RecordingOutputDescriptionSnapshot(
            videoWidth = 3840,
            videoHeight = 2160,
            videoFps = 60,
            codecLabel = "HEVC",
            bitDepthLabel = "10-bit",
            colorSpace = "Rec.2020",
            hdrIntentLabel = "HDR10",
            dynamicRangeLabel = dynamicRangeLabel,
            logCurveLabel = "Sony S-Log3",
            qualityTierLabel = RecordingCodecCatalog.QualityTier.CINEMA.name,
            cinemaMasteringEnabled = true,
            allIntraEnabled = true,
            gopSeconds = 0.10f,
            simulatedTenBitFallbackActive = false,
            bitrateBitsPerSecond = 180_000_000,
            sceneMetadataDescription = sceneMetadataDescription
        )
}
