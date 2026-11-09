from __future__ import annotations

import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

from elite_camera_pipeline_simulation import (  # noqa: E402
    DeliveryProof,
    ModeAdmissionState,
    ModeCapabilities,
    NdrProfileGate,
    PreviewHandoff,
    PreviewOwner,
    ProfileCommitResult,
    VideoMode,
    WhiteBalanceStabilizer,
    bounded_detail_recovery,
    choose_texture_transform,
    chronological_history,
    pq_decode_nits,
    pq_encode_nits,
    presentation_stats,
    resolve_bitrate,
    resolve_mode_admission,
    simulate_latest_frame_processor,
    slot_history,
    transform_uv,
)


class EliteCameraPipelineSimulationTest(unittest.TestCase):
    def test_mode_admission_retains_identity_and_never_substitutes_pro(self) -> None:
        unavailable = ModeCapabilities(False, False, False, True, True, True)
        raw = resolve_mode_admission(VideoMode.RAW, unavailable)
        ndr = resolve_mode_admission(VideoMode.NDR, unavailable)

        self.assertEqual(VideoMode.RAW, raw.requested_mode)
        self.assertEqual(ModeAdmissionState.REJECTED, raw.state)
        self.assertIn("RAW_CAPTURE_UNAVAILABLE", raw.failure_codes)
        self.assertFalse(raw.recording_allowed)
        self.assertEqual(VideoMode.NDR, ndr.requested_mode)
        self.assertEqual(ModeAdmissionState.REJECTED, ndr.state)
        self.assertNotEqual(VideoMode.PRO, ndr.requested_mode)

    def test_every_processed_mode_requires_complete_p010_main10_chain(self) -> None:
        complete = ModeCapabilities(True, True, True, True, True, True)
        no_encoder = ModeCapabilities(True, True, True, True, False, True)
        for mode in (VideoMode.VIDEO, VideoMode.PRO, VideoMode.NDR, VideoMode.LOG):
            self.assertTrue(resolve_mode_admission(mode, complete).recording_allowed)
            rejected = resolve_mode_admission(mode, no_encoder)
            self.assertEqual(mode, rejected.requested_mode)
            self.assertFalse(rejected.recording_allowed)
            self.assertIn("TEN_BIT_TRANSPORT_UNAVAILABLE", rejected.failure_codes)

    def test_android_displayed_fps_never_uses_sensor_arrival_timestamps(self) -> None:
        activity = (
            ROOT
            / "app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt"
        ).read_text(encoding="utf-8")
        presentation_collector = activity.split(
            "vm.previewDisplayPresentEvents.collect { event ->", 1
        )[1].split("vm.previewFrameTimestamps.collect", 1)[0]
        sensor_collector = activity.split("vm.previewFrameTimestamps.collect", 1)[1].split(
            "vm.liveResult.collect", 1
        )[0]
        self.assertIn(
            "notifyFramePresented(event.presentationTimestampNs)", presentation_collector
        )
        self.assertNotIn("notifyFramePresented", sensor_collector)

    def test_all_video_modes_are_at_least_ten_bit(self) -> None:
        proofs = [
            DeliveryProof(mode, 10, 16, 10, 10, True)
            for mode in (VideoMode.VIDEO, VideoMode.PRO, VideoMode.NDR, VideoMode.LOG)
        ] + [DeliveryProof(VideoMode.RAW, 10, 16, 10, None, True)]
        for proof in proofs:
            proof.validate_strict_ten_bit()
            self.assertGreaterEqual(proof.minimum_meaningful_bits, 10)

    def test_eight_bit_or_unproven_delivery_fails_closed(self) -> None:
        for proof in (
            DeliveryProof(VideoMode.PRO, 10, 16, 8, 8, True),
            DeliveryProof(VideoMode.NDR, 10, 16, 10, 10, False),
        ):
            with self.assertRaises(ValueError):
                proof.validate_strict_ten_bit()

    def test_pq_round_trip_is_monotone_and_precise(self) -> None:
        nits = [0.0, 0.01, 0.1, 1.0, 10.0, 100.0, 203.0, 1_000.0, 4_000.0, 10_000.0]
        codes = [pq_encode_nits(value) for value in nits]
        self.assertEqual(codes, sorted(codes))
        for expected, code in zip(nits, codes):
            actual = pq_decode_nits(code)
            tolerance = max(1.0e-6, expected * 1.0e-8)
            self.assertAlmostEqual(expected, actual, delta=tolerance)

    def test_project_owned_texture_uses_its_own_transform(self) -> None:
        identity = (
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0,
        )
        rotate_90 = (
            0.0, 1.0, 0.0, 0.0,
            -1.0, 0.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            1.0, 0.0, 0.0, 1.0,
        )
        selected = choose_texture_transform(True, identity, rotate_90)
        self.assertIs(selected, rotate_90)
        self.assertEqual((0.75, 0.25), transform_uv(selected, 0.25, 0.25))

    def test_final_presented_timestamps_expose_the_raw_bottleneck(self) -> None:
        slow = simulate_latest_frame_processor(25.0, 235.0, 12.0)
        fast = simulate_latest_frame_processor(25.0, 32.0, 12.0)
        slow_stats = presentation_stats(slow, 25.0)
        fast_stats = presentation_stats(fast, 25.0)
        self.assertLess(slow_stats.achieved_fps, 5.0)
        self.assertGreaterEqual(fast_stats.achieved_fps, 24.9)
        self.assertEqual(0, fast_stats.missing_intervals)

    def test_latest_frame_mailbox_never_relabels_the_in_flight_frame(self) -> None:
        presented = simulate_latest_frame_processor(25.0, 235.0, 1.0)
        self.assertEqual(0, presented[0])
        self.assertEqual(sorted(presented), presented)
        self.assertEqual(len(presented), len(set(presented)))

    def test_handoff_never_publishes_black_or_stale_epoch(self) -> None:
        handoff = PreviewHandoff(visible_frame="camera-frame-100")
        handoff.begin(11)
        self.assertEqual("camera-frame-100", handoff.visible_frame)
        self.assertFalse(handoff.present_proven(10, "stale-raw"))
        self.assertEqual("camera-frame-100", handoff.visible_frame)
        handoff.reject(11)
        self.assertEqual("camera-frame-100", handoff.visible_frame)
        handoff.begin(12)
        self.assertTrue(handoff.present_proven(12, "raw-frame-120"))
        self.assertEqual(PreviewOwner.PROJECT, handoff.owner)
        self.assertEqual("raw-frame-120", handoff.visible_frame)

    def test_ndr_profile_gate_rejects_stale_incoherent_and_unapplied_packets(self) -> None:
        gate = NdrProfileGate()
        gate.begin(7)
        self.assertFalse(gate.accepts_frame(7))
        self.assertEqual(
            ProfileCommitResult.STALE,
            gate.commit(6, coherent=True, applied=True),
        )
        self.assertEqual(
            ProfileCommitResult.INCOHERENT,
            gate.commit(7, coherent=False, applied=True),
        )
        self.assertEqual(
            ProfileCommitResult.APPLY_FAILED,
            gate.commit(7, coherent=True, applied=False),
        )
        self.assertFalse(gate.accepts_frame(7))
        self.assertEqual(
            ProfileCommitResult.COMMITTED,
            gate.commit(7, coherent=True, applied=True),
        )
        self.assertTrue(gate.accepts_frame(7))
        self.assertFalse(gate.accepts_frame(6))

    def test_chronological_history_eliminates_slot_order_temporal_jumps(self) -> None:
        frames = [100, 101, 102, 103, 104, 105]
        slots = [0, 2, 1, 0, 1, 2]
        correct = chronological_history(frames)
        broken = slot_history(frames, slots)
        self.assertTrue(all(previous is None or current - previous == 1 for current, previous in correct))
        self.assertTrue(any(previous is not None and current - previous > 1 for current, previous in broken))

    def test_wb_one_frame_outlier_is_held_and_normal_motion_is_smoothed(self) -> None:
        stabilizer = WhiteBalanceStabilizer()
        baseline = stabilizer.observe((2.0, 1.0, 1.0, 1.5))
        outlier = stabilizer.observe((8.0, 1.0, 1.0, 0.4))
        self.assertEqual(baseline, outlier)
        adapted = stabilizer.observe((2.2, 1.0, 1.0, 1.4))
        self.assertGreater(adapted[0], 2.0)
        self.assertLess(adapted[0], 2.2)

    def test_wb_sustained_large_change_requires_confirmation_then_adapts(self) -> None:
        stabilizer = WhiteBalanceStabilizer()
        baseline = stabilizer.observe((2.0, 1.0, 1.0, 1.5))
        first = stabilizer.observe((4.0, 1.0, 1.0, 0.8))
        second = stabilizer.observe((4.0, 1.0, 1.0, 0.8))
        third = stabilizer.observe((4.0, 1.0, 1.0, 0.8))
        self.assertEqual(baseline, first)
        self.assertGreater(second[0], baseline[0])
        self.assertLess(second[0], 4.0)
        self.assertGreater(third[0], second[0])

    def test_bitrate_tracks_scene_without_exceeding_codec_ceiling(self) -> None:
        clean = resolve_bitrate(3840, 2160, 25.0, 0.2, 0.05, 240_000_000)
        complex_scene = resolve_bitrate(3840, 2160, 25.0, 0.95, 0.3, 240_000_000)
        self.assertGreater(complex_scene.target_bps, clean.target_bps)
        self.assertGreaterEqual(clean.target_bps, clean.floor_bps)
        self.assertLessEqual(complex_scene.target_bps, complex_scene.ceiling_bps)

    def test_bitrate_fails_closed_when_codec_cannot_meet_quality_floor(self) -> None:
        with self.assertRaisesRegex(ValueError, "strict quality floor"):
            resolve_bitrate(3840, 2160, 60.0, 1.0, 1.0, 1_000_000)

    def test_detail_recovery_is_bounded_and_has_no_step_edge_overshoot(self) -> None:
        step = [0.1] * 4 + [0.8] * 4
        result = bounded_detail_recovery(step, amount=1.0)
        self.assertGreaterEqual(min(result), min(step))
        self.assertLessEqual(max(result), max(step))
        self.assertTrue(all(abs(a - b) <= 0.035 + 1.0e-9 for a, b in zip(step, result)))


