package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.niv99.Niv99OfflineConversionRoute
import com.neoproc.neoproccamx.pipeline.RawDevelopMetadataSidecar.RawDevelopMetadata
import com.neoproc.neoproccamx.rendering.DEFAULT_SENSOR_TO_XYZ
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99RawDeliveryIntegrationTest {
    @Test
    fun `all RAW video derivatives execute full-frame NIV99 by default`() {
        val width = 12
        val height = 8
        val metadata = metadata(width, height)
        val expectedRoutes = linkedMapOf(
            RawToNeoLogMovConverter.DeliveryProfile.NEOLOG to
                Niv99OfflineConversionRoute.RAW_TO_LOG,
            RawToNeoLogMovConverter.DeliveryProfile.NDR10_PLUS to
                Niv99OfflineConversionRoute.RAW_TO_NDR10_PLUS,
            RawToNeoLogMovConverter.DeliveryProfile.NIVUKX_VISION to
                Niv99OfflineConversionRoute.RAW_TO_NIVUKX_VISION
        )

        expectedRoutes.forEach { (delivery, route) ->
            val first = IntArray(width * height) { index -> 640 + (index % 7) }
            val second = IntArray(width * height) { index -> 643 + (index % 7) }
            val firstBefore = first.copyOf()
            val secondBefore = second.copyOf()
            val samples = mutableListOf<ByteArray>()

            val result = RawToNeoLogMovConverter().convert(
                frames = listOf(first, second).iterator(),
                meta = metadata,
                options = RawToNeoLogMovConverter.Options(
                    format = RawToNeoLogMovConverter.OutputFormat.MASTER_444,
                    deliveryProfile = delivery
                )
            ) { sample, _ -> samples += sample }

            val evidence = result.niv99Evidence
            assertNotNull("$delivery must publish NIV99 evidence", evidence)
            assertEquals(route, evidence?.route)
            assertEquals(2L, evidence?.processedFrames)
            assertEquals(width.toLong() * height * 2L, evidence?.fullFrameSamples)
            assertTrue(evidence?.sourceSamplesPreserved == true)
            assertTrue(evidence?.capturedWhiteBalancePreserved == true)
            assertEquals(2, samples.size)
            samples.forEach { sample ->
                assertEquals(RawToNeoLogMovConverter().sampleSize(
                    metadata,
                    RawToNeoLogMovConverter.Options(
                        format = RawToNeoLogMovConverter.OutputFormat.MASTER_444,
                        deliveryProfile = delivery
                    )
                ), sample.size)
            }
            assertArrayEquals(firstBefore, first)
            assertArrayEquals(secondBefore, second)
        }
    }

    private fun metadata(width: Int, height: Int): RawDevelopMetadata = RawDevelopMetadata(
        width = width,
        height = height,
        storageBitDepth = 12,
        sensorWhiteLevel = 4_095,
        blackLevel = 64,
        whiteLevel = 4_095,
        bayerPatternValue = 0,
        whiteBalanceR = 2.0f,
        whiteBalanceGr = 1f,
        whiteBalanceGb = 1f,
        whiteBalanceB = 1.7f,
        exposureTimeNs = 10_000_000L,
        analogGain = 2f,
        digitalGain = 1f,
        noiseS = 0.00008f,
        noiseO = 0.000004f,
        sensorToXyz = DEFAULT_SENSOR_TO_XYZ,
        fps = 25,
        colorProfileTag = "NIV99-CONVERSION-TEST",
        blackLevelRggb = floatArrayOf(64f, 64f, 64f, 64f)
    )
}
