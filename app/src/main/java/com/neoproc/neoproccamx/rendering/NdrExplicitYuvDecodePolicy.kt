package com.neoproc.neoproccamx.rendering

import android.os.Build

/**
 * Selects the raw [GL_EXT_YUV_target] import only on devices where that path is trustworthy.
 *
 * vivo I2405 advertises the extension and links the shader, but on-device A/B validation shows that
 * its P010 Y/Cb/Cr samples do not follow the advertised code-value contract: the explicit path creates
 * clipped luminance plus cyan/magenta edge colour. The ordinary external-texture sampler on the same
 * hardware is neutral and keeps the source PQ/BT.2020 precision through the vendor's accelerated CSC.
 * Keep the standards-owned explicit implementation available for conforming devices, and route this
 * measured device through the lossless hardware CSC for both preview and recording.
 */
internal object NdrExplicitYuvDecodePolicy {

    fun isAllowed(
        manufacturer: String?,
        model: String?,
        device: String?,
        product: String?
    ): Boolean {
        val vivo = manufacturer.equals("vivo", ignoreCase = true)
        val isI2405 = sequenceOf(model, device, product)
            .filterNotNull()
            .any { value ->
                value.equals("I2405", ignoreCase = true) ||
                    value.equals("I2405i", ignoreCase = true)
            }
        return !(vivo && isI2405)
    }

    fun isAllowedOnCurrentDevice(): Boolean = isAllowed(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        device = Build.DEVICE,
        product = Build.PRODUCT
    )
}
