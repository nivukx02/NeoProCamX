package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraReaderCloseRequest
import com.neoproc.neoproccamx.capture.StillCapture
import com.neoproc.neoproccamx.pipeline.FramePipelineManager
import com.neoproc.neoproccamx.pipeline.RawVideoManager
import java.util.concurrent.TimeUnit

/**
 * One deadline and one destructive boundary for every owner of an Image acquired from Camera2.
 * Relevant ingress domains are sealed before any wait; the close action is unreachable unless every
 * selected owner proves quiescence within the same budget.
 */
internal class CameraReaderCloseCoordinator(
    private val sealFramePipelineIngress: () -> AutoCloseable,
    private val awaitFramePipelineIdle: (Long) -> Boolean,
    private val sealStillCaptureIngress: () -> AutoCloseable,
    private val awaitStillCaptureIdle: (Long) -> Boolean,
    private val awaitRawVideoIdle: (Long) -> Boolean,
    private val monotonicNowNs: () -> Long = System::nanoTime
) {
    constructor(
        pipeline: FramePipelineManager,
        stillCapture: StillCapture,
        rawVideoManager: RawVideoManager
    ) : this(
        sealFramePipelineIngress = pipeline::sealImageIngressForReaderClose,
        awaitFramePipelineIdle = pipeline::awaitIdleBeforeReaderClose,
        sealStillCaptureIngress = stillCapture::sealImageReaderIngress,
        awaitStillCaptureIdle = stillCapture::awaitCameraBuffersReleased,
        awaitRawVideoIdle = rawVideoManager::awaitImageReaderOwnershipIdle
    )

    fun closeReaders(
        request: CameraReaderCloseRequest,
        timeoutMs: Long,
        closeReaders: () -> Unit
    ): Boolean {
        if (timeoutMs <= 0L) return false
        val deadlineNs = saturatedDeadlineNs(timeoutMs)
        val seals = ArrayList<AutoCloseable>(2)
        return try {
            if (request.requiresFramePipelineDrain) seals += sealFramePipelineIngress()
            if (request.requiresStillCaptureDrain) seals += sealStillCaptureIngress()

            if (request.requiresFramePipelineDrain &&
                !awaitFramePipelineIdle(remainingMs(deadlineNs))
            ) return false
            if (request.requiresStillCaptureDrain &&
                !awaitStillCaptureIdle(remainingMs(deadlineNs))
            ) return false
            if (request.requiresRawVideoDrain &&
                !awaitRawVideoIdle(remainingMs(deadlineNs))
            ) return false
            if (remainingNs(deadlineNs) <= 0L) return false

            closeReaders()
            true
        } finally {
            seals.asReversed().forEach { seal -> runCatching { seal.close() } }
        }
    }

    private fun saturatedDeadlineNs(timeoutMs: Long): Long {
        val nowNs = monotonicNowNs()
        val timeoutNs = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        return if (Long.MAX_VALUE - nowNs < timeoutNs) Long.MAX_VALUE else nowNs + timeoutNs
    }

    private fun remainingMs(deadlineNs: Long): Long {
        val remainingNs = remainingNs(deadlineNs)
        if (remainingNs <= 0L) return 0L
        val wholeMs = remainingNs / 1_000_000L
        return (wholeMs + if (remainingNs % 1_000_000L == 0L) 0L else 1L).coerceAtLeast(1L)
    }

    private fun remainingNs(deadlineNs: Long): Long =
        (deadlineNs - monotonicNowNs()).coerceAtLeast(0L)
}
