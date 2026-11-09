package com.neoproc.neoproccamx.recording

import androidx.annotation.Keep
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Explicitly released native memory used for large offline-export frames. */
internal class Npv10NativeFrameBuffer private constructor(
    val buffer: ByteBuffer
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        Npv10NativeFrameBufferAllocator.release(buffer)
    }

    companion object {
        fun allocate(capacity: Int): Npv10NativeFrameBuffer {
            require(capacity > 0) { "Native frame-buffer capacity must be positive" }
            val buffer = Npv10NativeFrameBufferAllocator.allocate(capacity)
                ?: throw OutOfMemoryError("Unable to allocate $capacity bytes of native export memory")
            require(buffer.isDirect && buffer.capacity() >= capacity) {
                Npv10NativeFrameBufferAllocator.release(buffer)
                "Native export allocator returned an invalid direct buffer"
            }
            buffer.clear()
            buffer.limit(capacity)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            return Npv10NativeFrameBuffer(buffer)
        }
    }
}

@Keep
private object Npv10NativeFrameBufferAllocator {
    private const val NATIVE_LIBRARY = "neoproc_vulkan"

    private val available: Boolean by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching {
            System.loadLibrary(NATIVE_LIBRARY)
            true
        }.getOrDefault(false)
    }

    fun allocate(capacity: Int): ByteBuffer? {
        check(available) { "Native export-buffer allocator is unavailable" }
        return nativeAllocate(capacity.toLong())
    }

    fun release(buffer: ByteBuffer) {
        if (!available) return
        nativeRelease(buffer)
    }

    @JvmStatic
    private external fun nativeAllocate(capacity: Long): ByteBuffer?

    @JvmStatic
    private external fun nativeRelease(buffer: ByteBuffer)
}
