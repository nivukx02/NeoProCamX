package com.neoproc.neoproccamx.ui

import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CameraHapticFeedbackExecutor(
    private val rootView: View,
    private val vibratorProvider: () -> Vibrator
) {
    private val hapticScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val hapticChannel = Channel<CameraHapticAction>(capacity = Channel.CONFLATED)
    private var lastHapticEvent: CameraHapticEvent? = null

    init {
        hapticScope.launch {
            for (action in hapticChannel) {
                performResolved(action)
            }
        }
    }

    fun enqueue(action: CameraHapticAction) {
        hapticChannel.trySend(action)
    }

    fun perform(action: CameraHapticAction) {
        enqueue(action)
    }

    private suspend fun performResolved(action: CameraHapticAction) {
        val decision = CameraHapticFeedbackPolicy.resolve(
            action = action,
            nowMs = SystemClock.uptimeMillis(),
            lastEvent = lastHapticEvent
        )
        if (!decision.shouldEmit) return
        lastHapticEvent = decision.nextEvent
        withContext(Dispatchers.Main.immediate) {
            emit(decision)
        }
    }

    private fun emit(decision: CameraHapticFeedbackDecision) {
        when (decision.primitive) {
            CameraHapticPrimitive.CLOCK_TICK ->
                rootView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            CameraHapticPrimitive.CONFIRM -> {
                val emitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    rootView.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    false
                }
                if (!emitted) vibrateFallback(decision)
            }
            CameraHapticPrimitive.REJECT -> {
                val emitted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    rootView.performHapticFeedback(HapticFeedbackConstants.REJECT)
                } else {
                    false
                }
                if (!emitted) vibrateFallback(decision)
            }
            CameraHapticPrimitive.HEAVY_CLICK -> {
                val emitted = vibratePredefinedHeavyClick()
                if (!emitted) vibrateFallback(decision)
            }
            CameraHapticPrimitive.DOUBLE_TICK,
            CameraHapticPrimitive.PULSE_ALERT -> vibrateFallback(decision)
        }
    }

    private fun vibratePredefinedHeavyClick(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        vibratorProvider().vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
        return true
    }

    private fun vibrateFallback(decision: CameraHapticFeedbackDecision) {
        val waveform = decision.waveform
        if (waveform != null) {
            vibrateWaveform(waveform)
        } else {
            vibratorProvider().vibrate(
                VibrationEffect.createOneShot(
                    decision.fallbackDurationMs,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        }
    }

    private fun vibrateWaveform(waveform: CameraHapticWaveform) {
        val timingsMs = waveform.timingsMs.toLongArray()
        val amplitudes = waveform.amplitudes?.toIntArray()
        val effect = if (amplitudes != null) {
            VibrationEffect.createWaveform(timingsMs, amplitudes, waveform.repeat)
        } else {
            VibrationEffect.createWaveform(timingsMs, waveform.repeat)
        }
        vibratorProvider().vibrate(effect)
    }
}
