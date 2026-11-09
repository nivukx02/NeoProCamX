from __future__ import annotations

import hashlib
import json
import math
from pathlib import Path
import tempfile
import unittest

from tools.neo_qa_hypergrid.digital_twin import (
    BackendState,
    CameraLifecycleTwin,
    CameraState,
    ColourProfileLifecycleTwin,
    ColourProfilePacket,
    ControlPolicyTwin,
    ControlRequest,
    ConversionPipelineTwin,
    ConversionProfile,
    ConversionState,
    DigitalTwinGrid,
    EVIDENCE_SCOPE,
    EncoderCadenceTwin,
    EncoderState,
    EventJournal,
    ExactOnceResourceTracker,
    ExactOnceViolation,
    MediaStoreTwin,
    PermissionState,
    PermissionTwin,
    PlaybackSchedulerTwin,
    PlaybackState,
    PressureTwin,
    ProcessingBackendTwin,
    RawOwnershipTwin,
    TwinStatus,
    write_scenario_result_json,
)


def conversion_profile(bit_depth: int = 10) -> ConversionProfile:
    return ConversionProfile(
        source="RAW",
        destination="XDR",
        width=4096,
        height=3072,
        bit_depth=bit_depth,
        bitrate_bps=160_000_000,
    )


def metadata() -> dict[str, object]:
    return {
        "sensor_timestamp_ns": 1,
        "iso": 400,
        "exposure_ns": 20_000_000,
        "wb_gains": (1.0, 1.0, 1.0, 1.0),
    }


class EvidenceContractTests(unittest.TestCase):
    def test_scope_disclaims_physical_and_quality_proof(self) -> None:
        lower = EVIDENCE_SCOPE.lower()
        self.assertIn("source-contract", lower)
        self.assertIn("not proof", lower)
        self.assertIn("physical camera fps", lower)
        self.assertIn("image quality", lower)

    def test_event_journal_uses_logical_monotonic_sequence(self) -> None:
        journal = EventJournal()
        journal.record("a", "one", z=2, a=1)
        journal.record("a", "two")
        self.assertEqual([event.sequence for event in journal.events], [1, 2])
        self.assertEqual(list(journal.events[0].to_dict()["details"]), ["a", "z"])

    def test_exact_once_resource_happy_path(self) -> None:
        tracker = ExactOnceResourceTracker(EventJournal(), "test")
        tracker.acquire("token", "surface", 1)
        self.assertEqual(tracker.active_count, 1)
        tracker.release("token", "done")
        self.assertTrue(tracker.exact_once_clean)
        self.assertEqual(tracker.release_count, 1)

    def test_exact_once_resource_rejects_double_release(self) -> None:
        tracker = ExactOnceResourceTracker(EventJournal(), "test")
        tracker.acquire("token", "surface", 1)
        tracker.release("token", "done")
        with self.assertRaises(ExactOnceViolation):
            tracker.release("token", "again")
        self.assertFalse(tracker.exact_once_clean)

    def test_exact_once_resource_rejects_unknown_release(self) -> None:
        tracker = ExactOnceResourceTracker(EventJournal(), "test")
        with self.assertRaises(ExactOnceViolation):
            tracker.release("missing", "invalid")
        self.assertEqual(tracker.active_count, 0)
        self.assertEqual(len(tracker.violations), 1)


