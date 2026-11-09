package com.neoproc.neoproccamx.ui

/**
 * Product-facing terminology boundary.
 *
 * The camera, recording, metadata, conversion and compatibility contracts intentionally retain
 * their established NDR tokens. Only text crossing into a visible UI surface is renamed to XDR,
 * preventing a cosmetic product-name change from altering persisted files or pipeline routing.
 */
internal object UiTechnologyNamePolicy {
    private val ndr10Plus = Regex("NDR10(?:\\+|[\\s_-]*PLUS)", RegexOption.IGNORE_CASE)
    private val ndr10Token = Regex("NDR10(?=[_\\s-]|$)", RegexOption.IGNORE_CASE)
    private val ndrWord = Regex("\\bNDR\\b", RegexOption.IGNORE_CASE)

    fun present(value: CharSequence?): String {
        val source = value?.toString().orEmpty()
        return source
            .replace(ndr10Plus, "XDR10+")
            .replace(ndr10Token, "XDR10")
            .replace(ndrWord, "XDR")
    }
}
