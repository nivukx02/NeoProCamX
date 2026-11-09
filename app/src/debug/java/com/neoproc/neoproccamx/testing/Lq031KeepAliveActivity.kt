package com.neoproc.neoproccamx.testing

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/**
 * Debug-only foreground host for long physical-codec instrumentation runs.
 *
 * Some Vivo firmware freezes a headless instrumentation target even while Android reports it as a
 * foreground-service process. That suspends every app/codec callback in `do_freezer_trap` and can
 * manufacture false conversion failures. The exclusive ADB harness starts this inert activity from
 * the app UID, keeping the test process visible without opening Camera2, a decoder, or an encoder.
 * It is absent from release builds and cannot alter conversion pixels or quality policy.
 */
class Lq031KeepAliveActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            View(this).apply {
                setBackgroundColor(Color.BLACK)
                contentDescription = "LQ-031 physical codec test in progress"
            }
        )
    }
}
