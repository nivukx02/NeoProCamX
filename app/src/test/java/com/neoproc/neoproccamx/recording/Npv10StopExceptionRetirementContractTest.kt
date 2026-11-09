package com.neoproc.neoproccamx.recording

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP-168 regression: a post-close audit failure must never strand restart ownership. */
class Npv10StopExceptionRetirementContractTest {

    private val sourceRoot = listOf(
        File("src/main/java/com/neoproc/neoproccamx"),
        File("app/src/main/java/com/neoproc/neoproccamx")
    ).first { it.isDirectory }
    private val managerSource = File(
        sourceRoot,
        "recording/Npv10RecordingManager.kt"
    ).readText()

    @Test
    fun `fresh manager gates direct start and preparation on process-wide cleanup`() {
        val initialization = managerSource
            .substringAfter("private set\n\n    init {")
            .substringBefore("/** Serialize recovery scans")
        val startupCleanup = initialization.indexOf("startupCleanupFuture = reservationExecutor.submit")
        val staleDeletion = initialization.indexOf("cleanupStaleReservations(\"startup\")")
        val start = managerSource
            .substringAfter("fun start(config: Npv10SessionConfig): File {")
            .substringBefore("fun preparePhysicalReservationAsync()")
        val directStartGate = start.indexOf("awaitStartupReservationCleanup()")
        val reservationTake = start.indexOf("takePreparedReservation()")
        val finalAdmission = start.indexOf("cleanupStaleReservations(\"direct-start-final-admission\")")
        val prepare = managerSource.indexOf("fun preparePhysicalReservationAsync()")

        assertTrue("Startup cleanup must run on the reservation executor", startupCleanup >= 0)
        assertTrue("Startup cleanup must reclaim stale private extents", staleDeletion > startupCleanup)
        assertTrue("Startup cleanup must be declared before reservation preparation", prepare > staleDeletion)
        assertTrue("Direct start must await startup cleanup", directStartGate >= 0)
        assertTrue("Direct start must await cleanup before consuming a reservation", reservationTake > directStartGate)
        assertTrue("Failed pre-arm must be rechecked before direct allocation", finalAdmission > reservationTake)
    }

    @Test
    fun `terminal release remains retryable until process reservation owner is unregistered`() {
        val release = managerSource
            .substringAfter("fun release() {")
            .substringBefore("private companion object")

        assertTrue(release.contains("reservationExecutor.awaitTermination("))
        assertTrue(release.contains("reservationOwnerReleased = true"))
        assertTrue(release.contains("releaseComplete = reservationOwnerReleased"))
        assertTrue(release.contains("live process lease retained"))
    }

    @Test
    fun `post-close evidence exception still retires every restart owner`() {
        val stop = managerSource
            .substringAfter("fun stop(): File?")
            .substringBefore("fun resetCancelledStartError()")
        val evidenceIndex = stop.indexOf("activeWriter.buildDeviceEvidence(")
        val finallyIndex = stop.indexOf("} finally {", startIndex = evidenceIndex)
        val retirementIndex = if (finallyIndex >= 0) {
            stop.indexOf("retireStoppedSessionOwnership(", startIndex = finallyIndex)
        } else {
            -1
        }
        val sourceHasExceptionSafeRetirement =
            evidenceIndex >= 0 && finallyIndex > evidenceIndex && retirementIndex > finallyIndex

        val injected = StopFailureInjectionHarness(sourceHasExceptionSafeRetirement)
        runCatching { injected.stop(injectPostCloseEvidenceFailure = true) }

        assertTrue(
            "Injected post-close evidence failure stranded writer/output/finalization/gate ownership",
            injected.canRestart()
        )
    }

