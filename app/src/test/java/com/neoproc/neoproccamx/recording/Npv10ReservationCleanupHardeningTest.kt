package com.neoproc.neoproccamx.recording

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10ReservationCleanupHardeningTest {

    @Test
    fun `false deletion is reported and the same orphan remains retryable`() {
        val root = Files.createTempDirectory("npv10-failed-cleanup").toFile()
        try {
            val store = Npv10MasterStore(root)
            val orphan = store.createReservationFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val attempts = AtomicInteger(0)

            val failed = store.deleteStaleReservations(
                excludedFiles = emptyList(),
                maximumAttempts = 3,
                deleteFile = {
                    attempts.incrementAndGet()
                    false
                },
                retryPause = {}
            )

            assertFalse(failed.successful)
            assertEquals(listOf(orphan.canonicalFile), failed.attempted.map(File::getCanonicalFile))
            assertTrue(failed.deleted.isEmpty())
            assertEquals(3, failed.failures.single().attempts)
            assertTrue(failed.failures.single().reason.contains("returned false"))
            assertTrue("Failed deletion must remain available for retry", orphan.isFile)

            val retried = store.deleteStaleReservations(
                excludedFiles = emptyList(),
                maximumAttempts = 1,
                deleteFile = File::delete,
                retryPause = {}
            )

            assertTrue(retried.successful)
            assertEquals(listOf(orphan.canonicalFile), retried.deleted.map(File::getCanonicalFile))
            assertFalse(orphan.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `transient false deletion is retried before cleanup reports success`() {
        val root = Files.createTempDirectory("npv10-transient-cleanup").toFile()
        try {
            val store = Npv10MasterStore(root)
            val orphan = store.createReservationFile().apply { writeBytes(byteArrayOf(7, 8, 9)) }
            val attempts = AtomicInteger(0)

            val result = store.deleteStaleReservations(
                excludedFiles = emptyList(),
                maximumAttempts = 3,
                deleteFile = { file ->
                    if (attempts.incrementAndGet() < 3) false else file.delete()
                },
                retryPause = {}
            )

            assertTrue(result.successful)
            assertEquals(3, attempts.get())
            assertEquals(listOf(orphan.canonicalFile), result.deleted.map(File::getCanonicalFile))
            assertFalse(orphan.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `overlapping manager cleanup preserves every live process lease`() {
        val root = Files.createTempDirectory("npv10-overlap-cleanup").toFile()
        val store = Npv10MasterStore(root)
        val coordinator = Npv10ReservationLeaseCoordinator()
        val firstOwner = coordinator.register(root)
        val secondOwner = coordinator.register(root)
        try {
            val firstLive = coordinator.createLeasedReservation(firstOwner, store::createReservationFile)
                .apply { writeBytes(byteArrayOf(1)) }
            val secondLive = coordinator.createLeasedReservation(secondOwner, store::createReservationFile)
                .apply { writeBytes(byteArrayOf(2)) }
            val orphan = store.createReservationFile().apply { writeBytes(byteArrayOf(3)) }

            val cleanup = coordinator.cleanup(secondOwner, store)

            assertTrue(cleanup.successful)
            assertFalse(orphan.exists())
            assertTrue("First overlapping manager lease must survive", firstLive.isFile)
            assertTrue("Second overlapping manager lease must survive", secondLive.isFile)
            assertEquals(2, cleanup.excluded.size)

            val firstRetirement = coordinator.unregister(firstOwner, store)
            assertTrue(firstRetirement.successful)
            assertFalse("Retired owner extent must be reclaimed", firstLive.exists())
            assertTrue("Other manager extent must remain leased", secondLive.isFile)

            val secondRetirement = coordinator.unregister(secondOwner, store)
            assertTrue(secondRetirement.successful)
            assertFalse(secondLive.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `direct-start admission blocks for startup cleanup and retries a truthful failure`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val cleanupEntered = CountDownLatch(1)
            val allowCleanup = CountDownLatch(1)
            val directStartReturned = AtomicBoolean(false)
            val initial = executor.submit<Npv10ReservationCleanupResult> {
                cleanupEntered.countDown()
                assertTrue(allowCleanup.await(5, TimeUnit.SECONDS))
                cleanupResult(successful = false)
            }
            val admissionExecutor = Executors.newSingleThreadExecutor()
            try {
                val admitted = admissionExecutor.submit<Npv10ReservationCleanupResult> {
                    Npv10StartupCleanupAdmission.await(initial) {
                        cleanupResult(successful = true)
                    }.also { directStartReturned.set(true) }
                }

                assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
                assertFalse("Direct start must still be gated", directStartReturned.get())
                allowCleanup.countDown()

                assertTrue(admitted.get(5, TimeUnit.SECONDS).successful)
                assertTrue(directStartReturned.get())
            } finally {
                admissionExecutor.shutdownNow()
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun cleanupResult(successful: Boolean): Npv10ReservationCleanupResult =
        Npv10ReservationCleanupResult(
            attempted = emptyList(),
            deleted = emptyList(),
            excluded = emptyList(),
            failures = if (successful) {
                emptyList()
            } else {
                listOf(
                    Npv10ReservationDeletionFailure(
                        file = File("still-present.reserve"),
                        attempts = 3,
                        reason = "injected false deletion"
                    )
                )
            }
        )
}