class LifecycleAndPermissionTests(unittest.TestCase):
    def test_permission_denial_permanent_denial_and_recovery_contract(self) -> None:
        permissions = PermissionTwin()
        permissions.deny("CAMERA")
        self.assertEqual(permissions.state("CAMERA"), PermissionState.DENIED)
        self.assertTrue(permissions.can_request("CAMERA"))
        permissions.deny("CAMERA", permanent=True)
        self.assertFalse(permissions.can_request("CAMERA"))
        permissions.grant("CAMERA")
        self.assertTrue(permissions.is_granted("CAMERA"))

    def test_camera_open_denied_is_truthful_and_allocates_nothing(self) -> None:
        permissions = PermissionTwin()
        camera = CameraLifecycleTwin(lambda: permissions.is_granted("CAMERA"))
        self.assertFalse(camera.open("Video"))
        self.assertEqual(camera.state, CameraState.ERROR)
        self.assertEqual(camera.error, "CAMERA_PERMISSION_UNAVAILABLE")
        self.assertEqual(camera.tracker.active_count, 0)

    def test_camera_mode_switch_releases_old_generation(self) -> None:
        permissions = PermissionTwin()
        permissions.grant("CAMERA")
        camera = CameraLifecycleTwin(lambda: permissions.is_granted("CAMERA"))
        self.assertTrue(camera.open("Video"))
        self.assertTrue(camera.activate())
        first_generation = camera.generation
        self.assertTrue(camera.switch_mode("Pro"))
        self.assertGreater(camera.generation, first_generation)
        self.assertEqual(camera.mode, "Pro")
        self.assertEqual(camera.tracker.active_count, 3)
        camera.close()
        self.assertTrue(camera.tracker.exact_once_clean)

    def test_camera_background_and_foreground_recovery(self) -> None:
        permissions = PermissionTwin()
        permissions.grant("CAMERA")
        camera = CameraLifecycleTwin(lambda: permissions.is_granted("CAMERA"))
        camera.open("Raw")
        camera.activate()
        self.assertTrue(camera.background())
        self.assertEqual(camera.state, CameraState.BACKGROUND)
        self.assertEqual(camera.tracker.active_count, 0)
        self.assertTrue(camera.recover("Raw"))
        camera.close()
        self.assertTrue(camera.tracker.exact_once_clean)

    def test_camera_same_mode_switch_is_generation_stable(self) -> None:
        permissions = PermissionTwin()
        permissions.grant("CAMERA")
        camera = CameraLifecycleTwin(lambda: permissions.is_granted("CAMERA"))
        camera.open("Video")
        camera.activate()
        generation = camera.generation
        self.assertTrue(camera.switch_mode("Video"))
        self.assertEqual(camera.generation, generation)
        camera.close()


class RawOwnershipTests(unittest.TestCase):
    def test_pre_acquire_backpressure_never_exceeds_capacity(self) -> None:
        raw = RawOwnershipTwin(capacity=2)
        self.assertTrue(raw.try_acquire(0, 1))
        self.assertTrue(raw.try_acquire(1, 1))
        self.assertFalse(raw.try_acquire(2, 1))
        self.assertEqual(raw.last_error, "RAW_CAPACITY_BACKPRESSURE")
        self.assertEqual(raw.peak_owned, 2)
        self.assertEqual(raw.tracker.active_count, 2)
        raw.cleanup_all("test")
        self.assertTrue(raw.tracker.exact_once_clean)

    def test_release_wakeup_allows_waiting_frame(self) -> None:
        raw = RawOwnershipTwin(capacity=1)
        raw.try_acquire(0, 7)
        self.assertFalse(raw.try_acquire(1, 7))
        raw.release(0, 7)
        self.assertTrue(raw.try_acquire(1, 7))
        self.assertEqual(raw.capacity_wakeups, 1)
        raw.release(1, 7)
        self.assertTrue(raw.tracker.exact_once_clean)

    def test_generation_cleanup_does_not_release_new_generation(self) -> None:
        raw = RawOwnershipTwin(capacity=3)
        raw.try_acquire(0, 1)
        raw.try_acquire(0, 2)
        self.assertEqual(raw.cleanup_generation(1, "stale_generation"), 1)
        self.assertEqual(raw.tracker.active_tokens, ("raw:2:0",))
        raw.cleanup_all("test")
        self.assertTrue(raw.tracker.exact_once_clean)

    def test_duplicate_active_frame_is_rejected_without_second_ownership(self) -> None:
        raw = RawOwnershipTwin(capacity=2)
        self.assertTrue(raw.try_acquire(0, 1))
        self.assertFalse(raw.try_acquire(0, 1))
        self.assertEqual(raw.last_error, "DUPLICATE_RAW_FRAME")
        self.assertEqual(raw.tracker.active_count, 1)
        raw.release(0, 1)


