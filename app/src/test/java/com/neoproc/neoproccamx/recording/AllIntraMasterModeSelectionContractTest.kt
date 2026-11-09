package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-contract guard for the Master-mode selection policy (CP-QV12).
 *
 * [AllIntraMasterModePolicy.MasterTier] references the `AllIntraHevcPolicy.BITRATE_*` constants by
 * name, so if any of those four constants were renamed or removed the *main* module would fail to
 * compile. This test additionally pins, at the source-text level, that:
 *
 *  1. the four tier constants keep their exact 80 / 100 / 120 / 180 Mbps values, and
 *  2. the all-intra signalling (`KEY_I_FRAME_INTERVAL = 0` and `KEY_MAX_B_FRAMES = 0`) remains —
 *     so a Master (cinema-master) recording can NEVER silently regress into a standard IPB stream
 *     with a GOP and B-frames while still claiming all-intra.
 *
 * The runtime honoring of these keys by a real encoder is DEVICE-GATED; here we lock the intent.
 */
class AllIntraMasterModeSelectionContractTest {

    @Test
    fun allIntraHevcPolicyKeepsFourMasterTierConstants() {
        val text = locatePolicySource().readText()
        assertTrue(
            "BITRATE_ECONOMY (80 Mbps) must remain the source-of-truth ECONOMY tier constant.",
            text.contains("const val BITRATE_ECONOMY = 80_000_000")
        )
        assertTrue(
            "BITRATE_STANDARD (100 Mbps) must remain the source-of-truth STANDARD tier constant.",
            text.contains("const val BITRATE_STANDARD = 100_000_000")
        )
        assertTrue(
            "BITRATE_PRO_ALL_INTRA (120 Mbps) must remain the source-of-truth PRO tier constant.",
            text.contains("const val BITRATE_PRO_ALL_INTRA = 120_000_000")
        )
        assertTrue(
            "BITRATE_CINEMA_MASTER (180 Mbps) must remain the source-of-truth CINEMA tier constant.",
            text.contains("const val BITRATE_CINEMA_MASTER = 180_000_000")
        )
    }

    @Test
    fun allIntraSignallingStaysIntraOnly_masterCannotRegressToIpb() {
        val text = locatePolicySource().readText()
        // Every frame is an I-frame: KEY_I_FRAME_INTERVAL = 0 (float on N_MR1+, int below).
        assertTrue(
            "All-Intra must signal KEY_I_FRAME_INTERVAL = 0f (setFloat) on API >= N_MR1.",
            text.contains("setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0f)")
        )
        assertTrue(
            "All-Intra must signal KEY_I_FRAME_INTERVAL = 0 (setInteger) on API < N_MR1.",
            text.contains("setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)")
        )
        // No inter-frame prediction: B-frames disabled so each frame stays independent.
        assertTrue(
            "All-Intra must disable B-frames (KEY_MAX_B_FRAMES = 0) so Master cannot regress to IPB.",
            text.contains("setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)")
        )
    }

    private fun locatePolicySource(): File {
        val candidates = listOf(
            "app/src/main/java/com/neoproc/neoproccamx/recording/AllIntraHevcPolicy.kt",
            "src/main/java/com/neoproc/neoproccamx/recording/AllIntraHevcPolicy.kt"
        )
        val found = candidates
            .map { File(it).absoluteFile }
            .firstOrNull { it.exists() }
        return found ?: error(
            "Could not locate AllIntraHevcPolicy.kt; tried: ${candidates.joinToString()}"
        )
    }
}
