package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.ai.DepthData
import com.neoproc.neoproccamx.ai.EnhancementResult
import com.neoproc.neoproccamx.ai.SceneType
import com.neoproc.neoproccamx.ai.SegmentationData
import com.neoproc.neoproccamx.ai.TrackedFace
import com.neoproc.neoproccamx.ai.TrackedPose
import com.neoproc.neoproccamx.ai.TrackedObject
import com.neoproc.neoproccamx.pipeline.AICameraProcessingGraph.ScheduledNode
import java.util.concurrent.Future

internal class FaceStageNode(private val maxStaleMs: Long) {
    fun schedule(future: Future<List<TrackedFace>>?, timeoutMs: Long, cancelOnTimeout: Boolean = false): ScheduledNode<List<TrackedFace>> =
        AICameraStageNodeBuilders.face(future, timeoutMs, maxStaleMs, cancelOnTimeout)

    fun resolve(
        node: ScheduledNode<List<TrackedFace>>?,
        fallback: List<TrackedFace>,
        fallbackTimestampMs: Long,
        frameTimestampMs: Long,
        strictHighEnd: Boolean
    ) = AICameraStageResolver.resolve(
        future = node?.future,
        timeoutMs = node?.timeoutMs ?: 0L,
        fallback = fallback,
        fallbackTimestampMs = fallbackTimestampMs,
        frameTimestampMs = frameTimestampMs,
        maxStaleMs = node?.maxStaleMs ?: maxStaleMs,
        emptyValue = emptyList(),
        cancelOnTimeout = node?.cancelOnTimeout ?: false,
        strictHighEnd = strictHighEnd
    )
}

internal class PoseStageNode(private val maxStaleMs: Long) {
    fun schedule(future: Future<TrackedPose?>?, timeoutMs: Long, cancelOnTimeout: Boolean = false): ScheduledNode<TrackedPose?> =
        AICameraStageNodeBuilders.pose(future, timeoutMs, maxStaleMs, cancelOnTimeout)

    fun resolve(
        node: ScheduledNode<TrackedPose?>?,
        fallback: TrackedPose?,
        fallbackTimestampMs: Long,
        frameTimestampMs: Long,
        strictHighEnd: Boolean
    ) = AICameraStageResolver.resolve<TrackedPose?>(
        future = node?.future,
        timeoutMs = node?.timeoutMs ?: 0L,
        fallback = fallback,
        fallbackTimestampMs = fallbackTimestampMs,
        frameTimestampMs = frameTimestampMs,
        maxStaleMs = node?.maxStaleMs ?: maxStaleMs,
        emptyValue = null,
        cancelOnTimeout = node?.cancelOnTimeout ?: false,
        strictHighEnd = strictHighEnd
    )
}

internal class SegmentationStageNode(private val maxStaleMs: Long) {
    fun schedule(future: Future<SegmentationData?>?, timeoutMs: Long, cancelOnTimeout: Boolean = false): ScheduledNode<SegmentationData?> =
        AICameraStageNodeBuilders.segmentation(future, timeoutMs, maxStaleMs, cancelOnTimeout)

    fun resolve(
        node: ScheduledNode<SegmentationData?>?,
        fallback: SegmentationData?,
        fallbackTimestampMs: Long,
        frameTimestampMs: Long,
        strictHighEnd: Boolean
    ) = AICameraStageResolver.resolve<SegmentationData?>(
        future = node?.future,
        timeoutMs = node?.timeoutMs ?: 0L,
        fallback = fallback,
        fallbackTimestampMs = fallbackTimestampMs,
        frameTimestampMs = frameTimestampMs,
        maxStaleMs = node?.maxStaleMs ?: maxStaleMs,
        emptyValue = null,
        cancelOnTimeout = node?.cancelOnTimeout ?: false,
        strictHighEnd = strictHighEnd
    )
}

internal class DepthStageNode(private val maxStaleMs: Long) {
    fun schedule(future: Future<DepthData?>?, timeoutMs: Long, cancelOnTimeout: Boolean = false): ScheduledNode<DepthData?> =
        AICameraStageNodeBuilders.depth(future, timeoutMs, maxStaleMs, cancelOnTimeout)

