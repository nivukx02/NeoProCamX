package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Regression tests for [AllIntraHevcPolicy].
 *
 * Note: [AllIntraHevcPolicy.applyToFormat] writes to a [android.media.MediaFormat]
 * which is an Android-runtime stub in unit tests (returnDefaultValues = true). We
 * therefore cannot read back the integer profile after a setInteger call. Instead
 * we assert at the source-code level that the buggy `setString(KEY_PROFILE, ...)`
 * pattern is gone — that single line silently overwrote the resolved HEVC profile
 * (Main / Main10) because [android.media.MediaFormat.KEY_PROFILE] is integer-typed.
 *
 * The runtime contract is also exercised via [AllIntraHevcPolicy.probe] which
 * gracefully returns null on the stub Android runtime; we verify that fallback
 * behavior so callers can rely on a non-throwing capability check at startup.
 */
class AllIntraHevcPolicyTest {

    @Test
    fun resolveEffectiveBitrate_usesValidatedEncoderRangeInsteadOfFixedMobileCeiling() {
        assertEquals(180_000_000, AllIntraHevcPolicy.BITRATE_CINEMA_MASTER)
        assertEquals(120_000_000, AllIntraHevcPolicy.BITRATE_PRO_ALL_INTRA)
        assertEquals(
            800_000_000,
            AllIntraHevcPolicy.resolveEffectiveBitrate(
                preferredBitrate = 800_000_000,
                capabilityLowerBound = 64_000,
                capabilityUpperBound = 900_000_000
            )
        )
        assertEquals(
            600_000_000,
            AllIntraHevcPolicy.resolveEffectiveBitrate(
                preferredBitrate = 800_000_000,
                capabilityLowerBound = 64_000,
                capabilityUpperBound = 600_000_000
            )
        )
        assertEquals(
            80_000_000,
            AllIntraHevcPolicy.resolveEffectiveBitrate(
                preferredBitrate = 60_000_000,
                capabilityLowerBound = 80_000_000,
                capabilityUpperBound = 600_000_000
            )
        )
    }

    @Test
    fun applyToFormat_doesNotOverwriteIntegerProfileWithStringHint() {
        val text = locatePolicySource().readText()
        // The fixed file must not contain the prior bug pattern that overwrote
        // the documented integer KEY_PROFILE with a string "all-intra" value.
        assertFalse(
            "AllIntraHevcPolicy must not call setString(MediaFormat.KEY_PROFILE, ...) — " +
                "KEY_PROFILE is integer-typed and a string would silently overwrite " +
                "the resolved HEVC profile (Main / Main10).",
            text.contains("setString(MediaFormat.KEY_PROFILE")
        )
        // The integer profile contract must still be set.
        assert(text.contains("setInteger(MediaFormat.KEY_PROFILE, config.profile)")) {
            "AllIntraHevcPolicy must keep the integer KEY_PROFILE write intact."
        }
        // All-Intra is signalled truthfully via KEY_I_FRAME_INTERVAL = 0
        // ("every frame is an I-frame" per Android docs). The previous
        // code-and-test pair locked in the value `1`, which actually means
        // a 1-second GOP (~FPS frames between keyframes) — silently producing
        // standard IPB while claiming all-intra. The fix uses 0 with API-aware
        // setFloat on N_MR1+ (since KEY_I_FRAME_INTERVAL is float-typed there).
        assert(text.contains("setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0f)")) {
            "All-Intra must signal KEY_I_FRAME_INTERVAL = 0f (setFloat) on API >= N_MR1."
        }
        assert(text.contains("setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)")) {
            "All-Intra must signal KEY_I_FRAME_INTERVAL = 0 (setInteger) on API < N_MR1."
        }
        // Hard regression guard: the buggy `, 1)` pattern that previously
        // shipped as 'all-intra' must never come back.
        assertFalse(
            "AllIntraHevcPolicy must not write KEY_I_FRAME_INTERVAL = 1 — that is a " +
                "1-second GOP, not all-intra.",
            text.contains("setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)") ||
                text.contains("setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 1f)")
        )
        assert(text.contains("setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)")) {
            "All-Intra must disable B-frames so every encoded frame remains an independent I-frame."
        }
    }

    @Test
    fun applyToFormat_preservesResolvedRecordingColorMetadataInsteadOfForcingHlg() {
        val policyText = locatePolicySource().readText()
        val recordingManagerText = locateRecordingManagerSource().readText()

        assertFalse(
            "All-Intra probing must not hard-code HLG for every 10-bit request. " +
                "Pro/Video can be 10-bit SDR and LOG is wide-gamut SDR, so the final " +
                "MediaFormat color transfer must come from the recording color resolver.",
            policyText.contains("colorTransfer = if (tenBit) MediaFormat.COLOR_TRANSFER_HLG")
        )
        assert(
            policyText.contains("colorEncoding: RecordingColorEncodingConfig")
        ) {
            "AllIntraHevcPolicy.applyToFormat must accept the resolved recording color encoding."
        }
        assert(
            recordingManagerText.contains("AllIntraHevcPolicy.applyToFormat(") &&
                recordingManagerText.contains("videoPlan.colorEncoding")
        ) {
            "RecordingManager must pass the authoritative videoPlan.colorEncoding into All-Intra " +
                "so SDR/LOG/HDR modes keep their selected transfer metadata."
        }
    }

    @Test
    fun probe_gracefullyReturnsNullOnUnsupportedRuntime() {
        // On the unit-test JVM there is no real MediaCodecList, so the policy's
        // outer try/catch must turn that into a clean null instead of an
        // exception. Callers depend on this contract for startup capability checks.
        val result = AllIntraHevcPolicy.probe(
            width = 3840,
            height = 2160,
            frameRate = 30,
            tenBit = false,
            preferredBitrate = AllIntraHevcPolicy.BITRATE_PRO_ALL_INTRA
        )
        assertNull("probe() must return null when the runtime cannot enumerate codecs", result)
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

    private fun locateRecordingManagerSource(): File {
        val candidates = listOf(
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt",
            "src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt"
        )
        val found = candidates
            .map { File(it).absoluteFile }
            .firstOrNull { it.exists() }
        return found ?: error(
            "Could not locate RecordingManager.kt; tried: ${candidates.joinToString()}"
        )
    }
}
