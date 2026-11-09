package com.neoproc.neoproccamx.pipeline

/**
 * AiPipelineTeardownOffMainFlag — device-gated (default OFF) lever that moves the BLOCKING part of the
 * AI pipeline teardown off the main thread.
 *
 * [AICameraPipeline.close] runs from CameraViewModel.onCleared (main thread). It shuts down ~9
 * single-thread AI executors and, for EACH, blocks up to 1500 ms in awaitTermination, then closes the ML
 * trackers (Face/Object/Pose/MLKit-Scene) and clears the bitmap pool — up to seconds of main-thread
 * stall on the way out, delaying camera.destroy()/vulkanRuntime.close() and risking a janky exit.
 *
 * When ON, close() still calls `shutdownNow()` on every executor SYNCHRONOUSLY on the main thread
 * (instant — it signals every worker to stop at once), then hands the blocking drain (the
 * awaitTermination loop, KEEPING the full 1500 ms per-executor budget) + the tracker close +
 * bitmapPool.clear() to a process-global (application-scoped) single daemon worker, so onCleared returns
 * immediately. Ordering inside the off-main work is identical to the shipped synchronous path
 * (drain-all -> close trackers -> clear pool), so the drain still completes before any tracker/pool is
 * touched. The AI executors/trackers/pool share no state with the Vulkan runtime, so running them
 * concurrently with vulkanRuntime.close() is safe. The worker is a daemon thread (never keeps the
 * process alive).
 *
 * DEFAULT OFF — device-behaviour-dependent (concurrent native tracker teardown). When OFF the close()
 * path is byte-identical to today's shipped synchronous teardown (the worker is never even created).
 * NOT a battery optimisation. On-device A/B (takes effect on the next ViewModel teardown):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.ai_teardown_off_main true
 */
object AiPipelineTeardownOffMainFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }

    /**
     * Application-scoped single daemon worker that runs the deferred teardown drain. Lazily created only
     * when [execute] is first called (i.e. only when the flag is ON), so the OFF default never allocates a
     * thread. Daemon => never keeps the process alive.
     */
    private val teardownWorker: java.util.concurrent.ExecutorService by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ai-teardown-offmain").apply { isDaemon = true }
        }
    }

    /**
     * Run [block] (the blocking awaitTermination drain + tracker close + bitmap-pool clear) on the
     * app-scoped daemon worker so the caller (main-thread onCleared) returns immediately. Failures are
     * swallowed+logged — a teardown drain error must never crash the exiting process.
     */
    fun execute(block: () -> Unit) {
        teardownWorker.execute {
            runCatching { block() }.onFailure {
                android.util.Log.w("AiPipelineTeardownOffMain", "Deferred AI teardown failed", it)
            }
        }
    }
}
