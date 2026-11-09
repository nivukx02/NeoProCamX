package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * s8-f09 — guards against caller-less quality policies rotting in the quality package. A QC validator
 * with no caller lies about coverage. Asserts the two satellite policies the s8-f09 prompt flagged are
 * actually wired (referenced by at least one .kt file other than their own declaration), so they are
 * either used or must be removed — never left dead.
 */
class NoDeadQualityPolicyMetaTest {

    @Test
    fun productionReadinessGatePolicyIsReferenced() {
        assertReferenced("ProductionReadinessGatePolicy")
    }

    @Test
    fun singularityAssetManifestValidatorIsReferenced() {
        assertReferenced("SingularityAssetManifestValidator")
    }

    private fun assertReferenced(symbol: String) {
        val declaringFile = "$symbol.kt"
        val references = sourceRoots()
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" && it.name != declaringFile }
            .count { it.readText().replace("\r\n", "\n").contains(symbol) }
        assertTrue(
            "$symbol must be referenced by at least one caller (else delete it as dead QC code), found $references",
            references >= 1
        )
    }

    private fun sourceRoots(): List<File> {
        val candidates = listOf(
            File("app/src/main/java/com/neoproc/neoproccamx") to File("src/main/java/com/neoproc/neoproccamx"),
            File("app/src/test/java/com/neoproc/neoproccamx") to File("src/test/java/com/neoproc/neoproccamx")
        )
        val roots = candidates.mapNotNull { (primary, fallback) ->
            listOf(primary, fallback).firstOrNull { it.isDirectory }
        }
        assertTrue("No source roots resolved", roots.isNotEmpty())
        return roots
    }
}
