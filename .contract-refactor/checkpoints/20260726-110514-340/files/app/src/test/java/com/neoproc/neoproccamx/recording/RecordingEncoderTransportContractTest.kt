package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-contract lock for the s6-f01 encoder transport-truth wiring in RecordingManager: the
 * start-time read-back is invoked on both drain paths, the evidence is built and merged additively
 * onto the certificate, and the read-back is containsKey-guarded (no false 0). Same source-reflection
 * idiom as RecordingManagerRealtimeRecordingContractTest.
 */
class RecordingEncoderTransportContractTest {

    private val source =
        File("src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt").readText()

    @Test
    fun readBackInvokedOnBothSurfaceAndBlockModelDrainPaths() {
        assertTrue(source.contains("captureEncoderTransportReadBack(format)"))
        assertTrue(source.contains("captureEncoderTransportReadBack(event.format)"))
    }

    @Test
    fun readBackIsContainsKeyGuardedAndOnceOnly() {
        val fn = source
            .substringAfter("private fun captureEncoderTransportReadBack(format: MediaFormat) {")
            .substringBefore("private fun writeQualityCertificateForFinalizedRecording(")
        assertTrue(fn.contains("if (encoderTransportReadBackAvailable) return"))
        assertTrue(fn.contains("format.containsKey(MediaFormat.KEY_PROFILE)"))
        assertTrue(fn.contains("format.containsKey(MediaFormat.KEY_LEVEL)"))
        assertTrue(fn.contains("format.containsKey(MediaFormat.KEY_BIT_RATE)"))
        assertTrue(fn.contains("encoderTransportReadBackAvailable = true"))
    }

    @Test
    fun evidenceBuiltFromConfigAndMergedAdditivelyIntoCertificate() {
        assertTrue(source.contains("EncoderTransportEvidencePolicy.build("))
        // Muxed color is sourced from the MOV color-atom config, not the encoder output format.
        assertTrue(source.contains("muxedColorStandard = activeColorEncodingConfig.colorStandard"))
        // Surface-mode input frame count is honestly unobservable -> NOT_PROVEN.
        assertTrue(source.contains("inputFrameCount = null"))
        val certificateBlock = source
            .substringAfter("RecordingQualityEvidenceRequest(")
            .substringBefore("generatedAtEpochMs")
        assertTrue(certificateBlock.contains("encoderTransportMetadata"))
        assertTrue(
            "encoderTransportMetadata must be appended after the persistent-surface map",
            certificateBlock.indexOf("encoderTransportMetadata") >
                certificateBlock.indexOf("persistentInputSurfaceMetadata")
        )
    }
}