class EncoderContractTests(unittest.TestCase):
    def test_exact_25fps_cadence_and_delivery(self) -> None:
        encoder = EncoderCadenceTwin(25.0)
        encoder.configure()
        for frame_id in range(10):
            pts = frame_id * 40_000
            self.assertTrue(encoder.submit(frame_id, pts))
            self.assertTrue(encoder.emit(frame_id, pts, frame_id == 0))
        report = encoder.finalize()
        self.assertTrue(report.contract_pass)
        self.assertEqual(report.missing, 0)
        self.assertAlmostEqual(report.measured_output_fps or 0.0, 25.0)
        self.assertEqual(report.max_interval_error_us, 0.0)
        self.assertTrue(report.cadence_within_tolerance)
        self.assertFalse(report.frame_drop_detected)
        self.assertTrue(encoder.tracker.exact_once_clean)

    def test_starvation_is_detected_not_hidden(self) -> None:
        encoder = EncoderCadenceTwin(25.0)
        encoder.configure()
        for frame_id in range(5):
            encoder.submit(frame_id, frame_id * 40_000)
        encoder.emit(0, 0, True)
        encoder.emit(1, 40_000)
        report = encoder.finalize(require_all_frames=True)
        self.assertEqual(encoder.state, EncoderState.ERROR)
        self.assertEqual(encoder.error, "ENCODER_STARVATION")
        self.assertEqual(report.missing, 3)
        self.assertTrue(report.frame_drop_detected)
        self.assertFalse(report.contract_pass)
        self.assertTrue(encoder.tracker.exact_once_clean)

    def test_single_frame_cannot_claim_a_cadence_contract(self) -> None:
        encoder = EncoderCadenceTwin(25.0)
        self.assertTrue(encoder.configure())
        self.assertTrue(encoder.submit(0, 0))
        self.assertTrue(encoder.emit(0, 0, keyframe=True))
        report = encoder.finalize()
        self.assertFalse(report.contract_pass)
        self.assertEqual("ENCODER_CONTRACT_FAILED", encoder.error)

    def test_slow_output_cadence_fails_target_contract_even_without_missing_ids(self) -> None:
        encoder = EncoderCadenceTwin(25.0)
        encoder.configure()
        for frame_id in range(4):
            encoder.submit(frame_id, frame_id * 40_000)
            encoder.emit(frame_id, frame_id * 120_000, frame_id == 0)
        report = encoder.finalize()
        self.assertEqual(report.missing, 0)
        self.assertFalse(report.frame_drop_detected)
        self.assertFalse(report.cadence_within_tolerance)
        self.assertAlmostEqual(report.measured_output_fps or 0.0, 8.333333333, places=6)
        self.assertFalse(report.contract_pass)
        self.assertEqual(encoder.state, EncoderState.ERROR)
        self.assertEqual(encoder.error, "ENCODER_CADENCE_VIOLATION")

    def test_non_monotonic_input_pts_is_rejected(self) -> None:
        encoder = EncoderCadenceTwin(30.0)
        encoder.configure()
        self.assertTrue(encoder.submit(0, 10))
        self.assertFalse(encoder.submit(1, 10))
        self.assertEqual(encoder.error, "NON_MONOTONIC_INPUT_PTS")
        encoder.reset()
        self.assertTrue(encoder.tracker.exact_once_clean)

    def test_non_monotonic_output_pts_is_rejected(self) -> None:
        encoder = EncoderCadenceTwin(30.0)
        encoder.configure()
        encoder.submit(0, 10)
        encoder.submit(1, 20)
        encoder.emit(0, 10)
        self.assertFalse(encoder.emit(1, 10))
        self.assertEqual(encoder.error, "NON_MONOTONIC_OUTPUT_PTS")
        encoder.reset()

    def test_output_without_submitted_frame_is_rejected(self) -> None:
        encoder = EncoderCadenceTwin(25.0)
        encoder.configure()
        self.assertFalse(encoder.emit(99, 0))
        self.assertEqual(encoder.error, "OUTPUT_WITHOUT_INPUT")
        encoder.reset()

    def test_codec_configuration_rejection_cleans_partial_resource(self) -> None:
        encoder = EncoderCadenceTwin(25.0)
        self.assertFalse(encoder.configure(False, "UNSUPPORTED_PROFILE"))
        self.assertEqual(encoder.error, "UNSUPPORTED_PROFILE")
        self.assertEqual(encoder.tracker.active_count, 0)
        self.assertTrue(encoder.tracker.exact_once_clean)


