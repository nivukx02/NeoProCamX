package com.neoproc.neoproccamx.rendering

import android.hardware.HardwareBuffer
import android.os.Build

enum class NativeCameraBufferFormat(val nativeFormatId: Int) {
    YUV_420_888(0x23),
    P010(0x36)
}

data class NativeHardwareBufferValidation(
    val valid: Boolean,
    val reason: String,
    val width: Int,
    val height: Int,
    val layers: Int,
    val format: Int,
    val usage: Long,
    val pixelCopy: Boolean
) {
    companion object {
        fun parse(raw: String): NativeHardwareBufferValidation {
            val fields = raw
                .split(';')
                .mapNotNull { item ->
                    val index = item.indexOf('=')
                    if (index <= 0) null else item.substring(0, index) to item.substring(index + 1)
                }
                .toMap()
            return NativeHardwareBufferValidation(
                valid = fields["valid"].toBoolean(),
                reason = fields["reason"].orEmpty(),
                width = fields["width"]?.toIntOrNull() ?: 0,
                height = fields["height"]?.toIntOrNull() ?: 0,
                layers = fields["layers"]?.toIntOrNull() ?: 0,
                format = fields["format"]?.toIntOrNull() ?: 0,
                usage = fields["usage"]?.toLongOrNull() ?: 0L,
                pixelCopy = fields["pixelCopy"].toBoolean()
            )
        }
    }
}

data class NativeDualGainHardwareBufferValidation(
    val valid: Boolean,
    val reason: String,
    val path: String,
    val timestampSkewNs: Long,
    val kGain: Float,
    val motionMask: Boolean,
    val acesWorkingSpace: String,
    val pixelCopy: Boolean
) {
    companion object {
        fun parse(raw: String): NativeDualGainHardwareBufferValidation {
            val fields = raw
                .split(';')
                .mapNotNull { item ->
                    val index = item.indexOf('=')
                    if (index <= 0) null else item.substring(0, index) to item.substring(index + 1)
                }
                .toMap()
            return NativeDualGainHardwareBufferValidation(
                valid = fields["valid"].toBoolean(),
                reason = fields["reason"].orEmpty(),
                path = fields["path"].orEmpty(),
                timestampSkewNs = fields["timestampSkewNs"]?.toLongOrNull() ?: Long.MAX_VALUE,
                kGain = fields["kGain"]?.toFloatOrNull() ?: 1f,
                motionMask = fields["motionMask"].toBoolean(),
                acesWorkingSpace = fields["aces"].orEmpty(),
                pixelCopy = fields["pixelCopy"].toBoolean()
            )
        }
    }
}

object NativeCameraBridge {
    val supportedCinemaFormats: Set<NativeCameraBufferFormat> =
        setOf(NativeCameraBufferFormat.YUV_420_888, NativeCameraBufferFormat.P010)

    init {
        System.loadLibrary("neoproc_vulkan")
    }

    fun allocationSupported(
        width: Int,
        height: Int,
        format: NativeCameraBufferFormat
    ): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            nativeAllocationSupported(width, height, format.nativeFormatId)

    fun allocateHardwareBuffer(
        width: Int,
        height: Int,
        format: NativeCameraBufferFormat
    ): HardwareBuffer? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return nativeAllocateHardwareBuffer(width, height, format.nativeFormatId)
    }

    fun validateHardwareBuffer(
        hardwareBuffer: HardwareBuffer?,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedFormat: NativeCameraBufferFormat
    ): NativeHardwareBufferValidation {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hardwareBuffer == null) {
            return NativeHardwareBufferValidation(
                valid = false,
                reason = "hardwareBuffer=null",
                width = 0,
                height = 0,
                layers = 0,
                format = 0,
                usage = 0L,
                pixelCopy = false
            )
        }
        return NativeHardwareBufferValidation.parse(
            nativeValidateHardwareBuffer(
                hardwareBuffer,
                expectedWidth,
                expectedHeight,
                expectedFormat.nativeFormatId
            )
        )
    }

    fun describeHardwareBuffer(hardwareBuffer: HardwareBuffer?): String =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hardwareBuffer == null) {
            "valid=false;reason=hardwareBuffer=null;pixelCopy=false"
        } else {
            nativeDescribeHardwareBuffer(hardwareBuffer)
        }

    fun cameraSampledUsageFlags(): Long =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            0L
        } else {
            nativeCameraSampledUsageFlags()
        }

    fun validateDualGainHardwareBuffers(
        lcgHardwareBuffer: HardwareBuffer?,
        hcgHardwareBuffer: HardwareBuffer?,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedFormat: NativeCameraBufferFormat,
        lcgTimestampNs: Long,
        hcgTimestampNs: Long,
        kGain: Float,
        privateExtensionRequested: Boolean = false
    ): NativeDualGainHardwareBufferValidation {
        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            lcgHardwareBuffer == null ||
            hcgHardwareBuffer == null
        ) {
            return NativeDualGainHardwareBufferValidation(
                valid = false,
                reason = "DUAL_GAIN_HARDWARE_BUFFER_PAIR_MISSING",
                path = "EXISTING_HDR_MERGE_FALLBACK",
                timestampSkewNs = Long.MAX_VALUE,
                kGain = kGain.coerceIn(1f, 64f),
                motionMask = false,
                acesWorkingSpace = "ACESCG_LINEAR",
                pixelCopy = false
            )
        }
        return NativeDualGainHardwareBufferValidation.parse(
            nativeValidateDualGainHardwareBuffers(
                lcgHardwareBuffer,
                hcgHardwareBuffer,
                expectedWidth,
                expectedHeight,
                expectedFormat.nativeFormatId,
                lcgTimestampNs,
                hcgTimestampNs,
                kGain,
                privateExtensionRequested
            )
        )
    }

    private external fun nativeAllocationSupported(
        width: Int,
        height: Int,
        formatId: Int
    ): Boolean

    private external fun nativeAllocateHardwareBuffer(
        width: Int,
        height: Int,
        formatId: Int
    ): HardwareBuffer?

    private external fun nativeValidateHardwareBuffer(
        hardwareBuffer: HardwareBuffer,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedFormat: Int
    ): String

    private external fun nativeDescribeHardwareBuffer(
        hardwareBuffer: HardwareBuffer
    ): String

    private external fun nativeCameraSampledUsageFlags(): Long

    private external fun nativeValidateDualGainHardwareBuffers(
        lcgHardwareBuffer: HardwareBuffer,
        hcgHardwareBuffer: HardwareBuffer,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedFormat: Int,
        lcgTimestampNs: Long,
        hcgTimestampNs: Long,
        kGain: Float,
        privateExtensionRequested: Boolean
    ): String
}
