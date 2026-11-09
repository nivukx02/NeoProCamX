package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SplashThemeInheritanceTest {
    @Test
    fun supportedApiCutoutVariantRetainsCompleteCameraTheme() {
        val defaultTheme = File("src/main/res/values/themes.xml").readText()
        val api27Theme = File("src/main/res/values-v27/themes.xml").readText()

        assertTrue(defaultTheme.contains("name=\"Theme.NeoProCamX.Base\""))
        assertTrue(
            defaultTheme.contains(
                "name=\"Theme.NeoProCamX\" parent=\"Theme.NeoProCamX.Base\""
            )
        )
        assertTrue(
            api27Theme.contains(
                "name=\"Theme.NeoProCamX\" parent=\"Theme.NeoProCamX.Base\""
            )
        )
        assertTrue(api27Theme.contains("android:windowLayoutInDisplayCutoutMode"))
    }
}
