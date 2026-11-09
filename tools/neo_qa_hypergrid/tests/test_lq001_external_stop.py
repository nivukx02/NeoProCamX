"""Host policy/runner tests. Synthetic exits are not physical recording evidence."""
from __future__ import annotations

from contextlib import nullcontext, redirect_stdout
from copy import deepcopy
import io
import json
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from tools.neo_qa_hypergrid.lq001_external_stop import classify_external_stop, read_run_external_stop
from tools.neo_qa_hypergrid.reconcile_lq001_batch import reconcile_external_stop
from tools.neo_qa_hypergrid.run_lq001_recording_batch import main as run_batch


PACKAGE = "com.neoproc.neoproccamx"
START = f"09-08 14:23:30.000  2632 3295 I am_proc_start: [0,30298,10933,{PACKAGE},top-activity,{{activity}}]"
HIGH = "09-08 14:23:34.177 2632 3295 I am_wtf : Sending non-protected broadcast com.iqoo.powersaving.temperature.high from system 4992:com.vivo.pem/1000 pkg com.vivo.pem Caller=system"
WINDOW = "09-08 14:23:34.402 2632 3295 I am_wtf : Sending non-protected broadcast android.thermal.WARNING_WINDOW_STATE from system 5120:com.vivo.daemonService/1000 pkg com.vivo.daemonService Caller=system"
KILL = f"09-08 14:23:44.466 2632 4858 I am_kill : [0,30298,{PACKAGE},0,stop {PACKAGE} due to stop by com.vivo.daemonService,469192]"
EVENTS = "\n".join((START, HIGH, WINDOW, KILL))
EXIT = f"""timestamp=2026-09-08 14:23:44.465 pid=30298 realUid=10933
process={PACKAGE} reason=10 (USER REQUESTED) subreason=21 (FORCE STOP) status=0
importance=100 description=stop {PACKAGE} due to stop by com.vivo.daemonService state=empty trace=null
"""


