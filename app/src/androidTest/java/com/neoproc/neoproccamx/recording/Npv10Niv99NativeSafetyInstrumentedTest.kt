package com.neoproc.neoproccamx.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side deterministic checks for the optimized NIV99 P010 processor and its worker pool. */
@RunWith(AndroidJUnit4::class)
class Npv10Niv99NativeSafetyInstrumentedTest {
    @Test
    fun optimizedTwoByFourLumaReuse_isBitExactWithScalarReference() {
        val comparedBytes = Npv10FramePacker.verifyNiv99P010BlockReuseParityForTest()

        assertEquals(
            "Every deterministic scene must complete scalar-versus-block parity",
            EXPECTED_COMPARED_BYTES,
            comparedBytes
        )
    }

    @Test
    fun nativeRowExecutor_tearsDownJoinsAndRestartsWithoutWorkerGrowth() {
        repeat(LIFECYCLE_ASSERTION_ROUNDS) { round ->
            assertEquals(
                "NIV99 helper pool was not fully restored after lifecycle round $round",
                EXPECTED_HELPER_THREADS,
                Npv10FramePacker.exerciseNiv99P010ProcessorLifecycleForTest(
                    LIFECYCLE_CYCLES_PER_ROUND
                )
            )
            assertTrue(
                "NIV99 parity failed after lifecycle round $round",
                Npv10FramePacker.verifyNiv99P010BlockReuseParityForTest() > 0L
            )
        }
    }

    private companion object {
        const val SYNTHETIC_WIDTH = 64
        const val SYNTHETIC_HEIGHT = 32
        const val SYNTHETIC_SCENES = 10L
        const val PACKED_BITS_PER_SAMPLE = 10L
        const val CHROMA_HEIGHT_DIVISOR = 2
        // Seven are prewarmed; offline conversion adds its submitting lane as the eighth worker.
        // Packed live DMC activates only five so Camera2 callbacks retain a compute lane.
        const val EXPECTED_HELPER_THREADS = 7
        const val LIFECYCLE_ASSERTION_ROUNDS = 3
        const val LIFECYCLE_CYCLES_PER_ROUND = 4
        const val EXPECTED_COMPARED_BYTES =
            SYNTHETIC_WIDTH * PACKED_BITS_PER_SAMPLE / Byte.SIZE_BITS *
                (SYNTHETIC_HEIGHT + SYNTHETIC_HEIGHT / CHROMA_HEIGHT_DIVISOR) *
                SYNTHETIC_SCENES
    }
}
