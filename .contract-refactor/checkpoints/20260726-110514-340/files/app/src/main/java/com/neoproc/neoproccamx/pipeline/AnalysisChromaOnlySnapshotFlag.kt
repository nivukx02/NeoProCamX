package com.neoproc.neoproccamx.pipeline

/**
 * AnalysisChromaOnlySnapshotFlag — reversible chroma-only source snapshot for the scene-stats / opt-in
 * HUD analysis frame built by [FramePipelineManager.snapshotAnalysisRawFrame].
 *
 * WHY: snapshotAnalysisRawFrame() currently calls snapshotRawYuvFrame() -> Yuv420ImageTools.snapshot(),
 * which fully copies the source Y plane (~12.6 MB at 4096x3072 8-bit, ~25 MB for 10-bit P010) on every
 * scene-stats refresh. That Y copy is IMMEDIATELY DISCARDED — the analysis frame's luma always comes from
 * the already-downscaled LumaPlaneSnapshot, and `source.yBytes` is never read. When enabled, the analysis
 * frame is built from Yuv420ImageTools.snapshotChromaOnly(), which copies only the U/V planes
 * (byte-for-byte identical chroma) and leaves yBytes empty, eliminating the wasted per-refresh Y
 * allocation + memcpy + GC pressure.
 *
 * NO-DOWNGRADE: the resulting analysis RawYuvFrame is byte-identical (same downscaled luma, same chroma
 * bytes/strides/pixelStrides, same width/height/bitDepth, same empty-chroma fallback). It only removes an
 * allocation+copy that is never observed. The shared Yuv420ImageTools.snapshot()/snapshotP010() used by
 * the NPV10 disk-writer, GPU recorder, and AI depth/segmentation paths are NOT touched.
 *
 * Default OFF (dormant; exact shipped behavior) pending on-device A/B. Read per analysis-frame snapshot,
 * so a flip takes effect on the next scene-stats refresh:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.analysis_chroma_only_snapshot true
 */
object AnalysisChromaOnlySnapshotFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
