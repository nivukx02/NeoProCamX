package com.neoproc.neoproccamx.ai

/**
 * s7-f05 / CP95 — process-global gate for pose face<->body handoff hardening.
 *
 * DEFAULT ON (user-directed activation 2026-07-03; the OFF path below stays bit-for-bit legacy and
 * golden-locked; the perceptual benefit is device-validated separately).
 * While [enabled] is false the FocusEngine pose (BODY) candidate is produced
 * exactly as before (no-downgrade, bit-for-bit). When flipped on (device-side, by the owner),
 * the pose candidate's focus point is routed through [PoseFaceHandoffPolicy]: a continuity-
 * bounded face->body anchor handoff, low-confidence keypoint gating on the body centroid, and
 * a pure jitter-smoothing step.
 *
 * Mirrors snce/SnceColorEngineFlag so it can be flipped at runtime (debug command receiver).
 * No persistence; resets to OFF on process start.
 */
internal object PoseHandoffFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }

    /**
     * Declared AI-stage drop order under load (most-droppable first). The live scheduler in
     * AICameraPipeline realises this via separate executors/strides and cannot be exercised in a
     * pure JVM test; this constant pins the CONTRACTED intent (depth/segmentation drop before
     * pose; pose drops before face) so a regression in the documented order is visible. The real
     * runtime order is DEVICE-GATED.
     */
    val AI_STAGE_DROP_ORDER: List<String> =
        listOf("DEPTH", "SEGMENTATION", "POSE", "FACE")
}
