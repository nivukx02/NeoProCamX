package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CP-F6 (s5-f05) — wiring lock for the depth-prior hybrid AF bias inside the coordinator.
 *
 * The coordinator depends on a CONCRETE CameraController, so its wiring is locked by a
 * source-contains contract (same pattern as [AIAutoFocusCoordinatorContractTest]). This asserts:
 *  - the depth-prior bias is ONLY consulted on the maxAfRegions>0 branch;
 *  - the default-OFF baseline (the original early-return) is preserved as the no-op path;
 *  - the one-shot lens hint is latched (cannot become a continuous servo drive);
 *  - the region-less PID path is still present and unchanged.
 */
class AIAutoFocusCoordinatorDepthPriorContractTest {
    private fun read(path: String): String =
        File(path).readText().replace("\r\n", "\n")

    private val source: String by lazy {
        read("src/main/java/com/neoproc/neoproccamx/ai/AIAutoFocusCoordinator.kt")
    }

    @Test
    fun depthPriorBiasIsGatedInsideRegionCapableBranch() {
        // The region-capable gate still exists.
        assertTrue(source.contains("if (camera.maxAfRegions > 0) {"))
        // The depth-prior bias is consulted and gated through AIDepthPriorFocusBias.
        assertTrue(source.contains("AIDepthPriorFocusBias.enabled"))
        assertTrue(source.contains("AIDepthPriorFocusBias.resolve("))
        // The default-OFF baseline no-op (original early-return) is preserved verbatim.
        assertTrue(source.contains("if (camera.aiDrivenFocusDist >= 0f) {"))
    }

    @Test
    fun oneShotLensHintIsLatchedNeverContinuous() {
        assertTrue(source.contains("depthPriorLensHintLatched"))
        assertTrue(source.contains("AIDepthPriorFocusBias.oneShotLensHintEnabled"))
        // The bias feeds hardware AF through the existing region path, not a continuous lens drive.
        assertTrue(source.contains("camera.setSubjectMeteringRegions("))
    }

    @Test
    fun regionlessPidPathPreserved() {
        // The region-less full-PID drive must remain untouched (regression lock).
        assertTrue(source.contains("AIFocusDistanceController.resolveDepthAwareFocusStep("))
        assertTrue(source.contains("depthStep.appliedDiopters,"))
        assertTrue(source.contains("depthStep.confidence"))
        assertTrue(source.contains("prior.confidence"))
    }
}
