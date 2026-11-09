package com.neoproc.neoproccamx.camera

/**
 * Pure, fail-closed proof that the exact P010 hardware-import reader belongs to the currently
 * authoritative Camera2 session.
 *
 * A prepared codec surface or an allocated [android.media.ImageReader] is not sufficient evidence:
 * Camera2 can only target a Surface that was part of the session's configured output set. Keeping
 * this decision pure makes every stale-session, stale-reader, format and geometry boundary directly
 * testable without camera hardware.
 */
internal object HardwareImportP010SessionMembershipPolicy {
    data class Input(
        val platformSupportsP010: Boolean,
        val internalHardwareImportEnabled: Boolean,
        val p010SourceEnabled: Boolean,
        val sessionRefreshPending: Boolean,
        val expectedWidth: Int,
        val expectedHeight: Int,
        val readerWidth: Int?,
        val readerHeight: Int?,
        val readerIsP010: Boolean,
        val readerSurfaceRegistered: Boolean,
        val sessionStillCurrent: Boolean,
        val readerStillCurrent: Boolean
    )

    fun isExactCurrentSessionMember(input: Input): Boolean =
        input.platformSupportsP010 &&
            input.internalHardwareImportEnabled &&
            input.p010SourceEnabled &&
            !input.sessionRefreshPending &&
            input.expectedWidth > 0 &&
            input.expectedHeight > 0 &&
            input.readerWidth == input.expectedWidth &&
            input.readerHeight == input.expectedHeight &&
            input.readerIsP010 &&
            input.readerSurfaceRegistered &&
            input.sessionStillCurrent &&
            input.readerStillCurrent
}
