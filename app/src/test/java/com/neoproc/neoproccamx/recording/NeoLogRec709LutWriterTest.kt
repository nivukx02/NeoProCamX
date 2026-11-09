package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NeoLogRec709LutWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sidecarIsDurablyReplacedWithoutLeavingTemporaryFiles() {
        val mov = temporaryFolder.newFile("raw_neolog.mov")
        val expected = NeoLogRec709LutWriter.sidecarFile(mov.absolutePath)
        expected.writeText("STALE")

        val written = NeoLogRec709LutWriter.writeSidecar(mov.absolutePath)

        assertEquals(expected.canonicalFile, requireNotNull(written).canonicalFile)
        assertTrue(written.length() > 1_000L)
        assertTrue(written.readLines().first().contains("NeoProCamX NeoLog"))
        assertFalse(written.readText().contains("STALE"))
        assertTrue(
            "Atomic LUT publication must not leave temporary files",
            requireNotNull(written.parentFile).listFiles().orEmpty().none { it.name.endsWith(".tmp") }
        )
    }
}
