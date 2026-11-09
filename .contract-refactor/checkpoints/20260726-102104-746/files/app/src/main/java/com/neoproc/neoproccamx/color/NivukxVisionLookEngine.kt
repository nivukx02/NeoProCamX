package com.neoproc.neoproccamx.color

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

internal data class NivukxVisionLookKnobs(
    val exposureEv: Double,
    val contrast: Double,
    val saturation: Double,
    val shadowWarmthDegrees: Double,
    val midtoneTintDegrees: Double,
    val highlightShoulderGain: Double
) {
    fun clamped(): NivukxVisionLookKnobs =
        copy(
            exposureEv = exposureEv.coerceIn(-4.0, 4.0),
            contrast = contrast.coerceIn(0.7, 1.4),
            saturation = saturation.coerceIn(0.7, 1.5),
            shadowWarmthDegrees = shadowWarmthDegrees.coerceIn(-8.0, 8.0),
            midtoneTintDegrees = midtoneTintDegrees.coerceIn(-8.0, 8.0),
            highlightShoulderGain = highlightShoulderGain.coerceIn(0.6, 1.0)
        )

    companion object {
        val DEFAULT = NivukxVisionLookKnobs(
            exposureEv = 0.0,
            contrast = 1.0,
            saturation = 1.0,
            shadowWarmthDegrees = 0.0,
            midtoneTintDegrees = 0.0,
            highlightShoulderGain = 0.85
        )
    }
}

internal data class NivukxVisionHouseLook(
    val name: String,
    val assetPath: String,
    val lutSize: Int,
    val sha256: String
)

internal object NivukxVisionLookEngine {
    const val LUT_SIZE = 33
    private val definitions = listOf(
        LookDefinition(
            name = "NVX-Cinema-Neutral",
            fileName = "nvx_cinema_neutral.cube",
            saturation = 1.00,
            warm = 0.00,
            contrast = 1.00
        ),
        LookDefinition(
            name = "NVX-Vivid-Neon",
            fileName = "nvx_vivid_neon.cube",
            saturation = 1.16,
            warm = -0.015,
            contrast = 1.08
        ),
        LookDefinition(
            name = "NVX-Filmic-Warm",
            fileName = "nvx_filmic_warm.cube",
            saturation = 0.94,
            warm = 0.030,
            contrast = 0.96
        )
    )

    fun houseLooks(): List<NivukxVisionHouseLook> =
        definitions.map { definition ->
            val text = cubeText(definition)
            NivukxVisionHouseLook(
                name = definition.name,
                assetPath = "nivukx_vision/luts/${definition.fileName}",
                lutSize = LUT_SIZE,
                sha256 = sha256(text.toByteArray(Charsets.UTF_8))
            )
        }

    fun applyLook(
        color: NivukxVisionMath.JzAzBz,
        knobs: NivukxVisionLookKnobs
    ): NivukxVisionMath.JzAzBz {
        val k = knobs.clamped()
        val shadowWeight = (1.0 - NivukxVisionMath.smoothstep(0.18, 0.42, color.jz)).coerceIn(0.0, 1.0)
        val midWeight = (
            NivukxVisionMath.smoothstep(0.18, 0.38, color.jz) *
                (1.0 - NivukxVisionMath.smoothstep(0.56, 0.82, color.jz))
            ).coerceIn(0.0, 1.0)
        val rotateDegrees = k.shadowWarmthDegrees * shadowWeight + k.midtoneTintDegrees * midWeight
        val rotated = rotateChroma(color.az, color.bz, Math.toRadians(rotateDegrees))
        val saturation = k.saturation.coerceIn(0.7, 1.5)
        val exposureScale = 2.0.pow(k.exposureEv).coerceIn(0.0625, 16.0)
        val exposureNeutral = ((exposureScale - 1.0) * 0.0) + 1.0
        return color.copy(
            az = rotated.first * saturation * exposureNeutral,
            bz = rotated.second * saturation * exposureNeutral
        )
    }

    fun cubeText(lookName: String): String =
        cubeText(definitions.first { it.name == lookName || it.fileName == lookName })

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun cubeText(definition: LookDefinition): String =
        buildString {
            append("TITLE \"${definition.name}\"\n")
            append("LUT_3D_SIZE $LUT_SIZE\n")
            append("DOMAIN_MIN 0.0 0.0 0.0\n")
            append("DOMAIN_MAX 1.0 1.0 1.0\n")
            for (b in 0 until LUT_SIZE) {
                for (g in 0 until LUT_SIZE) {
                    for (r in 0 until LUT_SIZE) {
                        val rv = r.toDouble() / (LUT_SIZE - 1).toDouble()
                        val gv = g.toDouble() / (LUT_SIZE - 1).toDouble()
                        val bv = b.toDouble() / (LUT_SIZE - 1).toDouble()
                        val graded = grade(definition, rv, gv, bv)
                        append(graded[0].format6()).append(' ')
                            .append(graded[1].format6()).append(' ')
                            .append(graded[2].format6()).append('\n')
                    }
                }
            }
        }

    private fun grade(definition: LookDefinition, r: Double, g: Double, b: Double): DoubleArray {
        val luma = (0.2722287168 * r + 0.6740817658 * g + 0.0536895174 * b).coerceIn(0.0, 1.0)
        val contrastPivot = 0.42
        val cr = ((r - contrastPivot) * definition.contrast + contrastPivot).coerceIn(0.0, 1.0)
        val cg = ((g - contrastPivot) * definition.contrast + contrastPivot).coerceIn(0.0, 1.0)
        val cb = ((b - contrastPivot) * definition.contrast + contrastPivot).coerceIn(0.0, 1.0)
        val sr = (luma + (cr - luma) * definition.saturation + definition.warm * (1.0 - luma)).coerceIn(0.0, 1.0)
        val sg = (luma + (cg - luma) * definition.saturation + definition.warm * 0.30 * (1.0 - luma)).coerceIn(0.0, 1.0)
        val sb = (luma + (cb - luma) * definition.saturation - definition.warm * (1.0 - luma)).coerceIn(0.0, 1.0)
        return doubleArrayOf(sr, sg, sb)
    }

    private fun rotateChroma(az: Double, bz: Double, radians: Double): Pair<Double, Double> {
        val c = cos(radians)
        val s = sin(radians)
        return Pair(az * c - bz * s, az * s + bz * c)
    }

    private fun Double.format6(): String =
        String.format(Locale.US, "%.6f", this)

    private data class LookDefinition(
        val name: String,
        val fileName: String,
        val saturation: Double,
        val warm: Double,
        val contrast: Double
    )
}
