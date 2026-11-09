from __future__ import annotations

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import activation_evidence as evidence


class Niv99ActivationEvidenceTest(unittest.TestCase):
    def test_complete_hashed_evidence_issues_deterministic_certificate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest(root, set(evidence.EVIDENCE_ORDER))
            encoded = self._encode(manifest)
            first = evidence.evaluate_manifest(manifest, encoded, root, now_epoch_ms=2_000_000)
            second = evidence.evaluate_manifest(manifest, encoded, root, now_epoch_ms=2_000_000)
            self.assertEqual("APPROVED", first.status)
            self.assertEqual((), first.missing_evidence)
            self.assertEqual((), first.invalid_artifacts)
            self.assertEqual((), first.failed_metrics)
            self.assertIsNotNone(first.certificate)
            certificate = first.certificate or {}
            self.assertEqual(
                hashlib.sha256(encoded).hexdigest(),
                certificate["evidenceManifestSha256"],
            )
            self.assertEqual(
                evidence.evidence_mask(evidence.EVIDENCE_ORDER),
                certificate["passedEvidenceMask"],
            )
            self.assertEqual(
                certificate["certificateId"],
                (second.certificate or {})["certificateId"],
            )
            self.assertFalse(first.as_dict()["claims"]["vendorSuperiorityProven"])

    def test_host_only_manifest_cannot_issue_live_certificate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest(root, set(evidence.COMMON_HOST_EVIDENCE))
            result = evidence.evaluate_manifest(
                manifest,
                self._encode(manifest),
                root,
                now_epoch_ms=2_000_000,
            )
            self.assertEqual("BLOCKED", result.status)
            self.assertIsNone(result.certificate)
            self.assertIn("ANDROID_VULKAN_PARITY", result.missing_evidence)
            self.assertIn("DEVICE_INTEGRATION", result.missing_evidence)
            self.assertFalse(result.as_dict()["claims"]["physicalDeviceValidated"])

    def test_artifact_hash_mismatch_is_reported_and_cannot_be_bypassed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest(root, set(evidence.EVIDENCE_ORDER))
            manifest["evidence"][0]["sha256"] = "f" * 64
            result = evidence.evaluate_manifest(
                manifest,
                self._encode(manifest),
                root,
                now_epoch_ms=2_000_000,
            )
            self.assertEqual("BLOCKED", result.status)
            self.assertTrue(result.invalid_artifacts)
            self.assertIn(manifest["evidence"][0]["kind"], result.missing_evidence)

    def test_artifact_path_cannot_escape_evidence_root(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest(root, set(evidence.EVIDENCE_ORDER))
            manifest["evidence"][0]["path"] = "../outside.json"
            result = evidence.evaluate_manifest(
                manifest,
                self._encode(manifest),
                root,
                now_epoch_ms=2_000_000,
            )
            self.assertEqual("BLOCKED", result.status)
            self.assertIn("missing-or-outside-root", result.invalid_artifacts[0])

    def test_realtime_tail_latency_failure_blocks_certificate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest(root, set(evidence.EVIDENCE_ORDER))
            manifest["metrics"]["p99FrameTimeNs"] = 41_000_000
            manifest["metrics"]["maximumFrameTimeNs"] = 45_000_000
            manifest["metrics"]["deadlineMissCount"] = 2
            result = evidence.evaluate_manifest(
                manifest,
                self._encode(manifest),
                root,
                now_epoch_ms=2_000_000,
            )
            self.assertEqual("BLOCKED", result.status)
            self.assertIn("FRAME_TIMING", result.failed_metrics)

    def test_raw_signal_domain_requires_raw_bit_exact_evidence_and_metric(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            passed = set(evidence.EVIDENCE_ORDER) - {"RAW_BIT_EXACT"}
            manifest = self._manifest(root, passed)
            result = evidence.evaluate_manifest(
                manifest,
                self._encode(manifest),
                root,
                now_epoch_ms=2_000_000,
            )
            self.assertIn("RAW_BIT_EXACT", result.missing_evidence)

            manifest = self._manifest(root, set(evidence.EVIDENCE_ORDER))
            manifest["metrics"]["rawByteMismatchCount"] = 1
            result = evidence.evaluate_manifest(
                manifest,
                self._encode(manifest),
                root,
                now_epoch_ms=2_000_000,
            )
            self.assertIn("RAW_FIDELITY", result.failed_metrics)

    def test_expired_manifest_is_blocked(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest(root, set(evidence.EVIDENCE_ORDER))
            manifest["validity"] = {"issuedAtEpochMs": 1_000, "expiresAtEpochMs": 2_000}
            result = evidence.evaluate_manifest(
                manifest,
                self._encode(manifest),
                root,
                now_epoch_ms=2_001,
            )
            self.assertEqual("BLOCKED", result.status)
            self.assertIn("CERTIFICATE_VALIDITY_WINDOW", result.failed_metrics)

    def test_target_and_operating_mode_must_match(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest(root, set(evidence.EVIDENCE_ORDER))
            manifest["identity"]["operatingMode"] = "PHOTO"
            with self.assertRaisesRegex(evidence.EvidenceFormatError, "cannot certify"):
                evidence.evaluate_manifest(
                    manifest,
                    self._encode(manifest),
                    root,
                    now_epoch_ms=2_000_000,
                )

    def test_offline_conversion_does_not_make_false_realtime_claim(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = self._manifest(root, set(evidence.EVIDENCE_ORDER))
            manifest["identity"]["target"] = "CONVERSION"
            manifest["identity"]["operatingMode"] = "CONVERSION_RAW_TO_LOG"
            for field in (
                "medianFrameTimeNs",
                "p95FrameTimeNs",
                "p99FrameTimeNs",
                "maximumFrameTimeNs",
                "frameDeadlineNs",
                "thermalRunDurationNs",
            ):
                manifest["metrics"][field] = 0
            manifest["metrics"]["deadlineMissCount"] = -1
            result = evidence.evaluate_manifest(
                manifest,
                self._encode(manifest),
                root,
                now_epoch_ms=2_000_000,
            )
            self.assertEqual("APPROVED", result.status)
            self.assertNotIn("FRAME_TIMING", result.failed_metrics)
            self.assertNotIn("THERMAL_DURATION", result.failed_metrics)

    def _manifest(self, root: Path, passed: set[str]) -> dict[str, object]:
        artifact = root / "evidence.json"
        artifact.write_text('{"passed":true}\n', encoding="utf-8")
        artifact_hash = hashlib.sha256(artifact.read_bytes()).hexdigest()
        records = [
            {
                "kind": name,
                "passed": name in passed,
                "path": artifact.name,
                "sha256": artifact_hash,
            }
            for name in evidence.EVIDENCE_ORDER
        ]
        return {
            "schema": evidence.SCHEMA,
            "identity": {
                "appBuildFingerprint": "test-build",
                "shaderGraphSha256": "a" * 64,
                "nativeLibrarySha256": "b" * 64,
                "nativeAbiVersion": evidence.NATIVE_ABI_VERSION,
                "deviceBuildFingerprint": "test-device",
                "gpuDriverFingerprint": "test-driver",
                "cameraId": "test-camera-0",
                "target": "VIDEO_PREVIEW",
                "operatingMode": "VIDEO",
                "signalDomain": "SENSOR_LINEAR_RAW",
                "width": 3840,
                "height": 2160,
                "targetFps": 25,
                "colorProfileId": 11,
            },
            "validity": {
                "issuedAtEpochMs": 1_000_000,
                "expiresAtEpochMs": 3_000_000,
            },
            "evidence": records,
            "metrics": {
                "shaderComparedValueCount": 1_895,
                "shaderMaximumAbsoluteError": 0.0009765625,
                "physicalDeviceFrameCount": 12_000,
                "controlledSceneCount": 12,
                "medianFrameTimeNs": 10_000_000,
                "p95FrameTimeNs": 15_000_000,
                "p99FrameTimeNs": 20_000_000,
                "maximumFrameTimeNs": 30_000_000,
                "frameDeadlineNs": 40_000_000,
                "deadlineMissCount": 0,
                "maximumResidentBytes": 128 * 1024 * 1024,
                "certifiedMemoryBudgetBytes": 512 * 1024 * 1024,
                "thermalRunDurationNs": 31 * 60 * 1_000_000_000,
                "mtf50Retention": 0.95,
                "clippingIncrease": 0.001,
                "clippingTolerance": 0.002,
                "awbStabilityImprovement": 0.08,
                "denoiseQualityImprovement": 0.12,
                "unexplainedRecordingFrameLoss": 0,
                "nonMonotonicTimestampCount": 0,
                "temporalHistoryLeakCount": 0,
                "visibleTileSeamCount": 0,
                "rawByteMismatchCount": 0,
                "conversionIntegrityFailureCount": 0,
                "longRecordingDurationNs": 31 * 60 * 1_000_000_000,
            },
        }

    @staticmethod
    def _encode(manifest: dict[str, object]) -> bytes:
        return json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("utf-8")


if __name__ == "__main__":
    unittest.main()