class ExternalStopTest(unittest.TestCase):
    def setUp(self):
        self.error = {"schema": "neo-qa-hypergrid/lq001-run-error/v1", "status": "FAIL",
                      "mode": "VIDEO", "pipeline": "DIRECT", "resolution": "4096x3072",
                      "requested_detail_profile": "FOCUSED_DETAIL", "fps": 25,
                      "requested_duration_seconds": 300, "terminal_recording_failure": ""}
        self.identity = "apkSha256=" + "A" * 64 + " /base.apk\nserial=test-device\n"
        self.ledger = {"current_app_sha256": "A" * 64, "latest_code_candidate": {},
                       "execution_summary": {}, "cells": [{
                           "id": "LQ-001.05/VIDEO/DIRECT/4096x3072/FOCUSED_DETAIL",
                           "status": "PENDING", "transport_status": "PASS",
                           "candidate_apk_sha256": "B" * 64,
                           "independent_transport_audit": {"status": "PASS"}}]}

    def write_failure(self, root: Path):
        (root / "run-error.json").write_text(json.dumps(self.error), encoding="utf-8")
        (root / "system-events-logcat.txt").write_text(EVENTS, encoding="utf-8")
        (root / "exit-info-postmortem-before-cleanup.txt").write_text(EXIT, encoding="utf-8")

    def test_matching_run_proves_external_thermal_stop_not_app_crash(self):
        result = classify_external_stop(EVENTS, EXIT)
        self.assertEqual(result["kind"], "OEM_THERMAL_FORCE_STOP")
        self.assertEqual(result["pid"], 30298)
        self.assertEqual(result["status"], "BLOCKED")
        self.assertFalse(result["production_ready"])

    def test_old_exit_record_without_current_run_kill_is_not_evidence(self):
        self.assertIsNone(classify_external_stop(START, EXIT))

    def test_pid_reuse_or_unrelated_app_does_not_match(self):
        for events, exit_info in ((EVENTS.replace("30298", "30299"), EXIT),
                                  (EVENTS.replace(PACKAGE, "com.other.app"), EXIT),
                                  (EVENTS, EXIT.replace("14:23:44.465", "13:23:44.465"))):
            with self.subTest(events=events, exit_info=exit_info):
                self.assertIsNone(classify_external_stop(events, exit_info))

    def test_missing_current_process_start_is_not_correlation(self):
        self.assertIsNone(classify_external_stop("\n".join((HIGH, WINDOW, KILL)), EXIT))

    def test_app_crash_is_not_hidden_as_external_blocker(self):
        self.assertIsNone(classify_external_stop(EVENTS, EXIT.replace("reason=10 (USER REQUESTED)", "reason=4 (CRASH)")))

    def test_user_shell_cleanup_is_not_oem_thermal_stop(self):
        self.assertIsNone(classify_external_stop(EVENTS.replace("by com.vivo.daemonService", "by 22335"),
                                               EXIT.replace("by com.vivo.daemonService", "by 22335")))

    def test_both_recent_authentic_thermal_events_are_required(self):
        for events in (EVENTS.replace(HIGH, ""), EVENTS.replace(WINDOW, ""),
                       EVENTS.replace(HIGH, HIGH.replace("14:23:34.177", "14:21:34.177")),
                       EVENTS.replace(HIGH, HIGH.replace("pkg com.vivo.pem ", "pkg com.other ")),
                       EVENTS.replace(HIGH, HIGH.replace("14:23:34.177", "14:23:54.177"))):
            with self.subTest(events=events):
                result = classify_external_stop(events, EXIT)
                self.assertEqual(result["kind"], "OEM_FORCE_STOP")
                self.assertFalse(result["thermal_warning_correlated"])

    def test_new_year_boundary_preserves_short_time_correlation(self):
        events = EVENTS.replace("09-08 14:23:30.000", "12-31 23:59:58.000").replace(
            "09-08 14:23:34.177", "12-31 23:59:59.177").replace(
            "09-08 14:23:34.402", "12-31 23:59:59.402").replace(
            "09-08 14:23:44.466", "01-01 00:00:04.466")
        exit_info = EXIT.replace("2026-09-08 14:23:44.465", "2027-01-01 00:00:04.465")
        self.assertEqual(classify_external_stop(events, exit_info)["kind"], "OEM_THERMAL_FORCE_STOP")

    def test_file_evidence_requires_failed_run_and_preserves_source_files(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertIsNone(read_run_external_stop(root))
            self.write_failure(root)
            before = {path.name: path.read_bytes() for path in root.iterdir()}
            result = read_run_external_stop(root)
            self.assertEqual(result["kind"], "OEM_THERMAL_FORCE_STOP")
            self.assertEqual(before, {path.name: path.read_bytes() for path in root.iterdir()})
            self.error["status"] = "PASS"
            self.write_failure(root)
            self.assertIsNone(read_run_external_stop(root))

    def test_interruption_reconciliation_retains_historical_success_and_no_new_pass(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_failure(root)
            stop = read_run_external_stop(root)
            original = deepcopy(self.ledger)
            result = reconcile_external_stop(self.ledger, self.error, self.identity, stop, directory)
            self.assertEqual(self.ledger, original)
            self.assertEqual(result["current_candidate_transport_pass_count"], 0)
            cell = result["cells"][0]
            self.assertEqual(cell["status"], "BLOCKED")
            self.assertNotIn("independent_transport_audit", cell)
            self.assertEqual(cell["attempt_history"][0]["candidate_apk_sha256"], "B" * 64)
            self.assertEqual(cell["attempt_history"][0]["independent_transport_audit"]["status"], "PASS")
            self.assertEqual(result, reconcile_external_stop(result, self.error, self.identity, stop, directory))

    def test_same_attempt_application_failure_still_counts_as_failure(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.error["terminal_recording_failure"] = "bounded writer admission failed"
            self.write_failure(root)
            stop = read_run_external_stop(root)
            result = reconcile_external_stop(self.ledger, self.error, self.identity, stop, directory)
            self.assertEqual(result["cells"][0]["status"], "FAIL")
            self.assertIn("bounded writer admission failed", result["cells"][0]["blocker"])

    def test_wrong_candidate_or_different_duration_never_merges(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_failure(root)
            stop = read_run_external_stop(root)
            with self.assertRaises(ValueError):
                reconcile_external_stop(self.ledger, self.error, self.identity.replace("A" * 64, "B" * 64), stop, directory)
            self.error["requested_duration_seconds"] = 60
            with self.assertRaises(ValueError):
                reconcile_external_stop(self.ledger, self.error, self.identity, stop, directory)

    def test_batch_does_not_launch_next_case_after_oem_stop_and_always_closes_camera(self):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            commands = []

            def run(command, **kwargs):
                commands.append(command)
                if command[0] == "powershell":
                    run_root = root / "lq001_05_video_direct_focused_detail_4096x3072_25fps_hot_test"
                    run_root.mkdir()
                    self.write_failure(run_root)
                    return subprocess.CompletedProcess(command, 1, "", "")
                arguments = command[3:]
                stdout = {("shell", "getprop", "ro.serialno"): "physical",
                          ("shell", "pm", "path", PACKAGE): "package:/base.apk",
                          ("shell", "sha256sum", "/base.apk"): "A" * 64 + " /base.apk",
                          ("shell", "dumpsys", "thermalservice"): "Thermal Status: 4\n"}.get(tuple(arguments), "")
                return subprocess.CompletedProcess(command, 0, stdout, "")

            argv = ["batch", "--adb", "SDK/adb.exe", "--serial", "test-device", "--physical-serial", "physical",
                    "--apk-sha256", "A" * 64, "--evidence-root", directory, "--label", "unit-test", "--inspect-direct",
                    "--case", "VIDEO/DIRECT/FOCUSED_DETAIL/4096x3072", "--case", "VIDEO/DIRECT/EXTREME_DETAIL/4096x3072"]
            with patch("sys.argv", argv), patch("tools.neo_qa_hypergrid.run_lq001_recording_batch.subprocess.run", side_effect=run), \
                    patch("tools.neo_qa_hypergrid.run_lq001_recording_batch.ExclusiveAdbLease", return_value=nullcontext()), \
                    patch("tools.neo_qa_hypergrid.run_lq001_recording_batch.inspect_and_record") as inspector, redirect_stdout(io.StringIO()):
                self.assertEqual(run_batch(), 1)
                inspector.assert_not_called()
            self.assertEqual(len([command for command in commands if command[0] == "powershell"]), 1)
            self.assertEqual(commands[-2][3:], ["shell", "am", "force-stop", PACKAGE])
            self.assertEqual(commands[-1][3:], ["shell", "input", "keyevent", "KEYCODE_SLEEP"])
            result = json.loads((root / "unit-test-batch.json").read_text())
            self.assertEqual(result[0]["status"], "BLOCKED")
            self.assertEqual(len(result[0]["remaining_cases_not_started"]), 1)


if __name__ == "__main__":
    unittest.main()
