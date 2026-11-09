package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.rendering.BayerPattern
import com.neoproc.neoproccamx.rendering.RawBufferDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

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
        colorProfileTag = "N-LOG-BASE",
        blackLevelRggb = floatArrayOf(60.5f, 63f, 65.5f, 68f),
        lensShadingGridRggb = FloatArray(36) { index -> 1f + index * 0.01f },
        lensShadingCorrectionEnabled = true
    )

    @Test
    fun roundTripsBitExact() {
        val meta = sample()
        val decoded = RawDevelopMetadataSidecar.decode(RawDevelopMetadataSidecar.encode(meta))
        assertEquals(meta, decoded)
        assertTrue(meta.sensorToXyz.contentEquals(decoded.sensorToXyz))
        assertTrue(meta.blackLevelRggb.contentEquals(decoded.blackLevelRggb))
        assertTrue(meta.lensShadingGridRggb.contentEquals(decoded.lensShadingGridRggb))
        assertTrue(decoded.lensShadingCorrectionEnabled)
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

    @Test
    fun audioObligationRoundTripsAndCannotBeRemovedWithoutBreakingCrc() {
        val original = sample().copy(audioExpected = true)
        val encoded = RawDevelopMetadataSidecar.encode(original)
        val decoded = RawDevelopMetadataSidecar.decode(encoded)
        assertEquals(original, decoded)
        assertTrue(decoded.audioExpected)
        assertTrue(decoded.hashCode() != original.copy(audioExpected = false).hashCode())
        encoded[encoded.size - 9] = 0
        assertThrows(RawDevelopMetadataSidecar.SidecarFormatException::class.java) {
            RawDevelopMetadataSidecar.decode(encoded)
        }
    }

    @Test
    fun everyLegacyVersionRemainsReadableWithoutInventingAnAudioObligation() {
        for (version in 1..4) {
            val decoded = RawDevelopMetadataSidecar.decode(encodeLegacy(sample(), version))
            assertEquals(false, decoded.audioExpected)
            if (version == 4) assertEquals(sample(), decoded)
        }
    }

    @Test
    fun legacyVersionsRetainScalarBlackAndIdentityLensShadingCompatibility() {
        val legacy = sample().copy(
            sensorPixelModeMaximumResolutionUsed = true,
            orientationHintDegrees = 90
        )
        for (version in 1..3) {
            val decoded = RawDevelopMetadataSidecar.decode(encodeLegacy(legacy, version))
            assertTrue(decoded.blackLevelRggb.contentEquals(FloatArray(4) { 64f }))
            assertTrue(decoded.lensShadingGridRggb.contentEquals(FloatArray(36) { 1f }))
            assertEquals(false, decoded.lensShadingCorrectionEnabled)
            assertEquals(if (version >= 2) legacy.sensorPixelModeMaximumResolutionUsed else false,
                decoded.sensorPixelModeMaximumResolutionUsed)
            assertEquals(if (version >= 3) legacy.orientationHintDegrees else 0,
                decoded.orientationHintDegrees)
        }
    }

    @Test
    fun rejectsNonFiniteOrOutOfRangeV4Calibration() {
        assertThrows(RawDevelopMetadataSidecar.SidecarFormatException::class.java) {
            RawDevelopMetadataSidecar.encode(sample().copy(blackLevelRggb = floatArrayOf(64f, 64f, Float.NaN, 64f)))
        }
        assertThrows(RawDevelopMetadataSidecar.SidecarFormatException::class.java) {
            RawDevelopMetadataSidecar.encode(sample().copy(lensShadingGridRggb = FloatArray(36) { 9f }))
        }
    }

    private fun encodeLegacy(meta: RawDevelopMetadataSidecar.RawDevelopMetadata, version: Int): ByteArray {
        require(version in 1..4)
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { out ->
            out.write("NRAWMETA".toByteArray(Charsets.US_ASCII))
            out.writeInt(version)
            out.writeInt(meta.width)
            out.writeInt(meta.height)
            out.writeInt(meta.storageBitDepth)
            out.writeInt(meta.sensorWhiteLevel)
            out.writeInt(meta.blackLevel)
            out.writeInt(meta.whiteLevel)
            out.writeInt(meta.bayerPatternValue)
            out.writeFloat(meta.whiteBalanceR)
            out.writeFloat(meta.whiteBalanceGr)
            out.writeFloat(meta.whiteBalanceGb)
            out.writeFloat(meta.whiteBalanceB)
            out.writeLong(meta.exposureTimeNs)
            out.writeFloat(meta.analogGain)
            out.writeFloat(meta.digitalGain)
            out.writeFloat(meta.noiseS)
            out.writeFloat(meta.noiseO)
            meta.sensorToXyz.forEach(out::writeFloat)
            out.writeInt(meta.fps)
            out.writeUTF(meta.colorProfileTag)
            if (version >= 2) out.writeBoolean(meta.sensorPixelModeMaximumResolutionUsed)
            if (version >= 3) out.writeInt(meta.orientationHintDegrees)
            if (version >= 4) {
                meta.blackLevelRggb.forEach(out::writeFloat)
                meta.lensShadingGridRggb.forEach(out::writeFloat)
                out.writeBoolean(meta.lensShadingCorrectionEnabled)
            }
        }
        val bodyBytes = body.toByteArray()
        val crc = CRC32().apply { update(bodyBytes) }.value
        return ByteArrayOutputStream().also { full ->
            DataOutputStream(full).use { out ->
                out.write(bodyBytes)
                out.writeLong(crc)
            }
        }.toByteArray()
    }
}
