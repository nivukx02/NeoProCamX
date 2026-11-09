package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictNdrPreviewOutputContractTest {

    @Test
    fun `strict start result cannot disguise a failed output as success`() {
        val success = PersistentPreviewOutputRouter.StartResult.success()
        val failure = PersistentPreviewOutputRouter.StartResult.failure(
            PersistentPreviewOutputRouter.StartFailure.NON_TEN_BIT_CONFIG,
            "driver selected RGBA8888"
        )

        assertTrue(success.started)
        assertNull(success.failure)
        assertFalse(failure.started)
        assertEquals(
            PersistentPreviewOutputRouter.StartFailure.NON_TEN_BIT_CONFIG,
            failure.failure
        )
    }

    @Test
    fun `armed project NDR holds output until exact epoch frame is ready`() {
        assertEquals(
            NdrVisiblePreviewSource.HOLD_LAST_VALIDATED,
            NdrVisiblePreviewSourcePolicy.resolve(
                projectOwnedRequested = true,
                routeCommitted = false,
                projectOwnedFrameReady = false,
                latchedEpoch = -1L,
                currentEpoch = 8L
            )
        )
        assertEquals(
            NdrVisiblePreviewSource.HOLD_LAST_VALIDATED,
            NdrVisiblePreviewSourcePolicy.resolve(
                projectOwnedRequested = true,
                routeCommitted = true,
                projectOwnedFrameReady = true,
                latchedEpoch = 7L,
                currentEpoch = 8L
            )
        )
        assertEquals(
            NdrVisiblePreviewSource.PROJECT_OWNED,
            NdrVisiblePreviewSourcePolicy.resolve(
                projectOwnedRequested = true,
                routeCommitted = true,
                projectOwnedFrameReady = true,
                latchedEpoch = 8L,
                currentEpoch = 8L
            )
        )
    }

    @Test
    fun `camera monitor remains available only when project NDR is not requested`() {
        assertEquals(
            NdrVisiblePreviewSource.CAMERA_MONITOR,
            NdrVisiblePreviewSourcePolicy.resolve(
                projectOwnedRequested = false,
                routeCommitted = false,
                projectOwnedFrameReady = false,
                latchedEpoch = -1L,
                currentEpoch = 0L
            )
        )
    }

    @Test
    fun `router exposes no untagged or eight bit window fallback`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/PersistentPreviewOutputRouter.kt"
        ).readText()
        val windowCreation = source
            .substringAfter("private fun createPqWindowSurface(")
            .substringBefore("/** EGL channel requests are minima")

        assertTrue(windowCreation.contains("EGL_GL_COLORSPACE_BT2020_PQ_EXT"))
        assertFalse(windowCreation.contains("intArrayOf(EGL14.EGL_NONE)"))
        assertTrue(source.contains("fun startWithResult("))
        assertTrue(source.contains("StartFailure.TEN_BIT_CONFIG_UNAVAILABLE"))
        assertTrue(source.contains("StartFailure.NON_TEN_BIT_CONFIG"))
    }
}
