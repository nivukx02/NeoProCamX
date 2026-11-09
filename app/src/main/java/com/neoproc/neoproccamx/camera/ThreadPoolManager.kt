package com.neoproc.neoproccamx.camera

import android.os.Process
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

object ThreadPoolManager {
    private val cameraThreadIndex = AtomicInteger(0)

    val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor(
        urgentCameraThreadFactory("NeoProCamX-CameraExecutor")
    )

    private fun urgentCameraThreadFactory(name: String): ThreadFactory =
        ThreadFactory { runnable ->
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                runnable.run()
            }.apply {
                this.name = "$name-${cameraThreadIndex.incrementAndGet()}"
                isDaemon = true
            }
        }
}
