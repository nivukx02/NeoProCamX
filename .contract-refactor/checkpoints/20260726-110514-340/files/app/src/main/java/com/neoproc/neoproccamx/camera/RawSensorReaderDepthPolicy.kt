package com.neoproc.neoproccamx.camera

internal data class RawSensorReaderDepthPlan(
    val maxImages: Int,
    val estimatedFrameBytes: Long,
    val estimatedReaderBytes: Long,
    val reason: String
)

internal object RawSensorReaderDepthPolicy {
    private const val RAW_CONTAINER_BYTES_PER_PIXEL = 2L
    private const val TWELVE_MEGAPIXELS = 13_000_000L
    private const val THIRTY_THREE_MEGAPIXELS = 33_000_000L

    // MAXIMUM_SENSOR (un-binned) RAW frames are enormous — a 100MP frame is ~200 MB at 2 bytes/px, so
    // even a shallow queue is heavy. Clamp the queue hard above this to bound reader RAM.
    private const val HUNDRED_MEGAPIXELS = 100_000_000L

    fun resolve(
        rawSize: CameraStreamSize,
        recordingActive: Boolean
    ): RawSensorReaderDepthPlan {
        val frameBytes = rawSize.area * RAW_CONTAINER_BYTES_PER_PIXEL
        val maxImages = when {
            recordingActive && rawSize.area <= TWELVE_MEGAPIXELS -> 6
            recordingActive && rawSize.area <= THIRTY_THREE_MEGAPIXELS -> 4
            recordingActive && rawSize.area <= HUNDRED_MEGAPIXELS -> 3
            recordingActive -> 2
            rawSize.area <= TWELVE_MEGAPIXELS -> 4
            rawSize.area <= THIRTY_THREE_MEGAPIXELS -> 3
            else -> 2
        }
        return RawSensorReaderDepthPlan(
            maxImages = maxImages,
            estimatedFrameBytes = frameBytes,
            estimatedReaderBytes = frameBytes * maxImages.toLong(),
            reason = reasonFor(rawSize, recordingActive)
        )
    }

    private fun reasonFor(rawSize: CameraStreamSize, recordingActive: Boolean): String =
        when {
            recordingActive && rawSize.area > THIRTY_THREE_MEGAPIXELS ->
                "ultra-high-resolution RAW recording memory guard"
            recordingActive ->
                "sustained RAW recording queue"
            rawSize.area > THIRTY_THREE_MEGAPIXELS ->
                "ultra-high-resolution RAW still companion memory guard"
            else ->
                "RAW still companion queue"
        }
}
