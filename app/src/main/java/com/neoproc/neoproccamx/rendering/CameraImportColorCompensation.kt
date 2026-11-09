package com.neoproc.neoproccamx.rendering

internal data class CameraImportColorCompensation(
    val redScale: Float = 1f,
    val greenScale: Float = 1f,
    val blueScale: Float = 1f,
    val blendStrength: Float = 0f
) {
    fun attenuate(amount: Float): CameraImportColorCompensation {
        val factor = amount.coerceIn(0f, 1f)
        return CameraImportColorCompensation(
            redScale = 1f + (redScale - 1f) * factor,
            greenScale = 1f + (greenScale - 1f) * factor,
            blueScale = 1f + (blueScale - 1f) * factor,
            blendStrength = blendStrength * factor
        )
    }
}

internal object CameraImportColorCompensationPolicy {
    fun resolve(deviceModel: String?): CameraImportColorCompensation =
        when (deviceModel?.trim()?.uppercase()) {
            // Do not stack an arbitrary RGB gain on top of the HAL AWB/CCM. HDR/LOG/RAW bypass this
            // path already; keeping the shared import neutral also protects SDR from a cyan cast.
            // Device correction belongs in the characterized YUV matrix/range stage.
            "I2405" -> CameraImportColorCompensation()
            else -> CameraImportColorCompensation()
        }
}
