package com.neoproc.neoproccamx.ui

/**
 * Selects the display-referred creative cube for finished SDR/PRO recording.
 *
 * Natural color science is deliberately neutral and can therefore resolve no combined cube even while
 * the full color engine is enabled. That absence must not suppress the calibrated project film-print
 * fallback. The decision is kept independent from rendering so preview and recording can share the exact
 * same cube reference without copying full LUT data.
 */
internal object SdrCreativeLutSelectionPolicy {

    inline fun resolveRecordCandidateLazy(
        isSdrRecordDelivery: Boolean,
        recordDevelopLightMaster: Boolean,
        fullColorEnabled: Boolean,
        userCreativeLutEnabled: Boolean,
        calibratedFallbackEnabled: Boolean,
        fullColorCandidate: () -> Pair<FloatArray, Int>?,
        userCreativeCandidate: () -> Pair<FloatArray, Int>?,
        calibratedFallbackCandidate: () -> Pair<FloatArray, Int>?
    ): Pair<FloatArray, Int>? {
        if (!isSdrRecordDelivery || recordDevelopLightMaster) return null

        val fullColor = if (fullColorEnabled) fullColorCandidate() else null
        if (fullColor != null) return fullColor
        val userCreative = if (userCreativeLutEnabled) userCreativeCandidate() else null
        if (userCreative != null) return userCreative
        return if (calibratedFallbackEnabled) calibratedFallbackCandidate() else null
    }

    fun resolveRecordCandidate(
        isSdrRecordDelivery: Boolean,
        recordDevelopLightMaster: Boolean,
        fullColorCandidate: Pair<FloatArray, Int>?,
        userCreativeLutEnabled: Boolean,
        userCreativeCandidate: Pair<FloatArray, Int>?,
        calibratedFallbackEnabled: Boolean,
        calibratedFallbackCandidate: Pair<FloatArray, Int>?
    ): Pair<FloatArray, Int>? {
        return resolveRecordCandidateLazy(
            isSdrRecordDelivery,
            recordDevelopLightMaster,
            fullColorEnabled = fullColorCandidate != null,
            userCreativeLutEnabled,
            calibratedFallbackEnabled,
            fullColorCandidate = { fullColorCandidate },
            userCreativeCandidate = { userCreativeCandidate },
            calibratedFallbackCandidate = { calibratedFallbackCandidate }
        )
    }

    fun resolvePreviewCandidate(
        isSdrVideoDelivery: Boolean,
        previewColorParityEnabled: Boolean,
        recordCandidate: Pair<FloatArray, Int>?,
        previewCandidate: Pair<FloatArray, Int>?
    ): Pair<FloatArray, Int>? {
        if (!isSdrVideoDelivery || !previewColorParityEnabled) return null

        // The file's cube is authoritative for WYSIWYG. The preview-only candidate remains useful for
        // record-then-develop monitoring, where the intentionally flat live master owns no record cube.
        return recordCandidate ?: previewCandidate
    }
}
