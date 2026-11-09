package com.neoproc.neoproccamx.niv99.android

import android.graphics.ImageFormat
import android.media.Image
import android.os.Build
import androidx.annotation.RequiresApi
import com.neoproc.neoproccamx.niv99.Niv99CfaPattern
import com.neoproc.neoproccamx.niv99.Niv99LinearPlane
import com.neoproc.neoproccamx.niv99.Niv99P010PlaneDescriptor
import com.neoproc.neoproccamx.niv99.Niv99PackedEndian
import com.neoproc.neoproccamx.niv99.Niv99PlaneCopyStatus
import com.neoproc.neoproccamx.niv99.Niv99Raw16PlaneAdapter

/** Android Image bindings. Ownership and closing of [Image] always remain with the caller. */
object Niv99AndroidPlaneAdapter {
    fun copyRawSensorToFloat(
        image: Image,
        cfaPattern: Niv99CfaPattern,
        whiteLevel: Float,
        workspace: FloatArray,
        output: Niv99LinearPlane
    ): Niv99PlaneCopyStatus {
        if (image.format != ImageFormat.RAW_SENSOR || image.planes.isEmpty()) {
            return Niv99PlaneCopyStatus.UNSUPPORTED_PIXEL_LAYOUT
        }
        val plane = image.planes[0]
        val status = Niv99Raw16PlaneAdapter.copyUnsignedRaw16(
            input = plane.buffer,
            inputOffsetBytes = plane.buffer.position(),
            width = image.width,
            height = image.height,
            rowStrideBytes = plane.rowStride,
            pixelStrideBytes = plane.pixelStride,
            endian = Niv99PackedEndian.LITTLE_ENDIAN,
            output = workspace,
            outputOffset = 0,
            outputRowStride = image.width
        )
        if (status == Niv99PlaneCopyStatus.VALID) {
            output.set(
                samples = workspace,
                width = image.width,
                height = image.height,
                rowStride = image.width,
                offset = 0,
                whiteLevel = whiteLevel,
                cfaPattern = cfaPattern
            )
        }
        return status
    }

    @RequiresApi(Build.VERSION_CODES.S)
    fun bindP010(
        image: Image,
        output: Niv99P010PlaneDescriptor
    ): Niv99PlaneCopyStatus {
        if (image.format != ImageFormat.YCBCR_P010 || image.planes.size < 2) {
            return Niv99PlaneCopyStatus.UNSUPPORTED_PIXEL_LAYOUT
        }
        val luma = image.planes[0]
        val chroma = image.planes[1]
        output.set(
            luma = luma.buffer,
            chroma = chroma.buffer,
            width = image.width,
            height = image.height,
            lumaRowStrideBytes = luma.rowStride,
            chromaRowStrideBytes = chroma.rowStride,
            lumaPixelStrideBytes = luma.pixelStride,
            chromaPixelStrideBytes = chroma.pixelStride,
            bitDepth = 10
        )
        return output.validate()
    }
}
