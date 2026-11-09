package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.rendering.BayerPattern
import com.neoproc.neoproccamx.rendering.RawBufferDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for the RAW develop-metadata sidecar. It must round-trip bit-exact (so the offline
 * RAW→NeoLog converter debayers/white-balances/colour-transforms with the true sensor metadata),
 * carry the fields the NPRAW pixel stream omits (WB gains, Bayer phase, sensor→XYZ matrix), and
 * reject a corrupt/truncated sidecar via its trailing CRC instead of yielding garbage.
 */
class RawDevelopMetadataSidecarTest {

    private fun sample() = RawDevelopMetadataSidecar.RawDevelopMetadata(
        width = 4096,
        height = 2160,
        storageBitDepth = 12,
        sensorWhiteLevel = 4095,
        blackLevel = 64,
        whiteLevel = 4095,
        bayerPatternValue = BayerPattern.GRBG.nativeValue,
        whiteBalanceR = 1.83f,
        whiteBalanceGr = 1.0f,
        whiteBalanceGb = 1.0f,
        whiteBalanceB = 1.61f,
        exposureTimeNs = 20_000_000L,
        analogGain = 400f,
        digitalGain = 1.0f,
        noiseS = 0.0012f,
        noiseO = 0.00034f,
        sensorToXyz = floatArrayOf(
            0.72f, 0.11f, 0.09f,
            0.24f, 0.83f, -0.07f,
            0.01f, -0.14f, 1.12f
        ),
        fps = 30,
        colorProfileTag = "N-LOG-BASE"
    )

    @Test
    fun roundTripsBitExact() {
        val meta = sample()
        val decoded = RawDevelopMetadataSidecar.decode(RawDevelopMetadataSidecar.encode(meta))
        assertEquals(meta, decoded)
        assertTrue(meta.sensorToXyz.contentEquals(decoded.sensorToXyz))
    }

    @Test
    fun fromDescriptorCarriesWbBayerAndMatrixThatTheNprawStreamOmits() {
        val descriptor = RawBufferDescriptor(
            width = 1920,
            height = 1080,
            stride = 1920 * 2,
            blackLevel = 64,
            whiteLevel = 4095,
            bayerPattern = BayerPattern.BGGR,
            whiteBalanceR = 2.01f,
            whiteBalanceGr = 1.0f,
            whiteBalanceGb = 1.0f,
            whiteBalanceB = 1.44f,
            exposureTimeNs = 33_000_000L,
            analogGain = 200f,
            digitalGain = 1.0f,
            noiseS = 0.001f,
            noiseO = 0.0002f,
            sensorToXyz = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        )
        val meta = RawDevelopMetadataSidecar.fromDescriptor(
            descriptor = descriptor,
            storageBitDepth = 12,
            sensorWhiteLevel = 4095,
            fps = 24,
            colorProfileTag = "N-LOG-CINEMA"
        )
        val decoded = RawDevelopMetadataSidecar.decode(RawDevelopMetadataSidecar.encode(meta))

        assertEquals(BayerPattern.BGGR.nativeValue, decoded.bayerPatternValue)
        assertEquals(2.01f, decoded.whiteBalanceR)
        assertEquals(1.44f, decoded.whiteBalanceB)
        assertEquals(12, decoded.storageBitDepth)
        assertEquals(24, decoded.fps)
        assertEquals("N-LOG-CINEMA", decoded.colorProfileTag)
        assertTrue(descriptor.sensorToXyz.contentEquals(decoded.sensorToXyz))
    }

    @Test
    fun rejectsCorruptSidecarViaCrc() {
        val bytes = RawDevelopMetadataSidecar.encode(sample())
        bytes[24] = (bytes[24] + 1).toByte() // flip a payload byte -> CRC no longer matches
        assertThrows(RawDevelopMetadataSidecar.SidecarFormatException::class.java) {
            RawDevelopMetadataSidecar.decode(bytes)
        }
    }

    @Test
    fun maxResPixelModeFlagRoundTrips() {
        val engaged = sample().copy(sensorPixelModeMaximumResolutionUsed = true)
        val decoded = RawDevelopMetadataSidecar.decode(RawDevelopMetadataSidecar.encode(engaged))
        assertTrue(decoded.sensorPixelModeMaximumResolutionUsed)
        assertEquals(engaged, decoded)
    }

    @Test
    fun recordingOrientationRoundTripsWithoutChangingRawSamples() {
        val oriented = sample().copy(orientationHintDegrees = 90)
        val decoded = RawDevelopMetadataSidecar.decode(RawDevelopMetadataSidecar.encode(oriented))

        assertEquals(90, decoded.orientationHintDegrees)
        assertEquals(oriented, decoded)
    }

    @Test
    fun fromDescriptorDefaultsMaxResFlagFalseAndCarriesTrueWhenSet() {
        val descriptor = RawBufferDescriptor(
            width = 1920, height = 1080, stride = 1920 * 2, blackLevel = 64, whiteLevel = 4095,
            bayerPattern = BayerPattern.BGGR, whiteBalanceR = 2.01f, whiteBalanceGr = 1.0f,
            whiteBalanceGb = 1.0f, whiteBalanceB = 1.44f, exposureTimeNs = 33_000_000L,
            analogGain = 200f, digitalGain = 1.0f, noiseS = 0.001f, noiseO = 0.0002f,
            sensorToXyz = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        )
        val defaulted = RawDevelopMetadataSidecar.fromDescriptor(
            descriptor = descriptor, storageBitDepth = 12, sensorWhiteLevel = 4095,
            fps = 24, colorProfileTag = "N-LOG-BASE"
        )
        assertEquals(false, defaulted.sensorPixelModeMaximumResolutionUsed)

        val maxRes = RawDevelopMetadataSidecar.fromDescriptor(
            descriptor = descriptor, storageBitDepth = 12, sensorWhiteLevel = 4095,
            fps = 24, colorProfileTag = "N-LOG-BASE", sensorPixelModeMaximumResolutionUsed = true
        )
        val decoded = RawDevelopMetadataSidecar.decode(RawDevelopMetadataSidecar.encode(maxRes))
        assertTrue(decoded.sensorPixelModeMaximumResolutionUsed)
    }

    @Test
    fun rejectsTruncatedSidecar() {
        val bytes = RawDevelopMetadataSidecar.encode(sample()).copyOf(6)
        assertThrows(RawDevelopMetadataSidecar.SidecarFormatException::class.java) {
            RawDevelopMetadataSidecar.decode(bytes)
        }
    }
}
