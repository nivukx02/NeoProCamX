package com.neoproc.neoproccamx.ui

import android.Manifest
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.recording.RecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProAndRecordingContractInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun recordState_isBackedBySingleGpuRecordingStack() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity ->
                vm = activity.vm
            }

            // Recording state settles to Idle after startup; poll rather than assert the instant the
            // ViewModel is obtained (startup can briefly report a transitional state).
            assertSettles(scenario) {
                vm.recordState.value::class == RecordingState.Idle::class &&
                    vm.recDuration.value == vm.gpuRecDuration.value
            }
            scenario.onActivity {
                assertEquals(RecordingState.Idle::class, vm.recordState.value::class)
                assertEquals(vm.recDuration.value, vm.gpuRecDuration.value)
            }
        }
    }

    /** Run [attempt] (which may set state and returns whether the contract holds) on the activity
     *  thread, polling until it holds or the timeout elapses. Fails honestly if it never settles. */
    private fun assertSettles(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long = 15_000L,
        attempt: () -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var ok = false
        while (SystemClock.elapsedRealtime() < deadline && !ok) {
            scenario.onActivity { ok = attempt() }
            if (!ok) {
                SystemClock.sleep(120L)
            }
        }
        assertTrue(ok)
    }
}
