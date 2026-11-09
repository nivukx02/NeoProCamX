"""Ledger merge tests use synthetic dictionaries, never synthetic camera evidence."""
from copy import deepcopy
import unittest
from tools.neo_qa_hypergrid.reconcile_lq001_batch import DIRECT_GATES, reconcile


class ReconcileLq001BatchTest(unittest.TestCase):
    def setUp(self):
        self.sha = "A" * 64
        self.identity = "apkSha256=" + self.sha + " /base.apk\nserial=device\n"
        self.ledger = {"current_app_sha256": self.sha, "latest_code_candidate": {},
                       "execution_summary": {}, "cells": [{
                           "id": "LQ-001.05/VIDEO/DIRECT/3264x2448/NATURAL_DETAIL",
                           "status": "PENDING", "transport_status": "PASS",
                           "candidate_apk_sha256": "B" * 64, "measured": {"label": "old"},
                           "attempt_history": [{"status": "FAIL", "reason": "earlier failure"}]}]}
        self.run = {"requested_duration_seconds": 300, "fps": 25, "mode": "VIDEO",
                    "pipeline": "DIRECT", "resolution": "3264x2448",
                    "requested_detail_profile": "NATURAL_DETAIL", "label": "new"}
        self.audit = {"candidate_apk_sha256": self.sha, "physical_serial": "device",
                      "status": "PASS", "gates": dict.fromkeys(DIRECT_GATES, True),
                      "evidence_directory": "run/independent-1"}

    def merge(self):
        return reconcile(self.ledger, self.run, self.identity, self.audit, "run")

    def test_transport_pass_does_not_grant_production_pass(self):
        result = self.merge()
        self.assertEqual(result["current_candidate_transport_pass_count"], 1)
        self.assertEqual(result["cells"][0]["status"], "PENDING")
        self.assertEqual(result["status_counts"]["PASS"], 0)

    def test_remaining_count_comes_from_active_scope_not_the_old_45_cell_matrix(self):
        for total in (1, 39, 45):
            with self.subTest(total=total):
                ledger = deepcopy(self.ledger)
                for index in range(1, total):
                    ledger["cells"].append({"id": f"other-{index}", "status": "PENDING",
                                            "transport_status": "PENDING"})
                result = reconcile(ledger, self.run, self.identity, self.audit, "run")
                self.assertEqual(result["execution_summary"]["current_candidate_cells_without_transport_pass"],
                                 total - 1)

    def test_a_failed_recording_does_not_remove_a_cell_from_remaining_scope(self):
        self.audit["status"] = "FAIL"
        self.audit["gates"]["bitrate"] = False
        result = self.merge()
        self.assertEqual(result["current_candidate_transport_pass_count"], 0)
        self.assertEqual(result["execution_summary"]["current_candidate_cells_without_transport_pass"], 1)

    def test_old_evidence_and_original_input_are_preserved(self):
        before = deepcopy(self.ledger)
        result = self.merge()
        self.assertEqual(self.ledger, before)
        history = result["cells"][0]["attempt_history"]
        self.assertEqual(history[0]["reason"], "earlier failure")
        self.assertEqual(history[1]["candidate_apk_sha256"], "B" * 64)

    def test_repeat_reconciliation_is_idempotent(self):
        result = self.merge()
        again = reconcile(result, self.run, self.identity, self.audit, "run")
        self.assertEqual(result, again)

    def test_matching_label_does_not_keep_another_attempts_status_or_candidate(self):
        result = self.merge()
        result["cells"][0]["status"] = "FAIL"
        result["cells"][0]["candidate_apk_sha256"] = "B" * 64
        self.ledger = result
        repaired = self.merge()
        self.assertEqual(repaired["cells"][0]["status"], "PENDING")
        self.assertEqual(repaired["cells"][0]["candidate_apk_sha256"], self.sha)
        self.assertEqual(repaired["cells"][0]["attempt_history"][-1]["status"], "FAIL")

    def test_same_label_still_validates_the_independent_oracle(self):
        self.ledger = self.merge()
        self.audit["physical_serial"] = "wrong-device"
        with self.assertRaises(ValueError):
            self.merge()

    def test_completed_recording_without_publication_is_a_failure_not_an_unstarted_case(self):
        self.audit = None
        self.run.update(recording_start_proven=True, output_finalized=False, measured_wait_seconds=300.082)
        result = self.merge()
        self.assertEqual(result["cells"][0]["transport_status"], "FAIL")
        self.assertIn("output did not finalize", result["cells"][0]["blocker"])
        self.assertEqual(result["current_candidate_transport_pass_count"], 0)

    def test_missing_or_short_publication_observation_is_not_a_completed_run(self):
        self.audit = None
        self.run.update(recording_start_proven=True, output_finalized=False, measured_wait_seconds=10)
        with self.assertRaises(ValueError):
            self.merge()

    def test_successful_retry_archives_external_stop_without_leaving_a_stale_current_blocker(self):
        old_stop = {"kind": "OEM_THERMAL_FORCE_STOP", "run_directory": "older-interrupted-run"}
        self.ledger["cells"][0]["external_stop"] = deepcopy(old_stop)
        self.ledger["cells"][0]["status"] = "BLOCKED"
        self.ledger["cells"][0]["transport_status"] = "BLOCKED"
        result = self.merge()
        self.assertNotIn("external_stop", result["cells"][0])
        self.assertEqual(result["cells"][0]["attempt_history"][-1]["external_stop"], old_stop)
        self.assertEqual(result["cells"][0]["transport_status"], "PASS")
        self.assertEqual(result["cells"][0]["status"], "PENDING")

    def test_wrong_candidate_or_device_is_rejected(self):
        for field, value in (("candidate_apk_sha256", "C" * 64), ("physical_serial", "other")):
            with self.subTest(field=field):
                original = self.audit[field]
                self.audit[field] = value
                with self.assertRaises(ValueError): self.merge()
                self.audit[field] = original

    def test_previous_candidate_never_transfers(self):
        self.identity = self.identity.replace(self.sha, "B" * 64)
        with self.assertRaises(ValueError): self.merge()

    def test_missing_gate_cannot_claim_pass(self):
        del self.audit["gates"]["source_zero_drops"]
        with self.assertRaises(ValueError): self.merge()

    def test_contradictory_gate_cannot_claim_pass(self):
        self.audit["gates"]["bitrate"] = False
        with self.assertRaises(ValueError): self.merge()

    def test_another_recordings_audit_is_rejected(self):
        self.audit["evidence_directory"] = "other/independent-1"
        with self.assertRaises(ValueError): self.merge()

    def test_short_diagnostic_and_lifecycle_regression_stay_separate(self):
        self.run["requested_duration_seconds"] = 60
        with self.assertRaises(ValueError): self.merge()
        self.run["requested_duration_seconds"] = 300
        self.run["lifecycle_interruption_requested"] = True
        with self.assertRaises(ValueError): self.merge()

    def test_failed_or_incomplete_audit_cannot_claim_success(self):
        self.audit["status"] = "FAIL"
        self.audit["gates"]["source_zero_drops"] = False
        result = self.merge()
        self.assertEqual(result["cells"][0]["status"], "FAIL")
        self.assertEqual(result["current_candidate_transport_pass_count"], 0)
        self.audit = None
        with self.assertRaises(ValueError): self.merge()

    def test_explicit_cinema_failure_keeps_exact_measured_cause(self):
        self.run["pipeline"] = "CINEMA"
        self.run["recording_stop_origin"] = "NPV10_WRITER_FAILURE"
        self.run["recording_stop_detail"] = "bounded leases exhausted"
        self.ledger["cells"][0]["id"] = self.ledger["cells"][0]["id"].replace("DIRECT", "CINEMA")
        self.audit = None
        result = self.merge()
        self.assertEqual(result["cells"][0]["status"], "FAIL")
        self.assertEqual(result["cells"][0]["blocker"], "bounded leases exhausted")

    def test_planned_stop_is_not_reported_as_the_sensor_gap_failure(self):
        self.run.update({"recording_stop_origin": "DEBUG_COMMAND",
                         "recording_stop_detail": "debug action stop_recording",
                         "planned_stop_origin_valid": True,
                         "source_unrecovered_timestamp_gap_frames": 1,
                         "source_explicit_dropped_recording_frames": 0})
        self.audit["status"] = "FAIL"
        self.audit["gates"]["source_cadence"] = False
        self.audit["gates"]["source_zero_drops"] = False
        result = self.merge()
        reason = result["cells"][0]["blocker"]
        self.assertIn("source_cadence", reason)
        self.assertIn("source_zero_drops", reason)
        self.assertIn("source_unrecovered_timestamp_gap_frames=1", reason)
        self.assertIn("source_explicit_dropped_recording_frames=0", reason)
        self.assertNotIn("debug action stop_recording", reason)
        self.assertEqual(result["cells"][0]["status"], "FAIL")
        self.assertEqual(result["cells"][0]["measured"]["recording_stop_detail"],
                         "debug action stop_recording")

    def test_completed_attempt_replaces_stale_latest_summary_but_keeps_history(self):
        old = {"candidate_apk_sha256": "B" * 64, "label": "old", "transport_status": "PASS"}
        self.ledger["cells"][0]["latest_attempt"] = deepcopy(old)
        result = self.merge()
        latest = result["cells"][0]["latest_attempt"]
        self.assertEqual(latest["candidate_apk_sha256"], self.sha)
        self.assertEqual(latest["label"], "new")
        self.assertEqual(latest["transport_status"], "PASS")
        self.assertEqual(latest["status"], "PENDING")
        self.assertEqual(latest["evidence"], result["cells"][0]["evidence_paths"])
        self.assertEqual(result["cells"][0]["attempt_history"][-1]["latest_attempt"], old)

    def test_audit_failure_names_missing_required_gates_without_inventing_cpu_cause(self):
        self.audit["status"] = "FAIL"
        del self.audit["gates"]["source_cadence"]
        self.audit["gates"]["bitrate"] = False
        reason = self.merge()["cells"][0]["blocker"]
        self.assertIn("source_cadence", reason)
        self.assertIn("bitrate", reason)
        self.assertNotIn("GPU", reason)
        self.assertNotIn("GC", reason)


if __name__ == "__main__":
    unittest.main()
