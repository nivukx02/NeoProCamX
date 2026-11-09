package com.neoproc.neoproccamx.camera

import android.util.Size
import android.view.Surface

/**
 * Policy for deciding whether the active session must be rebuilt.
 */
object CameraSessionRefreshPolicy {
    fun shouldRebuildSession(
        rebuildReaders: Boolean,
        hasActiveSession: Boolean,
        configuredSurfaces: Set<Surface>,
        targetSurfaces: Set<Surface>
    ): Boolean {
        if (!hasActiveSession) return true
        if (rebuildReaders) return true
        if (configuredSurfaces.isEmpty() || targetSurfaces.isEmpty()) return true
        return configuredSurfaces != targetSurfaces
    }

    fun shouldRebuildReaders(
        jpegReaderRequested: Boolean,
        currentJpegSize: Size?,
        targetJpegSize: Size?,
        rawReaderRequested: Boolean,
        currentRawSize: Size?,
        targetRawSize: Size?,
        currentYuvSize: Size?,
        targetYuvSize: Size?,
        currentHardwareImportSize: Size?,
        targetHardwareImportSize: Size?
    ): Boolean {
        if (jpegReaderRequested != (currentJpegSize != null)) return true
        if (jpegReaderRequested && !sizesMatch(currentJpegSize, targetJpegSize)) return true

        if (rawReaderRequested != (currentRawSize != null)) return true
        if (rawReaderRequested && !sizesMatch(currentRawSize, targetRawSize)) return true

        if (!sizesMatch(currentYuvSize, targetYuvSize)) return true
        if (!sizesMatch(currentHardwareImportSize, targetHardwareImportSize)) return true

        return false
    }

    private fun sizesMatch(current: Size?, target: Size?): Boolean {
        if (current == null || target == null) return current == null && target == null
        return current.width == target.width && current.height == target.height
    }
}
