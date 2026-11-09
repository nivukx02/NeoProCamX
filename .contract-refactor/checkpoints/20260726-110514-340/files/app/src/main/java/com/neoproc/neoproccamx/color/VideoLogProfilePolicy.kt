package com.neoproc.neoproccamx.color

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.util.Log
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.util.concurrent.ConcurrentHashMap

internal enum class NeoLogCurve {
    NONE,
    N_LOG_BASE,
    N_LOG_SHADOW,
    N_LOG_HIGHLIGHT,
    N_LOG_CINEMA,
    N_LOG
}

internal data class NeoLogLookProfile(
    val curve: NeoLogCurve,
    val label: String,
    val enabled: Boolean,
    val logStrengthFloor: Float,
    val contrastCap: Float,
    val saturationCap: Float,
    val highlightCompressionFloor: Float,
    val shadowBoostFloor: Float,
    val sharpeningCap: Float,
    val spatialDenoiseCap: Float,
    val chromaDenoiseCap: Float,
    val temporalBlendCap: Float,
    val warmthMultiplier: Float,
    val simulatedTenBitDitherFloor: Float,
    val nativeTenBitDitherFloor: Float,
    val bitrateBonus: Float,
    val preferredLutCubeSize: Int,
    val simulatedDynamicRangeBits: Int
)

internal enum class NeoTenBitDitherMode {
    NONE,
    DYNAMIC_BLUE_NOISE_TEMPORAL
}

internal data class NeoTenBitRenderPath(
    val useNativeHardwareBuffer: Boolean,
    val ditherMode: NeoTenBitDitherMode,
    val ditherFloor: Float
)

data class NeoTenBitHardwareSupport(
    val hdr10Supported: Boolean = false,
    val hlgSupported: Boolean = false
) {
    fun hasNativeTenBitPath(): Boolean = hdr10Supported || hlgSupported

    companion object {
        fun none(): NeoTenBitHardwareSupport = NeoTenBitHardwareSupport()
    }
}

internal object NeoLogProfilePolicy {
    private const val TAG = "NeoLogProfilePolicy"
    private val PROFILE_SEPARATOR_PATTERN = Regex("[^a-z0-9]+")

    private enum class ProfileKind {
        OFF,
        GENERIC_LOG,
        N_LOG_BASE,
        N_LOG_SHADOW,
        N_LOG_HIGHLIGHT,
        N_LOG_CINEMA
    }

    private data class ResolutionKey(
        val profileKind: ProfileKind,
        val highEndEnabled: Boolean,
        val strictHighEndOnly: Boolean
    )

    private val resolutionCache = ConcurrentHashMap<ResolutionKey, NeoLogLookProfile>()

    fun supportedLogFormats(): List<String> = listOf(
        "N-Log Base",
        "N-Log Shadow",
        "N-Log Highlight",
        "N-Log Cinema"
    )

    fun canonicalSupportedLogFormat(profileName: String?): String? {
        val key = normalize(profileName.orEmpty())
        return when {
            isNLogShadowCurve(key) -> "N-Log Shadow"
            isNLogHighlightCurve(key) -> "N-Log Highlight"
            isNLogCinemaCurve(key) -> "N-Log Cinema"
            isNLogCurve(key) -> "N-Log Base"
            isExternalLogAlias(key) -> "N-Log Cinema"
            else -> null
        }
    }

    fun effectiveOriginalLogFormat(profileName: String?, logEnabled: Boolean): String? =
        canonicalSupportedLogFormat(profileName)
            ?: if (logEnabled) "N-Log Base" else null

    fun resolve(profileName: String, logEnabled: Boolean): NeoLogLookProfile =
        resolve(
            profileName = profileName,
            logEnabled = logEnabled,
            highEndEnabled = HighEndQualityPolicy.enabled,
            strictHighEndOnly = HighEndQualityPolicy.strictHighEndOnly
        )

