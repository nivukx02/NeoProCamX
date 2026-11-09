package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProfileIntentPolicyTest {

    @Test
    fun nLogFamilySelectsLogWithoutForcingAnHdrTransport() {
        listOf("N-Log Base", "N-Log Shadow", "N-Log Highlight", "N-Log Cinema")
            .forEach { profile ->
                val intent = VideoProfileIntentPolicy.resolve(profile)

                assertTrue("$profile must select the LOG pipeline", intent.impliesLogMode)
                assertTrue("$profile must preserve wide-gamut intent", intent.requiresWideGamut)
                assertNull("$profile must not force an HDR transport", intent.recommendedHdrMode)
            }
    }

    @Test
    fun ndrAliasesSelectTheProjectPqTransport() {
        listOf("NDR10+", "NDR10+ Native").forEach { profile ->
            val intent = VideoProfileIntentPolicy.resolve(profile)

            assertFalse(intent.impliesLogMode)
            assertTrue(intent.requiresWideGamut)
            assertEquals("NDR10+", intent.recommendedHdrMode)
        }
    }

    @Test
    fun nivukxVisionSelectsItsOwnProjectPqTransport() {
        val intent = VideoProfileIntentPolicy.resolve("NIVUKX Vision")

        assertFalse(intent.impliesLogMode)
        assertTrue(intent.requiresWideGamut)
        assertEquals("NIVUKXVISION", intent.recommendedHdrMode)
        assertEquals(128, intent.lutSizeForPreview)
    }
}
