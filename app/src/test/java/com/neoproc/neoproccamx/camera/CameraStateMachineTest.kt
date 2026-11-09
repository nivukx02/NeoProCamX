package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
    fun replacementSessionCanRestoreRecordingFromOpen() {
        val fsm = CameraStateMachine()
        assertTrue(fsm.toOpening("recover-recording-device"))
        assertTrue(fsm.toOpen("replacement-device-opened"))
        assertTrue(fsm.toRecording("replacement-repeating-request-active"))
        assertTrue(fsm.isRecording)
    }

    @Test
    fun activeCameraLossCanEnterOpeningWithoutIllegalOrForcedTransition() {
        val sourceStates = listOf<(CameraStateMachine) -> Unit>(
            { fsm ->
                assertTrue(fsm.toOpening("open"))
                assertTrue(fsm.toConfiguring("configure"))
            },
            { fsm ->
                assertTrue(fsm.toOpening("open"))
                assertTrue(fsm.toOpen("opened"))
            },
            { fsm ->
                assertTrue(fsm.toOpening("open"))
                assertTrue(fsm.toOpen("opened"))
                assertTrue(fsm.toStreaming("streaming"))
            },
            { fsm ->
                assertTrue(fsm.toOpening("open"))
                assertTrue(fsm.toOpen("opened"))
                assertTrue(fsm.toStreaming("streaming"))
                assertTrue(fsm.toRecording("recording"))
            },
            { fsm ->
                assertTrue(fsm.toOpening("open"))
                assertTrue(fsm.toOpen("opened"))
                assertTrue(fsm.toStreaming("streaming"))
                assertTrue(fsm.toPhotoCapture("photo"))
            },
            { fsm ->
                assertTrue(fsm.toOpening("open"))
                assertTrue(fsm.toOpen("opened"))
                assertTrue(fsm.toStreaming("streaming"))
                assertTrue(fsm.toReconfiguring("reconfigure"))
            }
        )

        sourceStates.forEach { reachSource ->
            val fsm = CameraStateMachine()
            var legalTransitions = 0
            var illegalTransitions = 0
            fsm.setTransitionListener { _, _ -> legalTransitions += 1 }
            fsm.setIllegalTransitionListener { _, _ -> illegalTransitions += 1 }
            reachSource(fsm)
            val transitionsBeforeRecovery = legalTransitions

            assertTrue(fsm.toOpening("camera-loss-reopen"))
            assertTrue(fsm.current is CameraState.Opening)
            assertEquals(transitionsBeforeRecovery + 1, legalTransitions)
            assertEquals(0, illegalTransitions)
        }
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