    internal fun resolve(
        profileName: String,
        logEnabled: Boolean,
        highEndEnabled: Boolean,
        strictHighEndOnly: Boolean
    ): NeoLogLookProfile {
        val key = normalize(profileName)
        val resolutionKey = ResolutionKey(
            profileKind = classifyProfile(key, logEnabled),
            highEndEnabled = highEndEnabled,
            strictHighEndOnly = strictHighEndOnly
        )
        return resolutionCache.computeIfAbsent(resolutionKey, ::resolveUncached)
    }

    private fun resolveUncached(key: ResolutionKey): NeoLogLookProfile {
        val baseProfile = when (key.profileKind) {
            ProfileKind.N_LOG_SHADOW -> nLogProfile(
                curve = NeoLogCurve.N_LOG_SHADOW,
                label = "N-Log Shadow",
                logStrengthFloor = 0.99f,
                contrastCap = 0.88f,
                saturationCap = 0.88f,
                highlightCompressionFloor = 0.52f,
                shadowBoostFloor = 0.54f,
                sharpeningCap = 0.06f,
                spatialDenoiseCap = 0.48f,
                chromaDenoiseCap = 0.60f,
                temporalBlendCap = 0.44f,
                warmthMultiplier = 0.99f,
                bitrateBonus = 0.88f
            )

            ProfileKind.N_LOG_HIGHLIGHT -> nLogProfile(
                curve = NeoLogCurve.N_LOG_HIGHLIGHT,
                label = "N-Log Highlight",
                logStrengthFloor = 0.99f,
                contrastCap = 0.86f,
                saturationCap = 0.86f,
                highlightCompressionFloor = 0.64f,
                shadowBoostFloor = 0.40f,
                sharpeningCap = 0.055f,
                spatialDenoiseCap = 0.42f,
                chromaDenoiseCap = 0.56f,
                temporalBlendCap = 0.40f,
                warmthMultiplier = 0.98f,
                bitrateBonus = 0.90f
            )

            ProfileKind.N_LOG_CINEMA -> nLogProfile(
                curve = NeoLogCurve.N_LOG_CINEMA,
                label = "N-Log Cinema",
                logStrengthFloor = 1.00f,
                contrastCap = 0.92f,
                saturationCap = 0.88f,
                highlightCompressionFloor = 0.60f,
                shadowBoostFloor = 0.48f,
                sharpeningCap = 0.065f,
                spatialDenoiseCap = 0.46f,
                chromaDenoiseCap = 0.58f,
                temporalBlendCap = 0.42f,
                warmthMultiplier = 1.00f,
                bitrateBonus = 0.94f
            )

            ProfileKind.N_LOG_BASE,
            ProfileKind.GENERIC_LOG -> nLogProfile(
                curve = NeoLogCurve.N_LOG_BASE,
                label = "N-Log Base",
                logStrengthFloor = 0.98f,
                contrastCap = 0.90f,
                saturationCap = 0.90f,
                highlightCompressionFloor = 0.56f,
                shadowBoostFloor = 0.46f,
                sharpeningCap = 0.08f,
                spatialDenoiseCap = 0.44f,
                chromaDenoiseCap = 0.54f,
                temporalBlendCap = 0.38f,
                warmthMultiplier = 0.98f,
                bitrateBonus = 0.82f
            )

            ProfileKind.OFF -> offProfile()
        }
        if (!baseProfile.enabled || !(key.highEndEnabled && key.strictHighEndOnly)) {
            return baseProfile
        }
        return baseProfile.copy(
            logStrengthFloor = maxOf(baseProfile.logStrengthFloor, 0.95f),
            contrastCap = minOf(baseProfile.contrastCap + 0.08f, 0.92f),
            saturationCap = minOf(baseProfile.saturationCap + 0.05f, 0.88f),
            highlightCompressionFloor = maxOf(baseProfile.highlightCompressionFloor, 0.48f),
            shadowBoostFloor = maxOf(baseProfile.shadowBoostFloor, 0.42f),
            sharpeningCap = maxOf(baseProfile.sharpeningCap + 0.10f, 0.16f).coerceAtMost(0.22f),
            spatialDenoiseCap = maxOf(baseProfile.spatialDenoiseCap + 0.08f, 0.52f).coerceAtMost(0.62f),
            chromaDenoiseCap = maxOf(baseProfile.chromaDenoiseCap + 0.08f, 0.62f).coerceAtMost(0.70f),
            temporalBlendCap = maxOf(baseProfile.temporalBlendCap + 0.04f, 0.44f).coerceAtMost(0.52f),
            warmthMultiplier = minOf(baseProfile.warmthMultiplier + 0.06f, 0.99f),
            simulatedTenBitDitherFloor = minOf(baseProfile.simulatedTenBitDitherFloor, 0.006f),
            nativeTenBitDitherFloor = 0f,
            bitrateBonus = (baseProfile.bitrateBonus + 0.10f).coerceAtMost(0.95f),
            preferredLutCubeSize = HighEndQualityPolicy.highPrecisionLutSize,
            simulatedDynamicRangeBits = 12
        )
    }

