package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareImportP010SessionMembershipPolicyTest {
    @Test
    fun `exact settled P010 reader in current session is accepted`() {
        assertTrue(decide())
    }

    @Test
    fun `unsupported disabled and in-flight graphs fail closed`() {
        assertFalse(decide(platformSupportsP010 = false))
        assertFalse(decide(internalHardwareImportEnabled = false))
        assertFalse(decide(p010SourceEnabled = false))
        assertFalse(decide(sessionRefreshPending = true))
    }

    @Test
    fun `wrong reader geometry or precision fails closed`() {
        assertFalse(decide(readerWidth = 1920))
        assertFalse(decide(readerHeight = 1080))
        assertFalse(decide(readerIsP010 = false))
        assertFalse(decide(expectedWidth = 0))
        assertFalse(decide(expectedHeight = 0))
    }

    @Test
    fun `unregistered or stale owners fail closed`() {
        assertFalse(decide(readerSurfaceRegistered = false))
        assertFalse(decide(sessionStillCurrent = false))
        assertFalse(decide(readerStillCurrent = false))
        assertFalse(decide(readerWidth = null))
        assertFalse(decide(readerHeight = null))
    }

    private fun decide(
        platformSupportsP010: Boolean = true,
        internalHardwareImportEnabled: Boolean = true,
        p010SourceEnabled: Boolean = true,
        sessionRefreshPending: Boolean = false,
        expectedWidth: Int = 3840,
        expectedHeight: Int = 2160,
        readerWidth: Int? = 3840,
        readerHeight: Int? = 2160,
        readerIsP010: Boolean = true,
        readerSurfaceRegistered: Boolean = true,
        sessionStillCurrent: Boolean = true,
        readerStillCurrent: Boolean = true
    ): Boolean = HardwareImportP010SessionMembershipPolicy.isExactCurrentSessionMember(
        HardwareImportP010SessionMembershipPolicy.Input(
            platformSupportsP010 = platformSupportsP010,
            internalHardwareImportEnabled = internalHardwareImportEnabled,
            p010SourceEnabled = p010SourceEnabled,
            sessionRefreshPending = sessionRefreshPending,
            expectedWidth = expectedWidth,
            expectedHeight = expectedHeight,
            readerWidth = readerWidth,
            readerHeight = readerHeight,
            readerIsP010 = readerIsP010,
            readerSurfaceRegistered = readerSurfaceRegistered,
            sessionStillCurrent = sessionStillCurrent,
            readerStillCurrent = readerStillCurrent
        )
    )
}
