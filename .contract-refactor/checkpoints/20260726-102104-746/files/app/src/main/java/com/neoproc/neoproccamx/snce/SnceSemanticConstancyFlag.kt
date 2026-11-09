package com.neoproc.neoproccamx.snce

/**
 * s7-f04 / CP4A — process-global gate for SEMANTIC-REGION colour constancy.
 *
 * DEFAULT OFF. Independent of [SnceColorEngineFlag] (which gates the whole SNCE engine and is ON
 * by default). While this flag is false, [SnceLiveColorController] applies ZERO region nudge and the
 * preview/encoder grade is bit-for-bit identical to today's output — the no-downgrade default.
 *
 * Flipping it on is the device-gated step (vivo / Adreno): only then does the mask-derived per-region
 * WB nudge (skin / sky / foliage) reach pixels, still bounded by the existing ±0.05 WB clamp.
 */
object SnceSemanticConstancyFlag {
    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
