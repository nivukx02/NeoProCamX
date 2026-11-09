package com.neoproc.neoproccamx.recording

internal data class RecordingEncoderBitrateCapability(
    val lower: Int,
    val upper: Int,
    val hardwareAccelerated: Boolean,
    val softwareOnly: Boolean,
    val profileLevels: List<RecordingEncoderProfileLevelCapability> = emptyList()
)

internal data class RecordingEncoderProfileLevelCapability(
    val profile: Int,
    val level: Int
)

internal object RecordingEncoderBitrateCapabilityPolicy {
    fun resolveUpperBound(
        capabilities: List<RecordingEncoderBitrateCapability>,
        requiredProfile: Int? = null,
        requiredLevel: Int? = null
    ): Int? =
        preferredCapability(
            capabilities = capabilities,
            requiredProfile = requiredProfile,
            requiredLevel = requiredLevel
        )?.upper

    fun resolveLowerBound(
        capabilities: List<RecordingEncoderBitrateCapability>,
        requiredProfile: Int? = null,
        requiredLevel: Int? = null
    ): Int? =
        preferredCapability(
            capabilities = capabilities,
            requiredProfile = requiredProfile,
            requiredLevel = requiredLevel
        )?.lower

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
        capabilities: List<RecordingEncoderBitrateCapability>,
        requiredProfile: Int?,
        requiredLevel: Int?
    ): RecordingEncoderBitrateCapability? =
        preferredPool(
            capabilities.filter { capability ->
                capability.supports(requiredProfile, requiredLevel)
            }
        ).maxWithOrNull(
            compareBy<RecordingEncoderBitrateCapability> { it.upper }
                .thenBy { -it.lower.toLong() }
        )

    private fun RecordingEncoderBitrateCapability.supports(
        requiredProfile: Int?,
        requiredLevel: Int?
    ): Boolean {
        if (requiredProfile == null && requiredLevel == null) return true
        if (profileLevels.isEmpty()) return false
        return profileLevels.any { capability ->
            (requiredProfile == null || capability.profile == requiredProfile) &&
                (requiredLevel == null || capability.level >= requiredLevel)
        }
    }
}
