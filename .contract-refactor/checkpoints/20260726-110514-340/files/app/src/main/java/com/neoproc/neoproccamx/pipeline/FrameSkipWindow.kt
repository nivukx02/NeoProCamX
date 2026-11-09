package com.neoproc.neoproccamx.pipeline

/**
 * Manages frame skipping decisions based on processing load and target FPS.
 * Helps maintain smooth playback by intelligently dropping frames when needed.
 */
class FrameSkipWindow(
    private val targetFps: Int = 25,
    private val windowSizeMs: Long = 1000
) {
    private val frameTimestamps = ArrayDeque<Long>()
    private val decisionSamples = ArrayDeque<Pair<Long, Boolean>>()
    private val lock = Any()
    private var lastProcessedTimestamp = 0L

    /**
     * Determines if the current frame should be processed or skipped
     */
    fun shouldProcessFrame(currentTimestamp: Long): Boolean {
        synchronized(lock) {
            val cutoffTime = currentTimestamp - windowSizeMs
            while (frameTimestamps.isNotEmpty() && frameTimestamps.first() < cutoffTime) {
                frameTimestamps.removeFirst()
            }
            while (decisionSamples.isNotEmpty() && decisionSamples.first().first < cutoffTime) {
                decisionSamples.removeFirst()
            }

            val currentFps = if (frameTimestamps.size > 1) {
                val timeSpan = currentTimestamp - frameTimestamps.first()
                if (timeSpan > 0) {
                    (frameTimestamps.size * 1000L) / timeSpan
                } else {
                    0L
                }
            } else {
                0L
            }

            if (currentFps >= targetFps) {
                decisionSamples.addLast(currentTimestamp to false)
                return false
            }

            frameTimestamps.addLast(currentTimestamp)
             decisionSamples.addLast(currentTimestamp to true)
            lastProcessedTimestamp = currentTimestamp
            return true
        }
    }

    /**
     * Gets the current processing rate
     */
    fun getCurrentFps(): Int {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val cutoffTime = now - windowSizeMs
            while (frameTimestamps.isNotEmpty() && frameTimestamps.first() < cutoffTime) {
                frameTimestamps.removeFirst()
            }
            while (decisionSamples.isNotEmpty() && decisionSamples.first().first < cutoffTime) {
                decisionSamples.removeFirst()
            }
            val recentFrames = frameTimestamps.size
            return if (recentFrames > 0) {
                (recentFrames * 1000L / windowSizeMs).toInt()
            } else {
                0
            }
        }
    }

    internal fun getDropSummary(nowMs: Long = System.currentTimeMillis()): FrameDropSummary {
        synchronized(lock) {
            val cutoffTime = nowMs - windowSizeMs
            while (frameTimestamps.isNotEmpty() && frameTimestamps.first() < cutoffTime) {
                frameTimestamps.removeFirst()
            }
            while (decisionSamples.isNotEmpty() && decisionSamples.first().first < cutoffTime) {
                decisionSamples.removeFirst()
            }
            var processed = 0
            var skipped = 0
            for ((_, decision) in decisionSamples) {
                if (decision) processed++ else skipped++
            }
            return FrameDropSummary(
                currentFps = if (frameTimestamps.isNotEmpty()) {
                    (frameTimestamps.size * 1000L / windowSizeMs).toInt()
                } else {
                    0
                },
                processedFrames = processed,
                skippedFrames = skipped
            )
        }
    }

    /**
     * Resets the frame skip window
     */
    fun reset() {
        synchronized(lock) {
            frameTimestamps.clear()
            decisionSamples.clear()
            lastProcessedTimestamp = 0L
        }
    }
}