class ControlAndColourTests(unittest.TestCase):
    def test_control_policy_clamps_all_sensor_invariants(self) -> None:
        result = ControlPolicyTwin().apply(
            ControlRequest(
                target_fps=25.0,
                iso=1,
                shutter_ns=999_999_999_999,
                ev=10.0,
                wb_kelvin=100,
                wb_tint=2.0,
                focus_diopters=99.0,
            )
        )
        self.assertTrue(result.invariant_pass)
        self.assertEqual(result.iso, 50)
        self.assertEqual(result.shutter_ns, 40_000_000)
        self.assertEqual(result.effective_ev, 4.0)
        self.assertEqual(result.wb_kelvin, 2000)
        self.assertEqual(result.focus_diopters, 10.0)
        self.assertEqual(
            set(result.clipped_fields),
            {"iso", "shutter_ns", "ev", "wb_kelvin", "wb_tint", "focus_diopters"},
        )
        self.assertTrue(all(math.isfinite(gain) for gain in result.wb_gains))

    def test_manual_exposure_does_not_double_apply_ev(self) -> None:
        result = ControlPolicyTwin().apply(
            ControlRequest(
                target_fps=25.0,
                auto_exposure=False,
                iso=800,
                shutter_ns=20_000_000,
                ev=2.0,
            )
        )
        self.assertEqual(result.effective_ev, 0.0)
        self.assertIn("ev", result.clipped_fields)

    def test_non_finite_control_is_rejected(self) -> None:
        with self.assertRaises(ValueError):
            ControlPolicyTwin().apply(ControlRequest(target_fps=25.0, ev=float("nan")))

    def test_all_supported_colour_modes_commit_matching_packet(self) -> None:
        colour = ColourProfileLifecycleTwin()
        for mode in colour.REQUIREMENTS:
            with self.subTest(mode=mode):
                colour.transition(mode)
                packet = colour.expected_packet(f"{mode}-calibration")
                self.assertTrue(colour.commit(packet))
                self.assertEqual(colour.committed, packet)

    def test_stale_colour_generation_is_rejected(self) -> None:
        colour = ColourProfileLifecycleTwin()
        colour.transition("NDR10_PLUS")
        old = colour.expected_packet("old")
        colour.transition("NIVUKX_VISION")
        self.assertFalse(colour.commit(old))
        self.assertEqual(colour.last_error, "STALE_PROFILE_GENERATION")
        self.assertIsNone(colour.committed)

    def test_colour_correction_and_science_mismatch_are_distinct(self) -> None:
        colour = ColourProfileLifecycleTwin()
        generation = colour.transition("XDR")
        wrong_correction = ColourProfilePacket(generation, "XDR", "OFF", "XDR", "fp")
        self.assertFalse(colour.commit(wrong_correction))
        self.assertEqual(colour.last_error, "PROFILE_CORRECTION_MISMATCH")
        wrong_science = ColourProfilePacket(generation, "XDR", "NDR", "SDR", "fp")
        self.assertFalse(colour.commit(wrong_science))
        self.assertEqual(colour.last_error, "PROFILE_SCIENCE_MISMATCH")


