package com.neoproc.neoproccamx.camera

import android.util.Size

internal object CameraAnalysisReaderTargetPolicy {
    fun resolveRequestedAnalysisSize(
        analysisTargetSize: Size?,
        recordingReadoutOverrideSize: Size?
    ): Size? {
        // Full-sensor readout intent should guide capture/readout surfaces, not force the
        // AI/analysis YUV reader up to the same size when we already have a real-time target.
        return analysisTargetSize ?: recordingReadoutOverrideSize
    }
}
