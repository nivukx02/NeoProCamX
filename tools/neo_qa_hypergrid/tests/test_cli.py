from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.neo_qa_hypergrid.cli import build_parser, main


class HyperGridCliTest(unittest.TestCase):
    def test_digital_twin_and_failure_commands_write_truthful_host_results(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            twin = root / "twin.json"
            failures = root / "failures.json"
            self.assertEqual(0, main(["digital-twin", "--seed", "7", "--output", str(twin)]))
            self.assertEqual(
                0,
                main(["failure-injection", "--seed", "7", "--output", str(failures)]),
            )
            self.assertEqual("PASS", json.loads(twin.read_text(encoding="utf-8"))["status"])
            failure_payload = json.loads(failures.read_text(encoding="utf-8"))
            self.assertEqual("PASS", failure_payload["status"])
            self.assertEqual(15, len(failure_payload["outcomes"]))
            self.assertIn("not physical-device proof", failure_payload["evidence_scope"])

    def test_recording_audio_requirements_are_mutually_exclusive(self) -> None:
        parser = build_parser()
        with self.assertRaises(SystemExit):
            parser.parse_args(
                [
                    "analyze-recording",
                    "--probe",
                    "probe.json",
                    "--output",
                    "out.json",
                    "--width",
                    "1",
                    "--height",
                    "1",
                    "--target-fps",
                    "25",
                    "--require-audio",
                    "--forbid-audio",
                ]
            )

    def test_device_preflight_cli_requires_complete_identity_and_writes_atomically(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "preflight.json"
            output = root / "decision.json"
            source.write_text(
                json.dumps(
                    {
                        "serial": "SERIAL",
                        "expected_serial": "SERIAL",
                        "manufacturer": "vivo",
                        "model": "I2405",
                        "android_version": "16",
                        "api_level": 36,
                        "build_fingerprint": "vendor/device/build",
                        "abi": "arm64-v8a",
                        "boot_completed": True,
                        "interactive_unlocked": True,
                        "battery_percent": 80,
                        "charging": False,
                        "free_storage_bytes": 50 * 1024**3,
                        "thermal_status": 0,
                        "skin_temperature_c": 35.0,
                        "active_camera_clients": [],
                        "conflicting_background_processes": [],
                        "candidate_apk_sha256": "A" * 64,
                        "installed_apk_sha256": "a" * 64,
                        "signing_certificate_sha256": "B" * 64,
                    }
                ),
                encoding="utf-8",
            )
            self.assertEqual(
                0,
                main(
                    [
                        "device-preflight",
                        "--input",
                        str(source),
                        "--test-class",
                        "performance",
                        "--output",
                        str(output),
                    ]
                ),
            )
            self.assertTrue(json.loads(output.read_text(encoding="utf-8"))["admitted"])
            self.assertFalse(any(root.glob(".decision.json.*")))


if __name__ == "__main__":
    unittest.main()
