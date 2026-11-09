package com.neoproc.neoproccamx.recording

/**
 * Defines who owns deletion of a converted MOV destination.
 *
 * A direct MediaStore export is exposed to a file writer through `/proc/self/fd/<fd>`. That path is a
 * borrowed view of a caller-owned descriptor, not a filesystem entry a converter can unlink. The
 * MediaStore transaction owns rollback in that case. Regular staged files retain strict converter and
 * publication-gate cleanup.
 */
enum class MovOutputOwnership {
    OWNED_REGULAR_FILE,
    BORROWED_SEEKABLE_DESCRIPTOR;

    val converterMayDeleteOutput: Boolean
        get() = this == OWNED_REGULAR_FILE
}
