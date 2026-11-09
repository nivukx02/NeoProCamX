package com.neoproc.neoproccamx.ui.overlays

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

enum class GridMode {
    NONE,
    THIRDS,
    CENTER;

    fun resolveForStrictHighEnd(): GridMode {
        val strictHighEndEnabled = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        return if (strictHighEndEnabled && this == NONE) THIRDS else this
    }
}
