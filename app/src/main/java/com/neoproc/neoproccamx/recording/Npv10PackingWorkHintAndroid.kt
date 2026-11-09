package com.neoproc.neoproccamx.recording

import android.content.Context
import android.os.Build
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi

/** Prewarmed on the owning persistent Thread, never recreated for each camera frame. */
internal object Npv10PackingWorkHintAndroid {
    private const val TAG = "Npv10PackingHint"

    fun create(context: Context): Npv10PackingWorkHint {
        val backend = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                createSupported(context)
            } catch (failure: RuntimeException) {
                Log.w(TAG, "Packing ADPF unavailable; recording policy unchanged", failure)
                null
            }
        } else null
        Log.i(TAG, "PACKING_WORK_HINT active=${backend != null} tid=${Process.myTid()} " +
            "targetNs=${Npv10PackingWorkHint.targetDurationNs(Npv10PackingWorkHint.DEFAULT_FPS)} " +
            "scope=PACKING_AND_LOSSLESS thermalAuthority=ANDROID")
        return Npv10PackingWorkHint(backend) { operation, failure ->
            Log.w(TAG, "Packing ADPF $operation failed; session retired, recording unchanged", failure)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun createSupported(context: Context): Npv10PackingWorkHint.Backend? {
        val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return null
        val session = manager.createHintSession(
            intArrayOf(Process.myTid()),
            Npv10PackingWorkHint.targetDurationNs(Npv10PackingWorkHint.DEFAULT_FPS)
        ) ?: return null
        return object : Npv10PackingWorkHint.Backend {
            override fun updateTarget(durationNs: Long) = session.updateTargetWorkDuration(durationNs)
            override fun reportActual(durationNs: Long) = session.reportActualWorkDuration(durationNs)
            override fun close() = session.close()
        }
    }
}
