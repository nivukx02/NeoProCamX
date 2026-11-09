package com.neoproc.neoproccamx.recording

internal data class RecordingEncoderBitrateCapability(
    val lower: Int,
    val upper: Int,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean
)

internal object RecordingEncoderBitrateCapabilityPolicy {
    fun resolveUpperBound(capabilities: List<RecordingEncoderBitrateCapability>): Int? =
        preferredCapability(capabilities)?.upper

    fun resolveLowerBound(capabilities: List<RecordingEncoderBitrateCapability>): Int? =
        preferredCapability(capabilities)?.lower

    fun isBitrateSupported(
        lower: Int,
        upper: Int,
        requestedBitrate: Int
    ): Boolean =
        requestedBitrate > 0 &&
            upper >= lower &&
            requestedBitrate in lower..upper

    private fun preferredPool(
        capabilities: List<RecordingEncoderBitrateCapability>
    ): List<RecordingEncoderBitrateCapability> {
        val valid = capabilities.filter { capability ->
            capability.lower >= 0 &&
                capability.upper > 0 &&
                capability.upper >= capability.lower
        }
        val hardware = valid.filter { capability ->
            capability.hardwareAccelerated && !capability.softwareOnly
        }
        return hardware.ifEmpty { valid }
    }

    private fun preferredCapability(
        capabilities: List<RecordingEncoderBitrateCapability>
    ): RecordingEncoderBitrateCapability? =
        preferredPool(capabilities).maxWithOrNull(
            compareBy<RecordingEncoderBitrateCapability> { it.upper }
                .thenBy { -it.lower.toLong() }
        )
}
