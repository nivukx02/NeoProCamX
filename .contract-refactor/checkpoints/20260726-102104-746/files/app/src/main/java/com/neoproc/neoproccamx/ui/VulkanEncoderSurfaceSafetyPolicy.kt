package com.neoproc.neoproccamx.ui

internal object VulkanEncoderSurfaceSafetyPolicy {
    private const val VIVO_I2405_PIPELINE_ANR =
        "device-vivo-i2405-vulkan-encoder-surface-pipeline-anr"

    fun blockingReason(
        manufacturer: String?,
        model: String?,
        device: String?,
        product: String?
    ): String? {
        val normalizedManufacturer = manufacturer.normalizeDeviceToken()
        val deviceTokens = listOf(model, device, product).map { it.normalizeDeviceToken() }
        val vivoDevice = normalizedManufacturer.contains("vivo")
        val i2405Family = deviceTokens.any { it == "i2405" || it == "i2405i" }
        return if (vivoDevice && i2405Family) VIVO_I2405_PIPELINE_ANR else null
    }

    private fun String?.normalizeDeviceToken(): String =
        orEmpty().trim().lowercase()
}
