package com.neoproc.neoproccamx.camera

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLifecycleStateBridgeTest {

    @Test
    fun closeThenReopenKeepsStateMachineAndPublicFlowInSync() {
        val fsm = CameraStateMachine()
        val publicState = MutableStateFlow<CameraState>(CameraState.Closed)

        CameraLifecycleStateBridge.apply(fsm, publicState, CameraState.Opening, "open")
        CameraLifecycleStateBridge.apply(fsm, publicState, CameraState.Open, "device-opened")
        CameraLifecycleStateBridge.apply(fsm, publicState, CameraState.Streaming, "preview")
        CameraLifecycleStateBridge.apply(fsm, publicState, CameraState.Closing, "background")
        CameraLifecycleStateBridge.apply(fsm, publicState, CameraState.Closed, "background-closed")
        CameraLifecycleStateBridge.apply(fsm, publicState, CameraState.Opening, "foreground")
        CameraLifecycleStateBridge.apply(fsm, publicState, CameraState.Open, "device-opened")
        CameraLifecycleStateBridge.apply(fsm, publicState, CameraState.Streaming, "preview")

        assertTrue(fsm.current is CameraState.Streaming)
        assertEquals(CameraState.Streaming, publicState.value)
    }

    @Test
    fun repeatedStreamingTransitionRepairsStalePublicFlow() {
        val fsm = CameraStateMachine()
        val publicState = MutableStateFlow<CameraState>(CameraState.Open)

        CameraLifecycleStateBridge.apply(fsm, publicState, CameraState.Opening, "open")
        CameraLifecycleStateBridge.apply(fsm, publicState, CameraState.Open, "device-opened")
        CameraLifecycleStateBridge.apply(fsm, publicState, CameraState.Streaming, "preview")
        publicState.value = CameraState.Open

        CameraLifecycleStateBridge.apply(fsm, publicState, CameraState.Streaming, "preview-repeat")

        assertTrue(fsm.current is CameraState.Streaming)
        assertEquals(CameraState.Streaming, publicState.value)
    }

    @Test
    fun duplicateCloseFromClosedStateStaysClosed() {
        val fsm = CameraStateMachine()
        val publicState = MutableStateFlow<CameraState>(CameraState.Closed)

        CameraLifecycleStateBridge.apply(fsm, publicState, CameraState.Closing, "duplicate-close")

        assertTrue(fsm.current is CameraState.Closed)
        assertEquals(CameraState.Closed, publicState.value)
    }
}
