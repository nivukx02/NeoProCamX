package com.neoproc.neoproccamx.recording

import java.io.File
import java.io.IOException

data class Npv10ReservationDeletionFailure(
    val file: File,
    val attempts: Int,
    val reason: String
)

data class Npv10ReservationCleanupResult(
    val attempted: List<File>,
    val deleted: List<File>,
    val excluded: List<File>,
    val failures: List<Npv10ReservationDeletionFailure>
) {
    val successful: Boolean
        get() = failures.isEmpty()

    fun failureSummary(): String = failures.joinToString(separator = "; ") { failure ->
        "${failure.file.absolutePath} attempts=${failure.attempts} reason=${failure.reason}"
    }
}

class Npv10MasterStore(privateRoot: File) {
    private val masterDirectory: File = Npv10ContainerPolicy.masterDirectory(privateRoot)
    private val legacyMasterDirectory: File = Npv10ContainerPolicy.legacyMasterDirectory(privateRoot)
    private val reservationDirectory = File(privateRoot, ".npv10_reservations")

    fun createMasterFile(config: Npv10SessionConfig): File {
        ensureMasterDirectory()
        val target = File(masterDirectory, Npv10ContainerPolicy.buildMasterFileName(config))
        require(!target.exists()) { "NPV10 master already exists: ${target.absolutePath}" }
        // A marker without its bound master is stale. Collision protection must run first so a
        // duplicate session can never hide or overwrite a previously verified recording.
        Npv10VerifiedMasterPublication.invalidate(target)
        return target
    }

    fun createReservationFile(): File {
        ensureReservationDirectory()
        return File.createTempFile("npv10-", ".reserve", reservationDirectory)
    }

    fun adoptReservation(reservation: Npv10PreparedReservation, config: Npv10SessionConfig): File {
        require(reservation.isUsable()) { "Prepared NPV10 physical reservation is no longer usable" }
        ensureMasterDirectory()
        val target = File(masterDirectory, Npv10ContainerPolicy.buildMasterFileName(config))
        require(!target.exists()) { "NPV10 master already exists: ${target.absolutePath}" }
        // A stale sidecar without its master is safe to clear only after collision protection.
        Npv10VerifiedMasterPublication.invalidate(target)
        require(reservation.file.renameTo(target)) {
            "Unable to atomically adopt prepared NPV10 reservation"
        }
        Npv10VerifiedMasterPublication.invalidate(target)
        return target
    }

    fun recycleReservation(master: File): File? {
        ensureReservationDirectory()
        val target = File(reservationDirectory, "npv10-${System.nanoTime()}.reserve")
        if (!master.renameTo(target)) return null
        Npv10VerifiedMasterPublication.invalidate(master)
        return target
    }

    fun deleteStaleReservations(excluded: File? = null): Npv10ReservationCleanupResult =
        deleteStaleReservations(excludedFiles = listOfNotNull(excluded))

