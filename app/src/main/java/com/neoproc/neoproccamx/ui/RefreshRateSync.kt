package com.neoproc.neoproccamx.ui

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Surface
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi

/**
 * RefreshRateSync — Synchronises the camera preview and UI to the display's maximum refresh rate.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * PROBLEM
 * ═════════════════════════════════════════════════════════════════════════════════════
 * Modern flagships ship with 90 Hz, 120 Hz, or even 144 Hz OLED panels.  If the camera
 * preview is fixed at 60 fps while the display refreshes at 120 Hz, users perceive
 * stutter (2:1 frame repetition).  Conversely, if the preview renders at 30 fps on a
 * 60 Hz panel, every second frame is a duplicate.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * SOLUTION
 * ═════════════════════════════════════════════════════════════════════════════════════
 * 1. Query the display's supported refresh rates via [Display.Mode.getRefreshRate].
 * 2. Select the highest rate that the camera output can evenly divide into (or vice versa).
 * 3. Call [Surface.setFrameRate] (API 30+) to hint the SurfaceFlinger composition rate.
 * 4. Request [WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS] and
 *    FLAG_HARDWARE_ACCELERATED to ensure the window compositor doesn't throttle us.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * THREADING
 * ═════════════════════════════════════════════════════════════════════════════════════
 * All public methods are safe to call from any thread.  Internal work is posted to the
 * main looper because Window/Display APIs are UI-thread bound.
 *
 * ═════════════════════════════════════════════════════════════════════════════════════
 * FAIL-SAFE
 * ═════════════════════════════════════════════════════════════════════════════════════
 * On API < 30, or when the display reports only 60 Hz, this class no-ops gracefully.
 * No exceptions propagate to the caller.
 */
class RefreshRateSync(private val context: Context) {

    companion object {
        private const val TAG = "RefreshRateSync"

        /** Default target: 120 Hz (common on 2023+ flagships). */
        const val PREFERRED_REFRESH_RATE = 120f

        /** Minimum acceptable rate before we give up and leave system default. */
        const val MIN_ACCEPTABLE_RATE = 60f
    }

    private val windowManager: WindowManager? = try {
        context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    } catch (t: Throwable) {
        Log.w(TAG, "WindowManager unavailable", t)
        null
    }

    private val displayManager: DisplayManager? = try {
        context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    } catch (t: Throwable) {
        Log.w(TAG, "DisplayManager unavailable", t)
        null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var activeSurface: Surface? = null
    @Volatile private var activeCaptureRate = 30f

    /**
     * Attaches a Surface that will receive camera frames.  The sync logic will call
     * [Surface.setFrameRate] on this surface when appropriate.
     */
    fun attachSurface(surface: Surface?) {
        activeSurface = surface
        if (surface != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            applyFrameRateHint(surface)
        }
    }

    /**
     * Updates the camera's target capture rate (e.g. 24, 30, 60, 120 fps).
     * The UI will attempt to match the display refresh rate to an even multiple
     * of this rate to minimise judder.
     */
    fun setCaptureRate(fps: Float) {
        activeCaptureRate = fps.coerceAtLeast(1f)
        activeSurface?.let { surf ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                applyFrameRateHint(surf)
            }
        }
    }

    /**
     * Call from Activity.onResume or when the window gains focus.  This refreshes
     * the display mode list and may switch to a higher rate if available.
     */
    fun onResume(window: Window?) {
        if (window == null) return
        mainHandler.post {
            optimiseWindowForHighRefresh(window)
            activeSurface?.let { surf ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    applyFrameRateHint(surf)
                }
            }
        }
    }

    /**
     * Call from Activity.onPause.  Removes frame-rate hints to allow the system
     * to save power.
     */
    fun onPause() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activeSurface?.let { surf ->
                try {
                    surf.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to clear surface frame rate hint", t)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun applyFrameRateHint(surface: Surface) {
        val display = getDisplay() ?: return
        val supportedRates = display.supportedModes.map { it.refreshRate }.sortedDescending()
        val bestRate = selectBestRate(supportedRates, activeCaptureRate) ?: return

        try {
            // FRAME_RATE_COMPATIBILITY_DEFAULT allows SurfaceFlinger to adjust
            // if the display cannot hit the exact rate.
            surface.setFrameRate(bestRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            Log.d(TAG, "Set surface frame rate to ${bestRate}fps (capture=${activeCaptureRate}fps)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to set frame rate", t)
        }
    }

    @Suppress("DEPRECATION")
    private fun getDisplay(): Display? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display
            } else {
                // API < 30: Use DisplayManager instead of deprecated WindowManager.defaultDisplay
                displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                    ?: windowManager?.defaultDisplay
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Chooses the highest refresh rate that is an integer multiple of [captureRate],
     * or the highest available if no even multiple exists.
     */
    private fun selectBestRate(rates: List<Float>, captureRate: Float): Float? {
        if (rates.isEmpty()) return null
        // Prefer exact multiples: 120 Hz for 30fps, 120 Hz for 60fps, etc.
        val multiple = rates.firstOrNull { rate ->
            val ratio = rate / captureRate
            ratio > 0.9f && kotlin.math.abs(ratio - kotlin.math.round(ratio)) < 0.1f
        }
        return multiple ?: rates.firstOrNull { it >= MIN_ACCEPTABLE_RATE }
    }

    private fun optimiseWindowForHighRefresh(window: Window) {
        try {
            // Request hardware acceleration explicitly.
            window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Allow rendering into display cutout area for maximum sensor coverage.
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }

            // On some OEMs (Samsung, OnePlus) we can hint the display composer
            // to prefer high refresh via setPreferMinimalPostProcessing.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.setPreferMinimalPostProcessing(true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Window optimisation failed", t)
        }
    }

    /**
     * Returns the current display refresh rate, or 60.0 if unavailable.
     */
    fun currentDisplayRefreshRate(): Float {
        return try {
            getDisplay()?.refreshRate ?: 60f
        } catch (_: Throwable) {
            60f
        }
    }
}

internal class VSyncTelemetryGate(
    private val onVSync: () -> Unit,
    private val frameScheduler: (((() -> Unit)) -> Unit)? = null
) {
    @Volatile
    var isFrameScheduled: Boolean = false
        private set

    private val choreographer: Choreographer? by lazy {
        if (frameScheduler == null) Choreographer.getInstance() else null
    }

    private val callback = Choreographer.FrameCallback {
        completeFrame()
    }

    fun requestFrame() {
        if (isFrameScheduled) return
        isFrameScheduled = true
        val scheduler = frameScheduler
        if (scheduler != null) {
            scheduler { completeFrame() }
        } else {
            choreographer?.postFrameCallback(callback)
        }
    }

    private fun completeFrame() {
        isFrameScheduled = false
        onVSync()
    }
}
