package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10PackingOverlapPolicyTest {
    @Test
    fun losslessCompressionHasTwoBoundedLanesAndOtherCodecsKeepOne() {
        Npv10PacketCodec.entries.forEach { codec ->
            assertEquals(
                if (codec == Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2) 2 else 1,
                Npv10PackingOverlapPolicy.dispatchWorkers(codec)
            )
        }
    }

    @Test
    fun overlapNeverConsumesTheLastPendingFrameLease() {
        assertTrue(Npv10PackingOverlapPolicy.MAX_DISPATCH_WORKERS < Npv10InFlightBufferPolicy.MIN_PENDING_FRAMES)
        listOf(3264 to 2448, 4096 to 3072, 3840 to 2160).forEach { (width, height) ->
            val frames = Npv10InFlightBufferPolicy.pendingFrames(
                width, height, Npv10PacketCodec.CHUNKED_SPLIT_P010_10_LZ4_V2
            )
            assertTrue(frames > Npv10PackingOverlapPolicy.MAX_DISPATCH_WORKERS)
            assertTrue(frames <= Npv10InFlightBufferPolicy.MAX_PENDING_FRAMES)
        }
    }
}
