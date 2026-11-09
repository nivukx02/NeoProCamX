package com.neoproc.neoproccamx.ui

internal object VulkanEncoderSurfaceSafetyPolicy {
    /**
     * Permanent model denylists are intentionally empty.
     *
     * Offscreen-EGL safety is qualified by the current app-version/OS fingerprint and the measured
     * throughput circuit breaker in [CameraViewModel]. That path fails closed after a real setup or
     * cadence failure, but automatically re-probes after an app fix or GPU-driver OTA. A static I2405
     * block prevented the repaired pipeline from ever being validated and could never self-heal.
     */
    private val permanentBlocks: Map<String, String> = emptyMap()

    fun blockingReason(
        manufacturer: String?,
        model: String?,
        device: String?,
        product: String?
    ): String? {
        val identity = listOf(manufacturer, model, device, product)
            .joinToString(separator = ":") { it.normalizeDeviceToken() }
        return permanentBlocks[identity]
    }

    private fun String?.normalizeDeviceToken(): String =
        orEmpty().trim().lowercase()
}
