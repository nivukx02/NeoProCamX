package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoPlaybackPerformancePolicyTest {

    @Test
    fun `local camera masters require lossless video renderer admission`() {
        assertEquals(0L, VideoPlaybackPerformancePolicy.ALLOWED_VIDEO_JOINING_TIME_MS)

        val activity = File(
            "src/main/java/com/neoproc/neoproccamx/ui/MediaPreviewActivity.kt"
        ).readText()
        assertTrue(activity.contains(".setAllowedVideoJoiningTimeMs("))
        assertTrue(activity.contains("VideoPlaybackPerformancePolicy.ALLOWED_VIDEO_JOINING_TIME_MS"))
    }

    @Test
    fun `private master decode starts ahead of presentation deadline`() {
        assertEquals(
            28_333_000L,
            VideoPlaybackPerformancePolicy.decodeStartDelayNs(
                remainingMediaUs = 33_333L,
                playbackSpeed = 1f,
                estimatedDecodeNs = 5_000_000L
            )
        )
        assertEquals(
            11_666_500L,
            VideoPlaybackPerformancePolicy.decodeStartDelayNs(
                remainingMediaUs = 33_333L,
                playbackSpeed = 2f,
                estimatedDecodeNs = 5_000_000L
            )
        )
        assertEquals(
            0L,
            VideoPlaybackPerformancePolicy.decodeStartDelayNs(
                remainingMediaUs = -1L,
                playbackSpeed = 1f,
                estimatedDecodeNs = 5_000_000L
            )
        )
        assertEquals(1, VideoPlaybackPerformancePolicy.frameAtPosition(16_667L, 60, 600))
        assertEquals(599, VideoPlaybackPerformancePolicy.frameAtPosition(Long.MAX_VALUE / 60L, 60, 600))
    }

    @Test
    fun `decode ahead is held until exact presentation deadline`() {
        assertEquals(
            5_000_000L,
            VideoPlaybackPerformancePolicy.presentationDelayNs(
                frameIndex = 1,
                currentPositionUs = 28_333L,
                fps = 30,
                playbackSpeed = 1f
            )
        )
        assertEquals(
            2_500_000L,
            VideoPlaybackPerformancePolicy.presentationDelayNs(
                frameIndex = 1,
                currentPositionUs = 28_333L,
                fps = 30,
                playbackSpeed = 2f
            )
        )
        assertEquals(
            0L,
            VideoPlaybackPerformancePolicy.presentationDelayNs(
                frameIndex = 1,
                currentPositionUs = 40_000L,
                fps = 30,
                playbackSpeed = 1f
            )
        )
        assertEquals(
            0L,
            VideoPlaybackPerformancePolicy.presentationDelayNs(
                frameIndex = 0,
                currentPositionUs = 0L,
                fps = 30,
                playbackSpeed = 1f
            )
        )
    }

    @Test
    fun `raw16 playback reduction preserves Bayer phase and reference sample positions`() {
        val plan = VideoPlaybackPerformancePolicy.bayerReductionPlan(
            sourceWidth = 8,
            sourceHeight = 6,
            targetLongEdge = 4
        )

        assertEquals(4, plan.reducedWidth)
        assertEquals(2, plan.reducedHeight)
        assertTrue(
            plan.sourceSampleIndices.contentEquals(
                intArrayOf(
                    0, 1, 4, 5,
                    8, 9, 12, 13
                )
            )
        )
    }

    @Test
    fun `private master memory admission is bounded and overflow safe`() {
        val admitted = VideoPlaybackPerformancePolicy.playbackMemoryAdmission(
            storedFrameBytes = 24L * 1024 * 1024,
            decodedFrameBytes = 24L * 1024 * 1024,
            targetLongEdge = 1440,
            maxMemoryBytes = 512L * 1024 * 1024
        )
        assertTrue(admitted.admitted)

        val rejected = VideoPlaybackPerformancePolicy.playbackMemoryAdmission(
            storedFrameBytes = Long.MAX_VALUE,
            decodedFrameBytes = Long.MAX_VALUE,
            targetLongEdge = Int.MAX_VALUE,
            maxMemoryBytes = 512L * 1024 * 1024
        )
        assertTrue(!rejected.admitted)
    }

    @Test
    fun `autoplay requires both resumed lifecycle and visible playback UI`() {
        assertTrue(VideoPlaybackPerformancePolicy.shouldAutoplay(true, true))
        assertTrue(!VideoPlaybackPerformancePolicy.shouldAutoplay(false, true))
        assertTrue(!VideoPlaybackPerformancePolicy.shouldAutoplay(true, false))
        assertTrue(!VideoPlaybackPerformancePolicy.shouldAutoplay(false, false))
    }

    @Test
    fun `raw playback uses an interactive proxy without reducing conversion quality`() {
        assertEquals(
            640,
            VideoPlaybackPerformancePolicy.privateMasterDecodeLongEdge(
                viewportLongEdge = 1440,
                rawMaster = true,
                strictHighEnd = true
            )
        )
        assertEquals(
            540,
            VideoPlaybackPerformancePolicy.privateMasterDecodeLongEdge(
                viewportLongEdge = 1440,
                rawMaster = true,
                strictHighEnd = false
            )
        )
        assertEquals(
            1440,
            VideoPlaybackPerformancePolicy.privateMasterDecodeLongEdge(
                viewportLongEdge = 2800,
                rawMaster = false,
                strictHighEnd = true
            )
        )
    }

    @Test
    fun `player source keeps safe codec selection bounded buffers and allocation stable master playback`() {
        val activity = File(
            "src/main/java/com/neoproc/neoproccamx/ui/MediaPreviewActivity.kt"
        ).readText()
        val privatePlayer = File(
            "src/main/java/com/neoproc/neoproccamx/ui/PrivateMasterPlaybackController.kt"
        ).readText()

        assertTrue(!activity.contains("forceEnableMediaCodecAsynchronousQueueing()"))
        assertTrue(activity.contains("playWhenReady = false"))
        assertTrue(activity.contains("player.playWhenReady = shouldAutoplayCurrentMedia()"))
        assertTrue(activity.contains("if (initialAutoplayPending)"))
        assertFalse(activity.contains("restoreSeekPending"))
        assertTrue(activity.contains("initialPlayWhenReady = shouldAutoplayCurrentMedia()"))
        assertTrue(activity.contains("if (shouldAutoplayCurrentMedia()) controller.play()"))
        assertTrue(
            activity.indexOf("renderCurrent(animate = false)") <
                activity.indexOf("buildMediaQueue(initialEntry)")
        )
        assertTrue(activity.contains("setBufferDurationsMsForLocalPlayback("))
        assertTrue(activity.contains("setPrioritizeTimeOverSizeThresholdsForLocalPlayback(true)"))
        assertTrue(activity.contains("startPlaybackUiUpdatesIfNeeded()"))
        assertTrue(!activity.contains("postDelayed(this, 250L)"))
        assertTrue(privatePlayer.contains("TimeUnit.NANOSECONDS"))
        assertTrue(privatePlayer.contains("bitmap.prepareToDraw()"))
        assertTrue(privatePlayer.contains("estimateFixedFrameCount"))
        assertTrue(privatePlayer.contains("readRealtimeLinear16Frame"))
        assertTrue(privatePlayer.contains("readerRef.getAndSet(null)?.let(::closeReader)"))
        assertTrue(privatePlayer.contains("runCatching { activeReader.cancelIo() }"))
        assertTrue(privatePlayer.contains("retiringReader.getAndSet(null)?.let(::closeReader)"))
        assertTrue(privatePlayer.contains("executor.shutdownNow()"))
        assertFalse(privatePlayer.contains("executor.execute { closeReader(activeReader) }"))
        assertTrue(privatePlayer.contains("readerRef.compareAndSet(ownedReader, null)"))
        assertTrue(privatePlayer.contains("workspace = developWorkspace"))
        assertTrue(privatePlayer.contains("Raw12ThumbnailRenderer.renderPlayback("))
        assertTrue(privatePlayer.contains("presentationDelayNs("))
        assertTrue(privatePlayer.contains("schedulePresentation("))
        assertTrue(privatePlayer.contains("evidenceSession.recordPresentation("))
        assertFalse(privatePlayer.contains("prepareRealtimePlayback"))
        assertTrue(activity.contains("EncodedPlaybackEvidenceBridge("))
    }
}
