package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.rendering.PersistentPreviewOutputRouter
import com.neoproc.neoproccamx.rendering.PreviewRuntimeComponent
import com.neoproc.neoproccamx.rendering.PreviewRuntimeFailure
import com.neoproc.neoproccamx.rendering.PreviewRuntimeOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictPreviewAdmissionPolicyTest {
    @Test
    fun `successful result presented as rejection is normalized and remains fail closed`() {
        val rejection = StrictPreviewAdmissionPolicy.rejection(
            PersistentPreviewOutputRouter.StartResult.success()
        )
        assertFalse(rejection.result.started)
        assertEquals(PersistentPreviewOutputRouter.StartFailure.UNKNOWN, rejection.result.failure)
        assertTrue(rejection.warning.contains("8-bit fallback was blocked"))
    }

    @Test
    fun `runtime EGL evidence remains typed and machine readable`() {
        val result = StrictPreviewAdmissionPolicy.runtimeFailure(
            PreviewRuntimeFailure(
                component = PreviewRuntimeComponent.HDR_CHILD,
                operation = PreviewRuntimeOperation.EGL_SWAP_BUFFERS,
                eglError = 0x300D,
                detail = "surface lost"
            )
        )
        assertEquals(PersistentPreviewOutputRouter.StartFailure.RUNTIME_EGL_FAILURE, result.failure)
        assertTrue(result.detail.contains("HDR_CHILD/EGL_SWAP_BUFFERS"))
        assertTrue(result.detail.contains("eglError=0x300d"))
    }

    @Test
    fun `pending and failed preview proof block recording`() {
        for (mode in listOf(AppMode.VIDEO, AppMode.PRO)) {
            assertFalse(
                StrictPreviewAdmissionPolicy.recordingAllowed(
                    mode,
                    StrictPreviewAdmissionState.PENDING
                )
            )
            assertFalse(
                StrictPreviewAdmissionPolicy.recordingAllowed(
                    mode,
                    StrictPreviewAdmissionState.FAILED
                )
            )
        }
    }

    @Test
    fun `only proven persistent or native precision admits recording`() {
        for (mode in listOf(AppMode.VIDEO, AppMode.PRO)) {
            assertTrue(
                StrictPreviewAdmissionPolicy.recordingAllowed(
                    mode,
                    StrictPreviewAdmissionState.ACTIVE_PERSISTENT
                )
            )
            assertTrue(
                StrictPreviewAdmissionPolicy.recordingAllowed(
                    mode,
                    StrictPreviewAdmissionState.ACTIVE_NATIVE
                )
            )
        }
    }

    @Test
    fun `pending failed and unhealthy persistent routes retain frame ingress`() {
        for (mode in listOf(AppMode.VIDEO, AppMode.PRO)) {
            for (state in listOf(
                StrictPreviewAdmissionState.PENDING,
                StrictPreviewAdmissionState.FAILED
            )) {
                assertEquals(
                    StrictPreviewProfileFinalizationGate.RETAIN_FRAME_INGRESS,
                    StrictPreviewAdmissionPolicy.profileFinalizationGate(
                        appMode = mode,
                        state = state,
                        persistentRouteHealthy = false
                    )
                )
            }
            assertEquals(
                StrictPreviewProfileFinalizationGate.RETAIN_FRAME_INGRESS,
                StrictPreviewAdmissionPolicy.profileFinalizationGate(
                    appMode = mode,
                    state = StrictPreviewAdmissionState.ACTIVE_PERSISTENT,
                    persistentRouteHealthy = false
                )
            )
        }
    }

    @Test
    fun `only healthy persistent route bypasses per packet native precision proof`() {
        assertEquals(
            StrictPreviewProfileFinalizationGate.ALLOW_PROVEN_ROUTE,
            StrictPreviewAdmissionPolicy.profileFinalizationGate(
                appMode = AppMode.VIDEO,
                state = StrictPreviewAdmissionState.ACTIVE_PERSISTENT,
                persistentRouteHealthy = true
            )
        )
        assertEquals(
            StrictPreviewProfileFinalizationGate.REQUIRE_NATIVE_PRECISION_PROOF,
            StrictPreviewAdmissionPolicy.profileFinalizationGate(
                appMode = AppMode.VIDEO,
                state = StrictPreviewAdmissionState.ACTIVE_NATIVE,
                persistentRouteHealthy = false
            )
        )
    }
}
