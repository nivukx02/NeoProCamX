package com.neoproc.neoproccamx.rendering

import android.util.Log

/** Owns the bounded, lease-tracked readback buffers used only by the live RAW monitor. */
internal class RawDebayerPreviewPresenter(
    private val vulkanRuntime: VulkanRuntimeManager,
    private val compositor: PreviewGlCompositor
) {
    private val bufferPool = RawPreviewBufferPool(BUFFER_COUNT, MAX_PREVIEW_BYTES)
    private var logCounter = 0

    fun present(presentationEpoch: Long, sensorTimestampNs: Long) {
        val running = compositor.isRunning()
        if (!running || presentationEpoch < 0L || sensorTimestampNs <= 0L) return
        val buffer = bufferPool.acquire()
        if (buffer == null) {
            if ((logCounter++) % LOG_INTERVAL == 0) {
                Log.w(
                    TAG,
                    "RAW debayer present skipped: all $BUFFER_COUNT immutable upload leases are busy"
                )
            }
            return
        }
        var ownershipTransferred = false
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
            val requiredBytes = RawPreviewFrameLease.checkedRgba8ByteCount(width, height)
            if (requiredBytes != null && requiredBytes <= buffer.capacity()) {
                val frame = RawPreviewFrameLease(
                    pixels = buffer,
                    width = width,
                    height = height,
                    presentationEpoch = presentationEpoch,
                    sensorTimestampNs = sensorTimestampNs,
                    recycle = bufferPool::recycle
                )
                ownershipTransferred = true
                runCatching { compositor.presentDebayerRgba8(frame) }
                    .onFailure {
                        frame.close()
                        Log.e(TAG, "RAW debayer frame publication failed", it)
                    }
            } else if (requiredBytes != null) {
                Log.e(
                    TAG,
                    "RAW debayer frame rejected: ${width}x${height} needs=$requiredBytes " +
                        "capacity=${buffer.capacity()}"
                )
            }
        }
        if (!ownershipTransferred) bufferPool.recycle(buffer)
    }

    private companion object {
        const val TAG = "CameraViewModel"
        const val BUFFER_COUNT = 4
        const val MAX_PREVIEW_BYTES = 1024 * 1024 * 4
        const val LOG_INTERVAL = 60
    }
}
