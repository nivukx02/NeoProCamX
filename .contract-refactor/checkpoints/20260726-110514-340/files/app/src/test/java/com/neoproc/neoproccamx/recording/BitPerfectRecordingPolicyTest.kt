package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BitPerfectRecordingPolicyTest {

    @Test
    fun supportsAllIntraEncoding_doesNotHardcodeTrueCapabilityClaim() {
        val text = locatePolicySource().readText()

        assertFalse(
            "supportsAllIntraEncoding() must not return a hardcoded true. Runtime support has to come from " +
                "AllIntraHevcPolicy.probe or a caller-provided capability result.",
            Regex("""fun\s+supportsAllIntraEncoding\s*\([^)]*\)\s*:\s*Boolean\s*\{[\s\S]*return\s+true""")
                .containsMatchIn(text)
        )
    }

    @Test
    fun supportsAllIntraEncoding_tracksProbeResult() {
        val policy = BitPerfectRecordingPolicy()

        assertFalse(policy.supportsAllIntraEncoding(null))
        assertTrue(
            policy.supportsAllIntraEncoding(
                AllIntraHevcPolicy.AllIntraConfig(
                    bitrate = AllIntraHevcPolicy.BITRATE_STANDARD,
                    profile = 1,
                    level = 1,
                    frameRate = 30
                )
            )
        )
    }

    @Test
    fun sdr10AllIntraPolicyUsesEditingBitrateBandsWithoutPromotingHdrLook() {
        val policy = BitPerfectRecordingPolicy()
        val fullHd = policy.resolveSdr10AllIntraConfig(1920, 1080)
        val fourK = policy.resolveSdr10AllIntraConfig(3840, 2160)
        val eightK = policy.resolveSdr10AllIntraConfig(7680, 4320)

        assertEquals(420_000_000L, fullHd.minimumBitrateBps)
        assertEquals(800_000_000L, fullHd.maximumBitrateBps)
        assertEquals(1_000_000_000L, fourK.minimumBitrateBps)
        assertEquals(2_000_000_000L, fourK.maximumBitrateBps)
        assertEquals(0f, fourK.iFrameIntervalSeconds, 0.0001f)
        assertTrue(fourK.isAllIntraRequired)
        assertTrue(fourK.hdrLookBypass)
        assertEquals(2_000_000_000L, eightK.minimumBitrateBps)
        assertEquals(2_500_000_000L, eightK.maximumBitrateBps)
    }

    @Test
    fun normalSdrAllIntraPolicyKeepsRec709SdrWhileUsingMasteringBitrateBands() {
        val policy = BitPerfectRecordingPolicy()
        val fullHd = policy.resolveNormalSdrAllIntraConfig(1920, 1080)
        val fourK = policy.resolveNormalSdrAllIntraConfig(3840, 2160)

        assertEquals(420_000_000L, fullHd.minimumBitrateBps)
        assertEquals(800_000_000L, fullHd.maximumBitrateBps)
        assertEquals(1_000_000_000L, fourK.minimumBitrateBps)
        assertEquals(2_000_000_000L, fourK.maximumBitrateBps)
        assertEquals(0f, fourK.iFrameIntervalSeconds, 0.0001f)
        assertTrue(fourK.isAllIntraRequired)
        assertTrue(fourK.hdrLookBypass)
        assertTrue(fourK.tenBitMain10Required)
        assertEquals("BT.709", fourK.colorPrimaries)
        assertEquals("SDR_VIDEO", fourK.colorTransfer)
    }

    @Test
    fun normalSdrAllIntraPolicyScalesStrictFloorWithFpsWithoutHdrPromotion() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        try {
            HighEndQualityPolicy.enabled = true
            HighEndQualityPolicy.strictHighEndOnly = true
            val policy = BitPerfectRecordingPolicy()
            val sixtyFps = policy.resolveNormalSdrAllIntraConfig(3840, 2160, fps = 60)
            val expectedFloor = HighEndQualityPolicy.minimumBitrateBpsLong(3840, 2160, fps = 60)

            assertEquals(expectedFloor, sixtyFps.minimumBitrateBps)
            assertEquals(expectedFloor, sixtyFps.maximumBitrateBps)
            assertTrue(sixtyFps.isAllIntraRequired)
            assertTrue(sixtyFps.hdrLookBypass)
            assertTrue(sixtyFps.tenBitMain10Required)
            assertEquals("BT.709", sixtyFps.colorPrimaries)
            assertEquals("SDR_VIDEO", sixtyFps.colorTransfer)
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    private fun locatePolicySource(): File {
        val candidates = listOf(
            "app/src/main/java/com/neoproc/neoproccamx/recording/BitPerfectRecordingPolicy.kt",
            "src/main/java/com/neoproc/neoproccamx/recording/BitPerfectRecordingPolicy.kt"
        )
        return candidates
            .map { File(it).absoluteFile }
            .firstOrNull { it.exists() }
            ?: error("Could not locate BitPerfectRecordingPolicy.kt")
    }
}