    fun deleteStaleReservations(excludedFiles: Collection<File>): Npv10ReservationCleanupResult =
        deleteStaleReservations(
            excludedFiles = excludedFiles,
            maximumAttempts = STALE_RESERVATION_DELETE_ATTEMPTS,
            deleteFile = File::delete,
            retryPause = { failedAttempt ->
                try {
                    Thread.sleep(STALE_RESERVATION_RETRY_BASE_DELAY_MS * failedAttempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        )

    /**
     * Deterministic seam used by the JVM failure-injection tests. A false return or exception is
     * never counted as deletion while the path still exists, and every unresolved path remains on
     * disk for a later startup/preparation cleanup to retry.
     */
    internal fun deleteStaleReservations(
        excludedFiles: Collection<File>,
        maximumAttempts: Int,
        deleteFile: (File) -> Boolean,
        retryPause: (failedAttempt: Int) -> Unit
    ): Npv10ReservationCleanupResult {
        require(maximumAttempts >= 1) { "maximumAttempts must be positive" }
        ensureReservationDirectory()
        val excludedPaths = excludedFiles.mapTo(linkedSetOf()) { file -> file.normalizedPath() }
        val excluded = mutableListOf<File>()
        val attempted = mutableListOf<File>()
        val deleted = mutableListOf<File>()
        val failures = mutableListOf<Npv10ReservationDeletionFailure>()
        val candidates = reservationDirectory.listFiles()
            ?: throw IllegalStateException(
                "Unable to enumerate private NPV10 reservations: ${reservationDirectory.absolutePath}"
            )
        candidates.sortedBy(File::getName).forEach { file ->
            if (file.normalizedPath() in excludedPaths) {
                excluded += file
                return@forEach
            }
            attempted += file
            var lastReason = "delete was not attempted"
            var attempts = 0
            while (file.exists() && attempts < maximumAttempts) {
                attempts += 1
                val deletion = runCatching { deleteFile(file) }
                lastReason = deletion.exceptionOrNull()?.let { failure ->
                    "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
                } ?: if (deletion.getOrDefault(false)) {
                    "delete reported success but the path still exists"
                } else {
                    "delete returned false"
                }
                if (!file.exists()) break
                if (attempts < maximumAttempts) retryPause(attempts)
            }
            if (!file.exists()) {
                deleted += file
            } else {
                failures += Npv10ReservationDeletionFailure(
                    file = file,
                    attempts = attempts,
                    reason = lastReason
                )
            }
        }
        return Npv10ReservationCleanupResult(
            attempted = attempted,
            deleted = deleted,
            excluded = excluded,
            failures = failures
        )
    }

    /**
     * Read-only discovery for the asynchronous public-library replicator. Unlike the camera owner's
     * recovery scan, this must never recover, truncate or reclaim a concurrently recorded master.
     * Only a durable, generation-bound verified marker admits a source for replication.
     */
    internal fun listVerifiedMastersForPublication(): List<Npv10MasterClip> =
        sequenceOf(masterDirectory, legacyMasterDirectory)
            .flatMap { directory -> directory.listFiles().orEmpty().asSequence() }
            .filter { file ->
                file.isFile && file.extension.equals(Npv10ContainerPolicy.EXTENSION, ignoreCase = true)
            }
            .filter(Npv10VerifiedMasterPublication::isVerified)
            .map { file -> Npv10MasterClip(file, file.lastModified(), file.length()) }
            .sortedByDescending(Npv10MasterClip::createdAtEpochMs)
            .toList()

    fun listMasters(excludedActiveFile: File? = null): List<Npv10MasterClip> {
        ensureMasterDirectory()
        migrateLegacyMastersIfPresent()
        val excludedPath = excludedActiveFile?.normalizedPath()
        return sequenceOf(masterDirectory, legacyMasterDirectory)
            .flatMap { directory ->
                directory.listFiles().orEmpty().asSequence()
            }
            .asSequence()
            .filter { file -> file.isFile && file.extension.equals(Npv10ContainerPolicy.EXTENSION, ignoreCase = true) }
            .onEach { file -> recoverOrReclaimInactiveReservation(file, excludedPath) }
            .filter(Npv10VerifiedMasterPublication::isVerified)
            .map { file ->
                Npv10MasterClip(
                    file = file,
                    createdAtEpochMs = file.lastModified(),
                    bytes = file.length()
                )
            }
            .sortedByDescending { clip -> clip.createdAtEpochMs }
            .toList()
    }

    /**
     * A process can die after the physical extent is adopted but before the header or final truncate
     * reaches storage. Never inspect the active inode, and never delete an ambiguous/certified file.
     */
    private fun recoverOrReclaimInactiveReservation(file: File, excludedPath: String?) {
        if (file.normalizedPath() == excludedPath ||
            !Npv10FileRecovery.looksLikeUntrimmedReservation(file.length()) ||
            Npv10VerifiedMasterPublication.isVerified(file)
        ) {
            return
        }
        val recovery = try {
            Npv10FileRecovery.recover(file)
        } catch (_: IOException) {
            // An I/O failure is not evidence of corrupt media. Preserve the complete file so a
            // later scan or external recovery tool can retry without destructive mutation.
            return
        }
        when (recovery.status) {
            Npv10RecoveryStatus.TRUNCATED_TO_VALID_PREFIX ->
                Npv10VerifiedMasterPublication.invalidate(file)
            Npv10RecoveryStatus.INVALID_HEADER -> {
                if (hasStrictPrivateReservationOrphanSignature(file)) {
                    runCatching { file.delete() }
                }
            }
            Npv10RecoveryStatus.CLEAN -> Unit
        }
    }

    private fun hasStrictPrivateReservationOrphanSignature(file: File): Boolean {
        val parentPath = file.parentFile?.normalizedPath() ?: return false
        val ownedDirectory = parentPath == masterDirectory.normalizedPath() ||
            parentPath == legacyMasterDirectory.normalizedPath()
        return ownedDirectory &&
            STRICT_MASTER_FILE_NAME.matches(file.name) &&
            Npv10FileRecovery.looksLikeUntrimmedReservation(file.length()) &&
            !Npv10VerifiedMasterPublication.markerFor(file).exists()
    }

    private fun ensureMasterDirectory() {
        if (!masterDirectory.exists() && !masterDirectory.mkdirs()) {
            throw IllegalStateException("Unable to create private NPV10 master directory: ${masterDirectory.absolutePath}")
        }
        require(masterDirectory.isDirectory) {
            "Private NPV10 master path is not a directory: ${masterDirectory.absolutePath}"
        }
        val noMedia = File(masterDirectory, ".nomedia")
        if (!noMedia.exists()) {
            runCatching { noMedia.createNewFile() }
                .getOrElse { throwable ->
                    throw IllegalStateException("Unable to create NPV10 .nomedia marker", throwable)
                }
        }
        require(noMedia.isFile) {
            "NPV10 .nomedia marker is not a file: ${noMedia.absolutePath}"
        }
    }

    private fun ensureReservationDirectory() {
        if (!reservationDirectory.exists() && !reservationDirectory.mkdirs()) {
            throw IllegalStateException(
                "Unable to create NPV10 reservation directory: ${reservationDirectory.absolutePath}"
            )
        }
        require(reservationDirectory.isDirectory) {
            "NPV10 reservation path is not a directory: ${reservationDirectory.absolutePath}"
        }
    }

    private fun migrateLegacyMastersIfPresent() {
        if (!legacyMasterDirectory.isDirectory) return
        legacyMasterDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { file -> file.isFile && file.extension.equals(Npv10ContainerPolicy.EXTENSION, ignoreCase = true) }
            .forEach { legacy ->
                val target = File(masterDirectory, legacy.name)
                if (!target.exists()) {
                    runCatching { legacy.renameTo(target) }
                }
            }
    }

    private fun File.normalizedPath(): String = absoluteFile.toPath().normalize().toString()

    private companion object {
        const val STALE_RESERVATION_DELETE_ATTEMPTS = 3
        const val STALE_RESERVATION_RETRY_BASE_DELAY_MS = 25L

        /** Exact prefix/suffix contract emitted by Npv10ContainerPolicy.buildMasterFileName(). */
        val STRICT_MASTER_FILE_NAME = Regex(
            "^NPV10_[0-9]+_.+_[1-9][0-9]*x[1-9][0-9]*_[1-9][0-9]*fps\\.npv10$"
        )
    }
}
