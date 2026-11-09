package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProControlPresentationContractTest {
    private fun source(path: String): String = File(path).readText().replace("\r\n", "\n")

    @Test
    fun manualPanelIsStackedAboveModeAndTracksRuntimeDockTranslation() {
        val layout = source("src/main/res/layout/activity_main.xml")
        val activity = source("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt")

        val manualTag = layout.substringAfter("android:id=\"@+id/manualContainer\"").substringBefore("/>")
        assertTrue(manualTag.contains("app:layout_constraintBottom_toTopOf=\"@id/modeGroup\""))
        assertFalse(manualTag.contains("android:minHeight"))
        assertTrue(activity.contains("settledTranslationY = binding.modeGroup.translationY"))
        assertTrue(activity.contains("binding.manualContainer.translationY = plan.modeTranslationY"))
        assertTrue(activity.contains("binding.modeGroup.visibility = if (visible) View.VISIBLE else View.INVISIBLE"))
    }

    @Test
    fun proStartsCollapsedAndOnlyOneSelectedParameterShowsItsRuler() {
        val fragment = source(
            "src/main/java/com/neoproc/neoproccamx/ui/controls/ManualControlsFragment.kt"
        )
        val layout = source("src/main/res/layout/fragment_manual_controls.xml")
        val activity = source("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt")

        assertTrue(layout.substringAfter("android:id=\"@+id/controlStack\"")
            .substringBefore(">").contains("android:visibility=\"gone\""))
        // Pro entry must be presentation-only: collapse the ruler and render the durable manual
        // intent. Resetting controls to Auto here erased ISO/shutter/WB/focus/EV during recreation.
        assertFalse(fragment.contains("resetToAutoDefaults()"))
        assertTrue(fragment.contains("renderCurrentControlState()"))
        assertTrue(fragment.contains("hideAdjustmentPanel()"))
        assertTrue(fragment.contains("override fun onViewStateRestored"))
        assertTrue(fragment.contains("fun resetForProEntry()"))
        assertTrue(activity.contains("?.resetForProEntry()"))
        assertTrue(fragment.contains("selectTab(panelISO, tabISO)"))
        assertTrue(fragment.contains("CameraManualControlStateSyncPolicy.snapshot()"))
    }

    @Test
    fun adjustmentWidgetUsesStraightReferenceTicksInsteadOfAnArc() {
        val seekBar = source(
            "src/main/java/com/neoproc/neoproccamx/ui/controls/ReferenceAdjustSeekBar.kt"
        )

        assertTrue(seekBar.contains("canvas.drawLine("))
        assertFalse(seekBar.contains("canvas.drawArc("))
        assertTrue(seekBar.contains("centerX,"))
        assertTrue(seekBar.contains("progressForDragDelta"))
        assertTrue(seekBar.contains("postInvalidateOnAnimation()"))
        assertTrue(seekBar.contains("animateToSelectedTick"))
        assertFalse(seekBar.contains("adjacentLabel"))
    }

    @Test
    fun parameterRowUsesTransparentIconControlsInsteadOfTextTiles() {
        val layout = source("src/main/res/layout/fragment_manual_controls.xml")
        val control = source(
            "src/main/java/com/neoproc/neoproccamx/ui/controls/ProParameterButton.kt"
        )

        assertTrue(layout.contains("com.neoproc.neoproccamx.ui.controls.ProParameterButton"))
        assertTrue(layout.contains("android:background=\"@android:color/transparent\""))
        assertTrue(control.contains("drawExposureCompensation"))
        assertTrue(control.contains("drawManualFocus"))
        assertTrue(control.contains("drawMetering"))
        assertFalse(control.contains("super.onDraw(canvas)"))
    }

    @Test
    fun autoDiscIgnoresThemeTintAndUsesReferenceWhiteSurface() {
        val fragment = source(
            "src/main/java/com/neoproc/neoproccamx/ui/controls/ManualControlsFragment.kt"
        )
        val drawable = source("src/main/res/drawable/bg_manual_auto_button.xml")

        assertTrue(fragment.contains("backgroundTintList = null"))
        assertTrue(fragment.contains("setTextColor(Color.BLACK)"))
        assertTrue(drawable.contains("#FFFFFFFF"))
    }

    @Test
    fun rulerIsCenteredIndependentlyOfTheAutoControl() {
        val layout = source("src/main/res/layout/fragment_manual_controls.xml")
        val styles = source("src/main/res/values/styles.xml")

        assertTrue(layout.contains("<FrameLayout\n                android:id=\"@+id/sectionISO\""))
        val scaleStyle = styles.substringAfter("<style name=\"ManualReferenceScaleColumn\">")
            .substringBefore("</style>")
        assertTrue(scaleStyle.contains("<item name=\"android:layout_width\">240dp</item>"))
        assertTrue(scaleStyle.contains("<item name=\"android:layout_gravity\">center</item>"))
    }

    @Test
    fun compactAutoDiscIsDockedDirectlyBesideTheCenteredRuler() {
        val styles = source("src/main/res/values/styles.xml")
        val buttonStyle = styles.substringAfter("<style name=\"ManualAutoButton\">")
            .substringBefore("</style>")
        val columnStyle = styles.substringAfter("<style name=\"ManualAutoColumn\">")
            .substringBefore("</style>")

        assertTrue(buttonStyle.contains("<item name=\"android:layout_width\">24dp</item>"))
        assertTrue(buttonStyle.contains("<item name=\"android:layout_height\">24dp</item>"))
        assertTrue(columnStyle.contains("<item name=\"android:layout_gravity\">center</item>"))
        assertTrue(columnStyle.contains("<item name=\"android:translationX\">-135dp</item>"))
        assertTrue(columnStyle.contains("<item name=\"android:translationY\">23dp</item>"))
    }
}