class ProductionSourceContractTest(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_ndr_render_uses_project_owned_transform_and_orientation(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/rendering/Hdr10PqPreviewCompositor.kt"
        )
        self.assertIn(
            "val sourceMatrix = if (useProjectOwned) projectOwnedTexMatrix else texMatrix",
            source,
        )
        self.assertIn(
            "val sourceOrientation = if (useProjectOwned) projectOwnedOrient else IDENTITY_ORIENT",
            source,
        )

    def test_raw_texture_sampling_uses_inverse_display_rotation(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/rendering/RawDebayerPreviewTransformPolicy.kt"
        )
        self.assertIn(
            "normalizeDegrees(-workingPreviewTransform.rotationDegrees)", source
        )
        self.assertIn("normalizeDegrees(-previewRotationDegrees)", source)

    def test_ndr_default_does_not_force_sdr_eight_bit_output(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/rendering/Hdr10PqPreviewSdrOutputFlag.kt"
        )
        self.assertIn("var enabled: Boolean = false", source)

    def test_log_is_not_silently_normalized_to_pro(self) -> None:
        source = self.read("app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        block = source.split("private fun normalizeNpv10CinemaMode", 1)[1].split("\n\n", 1)[0]
        self.assertNotIn("CinemaVideoMode.PRO", block)

    def test_quality_headline_is_bound_to_overall_certificate_pass(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicy.kt"
        )
        self.assertIn(
            "eliteQualityProven = status == RecordingQualityEvidenceStatus.PASS &&",
            source,
        )
        self.assertIn("request.hasCompleteEliteCertificationEvidence()", source)
        self.assertIn(
            '"beyondImaxQualityProven" to eliteQualityProven.toString()',
            source,
        )

    def test_expected_frames_include_independently_observed_missing_frames(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt"
        )
        self.assertIn("expectedFrameCount = captureLedger.ingressFrames", source)

    def test_npv10_full_sensor_capture_uses_direct_pack_then_bounded_write(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt"
        )
        enqueue = source.split("fun enqueueP010Image(image: Image): Boolean", 1)[1].split(
            "private fun publishPreparedP010Commit", 1
        )[0]
        publish = source.split("private fun publishPreparedP010Commit", 1)[1].split(
            "private fun commitPreparedP010InOrder", 1
        )[0]
        commit = source.split("private fun commitPreparedP010InOrder", 1)[1].split(
            "private fun retireRejectedPreparedP010Commit", 1
        )[0]
        worker_index = enqueue.index("p010PackingExecutor.execute")
        # The production call now uses named arguments so session ownership is explicit. Keep this
        # contract structural instead of depending on a one-line positional spelling.
        copy_index = enqueue.index("prepareP010ImageForAsyncWrite(")
        publish_index = enqueue.index("publishPreparedP010Commit(requireNotNull(completion))")
        prepare_call = enqueue[copy_index:publish_index]
        self.assertIn("image = image", prepare_call)
        self.assertIn("config = sessionConfig", prepare_call)
        self.assertLess(worker_index, copy_index)
        self.assertLess(copy_index, publish_index)
        self.assertNotIn("writerExecutor.execute", enqueue)
        self.assertIn("writerExecutor.execute", publish)
        self.assertIn("writePreparedP010Packet", commit)
        self.assertIn("Semaphore(activeMaxPendingFrames, true)", source)
        self.assertIn("releasePreparedP010Resources(", commit)
        self.assertIn("completion,", commit)
        self.assertIn("abortDirectMappedFrame = !persistenceCommitted", commit)
        self.assertIn("preparedPacket.decodedPayloadBytes.toLong()", commit)
        self.assertIn("packP010ImageToPackedTenBitBufferWithCrcResult", source)
        self.assertIn("primeP010PacketBufferPool(config)", source)

    def test_visible_gl_outputs_require_real_ten_bit_egl_configs(self) -> None:
        for relative in (
            "app/src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt",
            "app/src/main/java/com/neoproc/neoproccamx/rendering/PersistentPreviewOutputRouter.kt",
        ):
            source = self.read(relative)
            self.assertIn("EGL14.EGL_RED_SIZE, 10", source)
            self.assertIn("EGL14.EGL_GREEN_SIZE, 10", source)
            self.assertIn("EGL14.EGL_BLUE_SIZE, 10", source)
            self.assertIn("EGL14.EGL_ALPHA_SIZE, 2", source)
            self.assertIn("isStrictTenBitConfig", source)

    def test_persistent_router_final_surface_is_native_bt2020_pq(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/rendering/PersistentPreviewOutputRouter.kt"
        )
        self.assertIn("EGL_GL_COLORSPACE_BT2020_PQ_EXT", source)
        self.assertIn("createPqWindowSurface", source)
        self.assertIn("pqEncodeNits", source)
        self.assertNotIn("createSrgbWindowSurface", source)

    def test_strict_preview_failure_never_binds_direct_camera2_surface(self) -> None:
        preparation = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/"
            "PersistentPreviewPreparationCoordinator.kt"
        )
        retry_path = preparation.split(
            "ActivationDisposition.RETRYABLE_STALE ->", 1
        )[1].split("ActivationDisposition.TERMINAL_FAILURE ->", 1)[0]
        failure_path = preparation.split(
            "ActivationDisposition.TERMINAL_FAILURE ->", 1
        )[1].split("inProgress = false", 1)[0]
        view_model = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        )
        persistent_apply = view_model.split(
            "private fun applyPersistentPreviewPipeline(", 1
        )[1].split("private fun shouldCompositeHdr10PqPreview(", 1)[0]

        self.assertIn("router.startWithResult(", preparation)
        self.assertIn("ActivationDisposition.RETRYABLE_STALE", preparation)
        self.assertNotIn("failed = true", retry_path)
        self.assertNotIn("onStrictOutputRejected", retry_path)
        self.assertIn("failed = true", failure_path)
        self.assertIn("camera.setPreviewSurface(null)", failure_path)
        self.assertIn("onStrictOutputRejected(startResult)", failure_path)
        self.assertNotIn("camera.setPreviewSurface(request.visibleSurface)", failure_path)
        self.assertNotIn("onDirectFallback", preparation)
        self.assertIn("strictPreviewOutputFailure == null", view_model)
        self.assertNotIn("using single-preview fallback", persistent_apply)
        self.assertIn("direct fallback blocked", persistent_apply)
        self.assertIn("rejectStrictPreviewOutput(", persistent_apply)

    def test_runtime_egl_failure_is_one_shot_and_detaches_camera2(self) -> None:
        router = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/rendering/"
            "PersistentPreviewOutputRouter.kt"
        )
        sdr = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/rendering/PreviewGlCompositor.kt"
        )
        hdr = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/rendering/"
            "Hdr10PqPreviewCompositor.kt"
        )
        coordinator = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/"
            "StrictPreviewRuntimeFailureCoordinator.kt"
        )
        admission_policy = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/StrictPreviewAdmissionPolicy.kt"
        )
        view_model = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        )
        failure_handler = view_model.split(
            "private fun handleStrictPreviewRuntimeFailure(", 1
        )[1].split("private fun markStrictPreviewAdmissionPending()", 1)[0]
        rejection_handler = view_model.split(
            "private fun rejectStrictPreviewOutput(", 1
        )[1].split("private fun handleStrictPreviewRuntimeFailure(", 1)[0]
        coordinator_wiring = view_model.split(
            "StrictPreviewRuntimeFailureCoordinator(", 1
        )[1].split("private val persistentPreviewPreparation", 1)[0]

        for stage, component in (
            (router, "PreviewRuntimeComponent.OUTPUT_ROUTER"),
            (sdr, "PreviewRuntimeComponent.SDR_CHILD"),
            (hdr, "PreviewRuntimeComponent.HDR_CHILD"),
        ):
            self.assertIn("PreviewRuntimeOperation.EGL_MAKE_CURRENT", stage)
            self.assertIn("PreviewRuntimeOperation.EGL_SWAP_BUFFERS", stage)
            self.assertIn("val eglError = EGL14.eglGetError()", stage)
            self.assertIn(component, stage)
            self.assertIn("runtimeFailureGate.tryClaimActiveFailure()", stage)

        self.assertIn(
            "val failureEpoch = routeGate.claimFailure(onFailureDetected) ?: return",
            coordinator,
        )
        self.assertIn("beforeRouteHealthMutation()", coordinator)
        self.assertIn("routeGate.isCurrent(failureEpoch)", coordinator)
        self.assertIn("setOnRuntimeFailureListener(null)", coordinator)
        self.assertIn("onFailureDetected = {", coordinator_wiring)
        self.assertIn(
            "strictNativePreviewProfileCoordinator.onRouteChanged()",
            coordinator_wiring,
        )
        self.assertIn(
            "strictPreviewAdmissionState = StrictPreviewAdmissionState.FAILED",
            coordinator_wiring,
        )
        self.assertIn("disablePersistentPreviewPipeline(releaseInputs = false)", failure_handler)
        self.assertIn("rejectStrictPreviewOutput(runtimeFailure)", failure_handler)
        self.assertIn("camera.setPreviewSurface(null)", rejection_handler)
        self.assertIn("camera.refreshRepeatingRequest()", failure_handler)
        self.assertIn("forceRebuild = true", failure_handler)
        self.assertIn("onPreviousSessionClosed = releaseFailedRoute", failure_handler)
        self.assertIn("disablePersistentPreviewPipeline(releaseInputs = true)", failure_handler)
        self.assertIn(
            "StrictPreviewAdmissionPolicy.runtimeFailure(failure)", failure_handler
        )
        self.assertIn("StartFailure.RUNTIME_EGL_FAILURE", admission_policy)

    def test_armed_ndr_route_holds_last_validated_frame_not_camera_proxy(self) -> None:
        compositor = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/rendering/"
            "Hdr10PqPreviewCompositor.kt"
        )
        lifecycle = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/"
            "PersistentPreviewLifecycleCoordinator.kt"
        )

        self.assertIn("NdrVisiblePreviewSource.HOLD_LAST_VALIDATED", compositor)
        self.assertIn("if (visibleSource == NdrVisiblePreviewSource.HOLD_LAST_VALIDATED)", compositor)
        self.assertIn("updatePresentation(null, incomingOwner)", lifecycle)
        self.assertIn("retained project-owned HDR output", lifecycle)

    def test_hdr_dynamic_range_profile_application_fails_closed(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/camera/HdrDynamicRangePolicy.kt"
        )
        self.assertIn("throw DynamicRangeProfileApplicationException", source)
        self.assertNotIn("Fall back to STANDARD silently", source)

    def test_sdr_eight_bit_ndr_debug_route_cannot_be_enabled(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/rendering/Hdr10PqPreviewSdrOutputFlag.kt"
        )
        setter = source.split("fun set(value: Boolean)", 1)[1].split("\n    }", 1)[0]
        self.assertNotIn("enabled = value", setter)

    def test_hdr_preview_handoffs_never_publish_synthetic_black_by_default(self) -> None:
        compositor = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/rendering/Hdr10PqPreviewCompositor.kt"
        )
        view_model = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        )
        self.assertGreaterEqual(compositor.count("publishInitialBlack: Boolean = false"), 2)
        self.assertNotIn("publishInitialBlack: Boolean = true", compositor)
        self.assertIn("publishInitialBlack = false", view_model)

    def test_ndr_profile_generation_blocks_frames_until_atomic_packet_commit(self) -> None:
        runtime = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        boundary = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/rendering/NdrNativeFrameBoundary.kt"
        )
        camera = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        )
        lifecycle = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/"
            "NdrRenderProfileLifecycleCoordinator.kt"
        )
        standalone_update = runtime.split(
            "internal fun updateNdrNivukxMeasuredCorrection(", 1
        )[1].split("private fun applyNdrNivukxMeasuredCorrectionIfNeeded", 1)[0]
        self.assertNotIn("applyNdrNivukxMeasuredCorrectionIfNeeded()", standalone_update)
        # Every native frame ingress now takes a fair read lease, while generation advance,
        # packet apply/replay/finalize and close take the exclusive writer boundary. This is
        # stronger than repeatedly sampling a Boolean packet predicate around separate JNI calls.
        self.assertIn("ReentrantReadWriteLock(true)", boundary)
        self.assertIn("private val ingressState = AtomicLong", boundary)
        self.assertIn("val observedState = ingressState.get()", boundary)
        self.assertIn("if (!ingressOpen(observedState))", boundary)
        self.assertIn("if (ingressState.get() != observedState)", boundary)
        self.assertIn("requireNoFrameLeaseUpgrade()", boundary)
        self.assertGreaterEqual(runtime.count("acquireFrameLease()"), 10)
        self.assertGreaterEqual(runtime.count("withMutationLease"), 4)
        self.assertIn("ndrNativeFrameBoundary.finalizeIngress", runtime)
        self.assertIn("ndrNativeFrameBoundary.closeIngressForMutation()", runtime)
        self.assertIn(
            "renderBundle.identity != submission.renderProfileIdentity",
            runtime,
        )
        self.assertIn("if (generation <= 0L || revision <= 0L ||", runtime)
        self.assertIn(
            "bundle.profileGeneration != generation || bundle.configRevision != revision",
            runtime,
        )
        self.assertIn("NdrRenderProfileCommitResult.INCOHERENT_PACKET", runtime)
        self.assertIn(
            "NdrRenderProfileLifecycleCoordinator.commitForFrameIngress(", camera
        )
        self.assertIn("NDR_RENDER_CONFIG_REJECTED result=$result", lifecycle)


if __name__ == "__main__":
    unittest.main(verbosity=2)
