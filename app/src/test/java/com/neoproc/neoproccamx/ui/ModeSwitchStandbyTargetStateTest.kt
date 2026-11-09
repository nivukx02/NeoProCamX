package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraStreamSize
import org.junit.Assert.assertEquals
import org.junit.Test

class ModeSwitchStandbyTargetStateTest {
    @Test
    fun `persisted recording size seeds standby before camera characteristics are ready`() {
        val state = ModeSwitchStandbyTargetState(CameraStreamSize(3072, 4096))

        assertEquals(CameraStreamSize(4096, 3072), state.activeSize())
    }

    @Test
    fun `exact p010 plan survives reapplying the same persisted selection`() {
        val state = ModeSwitchStandbyTargetState(CameraStreamSize(4000, 3000))
        state.promoteExactP010Size(
            requestedSizeAtResolve = CameraStreamSize(4000, 3000),
            exactSize = CameraStreamSize(4096, 3072)
        )

        assertEquals(
            CameraStreamSize(4096, 3072),
            state.updateRequestedSize(CameraStreamSize(4000, 3000))
        )
    }

    @Test
    fun `new recording selection invalidates the old exact p010 plan`() {
        val state = ModeSwitchStandbyTargetState(CameraStreamSize(4096, 3072))
        state.promoteExactP010Size(
            requestedSizeAtResolve = CameraStreamSize(4096, 3072),
            exactSize = CameraStreamSize(4096, 3072)
        )

        assertEquals(
            CameraStreamSize(3840, 2160),
            state.updateRequestedSize(CameraStreamSize(3840, 2160))
        )
    }

    @Test
    fun `stale plan cannot replace standby for a newer user selection`() {
        val state = ModeSwitchStandbyTargetState(CameraStreamSize(4096, 3072))
        state.updateRequestedSize(CameraStreamSize(3840, 2160))

        assertEquals(
            CameraStreamSize(3840, 2160),
            state.promoteExactP010Size(
                requestedSizeAtResolve = CameraStreamSize(4096, 3072),
                exactSize = CameraStreamSize(4096, 3072)
            )
        )
    }
}
