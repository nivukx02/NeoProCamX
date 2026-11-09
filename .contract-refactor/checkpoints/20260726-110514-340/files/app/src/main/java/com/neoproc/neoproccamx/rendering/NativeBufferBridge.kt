package com.neoproc.neoproccamx.rendering

import android.hardware.HardwareBuffer
import android.os.Build

data class NativeBufferHandleInfo(
    val valid: Boolean,
    val reason: String,
    val width: Int,
    val height: Int,
    val layers: Int,
    val format: Int,
    val usage: Long,
    val refCount: Int,
    val pixelCopy: Boolean
) {
    companion object {
        fun parse(raw: String): NativeBufferHandleInfo {
            val fields = raw
                .split(';')
                .mapNotNull { item ->
                    val index = item.indexOf('=')
                    if (index <= 0) null else item.substring(0, index) to item.substring(index + 1)
                }
                .toMap()
            return NativeBufferHandleInfo(
                valid = fields["valid"].toBoolean(),
                reason = fields["reason"].orEmpty(),
                width = fields["width"]?.toIntOrNull() ?: 0,
                height = fields["height"]?.toIntOrNull() ?: 0,
                layers = fields["layers"]?.toIntOrNull() ?: 0,
                format = fields["format"]?.toIntOrNull() ?: 0,
                usage = fields["usage"]?.toLongOrNull() ?: 0L,
                refCount = fields["refCount"]?.toIntOrNull() ?: 0,
                pixelCopy = fields["pixelCopy"].toBoolean()
            )
        }
    }
}

object NativeBufferBridge {
    init {
        System.loadLibrary("neoproc_vulkan")
    }

    fun externalMemoryBridgeSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && nativeExternalMemoryBridgeSupported()

    fun acquireHardwareBuffer(
        hardwareBuffer: HardwareBuffer?,
        width: Int,
        height: Int,
        format: Int
    ): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hardwareBuffer == null) return 0L
        return nativeAcquireHardwareBuffer(hardwareBuffer, width, height, format)
    }

    fun retainHandle(handle: Long): Boolean =
        handle != 0L && nativeRetainHandle(handle)

    fun releaseHandle(handle: Long): Boolean =
        handle != 0L && nativeReleaseHandle(handle)

    fun describeHandle(handle: Long): NativeBufferHandleInfo =
        NativeBufferHandleInfo.parse(
            if (handle == 0L) {
                "valid=false;reason=handle=null;refCount=0;pixelCopy=false"
            } else {
                nativeDescribeHandle(handle)
            }
        )

    fun validateHardwareBuffer(
        hardwareBuffer: HardwareBuffer?,
        width: Int,
        height: Int,
        format: Int
    ): NativeBufferHandleInfo =
        NativeBufferHandleInfo.parse(
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hardwareBuffer == null) {
                "valid=false;reason=hardwareBuffer=null;refCount=0;pixelCopy=false"
            } else {
                nativeValidateHardwareBuffer(hardwareBuffer, width, height, format)
            }
        )

    private external fun nativeExternalMemoryBridgeSupported(): Boolean

    private external fun nativeAcquireHardwareBuffer(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        format: Int
    ): Long

    private external fun nativeRetainHandle(handle: Long): Boolean

    private external fun nativeReleaseHandle(handle: Long): Boolean

    private external fun nativeDescribeHandle(handle: Long): String

    private external fun nativeValidateHardwareBuffer(
        hardwareBuffer: HardwareBuffer,
        width: Int,
        height: Int,
        format: Int
    ): String
}
