package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs

internal data class RecordingRuntimeHintDecision(
    val highFidelitySession: Boolean,
    val bitrateDeltaRatio: Float,
    val shouldPushBitrate: Boolean,
    val shouldRequestSyncFrame: Boolean
)

internal object RecordingRuntimeHintPolicy {
    fun decide(
        width: Int,
        height: Int,
        hdrMode: String,
        tenBitEnabled: Boolean,
        sceneMetadataDescription: String,
        activeOutputBitrate: Int,
        lastHintedBitrate: Int,
        nowMs: Long,
        lastSyncFrameRequestUptimeMs: Long,
        syncFrameBitrateDeltaRatio: Float,
        syncFrameRequestIntervalMs: Long
    ): RecordingRuntimeHintDecision {
        val highFidelitySession =
            width.toLong() * height >= 3840L * 2160L ||
                !hdrMode.equals("OFF", ignoreCase = true) ||
                tenBitEnabled ||
                sceneMetadataDescription.contains(";log=true", ignoreCase = true)
        val bitrateDeltaRatio =
            if (lastHintedBitrate <= 0) {
                1f
            } else {
                abs(activeOutputBitrate - lastHintedBitrate).toFloat() / lastHintedBitrate.toFloat()
            }
        val strictHighEnd = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        val firstRuntimeHint = lastHintedBitrate <= 0
        val pushDeltaThreshold = if (highFidelitySession) {
            0.05f
        } else {
            0.10f
        }
        val qualityPolicyAllowsRuntimeHint =
            strictHighEnd || HighEndQualityPolicy.enabled || !highFidelitySession
        val shouldPushBitrate =
            qualityPolicyAllowsRuntimeHint &&
                (firstRuntimeHint || bitrateDeltaRatio >= pushDeltaThreshold)
        val syncDeltaThreshold = if (strictHighEnd && highFidelitySession) {
            (syncFrameBitrateDeltaRatio * 0.45f).coerceAtLeast(0.03f)
        } else if (highFidelitySession && HighEndQualityPolicy.enabled) {
            (syncFrameBitrateDeltaRatio * 0.60f).coerceAtLeast(0.04f)
        } else {
            syncFrameBitrateDeltaRatio
        }
        val shouldRequestSyncFrame =
            shouldPushBitrate &&
                bitrateDeltaRatio >= syncDeltaThreshold &&
                nowMs - lastSyncFrameRequestUptimeMs >= syncFrameRequestIntervalMs
        return RecordingRuntimeHintDecision(
            highFidelitySession = highFidelitySession,
            bitrateDeltaRatio = bitrateDeltaRatio,
            shouldPushBitrate = shouldPushBitrate,
            shouldRequestSyncFrame = shouldRequestSyncFrame
        )
    }
}
