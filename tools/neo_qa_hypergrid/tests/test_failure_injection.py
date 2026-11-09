from __future__ import annotations

import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from tools.neo_qa_hypergrid.digital_twin import TwinStatus, write_scenario_result_json
from tools.neo_qa_hypergrid.failure_injection import (
    FailureInjectionEngine,
    FailureKind,
)


class FailureInjectionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.engine = FailureInjectionEngine(seed=20260819)

    def assertSafePass(self, kind: FailureKind) -> None:  # noqa: N802 - unittest convention
        outcome = self.engine.run(kind)
        self.assertEqual(outcome.kind, kind)
        self.assertEqual(outcome.status, TwinStatus.PASS, outcome.to_dict())
        self.assertTrue(outcome.recovered)
        self.assertTrue(outcome.exact_once_cleanup)
        self.assertTrue(outcome.corrupt_publication_absent)
        self.assertTrue(all(outcome.assertions.values()), outcome.to_dict())
        self.assertIn("not proof", outcome.evidence_scope.lower())
        self.assertEqual(outcome.evidence_class, "HOST_FAILURE_INJECTION_CONTRACT_ONLY")

    def test_failure_catalog_covers_every_required_injection(self) -> None:
        self.assertEqual(
            {kind.value for kind in FailureKind},
            {
                "camera_disconnect",
                "codec_configuration_rejection",
                "encoder_starvation",
                "raw_image_reader_exhaustion",
                "gpu_native_initialization_failure",
                "main_publication_failure",
                "sidecar_publication_failure",
                "low_storage",
                "permission_revocation",
                "surface_loss",
                "lifecycle_cancellation",
                "stale_generation",
                "thermal_throttling",
                "conversion_cancellation",
                "playback_decoder_failure",
            },
        )

    def test_camera_disconnect_cleanup_and_recovery(self) -> None:
        outcome = self.engine.run(FailureKind.CAMERA_DISCONNECT)
        self.assertEqual(outcome.injected_error, "CAMERA_DISCONNECTED")
        self.assertTrue(outcome.assertions["disconnect_reported_truthfully"])
        self.assertSafePass(FailureKind.CAMERA_DISCONNECT)

    def test_codec_rejection_cleanup_and_recovery(self) -> None:
        outcome = self.engine.run(FailureKind.CODEC_CONFIGURATION_REJECTION)
        self.assertEqual(outcome.injected_error, "CODEC_CONFIGURATION_REJECTED")
        self.assertEqual(outcome.metrics["recovery_encoded_frames"], 2)
        self.assertSafePass(FailureKind.CODEC_CONFIGURATION_REJECTION)

    def test_encoder_starvation_is_not_silently_accepted(self) -> None:
        outcome = self.engine.run(FailureKind.ENCODER_STARVATION)
        self.assertEqual(outcome.injected_error, "ENCODER_STARVATION")
        self.assertEqual(outcome.metrics["failed_submitted"], 5)
        self.assertEqual(outcome.metrics["failed_encoded"], 2)
        self.assertEqual(outcome.metrics["failed_missing"], 3)
        self.assertEqual(outcome.metrics["recovery_delivery_ratio"], 1.0)
        self.assertSafePass(FailureKind.ENCODER_STARVATION)

    def test_raw_exhaustion_uses_pre_acquire_backpressure(self) -> None:
        outcome = self.engine.run(FailureKind.RAW_IMAGE_READER_EXHAUSTION)
        self.assertEqual(outcome.injected_error, "RAW_CAPACITY_BACKPRESSURE")
        self.assertEqual(outcome.metrics["capacity"], 2)
        self.assertEqual(outcome.metrics["peak_owned"], 2)
        self.assertEqual(outcome.metrics["backpressured_frames"], 1)
        self.assertSafePass(FailureKind.RAW_IMAGE_READER_EXHAUSTION)

    def test_gpu_and_native_partial_initialization_are_both_cleaned(self) -> None:
        outcome = self.engine.run(FailureKind.GPU_NATIVE_INITIALIZATION_FAILURE)
        self.assertEqual(outcome.injected_error, "GPU_OR_NATIVE_INITIALIZATION_FAILED")
        self.assertTrue(outcome.assertions["gpu_failure_reported"])
        self.assertTrue(outcome.assertions["native_failure_reported"])
        self.assertSafePass(FailureKind.GPU_NATIVE_INITIALIZATION_FAILURE)

    def test_main_publication_failure_is_atomic(self) -> None:
        outcome = self.engine.run(FailureKind.MAIN_PUBLICATION_FAILURE)
        self.assertEqual(outcome.injected_error, "MAIN_PUBLICATION_FAILED")
        self.assertTrue(outcome.assertions["failed_item_unpublished"])
        self.assertEqual(outcome.metrics["published_count"], 1)
        self.assertSafePass(FailureKind.MAIN_PUBLICATION_FAILURE)

    def test_sidecar_publication_failure_is_atomic(self) -> None:
        outcome = self.engine.run(FailureKind.SIDECAR_PUBLICATION_FAILURE)
        self.assertEqual(outcome.injected_error, "SIDECAR_PUBLICATION_FAILED")
        self.assertTrue(outcome.assertions["failed_item_unpublished"])
        self.assertEqual(outcome.metrics["published_count"], 1)
        self.assertSafePass(FailureKind.SIDECAR_PUBLICATION_FAILURE)

    def test_low_storage_fails_before_pending_publication(self) -> None:
        outcome = self.engine.run(FailureKind.LOW_STORAGE)
        self.assertEqual(outcome.injected_error, "LOW_STORAGE")
        self.assertTrue(outcome.assertions["storage_failure_precedes_allocation"])
        self.assertTrue(outcome.assertions["no_pending_low_storage_output"])
        self.assertSafePass(FailureKind.LOW_STORAGE)

    def test_permission_revocation_refuses_reopen_until_granted(self) -> None:
        outcome = self.engine.run(FailureKind.PERMISSION_REVOCATION)
        self.assertEqual(outcome.injected_error, "CAMERA_PERMISSION_REVOKED")
        self.assertTrue(outcome.assertions["camera_not_reopened_without_permission"])
        self.assertSafePass(FailureKind.PERMISSION_REVOCATION)

    def test_surface_loss_releases_session_and_recovers(self) -> None:
        outcome = self.engine.run(FailureKind.SURFACE_LOSS)
        self.assertEqual(outcome.injected_error, "SURFACE_LOST")
        self.assertTrue(outcome.assertions["surface_loss_reported_and_cleaned"])
        self.assertSafePass(FailureKind.SURFACE_LOSS)

    def test_lifecycle_cancellation_releases_session_and_recovers(self) -> None:
        outcome = self.engine.run(FailureKind.LIFECYCLE_CANCELLATION)
        self.assertEqual(outcome.injected_error, "LIFECYCLE_CANCELLED")
        self.assertTrue(outcome.assertions["cancellation_reported_and_cleaned"])
        self.assertSafePass(FailureKind.LIFECYCLE_CANCELLATION)

    def test_stale_colour_generation_never_commits_old_packet(self) -> None:
        outcome = self.engine.run(FailureKind.STALE_GENERATION)
        self.assertEqual(outcome.injected_error, "STALE_PROFILE_GENERATION")
        self.assertTrue(outcome.assertions["stale_profile_rejected"])
        self.assertTrue(outcome.assertions["old_profile_not_retained"])
        self.assertEqual(outcome.metrics["current_generation"], 2)
        self.assertSafePass(FailureKind.STALE_GENERATION)

    def test_thermal_throttle_pauses_critical_functional_and_performance_work(self) -> None:
        outcome = self.engine.run(FailureKind.THERMAL_THROTTLING)
        self.assertEqual(outcome.injected_error, "THERMAL_NOT_ADMITTED_FOR_FUNCTIONAL")
        self.assertTrue(outcome.assertions["critical_functional_work_paused"])
        self.assertEqual(outcome.metrics["throttled_secondary_fraction"], 0.35)
        self.assertEqual(outcome.metrics["cooled_secondary_fraction"], 1.0)
        self.assertSafePass(FailureKind.THERMAL_THROTTLING)

    def test_conversion_cancellation_cannot_publish_partial_output(self) -> None:
        outcome = self.engine.run(FailureKind.CONVERSION_CANCELLATION)
        self.assertEqual(outcome.injected_error, "CONVERSION_CANCELLED")
        self.assertTrue(outcome.assertions["cancelled_output_unpublished"])
        self.assertEqual(outcome.metrics["recovery_frames"], 1)
        self.assertSafePass(FailureKind.CONVERSION_CANCELLATION)

    def test_decoder_failure_discards_queue_and_recovers(self) -> None:
        outcome = self.engine.run(FailureKind.PLAYBACK_DECODER_FAILURE)
        self.assertEqual(outcome.injected_error, "PLAYBACK_DECODER_FAILED")
        self.assertEqual(outcome.metrics["recovery_rendered"], 3)
        self.assertEqual(outcome.metrics["recovery_dropped"], 0)
        self.assertSafePass(FailureKind.PLAYBACK_DECODER_FAILURE)

    def test_run_all_returns_every_scenario_as_independent_pass(self) -> None:
        outcomes = self.engine.run_all()
        self.assertEqual(len(outcomes), len(FailureKind))
        self.assertEqual([outcome.kind for outcome in outcomes], list(FailureKind))
        for outcome in outcomes:
            with self.subTest(kind=outcome.kind.value):
                self.assertEqual(outcome.status, TwinStatus.PASS, outcome.to_dict())
                self.assertTrue(outcome.exact_once_cleanup)
                self.assertTrue(outcome.corrupt_publication_absent)

    def test_failure_scenarios_are_deterministic_for_same_seed(self) -> None:
        first = FailureInjectionEngine(seed=99).run_all()
        second = FailureInjectionEngine(seed=99).run_all()
        self.assertEqual(
            [outcome.to_dict() for outcome in first],
            [outcome.to_dict() for outcome in second],
        )
        self.assertEqual(
            [outcome.deterministic_sha256() for outcome in first],
            [outcome.deterministic_sha256() for outcome in second],
        )

    def test_scenario_seed_is_stable_and_changes_with_root_seed(self) -> None:
        first = FailureInjectionEngine(seed=1).run(FailureKind.CAMERA_DISCONNECT)
        repeated = FailureInjectionEngine(seed=1).run(FailureKind.CAMERA_DISCONNECT)
        second = FailureInjectionEngine(seed=2).run(FailureKind.CAMERA_DISCONNECT)
        self.assertEqual(first.seed, repeated.seed)
        self.assertNotEqual(first.seed, second.seed)

    def test_result_writer_accepts_failure_outcome_deterministically(self) -> None:
        outcome = self.engine.run(FailureKind.ENCODER_STARVATION)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "failure.json"
            first_hash = write_scenario_result_json(path, outcome)
            first_bytes = path.read_bytes()
            second_hash = write_scenario_result_json(path, outcome)
            self.assertEqual(first_hash, second_hash)
            self.assertEqual(first_hash, hashlib.sha256(first_bytes).hexdigest())
            payload = json.loads(first_bytes)
            self.assertEqual(payload["kind"], "encoder_starvation")
            self.assertEqual(payload["status"], "PASS")
            self.assertIn("not proof", payload["evidence_scope"].lower())


if __name__ == "__main__":
    unittest.main()
