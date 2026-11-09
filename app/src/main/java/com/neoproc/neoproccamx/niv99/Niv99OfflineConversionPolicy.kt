package com.neoproc.neoproccamx.niv99

import java.util.Locale

/** Every user-visible derivative whose pixels are owned by NIV99. */
enum class Niv99OfflineConversionRoute {
    NPV10_SDR,
    NPV10_NDR,
    RECORD_DEVELOP_SDR,
    RECORD_DEVELOP_NDR,
    RAW_TO_LOG,
    RAW_TO_RAW_DEVELOPED,
    RAW_TO_NDR10_PLUS,
    RAW_TO_NIVUKX_VISION
}

enum class Niv99OfflinePixelDomain {
    P010_TEN_BIT,
    SENSOR_CFA
}

/**
 * Immutable processing contract shared by every offline entry point. It deliberately describes
 * quality/integrity behavior, not aspirational benchmark claims.
 */
data class Niv99OfflineConversionPlan(
    val route: Niv99OfflineConversionRoute,
    val kind: Niv99ConversionKind,
    val pixelDomain: Niv99OfflinePixelDomain,
    val enabled: Boolean = Niv99ExecutionBoundaryPolicy.OFFLINE_CONVERSION_ENABLED,
    val fullFrame: Boolean = true,
    val preserveDimensions: Boolean = true,
    val preserveFrameCount: Boolean = true,
    val preservePresentationTimestamps: Boolean = true,
    val preserveSource: Boolean = true,
    val minimumOutputBitDepth: Int = 10,
    val capturedWhiteBalanceAuthoritative: Boolean = true,
    val spatialDenoise: Boolean = true,
    val temporalDenoise: Boolean = true,
    val detailProtection: Boolean = true,
    val highlightProtection: Boolean = true
) {
    init {
        require(minimumOutputBitDepth >= 10) { "NIV99 conversion cannot introduce an 8-bit stage" }
    }

    fun requireFrameContract(
        sourceWidth: Int,
        sourceHeight: Int,
        outputWidth: Int,
        outputHeight: Int,
        sourceFps: Int,
        outputFps: Int,
        outputBitDepth: Int
    ) {
        check(enabled) { "NIV99 must be enabled for ${route.name}" }
        require(sourceWidth > 0 && sourceHeight > 0 && sourceFps > 0) {
            "NIV99 source geometry/cadence is invalid: ${sourceWidth}x$sourceHeight@$sourceFps"
        }
        require(!preserveDimensions || sourceWidth == outputWidth && sourceHeight == outputHeight) {
            "NIV99 conversion must remain full-frame: source=${sourceWidth}x$sourceHeight " +
                "output=${outputWidth}x$outputHeight"
        }
        require(!preservePresentationTimestamps || sourceFps == outputFps) {
            "NIV99 conversion must preserve cadence: source=$sourceFps output=$outputFps"
        }
        require(outputBitDepth >= minimumOutputBitDepth) {
            "NIV99 ${route.name} requires at least $minimumOutputBitDepth-bit output"
        }
    }

    fun evidence(): Map<String, String> = linkedMapOf(
        "niv99OfflineEnabled" to enabled.toString(),
        "niv99OfflineRoute" to route.name,
        "niv99OfflineKind" to kind.name,
        "niv99OfflinePixelDomain" to pixelDomain.name,
        "niv99OfflineFullFrame" to fullFrame.toString(),
        "niv99OfflinePreserveDimensions" to preserveDimensions.toString(),
        "niv99OfflinePreserveFrameCount" to preserveFrameCount.toString(),
        "niv99OfflinePreservePts" to preservePresentationTimestamps.toString(),
        "niv99OfflineSourceImmutable" to preserveSource.toString(),
        "niv99OfflineMinimumOutputBitDepth" to minimumOutputBitDepth.toString(),
        "niv99OfflineCapturedWbAuthoritative" to capturedWhiteBalanceAuthoritative.toString(),
        "niv99OfflineSpatialDenoise" to spatialDenoise.toString(),
        "niv99OfflineTemporalDenoise" to temporalDenoise.toString(),
        "niv99OfflineDetailProtection" to detailProtection.toString(),
        "niv99OfflineHighlightProtection" to highlightProtection.toString()
    )
}

object Niv99OfflineConversionPolicy {
    fun npv10(transport: String): Niv99OfflineConversionPlan {
        val ndr = transport.contains("PQ", ignoreCase = true) ||
            transport.contains("NDR", ignoreCase = true) ||
            transport.contains("NIVUKX", ignoreCase = true)
        return plan(
            if (ndr) Niv99OfflineConversionRoute.NPV10_NDR else Niv99OfflineConversionRoute.NPV10_SDR,
            if (ndr) Niv99ConversionKind.P010_TO_NDR else Niv99ConversionKind.P010_TO_SDR,
            Niv99OfflinePixelDomain.P010_TEN_BIT
        )
    }