class ConversionPlaybackAndPublicationTests(unittest.TestCase):
    def test_conversion_preserves_required_contract_and_publishes_at_end(self) -> None:
        conversion = ConversionPipelineTwin()
        self.assertTrue(conversion.start(conversion_profile()))
        self.assertFalse(conversion.published)
        for frame_id in range(3):
            self.assertTrue(conversion.ingest(frame_id, frame_id * 40_000, metadata()))
        self.assertTrue(conversion.complete())
        self.assertEqual(conversion.state, ConversionState.COMPLETED)
        self.assertTrue(conversion.published)
        self.assertTrue(conversion.tracker.exact_once_clean)

    def test_conversion_rejects_less_than_ten_bit_contract(self) -> None:
        conversion = ConversionPipelineTwin()
        self.assertFalse(conversion.start(conversion_profile(bit_depth=8)))
        self.assertEqual(conversion.error, "INVALID_CONVERSION_PROFILE")
        self.assertFalse(conversion.published)

    def test_conversion_missing_metadata_fails_and_cleans(self) -> None:
        conversion = ConversionPipelineTwin()
        conversion.start(conversion_profile())
        self.assertFalse(conversion.ingest(0, 0, {"iso": 400}))
        self.assertEqual(conversion.error, "CONVERSION_METADATA_MISSING")
        self.assertFalse(conversion.published)
        self.assertTrue(conversion.tracker.exact_once_clean)

    def test_conversion_non_monotonic_pts_fails_and_cleans(self) -> None:
        conversion = ConversionPipelineTwin()
        conversion.start(conversion_profile())
        conversion.ingest(0, 10, metadata())
        self.assertFalse(conversion.ingest(1, 10, metadata()))
        self.assertEqual(conversion.error, "NON_MONOTONIC_CONVERSION_PTS")
        self.assertTrue(conversion.tracker.exact_once_clean)

    def test_conversion_cancel_never_publishes_partial_output(self) -> None:
        conversion = ConversionPipelineTwin()
        conversion.start(conversion_profile())
        conversion.ingest(0, 0, metadata())
        conversion.cancel()
        self.assertEqual(conversion.state, ConversionState.CANCELLED)
        self.assertFalse(conversion.published)
        self.assertTrue(conversion.tracker.exact_once_clean)

    def test_playback_exact_schedule_has_no_contract_drops(self) -> None:
        playback = PlaybackSchedulerTwin(late_threshold_us=5_000)
        for frame_id in range(4):
            playback.enqueue(frame_id, frame_id * 40_000)
        for frame_id in range(4):
            self.assertEqual(playback.present_next(1_000_000 + frame_id * 40_000), "RENDER")
        self.assertEqual(playback.state, PlaybackState.COMPLETE)
        self.assertEqual(playback.rendered, [0, 1, 2, 3])
        self.assertFalse(playback.dropped)

    def test_playback_waits_instead_of_rendering_a_future_frame_early(self) -> None:
        playback = PlaybackSchedulerTwin()
        playback.enqueue(0, 0)
        playback.enqueue(1, 40_000)
        self.assertEqual("RENDER", playback.present_next(1_000_000))
        self.assertEqual("WAIT", playback.present_next(1_020_000))
        self.assertEqual([0], playback.rendered)
        self.assertEqual("RENDER", playback.present_next(1_040_000))
        self.assertEqual([0, 1], playback.rendered)

    def test_playback_late_frame_drop_is_truthful(self) -> None:
        playback = PlaybackSchedulerTwin(late_threshold_us=1_000)
        playback.enqueue(0, 0)
        playback.enqueue(1, 40_000)
        self.assertEqual(playback.present_next(1_000_000), "RENDER")
        self.assertEqual(playback.present_next(1_100_000), "DROP")
        self.assertEqual(playback.dropped, [1])

    def test_playback_rejects_non_monotonic_pts(self) -> None:
        playback = PlaybackSchedulerTwin()
        self.assertTrue(playback.enqueue(0, 10))
        self.assertFalse(playback.enqueue(1, 10))
        self.assertEqual(playback.state, PlaybackState.ERROR)
        self.assertEqual(playback.error, "NON_MONOTONIC_PLAYBACK_PTS")

    def test_mediastore_atomic_success(self) -> None:
        media = MediaStoreTwin(500_000_000)
        self.assertTrue(media.begin("clip", 100_000_000, require_sidecar=True))
        media.write_main("clip")
        media.write_sidecar("clip")
        self.assertTrue(media.commit("clip"))
        self.assertIn("clip", media.published_items)
        self.assertFalse(media.has_corrupt_publication)
        self.assertTrue(media.tracker.exact_once_clean)
        self.assertEqual(media.free_bytes, 400_000_000)

    def test_mediastore_incomplete_transaction_aborts_without_publication(self) -> None:
        media = MediaStoreTwin(500_000_000)
        media.begin("clip", 100_000_000, require_sidecar=True)
        media.write_main("clip")
        self.assertFalse(media.commit("clip"))
        self.assertNotIn("clip", media.published_items)
        self.assertFalse(media.has_corrupt_publication)
        self.assertTrue(media.tracker.exact_once_clean)
        self.assertEqual(media.free_bytes, 500_000_000)

    def test_low_storage_does_not_create_pending_transaction(self) -> None:
        media = MediaStoreTwin(10)
        self.assertFalse(media.begin("clip", 11))
        self.assertEqual(media.last_error, "LOW_STORAGE")
        self.assertFalse(media.transactions)
        self.assertEqual(media.tracker.active_count, 0)

    def test_concurrent_pending_transactions_cannot_overcommit_storage(self) -> None:
        media = MediaStoreTwin(100)
        self.assertTrue(media.begin("first", 60))
        self.assertFalse(media.begin("second", 60))
        self.assertEqual("LOW_STORAGE", media.last_error)
        media.abort("first", "cancelled")
        self.assertTrue(media.begin("second", 60))

    def test_abort_cannot_unpublish_an_already_committed_item(self) -> None:
        media = MediaStoreTwin(100)
        self.assertTrue(media.begin("clip", 50, require_sidecar=False))
        self.assertTrue(media.write_main("clip"))
        self.assertTrue(media.commit("clip"))
        media.abort("clip", "late abort")
        self.assertIn("clip", media.published_items)
        self.assertEqual("MEDIA_TRANSACTION_NOT_PENDING", media.last_error)


