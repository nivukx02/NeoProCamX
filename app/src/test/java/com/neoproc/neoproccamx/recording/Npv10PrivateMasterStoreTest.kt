package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.file.Files

class Npv10PrivateMasterStoreTest {

    @Test
    fun policyUsesPrivateNpv10MasterDirectoryAndExtension() {
        val root = Files.createTempDirectory("npv10-private-root").toFile()
        val masterDirectory = Npv10ContainerPolicy.masterDirectory(root)

        assertEquals("CamXNPV10", masterDirectory.name)
        assertEquals("npv10", Npv10ContainerPolicy.EXTENSION)
        assertEquals(10, Npv10ContainerPolicy.BIT_DEPTH)
        assertEquals("NPV10", String(Npv10ContainerPolicy.MAGIC_BYTES, Charsets.US_ASCII))
    }

    @Test
    fun storeHidesUnverifiedMastersAndListsOnlyVerifiedPrivateMasters() {
        val root = Files.createTempDirectory("npv10-store").toFile()
        val store = Npv10MasterStore(root)
        val config = Npv10SessionConfig(
            width = 3840,
            height = 2160,
            fps = 60,
            mode = "PRO",
            colorProfile = "Rec.2020",
            startedAtEpochMs = 1_778_888_000_000L
        )
        val master = store.createMasterFile(config)
        master.parentFile?.mkdirs()
        master.writeBytes(byteArrayOf(1, 2, 3))
        root.resolve("gallery-proxy.mp4").writeBytes(byteArrayOf(4, 5, 6))

        assertTrue(store.listMasters().isEmpty())

        writeVerifiedMarker(master)
        val clips = store.listMasters()

        assertEquals(1, clips.size)
        assertEquals(master.canonicalFile, clips.single().file.canonicalFile)
        assertTrue(clips.single().file.name.endsWith(".npv10"))
        assertFalse(clips.single().file.name.endsWith(".mov"))
        assertFalse(clips.single().file.name.endsWith(".mp4"))
    }

    @Test
    fun storeMigratesLegacyNpv10MastersIntoCamXNPV10Directory() {
        val root = Files.createTempDirectory("npv10-legacy-store").toFile()
        val legacyDirectory = Npv10ContainerPolicy.legacyMasterDirectory(root)
        legacyDirectory.mkdirs()
        val legacyMaster = legacyDirectory.resolve("NPV10_1_pro_rec709_1920x1080_24fps.npv10")
        legacyMaster.writeBytes(byteArrayOf(7, 8, 9))

        val store = Npv10MasterStore(root)
        val unverifiedClips = store.listMasters()
        val migrated = Npv10ContainerPolicy.masterDirectory(root).resolve(legacyMaster.name)

        assertTrue(unverifiedClips.isEmpty())
        assertTrue(migrated.isFile)
        writeVerifiedMarker(migrated)
        val clips = store.listMasters()
        assertEquals(1, clips.size)
        assertEquals(migrated.canonicalFile, clips.single().file.canonicalFile)
    }

    @Test
    fun storeCreatesNoMediaMarkerBesidePrivateMasters() {
        val root = Files.createTempDirectory("npv10-nomedia").toFile()
        val store = Npv10MasterStore(root)
        val config = Npv10SessionConfig(
            width = 1920,
            height = 1080,
            fps = 24,
            mode = "PRO",
            colorProfile = "Rec.709",
            startedAtEpochMs = 1L
        )

        val master = store.createMasterFile(config)
        val masterDirectory = master.parentFile ?: error("NPV10 master must have a parent directory")

        assertTrue(masterDirectory.resolve(".nomedia").isFile)
    }

