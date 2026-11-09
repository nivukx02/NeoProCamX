package com.neoproc.neoproccamx.pipeline

/**
 * AiAnalysisConvertSkipFlag — thermal/no-downgrade lever (default ON) that elides a per-frame
 * YUV->ARGB analysis conversion whose result is provably discarded.
 *
 * On the [AICameraPipeline.analyzeMediaImage] ingress (fed by FramePipelineManager for the live YUV
 * analysis stream), the incoming media image is copied into a full analysis bitmap on EVERY strided
 * frame — BEFORE the pipeline knows whether any AI stage will use it. When NO detector is enabled
 * (face / pose / segmentation / enhancement / depth / scene all off) AND nothing taps the preview
 * bitmap, the downstream [AICameraPipeline.processBitmapFrame] takes its "all features disabled"
 * early-exit: it builds and posts an EMPTY frame result and throws the freshly-converted bitmap
 * straight back into the pool. The convert (a multi-plane heap copy + per-pixel pack) and the
 * bitmap-pool churn are therefore pure waste — dead CPU cycles and shared-DRAM bandwidth (= heat)
 * on the idle/armed video-preview path.
 *
 * When ON, [AICameraPipeline.analyzeMediaImage] detects that same all-disabled + no-preview-tap
 * condition up front, posts the IDENTICAL empty [AICameraFrameResult] directly, releases the image,
 * and returns — skipping the convert + enqueue + drain + discard entirely.
 *
 * NO DOWNGRADE: the observable output is byte-identical. Consumers receive the same empty
 * AICameraFrameResult on the same coalesced main-thread dispatch they would have received from the
 * early-exit; the only thing removed is dead compute/bandwidth. It never fires while any detector or
 * a preview tap is active, so it cannot affect AF / face / subject / depth / scene features or the
 * recorded video (the analysis stream never reaches the saved pixels regardless). Purely a heat/power
 * elision on frames the app was already throwing away.
 *
 * DEFAULT ON. Reversible for on-device A/B (revert to the eager convert):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.ai_analysis_convert_skip false
 */
object AiAnalysisConvertSkipFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
