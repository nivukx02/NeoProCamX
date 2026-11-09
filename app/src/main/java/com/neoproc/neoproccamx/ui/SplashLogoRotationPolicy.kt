package com.neoproc.neoproccamx.ui

/** Pure rotation policy kept separate so every launcher entry is deterministic and testable. */
internal object SplashLogoRotationPolicy {
    const val VARIANT_COUNT = 9

    fun nextIndex(lastIndex: Int): Int =
        if (lastIndex in 0 until VARIANT_COUNT) {
            (lastIndex + 1) % VARIANT_COUNT
        } else {
            0
        }
}
