from __future__ import annotations

import tempfile
import unittest
import math
import json
from pathlib import Path

from tools.neo_qa_hypergrid.device_orchestrator import (
    DevicePreflight,
    ExclusiveAdbLease,
    evaluate_preflight,
    tap_command_from_tree,
)


class ExclusiveDeviceTest(unittest.TestCase):
    def test_only_one_adb_owner_can_hold_lease(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "adb.lock"
            first = ExclusiveAdbLease.for_testing(lock, "first", "SERIAL").acquire()
            try:
                with self.assertRaises(RuntimeError):
                    ExclusiveAdbLease.for_testing(lock, "second", "SERIAL").acquire()
            finally:
                first.release()
            self.assertFalse(lock.exists())

    def test_production_lease_rejects_non_global_lock_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "canonical global lock"):
                ExclusiveAdbLease(Path(directory) / "local.lock", "owner", "SERIAL")

    def test_release_never_deletes_a_lock_with_different_owner_token(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "adb.lock"
            lease = ExclusiveAdbLease.for_testing(lock, "first", "SERIAL").acquire()
            payload = json.loads(lock.read_text(encoding="utf-8"))
            payload["token"] = "different-owner"
            lock.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "another controller"):
                lease.release()
            self.assertTrue(lock.exists())

    def test_performance_admission_is_stricter_than_functional(self) -> None:
        state = DevicePreflight(
            serial="SERIAL",
            expected_serial="SERIAL",
            manufacturer="vivo",
            model="I2405",
            android_version="16",
            api_level=36,
            build_fingerprint="vendor/device/build",
            abi="arm64-v8a",
            boot_completed=True,
            interactive_unlocked=True,
            battery_percent=80,
            charging=False,
            free_storage_bytes=50 * 1024**3,
            thermal_status=2,
            skin_temperature_c=41.0,
            active_camera_clients=(),
            conflicting_background_processes=(),
            candidate_apk_sha256="A" * 64,
            installed_apk_sha256="a" * 64,
            signing_certificate_sha256="B" * 64,
        )
        self.assertTrue(evaluate_preflight(state, test_class="functional").admitted)
        self.assertFalse(evaluate_preflight(state, test_class="performance").admitted)

    def test_preflight_rejects_missing_identity_nonfinite_thermal_and_fake_hashes(self) -> None:
        state = DevicePreflight(
            serial="SERIAL",
            expected_serial="SERIAL",
            manufacturer="",
            model="I2405",
            android_version="16",
            api_level=0,
            build_fingerprint="fp",
            abi="arm64-v8a",
            boot_completed=True,
            interactive_unlocked=True,
            battery_percent=101,
            charging=True,
            free_storage_bytes=50 * 1024**3,
            thermal_status=0,
            skin_temperature_c=math.nan,
            active_camera_clients=(),
            conflicting_background_processes=(),
            candidate_apk_sha256="A",
            installed_apk_sha256="A",
            signing_certificate_sha256="B",
        )
        decision = evaluate_preflight(state, test_class="functional")
        self.assertFalse(decision.admitted)
        self.assertTrue(any("manufacturer" in reason for reason in decision.reasons))
        self.assertTrue(any("API level" in reason for reason in decision.reasons))
        self.assertTrue(any("SHA-256" in reason for reason in decision.reasons))
        self.assertTrue(any("skin temperature" in reason for reason in decision.reasons))

    def test_tap_is_derived_from_unique_ui_tree_bounds(self) -> None:
        tree = (
            '<hierarchy><node text="Settings" resource-id="com.example:id/settings" '
            'bounds="[100,200][300,400]" /></hierarchy>'
        )
        command = tap_command_from_tree(
            "SERIAL",
            tree,
            resource_id="com.example:id/settings",
        )
        self.assertEqual(("adb", "-s", "SERIAL", "shell", "input", "tap", "200", "300"), command)

    def test_ambiguous_or_invalid_ui_tree_target_never_generates_a_tap(self) -> None:
        duplicate = (
            '<hierarchy><node text="Settings" bounds="[0,0][10,10]" />'
            '<node text="Settings" bounds="[10,10][20,20]" /></hierarchy>'
        )
        with self.assertRaises(LookupError):
            tap_command_from_tree("SERIAL", duplicate, text="Settings")
        invalid = '<hierarchy><node text="Settings" bounds="[20,20][10,10]" /></hierarchy>'
        with self.assertRaises(ValueError):
            tap_command_from_tree("SERIAL", invalid, text="Settings")


if __name__ == "__main__":
    unittest.main()
