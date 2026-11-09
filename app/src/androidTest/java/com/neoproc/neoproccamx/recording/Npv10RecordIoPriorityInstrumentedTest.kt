package com.neoproc.neoproccamx.recording

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real app-sandbox scheduling admission. This never counts as a camera/FPS checkpoint. */
@RunWith(AndroidJUnit4::class)
class Npv10RecordIoPriorityInstrumentedTest {
    @Test
    fun ownedWriterPriorityIsVerifiedAndCallerPriorityPreserved() {
        val result = nativeCheckOwnedWriterPriority()
        Log.i("Npv10IoPriorityQA", result)
        assertTrue("Actual app writer priority was not verified: $result", result.startsWith("PASS "))
        assertTrue("Writer did not restore its exact original priority: $result", result.contains("restored=true"))
        assertTrue(result, result.contains("target=current_writer_thread quality_changed=false"))
    }

    companion object {
        init { System.loadLibrary("neoproc_storage_qa") }
        @JvmStatic private external fun nativeCheckOwnedWriterPriority(): String
    }
}
