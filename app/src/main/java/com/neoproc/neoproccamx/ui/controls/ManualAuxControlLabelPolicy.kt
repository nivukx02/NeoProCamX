package com.neoproc.neoproccamx.ui.controls

internal object ManualAuxControlLabelPolicy {
    fun meteringLabel(enabled: Boolean): String = "Meter"

    fun meteringLabel(mode: CinemaManualMeteringMode): String = when (mode) {
        CinemaManualMeteringMode.MATRIX -> "Meter"
        CinemaManualMeteringMode.CENTER_WEIGHTED -> "Center"
        CinemaManualMeteringMode.SPOT -> "Spot"
    }
}
