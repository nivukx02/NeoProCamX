package com.neoproc.neoproccamx.recording

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real platform-session lifecycle proof; recording FPS still requires the endurance test. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
class Npv10PackingWorkHintInstrumentedTest {
    @Test fun actualPlatformSessionAcceptsMeasuredWorkAndTargetChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hint = Npv10PackingWorkHintAndroid.create(context)
        try {
            assertTrue("ADPF session unavailable on this target; do not claim hint validation", hint.active)
            for (fps in listOf(25, 30, 25)) {
                hint.prepareFrame(fps)
                val started = System.nanoTime()
                var value = 0L
                repeat(50_000) { value = (value xor it.toLong()) * 31L + 7L }
                val actual = System.nanoTime() - started
                hint.reportActual(actual)
                Log.i("Npv10PackingHintQA", "ACTUAL_WORK fps=$fps ns=$actual checksum=$value")
            }
            assertTrue(hint.active)
            assertFalse(hint.backendFailed)
            assertEquals(3L, hint.reportsAccepted)
        } finally {
            hint.close()
            hint.close()
        }
        assertFalse(hint.active)
        assertFalse(hint.backendFailed)
        Log.i("Npv10PackingHintQA", "ACTUAL_PLATFORM_SESSION_PASS scope=LIFECYCLE_NOT_CAMERA_FPS")
    }
}
