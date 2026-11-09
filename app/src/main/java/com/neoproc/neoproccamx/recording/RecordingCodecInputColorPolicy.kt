package com.neoproc.neoproccamx.recording

internal object RecordingCodecInputColorPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun resolve(
        mime: String,
        outputColorEncoding: RecordingColorEncodingConfig
    ): RecordingColorEncodingConfig {
        return outputColorEncoding
    }
}
