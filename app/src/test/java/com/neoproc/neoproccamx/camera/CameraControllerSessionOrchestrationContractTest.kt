package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * s1-f05 — source-text lock on the live CameraController session orchestration wiring. The
 * controller is Camera2-coupled and cannot be instantiated on the JVM runner, so (following the
 * established [CameraControllerHdrAwbContractTest] pattern) we assert the source text directly. This
 * locks: the canonical surface attach order, the dynamic-range role mapping, the record-path
 * dropped-frame accounting, the reconfigure-window bracketing, the DR-coherence accessor, and the
 * close-on-failure recovery — so a future edit cannot reorder the attach sequence, drop a frame
 * silently, or leak a session on recovery without tripping CI.
 */
class CameraControllerSessionOrchestrationContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    }
    private val p010ReaderFactorySource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/Npv10P010ImageReaderFactory.kt").readText()
    }

    // The exact session surface-assembly block, so ordering is asserted within the buildList only
    // (the streamGraph.*.includeInSession checks also appear in setupReaders).
    private val surfaceBuildBlock: String by lazy {
        source
            .substringAfter("private fun resolveSessionSurfaces(")
            .substringAfter("= buildList {")
            .substringBefore("}.distinct()")
    }

    private fun assertOrdered(haystack: String, vararg fragments: String) {
        var previousIndex = -1
        var previousFragment = ""
        for (fragment in fragments) {
            val index = haystack.indexOf(fragment)
            assertTrue("expected to find: $fragment", index >= 0)
            assertTrue(
                "expected \"$fragment\" to appear after \"$previousFragment\"",
                index > previousIndex
            )
            previousIndex = index
            previousFragment = fragment
        }
    }

    @Test
    fun `session surfaces attach preview then encoder then optional readers in canonical order`() {
        assertOrdered(
            surfaceBuildBlock,
            "PersistentPreviewSessionOutputPolicy.resolve(",
            "activePreview = previewWarmupSurface ?: previewSurface",
            "persistentSdrPreview = persistentSdrPreviewSurface",
            "persistentHdrPreview = persistentHdrPreviewSurface",
            "constrainToVisiblePreview = recordingSessionSurfaceBudgetConstrained(streamGraph)",
            "recordSurface?.let(::add)",
            "if (streamGraph.jpegStill.includeInSession)",
            "if (streamGraph.rawSensor.includeInSession)",
            "if (streamGraph.yuvAnalysis.includeInSession) {",
            "if (streamGraph.hardwareImport.includeInSession)"
        )
    }

    @Test
    fun `direct hardware import recording constrains session to visible preview without quality changes`() {
        val budgetBlock = source
            .substringAfter("private fun recordingSessionSurfaceBudgetConstrained(")
            .substringBefore("private fun resolveSessionSurfaces(")

        assertTrue(budgetBlock.contains("recordingActive"))
        assertTrue(budgetBlock.contains("streamGraph.mode == CinemaStreamGraphMode.ENCODED_CINEMA"))
        assertTrue(budgetBlock.contains("streamGraph.hardwareImport.includeInSession"))
        assertFalse(budgetBlock.contains("targetCameraFps"))
        assertFalse(budgetBlock.contains("recordingEncoderTargetSize"))
        assertFalse(budgetBlock.contains("bitrate"))
    }

    @Test
    fun `dynamic range role mapping treats npv10 p010 as recording and ordinary yuv as analysis`() {
        assertTrue(source.contains("previewSurface -> HdrSurfaceRole.PREVIEW"))
        assertTrue(source.contains("recordSurface -> HdrSurfaceRole.ENCODER"))
        assertTrue(source.contains("hardwareImportReader?.surface -> HdrSurfaceRole.ENCODER"))
        assertTrue(source.contains("yuvReader?.surface -> if (npv10TenBitCaptureEnabled)"))
        val yuvRole = source
            .substringAfter("yuvReader?.surface -> if (npv10TenBitCaptureEnabled)")
            .substringBefore("hardwareImportReader?.surface")
        assertTrue(yuvRole.contains("HdrSurfaceRole.ENCODER"))
        assertTrue(yuvRole.contains("HdrSurfaceRole.ANALYSIS"))
        // The DR profile is applied per-surface during the single session build.
        assertTrue(source.contains("val role = hdrSurfaceRoleFor(surface)"))
        assertTrue(source.contains("HdrDynamicRangePolicy.apply("))
    }

    @Test
    fun `p010 reader preserves HAL delivery and never manufactures dataspace authority`() {
        assertTrue(source.contains("Npv10P010ImageReaderFactory.create("))
        assertTrue(source.contains("Npv10P010ImageReaderFactory.matches("))
        assertTrue(p010ReaderFactorySource.contains("LEGACY_P010_HAL_DEFAULT_DATASPACE"))
        assertTrue(p010ReaderFactorySource.contains("ImageReader.newInstance(width, height, imageFormat, maxImages)"))
        assertTrue(p010ReaderFactorySource.contains("requireNotNull(transport)"))
        assertFalse(p010ReaderFactorySource.contains("setDefaultDataSpace("))
        assertFalse(p010ReaderFactorySource.contains("setColorSpace("))
        assertTrue(p010ReaderFactorySource.contains("unsupported(\"PQ_PROFILE_UNAVAILABLE\")"))
        assertTrue(p010ReaderFactorySource.contains("DATA_SPACE_UNKNOWN"))
    }

    @Test
    fun `record path capture callback feeds the honest dropped-frame ledger`() {
        assertTrue(source.contains("private val recordFrameDeliveryLedger = CameraRecordFrameDeliveryLedger()"))
        assertTrue(source.contains("recordFrameDeliveryLedger.onFrameCompleted("))
        assertTrue(source.contains("recordingActive = recordingActive && recordCadenceFrame"))
        assertTrue(
            "onCaptureFailed must be overridden so a dropped frame is never silent",
            source.contains("override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure)")
        )
        assertTrue(source.contains("override fun onCaptureBufferLost("))
        assertTrue(source.contains("recordFrameDeliveryLedger.onCaptureFailed("))
        assertTrue(source.contains("recordFrameDeliveryLedger.onBufferLost(recordingActive)"))
        // A FLUSHED failure is an intended transition drop, not a steady-state drop.
        assertTrue(source.contains("failure.reason == CaptureFailure.REASON_FLUSHED"))
    }

    @Test
    fun `reconfigure transitions are bracketed so drops during a rebuild are bounded not silent`() {
        assertTrue(
            "refreshOutputsAndSession must open the reconfigure window before rebuilding",
            source.contains("recordFrameDeliveryLedger.markReconfigureStart()")
        )
        assertTrue(
            "the reconfigure window must be settled when the replacement session configures or fails",
            source.contains("recordFrameDeliveryLedger.markReconfigureSettled()")
        )
        // The hot-swap keeps the old session streaming until the replacement configures (no black gap).
        assertTrue(source.contains("keeping current session streaming until replacement configures"))
    }

    @Test
    fun `dynamic range coherence accessor exposes negotiated vs requested truth`() {
        assertTrue(source.contains("fun describeDynamicRangeCoherence(): CameraDynamicRangeCoherenceReport"))
        assertTrue(source.contains("CameraDynamicRangeCoherencePolicy.evaluate("))
        assertTrue(source.contains("recordingEncoderActive = recordingActive"))
    }

    @Test
    fun `session failures close the failed session and route through the recovery policy`() {
        // Camera2 requires closing a failed session so ImageReader buffers are freed (no leak).
        assertTrue(source.contains("override fun onConfigureFailed(s: CameraCaptureSession)"))
        assertTrue(source.contains("runCatching { s.close() }"))
        assertTrue(source.contains("CameraSessionCreateFailurePolicy.resolve("))
    }

    @Test
    fun `stale session configure failures do not poison the live preview session`() {
        val failureBlock = source
            .substringAfter("override fun onConfigureFailed(s: CameraCaptureSession) {")
            .substringBefore("val sidecarFallback = resolveReadoutSidecarSessionFallback()")

        assertOrdered(
            failureBlock,
            "runCatching { s.close() }",
            "if (cameraDevice == null || generation != sessionGeneration.get()) {",
            "Ignoring stale Camera2 session configuration failure",
            "return"
        )
        assertOrdered(
            failureBlock,
            "generation != sessionGeneration.get()",
            "Camera session rejected hardware import surface"
        )
        assertTrue(
            "terminal failure logging must stay behind the stale-generation guard",
            source.indexOf("Ignoring stale Camera2 session configuration failure") <
                source.indexOf("Camera session configuration failed")
        )
    }

    @Test
    fun `replacement repeating request restores active recording lifecycle`() {
        val repeatingAcceptedBlock = source
            .substringAfter("private fun startPreviewInternal(")
            .substringBefore("private fun identitySurfaceSet(")

        assertTrue(source.contains("val repeatingRequest = buildSoftwareAeTrackedRequest(builder)"))
        assertTrue(repeatingAcceptedBlock.contains("submitTrackedRepeatingRequest("))
        assertTrue(repeatingAcceptedBlock.contains("if (recordingActive) CameraState.Recording"))
        assertTrue(repeatingAcceptedBlock.contains("else CameraState.Streaming"))
        assertTrue(repeatingAcceptedBlock.contains("reason = \"repeating-request-active\""))
    }
}
