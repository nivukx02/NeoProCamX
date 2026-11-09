package com.neoproc.neoproccamx.infrastructure.logging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NeoLoggerStorageLifecycleContractTest {
    private val sourceRoot = listOf(
        File("src/main/java/com/neoproc/neoproccamx"),
        File("app/src/main/java/com/neoproc/neoproccamx")
    ).first { it.isDirectory }
    private val loggerSource = File(
        sourceRoot,
        "infrastructure/logging/NeoLogger.kt"
    ).readText()

    @Test
    fun flushUsesOnePersistentBatchWriteWithoutQueueTraversalOrDuplicateFormatting() {
        val flushBody = loggerSource
            .substringAfter("private suspend fun flushBufferSynchronously(")
            .substringBefore("private fun drainQueueSnapshot()")

        assertTrue(flushBody.contains("persistentLogWriter.append(file, serializedBatch)"))
        assertTrue(flushBody.contains("entries.forEach { entry -> append(formatForFile(entry)) }"))
        assertFalse(flushBody.contains("appendText("))
        assertFalse(flushBody.contains("entries.sumOf"))
        assertFalse(loggerSource.contains("logQueue.size"))
    }

    @Test
    fun shutdownStopsAdmissionThenFlushesAndClosesBeforeDispatcherRelease() {
        val shutdownBody = loggerSource
            .substringAfter("fun shutdown() {")
            .substringBefore("// PUBLIC LOGGING API")

        val stopIndex = shutdownBody.indexOf("isOperational.compareAndSet(true, false)")
        val drainIndex = shutdownBody.indexOf("while (enqueuesInFlight.get() != 0)")
        val flushIndex = shutdownBody.indexOf("flushBufferSynchronously(allowWhileStopped = true)")
        val closeIndex = shutdownBody.indexOf("closeWriterQuietly()")
        val dispatcherCloseIndex = shutdownBody.indexOf("loggingDispatcher.close()")

        assertTrue(stopIndex in 0 until drainIndex)
        assertTrue(drainIndex < flushIndex)
        assertTrue(flushIndex < closeIndex)
        assertTrue(closeIndex < dispatcherCloseIndex)
    }

    @Test
    fun storageWorkHasDedicatedBackgroundPriorityLaneAndCoalescedPressureFlush() {
        assertTrue(loggerSource.contains("Executors.newSingleThreadExecutor"))
        assertTrue(loggerSource.contains("THREAD_PRIORITY_BACKGROUND"))
        assertTrue(loggerSource.contains("flushScheduled.compareAndSet(false, true)"))
        assertTrue(loggerSource.contains("queuedEntries.incrementAndGet()"))
    }
}
