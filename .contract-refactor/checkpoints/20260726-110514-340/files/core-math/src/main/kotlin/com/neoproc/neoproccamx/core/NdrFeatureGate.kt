package com.neoproc.neoproccamx.core

/**
 * Feature gate for Neo Dynamic Range (NDR) processing and UI features.
 * Controls the availability of NDR presets, NDR settings, and NDR processing pipelines.
 */
object NdrFeatureGate {
    /**
     * Controls whether NDR features are enabled. NDR, NDR10+ and NIVUKX Vision are shipping
     * NeoProCamX modes, so the product default must keep their processing and UI available.
     * The mutable gate remains only as a test/debug emergency switch.
     */
    @Volatile
    var enabled: Boolean = true
}
