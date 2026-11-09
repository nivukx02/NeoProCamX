package com.neoproc.neoproccamx.rendering

/**
 * CP02 — test support for locking the Kotlin↔GLSL push-constant ABI.
 *
 * The OmniClarity / SNCE GPU activations (CP41, CP50, …) upload a Kotlin-packed `FloatArray` into a
 * GLSL `layout(push_constant)` block. Integer fields are bit-reinterpreted into float slots (see
 * [ArgusBlockMatchParams.toPushConstants]); a single reordered slot or an int/float type drift would
 * silently corrupt the GPU pass. The audit flagged a byte-layout test as MANDATORY before that wiring.
 *
 * This helper parses a push-constant block from real shader source and asserts a packed array matches
 * the block's field ORDER and per-slot int/float typing, so any future drift fails the build first.
 */
object GpuPushConstantLayout {

    data class GlslField(val type: String, val name: String)

    private val FIELD = Regex(
        """\b(int|uint|float|vec2|vec3|vec4|ivec2|ivec3|ivec4|mat3|mat4)\s+(\w+)\s*;"""
    )

    /** Ordered scalar/vector fields of the named `layout(push_constant)` block in [shaderSource]. */
    fun parseBlock(shaderSource: String, structName: String): List<GlslField> {
        val start = shaderSource.indexOf(structName)
        require(start >= 0) { "push_constant struct '$structName' not found" }
        val open = shaderSource.indexOf('{', start)
        val close = shaderSource.indexOf('}', open)
        require(open in 0 until close) { "malformed block for '$structName'" }
        val body = shaderSource.substring(open + 1, close)
        return FIELD.findAll(body).map { GlslField(it.groupValues[1], it.groupValues[2]) }.toList()
    }

    /** Number of float slots a GLSL type occupies in the packed array. */
    fun floatSlots(type: String): Int = when (type) {
        "int", "uint", "float" -> 1
        "vec2", "ivec2" -> 2
        "vec3", "ivec3" -> 3
        "vec4", "ivec4" -> 4
        "mat3" -> 9
        "mat4" -> 16
        else -> error("unsupported GLSL push-constant type '$type'")
    }

    fun isIntType(type: String): Boolean =
        type == "int" || type == "uint" || type.startsWith("ivec")

    /** Total float slots the whole block occupies. */
    fun totalFloatSlots(fields: List<GlslField>): Int = fields.sumOf { floatSlots(it.type) }

    // ────────────────────────────────────────────────────────────────────────────────
    // std140 UBO layout (s1-f07 / CP10c) — for `layout(std140) uniform` blocks (e.g.
    // RawDemosaicUniforms in demosaic_vulkan.comp), whose rules differ from the tightly-packed
    // push-constant array above: a vec3/vec4/mat column aligns to 16 bytes and a mat3 occupies three
    // vec4-padded columns (48 bytes). RawDemosaicUboLayoutTest pins the result to the C++ struct.
    // ────────────────────────────────────────────────────────────────────────────────

    data class Std140Field(val field: GlslField, val offset: Int, val size: Int)

    /** std140 base alignment (bytes) of a GLSL type. */
    fun std140BaseAlignment(type: String): Int = when (type) {
        "int", "uint", "float" -> 4
        "vec2", "ivec2" -> 8
        "vec3", "ivec3", "vec4", "ivec4" -> 16
        "mat3", "mat4" -> 16
        else -> error("unsupported std140 type '$type'")
    }

    /** Bytes a GLSL type consumes in std140 (mat3 = 3 columns each padded to a vec4 = 48). */
    fun std140ConsumedSize(type: String): Int = when (type) {
        "int", "uint", "float" -> 4
        "vec2", "ivec2" -> 8
        "vec3", "ivec3" -> 12
        "vec4", "ivec4" -> 16
        "mat3" -> 48
        "mat4" -> 64
        else -> error("unsupported std140 type '$type'")
    }

    private fun roundUp(value: Int, alignment: Int): Int = ((value + alignment - 1) / alignment) * alignment

    /** Per-field std140 byte offset + consumed size, in declaration order. */
    fun std140Layout(fields: List<GlslField>): List<Std140Field> {
        var cursor = 0
        val out = ArrayList<Std140Field>(fields.size)
        for (f in fields) {
            val offset = roundUp(cursor, std140BaseAlignment(f.type))
            val size = std140ConsumedSize(f.type)
            out.add(Std140Field(f, offset, size))
            cursor = offset + size
        }
        return out
    }

    /** Total std140 block size, rounded up to a 16-byte multiple (the UBO buffer size). */
    fun std140BlockSize(fields: List<GlslField>): Int {
        val layout = std140Layout(fields)
        if (layout.isEmpty()) return 0
        val last = layout.last()
        return roundUp(last.offset + last.size, 16)
    }
}
