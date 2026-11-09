package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * s8-f10 (CP-CI1) — coverage cannot silently shrink. Asserts the test-file count and the
 * contract/golden/characterization count are non-decreasing relative to a pinned baseline (set to the
 * actual post-s8 live counts). A deleted test or removed golden trips the build, forcing coverage to
 * trend UP, never down. Mirrors ProductionSourceHygieneTest's dual-candidate root walk.
 */
class CoverageNonRegressionMetaTest {

    @Test
    fun testFileCountDoesNotRegress() {
        val count = testKotlinFiles().count()
        assertTrue("Test-file count regressed to $count (baseline $BASELINE_TEST_FILES)", count >= BASELINE_TEST_FILES)
    }

    @Test
    fun contractGoldenCharacterizationCountDoesNotRegress() {
        val count = testKotlinFiles().count { CONTRACT_GOLDEN_REGEX.containsMatchIn(it.name) }
        assertTrue(
            "Contract/golden/characterization count regressed to $count (baseline $BASELINE_CONTRACT_GOLDEN)",
            count >= BASELINE_CONTRACT_GOLDEN
        )
    }

    private fun testKotlinFiles(): Sequence<File> {
        val root = listOf(
            File("src/test/java/com/neoproc/neoproccamx"),
            File("app/src/test/java/com/neoproc/neoproccamx")
        ).firstOrNull { it.isDirectory }
        assertTrue("Could not resolve the test source root", root != null)
        return root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }
    }

    private companion object {
        // Frozen so the baseline is reproducible.
        val CONTRACT_GOLDEN_REGEX = Regex("(Golden|Contract|Characteriz)")

        // Pinned to the actual post-s8 live counts (re-measured at f10 integration time).
        const val BASELINE_TEST_FILES = 741
        const val BASELINE_CONTRACT_GOLDEN = 136
    }
}
