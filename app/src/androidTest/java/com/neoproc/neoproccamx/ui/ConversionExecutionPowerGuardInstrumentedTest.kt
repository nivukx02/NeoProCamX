package com.neoproc.neoproccamx.ui

import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.neoproc.neoproccamx.recording.MediaConversionForegroundService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversionExecutionPowerGuardInstrumentedTest {
    @Test
    fun niv99ConversionLeaseIsHeldOnlyInsideProtectedWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val powerManager = requireNotNull(context.getSystemService(PowerManager::class.java))
        assertTrue(powerManager.isWakeLockLevelSupported(PowerManager.PARTIAL_WAKE_LOCK))
        val guard = ConversionExecutionPowerGuard(context)

        guard.run {
            assertTrue(
                "Foreground media-conversion service was not registered as active",
                MediaConversionForegroundService.hasActiveLeaseForTest()
            )
        }

        assertFalse(MediaConversionForegroundService.hasActiveLeaseForTest())
    }
}
