package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashLaunchPolicyTest {
    @Test
    fun backgroundLauncherDelivery_showsWarmSplash() {
        assertTrue(
            SplashLaunchPolicy.shouldShowWarmSplash(
                isLauncherIntent = true,
                activityResumed = false,
                windowFocused = false
            )
        )
    }

    @Test
    fun alreadyVisibleCamera_isNotCoveredAgain() {
        assertFalse(
            SplashLaunchPolicy.shouldShowWarmSplash(
                isLauncherIntent = true,
                activityResumed = true,
                windowFocused = true
            )
        )
    }

    @Test
    fun debugOrDeepLinkIntent_neverCreatesBrandingOverlay() {
        assertFalse(
            SplashLaunchPolicy.shouldShowWarmSplash(
                isLauncherIntent = false,
                activityResumed = false,
                windowFocused = false
            )
        )
    }
}
