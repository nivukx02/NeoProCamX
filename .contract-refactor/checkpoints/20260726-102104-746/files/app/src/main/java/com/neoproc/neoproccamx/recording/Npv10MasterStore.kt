package com.neoproc.neoproccamx.recording

import java.io.File

class Npv10MasterStore(privateRoot: File) {
    private val masterDirectory: File = Npv10ContainerPolicy.masterDirectory(privateRoot)
    private val legacyMasterDirectory: File = Npv10ContainerPolicy.legacyMasterDirectory(privateRoot)
    private val reservationDirectory = File(privateRoot, ".npv10_reservations")

    fun createMasterFile(config: Npv10SessionConfig): File {
        ensureMasterDirectory()
        return File(masterDirectory, Npv10ContainerPolicy.buildMasterFileName(config))
    }

    fun createReservationFile(): File {
        ensureReservationDirectory()
        return File.createTempFile("npv10-", ".reserve", reservationDirectory)
    }

    fun adoptReservation(reservation: Npv10PreparedReservation, config: Npv10SessionConfig): File {
        require(reservation.isUsable()) { "Prepared NPV10 physical reservation is no longer usable" }
        val target = createMasterFile(config)
        require(!target.exists()) { "NPV10 master already exists: ${target.absolutePath}" }
        require(reservation.file.renameTo(target)) {
            "Unable to atomically adopt prepared NPV10 reservation"
        }
        return target
    }

    fun recycleReservation(master: File): File? {
        ensureReservationDirectory()
        val target = File(reservationDirectory, "npv10-${System.nanoTime()}.reserve")
        return target.takeIf { master.renameTo(it) }
    }

    fun deleteStaleReservations(excluded: File? = null) {
        ensureReservationDirectory()
        reservationDirectory.listFiles().orEmpty().forEach { file ->
            if (file != excluded) runCatching { file.delete() }
        }
    }

    fun listMasters(excludedActiveFile: File? = null): List<Npv10MasterClip> {
        ensureMasterDirectory()
        migrateLegacyMastersIfPresent()
        val excludedPath = excludedActiveFile?.absoluteFile?.toPath()?.normalize()?.toString()
        return sequenceOf(masterDirectory, legacyMasterDirectory)
            .flatMap { directory ->
                directory.listFiles().orEmpty().asSequence()
            }
            .asSequence()
            .filter { file -> file.isFile && file.extension.equals(Npv10ContainerPolicy.EXTENSION, ignoreCase = true) }
            .onEach { file ->
                if (file.absoluteFile.toPath().normalize().toString() != excludedPath) {
                    if (Npv10FileRecovery.looksLikeUntrimmedReservation(file.length())) {
                        runCatching { Npv10FileRecovery.recover(file) }
                    }
                }
            }
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
}
