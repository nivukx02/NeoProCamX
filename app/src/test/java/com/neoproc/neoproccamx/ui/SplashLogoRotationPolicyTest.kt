package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashLogoRotationPolicyTest {
    @Test
    fun firstLaunchStartsAtFirstSplashVariant() {
        assertEquals(0, SplashLogoRotationPolicy.nextIndex(-1))
    }

    @Test
    fun consecutiveLaunchAdvancesToDifferentVariant() {
        assertEquals(5, SplashLogoRotationPolicy.nextIndex(4))
    }

    @Test
    fun finalVariantWrapsToFirstVariant() {
        assertEquals(
            0,
            SplashLogoRotationPolicy.nextIndex(SplashLogoRotationPolicy.VARIANT_COUNT - 1)
        )
    }

    @Test
    fun fullCycleShowsEveryVariantExactlyOnce() {
        var index = -1
        val visited = mutableSetOf<Int>()

        repeat(SplashLogoRotationPolicy.VARIANT_COUNT) {
            index = SplashLogoRotationPolicy.nextIndex(index)
            assertTrue(visited.add(index))
        }

        assertEquals(SplashLogoRotationPolicy.VARIANT_COUNT, visited.size)
        assertEquals(0, SplashLogoRotationPolicy.nextIndex(index))
    }
}
