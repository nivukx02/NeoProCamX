package com.neoproc.neoproccamx.ai

/**
 * AF-SD (F09) — process-global gate for the previously-dead SubjectType producers:
 *  - GROUP: when >=2 faces form a spatial cluster, their member FACE/EYE candidates are suppressed
 *    and represented by one GROUP candidate spanning the cluster (group-portrait framing).
 *  - UPPER_BODY / FULL_BODY: the pose candidate is classified from lower-body landmark visibility.
 *
 * DEFAULT ON (user-directed activation 2026-07-03; the OFF path below stays bit-for-bit legacy and
 * golden-locked; the perceptual benefit is device-validated separately).
 * While [enabled] is false, FocusEngine emits exactly the legacy FACE/EYE/BODY/OBJECT
 * set (no cluster is formed, no face is suppressed, pose stays SubjectType.BODY), so output is
 * bit-for-bit the legacy path. A dominant single face always suppresses the cluster, so enabling
 * this can never make a group steal primary from a clear single subject. No persistence; resets to
 * OFF on process start. Mirrors [PoseHandoffFlag].
 */
internal object DeadSubjectTypeFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
