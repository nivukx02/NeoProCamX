package com.neoproc.neoproccamx.ui

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.core.content.edit
import com.neoproc.neoproccamx.R

/** Persists the last splash selection so consecutive app entries never show the same logo. */
internal object SplashLogoRotator {
    private const val PREFERENCES_NAME = "splash_logo_rotation"
    private const val LAST_INDEX_KEY = "last_index"

    private val logos = intArrayOf(
        R.drawable.splash_logo_01,
        R.drawable.splash_logo_02,
        R.drawable.splash_logo_03,
        R.drawable.splash_logo_05,
        R.drawable.splash_logo_06,
        R.drawable.splash_logo_07,
        R.drawable.splash_logo_08,
        R.drawable.splash_logo_09,
        R.drawable.splash_logo_10
    )

    @DrawableRes
    fun nextLogo(context: Context): Int {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val nextIndex = SplashLogoRotationPolicy.nextIndex(
            preferences.getInt(LAST_INDEX_KEY, -1)
        )
        preferences.edit { putInt(LAST_INDEX_KEY, nextIndex) }
        return logos[nextIndex]
    }
}
