package com.neoproc.neoproccamx.snce

/**
 * Process-global toggle for the live SNCE v2.0 HVC colour engine.
 *
 * DEFAULT ON. Mirrors the shape of camera/ColorDriftEnforcementFlag so it can be flipped at
 * runtime (e.g. via the debug command receiver) and trigger a preview-grade refresh through
 * [registerOnChanged]. When disabled, [SnceLiveColorController] becomes a no-op and the
 * existing grade path is untouched.
 */
object SnceColorEngineFlag {
    @Volatile var enabled: Boolean = true
        private set

    @Volatile private var onChanged: (() -> Unit)? = null

    fun set(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (changed) onChanged?.invoke()
    }

    fun registerOnChanged(callback: (() -> Unit)?) {
        onChanged = callback
    }
}
