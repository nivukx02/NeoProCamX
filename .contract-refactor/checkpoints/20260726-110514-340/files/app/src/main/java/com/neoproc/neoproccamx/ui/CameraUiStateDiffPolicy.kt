package com.neoproc.neoproccamx.ui

import android.view.View
import kotlin.math.abs

internal object CameraUiStateDiffPolicy {
    fun textChanged(current: CharSequence?, next: String): Boolean =
        current?.toString() != next

    fun floatChanged(current: Float, next: Float, epsilon: Float = 0.001f): Boolean =
        abs(current - next) > epsilon

    fun intChanged(current: Int, next: Int): Boolean = current != next

    fun booleanChanged(current: Boolean, next: Boolean): Boolean = current != next

    /**
     * First-render reconciliation for a boolean-driven view property.
     *
     * The activity seeds its `lastState` from the [CameraUiState] defaults (monitor panels default
     * visible), while the corresponding views start from their layout state (e.g. the waveform panel
     * is `android:visibility="gone"`, the overlay's `showHistogram` starts false). When a persisted
     * preference matches the seeded default, the plain change-diff is false on the very first render,
     * so the view is never reconciled and a HUD monitor silently stays hidden after relaunch. Forcing
     * the apply once on first render fixes that while staying a pure change-diff thereafter.
     */
    fun shouldApplyBoolean(firstRender: Boolean, current: Boolean, next: Boolean): Boolean =
        firstRender || booleanChanged(current, next)

    fun visibilityChanged(currentVisibility: Int, desiredVisible: Boolean): Boolean {
        val desiredVisibility = if (desiredVisible) View.VISIBLE else View.GONE
        return currentVisibility != desiredVisibility
    }

    fun floatArrayChanged(current: FloatArray?, next: FloatArray?): Boolean {
        if (current === next) return false
        if (current == null || next == null) return true
        if (current.size != next.size) return true
        for (i in current.indices) {
            if (abs(current[i] - next[i]) > 0.0001f) return true
        }
        return false
    }

    fun intArrayChanged(current: IntArray?, next: IntArray?): Boolean {
        if (current === next) return false
        if (current == null || next == null) return true
        if (current.size != next.size) return true
        for (i in current.indices) {
            if (current[i] != next[i]) return true
        }
        return false
    }

    fun <T> listChanged(current: List<T>?, next: List<T>?): Boolean =
        current != next

    fun <T> objectChanged(current: T?, next: T?): Boolean =
        current != next
}