    fun preferredLutCubeSize(profileName: String, logEnabled: Boolean): Int =
        resolve(profileName, logEnabled).preferredLutCubeSize

    fun detectTenBitHardwareSupport(
        characteristics: CameraCharacteristics?
    ): NeoTenBitHardwareSupport {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || characteristics == null) {
            return NeoTenBitHardwareSupport.none()
        }
        return try {
            val profiles = characteristics.get(
                CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES
            ) ?: return NeoTenBitHardwareSupport.none()
            resolveHardwareSupport(
                supportedProfiles = profiles.supportedProfiles
                    .filterIsInstance<Long>()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Dynamic 10-bit profile query failed", t)
            NeoTenBitHardwareSupport.none()
        }
    }

    fun resolveHardwareSupport(
        supportedProfiles: Collection<Long>
    ): NeoTenBitHardwareSupport {
        if (supportedProfiles.isEmpty()) {
            return NeoTenBitHardwareSupport.none()
        }
        val supported = supportedProfiles.toSet()
        val hdr10Profiles = dynamicRangeProfileValues("HDR10")
        val hlgProfiles = dynamicRangeProfileValues("HLG")
        return NeoTenBitHardwareSupport(
            hdr10Supported = hdr10Profiles.any(supported::contains),
            hlgSupported = hlgProfiles.any(supported::contains)
        )
    }

    fun resolveTenBitRenderPath(
        profileName: String,
        logEnabled: Boolean,
        hardwareSupport: NeoTenBitHardwareSupport = NeoTenBitHardwareSupport.none(),
        tenBitRequested: Boolean = logEnabled
    ): NeoTenBitRenderPath {
        if (!tenBitRequested) {
            return NeoTenBitRenderPath(
                useNativeHardwareBuffer = false,
                ditherMode = NeoTenBitDitherMode.NONE,
                ditherFloor = 0f
            )
        }
        val profile = resolve(profileName, logEnabled)
        val simulatedFloor = if (profile.enabled) profile.simulatedTenBitDitherFloor else 0.004f
        val nativeFloor = if (profile.enabled) profile.nativeTenBitDitherFloor else 0f
        return if (hardwareSupport.hasNativeTenBitPath()) {
            NeoTenBitRenderPath(
                useNativeHardwareBuffer = true,
                ditherMode = NeoTenBitDitherMode.NONE,
                ditherFloor = nativeFloor
            )
        } else {
            NeoTenBitRenderPath(
                useNativeHardwareBuffer = false,
                ditherMode = NeoTenBitDitherMode.DYNAMIC_BLUE_NOISE_TEMPORAL,
                ditherFloor = simulatedFloor
            )
        }
    }

