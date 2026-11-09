package com.neoproc.neoproccamx.ui.controls

import android.util.Range

internal object ManualIsoRangePolicy {

    fun supportedValues(hardwareRange: Range<Int>): List<Int> =
        supportedValues(hardwareRange.lower, hardwareRange.upper)

    fun supportedValues(lowerIso: Int, upperIso: Int): List<Int> {
        val lower = lowerIso.coerceAtMost(upperIso)
        val upper = upperIso.coerceAtLeast(lowerIso)
        val values = ManualControlValueMapper.isoValues
            .filter { it in lower..upper }
            .toMutableList()

        values.add(lower)
        values.add(upper)

        return values
            .distinct()
            .sorted()
    }

    fun isoForProgress(progress: Int, supportedValues: List<Int>): Int {
        if (supportedValues.isEmpty()) return ManualControlValueMapper.nativeIsoRange.first
        return supportedValues[progress.coerceIn(0, supportedValues.lastIndex)]
    }

    fun progressForIso(iso: Int, supportedValues: List<Int>): Int {
        if (supportedValues.isEmpty()) return 0
        return supportedValues.indexOfFirst { it >= iso }.takeIf { it >= 0 } ?: supportedValues.lastIndex
    }
}
