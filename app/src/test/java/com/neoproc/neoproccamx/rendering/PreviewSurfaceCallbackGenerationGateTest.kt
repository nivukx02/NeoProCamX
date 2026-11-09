package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSurfaceCallbackGenerationGateTest {

    @Test
    fun `posted destruction cannot invalidate a replacement surface`() {
        val gate = PreviewSurfaceCallbackGenerationGate<Any>()
        val original = Any()
        val replacement = Any()

        gate.publish(original)
        val postedDestruction = gate.publish(null)
        val replacementEvent = gate.publish(replacement)

        assertFalse(gate.isCurrent(postedDestruction))
        assertTrue(gate.isCurrent(replacementEvent))
        assertSame(replacement, gate.snapshot()?.surface)
    }

    @Test
    fun `posted ready callback is rejected after destruction`() {
        val gate = PreviewSurfaceCallbackGenerationGate<Any>()
        val postedReady = gate.publish(Any())
        val destruction = gate.publish(null)

        assertFalse(gate.isCurrent(postedReady))
        assertTrue(gate.isCurrent(destruction))
    }

    @Test
    fun `same surface object still receives a new generation`() {
        val gate = PreviewSurfaceCallbackGenerationGate<Any>()
        val surface = Any()
        val created = gate.publish(surface)
        val changed = gate.publish(surface)

        assertFalse(gate.isCurrent(created))
        assertTrue(gate.isCurrent(changed))
        assertTrue(changed.generation > created.generation)
    }
}
