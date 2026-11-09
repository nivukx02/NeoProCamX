package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class AICameraPipelineListenerTeardownTest {
    @Test
    fun `queued dispatch resolves listener only after teardown gate`() {
        val invocations = AtomicInteger(0)
        val holder = TeardownDetachableListener<Runnable>(
            Runnable { invocations.incrementAndGet() }
        )
        val queuedDispatch = { holder.dispatch(Runnable::run) }

        holder.detach()

        assertFalse(queuedDispatch())
        assertFalse(holder.isAttached())
        assertTrue(invocations.get() == 0)
    }

    @Test
    fun `listener may initiate reentrant teardown without a stale second invocation`() {
        val invocations = AtomicInteger(0)
        lateinit var holder: TeardownDetachableListener<Runnable>
        holder = TeardownDetachableListener(
            Runnable {
                invocations.incrementAndGet()
                holder.detach()
            }
        )

        assertTrue(holder.dispatch(Runnable::run))
        assertFalse(holder.dispatch(Runnable::run))
        assertTrue(invocations.get() == 1)
    }

    @Test
    fun `pipeline never stores or captures its constructor listener directly`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt"
        ).readText()

        assertTrue(source.contains("fun detachListenerForTeardown()"))
        assertTrue(source.contains("private val listenerHolder = TeardownDetachableListener(listener)"))
        assertTrue(source.contains("listenerHolder.dispatch"))
        assertFalse(source.contains("private val listener: Listener?"))
        assertFalse(source.contains("listener?.onFrameProcessed"))
        assertFalse(source.contains("listener?.onPipelineError"))
    }
}
