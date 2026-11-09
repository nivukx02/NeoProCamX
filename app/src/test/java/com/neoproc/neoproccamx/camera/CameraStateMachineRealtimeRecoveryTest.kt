package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStateMachineRealtimeRecoveryTest {

    @Test
    fun sessionResetContractIsNonBlockingAndTargetsTwoHundredMilliseconds() {
        val contract = CameraSessionResetContract.resolve(
            reason = "swapchain invalid",
            nowElapsedMs = 1_000L
        )

        assertEquals(200L, contract.targetRecoveryWindowMs)
        assertTrue(contract.nonBlocking)
        assertTrue(contract.steps.contains(CameraSessionResetStep.FLUSH_NATIVE_BUFFERS))
        assertTrue(contract.steps.contains(CameraSessionResetStep.REBUILD_CAPTURE_SESSION))
        assertFalse(contract.steps.contains(CameraSessionResetStep.BLOCK_UI_THREAD))
        assertTrue(contract.signature.contains("target=200ms"))
    }

    @Test
    fun stateMachineExposesSelfHealingSessionResetContract() {
        val fsm = CameraStateMachine()
        val contract = fsm.resolveSessionResetContract("camera device error")

        assertTrue(contract.nonBlocking)
        assertTrue(contract.signature.contains("isolated-session-reset"))
    }
}
