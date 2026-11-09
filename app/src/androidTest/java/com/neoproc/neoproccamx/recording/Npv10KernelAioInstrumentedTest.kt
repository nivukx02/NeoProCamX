package com.neoproc.neoproccamx.recording

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual app sandbox and private storage capability, never a camera-quality or FPS assertion. */
@RunWith(AndroidJUnit4::class)
class Npv10KernelAioInstrumentedTest {
    @Test
    fun applicationSandboxAndPrivateStorageSupportExactNativeAio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent().setClassName(context.packageName,
            "com.neoproc.neoproccamx.testing.Lq031KeepAliveActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<Activity>(intent).use {
            val capability = nativeProbeCapabilities()
            Log.i(TAG, "ACTUAL_APP_AIO_CAPABILITY result=$capability " +
                "meaning=0_SUPPORTED_1000_PLUS_SIGNAL_BLOCKED production_backend=UNCHANGED")
            assertEquals("App sandbox rejected native AIO; do not enable this backend", 0, capability)
            val directory = File(context.cacheDir, "qa-native-aio-${System.nanoTime()}")
            check(!directory.exists() && directory.mkdirs())
            try {
                val report = nativeCompareStorage(directory.canonicalPath)
                Log.i(TAG, "ACTUAL_APP_AIO_STORAGE $report")
                assertTrue(report, report.startsWith("PASS ") && report.contains("all_bytes_exact=true"))
                assertTrue("Probe leaked its private files", directory.list()?.isEmpty() == true)
            } finally {
                // Never recursively remove unknown content; the native owners retire their files.
                assertTrue("Probe directory was not empty or could not be removed", directory.delete())
            }
        }
    }

    companion object {
        private const val TAG = "Npv10KernelAioQA"
        init { System.loadLibrary("neoproc_storage_qa") }
        @JvmStatic private external fun nativeProbeCapabilities(): Int
        @JvmStatic private external fun nativeCompareStorage(directory: String): String
    }
}
