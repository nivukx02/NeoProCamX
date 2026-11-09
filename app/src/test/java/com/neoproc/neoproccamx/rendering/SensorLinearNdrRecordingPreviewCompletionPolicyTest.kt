package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrRecordingPreviewCompletionPolicyTest {
    @Test
    fun `current compositor rejection is terminal`() {
        assertEquals(
            SensorLinearNdrRecordingPreviewCompletionPolicy.Decision.FAIL_CURRENT_PRESENTATION,
            resolve(presented = false)
        )
    }

    @Test
    fun `render generation advanced after admission retires stale without false failure`() {
        assertEquals(
            SensorLinearNdrRecordingPreviewCompletionPolicy.Decision.RETIRE_STALE,
            resolve(presented = false, renderProfileCurrent = false)
        )
    }

    @Test
    fun `camera session advanced after admission retires stale without lineage`() {
        assertEquals(
            SensorLinearNdrRecordingPreviewCompletionPolicy.Decision.RETIRE_STALE,
            resolve(presented = true, cameraSessionCurrent = false)
        )
    }

    @Test
    fun `successful current presentation emits exact preview lineage`() {
        assertEquals(
            SensorLinearNdrRecordingPreviewCompletionPolicy.Decision.EMIT_CURRENT_LINEAGE,
            resolve(presented = true)
        )
    }

    @Test
    fun `task rejected before listener admission is stale even if sampled identities are current`() {
        assertEquals(
            SensorLinearNdrRecordingPreviewCompletionPolicy.Decision.RETIRE_STALE,
            resolve(presentationAttempted = false, presented = false)
        )
    }

    @Test
    fun `ordinary stale presentation failure does not poison the clip`() {
        val failure = IllegalStateException("stale listener")
        assertNull(
            SensorLinearNdrRecordingPreviewCompletionPolicy.selectTerminalFailure(
                decision = SensorLinearNdrRecordingPreviewCompletionPolicy.Decision.RETIRE_STALE,
                presentationFailure = failure,
                retirementFailure = null,
                currentRejectionFailure = { AssertionError("must not create fallback") }
            )
        )
    }

    @Test
    fun `retirement failure is terminal and preserves presentation diagnosis`() {
        val presentation = IllegalStateException("listener")
        val retirement = IllegalStateException("retirement")
        val selected = SensorLinearNdrRecordingPreviewCompletionPolicy.selectTerminalFailure(
            decision = SensorLinearNdrRecordingPreviewCompletionPolicy.Decision.RETIRE_STALE,
            presentationFailure = presentation,
            retirementFailure = retirement,
            currentRejectionFailure = { AssertionError("must not create fallback") }
        )

        assertSame(retirement, selected)
        assertTrue(retirement.suppressed.contains(presentation))
    }

    @Test
    fun `virtual machine error is never swallowed by stale retirement`() {
        val fatal = OutOfMemoryError("fatal")
        assertSame(
            fatal,
            SensorLinearNdrRecordingPreviewCompletionPolicy.selectTerminalFailure(
                decision = SensorLinearNdrRecordingPreviewCompletionPolicy.Decision.RETIRE_STALE,
                presentationFailure = fatal,
                retirementFailure = null,
                currentRejectionFailure = { AssertionError("must not create fallback") }
            )
        )
    }

    @Test
    fun `thread death is never swallowed by stale retirement`() {
        val fatal = ThreadDeath()
        assertSame(
            fatal,
            SensorLinearNdrRecordingPreviewCompletionPolicy.selectTerminalFailure(
                decision = SensorLinearNdrRecordingPreviewCompletionPolicy.Decision.RETIRE_STALE,
                presentationFailure = fatal,
                retirementFailure = null,
                currentRejectionFailure = { AssertionError("must not create fallback") }
            )
        )
    }

    private fun resolve(
        presentationAttempted: Boolean = true,
        presented: Boolean,
        cameraSessionCurrent: Boolean = true,
        renderProfileCurrent: Boolean = true
    ) = SensorLinearNdrRecordingPreviewCompletionPolicy.resolve(
        presentationAttempted = presentationAttempted,
        presented = presented,
        cameraSessionCurrentAfterPresentation = cameraSessionCurrent,
        renderProfileCurrentAfterPresentation = renderProfileCurrent
    )
}
