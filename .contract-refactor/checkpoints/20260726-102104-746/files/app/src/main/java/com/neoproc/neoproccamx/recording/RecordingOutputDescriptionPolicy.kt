package com.neoproc.neoproccamx.recording

import java.util.Locale

internal data class RecordingOutputDescriptionSnapshot(
    val videoWidth: Int,
    val videoHeight: Int,
    val videoFps: Int,
    val codecLabel: String,
    val bitDepthLabel: String,
    val colorSpace: String,
    val hdrIntentLabel: String,
    val dynamicRangeLabel: String,
    val logCurveLabel: String,
    val qualityTierLabel: String,
    val cinemaMasteringEnabled: Boolean,
    val allIntraEnabled: Boolean,
    val gopSeconds: Float,
    val simulatedTenBitFallbackActive: Boolean,
    val bitrateBitsPerSecond: Int,
    val sceneMetadataDescription: String
)

internal object RecordingOutputDescriptionPolicy {
    fun build(snapshot: RecordingOutputDescriptionSnapshot): String {
        val scene = snapshot.sceneMetadataDescription.trim()
        val base = buildString {
            append("video=")
            append("${snapshot.videoWidth}x${snapshot.videoHeight}@${snapshot.videoFps}fps")
            append(";codec=")
            append(snapshot.codecLabel)
            append(";bitDepth=")
            append(snapshot.bitDepthLabel)
            append(";color=")
            append(snapshot.colorSpace)
            append(";hdrIntent=")
            append(snapshot.hdrIntentLabel)
            append(";dynamicRange=")
            append(snapshot.dynamicRangeLabel)
            append(";logCurve=")
            append(snapshot.logCurveLabel)
            append(";qualityTier=")
            append(snapshot.qualityTierLabel)
            append(";cinemaMaster=")
            append(snapshot.cinemaMasteringEnabled)
            append(";allIntra=")
            append(snapshot.allIntraEnabled)
            append(";gopSeconds=")
            append(String.format(Locale.US, "%.2f", snapshot.gopSeconds))
            append(";simulatedTenBit=")
            append(snapshot.simulatedTenBitFallbackActive)
            append(";bitrateMbps=")
            append(String.format(Locale.US, "%.1f", snapshot.bitrateBitsPerSecond / 1_000_000f))
        }
        return if (scene.isNotEmpty()) "$base;$scene" else base
    }
}
