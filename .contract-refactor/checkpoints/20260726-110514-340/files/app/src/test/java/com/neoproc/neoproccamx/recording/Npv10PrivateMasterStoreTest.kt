package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
    fun storeListsOnlyPrivateNpv10MastersForManualConversion() {
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

        val clips = Npv10MasterStore(root).listMasters()
        val migrated = Npv10ContainerPolicy.masterDirectory(root).resolve(legacyMaster.name)

        assertEquals(1, clips.size)
        assertTrue(migrated.isFile)
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
}
