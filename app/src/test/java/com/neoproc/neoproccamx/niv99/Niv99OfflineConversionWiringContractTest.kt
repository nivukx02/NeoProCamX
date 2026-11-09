package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Niv99OfflineConversionWiringContractTest {
    private val appRoot = listOf(File("."), File("app"))
        .first { File(it, "src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").isFile }

    @Test
    fun `NPV10 SDR and NDR routes require complete NIV99 P010 pixel coverage`() {
        val source = production("recording/Npv10MovConverter.kt")

        assertTrue(source.contains("Niv99OfflineConversionPolicy.npv10(colorTagPlan.activeTransport)"))
        assertTrue(source.contains("val niv99PixelProcessor = Niv99OfflineP010PixelProcessor("))
        assertTrue(source.contains("niv99PixelProcessor.requireComplete(asyncExport.frameCount)"))
        assertTrue(source.contains("NIV99 Pixel Evidence"))
    }

    @Test
    fun `record develop SDR and NDR routes require complete NIV99 P010 pixel coverage`() {
        val source = production("recording/RecordDevelopMovConverter.kt")

        assertTrue(source.contains("Niv99OfflineConversionPolicy.recordDevelop("))
        assertTrue(source.contains("Niv99OfflineP010PixelProcessor("))
        assertTrue(source.contains("niv99PixelProcessor?.requireComplete(result.frameCount)"))
        assertTrue(source.contains("niv99PixelEvidence = niv99PixelEvidence?.metadata().orEmpty()"))
    }

    @Test
    fun `RAW Log NDR and NIVUKX routes process sensor CFA before develop`() {
        val converter = production("recording/RawToNeoLogMovConverter.kt")
        val coordinator = production("recording/Raw12ClipConversionCoordinator.kt")

        assertTrue(converter.contains("Niv99OfflineConversionPolicy.forRawDelivery(options.deliveryProfile.name)"))
        assertTrue(converter.contains("Niv99RawConversionProcessor("))
        assertTrue(converter.contains("val prepared = niv99?.process("))
        assertTrue(coordinator.contains("RAW conversion completed without NIV99 pixel evidence"))
        assertTrue(coordinator.contains("pixelEvidence.processedFrames == result.framesConverted.toLong()"))
    }

    @Test
    fun `RAW to RAW preserves every CFA sample before transactional CinemaDNG publication`() {
        val source = production("ui/CameraMediaConversionCoordinator.kt")
        val conversion = source
            .substringAfter("fun convertRawClipToCinemaDng(")
            .substringBefore("fun deleteRawClip(")

        assertTrue(conversion.contains("Niv99OfflineConversionPolicy.rawToRawDeveloped()"))
        assertTrue(conversion.contains("RawCinemaDngIdentityPolicy.requirePreservedSensorFrame("))
        assertTrue(conversion.contains("mosaic = frame.samples"))
        assertTrue(conversion.indexOf("RawCinemaDngIdentityPolicy.requirePreservedSensorFrame(") <
            conversion.indexOf("transaction.commit(cancellation)"))
        assertTrue(!conversion.contains("Niv99RawConversionProcessor("))
    }

    private fun production(relativePath: String): String = File(
        appRoot,
        "src/main/java/com/neoproc/neoproccamx/$relativePath"
    ).readText()
}