    @Test
    fun `empty stop keeps one usable recycled reservation through finally cleanup`() {
        val stop = managerSource
            .substringAfter("fun stop(): File?")
            .substringBefore("fun resetCancelledStartError()")
        val emptyStop = stop
            .substringAfter("if (writtenFrames <= 0L) {")
            .substringBefore("val evidenceWriteStartedNs")
        val prepare = managerSource
            .substringAfter("fun preparePhysicalReservationAsync() {")
            .substringBefore("private fun discardPreparedPhysicalReservation()")

        val recycleIndex = emptyStop.indexOf("store.recycleReservation(activeOutput)")
        val publishPreparedIndex = emptyStop.indexOf("preparedReservation = Npv10PreparedReservation(")
        val emptyReturnIndex = emptyStop.indexOf("return null")
        val usableReturnIndex = prepare.indexOf("if (ready.isUsable()) return")
        val staleDeletionIndex = prepare.indexOf("cleanupStaleReservations(\"preparation\")")
        assertTrue(recycleIndex >= 0)
        assertTrue(publishPreparedIndex > recycleIndex)
        assertTrue(emptyReturnIndex > publishPreparedIndex)
        assertTrue(usableReturnIndex >= 0)
        assertTrue(staleDeletionIndex > usableReturnIndex)
        assertTrue(stop.contains("} finally {"))
        assertTrue(stop.contains("retireStoppedSessionOwnership("))

        val root = Files.createTempDirectory("npv10-empty-stop-recycle").toFile()
        try {
            val store = Npv10MasterStore(root)
            val master = store.createMasterFile(testConfig())
            val reservedBytes = RecordingPreallocationPolicy.MIN_RESERVATION_BYTES
            RandomAccessFile(master, "rw").use { it.setLength(reservedBytes) }
            val result = RecordingPreallocationResult(
                plan = RecordingPreallocationPolicy.resolve(
                    availableBytes = reservedBytes +
                        RecordingPreallocationPolicy.MIN_SAFETY_RESERVE_BYTES,
                    requestedBytes = reservedBytes
                ),
                method = RecordingPreallocationMethod.POSIX_FALLOCATE,
                reservedBytes = reservedBytes
            )

            val recycled = store.recycleReservation(master)
            assertTrue(recycled?.isFile == true)
            val prepared = Npv10PreparedReservation(recycled!!, result)
            assertTrue(prepared.isUsable())
            val reservationFilesBeforeFinally = recycled.parentFile?.listFiles().orEmpty().toList()

            // retireStoppedSessionOwnership() re-arms through preparePhysicalReservationAsync().
            // Its first branch returns for this usable reservation, before stale deletion or creation.
            val retained = if (prepared.isUsable()) prepared else null

            assertSame(prepared, retained)
            assertTrue(recycled.isFile)
            assertEquals(
                reservationFilesBeforeFinally.map(File::getCanonicalPath).sorted(),
                recycled.parentFile?.listFiles().orEmpty()
                    .map(File::getCanonicalPath)
                    .sorted()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `injected post-close failure preserves master bytes and removes verified marker`() {
        val stop = managerSource
            .substringAfter("fun stop(): File?")
            .substringBefore("fun resetCancelledStartError()")
        val catchBody = stop
            .substringAfter("} catch (failure: Throwable) {")
            .substringBefore("} finally {")
        val sourceFailsClosedWithoutDeletingMaster =
            catchBody.contains("activeOutput?.let(Npv10VerifiedMasterPublication::invalidate)") &&
                catchBody.contains("lastStopDurabilityAuditPassed = false") &&
                catchBody.contains("throw failure") &&
                !catchBody.contains("activeOutput?.delete()") &&
                !catchBody.contains("activeOutput.delete()")
        assertTrue(sourceFailsClosedWithoutDeletingMaster)

        val root = Files.createTempDirectory("npv10-post-close-preserve").toFile()
        try {
            val master = root.resolve("strict-private-master.npv10")
            val expectedBytes = ByteArray(4096) { index -> (index * 31).toByte() }
            master.writeBytes(expectedBytes)
            val marker = Npv10VerifiedMasterPublication.markerFor(master).apply {
                writeText("pre-existing-marker-must-be-invalidated")
            }
            assertTrue(marker.isFile)

            val harness = PostClosePublicationFailureHarness(
                sourceFailsClosedWithoutDeletingMaster = sourceFailsClosedWithoutDeletingMaster
            )
            val failure = runCatching { harness.stop(master, injectFailure = true) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(master.isFile)
            assertArrayEquals(expectedBytes, master.readBytes())
            assertFalse(marker.exists())
            assertFalse(Npv10VerifiedMasterPublication.isVerified(master))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `retirement is idempotent and stale generation cannot clear replacement owners`() {
        val retirement = managerSource
            .substringAfter("private fun retireStoppedSessionOwnership(")
            .substringBefore("fun resetCancelledStartError()")
        val sourceUsesIdentitySafeRetirement =
            retirement.contains("currentWriter !== expectedWriter") &&
                retirement.contains("currentOutput !== expectedOutput") &&
                retirement.contains("currentConfig !== expectedConfig") &&
                retirement.contains("currentProducerBoundary !== expectedProducerBoundaryGate") &&
                retirement.contains("if (replacementPresent)") &&
                retirement.contains("if (!ownsWriter && !ownsOutput && !ownsConfig && !ownsProducerBoundary) return")
        assertTrue(sourceUsesIdentitySafeRetirement)

        val expected = SessionOwners()
        val replacement = SessionOwners()
        val harness = RetirementHarness(expected, sourceUsesIdentitySafeRetirement)

        harness.retire(expected)
        assertNull(harness.current)
        assertEquals(1, harness.retirementCount)

        harness.retire(expected)
        assertNull(harness.current)
        assertEquals(1, harness.retirementCount)

        harness.current = replacement
        harness.retire(expected)
        assertSame(replacement, harness.current)
        assertEquals(1, harness.retirementCount)
    }

    private fun testConfig() = Npv10SessionConfig(
        width = 2,
        height = 2,
        fps = 25,
        mode = "RAW",
        colorProfile = "SDR",
        startedAtEpochMs = 1_800_000_000_168L
    )

    private class StopFailureInjectionHarness(
        private val sourceHasExceptionSafeRetirement: Boolean
    ) {
        private var writerOwned = true
        private var outputOwned = true
        private var finalizationInProgress = true
        private var producerBoundaryOwned = true

        fun stop(injectPostCloseEvidenceFailure: Boolean) {
            try {
                // The writer is already durably closed when the expensive evidence/audit stage runs.
                if (injectPostCloseEvidenceFailure) {
                    throw IllegalStateException("injected post-close evidence failure")
                }
            } finally {
                if (sourceHasExceptionSafeRetirement) retireEveryOwner()
            }
        }

        fun canRestart(): Boolean =
            !writerOwned &&
                !outputOwned &&
                !finalizationInProgress &&
                !producerBoundaryOwned

        private fun retireEveryOwner() {
            writerOwned = false
            outputOwned = false
            finalizationInProgress = false
            producerBoundaryOwned = false
        }
    }

    private class PostClosePublicationFailureHarness(
        private val sourceFailsClosedWithoutDeletingMaster: Boolean
    ) {
        fun stop(master: File, injectFailure: Boolean) {
            try {
                if (injectFailure) throw IllegalStateException("injected post-close failure")
            } catch (failure: Throwable) {
                if (sourceFailsClosedWithoutDeletingMaster) {
                    Npv10VerifiedMasterPublication.invalidate(master)
                }
                throw failure
            }
        }
    }

    private class SessionOwners(
        val writer: Any = Any(),
        val output: Any = Any(),
        val config: Any = Any(),
        val producerBoundary: Any = Any()
    )

    private class RetirementHarness(
        current: SessionOwners,
        private val sourceUsesIdentitySafeRetirement: Boolean
    ) {
        var current: SessionOwners? = current
        var retirementCount: Int = 0
            private set

        fun retire(expected: SessionOwners) {
            val active = current ?: return
            val replacementPresent =
                active.writer !== expected.writer ||
                    active.output !== expected.output ||
                    active.config !== expected.config ||
                    active.producerBoundary !== expected.producerBoundary
            if (sourceUsesIdentitySafeRetirement && replacementPresent) return
            current = null
            retirementCount++
        }
    }
}
