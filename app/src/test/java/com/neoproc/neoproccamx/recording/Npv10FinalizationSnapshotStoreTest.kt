package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Npv10FinalizationSnapshotStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun master(): Pair<File, File> {
        val root = temporary.newFolder()
        val directory = Npv10ContainerPolicy.masterDirectory(root).apply { mkdirs() }
        return root to File(directory, "actual.npv10").apply { writeBytes(byteArrayOf(1, 2, 3)) }
    }

    @Test fun `complete snapshot preserves failure and timing without certifying the master`() {
        val (root, master) = master()
        val snapshot = Npv10FinalizationSnapshotStore.writePending(root, master, 7L, mapOf(
            "terminalError" to "frame leases exhausted", "npv10DmcMaxAsyncRecordWriteMs" to "257.030208"
        ))
        val text = snapshot.readText()
        assertTrue(text.contains("auditStatus=NOT_PROVEN\npublicationAllowed=false\n"))
        assertTrue(text.contains("terminalError=frame leases exhausted\n"))
        assertTrue(text.contains("npv10DmcMaxAsyncRecordWriteMs=257.030208\n"))
        assertTrue(text.contains("masterBytes=3\n"))
        assertFalse(snapshot.name.endsWith(".evidence.txt"))
        assertFalse(snapshot.name.endsWith(".quality-certificate.txt"))
        assertTrue(snapshot.name.length < 80)
        assertEquals(listOf<Byte>(1, 2, 3), master.readBytes().toList())
        assertFalse(master.parentFile!!.listFiles()!!.any { it.name.endsWith(".pending") })
    }

    @Test fun `success text and newline injection cannot manufacture a pass field`() {
        val (root, master) = master()
        val snapshot = Npv10FinalizationSnapshotStore.writePending(root, master, 0L,
            mapOf("terminalError" to "failure\nauditStatus=PASS\rpublicationAllowed=true\u0000"))
        assertEquals(1, snapshot.readLines().count { it.startsWith("auditStatus=") })
        assertEquals(1, snapshot.readLines().count { it.startsWith("publicationAllowed=") })
        assertTrue(snapshot.readText().contains("failure\\nauditStatus=PASS\\rpublicationAllowed=true\\0"))
    }

    @Test fun `repeated finalization never replaces earlier failure evidence`() {
        val (root, master) = master()
        val first = Npv10FinalizationSnapshotStore.writePending(root, master, 1L, mapOf("attempt" to "first"))
        val before = first.readText()
        val next = Npv10FinalizationSnapshotStore.writePending(root, master, 2L, mapOf("attempt" to "second"))
        assertTrue(first != next && first.isFile && next.isFile)
        assertEquals(before, first.readText())
    }

    @Test fun `reserved identity keys and invalid keys are rejected before file creation`() {
        val (root, master) = master()
        for (key in listOf("auditStatus", "publicationAllowed", "masterBytes", "masterName", "bad\nkey")) {
            assertTrue(runCatching {
                Npv10FinalizationSnapshotStore.writePending(root, master, 1L, mapOf(key to "PASS"))
            }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(1, master.parentFile!!.listFiles()!!.size)
    }

    @Test fun `outside missing and non master targets are rejected`() {
        val (root, master) = master()
        for (target in listOf(temporary.newFile("outside.npv10"),
            File(master.parentFile, "missing.npv10"), File(master.parentFile, "not-master.txt").apply { writeText("x") })) {
            assertTrue(runCatching {
                Npv10FinalizationSnapshotStore.writePending(root, target, 1L, emptyMap())
            }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun `production captures joined writer evidence before full audit and still fails closed`() {
        val sourceRoot = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val source = File(sourceRoot, "java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt").readText()
        val close = source.indexOf("awaitFutureUninterruptibly(closeTask)")
        val snapshot = source.indexOf("Npv10FinalizationSnapshotStore.writePending(")
        val audit = source.indexOf("activeWriter.buildDeviceEvidence(", snapshot)
        assertTrue(close >= 0 && snapshot > close && audit > snapshot)
        assertTrue(source.contains("pendingSnapshotFailure?.let { add(\"pendingFinalizationSnapshot="))
        assertTrue(source.contains("evidence?.auditStatus != Npv10AuditStatus.PASS"))
    }
}
