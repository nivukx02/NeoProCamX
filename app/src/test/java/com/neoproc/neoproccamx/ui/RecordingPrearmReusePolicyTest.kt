package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuVideoCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPrearmReusePolicyTest {

    @Test
    fun `prepared surface is reusable only when complete recording signature matches`() {
        val prepared = signature()

        assertTrue(
            RecordingPrearmReusePolicy.shouldReusePreparedSurface(
                recordingStateReady = true,
                surfaceAvailable = true,
                preparedSignature = prepared,
                expectedSignature = prepared
            )
        )

        assertFalse(
            RecordingPrearmReusePolicy.shouldReusePreparedSurface(
                recordingStateReady = true,
                surfaceAvailable = true,
                preparedSignature = prepared,
                expectedSignature = signature(fps = 60)
            )
        )
    }

    @Test
    fun `prepared surface is not reusable when codec is not ready or surface is missing`() {
        val prepared = signature()

        assertFalse(
            RecordingPrearmReusePolicy.shouldReusePreparedSurface(
                recordingStateReady = false,
                surfaceAvailable = true,
                preparedSignature = prepared,
                expectedSignature = prepared
            )
        )
        assertFalse(
            RecordingPrearmReusePolicy.shouldReusePreparedSurface(
                recordingStateReady = true,
                surfaceAvailable = false,
                preparedSignature = prepared,
                expectedSignature = prepared
            )
        )
    }

    @Test
    fun `output label suffix is not part of prepared encoder surface compatibility`() {
        val prepared = signature()

        assertTrue(
            RecordingPrearmReusePolicy.shouldReusePreparedSurface(
                recordingStateReady = true,
                surfaceAvailable = true,
                preparedSignature = prepared,
                expectedSignature = prepared
            )
        )
    }

    private fun signature(fps: Int = 25): RecordingPrearmSignature =
        RecordingPrearmSignature(
            width = 3840,
            height = 2160,
            fps = fps,
            bitrate = 160_000_000,
            codec = GpuVideoCodec.HEVC,
            audioEnabled = false,
            tenBitEnabled = true,
            rawVideoEnabled = false,
            logEnabled = false,
            hdrMode = "OFF",
            colorSpace = "Rec.709",
            cinemaMode = CinemaVideoMode.PRO
        )
}
