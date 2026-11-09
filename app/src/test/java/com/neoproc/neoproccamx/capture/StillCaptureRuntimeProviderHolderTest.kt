package com.neoproc.neoproccamx.capture

import com.neoproc.neoproccamx.ai.SceneType
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class StillCaptureRuntimeProviderHolderTest {
    @Test
    fun `providers preserve supplied behavior until teardown`() {
        val calls = AtomicInteger(0)
        val holder = StillCaptureRuntimeProviderHolder(
            captureRecommendationProvider = { calls.incrementAndGet(); null },
            aiEnhancementEnabledProvider = { calls.incrementAndGet(); true },
            aiSceneTypeProvider = { calls.incrementAndGet(); SceneType.NIGHT },
            aiHdrSuggestedProvider = { calls.incrementAndGet(); true },
            videoDetailProfileProvider = {
                calls.incrementAndGet()
                VideoDetailProfile.EXTREME_DETAIL
            },
            debugValidationLabelProvider = { calls.incrementAndGet(); "runtime-label" }
        )

        assertNull(holder.captureRecommendation())
        assertTrue(holder.aiEnhancementEnabled())
        assertEquals(SceneType.NIGHT, holder.aiSceneType())
        assertTrue(holder.aiHdrSuggested())
        assertEquals(VideoDetailProfile.EXTREME_DETAIL, holder.videoDetailProfile())
        assertEquals("runtime-label", holder.debugValidationLabel())
        assertEquals(6, calls.get())
    }

    @Test
    fun `teardown atomically replaces every UI provider with safe defaults`() {
        val calls = AtomicInteger(0)
        val holder = StillCaptureRuntimeProviderHolder(
            captureRecommendationProvider = { calls.incrementAndGet(); null },
            aiEnhancementEnabledProvider = { calls.incrementAndGet(); true },
            aiSceneTypeProvider = { calls.incrementAndGet(); SceneType.NIGHT },
            aiHdrSuggestedProvider = { calls.incrementAndGet(); true },
            videoDetailProfileProvider = {
                calls.incrementAndGet()
                VideoDetailProfile.EXTREME_DETAIL
            },
            debugValidationLabelProvider = { calls.incrementAndGet(); "runtime-label" }
        )

        holder.detachForTeardown()
        holder.detachForTeardown()

        assertNull(holder.captureRecommendation())
        assertFalse(holder.aiEnhancementEnabled())
        assertEquals(SceneType.GENERAL, holder.aiSceneType())
        assertFalse(holder.aiHdrSuggested())
        assertEquals(VideoDetailProfile.FOCUSED_DETAIL, holder.videoDetailProfile())
        assertNull(holder.debugValidationLabel())
        assertEquals(0, calls.get())
    }

    @Test
    fun `still processors retain holder methods instead of immutable UI lambdas`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/capture/StillCapture.kt"
        ).readText()

        assertTrue(source.contains("fun detachRuntimeProvidersForTeardown()"))
        assertTrue(source.contains("debugValidationLabelProvider = runtimeProviders::debugValidationLabel"))
        assertTrue(source.contains("aiEnhancementEnabledProvider = runtimeProviders::aiEnhancementEnabled"))
        assertTrue(source.contains("aiSceneTypeProvider = runtimeProviders::aiSceneType"))
        assertTrue(source.contains("aiHdrSuggestedProvider = runtimeProviders::aiHdrSuggested"))
        assertTrue(source.contains("videoDetailProfileProvider = runtimeProviders::videoDetailProfile"))
        assertFalse(source.contains("private val captureRecommendationProvider:"))
        assertFalse(source.contains("private val aiEnhancementEnabledProvider:"))
        assertFalse(source.contains("private val aiSceneTypeProvider:"))
        assertFalse(source.contains("private val aiHdrSuggestedProvider:"))
        assertFalse(source.contains("private val videoDetailProfileProvider:"))
        assertFalse(source.contains("private val debugValidationLabelProvider:"))
    }
}
