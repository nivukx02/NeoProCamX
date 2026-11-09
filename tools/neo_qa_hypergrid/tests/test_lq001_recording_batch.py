from __future__ import annotations

import itertools
import json
import shutil
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from tools.neo_qa_hypergrid.inspect_lq001_direct_recordings import inspect_and_record
from tools.neo_qa_hypergrid.run_lq001_recording_batch import (
    find_new_run,
    parse_case,
    recording_command,
)


class RecordingBatchContractTests(unittest.TestCase):
    """Host orchestration tests; these do not prove physical camera performance."""

    def setUp(self) -> None:
        self.args = SimpleNamespace(
            serial="test-device",
            physical_serial="physical-identity",
            adb="SDK/platform-tools/adb.exe",
            evidence_root=Path("evidence"),
            apk_sha256="A" * 64,
        )

    def test_all_45_authorized_cells_have_exact_preserved_commands(self) -> None:
        count = 0
        for mode, profile, resolution in itertools.product(
            ("VIDEO", "PRO", "RAW"),
            ("NATURAL_DETAIL", "FOCUSED_DETAIL", "EXTREME_DETAIL"),
            ("3264x2448", "4096x3072", "3840x2160"),
        ):
            for pipeline in (("CINEMA",) if mode == "RAW" else ("DIRECT", "CINEMA")):
                with self.subTest(mode=mode, pipeline=pipeline, profile=profile, resolution=resolution):
                    case = parse_case("/".join((mode, pipeline, profile, resolution)))
                    command = recording_command(self.args, case)
                    self.assertEqual(command[command.index("-Fps") + 1], "25")
                    self.assertEqual(command[command.index("-DurationSeconds") + 1], "300")
                    self.assertEqual(command[command.index("-DetailProfile") + 1], profile)
                    self.assertEqual(command[command.index("-Resolution") + 1], resolution)
                    self.assertEqual(command[command.index("-AdbPath") + 1], self.args.adb)
                    self.assertEqual(command[command.index("-Serial") + 1], self.args.serial)
                    self.assertEqual(command[command.index("-Aspect") + 1],
                                     "16:9" if resolution == "3840x2160" else "4:3")
                    if mode == "RAW":
                        self.assertIn("tools/neo_qa_hypergrid/run_lq001_raw_endurance.ps1", command)
                        self.assertNotIn("-Mode", command)
                        self.assertNotIn("-Pipeline", command)
                    else:
                        self.assertIn("tools/neo_qa_hypergrid/run_lq001_endurance.ps1", command)
                        self.assertEqual(command[command.index("-Mode") + 1], mode)
                        self.assertEqual(command[command.index("-Pipeline") + 1], pipeline)
                    count += 1
        self.assertEqual(count, 45)

    def test_raw_direct_is_rejected_instead_of_degrading_raw(self) -> None:
        with self.assertRaisesRegex(ValueError, "RAW has only"):
            parse_case("RAW/DIRECT/NATURAL_DETAIL/3840x2160")

    def test_lifecycle_injection_is_explicit_and_keeps_capture_contract(self) -> None:
        case = parse_case("VIDEO/DIRECT/EXTREME_DETAIL/3264x2448")
        ordinary = recording_command(self.args, case)
        self.assertNotIn("-BackgroundAtSeconds", ordinary)
        self.args.background_at_seconds = 60
        self.args.background_duration_seconds = 5
        injected = recording_command(self.args, case)
        self.assertEqual(injected[:-4], ordinary)
        self.assertEqual(injected[-4:], ["-BackgroundAtSeconds", "60", "-BackgroundDurationSeconds", "5"])

    def test_raw_cannot_silently_claim_unsupported_lifecycle_injection(self) -> None:
        self.args.background_at_seconds = 60
        self.args.background_duration_seconds = 5
        with self.assertRaisesRegex(ValueError, "RAW runner does not implement"):
            recording_command(self.args, parse_case("RAW/CINEMA/NATURAL_DETAIL/4096x3072"))

    def test_lifecycle_resume_binds_actual_powershell_arguments_without_common_parameter_collision(self) -> None:
        powershell = shutil.which("powershell") or shutil.which("pwsh")
        if powershell is None:
            self.skipTest("PowerShell is required to execute the runner's argument binding")
        script = r'''
$ErrorActionPreference = 'Stop'
$scriptPath = Join-Path (Get-Location) 'tools/neo_qa_hypergrid/run_lq001_endurance.ps1'
$tokens = $null
$errors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$errors)
if ($errors.Count -ne 0) { throw 'Runner parse failed' }
$commands = @($ast.FindAll({param($node)
    $node -is [Management.Automation.Language.CommandAst] -and
    $node.GetCommandName() -eq 'Invoke-Adb' -and $node.Extent.Text -match 'android.intent.action.MAIN' -and
    $node.Extent.Text -match '-W'
}, $true))
if ($commands.Count -ne 1) { throw 'Expected one same-activity resume command' }
function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    ConvertTo-Json -InputObject $Arguments -Compress
}
$packageName = 'com.neoproc.neoproccamx'
& ([scriptblock]::Create($commands[0].Extent.Text))
'''
        result = subprocess.run([powershell, "-NoProfile", "-Command", script],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(json.loads(result.stdout), ["shell", "am", "start", "-W", "-a",
            "android.intent.action.MAIN", "-n", "com.neoproc.neoproccamx/.ui.MainActivity"])

    def test_unknown_mode_pipeline_profile_or_resolution_never_falls_back(self) -> None:
        for value in (
            "VIDEO/DIRECT/NATURAL_DETAIL", "VIDEO/DIRECT/NATURAL_DETAIL/3840x2160/25",
            "PHOTO/DIRECT/NATURAL_DETAIL/3840x2160", "VIDEO/AUTO/NATURAL_DETAIL/3840x2160",
            "VIDEO/DIRECT/LOW_DETAIL/3840x2160", "VIDEO/DIRECT/NATURAL_DETAIL/1920x1080",
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                parse_case(value)

    def test_old_matching_evidence_is_never_reused_as_new(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            old = root / "lq001_05_video_direct_natural_detail_3840x2160_25fps_hot_old"
            old.mkdir()
            with self.assertRaisesRegex(ValueError, "found 0"):
                find_new_run(root, {old}, parse_case("VIDEO/DIRECT/NATURAL_DETAIL/3840x2160"))

    def test_matching_new_run_is_selected_without_other_profiles(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            wanted = root / "lq001_06_pro_direct_focused_detail_3264x2448_25fps_hot_new"
            other = root / "lq001_06_pro_direct_natural_detail_3264x2448_25fps_hot_new"
            wanted.mkdir()
            other.mkdir()
            self.assertEqual(find_new_run(root, set(), parse_case("PRO/DIRECT/FOCUSED_DETAIL/3264x2448")), wanted)

    def test_ambiguous_new_evidence_is_rejected(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            for suffix in ("first", "second"):
                (root / f"lq001_05_video_direct_natural_detail_3840x2160_25fps_hot_{suffix}").mkdir()
            with self.assertRaisesRegex(ValueError, "found 2"):
                find_new_run(root, set(), parse_case("VIDEO/DIRECT/NATURAL_DETAIL/3840x2160"))

    def test_missing_original_audit_is_saved_and_cannot_claim_pass(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            prior = root / "preserved-earlier-evidence.json"
            prior.write_text('{"status":"PASS"}', encoding="utf-8")
            with patch("tools.neo_qa_hypergrid.inspect_lq001_direct_recordings.inspect",
                       side_effect=FileNotFoundError("Original QA movie was deleted")):
                result = inspect_and_record(self.args, root)
            self.assertEqual(result["status"], "ERROR")
            self.assertFalse(result["production_ready"])
            self.assertEqual(result["candidate_apk_sha256"], self.args.apk_sha256)
            self.assertEqual(result["error_type"], "FileNotFoundError")
            self.assertEqual(json.loads(Path(result["error_evidence"]).read_text()), result)
            self.assertEqual(prior.read_text(), '{"status":"PASS"}')

    def test_failed_audit_never_overwrites_a_previous_failure(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            with patch("tools.neo_qa_hypergrid.inspect_lq001_direct_recordings.inspect",
                       side_effect=ValueError("Candidate mismatch")):
                first = inspect_and_record(self.args, root)
                second = inspect_and_record(self.args, root)
            self.assertNotEqual(first["error_evidence"], second["error_evidence"])
            self.assertTrue(Path(first["error_evidence"]).is_file())
            self.assertTrue(Path(second["error_evidence"]).is_file())


if __name__ == "__main__":
    unittest.main()
