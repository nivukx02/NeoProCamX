package com.neoproc.neoproccamx.ui

import android.util.Log
import com.neoproc.neoproccamx.BuildConfig
import java.lang.ref.WeakReference

/** A stop command must never create a camera owner or wait for a future Activity launch. */
internal class ExistingRecordingStopRouter<T : Any> {
    enum class Result { NOT_HANDLED, NO_EXISTING_OWNER, DELIVERED }

    private var owner = WeakReference<T>(null)

    @Synchronized
    fun bind(value: T) {
        owner = WeakReference(value)
    }

    @Synchronized
    fun unbind(value: T) {
        // A retiring generation cannot unregister its replacement.
        if (owner.get() === value) owner.clear()
    }

    @Synchronized
    fun dispatch(debugBuild: Boolean, action: String?, requestStop: (T) -> Unit): Result {
        if (!debugBuild || !action?.trim().equals("stop_recording", ignoreCase = true)) {
            return Result.NOT_HANDLED
        }
        // An idle stop is consumed, not queued: replaying it could stop the NEXT recording.
        val existing = owner.get() ?: return Result.NO_EXISTING_OWNER
        requestStop(existing)
        return Result.DELIVERED
    }
}

/** Uses the already retained application owner; Android background Activity launch is unnecessary. */
internal object RetainedRecordingStopCommands {
    private val router = ExistingRecordingStopRouter<CameraViewModel>()

    fun bind(owner: CameraViewModel) {
        if (BuildConfig.DEBUG) router.bind(owner)
    }

    fun unbind(owner: CameraViewModel) = router.unbind(owner)

    fun dispatch(action: String?): Boolean {
        val result = router.dispatch(BuildConfig.DEBUG, action) { owner ->
            owner.stopRecordingFromRetainedDebugOwner()
        }
        if (result == ExistingRecordingStopRouter.Result.NOT_HANDLED) return false
        Log.i("RetainedRecordingStop", "DEBUG_RECORDING_STOP_PROCESS_OWNER result=$result")
        return true
    }
}
