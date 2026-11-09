package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.LiveResult

internal object PreviewFramePresentationPolicy {
    fun timestampForPreviewFrame(result: LiveResult?): Long? =
        result?.timestampNs?.takeIf { it > 0L }
}
