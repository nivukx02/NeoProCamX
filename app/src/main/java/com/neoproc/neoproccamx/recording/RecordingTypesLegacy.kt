package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

enum class VideoCodecType {
    HEVC,
    AVC,
    AV1;

    fun normalizeForStrictHighEnd(): VideoCodecType =
        if (HighEndQualityPolicy.enabled &&
            HighEndQualityPolicy.strictHighEndOnly &&
            this == AVC
        ) {
            HEVC
        } else {
            this
        }
}

enum class AudioSrc {
    CAMCORDER,
    MIC,
    UNPROCESSED,
    NONE;

    fun normalizeForStrictHighEnd(): AudioSrc =
        if (HighEndQualityPolicy.enabled &&
            HighEndQualityPolicy.strictHighEndOnly &&
            this != NONE
        ) {
            UNPROCESSED
        } else {
            this
        }
}