    fun npv10Sdr(): Niv99OfflineConversionPlan = npv10("SDR")

    fun recordDevelop(isNdr: Boolean): Niv99OfflineConversionPlan = plan(
        if (isNdr) Niv99OfflineConversionRoute.RECORD_DEVELOP_NDR
        else Niv99OfflineConversionRoute.RECORD_DEVELOP_SDR,
        if (isNdr) Niv99ConversionKind.RECORD_DEVELOP_TO_NDR
        else Niv99ConversionKind.RECORD_DEVELOP_TO_SDR,
        Niv99OfflinePixelDomain.P010_TEN_BIT
    )

    fun recordDevelopSdr(): Niv99OfflineConversionPlan = recordDevelop(isNdr = false)

    fun rawToLog(): Niv99OfflineConversionPlan = raw(
        Niv99OfflineConversionRoute.RAW_TO_LOG,
        Niv99ConversionKind.RAW_TO_LOG
    )

    fun rawToRawDeveloped(): Niv99OfflineConversionPlan = raw(
        Niv99OfflineConversionRoute.RAW_TO_RAW_DEVELOPED,
        Niv99ConversionKind.RAW_TO_RAW_DEVELOPED
    ).copy(
        // A CinemaDNG master is a sensor-CFA identity export. Spatial/temporal cleanup, detail
        // shaping, and highlight transforms remain available to Log/NDR derivatives but would make
        // a supposedly RAW file non-RAW.
        spatialDenoise = false,
        temporalDenoise = false,
        detailProtection = false,
        highlightProtection = false
    )

    fun rawToNdr10Plus(): Niv99OfflineConversionPlan = raw(
        Niv99OfflineConversionRoute.RAW_TO_NDR10_PLUS,
        Niv99ConversionKind.RAW_TO_NDR10
    )

    fun rawToNivukxVision(): Niv99OfflineConversionPlan = raw(
        Niv99OfflineConversionRoute.RAW_TO_NIVUKX_VISION,
        Niv99ConversionKind.RAW_TO_NIVUKX
    )

    fun forRawDelivery(deliveryName: String): Niv99OfflineConversionPlan = when (
        deliveryName.trim().uppercase(Locale.US)
    ) {
        "NDR10_PLUS", "XDR10_PLUS", "RAW_TO_NDR10_PLUS", "RAW_TO_XDR10" -> rawToNdr10Plus()
        "NIVUKX_VISION", "RAW_TO_NIVUKX" -> rawToNivukxVision()
        else -> rawToLog()
    }

    private fun raw(
        route: Niv99OfflineConversionRoute,
        kind: Niv99ConversionKind
    ): Niv99OfflineConversionPlan = plan(route, kind, Niv99OfflinePixelDomain.SENSOR_CFA)

    private fun plan(
        route: Niv99OfflineConversionRoute,
        kind: Niv99ConversionKind,
        domain: Niv99OfflinePixelDomain
    ): Niv99OfflineConversionPlan = Niv99OfflineConversionPlan(
        route = route,
        kind = kind,
        pixelDomain = domain
    )
}

/**
 * Resolves whether an already-rendered P010 derivative may receive NIV99's small residual illuminant
 * correction.  Missing legacy metadata fails closed: denoise/detail still run, but baked manual or
 * locked white balance can never be moved on inference alone.
 */
object Niv99OfflineWhiteBalanceAuthorityPolicy {
    enum class Authority {
        AUTOMATIC_REFINEMENT_ALLOWED,
        MANUAL_CAPTURE_PRESERVED,
        AWB_LOCK_PRESERVED,
        UNKNOWN_CAPTURE_PRESERVED
    }

    fun resolve(metadata: Map<String, String>): Authority {
        if (metadata.booleanValue("awbLockActive") == true) return Authority.AWB_LOCK_PRESERVED
        if (metadata.booleanValue("manualWhiteBalanceActive") == true ||
            metadata["whiteBalanceAuthority"].orEmpty().equals("MANUAL", ignoreCase = true)
        ) {
            return Authority.MANUAL_CAPTURE_PRESERVED
        }
        val explicitlyAutomatic = metadata.booleanValue("manualWhiteBalanceActive") == false &&
            metadata.booleanValue("awbLockActive") == false &&
            metadata["whiteBalanceAuthority"].orEmpty().equals("AUTO", ignoreCase = true)
        return if (explicitlyAutomatic) {
            Authority.AUTOMATIC_REFINEMENT_ALLOWED
        } else {
            Authority.UNKNOWN_CAPTURE_PRESERVED
        }
    }

    fun residualCorrectionAllowed(metadata: Map<String, String>): Boolean =
        resolve(metadata) == Authority.AUTOMATIC_REFINEMENT_ALLOWED

    private fun Map<String, String>.booleanValue(key: String): Boolean? = when (
        entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.trim()?.lowercase(Locale.US)
    ) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
}
