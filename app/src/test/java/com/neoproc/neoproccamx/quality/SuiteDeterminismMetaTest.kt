package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * s8-f10 (CP-CI1) — keeps the core unit suite hermetic and deterministic: it must contain NO
 * Robolectric / Android-instrumentation runner. (The suite is pure-JVM with returnDefaultValues=true
 * and JUnit4 only.) Scoped to the genuinely-forbidden, currently-absent thing — it deliberately does
 * NOT scan for time/randomness, which legitimately appear in a few deterministic tests.
 */
class SuiteDeterminismMetaTest {

    @Test
    fun coreJvmSuiteHasNoRobolectricOrInstrumentation() {
        val forbidden = listOf(
            "org.robolectric",
            "RobolectricTestRunner",
            "androidx.test",
            "InstrumentationRegistry",
            "@RunWith(AndroidJUnit"
        )
        val offenders = testKotlinFiles()
            // Exclude this meta-test itself — it names the forbidden tokens as string literals.
            .filter { it.name != "SuiteDeterminismMetaTest.kt" }
            .filter { file ->
                val text = file.readText()
                forbidden.any { text.contains(it) }
            }
            .map { it.name }
            .toList()
        assertTrue(
            "Core JVM suite must stay hermetic (no Robolectric/instrumentation): $offenders",
            offenders.isEmpty()
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
}
