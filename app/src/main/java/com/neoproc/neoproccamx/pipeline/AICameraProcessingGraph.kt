package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.Future

/**
 * First-step DAG abstraction for AI pipeline stages.
 *
 * This keeps node dependencies explicit and allows future hot-swappable stage implementations
 * without changing frame orchestration call sites.
 */
internal object AICameraProcessingGraph {
    internal enum class NodeId {
        FACE,
        POSE,
        SEGMENTATION,
        DEPTH,
        SCENE,
        ENHANCEMENT,
        OBJECT
    }

    internal data class ScheduledNode<T>(
        val id: NodeId,
        val future: Future<T>?,
        val timeoutMs: Long,
        val maxStaleMs: Long,
        val cancelOnTimeout: Boolean = false
    )
}
