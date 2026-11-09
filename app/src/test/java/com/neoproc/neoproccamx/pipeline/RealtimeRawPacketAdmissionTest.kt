package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RealtimeRawPacketAdmissionTest {

    @Test
    fun bufferExhaustionFailsWithoutInvokingAnEncoderOrHeapFallback() {
        var encoderInvoked = false

        expectUnavailable {
            RealtimeRawPacketAdmission.acquireAndEncode<Any, Any>(
                acquireBuffer = { null },
                encodeInto = {
                    encoderInvoked = true
                    Any()
                },
                recycleBuffer = { fail("No buffer was acquired") }
            )
        }

        assertFalse(encoderInvoked)
    }

    @Test
    fun nativeEncoderFailureRecyclesTheBorrowedBufferExactlyOnce() {
        val buffer = Any()
        var recycleCount = 0

        expectUnavailable {
            RealtimeRawPacketAdmission.acquireAndEncode<Any, Any>(
                acquireBuffer = { buffer },
                encodeInto = { null },
                recycleBuffer = {
                    assertSame(buffer, it)
                    recycleCount++
                }
            )
        }

        assertEquals(1, recycleCount)
    }

    @Test
    fun successfulEncodeTransfersBufferOwnershipWithoutEarlyRecycle() {
        val buffer = Any()
        val encoded = Any()
        var recycled = false

        val prepared = RealtimeRawPacketAdmission.acquireAndEncode(
            acquireBuffer = { buffer },
            encodeInto = { encoded },
            recycleBuffer = { recycled = true }
        )

        assertSame(buffer, prepared.buffer)
        assertSame(encoded, prepared.result)
        assertFalse(recycled)
    }

    @Test
    fun realtimeManagerHasNoFullFrameHeapEncoderFallback() {
        val source = java.io.File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        ).readText()

        assertTrue(source.contains("RealtimeRawPacketAdmission.acquireAndEncode"))
        assertFalse(source.contains("NprawFrameCodec.encodeRealtimeLinear16Frame("))
    }

    private fun expectUnavailable(block: () -> Unit) {
        try {
            block()
            fail("Expected realtime RAW packet admission to fail")
        } catch (_: RealtimeRawPacketUnavailableException) {
            // Expected hard failure: there is deliberately no heap allocation fallback.
        }
    }
}
