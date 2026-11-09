package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test

class EliteDetailProfilePolicyTest {
    @Test
    fun `all profiles are high quality and increase detail monotonically`() {
        val natural = EliteDetailProfilePolicy.resolve(VideoDetailProfile.NATURAL_DETAIL)
        val focused = EliteDetailProfilePolicy.resolve(VideoDetailProfile.FOCUSED_DETAIL)
        val extreme = EliteDetailProfilePolicy.resolve(VideoDetailProfile.EXTREME_DETAIL)

        assertTrue(natural.stillSharpen >= 0.16f)
        assertTrue(natural.stillSharpen < focused.stillSharpen)
        assertTrue(focused.stillSharpen < extreme.stillSharpen)
        assertTrue(natural.conversionCleanSharpen < focused.conversionCleanSharpen)
        assertTrue(focused.conversionCleanSharpen < extreme.conversionCleanSharpen)
        assertTrue(natural.stillResidualChromaSuppression > 0f)
        assertTrue(natural.stillResidualChromaSuppression < focused.stillResidualChromaSuppression)
        assertTrue(focused.stillResidualChromaSuppression < extreme.stillResidualChromaSuppression)
        assertTrue(extreme.stillSharpen <= 0.32f)
        assertTrue(extreme.stillResidualChromaSuppression <= 0.12f)
        assertTrue(extreme.conversionCleanSharpen <= 0.66f)
    }
}
