package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class AiWarmupStagePolicyTest {
    @Test
    fun `disabled features schedule no model initialization`() {
        val stages = AiWarmupStagePolicy.resolve(
            state = AiWarmupFeatureState(
                face = false,
                pose = false,
                segmentation = false,
                depth = false,
                scene = false,
                enhancement = false
            ),
            isEmulator = false
        )

        assertEquals(emptyList<AiWarmupStage>(), stages)
    }

    @Test
    fun `active device features warm only their complete dependency graph`() {
        val stages = AiWarmupStagePolicy.resolve(
            state = AiWarmupFeatureState(
                face = true,
                pose = true,
                segmentation = false,
                depth = true,
                scene = false,
                enhancement = true
            ),
            isEmulator = false
        )

        assertEquals(
            listOf(
                AiWarmupStage.FACE,
                AiWarmupStage.OBJECT,
                AiWarmupStage.POSE,
                AiWarmupStage.DEPTH,
                AiWarmupStage.ENHANCEMENT
            ),
            stages
        )
    }

    @Test
    fun `emulator excludes unsupported trackers without suppressing supported features`() {
        val stages = AiWarmupStagePolicy.resolve(
            state = AiWarmupFeatureState(
                face = true,
                pose = true,
                segmentation = true,
                depth = true,
                scene = true,
                enhancement = true
            ),
            isEmulator = true
        )

        assertEquals(
            listOf(
                AiWarmupStage.SCENE,
                AiWarmupStage.DEPTH,
                AiWarmupStage.ENHANCEMENT
            ),
            stages
        )
    }
}
