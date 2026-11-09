package com.neoproc.neoproccamx.ai

/**
 * AF-SD (F10) — process-global gate for (a) emitting the [SubjectType.HEAD] focus target for a
 * profile/averted face whose eyes are unusable, and (b) consuming the authoritative ObjectTracker
 * [SubjectCategory] (fixing bird -> ANIMAL and horse/etc -> ANIMAL instead of the drifted inline
 * className map).
 *
 * DEFAULT ON (user-directed activation 2026-07-03; the OFF path below stays bit-for-bit legacy and
 * golden-locked; the perceptual benefit is device-validated separately).
 * While [enabled] is false, no HEAD subject is ever constructed and the object category
 * is derived from the legacy inline className map, so FocusEngine output is bit-for-bit the legacy
 * path. When flipped on (device-side by the owner), an averted face resolves to HEAD (priority
 * between FACE and BODY) and object categorization matches ObjectTracker. No persistence; resets to
 * OFF on process start. Mirrors [PoseHandoffFlag].
 */
internal object HeadSubjectFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
