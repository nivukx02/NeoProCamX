package com.neoproc.neoproccamx.ui

import java.util.concurrent.atomic.AtomicLong

/** Resolves one result at a time and applies it only when its input is still the newest request. */
internal class LatestWinsSerialResultCoordinator<T, R>(
    scheduleResolve: (drain: () -> Unit) -> Unit,
    private val scheduleApply: (apply: () -> Unit) -> Unit,
    private val resolve: (T) -> R,
    private val apply: (T, R) -> Unit,
    private val onFailure: (T, Throwable) -> Unit = { _, _ -> }
) {
    private data class Versioned<T>(val generation: Long, val value: T)

    private val latestGeneration = AtomicLong()
    private val work = LatestWinsSerialWorkCoordinator<Versioned<T>>(
        merge = { _, incoming -> incoming },
        schedule = scheduleResolve,
        execute = { versioned ->
            try {
                val result = resolve(versioned.value)
                scheduleApply {
                    if (versioned.generation == latestGeneration.get()) {
                        apply(versioned.value, result)
                    }
                }
            } catch (failure: Throwable) {
                onFailure(versioned.value, failure)
                throw failure
            }
        }
    )

    fun submit(value: T) {
        work.submit(Versioned(latestGeneration.incrementAndGet(), value))
    }
}

internal data class PreviewLutResolutionWork(
    val request: PreviewLutRequest,
    val previewFpsHint: Float,
    val recordingActive: Boolean,
    val reuseCachedResult: Boolean,
    val cachedResult: Pair<FloatArray, Int>?,
    val publicationSequence: Long,
    val recordRequest: SdrRecordLutResolutionRequest
)

/** Immutable recording-side inputs captured before expensive 65-cube colour resolution starts. */
internal data class SdrRecordLutResolutionRequest(
    val videoSurface: Boolean,
    val isSdrRecordDelivery: Boolean,
    val recordDevelopLightMaster: Boolean,
    val fullColorRecord: Boolean,
    val userCreativeLutEnabled: Boolean,
    val calibratedFallbackEnabled: Boolean
)

/** Preview and recorder cubes resolved from one serialized colour-configuration generation. */
internal data class ResolvedPreviewAndRecordLuts(
    val previewLut: Pair<FloatArray, Int>?,
    val recordLut: Pair<FloatArray, Int>?
)
