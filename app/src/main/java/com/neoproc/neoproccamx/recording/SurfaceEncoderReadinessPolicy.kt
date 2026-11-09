package com.neoproc.neoproccamx.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat

/**
 * Fail-closed contract for waking a surface-input encoder before live sensor ingress begins.
 *
 * The readiness frame is transport-only. It is never a clip frame, never advances the clip clock,
 * and may not authorize an 8-bit or colour-transport fallback.
 */
internal object SurfaceEncoderReadinessPolicy {
    const val DEFAULT_TIMEOUT_MS = 2_000L

    enum class Phase {
        INACTIVE,
        WARMING,
        READY,
        CLIP_ACTIVE,
        FAILED
    }

    data class OutputFormatEvidence(
        val mime: String?,
        val profile: Int?,
        val colorStandard: Int?,
        val colorTransfer: Int?
    )

    data class Verdict(
        val accepted: Boolean,
        val reason: String
    )

    /**
     * Verifies the actual output format against the immutable colour contract retained for this
     * encoder session. The offscreen P010 route is HEVC Main10 in SDR, Log, and PQ modes; readiness
     * must therefore prove the selected transfer/primaries instead of assuming that every route is
     * PQ. This keeps pre-roll useful for direct Gallery SDR without weakening its 10-bit contract.
     */
    fun verifyConfiguredHevcMain10(
        evidence: OutputFormatEvidence,
        expectedColorStandard: Int,
        expectedColorTransfer: Int
    ): Verdict {
        if (!evidence.mime.equals("video/hevc", ignoreCase = true)) {
            return Verdict(false, "encoder output MIME is not HEVC")
        }
        val main10 = evidence.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
            evidence.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
            evidence.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus
        if (!main10) {
            return Verdict(false, "encoder output profile is not HEVC Main10")
        }
        if (evidence.colorStandard != expectedColorStandard) {
            return Verdict(
                false,
                "encoder output color standard ${evidence.colorStandard} does not match " +
                    "configured $expectedColorStandard"
            )
        }
        if (evidence.colorTransfer != expectedColorTransfer) {
            return Verdict(
                false,
                "encoder output transfer ${evidence.colorTransfer} does not match " +
                    "configured $expectedColorTransfer"
            )
        }
        return Verdict(
            true,
            "HEVC Main10 output matches configured color standard/transfer"
        )
    }

    fun verifyStrictHevcMain10Pq(evidence: OutputFormatEvidence): Verdict {
        val verdict = verifyConfiguredHevcMain10(
            evidence = evidence,
            expectedColorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            expectedColorTransfer = MediaFormat.COLOR_TRANSFER_ST2084
        )
        return if (verdict.accepted) {
            Verdict(true, "HEVC Main10 BT.2020/PQ output proven")
        } else {
            verdict
        }
    }

    fun shouldDiscardOutput(phase: Phase): Boolean =
        phase == Phase.WARMING || phase == Phase.READY || phase == Phase.FAILED

    fun readinessProven(
        formatAccepted: Boolean,
        readinessSampleDiscarded: Boolean
    ): Boolean = formatAccepted && readinessSampleDiscarded

    fun verifyFirstClipSample(keyFrameRequired: Boolean, bufferFlags: Int): Verdict {
        if (!keyFrameRequired) return Verdict(true, "post-readiness keyframe gate inactive")
        return if (bufferFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
            Verdict(true, "first live sample is a random-access keyframe")
        } else {
            Verdict(false, "first live sample after encoder pre-roll is not a keyframe")
        }
    }
}
