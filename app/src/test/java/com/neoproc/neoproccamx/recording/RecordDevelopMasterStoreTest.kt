package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Contract for [RecordDevelopMasterStore] — the per-lane private store for record-then-develop LIGHT
 * intermediates. Verifies file naming, `.nomedia` marking, newest-first + extension-filtered listing,
 * SDR/NDR isolation (no cross-contamination), and clip+sidecar deletion. All pure java.io on a temp dir.
 */
class RecordDevelopMasterStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cfg(started: Long, w: Int = 4096, h: Int = 3072, fps: Int = 30) =
        RecordDevelopSessionConfig(
            width = w, height = h, fps = fps, mode = "video",
            colorProfile = "rec709", startedAtEpochMs = started
        )

    @Test
    fun createMasterFileNamesInKindDirAndMarksNoMedia() {
        val root = tmp.newFolder("files")
        val store = RecordDevelopMasterStore(root, RecordDevelopMasterKind.SDR)
        val f = store.createMasterFile(cfg(1000L))
        assertEquals("mov", f.extension)
        assertTrue(f.name.startsWith("DEVSDR_1000_"))
        assertTrue(f.name.contains("4096x3072_30fps"))
        val dir = File(root, "CamXDevelopSDR")
        assertTrue(dir.isDirectory)
        assertTrue(File(dir, ".nomedia").isFile)
    }

    @Test
    fun listMastersReturnsNewestFirstAndFiltersExtension() {
        val root = tmp.newFolder("files")
        val store = RecordDevelopMasterStore(root, RecordDevelopMasterKind.NDR)
        val a = store.createMasterFile(cfg(1000L)).apply { writeBytes(ByteArray(10)); setLastModified(1_600_000_000_000L) }
        val b = store.createMasterFile(cfg(2000L)).apply { writeBytes(ByteArray(20)); setLastModified(1_600_000_100_000L) }
        // A non-mov sibling must be ignored by the extension filter.
        File(a.parentFile, "note.txt").writeText("x")
        // A failed/partial zero-byte mov must not surface in the converter card.
        store.createMasterFile(cfg(3000L)).createNewFile()

        val masters = store.listMasters()
        assertEquals(2, masters.size)
        assertEquals(b.name, masters[0].file.name) // newest first
        assertEquals(a.name, masters[1].file.name)
        assertEquals(RecordDevelopMasterKind.NDR, masters[0].kind)
        assertEquals(20L, masters[0].bytes)
    }

    @Test
    fun sdrAndNdrStoresDoNotCrossContaminate() {
        val root = tmp.newFolder("files")
        val sdr = RecordDevelopMasterStore(root, RecordDevelopMasterKind.SDR)
        val ndr = RecordDevelopMasterStore(root, RecordDevelopMasterKind.NDR)
        sdr.createMasterFile(cfg(1000L)).writeBytes(ByteArray(4))
        ndr.createMasterFile(cfg(1000L)).writeBytes(ByteArray(4))

        assertEquals(1, sdr.listMasters().size)
        assertEquals(1, ndr.listMasters().size)
        assertTrue(sdr.listMasters().all { it.kind == RecordDevelopMasterKind.SDR })
        assertTrue(ndr.listMasters().all { it.kind == RecordDevelopMasterKind.NDR })
    }

    @Test
    fun hasMastersIsTrueWhenAClipExistsAndNeverCreatesTheDirectory() {
        val root = tmp.newFolder("files")
        val sdr = RecordDevelopMasterStore(root, RecordDevelopMasterKind.SDR)
        val dir = File(root, "CamXDevelopSDR")

        // Absent lane: false, and — critically — the probe must NOT materialise the private dir/.nomedia.
        assertFalse(sdr.hasMasters())
        assertFalse(dir.exists())

        // A non-mov sibling alone does not count as a master (and still no dir side effects from probing).
        dir.mkdirs()
        File(dir, "note.txt").writeText("x")
        assertFalse(sdr.hasMasters())

        // A zero-byte .mov is a failed/partial output, not a usable develop master.
        sdr.createMasterFile(cfg(900L)).createNewFile()
        assertFalse(sdr.hasMasters())

        // A real .mov intermediate flips it true.
        sdr.createMasterFile(cfg(1000L)).writeBytes(ByteArray(4))
        assertTrue(sdr.hasMasters())
    }

    @Test
    fun deleteRemovesClipAndSidecar() {
        val root = tmp.newFolder("files")
        val store = RecordDevelopMasterStore(root, RecordDevelopMasterKind.SDR)
        val f = store.createMasterFile(cfg(1000L)).apply { writeBytes(ByteArray(4)) }
        val sidecar = VideoDevelopColorSidecar.sidecarFileFor(f).apply { writeBytes(ByteArray(2)) }

        val clip = store.listMasters().first()
        assertTrue(store.delete(clip))
        assertFalse(f.exists())
        assertFalse(sidecar.exists())
    }
}
