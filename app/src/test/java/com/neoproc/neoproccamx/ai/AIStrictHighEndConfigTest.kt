package com.neoproc.neoproccamx.ai

import com.neoproc.neoproccamx.core.ImaxStrictProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class AIStrictHighEndConfigTest {
    @Test
    fun strictAiConfigMatchesCanonicalImaxProfile() {
        assertEquals(ImaxStrictProfile.AI_RESOLUTION_PREVIEW, AIStrictHighEndConfig.aiPreviewMaxDimension)
        assertEquals(ImaxStrictProfile.AI_RESOLUTION_RECORDING, AIStrictHighEndConfig.aiMaxDimension)
        assertEquals(ImaxStrictProfile.AI_RESOLUTION_RECORDING, AIStrictHighEndConfig.maxDimension)
        assertEquals(ImaxStrictProfile.RUNTIME_PREVIEW_LUT_CUBE_SIZE, AIStrictHighEndConfig.lutSizeForPreview)
        assertEquals(ImaxStrictProfile.RUNTIME_PREVIEW_LUT_CUBE_SIZE, AIStrictHighEndConfig.highPrecisionLutSize)
    }
}
