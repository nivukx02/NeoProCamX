package com.neoproc.neoproccamx.recording

import java.util.Locale

internal enum class RecordingOutputColorRange {
    LIMITED,
    FULL
}

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
    /** Exact range configured on the immutable encoder session, not the current UI mode. */
    val colorRange: RecordingOutputColorRange,
    /** Exact ST.2086 mastering peak retained by the encoder session; null for non-PQ sessions. */
    val masteringPeakNits: Double?,
    val sceneMetadataDescription: String
)

internal object RecordingOutputDescriptionPolicy {
    /**
     * Runtime scene/validation evidence may legitimately advance after encoder preparation. Refresh
     * only that packet; every encoder-owned field (especially range and mastering peak) stays bound
     * to the immutable prepared-session snapshot.
     */
    fun withLatestSceneMetadata(
        preparedSnapshot: RecordingOutputDescriptionSnapshot,
        latestSceneMetadataDescription: String
    ): RecordingOutputDescriptionSnapshot =
        preparedSnapshot.copy(sceneMetadataDescription = latestSceneMetadataDescription)

    fun build(snapshot: RecordingOutputDescriptionSnapshot): String {
        val scene = snapshot.sceneMetadataDescription.trim()
        val masteringPeakNits = resolveSerializedMasteringPeakNits(snapshot)
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
            // These authoritative session fields intentionally precede scene metadata. The reader
            // uses first-value-wins semantics, so an appended stale/hostile scene packet cannot
            // relabel the pixels that the configured encoder actually produced.
            append(";colorRange=")
            append(snapshot.colorRange.name)
            append(";masteringPeakNits=")
            append(masteringPeakNits?.toDescriptionValue() ?: "NONE")
        }
        return if (scene.isNotEmpty()) "$base;$scene" else base
    }

    /**
     * Branded NDR masters have fixed product mastering contracts. A generic PQ session retains its
     * explicit encoder-session ST.2086 peak, with a conservative 1,000-nit fail-closed value when
     * that value is absent or malformed. Non-PQ sessions do not invent mastering-display metadata.
     */
    private fun resolveSerializedMasteringPeakNits(
        snapshot: RecordingOutputDescriptionSnapshot
    ): Double? {
        val identity = "${snapshot.hdrIntentLabel} ${snapshot.dynamicRangeLabel}"
            .uppercase(Locale.US)
        return when {
            identity.contains("NIVUKX") || identity.contains("APEX") -> 10_000.0
            identity.contains("NDR10") -> 5_000.0
            isGenericPq(identity, snapshot.masteringPeakNits) ->
                snapshot.masteringPeakNits.toValidMasteringPeakOrNull() ?: 1_000.0
            else -> null
        }
    }

    private fun isGenericPq(identity: String, explicitPeakNits: Double?): Boolean =
        explicitPeakNits != null ||
            identity.contains("ST2084") ||
            identity.contains("ST.2084") ||
            identity.contains("PQ")

    private fun Double?.toValidMasteringPeakOrNull(): Double? {
        val value = this ?: return null
        if (!value.isFinite() || value < 1_000.0 || value > 10_000.0) return null
        return value
    }

    private fun Double.toDescriptionValue(): String =
        if (this == toLong().toDouble()) toLong().toString() else toString()
}
