package com.neoproc.neoproccamx.ui.overlays

import android.graphics.Typeface
import android.os.Build

internal data class ProOverlayFontSpec(
    val familyName: String,
    val weight: Int
)

internal object ProOverlayTypographyPolicy {
    private const val MEDIUM_WEIGHT = 500
    private const val BOLD_WEIGHT = 700

    fun labelSpec(): ProOverlayFontSpec =
        ProOverlayFontSpec(familyName = "sans-serif", weight = MEDIUM_WEIGHT)

    fun valueSpec(): ProOverlayFontSpec =
        ProOverlayFontSpec(familyName = "sans-serif", weight = BOLD_WEIGHT)

    fun recordTimeSpec(): ProOverlayFontSpec =
        ProOverlayFontSpec(familyName = "sans-serif", weight = BOLD_WEIGHT)

    fun createTypeface(spec: ProOverlayFontSpec): Typeface {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(Typeface.create(spec.familyName, Typeface.NORMAL), spec.weight, false)
        } else {
            val family = if (spec.weight >= BOLD_WEIGHT) "sans-serif" else "sans-serif-medium"
            val style = if (spec.weight >= BOLD_WEIGHT) Typeface.BOLD else Typeface.NORMAL
            Typeface.create(family, style)
        }
    }
}
