package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeSwitchSessionTransactionTest {
    private fun state(
        cinemaMode: CinemaVideoMode,
        signature: String,
        hdrMode: String = "OFF",
        rawGraph: Boolean = false
    ) = ModeSwitchSessionState(
        cinemaMode = cinemaMode,
        pipelineSignature = signature,
        rawReaderGraphActive = rawGraph,
        hdrMode = hdrMode,
        logEnabled = false
    )

    @Test
    fun `intermediate mutations collapse into one final request`() {
        val transaction = ModeSwitchSessionTransaction()
        transaction.begin(state(CinemaVideoMode.PRO, "sdr"))
        transaction.requestRefresh()
        transaction.requestRefresh()

        val request = transaction.finish(
            state(CinemaVideoMode.RAW, "raw", rawGraph = true)
        )!!

        assertTrue(request.previous.pipelineSignature == "sdr")
        assertTrue(request.current.pipelineSignature == "raw")
        assertTrue(request.previous.rawReaderGraphActive != request.current.rawReaderGraphActive)
        assertNull(transaction.finish(state(CinemaVideoMode.RAW, "raw")))
    }

    @Test
    fun `same hdr transport is retained as a no-rebuild hint`() {
        val transaction = ModeSwitchSessionTransaction()
        transaction.begin(state(CinemaVideoMode.PRO, "ndr", hdrMode = "NDR10+"))
        transaction.requestRefresh()

        val request = transaction.finish(
            state(CinemaVideoMode.PRO, "vision", hdrMode = "NIVUKX Vision")
        )!!

        assertTrue(request.hdrToHdrSameTransport)
        assertFalse(request.previous.pipelineSignature == request.current.pipelineSignature)
    }

    @Test
    fun `abort discards stale baseline and refresh request`() {
        val transaction = ModeSwitchSessionTransaction()
        transaction.begin(state(CinemaVideoMode.PRO, "stale-sdr"))
        transaction.requestRefresh()

        transaction.abort()

        assertNull(transaction.finish(state(CinemaVideoMode.RAW, "raw", rawGraph = true)))
    }

    @Test
    fun `abort is idempotent and transaction is reusable`() {
        val transaction = ModeSwitchSessionTransaction()
        transaction.begin(state(CinemaVideoMode.PRO, "abandoned"))
        transaction.requestRefresh()
        transaction.abort()
        transaction.abort()

        transaction.begin(state(CinemaVideoMode.PRO, "fresh-sdr"))
        transaction.requestRefresh()
        val request = transaction.finish(state(CinemaVideoMode.LOG, "fresh-log"))

        assertTrue(request != null)
        assertTrue(request?.previous?.pipelineSignature == "fresh-sdr")
        assertTrue(request?.current?.pipelineSignature == "fresh-log")
    }
}
