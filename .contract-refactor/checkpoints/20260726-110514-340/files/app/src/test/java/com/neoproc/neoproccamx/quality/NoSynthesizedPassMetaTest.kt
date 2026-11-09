package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * s8-f09 — source-hygiene lock: no synthesized all-PASS quality-gate factory may exist in PRODUCTION
 * code. `allPassingResults()` (which manufactured 16 PASS verdicts with zero measurement) was retired
 * to a test-only fixture; this test fails the build if it (or any equivalent) reappears in main. It
 * guarantees the Singularity gate's false-pass count is 0 by construction.
 */
class NoSynthesizedPassMetaTest {

    @Test
    fun noProductionSourceReferencesAllPassingResults() {
        val offenders = productionKotlinFiles()
            .filter { it.readText().replace("\r\n", "\n").contains("allPassingResults") }
            .map { it.name }
            .toList()
        assertEquals(
            "Synthesized all-PASS factory must not exist in production code: $offenders",
            emptyList<String>(),
            offenders
        )
    }

    private fun productionKotlinFiles(): Sequence<File> {
        val sourceRoot = listOf(
            File("app/src/main/java/com/neoproc/neoproccamx"),
            File("src/main/java/com/neoproc/neoproccamx")
        ).firstOrNull { it.isDirectory }
            ?: File("app/src/main/java/com/neoproc/neoproccamx")
        assertTrue("Missing production source root: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        return sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
    }
}