    @Test
    fun stalePrivateReservationsAreReclaimedWithoutTouchingMastersOrExcludedOwner() {
        val root = Files.createTempDirectory("npv10-stale-reservation-cleanup").toFile()
        try {
            val store = Npv10MasterStore(root)
            val stale = store.createReservationFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val owned = store.createReservationFile().apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val master = store.createMasterFile(storeTestConfig(1_800_000_000_006L)).apply {
                writeBytes(byteArrayOf(7, 8, 9))
            }

            store.deleteStaleReservations(excluded = owned)

            assertFalse("Abandoned private reservation must be reclaimed", stale.exists())
            assertTrue("Explicitly owned reservation must be retained", owned.isFile)
            assertTrue("A cleanup must never touch a master", master.isFile)
            assertEquals(listOf(4, 5, 6), owned.readBytes().map(Byte::toInt))
            assertEquals(listOf(7, 8, 9), master.readBytes().map(Byte::toInt))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun masterFileNameKeepsDebugValidationLabelForPhysicalMatrixTraceability() {
        val config = Npv10SessionConfig(
            width = 1920,
            height = 1080,
            fps = 30,
            mode = "PRO",
            colorProfile = "Rec.709",
            validationLabel = "phase6_codex0706L_npv10_pro_sdr_1920x1080_30fps",
            startedAtEpochMs = 1_783_350_200_075L
        )

        val fileName = Npv10ContainerPolicy.buildMasterFileName(config)

        assertTrue(fileName.contains("phase6_codex0706L_npv10_pro_sdr_1920x1080_30fps"))
        assertTrue(fileName.endsWith(".npv10"))
    }

    @Test
    fun storeRecoversBoundedReservationToValidCrcPrefix() {
        val root = Files.createTempDirectory("npv10-store-large-recovery").toFile()
        try {
            val store = Npv10MasterStore(root)
            val config = storeTestConfig(startedAtEpochMs = 1_800_000_000_001L)
            val master = store.createMasterFile(config)
            Npv10FileWriter(master, config, availableStorageBytes = 0L).use { writer ->
                writer.writeFrame(storeFrame(1_000_000_000L))
            }
            val committedLength = master.length()
            val reservationBytes = RecordingPreallocationPolicy.MIN_RESERVATION_BYTES
            RandomAccessFile(master, "rw").use { file -> file.setLength(reservationBytes) }

            assertTrue(store.listMasters().isEmpty())
            assertTrue(master.isFile)
            assertEquals(committedLength, master.length())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun storeReclaimsOnlyInactiveStrictInvalidHeaderReservationOrphans() {
        val root = Files.createTempDirectory("npv10-store-orphan-reclaim").toFile()
        try {
            val store = Npv10MasterStore(root)
            val inactive = store.createMasterFile(storeTestConfig(1_800_000_000_002L))
            val active = store.createMasterFile(storeTestConfig(1_800_000_000_003L))
            val ambiguous = Npv10ContainerPolicy.masterDirectory(root).resolve("unrelated.npv10")
            // Three full-sensor extents can exceed a workstation or CI disk quota. The strict
            // orphan classification depends on the reservation signature, not its maximum size.
            val reservationBytes = RecordingPreallocationPolicy.MIN_RESERVATION_BYTES
            listOf(inactive, active, ambiguous).forEach { file ->
                RandomAccessFile(file, "rw").use { it.setLength(reservationBytes) }
            }

            assertTrue(store.listMasters(excludedActiveFile = active).isEmpty())

            assertFalse("Inactive strict orphan must release its reservation", inactive.exists())
            assertTrue("Active reservation must never be inspected or reclaimed", active.isFile)
            assertTrue("Ambiguous file name must fail closed and be preserved", ambiguous.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun adoptionCollisionPreservesExistingVerifiedMasterAndPreparedReservation() {
        val root = Files.createTempDirectory("npv10-store-adoption-collision").toFile()
        try {
            val store = Npv10MasterStore(root)
            val config = storeTestConfig(1_800_000_000_004L)
            val existing = store.createMasterFile(config).apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            writeVerifiedMarker(existing)
            assertTrue(Npv10VerifiedMasterPublication.isVerified(existing))

            val reservationFile = store.createReservationFile()
            val reservedBytes = RecordingPreallocationPolicy.MIN_RESERVATION_BYTES
            RandomAccessFile(reservationFile, "rw").use { it.setLength(reservedBytes) }
            val plan = RecordingPreallocationPolicy.resolve(
                availableBytes = reservedBytes + RecordingPreallocationPolicy.MIN_SAFETY_RESERVE_BYTES,
                requestedBytes = reservedBytes
            )
            val reservation = Npv10PreparedReservation(
                file = reservationFile,
                preallocationResult = RecordingPreallocationResult(
                    plan = plan,
                    method = RecordingPreallocationMethod.POSIX_FALLOCATE,
                    reservedBytes = reservedBytes
                )
            )
            assertTrue(reservation.isUsable())

            val collision = runCatching { store.adoptReservation(reservation, config) }.exceptionOrNull()

            assertTrue(collision is IllegalArgumentException)
            assertTrue(existing.isFile)
            assertTrue(Npv10VerifiedMasterPublication.isVerified(existing))
            assertTrue(reservationFile.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun directCreationCollisionPreservesExistingVerifiedMaster() {
        val root = Files.createTempDirectory("npv10-store-direct-collision").toFile()
        try {
            val store = Npv10MasterStore(root)
            val config = storeTestConfig(1_800_000_000_005L)
            val existing = store.createMasterFile(config).apply {
                writeBytes(byteArrayOf(4, 5, 6))
            }
            writeVerifiedMarker(existing)
            val bytesBefore = existing.readBytes()
            assertTrue(Npv10VerifiedMasterPublication.isVerified(existing))

            val collision = runCatching { store.createMasterFile(config) }.exceptionOrNull()

            assertTrue(collision is IllegalArgumentException)
            assertEquals(bytesBefore.toList(), existing.readBytes().toList())
            assertTrue(Npv10VerifiedMasterPublication.isVerified(existing))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedReservationRecycleKeepsExistingMarker() {
        val parent = Files.createTempDirectory("npv10-store-recycle-failure").toFile()
        try {
            val privateRoot = parent.resolve("private-root").apply { mkdirs() }
            val store = Npv10MasterStore(privateRoot)
            // Moving a directory into its own child is invalid on every supported filesystem.
            val marker = Npv10VerifiedMasterPublication.markerFor(privateRoot).apply {
                writeText("must-survive-failed-recycle")
            }

            val recycled = store.recycleReservation(privateRoot)

            assertNull(recycled)
            assertTrue(privateRoot.isDirectory)
            assertTrue(marker.isFile)
        } finally {
            parent.deleteRecursively()
        }
    }

    private fun storeTestConfig(startedAtEpochMs: Long): Npv10SessionConfig = Npv10SessionConfig(
        width = 2,
        height = 2,
        fps = 24,
        mode = "PRO",
        colorProfile = "SDR",
        startedAtEpochMs = startedAtEpochMs
    )

    private fun storeFrame(timestampNs: Long) = com.neoproc.neoproccamx.pipeline.RawYuvFrame(
        width = 2,
        height = 2,
        yBytes = byteArrayOf(1, 2, 3, 4),
        uBytes = byteArrayOf(128.toByte()),
        vBytes = byteArrayOf(128.toByte()),
        yRowStride = 2,
        yPixelStride = 1,
        uRowStride = 1,
        vRowStride = 1,
        uPixelStride = 1,
        vPixelStride = 1,
        timestamp = timestampNs
    )

    private fun writeVerifiedMarker(master: java.io.File) {
        val qualityReport = java.io.File(master.parentFile, "${master.name}.quality-certificate.txt")
            .apply {
                writeText(
                    "qualityEvidenceStatus=NOT_PROVEN\n" +
                        "recordingId=${master.nameWithoutExtension}\n" +
                        "recordedFileName=${master.name}\n"
                )
            }
        val qualityReportHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(qualityReport.readBytes())
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        Npv10VerifiedMasterPublication.markerFor(master).writeText(
            buildString {
                appendLine("version=3")
                appendLine("masterName=${master.name}")
                appendLine("masterBytes=${master.length()}")
                appendLine("masterLastModifiedMs=${master.lastModified()}")
                appendLine("frameCount=1")
                appendLine("payloadBytes=${master.length()}")
                appendLine("firstTimestampNs=1000000000")
                appendLine("lastTimestampNs=1000000000")
                appendLine("sessionHash=${"a".repeat(64)}")
                appendLine("captureIngressFrames=1")
                appendLine("captureAcceptedFrames=1")
                appendLine("captureCommittedFrames=1")
                appendLine("captureCommittedPayloadBytes=${master.length()}")
                appendLine("captureTimestampHash=1")
                appendLine("captureInvalidTimestamps=0")
                appendLine("captureNonMonotonicIngress=0")
                appendLine("captureMissingCadenceFrames=0")
                appendLine("masterCertificateStatus=PASS")
                appendLine("qualityEvidenceStatus=NOT_PROVEN")
                appendLine("qualityEvidenceReportState=PERSISTED")
                appendLine("qualityEvidenceReportPath=${qualityReport.canonicalPath}")
                appendLine("qualityEvidenceReportBytes=${qualityReport.length()}")
                appendLine("qualityEvidenceReportLastModifiedMs=${qualityReport.lastModified()}")
                appendLine("qualityEvidenceReportSha256=$qualityReportHash")
                appendLine("auditStatus=PASS")
                appendLine("eliteVideoQualityAuditStatus=PASS")
                appendLine("droppedFrames=0")
                appendLine("crcCheckPass=true")
                appendLine("byteCountCheckPass=true")
                appendLine("sessionHashCheckPass=true")
                appendLine("timestampCheckPass=true")
            }
        )
    }
}
