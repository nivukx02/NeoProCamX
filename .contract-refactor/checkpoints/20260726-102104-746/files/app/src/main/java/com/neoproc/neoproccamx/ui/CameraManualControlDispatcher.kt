package com.neoproc.neoproccamx.ui

import android.os.Handler
import android.os.HandlerThread
import android.os.Process

internal class CameraManualControlDispatcher(
    threadName: String = "CamX-ManualControls"
) {
    private val threadDelegate = lazy(LazyThreadSafetyMode.NONE) {
        HandlerThread(threadName, Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    }
    private val handlerDelegate = lazy(LazyThreadSafetyMode.NONE) {
        Handler(threadDelegate.value.looper)
    }
    private val latestRunnableLock = Any()
    private val latestRunnables = mutableMapOf<String, Runnable>()
    @Volatile private var closed = false

    fun post(action: () -> Unit): Boolean {
        val handler = synchronized(latestRunnableLock) {
            if (closed) return false
            handlerDelegate.value
        }
        return handler.post(action)
    }

    fun postLatest(key: String, action: () -> Unit): Boolean {
        val runnable = object : Runnable {
            override fun run() {
                synchronized(latestRunnableLock) {
                    if (latestRunnables[key] === this) {
                        latestRunnables.remove(key)
                    }
                }
                action()
            }
        }
        val handler = synchronized(latestRunnableLock) {
            if (closed) return false
            handlerDelegate.value
        }
        synchronized(latestRunnableLock) {
            if (closed) return false
            latestRunnables.remove(key)?.let { handler.removeCallbacks(it) }
            latestRunnables[key] = runnable
        }
        val posted = handler.post(runnable)
        if (!posted) {
            synchronized(latestRunnableLock) {
                if (latestRunnables[key] === runnable) {
                    latestRunnables.remove(key)
                }
            }
        }
        return posted
    }

    fun postLatestZoom(action: () -> Unit): Boolean {
        return postLatest(CameraUiResponsivenessPolicy.MANUAL_ZOOM_KEY, action)
    }

    fun shutdown() {
        val handler = synchronized(latestRunnableLock) {
            if (closed) return
            closed = true
            if (handlerDelegate.isInitialized()) {
                handlerDelegate.value
            } else {
                null
            }
        }
        if (handlerDelegate.isInitialized()) {
            handler?.removeCallbacksAndMessages(null)
        }
        if (threadDelegate.isInitialized()) {
            threadDelegate.value.quitSafely()
        }
        synchronized(latestRunnableLock) {
            latestRunnables.clear()
        }
    }
}
