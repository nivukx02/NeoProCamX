package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocaleStableStringFormatTest {

    @Test
    fun productionStringFormatCallsDeclareExplicitLocale() {
        val sourceRoot = listOf(
            File("app/src/main/java/com/neoproc/neoproccamx"),
            File("src/main/java/com/neoproc/neoproccamx")
        ).firstOrNull { it.isDirectory }
            ?: File("app/src/main/java/com/neoproc/neoproccamx")
        assertTrue("Missing production source root: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)

        val violations = sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val lines = file.readLines()
                lines.mapIndexedNotNull { index, line ->
                    if (!line.contains("String.format(")) return@mapIndexedNotNull null

                    val nextNonBlank = lines.drop(index + 1).firstOrNull { it.isNotBlank() }.orEmpty()
                    val hasExplicitLocale =
                        line.contains("String.format(Locale.") ||
                            line.contains("String.format(java.util.Locale.") ||
                            nextNonBlank.trimStart().startsWith("Locale.") ||
                            nextNonBlank.trimStart().startsWith("java.util.Locale.")

                    if (hasExplicitLocale) {
                        null
                    } else {
                        "${file.relativeTo(File(".")).path}:${index + 1}: ${line.trim()}"
                    }
                }
            }
            .toList()

        assertTrue(
            "String.format must use an explicit Locale for stable diagnostics:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty()
        )
    }
}