    fun resolve(
        node: ScheduledNode<DepthData?>?,
        fallback: DepthData?,
        fallbackTimestampMs: Long,
        frameTimestampMs: Long,
        strictHighEnd: Boolean
    ) = AICameraStageResolver.resolve<DepthData?>(
        future = node?.future,
        timeoutMs = node?.timeoutMs ?: 0L,
        fallback = fallback,
        fallbackTimestampMs = fallbackTimestampMs,
        frameTimestampMs = frameTimestampMs,
        maxStaleMs = node?.maxStaleMs ?: maxStaleMs,
        emptyValue = null,
        cancelOnTimeout = node?.cancelOnTimeout ?: false,
        strictHighEnd = strictHighEnd
    )
}

internal class SceneStageNode(private val maxStaleMs: Long) {
    fun schedule(future: Future<SceneType>?, timeoutMs: Long, cancelOnTimeout: Boolean = false): ScheduledNode<SceneType> =
        AICameraStageNodeBuilders.scene(future, timeoutMs, maxStaleMs, cancelOnTimeout)

    fun resolve(
        node: ScheduledNode<SceneType>?,
        fallback: SceneType,
        fallbackTimestampMs: Long,
        frameTimestampMs: Long,
        strictHighEnd: Boolean
    ) = AICameraStageResolver.resolve(
        future = node?.future,
        timeoutMs = node?.timeoutMs ?: 0L,
        fallback = fallback,
        fallbackTimestampMs = fallbackTimestampMs,
        frameTimestampMs = frameTimestampMs,
        maxStaleMs = node?.maxStaleMs ?: maxStaleMs,
        emptyValue = SceneType.GENERAL,
        cancelOnTimeout = node?.cancelOnTimeout ?: false,
        strictHighEnd = strictHighEnd
    )
}

internal class EnhancementStageNode(private val maxStaleMs: Long) {
    fun schedule(future: Future<EnhancementResult?>?, timeoutMs: Long, cancelOnTimeout: Boolean = false): ScheduledNode<EnhancementResult?> =
        AICameraStageNodeBuilders.enhancement(future, timeoutMs, maxStaleMs, cancelOnTimeout)

    fun resolve(
        node: ScheduledNode<EnhancementResult?>?,
        fallback: EnhancementResult?,
        fallbackTimestampMs: Long,
        frameTimestampMs: Long,
        strictHighEnd: Boolean
    ) = AICameraStageResolver.resolve<EnhancementResult?>(
        future = node?.future,
        timeoutMs = node?.timeoutMs ?: 0L,
        fallback = fallback,
        fallbackTimestampMs = fallbackTimestampMs,
        frameTimestampMs = frameTimestampMs,
        maxStaleMs = node?.maxStaleMs ?: maxStaleMs,
        emptyValue = null,
        cancelOnTimeout = node?.cancelOnTimeout ?: false,
        strictHighEnd = strictHighEnd
    )
}

internal class ObjectStageNode(private val maxStaleMs: Long) {
    fun schedule(future: Future<List<TrackedObject>>?, timeoutMs: Long, cancelOnTimeout: Boolean = false): ScheduledNode<List<TrackedObject>> =
        AICameraStageNodeBuilders.objectTracker(future, timeoutMs, maxStaleMs, cancelOnTimeout)

    fun resolve(
        node: ScheduledNode<List<TrackedObject>>?,
        fallback: List<TrackedObject>,
        fallbackTimestampMs: Long,
        frameTimestampMs: Long,
        strictHighEnd: Boolean
    ) = AICameraStageResolver.resolve(
        future = node?.future,
        timeoutMs = node?.timeoutMs ?: 0L,
        fallback = fallback,
        fallbackTimestampMs = fallbackTimestampMs,
        frameTimestampMs = frameTimestampMs,
        maxStaleMs = node?.maxStaleMs ?: maxStaleMs,
        emptyValue = emptyList(),
        cancelOnTimeout = node?.cancelOnTimeout ?: false,
        strictHighEnd = strictHighEnd
    )
}
