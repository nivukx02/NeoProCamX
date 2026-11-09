package com.neoproc.neoproccamx.ui

import android.view.View
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiStateDiffPolicyTest {

    @Test
    fun textChanged_comparesRenderedTextContent() {
        assertFalse(CameraUiStateDiffPolicy.textChanged("HDR", "HDR"))
        assertTrue(CameraUiStateDiffPolicy.textChanged("HDR", "LOG"))
    }

    @Test
    fun floatChanged_usesEpsilonForAnimationProperties() {
        assertFalse(CameraUiStateDiffPolicy.floatChanged(1.0f, 1.0004f))
        assertTrue(CameraUiStateDiffPolicy.floatChanged(1.0f, 1.02f))
    }

    @Test
    fun visibilityChanged_comparesAndroidVisibilityWithBooleanTarget() {
        assertFalse(CameraUiStateDiffPolicy.visibilityChanged(View.VISIBLE, desiredVisible = true))
        assertTrue(CameraUiStateDiffPolicy.visibilityChanged(View.GONE, desiredVisible = true))
    }

    @Test
    fun shouldApplyBoolean_forcesReconcileOnFirstRenderEvenWhenUnchanged() {
        // Persisted-true monitor equals the seeded default -> plain diff is false, but the layout
        // (gone panel / overlay prop) must still be reconciled once on the first render.
        assertTrue(CameraUiStateDiffPolicy.shouldApplyBoolean(firstRender = true, current = true, next = true))
        assertTrue(CameraUiStateDiffPolicy.shouldApplyBoolean(firstRender = true, current = false, next = false))
    }

    @Test
    fun shouldApplyBoolean_isPlainChangeDiffAfterFirstRender() {
        assertFalse(CameraUiStateDiffPolicy.shouldApplyBoolean(firstRender = false, current = true, next = true))
        assertFalse(CameraUiStateDiffPolicy.shouldApplyBoolean(firstRender = false, current = false, next = false))
        assertTrue(CameraUiStateDiffPolicy.shouldApplyBoolean(firstRender = false, current = false, next = true))
        assertTrue(CameraUiStateDiffPolicy.shouldApplyBoolean(firstRender = false, current = true, next = false))
    }
}
