package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStateMachineTest {

    @Test
    fun supportsConfiguringPhotoCaptureAndReconfiguringTransitions() {
        val fsm = CameraStateMachine()
        assertTrue(fsm.toOpening("open"))
        assertTrue(fsm.toConfiguring("configure"))
        assertTrue(fsm.toOpen("configured"))
        assertTrue(fsm.toStreaming("stream"))
        assertTrue(fsm.toPhotoCapture("capture"))
        assertTrue(fsm.toReconfiguring("refresh"))
        assertTrue(fsm.toStreaming("resume"))
        assertTrue(fsm.toRecording("record"))
        assertTrue(fsm.toStreaming("stop-record"))
    }

    @Test
    fun rejectsIllegalTransitions() {
        val fsm = CameraStateMachine()
        assertFalse(fsm.toRecording("illegal-direct-recording"))
        assertTrue(fsm.isClosed)
    }

    @Test
    fun timeoutTriggersErrorAndAutoRecovery() {
        val fsm = CameraStateMachine()
        assertTrue(fsm.toOpening("open"))

        val timedOut = fsm.checkTimeoutAndRecover(Long.MAX_VALUE)
        assertTrue(timedOut)
        assertTrue(
            fsm.current is CameraState.Opening ||
                fsm.current is CameraState.Closed ||
                fsm.current is CameraState.Closing
        )
        assertFalse(fsm.current is CameraState.Error)
    }
}
