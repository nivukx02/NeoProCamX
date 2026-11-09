package com.neoproc.neoproccamx.recording

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the RAW-video capture-mode contract for the realtime bit-exact 16-bit packet stream.
 *
 * Two roles are deliberately distinct:
 *  - [RawVideoContractPolicy.canonicalUiCompressionMode] — UI/preference facing, kept byte-identical to
 *    the legacy single-valued selector (always the uncompressed 12-bit master). Locked by
 *    [VideoRecordingContractPolicyTest] and [com.neoproc.neoproccamx.ui.CameraPreferenceValuePolicyTest].
 *  - [RawVideoContractPolicy.resolveVideoCaptureMode] — CAPTURE boundary, always applies the strict
 *    direct packed-16-bit lossless default. Diagnostic overrides cannot downgrade recording.
 */
class RawVideoContractPolicyTest {

    @After
    fun clearOverride() {
        RawVideoContractPolicy.applyCaptureModeOverride(null)
    }

    @Test
    fun `capture boundary applies realtime packed 16 bit lossless default`() {
        assertEquals(RawCompressionMode.LOSSLESS_REALTIME, RawVideoContractPolicy.DEFAULT_VIDEO_CAPTURE_MODE)
        // The UI normalizes every request to the canonical uncompressed master, so capture ignores the
        // requested value and applies the realtime lossless default irrespective of what is passed in.
        for (requested in RawCompressionMode.entries) {
            assertEquals(
                "capture must apply the realtime lossless default regardless of requested ${requested.name}",
                RawCompressionMode.LOSSLESS_REALTIME,
                RawVideoContractPolicy.resolveVideoCaptureMode(requested)
            )
        }
    }

    @Test
    fun `ui canonicalization stays uncompressed 12 bit master and never leaks the capture default`() {
        for (requested in RawCompressionMode.entries) {
            assertEquals(
                "UI/pref must stay the canonical uncompressed master for ${requested.name}",
                RawCompressionMode.HIGH_COMPRESSION,
                RawVideoContractPolicy.canonicalUiCompressionMode(requested)
            )
        }
    }

    @Test
    fun `debug override cannot downgrade capture to legacy packed 12 bit`() {
        RawVideoContractPolicy.applyCaptureModeOverride("HIGH_COMPRESSION")
        assertEquals(RawCompressionMode.HIGH_COMPRESSION, RawVideoContractPolicy.captureModeOverride)
        assertEquals(
            RawCompressionMode.LOSSLESS_REALTIME,
            RawVideoContractPolicy.resolveVideoCaptureMode(RawCompressionMode.HIGH_COMPRESSION)
        )
        assertEquals(12, RawCompressionMode.getBitDepth(RawCompressionMode.HIGH_COMPRESSION))
        assertTrue(RawVideoContractPolicy.isUncompressedTwelveBitRaw(RawCompressionMode.HIGH_COMPRESSION))
    }

    @Test
    fun `debug override values are retained for telemetry but capture remains lossless`() {
        RawVideoContractPolicy.applyCaptureModeOverride("LOSSLESS_ENHANCED")
        assertEquals(
            RawCompressionMode.LOSSLESS_REALTIME,
            RawVideoContractPolicy.resolveVideoCaptureMode(RawCompressionMode.HIGH_COMPRESSION)
        )

        // OFF remains a diagnostic preference only and cannot switch video to frame-file export.
        RawVideoContractPolicy.applyCaptureModeOverride("off")
        assertEquals(
            RawCompressionMode.LOSSLESS_REALTIME,
            RawVideoContractPolicy.resolveVideoCaptureMode(RawCompressionMode.HIGH_COMPRESSION)
        )

        // Blank / "DEFAULT" clears the override, returning to the realtime lossless capture default.
        RawVideoContractPolicy.applyCaptureModeOverride("DEFAULT")
        assertEquals(null, RawVideoContractPolicy.captureModeOverride)
        assertEquals(
            RawCompressionMode.LOSSLESS_REALTIME,
            RawVideoContractPolicy.resolveVideoCaptureMode(RawCompressionMode.HIGH_COMPRESSION)
        )
    }

    @Test
    fun `unknown override value cannot change strict lossless capture`() {
        RawVideoContractPolicy.applyCaptureModeOverride("not_a_real_mode")
        assertEquals(
            RawCompressionMode.LOSSLESS_REALTIME,
            RawVideoContractPolicy.resolveVideoCaptureMode(RawCompressionMode.HIGH_COMPRESSION)
        )
    }
}
