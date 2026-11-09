package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10AuthorizedCameraDispatchTest {
    @Test
    fun `executor rejection is reported exactly once`() {
        val failures = mutableListOf<Pair<String, Throwable?>>()

        Npv10AuthorizedCameraDispatch.dispatch(
            execute = { throw IllegalStateException("executor stopped") },
            post = { true },
            action = { error("must not run") },
            onFailure = { reason, failure -> failures += reason to failure }
        )

        assertEquals(1, failures.size)
        assertTrue(failures.single().first.contains("executor"))
        assertTrue(failures.single().second is IllegalStateException)
    }

    @Test
    fun `handler rejection is reported without running action`() {
        var ran = false
        val failures = mutableListOf<Pair<String, Throwable?>>()

        Npv10AuthorizedCameraDispatch.dispatch(
            execute = { it.run() },
            post = { false },
            action = { ran = true },
            onFailure = { reason, failure -> failures += reason to failure }
        )

        assertFalse(ran)
        assertEquals(1, failures.size)
        assertTrue(failures.single().first.contains("post was rejected"))
        assertNull(failures.single().second)
    }

    @Test
    fun `handler exception and action exception are both fail closed`() {
        val postFailures = mutableListOf<String>()
        Npv10AuthorizedCameraDispatch.dispatch(
            execute = { it.run() },
            post = { throw IllegalArgumentException("dead looper") },
            action = { error("must not run") },
            onFailure = { reason, _ -> postFailures += reason }
        )
        val actionFailures = mutableListOf<String>()
        Npv10AuthorizedCameraDispatch.dispatch(
            execute = { it.run() },
            post = { it.run(); true },
            action = { error("publication failed") },
            onFailure = { reason, _ -> actionFailures += reason }
        )

        assertEquals(listOf("Authorized NPV10 camera-handler post threw"), postFailures)
        assertEquals(listOf("Authorized NPV10 camera action threw"), actionFailures)
    }

    @Test
    fun `preview-only or stale-session replay cannot commit`() {
        assertFalse(commitEligible(p010 = false, sessionCurrent = true, generation = SESSION))
        assertFalse(commitEligible(p010 = true, sessionCurrent = false, generation = SESSION))
        assertFalse(commitEligible(p010 = true, sessionCurrent = true, generation = SESSION + 1L))
        assertTrue(commitEligible(p010 = true, sessionCurrent = true, generation = SESSION))
    }

    private fun commitEligible(p010: Boolean, sessionCurrent: Boolean, generation: Long) =
        Npv10ProducerStartupCommitPolicy.canCommitReplay(
            releaseRequested = true,
            p010TargetPresent = p010,
            sessionCurrent = sessionCurrent,
            submissionGeneration = generation,
            leaseGeneration = SESSION
        )

    private companion object {
        const val SESSION = 17L
    }
}
