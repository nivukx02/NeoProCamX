package com.neoproc.neoproccamx.ui

/**
 * Camera2 owns transport geometry; the project-owned sensor-linear NDR renderer owns its output
 * color space. A color-space-only change must therefore not build an intermediate RAW Camera2 graph.
 */
internal object VideoColorSpaceCameraSessionPolicy {
    fun shouldMutateCameraSession(sensorLinearNdrActive: Boolean): Boolean =
        !sensorLinearNdrActive
}
