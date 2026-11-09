package com.neoproc.neoproccamx.ui

/** Keeps warm-launch branding separate from debug intents and an already-visible camera. */
internal object SplashLaunchPolicy {
    fun shouldShowWarmSplash(
        isLauncherIntent: Boolean,
        activityResumed: Boolean,
        windowFocused: Boolean
    ): Boolean = isLauncherIntent && (!activityResumed || !windowFocused)
}
