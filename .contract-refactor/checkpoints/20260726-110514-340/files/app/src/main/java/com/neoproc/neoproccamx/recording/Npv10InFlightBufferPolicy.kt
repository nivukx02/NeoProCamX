package com.neoproc.neoproccamx.recording

/** Bounds full-resolution direct buffers without changing a single recorded pixel or packet. */
internal object Npv10InFlightBufferPolicy {
    const val MIN_PENDING_FRAMES = 3
    const val MAX_PENDING_FRAMES = 12
    const val MAX_DIRECT_BUFFER_BYTES = 192L * 1024L * 1024L
    private const val READER_RESERVED_IMAGES = 2

    fun pendingFrames(width: Int, height: Int): Int {
        val decodedBytes = Npv10ContainerPolicy.estimatedPayloadBytesPerFrame(
            width,
            height,
            Npv10PayloadFormat.P010_LE
        )
        val encodedBytes = Npv10PacketCodecEngine.storedBytes(
            decodedBytes,
            Npv10PacketCodec.PACKED_P010_10_LE_V1
        )
        val copyBudget = (MAX_DIRECT_BUFFER_BYTES - encodedBytes).coerceAtLeast(decodedBytes)
        return (copyBudget / decodedBytes.coerceAtLeast(1L)).toInt()
            .coerceIn(MIN_PENDING_FRAMES, MAX_PENDING_FRAMES)
    }

    fun readerMaxImages(width: Int, height: Int): Int =
        pendingFrames(width, height) + READER_RESERVED_IMAGES
}