class PressureBackendAndGridTests(unittest.TestCase):
    def test_thermal_pressure_separates_functional_and_performance_admission(self) -> None:
        pressure = PressureTwin(1_000_000)
        decision = pressure.evaluate(thermal_status=4, memory_used_bytes=100_000)
        self.assertFalse(decision.functional_admitted)
        self.assertFalse(decision.performance_measurement_admitted)
        self.assertEqual(decision.secondary_work_fraction, 0.35)
        self.assertEqual(decision.reason, "THERMAL_NOT_ADMITTED_FOR_FUNCTIONAL")

        moderate = pressure.evaluate(thermal_status=2, memory_used_bytes=100_000)
        self.assertTrue(moderate.functional_admitted)
        self.assertFalse(moderate.performance_measurement_admitted)
        self.assertEqual(moderate.reason, "THERMAL_NOT_ADMITTED_FOR_PERFORMANCE")

    def test_memory_pressure_blocks_functional_admission(self) -> None:
        pressure = PressureTwin(1_000_000)
        decision = pressure.evaluate(thermal_status=0, memory_used_bytes=1_000_001)
        self.assertFalse(decision.functional_admitted)
        self.assertFalse(decision.performance_measurement_admitted)
        self.assertEqual(decision.secondary_work_fraction, 0.0)
        self.assertEqual(decision.reason, "MEMORY_PRESSURE")

    def test_backend_partial_initialization_failure_is_cleaned(self) -> None:
        backend = ProcessingBackendTwin()
        self.assertFalse(backend.initialize(gpu_ok=True, native_ok=False))
        self.assertEqual(backend.state, BackendState.ERROR)
        self.assertEqual(backend.error, "NATIVE_INITIALIZATION_FAILED")
        self.assertTrue(backend.tracker.exact_once_clean)
        self.assertTrue(backend.initialize())
        backend.close()
        self.assertTrue(backend.tracker.exact_once_clean)

    def test_nominal_grid_passes_all_contract_assertions(self) -> None:
        result = DigitalTwinGrid(seed=17).run_nominal()
        self.assertEqual(result.status, TwinStatus.PASS)
        self.assertTrue(all(result.assertions.values()))
        self.assertGreater(result.metrics["event_count"], 0)
        self.assertIn("not proof", result.evidence_scope.lower())

    def test_nominal_grid_is_byte_deterministic_for_same_seed(self) -> None:
        first = DigitalTwinGrid(seed=1234).run_nominal()
        second = DigitalTwinGrid(seed=1234).run_nominal()
        self.assertEqual(first.to_dict(), second.to_dict())
        self.assertEqual(first.deterministic_sha256(), second.deterministic_sha256())

    def test_nominal_grid_seed_changes_deterministic_scenario(self) -> None:
        first = DigitalTwinGrid(seed=1).run_nominal()
        second = DigitalTwinGrid(seed=2).run_nominal()
        self.assertNotEqual(first.deterministic_sha256(), second.deterministic_sha256())

    def test_result_writer_is_atomic_canonical_and_repeatable(self) -> None:
        result = DigitalTwinGrid(seed=88).run_nominal()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "nested" / "result.json"
            first_hash = write_scenario_result_json(path, result)
            first_bytes = path.read_bytes()
            second_hash = write_scenario_result_json(path, result)
            second_bytes = path.read_bytes()
            self.assertEqual(first_bytes, second_bytes)
            self.assertEqual(first_hash, second_hash)
            self.assertEqual(first_hash, hashlib.sha256(first_bytes).hexdigest())
            self.assertTrue(first_bytes.endswith(b"\n"))
            parsed = json.loads(first_bytes)
            self.assertEqual(parsed["status"], "PASS")
            self.assertEqual(parsed["evidence_class"], "HOST_DIGITAL_TWIN_CONTRACT_ONLY")


if __name__ == "__main__":
    unittest.main()
