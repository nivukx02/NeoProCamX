package com.neoproc.neoproccamx.rendering

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Owns the ping-pong readback buffers used only by the live RAW monitor. */
internal class RawDebayerPreviewPresenter(
    private val vulkanRuntime: VulkanRuntimeManager,
    private val compositor: PreviewGlCompositor
) {
    private var buffers: Array<ByteBuffer>? = null
    private var bufferIndex = 0
    private var logCounter = 0

    fun present() {
        val running = compositor.isRunning()
        val activeBuffers = buffers ?: Array(BUFFER_COUNT) {
            ByteBuffer.allocateDirect(MAX_PREVIEW_BYTES).order(ByteOrder.nativeOrder())
        }.also { buffers = it }
        val buffer = activeBuffers[bufferIndex]
        bufferIndex = bufferIndex xor 1
        val packedDimensions = if (running) {
            runCatching { vulkanRuntime.copyRawDemosaicPreviewInto(buffer) }.getOrDefault(0)
        } else {
            0
        }
        if ((logCounter++) % LOG_INTERVAL == 0) {
            Log.i(
                TAG,
                "RAW debayer present: compositorRunning=$running packed=$packedDimensions " +
                    "(w=${(packedDimensions ushr 16) and 0xFFFF} h=${packedDimensions and 0xFFFF})"
            )
        }
        if (running && packedDimensions != 0) {
            val width = (packedDimensions ushr 16) and 0xFFFF
            val height = packedDimensions and 0xFFFF
            if (width > 0 && height > 0) {
                compositor.presentDebayerRgba8(buffer, width, height)
            }
        }
    }

    private companion object {
        const val TAG = "CameraViewModel"
        const val BUFFER_COUNT = 2
        const val MAX_PREVIEW_BYTES = 1024 * 1024 * 4
        const val LOG_INTERVAL = 60
    }
}

