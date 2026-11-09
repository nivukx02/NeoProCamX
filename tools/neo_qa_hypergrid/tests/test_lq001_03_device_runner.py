from __future__ import annotations

import re
import shutil
import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
RUNNER = ROOT / "tools" / "neo_qa_hypergrid" / "run_lq001_03_device.ps1"
POWERSHELL_TEST = Path(__file__).with_name("run_lq001_03_device_runner_tests.ps1")


class Lq00103DeviceRunnerContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = RUNNER.read_text(encoding="utf-8")

    def test_quality_and_performance_gates_are_not_weakened(self) -> None:
        expected_assignments = {
            "minimumPreviewFps": "24.8",
            "maximumPreviewFps": "25.2",
            "maximumFirstFreshFrameLatencyMs": "1000.0",
            "requiredTransitionCount": "30",
        }
        for variable, expected in expected_assignments.items():
            match = re.search(rf"\${variable}\s*=\s*([^\r\n]+)", self.source)
            self.assertIsNotNone(match, variable)
            self.assertEqual(expected, match.group(1).strip(), variable)

    def test_runner_correlates_fresh_telemetry_and_candidate_identity(self) -> None:
        required_contracts = (
            "Wait-ForTransitionTelemetry",
            "Get-CurrentAppOwnedLogPath",
            "Read-AppOwnedLogTail",
            "Read-AppOwnedTelemetryTail",
            "APP-OWNED DURABLE LOG",
            "telemetry-app-log.txt",
            "-MinimumSequence $previousSequence",
            "$presentation.Index + $presentation.Length",
            "Get-CandidateContinuityAssessment",
            "foreground UI package changed",
            "application process identity changed",
            "installed candidate path changed",
            "authoritative package replacement was detected",
            "authoritative package force-stop was detected",
        )
        for contract in required_contracts:
            self.assertIn(contract, self.source)

    def test_candidate_mutation_uses_authoritative_evidence(self) -> None:
        self.assertIn("Get-PackageLifecycleSignals", self.source)
        self.assertIn("ActivityThread", self.source)
        self.assertIn("observer_replacement_only", self.source)
        self.assertIn("authoritative_force_stop_signal", self.source)
        self.assertIn("authoritative_replacement_signal", self.source)
        self.assertNotIn(
            '"Package \\[$escapedPackage\\] reported as REPLACED|PACKAGE_REPLACED',
            self.source,
        )

    def test_final_installed_apk_identity_is_rehashed_before_cleanup(self) -> None:
        capture_index = self.source.index("33-final-candidate-identity.json")
        cleanup_index = self.source.index(
            "Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $Package)",
            capture_index,
        )
        self.assertLess(capture_index, cleanup_index)
        for contract in (
            "$finalInstalledIdentity = Get-InstalledApkIdentity",
            "$finalInstalledIdentity.sha256 -eq $candidateHash",
            "final_installed_apk_sha256",
            "final_candidate_identity_verified",
        ):
            self.assertIn(contract, self.source)

    def test_manual_visual_review_is_optional_but_never_auto_passes(self) -> None:
        signature = re.search(
            r"function Test-ManualVisualReview\s*\{(?P<body>.*?)\n\}",
            self.source,
            flags=re.DOTALL,
        )
        self.assertIsNotNone(signature)
        body = signature.group("body")
        self.assertRegex(body, r"\[AllowEmptyString\(\)\]\[string\]\$ReviewPath")
        self.assertIn("Manual visual review was not supplied", body)
        self.assertIn("accepted = $false", body)

    def test_device_identity_does_not_expand_all_getprop_values(self) -> None:
        self.assertNotRegex(
            self.source,
            r"'shell',\s*'sh',\s*'-c',\s*'getprop ro\.product\.manufacturer",
        )
        for prop in (
            "ro.product.manufacturer",
            "ro.product.model",
            "ro.build.fingerprint",
            "ro.build.version.release",
            "ro.build.version.sdk",
        ):
            self.assertIn(f"'{prop}'", self.source)

    def test_selected_transport_is_serial_scoped_without_rejecting_duplicate_phone_transports(self) -> None:
        self.assertIn("$selectedDevicePattern", self.source)
        self.assertIn("$selectedDeviceRows.Count -ne 1", self.source)
        self.assertIn("Invoke-Adb -Arguments @('get-state')", self.source)
        self.assertIn("00-adb-transports.txt", self.source)
        self.assertNotIn("$devices.Count -ne 1", self.source)

    def test_powershell_behavioral_fixtures(self) -> None:
        executable = shutil.which("powershell") or shutil.which("pwsh")
        if executable is None:
            self.skipTest("PowerShell is unavailable")
        result = subprocess.run(
            [
                executable,
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(POWERSHELL_TEST),
                "-RunnerPath",
                str(RUNNER),
            ],
            cwd=ROOT,
            text=True,
            capture_output=True,
            timeout=30,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("LQ001_03_RUNNER_TESTS=PASS", result.stdout)


if __name__ == "__main__":
    unittest.main()
