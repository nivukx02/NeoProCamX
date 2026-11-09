package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionHdrMetadataLifecycleContractTest {
    private val source = locateRecordingManager().readText()

    @Test
    fun everyAsyncVideoDrainOwnsThePreparedSessionColorSnapshot() {
        assertEquals(
            3,
            Regex(
                "launchVideoEncoderDrain\\("
            ).findAll(source).count()
        )
        assertEquals(
            3,
            Regex(
                "addVideoTrack\\(\\s*drainSession\\s*="
            ).findAll(source).count()
        )
        assertTrue(source.contains("codec = videoCodecForDrain"))
        assertTrue(source.contains("colorSnapshot = colorSnapshotForDrain"))
    }

    @Test
    fun finalSuccessfulFormatAndOutputColorArePublishedAsOneSessionSnapshot() {
        val retainBlock = source
            .substringAfter("activeSessionHdrMetadata.retain(")
            .substringBefore(")")

        assertTrue(retainBlock.contains("formatResult = videoFormatResult"))
        assertTrue(retainBlock.contains("colorEncoding = videoPlan.colorEncoding"))
    }

    @Test
    fun teardownClearsOnlyItsSnapshotAfterContainerRetirement() {
        val releaseBody = source
            .substringAfter("private fun releaseInternal(")
            .substringBefore("fun destroy()")
        val captureIndex = releaseBody.indexOf(
            "val colorSnapshotToRetire = activeSessionHdrMetadata.currentSnapshotOrNull()"
        )
        val abortIndex = releaseBody.indexOf("movSessionController?.abort()")
        val nullControllerIndex = releaseBody.indexOf("movSessionController = null")
        val compareClearIndex = releaseBody.indexOf(
            "activeSessionHdrMetadata.clear(colorSnapshotToRetire)"
        )

        assertTrue(captureIndex >= 0)
        assertTrue(abortIndex > captureIndex)
        assertTrue(nullControllerIndex > abortIndex)
        assertTrue(compareClearIndex > nullControllerIndex)
    }

    @Test
    fun staleFailureTeardownCannotClearReplacementSessionMetadata() {
        val releaseBody = source
            .substringAfter("private fun releaseInternal(")
            .substringBefore("fun destroy()")
        val cleanupBody = source
            .substringAfter("private fun scheduleMuxerTerminalFailureCleanup(")
            .substringBefore("private fun estimateEncoderQueueLatencyMs")

        assertTrue(releaseBody.contains("drainSessionOwnership.retire(expectedDrainSession) ?: return"))
        assertTrue(releaseBody.contains("activeSessionHdrMetadata.clear(colorSnapshotToRetire)"))
        assertTrue(cleanupBody.contains("!drainSessionOwnership.isCurrent(expectedDrainSession)"))
        assertTrue(cleanupBody.contains("releaseInternal(expectedDrainSession = expectedDrainSession)"))
    }

    @Test
    fun teardownJoinIsNeverInvokedFromInsideAnEncoderDrainJob() {
        val videoDrainBody = source
            .substringAfter("private fun drainVideoEncoder(")
            .substringBefore("private fun drainBlockModelVideoEncoder(")
        val blockDrainBody = source
            .substringAfter("private fun drainBlockModelVideoEncoder(")
            .substringBefore("// AUDIO")
        val audioDrainBody = source
            .substringAfter("private fun recordAndEncodeAudio(")
            .substringBefore("// MUXER START")
        val cleanupBody = source
            .substringAfter("private fun scheduleMuxerTerminalFailureCleanup(")
            .substringBefore("private fun estimateEncoderQueueLatencyMs")
        val releaseBody = source
            .substringAfter("private fun releaseInternal(")
            .substringBefore("fun destroy()")

        assertTrue(!videoDrainBody.contains("releaseInternal("))
        assertTrue(!blockDrainBody.contains("releaseInternal("))
        assertTrue(!audioDrainBody.contains("releaseInternal("))
        assertTrue(cleanupBody.contains("scope.launch {"))
        assertTrue(releaseBody.contains("awaitTermination(DRAIN_SESSION_JOIN_TIMEOUT_MS)"))
        assertTrue(!releaseBody.contains("runBlocking {"))
        assertTrue(!releaseBody.contains(".join()"))
    }

    private fun locateRecordingManager(): File = listOf(
        File("src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt"),
        File("app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt")
    ).firstOrNull(File::isFile)
        ?: error("Could not locate RecordingManager.kt")
}
