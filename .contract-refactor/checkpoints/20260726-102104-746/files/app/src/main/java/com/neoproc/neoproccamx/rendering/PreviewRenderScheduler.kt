package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal class PreviewRenderScheduler(
    private val isMainThread: () -> Boolean,
    private val postToMain: (() -> Unit) -> Unit,
    private val scheduleFrame: () -> Unit
) {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private var renderQueued = false
    private var renderFrameScheduled = false
    private var renderRequestedWhileScheduled = false

    fun requestRender() {
        if (!isMainThread()) {
            postToMain { requestRender() }
            return
        }
        if (renderFrameScheduled) {
            renderRequestedWhileScheduled = true
            return
        }
        if (renderQueued) {
            if (strictHighEndEnabled()) {
                renderRequestedWhileScheduled = true
            }
            return
        }
        renderQueued = true
        renderFrameScheduled = true
        scheduleFrame()
    }

    fun onFrameDispatched() {
        renderFrameScheduled = false
        renderQueued = false
        if (renderRequestedWhileScheduled) {
            renderRequestedWhileScheduled = false
            requestRender()
        }
    }

    fun cancelScheduledFrame(): Boolean {
        val hadScheduledFrame = renderFrameScheduled
        renderFrameScheduled = false
        renderQueued = false
        renderRequestedWhileScheduled = false
        return hadScheduledFrame
    }
}