    private fun nLogProfile(
        curve: NeoLogCurve,
        label: String,
        logStrengthFloor: Float,
        contrastCap: Float,
        saturationCap: Float,
        highlightCompressionFloor: Float,
        shadowBoostFloor: Float,
        sharpeningCap: Float,
        spatialDenoiseCap: Float,
        chromaDenoiseCap: Float,
        temporalBlendCap: Float,
        warmthMultiplier: Float,
        bitrateBonus: Float
    ): NeoLogLookProfile =
        NeoLogLookProfile(
            curve = curve,
            label = label,
            enabled = true,
            logStrengthFloor = logStrengthFloor,
            contrastCap = contrastCap,
            saturationCap = saturationCap,
            highlightCompressionFloor = highlightCompressionFloor,
            shadowBoostFloor = shadowBoostFloor,
            sharpeningCap = sharpeningCap,
            spatialDenoiseCap = spatialDenoiseCap,
            chromaDenoiseCap = chromaDenoiseCap,
            temporalBlendCap = temporalBlendCap,
            warmthMultiplier = warmthMultiplier,
            simulatedTenBitDitherFloor = 0.004f,
            nativeTenBitDitherFloor = 0f,
            bitrateBonus = bitrateBonus,
            preferredLutCubeSize = HighEndQualityPolicy.highPrecisionLutSize,
            simulatedDynamicRangeBits = 12
        )

    private fun offProfile(): NeoLogLookProfile =
        NeoLogLookProfile(
            curve = NeoLogCurve.NONE,
            label = "OFF",
            enabled = false,
            logStrengthFloor = 0f,
            contrastCap = 1f,
            saturationCap = 1f,
            highlightCompressionFloor = 0f,
            shadowBoostFloor = 0f,
            sharpeningCap = 1f,
            spatialDenoiseCap = 1f,
            chromaDenoiseCap = 1f,
            temporalBlendCap = 1f,
            warmthMultiplier = 1f,
            simulatedTenBitDitherFloor = 0f,
            nativeTenBitDitherFloor = 0f,
            bitrateBonus = 0f,
            preferredLutCubeSize = HighEndQualityPolicy.standardLutSize,
            simulatedDynamicRangeBits = 10
        )

    private fun normalize(raw: String): String =
        raw.trim().lowercase().replace(PROFILE_SEPARATOR_PATTERN, " ").trim()

    private fun classifyProfile(key: String, logEnabled: Boolean): ProfileKind =
        when {
            isNLogShadowCurve(key) -> ProfileKind.N_LOG_SHADOW
            isNLogHighlightCurve(key) -> ProfileKind.N_LOG_HIGHLIGHT
            isNLogCinemaCurve(key) || isExternalLogAlias(key) -> ProfileKind.N_LOG_CINEMA
            isNLogCurve(key) -> ProfileKind.N_LOG_BASE
            logEnabled -> ProfileKind.GENERIC_LOG
            else -> ProfileKind.OFF
        }

    private fun dynamicRangeProfileValues(token: String): Set<Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return emptySet()
        }
        return runCatching {
            Class.forName("android.hardware.camera2.params.DynamicRangeProfiles")
                .fields
                .filter { field ->
                    field.type == java.lang.Long.TYPE &&
                        field.name.contains(token, ignoreCase = true)
                }
                .mapNotNull { field -> (field.get(null) as? Long) }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun containsAny(key: String, vararg probes: String): Boolean =
        probes.any { key.contains(it) }

    private fun isNLogShadowCurve(key: String): Boolean =
        containsAny(key, "n log shadow", "nlog shadow", "n-log shadow")

    private fun isNLogHighlightCurve(key: String): Boolean =
        containsAny(key, "n log highlight", "nlog highlight", "n-log highlight")

    private fun isNLogCinemaCurve(key: String): Boolean =
        containsAny(key, "n log cinema", "nlog cinema", "n-log cinema")

    private fun isNLogCurve(key: String): Boolean =
        containsAny(key, "n log", "nlog", "n-log", "neoproc n log", "neopro n log")

    private fun isExternalLogAlias(key: String): Boolean =
        containsAny(
            key,
            "external log",
            "intermediate",
            "film gen",
            "cinema gamut"
        ) || containsAny(
            key,
            "neo deep",
            "deep curve",
            "neo flex",
            "flex curve",
            "neo legacy",
            "legacy curve",
            "neo film",
            "film curve",
            "neo artistic",
            "artistic curve",
            "neo intermediate",
            "intermediate curve",
            "neo vision",
            "vision curve",
            "neo shallow",
            "shallow curve",
            "neo standard",
            "standard curve"
        )
}
