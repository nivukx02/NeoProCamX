package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Hdr10PqPreviewShaderInterfaceContractTest {
    private enum class Stage { VERTEX, FRAGMENT }

    private data class Uniform(
        val type: String,
        val explicitPrecision: String?
    )

    private val compositorSource by lazy {
        File("src/main/java/com/neoproc/neoproccamx/rendering/Hdr10PqPreviewCompositor.kt").readText()
    }

    private fun shaderSource(name: String): String {
        val marker = "private const val $name = \"\"\""
        return compositorSource.substringAfter(marker, missingDelimiterValue = "")
            .substringBefore("\"\"\"")
            .also { assertTrue("$name source must remain available to the contract test", it.isNotBlank()) }
    }

    private fun uniforms(shader: String): Map<String, Uniform> =
        Regex("""uniform\s+(?:(lowp|mediump|highp)\s+)?([A-Za-z0-9_]+)\s+([A-Za-z0-9_]+)\s*;""")
            .findAll(shader)
            .associate { match ->
                match.groupValues[3] to Uniform(
                    type = match.groupValues[2],
                    explicitPrecision = match.groupValues[1].ifBlank { null }
                )
            }

    private fun effectivePrecision(shader: String, stage: Stage, uniform: Uniform): String? {
        uniform.explicitPrecision?.let { return it }
        val family = when {
            uniform.type == "int" || uniform.type == "uint" ||
                uniform.type.startsWith("ivec") || uniform.type.startsWith("uvec") -> "int"
            uniform.type == "float" || uniform.type.startsWith("vec") ||
                uniform.type.startsWith("mat") -> "float"
            else -> return null
        }
        Regex("""precision\s+(lowp|mediump|highp)\s+$family\s*;""")
            .find(shader)
            ?.groupValues
            ?.get(1)
            ?.let { return it }
        return when (stage) {
            Stage.VERTEX -> "highp"
            Stage.FRAGMENT -> if (family == "int") "mediump" else null
        }
    }

    @Test
    fun `all cross-stage NDR uniforms have identical high precision`() {
        val vertex = shaderSource("VERTEX_SHADER")
        val fragment = shaderSource("FRAGMENT_SHADER")
        val vertexUniforms = uniforms(vertex)
        val fragmentUniforms = uniforms(fragment)
        val sharedNames = vertexUniforms.keys.intersect(fragmentUniforms.keys)

        assertTrue("project-owned route selector must remain a cross-stage uniform", "uProjectOwnedInput" in sharedNames)
        sharedNames.forEach { name ->
            val vertexUniform = requireNotNull(vertexUniforms[name])
            val fragmentUniform = requireNotNull(fragmentUniforms[name])
            val vertexPrecision = effectivePrecision(vertex, Stage.VERTEX, vertexUniform)
            val fragmentPrecision = effectivePrecision(fragment, Stage.FRAGMENT, fragmentUniform)

            assertEquals("cross-stage uniform $name changed GLSL type", vertexUniform.type, fragmentUniform.type)
            assertNotNull("vertex precision for $name must be deterministic", vertexPrecision)
            assertNotNull("fragment precision for $name must be deterministic", fragmentPrecision)
            assertEquals("cross-stage uniform $name has incompatible precision", vertexPrecision, fragmentPrecision)
            assertEquals("cross-stage NDR control $name must remain high precision", "highp", vertexPrecision)
        }
    }
}
