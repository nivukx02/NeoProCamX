package com.neoproc.neoproccamx.camera

/** Selects the Camera2 session carrier for proprietary sensor-linear NDR/NIVUKX capture. */
internal object NdrCaptureTransportPolicy {

    @Suppress("UNUSED_PARAMETER")
    fun resolve(
        manufacturer: String?,
        model: String?,
        device: String?,
        product: String?,
        hdr10Supported: Boolean,
        hdr10PlusSupported: Boolean,
        hlg10Supported: Boolean
    ): HdrDynamicRangeMode = HdrDynamicRangeMode.SDR
}
