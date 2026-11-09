package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.ai.DepthData
import com.neoproc.neoproccamx.ai.EnhancementResult
import com.neoproc.neoproccamx.ai.SceneType
import com.neoproc.neoproccamx.ai.SegmentationData
import com.neoproc.neoproccamx.ai.TrackedFace
import com.neoproc.neoproccamx.ai.TrackedPose
import com.neoproc.neoproccamx.ai.TrackedObject
import com.neoproc.neoproccamx.pipeline.AICameraProcessingGraph.NodeId
import com.neoproc.neoproccamx.pipeline.AICameraProcessingGraph.ScheduledNode
import java.util.concurrent.Future

internal object AICameraStageNodeBuilders {
    fun face(future: Future<List<TrackedFace>>?, timeoutMs: Long, maxStaleMs: Long, cancelOnTimeout: Boolean = false) =
        ScheduledNode(NodeId.FACE, future, timeoutMs, maxStaleMs, cancelOnTimeout)

    fun pose(future: Future<TrackedPose?>?, timeoutMs: Long, maxStaleMs: Long, cancelOnTimeout: Boolean = false) =
        ScheduledNode(NodeId.POSE, future, timeoutMs, maxStaleMs, cancelOnTimeout)

    fun segmentation(future: Future<SegmentationData?>?, timeoutMs: Long, maxStaleMs: Long, cancelOnTimeout: Boolean = false) =
        ScheduledNode(NodeId.SEGMENTATION, future, timeoutMs, maxStaleMs, cancelOnTimeout)

    fun depth(future: Future<DepthData?>?, timeoutMs: Long, maxStaleMs: Long, cancelOnTimeout: Boolean = false) =
        ScheduledNode(NodeId.DEPTH, future, timeoutMs, maxStaleMs, cancelOnTimeout)

    fun scene(future: Future<SceneType>?, timeoutMs: Long, maxStaleMs: Long, cancelOnTimeout: Boolean = false) =
        ScheduledNode(NodeId.SCENE, future, timeoutMs, maxStaleMs, cancelOnTimeout)

    fun enhancement(future: Future<EnhancementResult?>?, timeoutMs: Long, maxStaleMs: Long, cancelOnTimeout: Boolean = false) =
        ScheduledNode(NodeId.ENHANCEMENT, future, timeoutMs, maxStaleMs, cancelOnTimeout)

    fun objectTracker(future: Future<List<TrackedObject>>?, timeoutMs: Long, maxStaleMs: Long, cancelOnTimeout: Boolean = false) =
        ScheduledNode(NodeId.OBJECT, future, timeoutMs, maxStaleMs, cancelOnTimeout)
}
